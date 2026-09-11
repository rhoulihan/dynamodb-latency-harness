#!/usr/bin/env bash
# 40-collect.sh — wait for the DONE marker the harness writes at the end of
# TEARDOWN, then pull everything down. The instance self-stops after writing the
# marker, so polling S3 (not the instance) is the right thing to poll.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"
load_state
load_run

POLL_SECONDS="${POLL_SECONDS:-30}"
MAX_MINUTES="${MAX_MINUTES:-200}"
DEST="$DDBLAT_REPO_ROOT/results/$RUN_ID"
MARKER="s3://$BUCKET/results/$RUN_ID/DONE"

log "waiting for $MARKER (poll ${POLL_SECONDS}s, give up after ${MAX_MINUTES}m)"
deadline=$(( $(date +%s) + MAX_MINUTES * 60 ))
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  while :; do
    if aws s3api head-object --bucket "$BUCKET" --key "results/$RUN_ID/DONE" >/dev/null 2>&1; then
      log "DONE marker present"
      break
    fi
    if [[ $(date +%s) -ge $deadline ]]; then
      die "timed out waiting for $MARKER — check: aws ssm start-session --target $INSTANCE_ID"
    fi
    state=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
              --query 'Reservations[0].Instances[0].State.Name' --output text)
    printf '  %s  instance=%s  still waiting\n' "$(date -u +%H:%M:%S)" "$state" >&2
    if [[ "$state" == "stopped" ]]; then
      die "instance stopped without writing DONE — pull /var/log/ddblat.log before restarting it"
    fi
    sleep "$POLL_SECONDS"
  done
fi

run mkdir -p "$DEST"
run aws s3 sync "s3://$BUCKET/results/$RUN_ID/" "$DEST/"

echo
echo "Collected into $DEST:"
if [[ -d "$DEST" ]]; then ls -lh "$DEST"; fi

if [[ "${DRY_RUN:-0}" != "1" ]]; then
  for f in ddblat.log run.jfr summary-LOAD.json summary-R-A.json summary-R-B.json; do
    [[ -f "$DEST/$f" ]] || log "WARNING: expected artifact missing: $f"
  done
  state=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
            --query 'Reservations[0].Instances[0].State.Name' --output text)
  log "instance state after run: $state (expect 'stopping' or 'stopped' — self-stop, spec §9)"
fi
