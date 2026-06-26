#!/usr/bin/env bash
# Finalize + shrink provisioner for the OL10 ebssurrogate finalize build (bootstrap/finalize-ol10.pkr.hcl).
# Runs as root over SSM on a builder booted FROM the raw Oracle OL10 import. It (1) finalizes the live
# root (ec2-user default + amazon-ssm-agent + dnf update), then (2) reproduces that finalized root onto
# the blank ROOT_GIB surrogate volume Packer attached, so Packer registers a var.volume_size AMI from it.
# Rationale for every surgery step (dd /boot nrext64, UUID cloning, LVM->plain, the gates) is in docs/finalize.md.
set -euo pipefail

ARCH="${ARCH:?set ARCH}"
ROOT_GIB="${ROOT_GIB:?set ROOT_GIB (surrogate size; = var.volume_size)}"

command -v parted >/dev/null || dnf -y install parted
command -v rsync  >/dev/null || dnf -y install rsync
[[ "$ARCH" = arm64 ]] && { command -v mkfs.fat >/dev/null || dnf -y install dosfstools; }
for tool in mkfs.xfs mkswap partprobe xfs_freeze blkid findmnt; do
  command -v "$tool" >/dev/null || { echo "MISSING required tool: $tool" >&2; exit 1; }
done

# === 1. finalize the live root (this content becomes the AMI after the rsync in step 3) ===
# default_user -> ec2-user (99- sorts AFTER Oracle's cloud.cfg.d/90_ol.cfg, which sets opc and would win).
cat >/etc/cloud/cloud.cfg.d/99-ec2-user.cfg <<'CFG'
system_info:
  default_user: {name: ec2-user, gecos: "EC2 Default User", sudo: ["ALL=(ALL) NOPASSWD:ALL"], groups: [adm, systemd-journal, wheel], shell: /bin/bash}
CFG
systemctl enable amazon-ssm-agent   # installed at launch by the template user_data; enable for the baked image
dnf -y update                       # latest errata (the raw base ships a few behind)
# kernel-install defers a freshly-installed kernel's initramfs to first boot; the fold shrinks BEFORE any
# boot, so build every installed kernel's initramfs NOW, or the dd below captures /boot with the new default
# kernel missing its initramfs (-> unbootable). The old two-step path got this from its pre-shrink boot.
dracut --force --regenerate-all

# === 2. locate the surrogate: Packer attached a blank ROOT_GIB volume. The builder root is the larger
#        raw base, so the surrogate is the blank disk of exactly ROOT_GIB (no VolumeId to match under Packer). ===
want=$((ROOT_GIB * 1024 * 1024 * 1024))
TGT=""
for _ in $(seq 1 30); do
  while read -r name size type; do
    [[ "$type" = disk && "$size" = "$want" ]] || continue
    [[ $(lsblk -rno NAME "/dev/$name" | wc -l) -eq 1 ]] || continue   # blank (no partitions)
    TGT="/dev/$name"; break
  done < <(lsblk -bdno NAME,SIZE,TYPE)
  [[ -n "$TGT" ]] && break
  sleep 3
done
[[ -n "$TGT" ]] || { echo "FATAL: no blank ${ROOT_GIB}GiB surrogate disk found" >&2; exit 1; }
echo "surrogate target: $TGT"

# fail-safe: thaw /boot if still frozen and unmount the target deepest-first on ANY exit, so a
# mid-surgery failure leaves the builder clean for Packer's volume detach (see docs/finalize.md).
cleanup_surgery() {
  xfs_freeze -u /boot 2>/dev/null || true
  for m in $(mount | awk '{print $3}' | grep '^/mnt/target' | sort -r); do
    umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null || true
  done
}
trap cleanup_surgery EXIT

# === 3. surgery: reproduce the finalized live root onto $TGT at ROOT_GIB ===
# --- introspect the source (the running root) ---
SRC_ROOT_DEV=$(findmnt -no SOURCE /)
SRC_ROOT_UUID=$(blkid -s UUID -o value "$SRC_ROOT_DEV")
SRC_BOOT_DEV=$(findmnt -no SOURCE /boot)
SRC_SWAP_DEV=$(swapon --show=NAME --noheadings | head -1 || true)
SRC_SWAP_UUID=$([[ -n "$SRC_SWAP_DEV" ]] && blkid -s UUID -o value "$SRC_SWAP_DEV" || true)
IS_LVM=$([[ "${SRC_ROOT_DEV#/dev/mapper/}" != "$SRC_ROOT_DEV" ]] && echo 1 || echo 0)
if [[ "$ARCH" = arm64 ]]; then
  SRC_ESP_DEV=$(findmnt -no SOURCE /boot/efi)
  FATID=$(blkid -s UUID -o value "$SRC_ESP_DEV" | tr -d -)
fi
echo "source: root=$SRC_ROOT_DEV ($SRC_ROOT_UUID) boot=$SRC_BOOT_DEV swap=${SRC_SWAP_UUID:-none} lvm=$IS_LVM"

# --- partition the target (layout + rationale in docs/finalize.md; root is the last, growable part) ---
# partition-name prefix: nvme needs a 'p' (nvme1n1p2), sd* does not (sdf2).
case "$TGT" in *[0-9]) PART="${TGT}p" ;; *) PART="$TGT" ;; esac
wipefs -a "$TGT" || true
parted -s "$TGT" mklabel gpt
if [[ "$ARCH" = arm64 ]]; then
  parted -s "$TGT" mkpart EFI  fat32      1MiB    201MiB
  parted -s "$TGT" set 1 esp on
  parted -s "$TGT" mkpart boot xfs        201MiB  1225MiB
  parted -s "$TGT" mkpart swap linux-swap 1225MiB 5321MiB
  parted -s "$TGT" mkpart root xfs        5321MiB 100%
  ESP_P=${PART}1
else
  parted -s "$TGT" mkpart bios_grub 1MiB 3MiB
  parted -s "$TGT" set 1 bios_grub on
  parted -s "$TGT" mkpart boot xfs        3MiB    1027MiB
  parted -s "$TGT" mkpart swap linux-swap 1027MiB 5123MiB
  parted -s "$TGT" mkpart root xfs        5123MiB 100%
fi
BOOT_P=${PART}2; SWAP_P=${PART}3; ROOT_P=${PART}4
partprobe "$TGT"; udevadm settle; sleep 2

# --- /boot: dd verbatim (GRUB can't read EL10 nrext64 XFS); swap + root: fresh fs, cloned UUIDs ---
# guard: never dd a source /boot larger than the target partition (silent truncation -> unbootable).
src_boot_sz=$(blockdev --getsize64 "$SRC_BOOT_DEV")
tgt_boot_sz=$(blockdev --getsize64 "$BOOT_P")
[[ "$src_boot_sz" -le "$tgt_boot_sz" ]] || { echo "FATAL: source /boot ${src_boot_sz}B > target /boot ${tgt_boot_sz}B; widen the boot partition" >&2; exit 1; }
xfs_freeze -f /boot; dd if="$SRC_BOOT_DEV" of="$BOOT_P" bs=4M conv=fsync; xfs_freeze -u /boot
if [[ -n "$SRC_SWAP_UUID" ]]; then mkswap -U "$SRC_SWAP_UUID" -L swap "$SWAP_P"; else mkswap -L swap "$SWAP_P"; fi
mkfs.xfs -f -m uuid="$SRC_ROOT_UUID" "$ROOT_P"
[[ "$ARCH" = arm64 ]] && mkfs.fat -F32 -n EFI -i "$FATID" "$ESP_P"
partprobe "$TGT"; udevadm settle

# --- mount target (-o nouuid: the same-UUID source fs is still mounted) + copy root ---
mkdir -p /mnt/target; mount -o nouuid "$ROOT_P" /mnt/target
mkdir -p /mnt/target/boot; mount -o nouuid "$BOOT_P" /mnt/target/boot
if [[ "$ARCH" = arm64 ]]; then mkdir -p /mnt/target/boot/efi; mount "$ESP_P" /mnt/target/boot/efi; fi
rsync -aHAXx --numeric-ids --exclude='/tmp/*' --exclude='/var/tmp/*' --exclude='/mnt/*' / /mnt/target/
[[ "$ARCH" = arm64 ]] && rsync -rt --no-perms --no-owner --no-group /boot/efi/ /mnt/target/boot/efi/

# --- LVM source -> plain root: rewrite the GRUB cmdline only (fstab is already by UUID) ---
if [[ "$IS_LVM" = 1 ]]; then
  for cfg in /mnt/target/boot/loader/entries/*.conf; do
    [[ -e "$cfg" ]] || continue
    sed -i -e "s#root=${SRC_ROOT_DEV}#root=UUID=${SRC_ROOT_UUID}#g" \
           -e "s#root=/dev/dm-[0-9]*#root=UUID=${SRC_ROOT_UUID}#g" \
           -e 's#rd\.lvm\.lv=[^ ]*##g' "$cfg"
  done
  sed -i 's#rd\.lvm\.lv=[^ ]*##g' /mnt/target/etc/default/grub
  # /etc/kernel/cmdline seeds FUTURE BLS entries (kernel-install / grubby); rewrite it too or
  # the next kernel update reintroduces the LVM root on the plain image. See docs/finalize.md.
  if [[ -f /mnt/target/etc/kernel/cmdline ]]; then
    sed -i -e "s#root=${SRC_ROOT_DEV}#root=UUID=${SRC_ROOT_UUID}#g" \
           -e "s#root=/dev/dm-[0-9]*#root=UUID=${SRC_ROOT_UUID}#g" \
           -e 's#rd\.lvm\.lv=[^ ]*##g' /mnt/target/etc/kernel/cmdline
  fi
fi

# --- fail-closed: target fstab root must be UUID=<cloned> or absent (see docs/finalize.md). A
#     /dev/mapper or bare-device pin would not resolve on the plain-partition target. ---
[[ -f /mnt/target/etc/fstab ]] || { echo "FATAL: /mnt/target/etc/fstab missing after rsync" >&2; exit 1; }
root_spec=$(awk '$1 !~ /^#/ && $2 == "/" {print $1; exit}' /mnt/target/etc/fstab || true)
case "${root_spec:-}" in
  ""|"UUID=$SRC_ROOT_UUID") : ;;
  *) echo "FATAL: target fstab pins / to '$root_spec' (expected UUID=$SRC_ROOT_UUID or none); refusing to produce an image that may fail to mount /" >&2; exit 1 ;;
esac

# fail-closed: /boot, /boot/efi, and swap must also resolve on the fresh-GPT plain target. The
# surgery clones their filesystem UUIDs (dd /boot, mkswap -U, mkfs.fat -i), so a UUID= or LABEL=
# pin survives but a PARTUUID (new GPT) or bare-device pin would not. Absence is fine.
while read -r spec where; do
  case "$spec" in
    UUID=*|LABEL=*) ;;
    *) echo "FATAL: target fstab pins $where by '$spec' (won't resolve on the fresh-GPT plain target; expected UUID= or LABEL=)" >&2; exit 1 ;;
  esac
done < <(awk '$1 !~ /^#/ && ($2=="/boot"||$2=="/boot/efi"||$3=="swap"){print $1, ($3=="swap"?"swap":$2)}' /mnt/target/etc/fstab)

# --- bootloader: arm64 uses the verbatim ESP; x86_64 reinstalls grub2 with os-prober off ---
if [[ "$ARCH" = x86_64 ]]; then
  for f in proc sys dev dev/pts run; do mountpoint -q "/mnt/target/$f" || mount --bind "/$f" "/mnt/target/$f"; done
  if grep -q '^GRUB_DISABLE_OS_PROBER=' /mnt/target/etc/default/grub; then
    sed -i 's/^GRUB_DISABLE_OS_PROBER=.*/GRUB_DISABLE_OS_PROBER=true/' /mnt/target/etc/default/grub
  else echo 'GRUB_DISABLE_OS_PROBER=true' >> /mnt/target/etc/default/grub; fi
  chroot /mnt/target /bin/bash -c "grub2-install --target=i386-pc --recheck $TGT && grub2-mkconfig -o /boot/grub2/grub.cfg" >/dev/null
  # fail-closed: any surviving LVM reference in ANY cmdline source -- including the future-kernel
  # seeds /etc/kernel/cmdline and grubenv -- would register a base that boots now but breaks on
  # the next kernel update. -a so the binary-ish grubenv is searched as text.
  if grep -aREl 'vg_main|rd\.lvm\.lv|root=/dev/(mapper|dm-)' \
       /mnt/target/boot/loader/entries/ /mnt/target/boot/grub2/grub.cfg \
       /mnt/target/boot/grub2/grubenv /mnt/target/etc/default/grub \
       /mnt/target/etc/kernel/cmdline 2>/dev/null; then
    echo "FATAL: LVM references survive in the x86_64 boot config; refusing to produce a non-booting image" >&2; exit 1
  fi
  # positive gate: the effective cmdline must pin root by the cloned UUID, not LVM.
  if ! grep -aqrs "root=UUID=${SRC_ROOT_UUID}" /mnt/target/boot/loader/entries/ /mnt/target/etc/kernel/cmdline; then
    echo "FATAL: no root=UUID=${SRC_ROOT_UUID} in the x86_64 boot cmdline; refusing a possibly-unbootable image" >&2; exit 1
  fi
fi

# --- de-instance for a clean AMI (cloud-init + machine-id + host keys, as provision.sh does) ---
rm -rf /mnt/target/var/lib/cloud/instances/* /mnt/target/var/lib/cloud/instance /mnt/target/var/lib/cloud/sem 2>/dev/null || true
: > /mnt/target/etc/machine-id
rm -f /mnt/target/etc/ssh/ssh_host_*_key /mnt/target/etc/ssh/ssh_host_*_key.pub /mnt/target/var/lib/systemd/random-seed 2>/dev/null || true
# strip the builder's injected SSH key; cloud-init re-injects the consumer's key on first boot.
rm -f /mnt/target/home/ec2-user/.ssh/authorized_keys /mnt/target/root/.ssh/authorized_keys 2>/dev/null || true
touch /mnt/target/.autorelabel

# --- unmount deepest-first (a --bind /run pulls in autofs/credential submounts) ---
sync
for m in $(mount | awk '{print $3}' | grep '^/mnt/target' | sort -r); do umount "$m" 2>/dev/null || umount -l "$m"; done
echo "FINALIZE_SURGERY_OK root_uuid=$SRC_ROOT_UUID lvm_converted=$IS_LVM target=$TGT"
