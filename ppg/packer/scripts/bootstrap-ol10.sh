#!/usr/bin/env bash
# PG-2353 OL10 bootstrap (SPIKE SCAFFOLD - not yet validated end-to-end).
#
# OL10 has no AWS base AMI to refresh from, and AWS VM Import/Export does not
# support arm64 (Oracle ships aarch64 only as KVM qcow2), so neither the refresh
# template nor a plain image-import works for the arm64 target. This builds an
# OL10 root filesystem natively on an EC2 builder of the right architecture,
# from Oracle's public yum repos, then snapshots + register-image.
#
# Run ON an EL10-compatible builder instance (AlmaLinux 10 or RHEL 10) of the
# SAME arch as the target, with a blank second EBS volume attached.
#
# Once a base OL10 AMI per arch is registered by this script, ongoing
# maintenance switches to oracle-linux.pkr.hcl (raise its os_major validation to
# allow "10"); this bootstrap only runs to create the first OL10 base per arch.
#
# Usage:  sudo ./bootstrap-ol10.sh <target-device e.g. /dev/nvme1n1>
set -euxo pipefail

DEV="${1:?usage: bootstrap-ol10.sh <device>}"
ARCH="$(uname -m)"                       # x86_64 | aarch64
MNT=/mnt/ol10
RELEASEVER=10

# Oracle public yum repos for OL10 (no auth, free redistribution).
# Verified live: BaseOS has a /latest/ segment, AppStream and CodeReady do NOT.
OL_BASE="https://yum.oracle.com/repo/OracleLinux/OL10/baseos/latest/${ARCH}"
OL_APP="https://yum.oracle.com/repo/OracleLinux/OL10/appstream/${ARCH}"
OL_CRB="https://yum.oracle.com/repo/OracleLinux/OL10/codeready/builder/${ARCH}"

# --- 1. partition + filesystem (GPT + UEFI ESP + root) ---
parted -s "$DEV" mklabel gpt
parted -s "$DEV" mkpart ESP fat32 1MiB 513MiB
parted -s "$DEV" set 1 esp on
parted -s "$DEV" mkpart root xfs 513MiB 100%
# Partition suffix: nvme/loop use pN (/dev/nvme1n1p1), sd/xvd use N (/dev/sdf1).
case "$DEV" in *nvme*|*loop*) PS=p ;; *) PS="" ;; esac
ESP="${DEV}${PS}1"; ROOT="${DEV}${PS}2"
mkfs.vfat -F32 "$ESP"
mkfs.xfs -f "$ROOT"
mkdir -p "$MNT"; mount "$ROOT" "$MNT"
mkdir -p "$MNT/boot/efi"; mount "$ESP" "$MNT/boot/efi"

# --- 2. install OL10 rootfs from Oracle public yum ---
GPGKEY="https://yum.oracle.com/RPM-GPG-KEY-oracle-ol10"
dnf -y --installroot="$MNT" --releasever="$RELEASEVER" \
    --repofrompath="ol10-baseos,$OL_BASE" \
    --repofrompath="ol10-appstream,$OL_APP" \
    --repofrompath="ol10-codeready,$OL_CRB" \
    --setopt=ol10-baseos.gpgcheck=1   --setopt=ol10-baseos.gpgkey="$GPGKEY" \
    --setopt=ol10-appstream.gpgcheck=1 --setopt=ol10-appstream.gpgkey="$GPGKEY" \
    --setopt=ol10-codeready.gpgcheck=1 --setopt=ol10-codeready.gpgkey="$GPGKEY" \
    install \
      @core oraclelinux-release oraclelinux-release-el10 \
      kernel grub2-efi-"$([ "$ARCH" = aarch64 ] && echo aa64 || echo x64)" \
      grub2-tools shim-* efibootmgr \
      cloud-init cloud-utils-growpart \
      NetworkManager openssh-server python3 chrony selinux-policy-targeted

# --- 3. base config inside the new root ---
for fs in proc sys dev dev/pts; do mount --bind "/$fs" "$MNT/$fs"; done

cat > "$MNT/etc/fstab" <<EOF
UUID=$(blkid -s UUID -o value "$ROOT")     /         xfs   defaults        0 0
UUID=$(blkid -s UUID -o value "$ESP")      /boot/efi vfat  umask=0077      0 2
EOF

# cloud-init for EC2: default user ec2-user, grow root, regenerate host keys.
cat > "$MNT/etc/cloud/cloud.cfg.d/10-ec2.cfg" <<'EOF'
system_info:
  default_user:
    name: ec2-user
    sudo: ["ALL=(ALL) NOPASSWD:ALL"]
datasource_list: [ Ec2 ]
EOF

chroot "$MNT" /bin/bash -euxo pipefail <<'CHROOT'
  systemctl enable sshd cloud-init cloud-init-local cloud-config cloud-final NetworkManager chronyd
  # SELinux enforcing + relabel on first boot
  sed -i 's/^SELINUX=.*/SELINUX=enforcing/' /etc/selinux/config
  touch /.autorelabel
  # bootloader: UEFI (both x86_64 and arm64 register as boot-mode uefi)
  grub2-mkconfig -o /boot/grub2/grub.cfg
  # ensure a root password is locked; ec2-user via cloud-init only
  passwd -l root || true
CHROOT

for fs in dev/pts dev sys proc; do umount "$MNT/$fs"; done
umount "$MNT/boot/efi"; umount "$MNT"

cat <<EOF

BOOTSTRAP rootfs built on ${DEV} (arch=${ARCH}).
NEXT (run from the builder, then validate):
  1. aws ec2 create-snapshot --volume-id <vol-of-${DEV}> --description "OL10 ${ARCH} bootstrap"
  2. aws ec2 register-image --name "OL10-$([ "$ARCH" = aarch64 ] && echo arm64 || echo x86_64)-\$(date -u +%Y%m%d-%H%M%S)" \\
       --architecture $([ "$ARCH" = aarch64 ] && echo arm64 || echo x86_64) \\
       --boot-mode uefi --root-device-name /dev/sda1 --ena-support \\
       --block-device-mappings 'DeviceName=/dev/sda1,Ebs={SnapshotId=<snap>,VolumeType=gp3,DeleteOnTermination=true}'
  3. Boot a t3/t4g from the new AMI, SSH as ec2-user, run validate.sh with OS_MAJOR=10.
EOF
echo "BOOTSTRAP-OL10 SCAFFOLD COMPLETE (end-to-end boot not yet validated - this is the spike)"
