#!/usr/bin/env bash
# PG-2353 Oracle Linux package-test target refresh provisioner.
# Minimal by design: refresh packages and keep the image close to a vanilla
# Oracle Linux target so package tests stay faithful. De-instancing (host keys,
# authorized_keys) happens at the END of validate.sh, the last provisioner, so
# it does not break packer's SSH session mid-build.
set -euxo pipefail

# Core refresh: this is what the manual process did by hand.
dnf -y update

# Baseline tooling molecule/ansible drivers expect; best-effort so a renamed
# package across minors does not fail the build.
dnf -y install python3 cloud-init || true

# Bake the SSM agent into the image (Oracle Linux does not ship it). The builder
# already got it via user_data; install-if-missing makes provision.sh self-
# sufficient. The baked image needs it so the smoke test (aws ssm send-command)
# and the next refresh bake (session_manager) can connect. REGION/SSM_ARCH come
# from packer; fall back to the global RPM if the regional one is unavailable.
if ! rpm -q amazon-ssm-agent >/dev/null 2>&1; then
  dnf -y install "https://s3.${REGION}.amazonaws.com/amazon-ssm-${REGION}/latest/linux_${SSM_ARCH}/amazon-ssm-agent.rpm" \
    || dnf -y install "https://s3.amazonaws.com/ec2-downloads-windows/SSMAgent/latest/linux_${SSM_ARCH}/amazon-ssm-agent.rpm"
fi
systemctl enable amazon-ssm-agent

dnf clean all
rm -rf /var/cache/dnf

# Reset cloud-init + machine-id so a launched instance re-initialises. SSH keys
# are cleared later (see validate.sh) to avoid cutting our own session.
cloud-init clean --logs || true
rm -rf /var/lib/cloud/instances/* || true
: > /etc/machine-id || true

echo "PROVISION OK: $(. /etc/os-release; echo "$PRETTY_NAME") $(uname -m)"
