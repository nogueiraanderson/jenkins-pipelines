#!/usr/bin/env bash
# Re-image the current OL10 base for an arch onto a smaller root volume (one-time lineage shrink).
# Launch a builder FROM the base, copy its root onto a fresh ROOT_GIB volume via reimage-surgery.sh,
# then snapshot + register a candidate AMI (non-consumed role until reimage-ol10-verify promotes it).
# Why this is needed + the full flow: docs/reimage.md.
#
#   Usage:  ARCH=x86_64 ./reimage-ol10.sh   |   ARCH=arm64 ./reimage-ol10.sh
# Identity (tags/roles/subnet/key/SG/size/env) is passed in by the `reimage-ol10` justfile recipe.
set -euo pipefail

ARCH="${ARCH:?set ARCH=arm64|x86_64}"
REGION="${REGION:-eu-central-1}"
ROOT_GIB="${ROOT_GIB:-20}"                            # target root; must match var.volume_size
ENV="${ENV:-prod}"                                    # prod | test (test = isolated tags)
BILLING_TAG="${BILLING_TAG:-ppg-ami-factory}"
OS_NAME="${OS_NAME:-oraclelinux}"
ROLE_PROMOTED="${ROLE_PROMOTED:-ppg-package-test}"    # the consumed base; re-image FROM it when present
ROLE_PREBASE="${ROLE_PREBASE:-ppg-ol10-prebase}"      # greenfield bootstrap output, not yet shrunk/consumed
SUBNET="${SUBNET:?set SUBNET}"
BUILDER_PROFILE="${BUILDER_PROFILE:?set BUILDER_PROFILE}"
BOOTTEST_KEY="${BOOTTEST_KEY:?set BOOTTEST_KEY (path to .pem)}"
BOOTTEST_SG="${BOOTTEST_SG:?set BOOTTEST_SG (group name)}"
FORCE="${FORCE:-0}"
BASE_AMI="${BASE_AMI:-}"                              # optional: re-image THIS exact base, not the newest
HERE="$(cd "$(dirname "$0")" && pwd)"

case "$ARCH" in
  arm64)  EC2ARCH=arm64;  INST=t4g.large; BOOTMODE=uefi ;;
  x86_64) EC2ARCH=x86_64; INST=t3.large;  BOOTMODE=legacy-bios ;;
  *) echo "ARCH must be arm64 or x86_64" >&2; exit 1 ;;
esac
if [ "$ENV" = test ]; then CAND_ROLE="ppg-reimage-test-candidate"; SRC_TAG="factory-test"; F_ENV="test"; NPFX="TEST-OL"
else CAND_ROLE="ppg-reimage-candidate"; SRC_TAG="factory"; F_ENV="prod"; NPFX="OL"; fi
TS="$(date -u +%Y%m%d-%H%M%S)"; NAME="${NPFX}10-${ARCH}-reimage-${TS}-$$"   # $$ avoids a same-second same-arch recovery-tag collision

_describe_newest() {  # $@ = --filters ... ; fail-closed (a describe failure is never "absent")
  local out a
  for a in 1 2 3; do
    if out=$(aws ec2 describe-images --region "$REGION" --owners self "$@" \
              --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text); then
      [ "$out" = None ] && out=""; printf '%s' "$out"; return 0
    fi; sleep $((a * 3))
  done
  echo "FATAL: describe-images failed after 3 attempts" >&2; return 1
}
snap_gib() { aws ec2 describe-images --region "$REGION" --image-ids "$1" \
  --query 'Images[0].BlockDeviceMappings[0].Ebs.VolumeSize' --output text; }
img_role() { _describe_newest --filters Name=tag:os,Values="$OS_NAME" Name=tag:os_major,Values=10 \
  Name=tag:arch,Values="$ARCH" Name=tag:role,Values="$1" Name=state,Values=available; }

# 0. source: an explicit BASE_AMI override, else the consumed base, else a greenfield prebase
#    (bootstrap output). Skip only when the CONSUMED base is already <= ROOT_GIB.
if [ -n "$BASE_AMI" ]; then
  SRC="$BASE_AMI"; FROM=override
else
  SRC=$(img_role "$ROLE_PROMOTED") || exit 1; FROM=consumed
  if [ -z "$SRC" ]; then SRC=$(img_role "$ROLE_PREBASE") || exit 1; FROM=prebase; fi
fi
[ -n "$SRC" ] || { echo "no $ROLE_PROMOTED or $ROLE_PREBASE OL10 $ARCH base; bootstrap one first"; exit 1; }
CUR=$(snap_gib "$SRC")
if [ "$FROM" = consumed ] && [ "$CUR" -le "$ROOT_GIB" ] && [ "$FORCE" != 1 ]; then
  echo "OL10 $ARCH consumed base $SRC root is ${CUR} GiB (<= ${ROOT_GIB}); already shrunk. Nothing to do (FORCE=1 to re-run)."
  exit 0
fi
echo "=== re-imaging OL10 $ARCH $FROM base $SRC (${CUR} GiB) -> ${ROOT_GIB} GiB (env=$ENV) ==="

# 1. launch a builder FROM the base; SSH in via the boot-test key.
sg=$(aws ec2 describe-security-groups --region "$REGION" --filters Name=group-name,Values="$BOOTTEST_SG" --query 'SecurityGroups[0].GroupId' --output text)
IID=""; VOL=""; SNAP=""; AMI=""; DONE=0
RUNTAG=ppg-reimage-run    # unique-per-run recovery tag (value=$NAME); lets cleanup find a resource
                          # even if the AWS CLI dies after the server created it but before the id returns.
cleanup() {
  set +e
  [ -z "$IID" ] && IID=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:$RUNTAG,Values=$NAME" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null)
  [ -z "$VOL" ] && VOL=$(aws ec2 describe-volumes --region "$REGION" \
    --filters "Name=tag:$RUNTAG,Values=$NAME" --query 'Volumes[].VolumeId' --output text 2>/dev/null)
  if [ "$DONE" != 1 ]; then
    [ -z "$AMI" ]  && AMI=$(aws ec2 describe-images --region "$REGION" --owners self \
      --filters "Name=name,Values=$NAME" --query 'Images[].ImageId' --output text 2>/dev/null)
    [ -z "$SNAP" ] && SNAP=$(aws ec2 describe-snapshots --region "$REGION" --owner-ids self \
      --filters "Name=tag:$RUNTAG,Values=$NAME" --query 'Snapshots[].SnapshotId' --output text 2>/dev/null)
    for a in $AMI;  do aws ec2 deregister-image --region "$REGION" --image-id "$a"  >/dev/null 2>&1; done
    for s in $SNAP; do aws ec2 delete-snapshot  --region "$REGION" --snapshot-id "$s" >/dev/null 2>&1; done
  fi
  for v in $VOL; do
    aws ec2 detach-volume --region "$REGION" --volume-id "$v" --force >/dev/null 2>&1
    aws ec2 wait volume-available --region "$REGION" --volume-ids "$v" 2>/dev/null
    aws ec2 delete-volume --region "$REGION" --volume-id "$v" >/dev/null 2>&1
  done
  for i in $IID; do aws ec2 terminate-instances --region "$REGION" --instance-ids "$i" >/dev/null 2>&1; done
  return 0
}
trap cleanup EXIT

IID=$(aws ec2 run-instances --region "$REGION" --image-id "$SRC" --instance-type "$INST" \
  --key-name "$(basename "${BOOTTEST_KEY%.pem}")" --security-group-ids "$sg" --subnet-id "$SUBNET" \
  --associate-public-ip-address --iam-instance-profile "Name=$BUILDER_PROFILE" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=ol10-reimage-builder},{Key=$RUNTAG,Value=$NAME},{Key=iit-billing-tag,Value=$BILLING_TAG}]" \
  --query 'Instances[0].InstanceId' --output text)
aws ec2 wait instance-status-ok --region "$REGION" --instance-ids "$IID"
read -r IP AZ < <(aws ec2 describe-instances --region "$REGION" --instance-ids "$IID" \
  --query 'Reservations[0].Instances[0].[PublicIpAddress,Placement.AvailabilityZone]' --output text)
SSH=(ssh -i "$BOOTTEST_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=25 "ec2-user@$IP")

# 2. attach a fresh target volume; poll for its Nitro device (NOT the /dev/sdf alias).
VOL=$(aws ec2 create-volume --region "$REGION" --availability-zone "$AZ" --size "$ROOT_GIB" --volume-type gp3 \
  --tag-specifications "ResourceType=volume,Tags=[{Key=Name,Value=ol10-reimage-target},{Key=$RUNTAG,Value=$NAME},{Key=iit-billing-tag,Value=$BILLING_TAG}]" --query VolumeId --output text)
aws ec2 wait volume-available --region "$REGION" --volume-ids "$VOL"
aws ec2 attach-volume --region "$REGION" --volume-id "$VOL" --instance-id "$IID" --device /dev/sdf >/dev/null
aws ec2 wait volume-in-use --region "$REGION" --volume-ids "$VOL"
TGT=""
for _ in $(seq 1 30); do
  TGT=$("${SSH[@]}" "lsblk -dno NAME,SERIAL | awk -v v=\"${VOL//-/}\" '\$2==v||\$2==\"$VOL\"{print \"/dev/\"\$1}'" 2>/dev/null) || true
  [ -n "$TGT" ] && break
  sleep 3
done
[ -n "$TGT" ] || { echo "target device for $VOL never appeared after attach" >&2; exit 1; }
echo "target volume $VOL -> $TGT"

# 3. run the on-builder surgery (introspects source, reproduces it on $TGT at ROOT_GIB).
"${SSH[@]}" "sudo ARCH=$ARCH TGT=$TGT bash -s" < "$HERE/reimage-surgery.sh"

# 4. detach, snapshot, register the candidate AMI (non-consumed role until verify promotes it).
aws ec2 detach-volume --region "$REGION" --volume-id "$VOL" >/dev/null
aws ec2 wait volume-available --region "$REGION" --volume-ids "$VOL"
SNAP=$(aws ec2 create-snapshot --region "$REGION" --volume-id "$VOL" --description "OL10 $ARCH ${ROOT_GIB}GiB reimaged" \
  --tag-specifications "ResourceType=snapshot,Tags=[{Key=os,Value=$OS_NAME},{Key=os_major,Value=10},{Key=arch,Value=$ARCH},{Key=role,Value=$CAND_ROLE},{Key=factory_env,Value=$F_ENV},{Key=source,Value=$SRC_TAG},{Key=$RUNTAG,Value=$NAME},{Key=iit-billing-tag,Value=$BILLING_TAG}]" \
  --query SnapshotId --output text)
aws ec2 wait snapshot-completed --region "$REGION" --snapshot-ids "$SNAP"
AMI=$(aws ec2 register-image --region "$REGION" --name "$NAME" --architecture "$EC2ARCH" --boot-mode "$BOOTMODE" \
  --ena-support --virtualization-type hvm --root-device-name /dev/sda1 \
  --block-device-mappings "DeviceName=/dev/sda1,Ebs={SnapshotId=$SNAP,VolumeSize=$ROOT_GIB,VolumeType=gp3,DeleteOnTermination=true}" \
  --query ImageId --output text)
aws ec2 create-tags --region "$REGION" --resources "$AMI" --tags \
  Key=Name,Value="$NAME" Key=os,Value="$OS_NAME" Key=os_major,Value=10 Key=arch,Value="$ARCH" \
  Key=role,Value="$CAND_ROLE" Key=source,Value="$SRC_TAG" Key=factory_env,Value="$F_ENV" \
  Key=base_ami,Value="$SRC" Key="$RUNTAG",Value="$NAME" Key=iit-billing-tag,Value="$BILLING_TAG"
aws ec2 delete-volume --region "$REGION" --volume-id "$VOL" >/dev/null; VOL=""
DONE=1   # snapshot + AMI are the deliverable from here; the trap keeps them

echo
echo "REIMAGED: $AMI ($NAME, ${ROOT_GIB} GiB, $BOOTMODE) role=$CAND_ROLE from $SRC"
echo "NEXT: just reimage-ol10-verify $AMI $ARCH${ENV:+ $ENV}   # boot-validate (two sizes) + smoke + size-gate + promote"
