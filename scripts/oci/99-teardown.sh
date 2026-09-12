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
  # Order and route rules matter. An internet gateway cannot be deleted while a route table
  # still references it, and the VCN cannot be deleted while the gateway exists -- so clearing
  # the default route table first is not optional. Getting this wrong leaves the VCN behind
  # silently, which is exactly what happened the first time this script was run for real.
  drop() {                      # drop <label> <command...>: report the real outcome, never assume
    local label="$1"; shift
    local out; out=$("$@" 2>&1)
    if [[ -z "$out" ]]; then log "$label deleted"
    else log "$label NOT deleted: $(tr -d '\n' <<<"$out" | cut -c1-140)"; fi
  }

  if [[ -n "${SUBNET_ID:-}" ]]; then drop subnet o network subnet delete --subnet-id "$SUBNET_ID" --force; fi

  if [[ -n "${VCN_ID:-}" ]]; then
    RT=$(o network vcn get --vcn-id "$VCN_ID" --query 'data."default-route-table-id"' --raw-output 2>/dev/null)
    if [[ -n "$RT" ]]; then
      o network route-table update --rt-id "$RT" --force --route-rules '[]' >/dev/null 2>&1 \
        && log "route rules cleared" || log "could not clear route rules — gateway delete may fail"
    fi
  fi

  if [[ -n "${IGW_ID:-}" ]]; then drop gateway o network internet-gateway delete --ig-id "$IGW_ID" --force; fi
  sleep 10
  if [[ -n "${VCN_ID:-}" ]]; then drop vcn o network vcn delete --vcn-id "$VCN_ID" --force; fi
  rm -f "$STATE_FILE" "$OCI_SCRIPTS_DIR/.preflight-ok"
  log "destroy complete"
else
  log "parked. run with --destroy to remove everything."
fi
