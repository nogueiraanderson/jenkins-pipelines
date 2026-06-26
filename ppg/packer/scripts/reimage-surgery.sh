#!/usr/bin/env bash
# Shrink the booted prebase root onto Packer's blank ROOT_GIB surrogate (amazon-ebssurrogate
# provisioner, reimage-ol10.pkr.hcl). Runs as root on the builder. Rationale: docs/reimage.md.
set -euo pipefail

ARCH="${ARCH:?set ARCH}"
ROOT_GIB="${ROOT_GIB:?set ROOT_GIB (= var.volume_size)}"

command -v parted >/dev/null || dnf -y install parted
command -v rsync  >/dev/null || dnf -y install rsync
[[ "${ARCH}" = arm64 ]] && { command -v mkfs.fat >/dev/null || dnf -y install dosfstools; }

for tool in mkfs.xfs mkswap partprobe xfs_freeze blkid findmnt; do
  command -v "${tool}" >/dev/null || { echo "MISSING required tool: ${tool}" >&2; exit 1; }
done

# find the blank ROOT_GIB surrogate Packer attached (by size; no VolumeId to match)
want=$((ROOT_GIB * 1024 * 1024 * 1024))
TGT=""

for _ in $(seq 1 30); do
  while read -r name size type; do
    [[ "${type}" = disk && "${size}" = "${want}" ]] || continue
    [[ $(lsblk -rno NAME "/dev/${name}" | wc -l) -eq 1 ]] || continue   # blank = no partitions
    TGT="/dev/${name}"; break
  done < <(lsblk -bdno NAME,SIZE,TYPE)
  [[ -n "${TGT}" ]] && break
  sleep 3
done

[[ -n "${TGT}" ]] || { echo "FATAL: no blank ${ROOT_GIB}GiB surrogate disk found" >&2; exit 1; }
echo "surrogate target: ${TGT}"

# fail-safe: thaw /boot + unmount the target on any exit
cleanup_surgery() {
  xfs_freeze -u /boot 2>/dev/null || true
  for m in $(mount | awk '{print $3}' | grep '^/mnt/target' | sort -r); do
    umount "${m}" 2>/dev/null || umount -l "${m}" 2>/dev/null || true
  done
}
trap cleanup_surgery EXIT

# introspect the source (the running root)
SRC_ROOT_DEV=$(findmnt -no SOURCE /)
SRC_ROOT_UUID=$(blkid -s UUID -o value "${SRC_ROOT_DEV}")
SRC_BOOT_DEV=$(findmnt -no SOURCE /boot)
SRC_SWAP_DEV=$(swapon --show=NAME --noheadings | head -1 || true)
SRC_SWAP_UUID=$([[ -n "${SRC_SWAP_DEV}" ]] && blkid -s UUID -o value "${SRC_SWAP_DEV}" || true)
IS_LVM=$([[ "${SRC_ROOT_DEV#/dev/mapper/}" != "${SRC_ROOT_DEV}" ]] && echo 1 || echo 0)

if [[ "${ARCH}" = arm64 ]]; then
  SRC_ESP_DEV=$(findmnt -no SOURCE /boot/efi)
  FATID=$(blkid -s UUID -o value "${SRC_ESP_DEV}" | tr -d -)
fi

echo "source: root=${SRC_ROOT_DEV} (${SRC_ROOT_UUID}) boot=${SRC_BOOT_DEV} swap=${SRC_SWAP_UUID:-none} lvm=${IS_LVM}"

# partition the surrogate (offsets in docs/reimage.md; root is the last, growable partition)
case "${TGT}" in *[0-9]) PART="${TGT}p" ;; *) PART="${TGT}" ;; esac # nvme needs a 'p' suffix, sd* does not
wipefs -a "${TGT}" || true
parted -s "${TGT}" mklabel gpt

if [[ "${ARCH}" = arm64 ]]; then
  parted -s "${TGT}" mkpart EFI  fat32      1MiB    201MiB
  parted -s "${TGT}" set 1 esp on
  parted -s "${TGT}" mkpart boot xfs        201MiB  1225MiB
  parted -s "${TGT}" mkpart swap linux-swap 1225MiB 5321MiB
  parted -s "${TGT}" mkpart root xfs        5321MiB 100%
  ESP_P=${PART}1
else
  parted -s "${TGT}" mkpart bios_grub 1MiB 3MiB
  parted -s "${TGT}" set 1 bios_grub on
  parted -s "${TGT}" mkpart boot xfs        3MiB    1027MiB
  parted -s "${TGT}" mkpart swap linux-swap 1027MiB 5123MiB
  parted -s "${TGT}" mkpart root xfs        5123MiB 100%
fi

BOOT_P=${PART}2; SWAP_P=${PART}3; ROOT_P=${PART}4
partprobe "${TGT}"; udevadm settle; sleep 2

# /boot: dd verbatim (GRUB can't read EL10 nrext64 xfs); root/swap: fresh fs, cloned UUIDs
src_boot_sz=$(blockdev --getsize64 "${SRC_BOOT_DEV}")
tgt_boot_sz=$(blockdev --getsize64 "${BOOT_P}")
[[ "${src_boot_sz}" -le "${tgt_boot_sz}" ]] || { echo "FATAL: source /boot ${src_boot_sz}B > target ${tgt_boot_sz}B" >&2; exit 1; }
xfs_freeze -f /boot; dd if="${SRC_BOOT_DEV}" of="${BOOT_P}" bs=4M conv=fsync; xfs_freeze -u /boot
if [[ -n "${SRC_SWAP_UUID}" ]]; then mkswap -U "${SRC_SWAP_UUID}" -L swap "${SWAP_P}"; else mkswap -L swap "${SWAP_P}"; fi
mkfs.xfs -f -m uuid="${SRC_ROOT_UUID}" "${ROOT_P}"
[[ "${ARCH}" = arm64 ]] && mkfs.fat -F32 -n EFI -i "${FATID}" "${ESP_P}"
partprobe "${TGT}"; udevadm settle

# mount surrogate (-o nouuid: same-UUID source still mounted) + copy root
mkdir -p /mnt/target; mount -o nouuid "${ROOT_P}" /mnt/target
mkdir -p /mnt/target/boot; mount -o nouuid "${BOOT_P}" /mnt/target/boot
if [[ "${ARCH}" = arm64 ]]; then mkdir -p /mnt/target/boot/efi; mount "${ESP_P}" /mnt/target/boot/efi; fi
rsync -aHAXx --numeric-ids --exclude='/tmp/*' --exclude='/var/tmp/*' --exclude='/mnt/*' / /mnt/target/
[[ "${ARCH}" = arm64 ]] && rsync -rt --no-perms --no-owner --no-group /boot/efi/ /mnt/target/boot/efi/

# LVM source -> plain root: rewrite the GRUB cmdline + /etc/kernel/cmdline (future kernels); fstab is by UUID
if [[ "${IS_LVM}" = 1 ]]; then
  for cfg in /mnt/target/boot/loader/entries/*.conf /mnt/target/etc/kernel/cmdline; do
    [[ -e "${cfg}" ]] || continue
    sed -i -e "s#root=${SRC_ROOT_DEV}#root=UUID=${SRC_ROOT_UUID}#g" \
           -e "s#root=/dev/dm-[0-9]*#root=UUID=${SRC_ROOT_UUID}#g" \
           -e 's#rd\.lvm\.lv=[^ ]*##g' "${cfg}"
  done

  sed -i 's#rd\.lvm\.lv=[^ ]*##g' /mnt/target/etc/default/grub
fi

# gate: fstab root must be UUID=<cloned> or absent (docs/reimage.md)
[[ -f /mnt/target/etc/fstab ]] || { echo "FATAL: /mnt/target/etc/fstab missing after rsync" >&2; exit 1; }
root_spec=$(awk '$1 !~ /^#/ && $2 == "/" {print $1; exit}' /mnt/target/etc/fstab || true)

case "${root_spec:-}" in
  ""|"UUID=${SRC_ROOT_UUID}") : ;;
  *) echo "FATAL: target fstab pins / to '${root_spec}' (expected UUID=${SRC_ROOT_UUID} or none)" >&2; exit 1 ;;
esac

# gate: fstab /boot, /boot/efi, swap must be UUID=/LABEL= or absent (PARTUUID/device won't resolve on the fresh GPT)
while read -r spec where; do
  case "${spec}" in
    UUID=*|LABEL=*) ;;
    *) echo "FATAL: target fstab pins ${where} by '${spec}' (expected UUID= or LABEL=)" >&2; exit 1 ;;
  esac
done < <(awk '$1 !~ /^#/ && ($2=="/boot"||$2=="/boot/efi"||$3=="swap"){print $1, ($3=="swap"?"swap":$2)}' /mnt/target/etc/fstab)

# bootloader: arm64 uses the verbatim ESP; x86_64 reinstalls grub2 (os-prober off)
if [[ "${ARCH}" = x86_64 ]]; then
  for f in proc sys dev dev/pts run; do mountpoint -q "/mnt/target/${f}" || mount --bind "/${f}" "/mnt/target/${f}"; done

  if grep -q '^GRUB_DISABLE_OS_PROBER=' /mnt/target/etc/default/grub; then
    sed -i 's/^GRUB_DISABLE_OS_PROBER=.*/GRUB_DISABLE_OS_PROBER=true/' /mnt/target/etc/default/grub
  else echo 'GRUB_DISABLE_OS_PROBER=true' >> /mnt/target/etc/default/grub; fi

  chroot /mnt/target /bin/bash -c "grub2-install --target=i386-pc --recheck ${TGT} && grub2-mkconfig -o /boot/grub2/grub.cfg" >/dev/null

  # gate: no LVM ref may survive in any cmdline source (incl /etc/kernel/cmdline + grubenv); -a for binary grubenv
  if grep -aREl 'vg_main|rd\.lvm\.lv|root=/dev/(mapper|dm-)' \
       /mnt/target/boot/loader/entries/ /mnt/target/boot/grub2/grub.cfg \
       /mnt/target/boot/grub2/grubenv /mnt/target/etc/default/grub \
       /mnt/target/etc/kernel/cmdline 2>/dev/null; then
    echo "FATAL: LVM references survive in the x86_64 boot config" >&2; exit 1
  fi

  # gate: the cmdline must pin root by the cloned UUID
  if ! grep -aqrs "root=UUID=${SRC_ROOT_UUID}" /mnt/target/boot/loader/entries/ /mnt/target/etc/kernel/cmdline; then
    echo "FATAL: no root=UUID=${SRC_ROOT_UUID} in the x86_64 boot cmdline" >&2; exit 1
  fi
fi

# de-instance for a clean AMI (cloud-init + machine-id + host/build keys)
rm -rf /mnt/target/var/lib/cloud/instances/* /mnt/target/var/lib/cloud/instance /mnt/target/var/lib/cloud/sem 2>/dev/null || true
: > /mnt/target/etc/machine-id
rm -f /mnt/target/etc/ssh/ssh_host_*_key /mnt/target/etc/ssh/ssh_host_*_key.pub /mnt/target/var/lib/systemd/random-seed 2>/dev/null || true
rm -f /mnt/target/home/ec2-user/.ssh/authorized_keys /mnt/target/root/.ssh/authorized_keys 2>/dev/null || true
touch /mnt/target/.autorelabel

# unmount deepest-first
sync
for m in $(mount | awk '{print $3}' | grep '^/mnt/target' | sort -r); do umount "${m}" 2>/dev/null || umount -l "${m}"; done
echo "REIMAGE_SURGERY_OK root_uuid=${SRC_ROOT_UUID} lvm_converted=${IS_LVM}"
