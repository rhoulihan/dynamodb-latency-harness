#!/usr/bin/env bash
# 99-teardown.sh [--destroy]
#   default   : scale the database to its minimum and stop the client instance.
#   --destroy : additionally terminate the instance and delete the database, subnet, gateway
#               and VCN.
# Every step is independent: a missing resource logs and is skipped, never aborts the rest.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
load_state
DESTROY=0; [[ "${1:-}" == "--destroy" ]] && DESTROY=1

# The database dominates cost (64 ECPU is ~$21/hr against the client's ~$0.32/hr), so it
# goes first: a later step failing must not leave the pricier resource running.
if [[ -n "${ADB_ID:-}" ]]; then
  if (( DESTROY )); then
    log "deleting Autonomous Database"
    o db autonomous-database delete --autonomous-database-id "$ADB_ID" --force >/dev/null 2>&1 \
      && log "  delete requested" || log "  delete failed — CHECK MANUALLY"
  else
    log "scaling database down to 2 ECPU"
    o db autonomous-database update --autonomous-database-id "$ADB_ID" --compute-count 2 --force >/dev/null 2>&1 \
      && log "  scaled" || log "  scale failed — CHECK MANUALLY"
  fi
fi

if [[ -n "${INSTANCE_ID:-}" ]]; then
  if (( DESTROY )); then
    log "terminating client instance"
    o compute instance terminate --instance-id "$INSTANCE_ID" --force >/dev/null 2>&1 || log "  failed"
  else
    log "stopping client instance"
    o compute instance action --instance-id "$INSTANCE_ID" --action STOP >/dev/null 2>&1 || log "  failed"
  fi
fi

if (( DESTROY )); then
  log "waiting for instance termination before removing the network"
  for _ in $(seq 1 60); do
    st=$(o compute instance get --instance-id "$INSTANCE_ID" --query 'data."lifecycle-state"' --raw-output 2>/dev/null)
    [[ "$st" == "TERMINATED" || -z "$st" ]] && break
    sleep 10
  done
  [[ -n "${SUBNET_ID:-}" ]] && o network subnet delete --subnet-id "$SUBNET_ID" --force >/dev/null 2>&1 && log "subnet deleted"
  [[ -n "${IGW_ID:-}"    ]] && o network internet-gateway delete --ig-id "$IGW_ID" --force >/dev/null 2>&1 && log "gateway deleted"
  [[ -n "${VCN_ID:-}"    ]] && o network vcn delete --vcn-id "$VCN_ID" --force >/dev/null 2>&1 && log "vcn deleted"
  rm -f "$STATE_FILE" "$OCI_SCRIPTS_DIR/.preflight-ok"
  log "destroy complete"
else
  log "parked. run with --destroy to remove everything."
fi
