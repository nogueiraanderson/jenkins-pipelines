# PPG Oracle Linux AMI factory (PG-2353)

Builds the Oracle Linux package-test target AMIs that PG release testing runs
against, so we stop hand-maintaining them (launch base, `dnf update`, snapshot).

## Why this exists

There is no off-the-shelf AWS image that is all of: genuine Oracle Linux,
OL8/OL9/OL10, x86_64 **and** arm64, free of software fees, and maintained.

- Oracle stopped publishing official AWS AMIs. The newest public Oracle images
  (owner `131827586825`) are OL8.9 / OL9.3 from Feb 2024 and were all deprecated
  on 2026-02-20. There is no official OL8.10, OL9.7, or OL10 AMI on AWS. (Same
  deprecation tracked fleet-wide in PS-10909.)
- Marketplace vendors (ProComputers, SupportedImages) add a per-hour software
  fee, require a per-account subscription that blocks unattended fleet launches,
  and are x86_64-only.
- AlmaLinux is free + current + both-arch, but it is an EL clone, fine as
  supplemental smoke coverage, not a replacement for the Oracle Linux gate.

So we bake our own, on a schedule, into account `119175775298` / eu-central-1.

## Two build paths

| Path | Covers | Mechanism |
|------|--------|-----------|
| **Refresh** (`oracle-linux.pkr.hcl`) | OL8, OL9 (x86_64 + arm64); OL10 once a base exists | `amazon-ebs`: launch latest self-owned base of same major+arch, `dnf update`, validate (fail-closed), snapshot. Chains forward. |
| **Bootstrap** (`scripts/bootstrap-ol10.sh`) | OL10 greenfield (x86_64 + arm64) | EC2-native: `dnf --installroot` an OL10 rootfs from Oracle public yum onto a second EBS volume, install grub2+cloud-init, snapshot, `register-image`. Needed because AWS VM Import does not support arm64 and Oracle ships aarch64 only as KVM qcow2. |

## Usage (refresh)

```bash
export AWS_PROFILE=percona-dev-admin
packer init .
packer build -var os_major=9 -var arch=x86_64 .
packer build -var os_major=9 -var arch=arm64  .
packer build -var os_major=8 -var arch=x86_64 .
packer build -var os_major=8 -var arch=arm64  .
```

Each build registers `OL<major>-<arch>-<UTCstamp>` tagged `os=oraclelinux,
os_major, arch, source=factory` with **`role=ppg-candidate`**, then
`scripts/smoke-boot.sh` fresh-boot-tests it and, on success, **promotes** it to
`role=ppg-package-test` (deregistering it on failure). Consumers and the next
build's source filter select only `role=ppg-package-test`, so a non-booting
image is never selectable and AMI IDs are never pinned. There is a single
"latest" mechanism: newest `role=ppg-package-test` AMI by `CreationDate` (no SSM
parameter).

## Consumer lookup (replaces hardcoded IDs)

`vars/moleculeEnvPPG.groovy` resolves each Oracle target at pipeline time:

```bash
export ami_ol9_x86_64=$(aws ec2 describe-images --region eu-central-1 --owners self \
  --filters Name=tag:role,Values=ppg-package-test Name=tag:os,Values=oraclelinux \
            Name=tag:os_major,Values=9 Name=tag:arch,Values=x86_64 \
            Name=state,Values=available \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)
```

## Validation gates (fail-closed)

`scripts/validate.sh` runs as the last provisioner; any failure aborts the build
so a broken/mislabeled image is never registered:

1. `/etc/oracle-release` major matches the target (fidelity).
2. `uname -m` matches the target arch.
3. `ol<major>_codeready_builder` repo is defined (PG CRB deps resolvable).
4. `cloud-init` present.
5. SELinux `enforcing` in `/etc/selinux/config` (warn-only).
6. `dnf makecache` succeeds (repos healthy).

Then it de-instances the image (host keys, authorized_keys) as its final action.

## Promotion (fresh-boot smoke) — `scripts/smoke-boot.sh`

`validate.sh` runs on the *builder* before snapshot; it cannot prove the
*resulting* AMI boots. `smoke-boot.sh <ami> <major> <arch>` closes that gap: it
launches the candidate, waits for 2/2 status, SSHes as `ec2-user` (proving
cloud-init re-injected the launch key after bake deleted `authorized_keys`),
asserts `cloud-init status --wait` is done and the OS major/arch are right, then
installs `percona-release` + a PPG server package. On success it promotes
(`role=ppg-package-test`); on any failure it deregisters the candidate.

## OL10 status

OL10 is a declared PPG platform (PPG 17 yum docs enable `ol10_codeready_builder`)
but exists nowhere in CI today. It needs the bootstrap path first (see
`scripts/bootstrap-ol10.sh`); once a base OL10 AMI per arch is registered, the
refresh template takes over (raise the `os_major` validation to allow `10`).
Known landmines: OL10 x86_64 requires the `x86-64-v3` microarch (no EC2 nitro
issue, but qemu emulation cannot run it); arm64 has no AWS VM-import path.
