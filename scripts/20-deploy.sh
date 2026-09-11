#!/usr/bin/env bash
# 20-deploy.sh <config.properties>
#
# Why S3 staging rather than pushing bytes through SSM:
#   1. There is no SSH. The security group has no ingress rule and no key pair
#      was ever created (spec §8/§9), so the only channel to the instance is
#      SSM RunCommand, which the agent establishes outbound.
#   2. SSM RunCommand carries its payload inside the API request. The shaded jar
#      is ~30 MB; the document parameter limit is far below that, and base64
#      wrapping makes it worse, not better. The jar cannot ride inside the
#      command at all.
# So the Mac uploads to s3://$BUCKET/deploy/ and SSM runs a one-line `aws s3 cp`
# on the instance under the instance profile. Both hops already had to exist for
# results collection, so this adds no new surface.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"
require_preflight
load_state

CONFIG="${1:-}"
[[ -n "$CONFIG" && -f "$CONFIG" ]] || die "usage: $0 <config.properties>"

cd "$DDBLAT_REPO_ROOT"
log "building shaded jar"
run mvn -q clean package
JAR="$DDBLAT_REPO_ROOT/target/ddblat.jar"
[[ "${DRY_RUN:-0}" == "1" || -f "$JAR" ]] || die "expected $JAR after package"

RUN_ID=$(basename "$CONFIG" .properties)-$(date -u +%Y%m%dT%H%M%SZ)
TABLE_NAME=$(grep -E '^table=' "$CONFIG" | head -n1 | cut -d= -f2- | tr -d '[:space:]')
[[ -n "$TABLE_NAME" ]] || die "$CONFIG has no table= key"

# The committed config files carry sane standalone defaults for region and
# resultsDir (Config.load requires both); anything genuinely account- or
# run-specific is appended here so nothing generated is ever hand-edited.
# run.id and instance.id have no Config key -- they are this script's own
# bookkeeping, recorded in scripts/.run, not the properties file.
GEN="$DDBLAT_REPO_ROOT/target/ddblat.properties"
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  {
    cat "$CONFIG"
    echo ""
    echo "# --- injected by 20-deploy.sh, do not edit ---"
    echo "region=$AWS_REGION"
    echo "s3Bucket=$BUCKET"
    echo "s3Prefix=results/$RUN_ID"
    echo "resultsDir=/opt/ddblat/results"
    echo "checkpointFile=/var/lib/ddblat/checkpoint-$TABLE_NAME"
  } > "$GEN"
fi

log "run id: $RUN_ID   table: $TABLE_NAME"
run aws s3 cp "$JAR" "s3://$BUCKET/deploy/ddblat.jar"
run aws s3 cp "$GEN" "s3://$BUCKET/deploy/ddblat.properties"

log "waiting for instance bootstrap to finish (Corretto install)"
# Single-quoted on purpose: this whole block is the remote command body, and its
# $(seq ...) / [ -f ... ] must be evaluated on the instance, not expanded here.
# shellcheck disable=SC2016
ssm_run "$INSTANCE_ID" '
set -euo pipefail
for i in $(seq 1 60); do
  if [ -f /opt/ddblat/.bootstrap-complete ]; then break; fi
  sleep 5
done
[ -f /opt/ddblat/.bootstrap-complete ] || { echo "bootstrap never completed"; tail -50 /var/log/ddblat-bootstrap.log; exit 1; }
java -version 2>&1
'

log "pulling artifacts down to the instance"
ssm_run "$INSTANCE_ID" "
set -euo pipefail
aws s3 cp s3://$BUCKET/deploy/ddblat.jar        /opt/ddblat/ddblat.jar
aws s3 cp s3://$BUCKET/deploy/ddblat.properties /opt/ddblat/ddblat.properties
chmod 0644 /opt/ddblat/ddblat.jar /opt/ddblat/ddblat.properties
sha256sum /opt/ddblat/ddblat.jar
"

if [[ "${DRY_RUN:-0}" != "1" ]]; then
  LOCAL_SHA=$(shasum -a 256 "$JAR" | cut -d' ' -f1)
  REMOTE_SHA=$(ssm_run "$INSTANCE_ID" 'sha256sum /opt/ddblat/ddblat.jar' | awk '{print $1}' | tail -n1)
  [[ "$LOCAL_SHA" == "$REMOTE_SHA" ]] \
    || die "jar sha256 mismatch: local $LOCAL_SHA remote $REMOTE_SHA"
  log "jar verified: $LOCAL_SHA"
  {
    echo "RUN_ID=$RUN_ID"
    echo "CONFIG=$CONFIG"
    echo "TABLE_NAME=$TABLE_NAME"
  } > "$RUN_FILE"
fi

log "deployed. next: scripts/30-run.sh"
