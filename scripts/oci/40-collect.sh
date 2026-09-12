#!/usr/bin/env bash
# 40-collect.sh — wait for the DONE marker, then pull artifacts down.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
load_state
DEST="${DEST:-$DDBLAT_REPO_ROOT/results/oci-$(date -u +%Y%m%dT%H%M%SZ)}"
MAX_MIN="${MAX_MIN:-240}"
log "waiting for the run to finish (max ${MAX_MIN}m)"
deadline=$(( $(date +%s) + MAX_MIN*60 ))
while :; do
  if osh 'test -f ~/results/DONE' 2>/dev/null; then log "DONE present"; break; fi
  if ! osh 'pgrep -f ddblat.jar >/dev/null' 2>/dev/null; then
    die "harness exited without writing DONE — pull ~/ddblat.log before rerunning"
  fi
  (( $(date +%s) >= deadline )) && die "timed out"
  printf '  %s still running\n' "$(date -u +%H:%M:%S)" >&2
  sleep 30
done
mkdir -p "$DEST"
oscp -r "opc@${INSTANCE_IP}:~/results/*" "$DEST/" >/dev/null
oscp "opc@${INSTANCE_IP}:~/ddblat.log" "$DEST/" >/dev/null
log "collected into $DEST"
ls -lh "$DEST"
