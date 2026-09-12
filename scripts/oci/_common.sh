#!/usr/bin/env bash
# Shared state and helpers for the OCI pipeline. Mirrors scripts/_common.sh on the AWS side.
set -uo pipefail
OCI_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DDBLAT_REPO_ROOT="$(cd "$OCI_SCRIPTS_DIR/../.." && pwd)"
STATE_FILE="$OCI_SCRIPTS_DIR/.provisioned"
export OCI_SCRIPTS_DIR DDBLAT_REPO_ROOT STATE_FILE

: "${OCI_PROFILE:=ddblat}"
: "${OCI_REGION:=us-ashburn-1}"
: "${OCI_SSH_KEY:=$HOME/.ssh/ddblat_oci}"
export OCI_PROFILE OCI_REGION OCI_SSH_KEY

# The SDK signs with a credential scope Oracle validates and accepts only as us-west-2,
# regardless of which OCI region the database is in. See Provider.signingRegion().
export SUPPRESS_LABEL_WARNING=True

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

o() { oci --profile "$OCI_PROFILE" --region "$OCI_REGION" "$@"; }

save() {
  local k="$1" v="$2"
  touch "$STATE_FILE"
  sed -i.bak "/^${k}=/d" "$STATE_FILE" && rm -f "$STATE_FILE.bak"
  printf '%s=%s\n' "$k" "$v" >> "$STATE_FILE"
}
load_state() {
  [[ -f "$STATE_FILE" ]] || die "no $STATE_FILE — run scripts/oci/10-provision.sh first"
  set -a; . "$STATE_FILE"; set +a
}

# Remote exec on the client instance. No Run Command dependency: plain SSH over the public
# subnet, with the key this pipeline generated.
osh() {
  ssh -i "$OCI_SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -o ConnectTimeout=15 -o LogLevel=ERROR "opc@${INSTANCE_IP}" "$@"
}
oscp() {
  scp -i "$OCI_SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -o LogLevel=ERROR "$@"
}
