#!/usr/bin/env bash
# 99-teardown.sh [--destroy]
#
#   default    : RCU -> 10 (stop paying ~$5/hr for read capacity at 40,000
#                RCU -- WCU is already down at 10 from the mid-run SWITCH
#                step, so RCU is the only capacity teardown ever needs to
#                lower), then stop the instance (~$0.91/hr). The table and
#                its data are KEPT (spec §12: ~$30/mo residual, against ~55
#                minutes to reload it).
#   --destroy  : additionally delete the table, terminate the instance, delete
#                the VPC endpoint, security group, IAM role/profile and bucket.
#
# Every id below is optional, not required: scripts/.provisioned can be
# incomplete (a provisioning run that died partway through, or a state file
# that was lost or hand-edited), and this script's whole job is to leave as
# little billing behind as possible, so it must do everything it CAN rather
# than abort on the first missing piece. Each step below checks its own id,
# logs and skips if that id is absent, and continues to the next -- one
# missing resource must never prevent tearing down the others. RCU is handled
# before the instance: at up to 40,000 RCU (~$5/hr) it is the larger cost
# here, well above the instance's ~$0.91/hr, so it goes first -- a later step
# failing must not leave the pricier resource burning for the length of
# `wait instance-stopped`.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"
load_state

DESTROY=0
if [[ "${1:-}" == "--destroy" ]]; then DESTROY=1; fi

TABLE_NAME="${TABLE_NAME:-}"
if [[ -z "$TABLE_NAME" && -f "$RUN_FILE" ]]; then load_run; fi
TABLE_NAME="${TABLE_NAME:-latency-test-100g}"

# ${VAR:-} on every one of these: under `set -u` a bare reference to a name
# that load_state never set (because 10-provision.sh died before recording it)
# aborts the whole script before any fallback can run. Defaulting to empty
# here is what makes every "is it set" check below possible at all.
INSTANCE_ID="${INSTANCE_ID:-}"
VPCE_ID="${VPCE_ID:-}"
SG_ID="${SG_ID:-}"
ROLE_NAME="${ROLE_NAME:-}"
PROFILE_NAME="${PROFILE_NAME:-}"
BUCKET="${BUCKET:-}"

# ------------------------------------------------------- RCU back down to 10 --
CUR=$(query "10	10" aws dynamodb describe-table --table-name "$TABLE_NAME" \
        --query 'Table.ProvisionedThroughput.[ReadCapacityUnits,WriteCapacityUnits]' \
        --output text 2>/dev/null || echo "")
if [[ -n "$CUR" ]]; then
  CUR_RCU=$(echo "$CUR" | awk '{print $1}')
  CUR_WCU=$(echo "$CUR" | awk '{print $2}')
  log "$TABLE_NAME currently RCU=$CUR_RCU WCU=$CUR_WCU"
  if [[ "$CUR_RCU" != "10" || "$CUR_WCU" != "10" ]]; then
    run aws dynamodb update-table --table-name "$TABLE_NAME" \
        --provisioned-throughput ReadCapacityUnits=10,WriteCapacityUnits=10
    run aws dynamodb wait table-exists --table-name "$TABLE_NAME"
    log "$TABLE_NAME set to RCU=10 WCU=10"
  else
    log "$TABLE_NAME already at minimum capacity"
  fi
else
  log "table $TABLE_NAME not found — nothing to scale down"
fi

# ------------------------------------------- tag-based instance fallback -----
# State files get lost; the project tag does not. 10-provision.sh applies it
# via run-instances --tag-specifications, atomically with creation, rather
# than a later create-tags call that could itself fail -- so if INSTANCE_ID
# didn't survive, the tag is still there to find the orphan by. But the tag
# alone cannot prove uniqueness, and guessing wrong here means stopping -- or
# under --destroy, TERMINATING -- an unrelated instance in a live account. So
# this never picks one out of several: zero matches skips (as before), exactly
# one match proceeds (as before), and two or more matches aborts only this
# recovery, lists every id found, and lets the rest of teardown continue.
if [[ -z "$INSTANCE_ID" ]]; then
  log "no INSTANCE_ID in state — looking it up by tag project=ddblat"
  FOUND_RAW=$(query "" aws ec2 describe-instances \
                --filters Name=tag:project,Values=ddblat \
                          Name=instance-state-name,Values=pending,running,stopping,stopped \
                --query 'Reservations[].Instances[].InstanceId' --output text)
  read -ra FOUND_IIDS <<< "$FOUND_RAW"
  case "${#FOUND_IIDS[@]}" in
    0)
      log "SKIPPED: no instance id in state and none found by tag — nothing to stop"
      ;;
    1)
      INSTANCE_ID="${FOUND_IIDS[0]}"
      log "recovered orphaned instance $INSTANCE_ID by tag"
      ;;
    *)
      log "SKIPPED: tag project=ddblat matches ${#FOUND_IIDS[@]} instances — refusing to guess which one: ${FOUND_IIDS[*]}. Stop/terminate the right one manually (aws ec2 stop-instances / terminate-instances --instance-ids <id>), then re-run teardown for the rest."
      ;;
  esac
fi

# ------------------------------------------------------------ stop instance ---
if [[ -n "$INSTANCE_ID" ]]; then
  STATE=$(query "running" aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
            --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null || echo "absent")
  if [[ "$STATE" == "running" || "$STATE" == "pending" ]]; then
    run aws ec2 stop-instances --instance-ids "$INSTANCE_ID"
    run aws ec2 wait instance-stopped --instance-ids "$INSTANCE_ID"
    log "instance $INSTANCE_ID stopped"
  else
    log "instance $INSTANCE_ID is $STATE — leaving it"
  fi
fi

if [[ "$DESTROY" -eq 0 ]]; then
  cat <<EOF

Teardown (safe mode) complete.
  KEPT: table $TABLE_NAME with its data, EBS volume, bucket, IAM, networking.
  Residual cost ~\$30/month (spec §12). Re-run scripts/30-run.sh any time.
  To delete everything including the table and its 100 GiB:  $0 --destroy
EOF
  exit 0
fi

# ------------------------------------------------------------------ destroy ---
log "DESTROY: deleting table $TABLE_NAME, instance, endpoint, SG, IAM, bucket"
run aws dynamodb delete-table --table-name "$TABLE_NAME" || log "table already gone"

if [[ -n "$INSTANCE_ID" ]]; then
  run aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" || true
  run aws ec2 wait instance-terminated --instance-ids "$INSTANCE_ID" || true
else
  log "SKIPPED: no instance id — nothing to terminate"
fi

if [[ -n "$VPCE_ID" ]]; then
  run aws ec2 delete-vpc-endpoints --vpc-endpoint-ids "$VPCE_ID" || true
else
  log "SKIPPED: no vpc endpoint id"
fi

if [[ -n "$SG_ID" ]]; then
  run aws ec2 delete-security-group --group-id "$SG_ID" || \
    log "SG delete failed — usually a lingering ENI; retry in a minute"
else
  log "SKIPPED: no security group id"
fi

if [[ -n "$ROLE_NAME" && -n "$PROFILE_NAME" ]]; then
  run aws iam remove-role-from-instance-profile \
      --instance-profile-name "$PROFILE_NAME" --role-name "$ROLE_NAME" || true
fi

if [[ -n "$PROFILE_NAME" ]]; then
  run aws iam delete-instance-profile --instance-profile-name "$PROFILE_NAME" || true
else
  log "SKIPPED: no instance profile name"
fi

if [[ -n "$ROLE_NAME" ]]; then
  run aws iam delete-role-policy --role-name "$ROLE_NAME" --policy-name ddblat-scoped   || true
  run aws iam delete-role-policy --role-name "$ROLE_NAME" --policy-name ddblat-selfstop || true
  run aws iam detach-role-policy --role-name "$ROLE_NAME" \
      --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore || true
  run aws iam delete-role --role-name "$ROLE_NAME" || true
else
  log "SKIPPED: no role name — IAM role/policies left untouched"
fi

if [[ -n "$BUCKET" ]]; then
  run aws s3 rm "s3://$BUCKET" --recursive || true
  run aws s3api delete-bucket --bucket "$BUCKET" || true
else
  log "SKIPPED: no bucket name"
fi

run rm -f "$STATE_FILE" "$RUN_FILE"
log "destroy complete"
