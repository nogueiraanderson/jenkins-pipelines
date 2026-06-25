# OL10 finalize (bootstrap shrink via amazon-ebssurrogate)

How the OL10 lineage-root base is finalized AND shrunk to `var.volume_size` in a
single Packer build, and why each non-obvious step is the way it is. The provisioner
(`scripts/finalize-surgery.sh`) carries only terse pointers back here.

## Why this exists

The OL10 lineage starts from Oracle's official cloud image, whose root is larger
than the `var.volume_size` (20 GiB) that OL8/OL9 use. Two AWS/filesystem limits
mean the [refresh](../README.md) path cannot launch (let alone shrink) it:

- EBS cannot restore a volume smaller than its source snapshot.
- XFS cannot shrink in place.

So the finalize build uses Packer's `amazon-ebssurrogate` builder, which registers
the AMI from a **surrogate volume** sized at `var.volume_size` rather than from the
builder's own (large) root. A file-level copy onto that surrogate is the only way
to land the content on a smaller disk. OL8/OL9 never need this: their bases are
already at `var.volume_size`, so they live on the refresh path alone.

## Where it fits

```
bootstrap-ol10          -> import Oracle's image (AWS CLI) + register the raw base
finalize (ebssurrogate) -> candidate at var.volume_size (role=ppg-ol10-candidate)
bootstrap-ol10-verify   -> two-size boot + smoke + size gate -> promote role=ppg-package-test
refresh                 -> sustains var.volume_size from here (each build is the next source)
```

The finalize is **one-time per arch** (the refresh sustains the size afterward).
Re-run it only when a fresh oversized source is re-bootstrapped (FORCE rebuild, or
a new Oracle image), or when `var.volume_size` is reduced further (the refresh can
grow or hold a root, never shrink below the current snapshot).

## The build (`bootstrap/finalize-ol10.pkr.hcl`)

`amazon-ebssurrogate` launches a builder FROM the raw base, attaches a blank
`var.volume_size` surrogate (`launch_block_device_mappings`, `/dev/sdf`), runs
`finalize-surgery.sh`, then snapshots the surrogate and registers the AMI from it
(`ami_root_device`, `source_device_name = /dev/sdf`). Packer owns the instance,
volume, snapshot, register, and **cleanup** lifecycle, so there is no hand-rolled
orchestration to recover from. The raw base's own root is `delete_on_termination`,
so the builder leaves nothing behind.

`finalize-surgery.sh` runs as a provisioner over Session Manager and does two
things in order:

1. **Finalize the live root** (so the finalized content is what the surrogate
   captures): default user -> `ec2-user`, enable `amazon-ssm-agent`, `dnf -y update`.
2. **Reproduce that root onto the surrogate at `var.volume_size`** (the surgery below).

## Surgery internals

### Surrogate discovery

Packer creates the surrogate volume, so the surgery cannot match it by VolumeId.
It finds the blank disk of exactly `ROOT_GIB` GiB (the builder root is the larger
raw base, so size disambiguates) with no partitions, polling for the Nitro device
to settle.

### Partition layout

Root is always the last, growable partition (cloud-init `growpart` expands it on
first boot). Offsets (MiB):

| | arm64 (UEFI) | x86_64 (BIOS) |
|---|---|---|
| p1 | ESP 1-201 | bios_grub 1-3 |
| p2 | boot 201-1225 | boot 3-1027 |
| p3 | swap 1225-5321 | swap 1027-5123 |
| p4 | root 5321-100% | root 5123-100% |

The non-root overhead (ESP/bios + boot + swap) is what `bootstrap-ol10-verify`
derives its post-`growpart` floor from, so the check never drifts if this table
changes.

### `/boot` is `dd`'d verbatim, not `mkfs` + copy

A fresh `mkfs.xfs` on EL10 emits an XFS on-disk format (`nrext64`) that GRUB
cannot read, leaving the firmware unable to find `/boot`. `dd` preserves the
source format and its UUID. The kernel reads any XFS, so only `/boot` (and the
ESP) must stay GRUB-readable; the root filesystem is a fresh `mkfs`.

### Cloned UUIDs

Every filesystem UUID is cloned onto the surrogate (`mkfs.xfs -m uuid=...`,
`mkswap -U`, `mkfs.fat -i`), so `/etc/fstab` and the GRUB cmdline resolve
unchanged. The surrogate is mounted `-o nouuid` because the source filesystem with
the same UUID is still mounted on the builder.

### LVM source -> plain-partition root (x86_64)

The x86_64 base is LVM-rooted. A second VG of the same name cannot coexist on the
builder, so root is converted to a plain partition. The GRUB cmdline and the
future-kernel seed `/etc/kernel/cmdline` are rewritten (`root=/dev/mapper/...`,
`rd.lvm.lv=...` -> `root=UUID=...`); fstab is already by UUID. `os-prober` is
disabled so `grub2-mkconfig` does not graft the builder's own root.

## Fail-closed gates

Every gate refuses to produce a possibly-unbootable or wrongly-sized image:

- **/boot size guard** (`surgery`): never `dd` a source `/boot` larger than the target partition (silent truncation -> unbootable).
- **fstab-root invariant** (`surgery`): the target `/etc/fstab` root must be `UUID=<cloned>` or absent (root via cmdline). A `/dev/mapper` or bare-device pin would not resolve on the plain-partition target.
- **fstab /boot, /boot/efi, swap invariant** (`surgery`): each must be `UUID=`/`LABEL=` or absent; a PARTUUID (new GPT) or bare-device pin would not resolve.
- **surviving-LVM grep** (`surgery`, x86_64): abort if any `vg_main` / `rd.lvm.lv` / `root=/dev/(mapper|dm-)` reference survives in any cmdline source, including the future-kernel seeds `/etc/kernel/cmdline` and `grubenv` (a clean immediate boot can still regress on the next kernel install otherwise).
- **root=UUID positive gate** (`surgery`, x86_64): abort unless the effective cmdline pins root by the cloned filesystem UUID.
- **size gate** (`verify`): never promote a base whose root snapshot exceeds `var.volume_size`, or the refresh could not launch it.
- **cross-env gate** (`verify`): never promote a candidate whose `factory_env` tag does not match the requested env (a test candidate can never reach the prod role by an omitted/wrong arg).
- **two-size boot + smoke** (`verify`): boot at `var.volume_size` and 30 GiB, assert `growpart` grew root, then a fresh-boot smoke (install) before promotion.

## Cleanup and recovery

Packer owns the build's instance, surrogate volume, snapshot, and AMI lifecycle,
including cleanup on failure. `finalize-surgery.sh` additionally traps EXIT to thaw
`/boot` if still frozen and unmount `/mnt/target` deepest-first, so a mid-surgery
failure leaves the builder clean for Packer's detach. An orphaned candidate from a
failed `verify` (role `ppg-ol10-candidate`, never consumed) is reaped by
`just prune-stale`.
