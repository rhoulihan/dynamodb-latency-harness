#!/usr/bin/env bash
# 50-repeat.sh <config.properties> [N]
#
# Runs the same measurement N times back to back and collects each one separately.
#
# Why this exists: a single run cannot distinguish a property of the system from a property
# of that afternoon. The eventual-vs-strong P99 gap measured -37%, -42%, -15% and -0.4% on
# four separate runs of identical code -- which only reads as "no reliable difference" once
# there are four of them. Percentile tails especially need repetition; one clean-looking
# number is a hypothesis, not a result.
#
# Deploys the jar ONCE and then, per iteration, pushes only a fresh properties file (new
# s3Prefix so runs never overwrite each other) and restarts the harness. selfStop is forced
# OFF for every iteration -- the instance must survive to run the next one -- and this script
# stops it on the way out, including on failure or interrupt, so an aborted sequence cannot
# leave a c6in.4xlarge and 40,000 RCU running unattended.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"
require_preflight
load_state

CONFIG="${1:-}"
N="${2:-5}"
[[ -n "$CONFIG" && -f "$CONFIG" ]] || die "usage: $0 <config.properties> [N]"

TABLE_NAME=$(grep -E '^table=' "$CONFIG" | head -n1 | cut -d= -f2- | tr -d '[:space:]')
BASE=$(basename "$CONFIG" .properties)
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
SERIES="$DDBLAT_REPO_ROOT/results/series-$BASE-$STAMP"
mkdir -p "$SERIES"

# Any exit path -- success, a die(), Ctrl-C -- parks the expensive resources. The table is the
# costly one (40,000 RCU is ~$5.20/hr against the instance's ~$0.91/hr), so it goes first.
#
# The decrease can legitimately be REFUSED: DynamoDB allows 4 provisioned-throughput decreases
# per table per UTC day and then one per hour. That is precisely why this script raises capacity
# once for the whole series instead of letting each run raise and drop it -- five runs would
# spend five decreases and strand the table at full read capacity when the fifth was refused.
# If it is refused anyway, say so loudly with the error, because it is real money per hour.
cleanup() {
  local rc=$? out
  log "cleanup: parking resources"
  if out=$(aws dynamodb update-table --table-name "$TABLE_NAME" \
             --provisioned-throughput ReadCapacityUnits=10,WriteCapacityUnits=10 2>&1); then
    log "cleanup: table -> RCU 10 / WCU 10"
  elif grep -q "will not change" <<<"$out"; then
    log "cleanup: table already at RCU 10 / WCU 10"
  else
    log "cleanup: !!! COULD NOT LOWER TABLE CAPACITY -- STILL BILLING !!!"
    log "cleanup: $(tr -d '\n' <<<"$out" | cut -c1-400)"
  fi
  aws ec2 stop-instances --instance-ids "$INSTANCE_ID" >/dev/null 2>&1 \
    && log "cleanup: instance stopping" \
    || log "cleanup: instance stop failed -- CHECK MANUALLY"
  log "series artifacts: $SERIES"
  exit $rc
}
trap cleanup EXIT INT TERM

# The instance is normally left stopped between sessions, and StartInstances can legitimately
# fail with InsufficientInstanceCapacity for a c6in.4xlarge -- it did once already today -- so
# retry rather than losing the whole sequence to a transient. SSM has to be Online too: the
# security group has no ingress and no key pair exists, so RunCommand is the only channel.
ensure_instance_up() {
  local state
  state=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
            --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null)
  if [[ "$state" != "running" ]]; then
    log "instance is $state; starting"
    for attempt in 1 2 3 4 5 6; do
      aws ec2 start-instances --instance-ids "$INSTANCE_ID" >/dev/null 2>&1 && break
      log "  start attempt $attempt failed (capacity?); retrying in 45s"
      [[ $attempt -eq 6 ]] && die "could not start $INSTANCE_ID after 6 attempts"
      sleep 45
    done
  fi
  log "waiting for SSM to report Online"
  for _ in $(seq 1 60); do
    [[ "$(aws ssm describe-instance-information \
            --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
            --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null)" == "Online" ]] \
      && { log "SSM online"; return 0; }
    sleep 10
  done
  die "SSM never came Online for $INSTANCE_ID"
}
ensure_instance_up

log "building and deploying once for $N runs"
cd "$DDBLAT_REPO_ROOT"
mvn -q clean package || die "build failed"
JAR="$DDBLAT_REPO_ROOT/target/ddblat.jar"
[[ -f "$JAR" ]] || die "expected $JAR after package"
aws s3 cp --no-progress "$JAR" "s3://$BUCKET/deploy/ddblat.jar" >/dev/null || die "jar upload failed"

# start.sh is regenerated here rather than reused from 30-run.sh so this script is
# self-contained; the JVM flags are the same ones every previous run used.
START="$DDBLAT_REPO_ROOT/target/start.sh"
cat > "$START" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd /opt/ddblat
if pgrep -f 'ddblat\.jar' >/dev/null 2>&1; then
  echo "harness already running:"; pgrep -af 'ddblat\.jar'; exit 1
fi
: > /var/log/ddblat.log
nohup setsid java \
  -XX:+UseZGC -Xms24g -Xmx24g -XX:+AlwaysPreTouch \
  -XX:StartFlightRecording=settings=profile,filename=/opt/ddblat/results/run.jfr,maxsize=2G \
  -jar /opt/ddblat/ddblat.jar --config /opt/ddblat/ddblat.properties \
  >> /var/log/ddblat.log 2>&1 < /dev/null &
sleep 15
pgrep -af 'ddblat\.jar' || { echo "harness did not start"; tail -n 40 /var/log/ddblat.log; exit 1; }
EOF
aws s3 cp --no-progress "$START" "s3://$BUCKET/deploy/start.sh" >/dev/null || die "start.sh upload failed"

ssm_run "$INSTANCE_ID" "
set -euo pipefail
aws s3 cp s3://$BUCKET/deploy/ddblat.jar  /opt/ddblat/ddblat.jar
aws s3 cp s3://$BUCKET/deploy/start.sh    /opt/ddblat/start.sh
chmod 0644 /opt/ddblat/ddblat.jar; chmod +x /opt/ddblat/start.sh
sha256sum /opt/ddblat/ddblat.jar
" >/dev/null || die "staging to instance failed"
log "jar staged"

# Fail-fast permission probe. SegmentKeySelector is the only thing here that scans, and it
# runs AFTER the SWITCH's 5-minute settle -- so a missing dynamodb:Scan grant on the instance
# role surfaces six minutes and one capacity change into a run, which is exactly how run 1 of
# the first attempt at this series died. One Limit=1 Scan from the instance costs a fraction
# of an RCU and answers it before anything expensive starts.
if grep -qE '^readSegmentsTotal=[1-9]' "$CONFIG"; then
  # Retried, not one-shot: IAM is eventually consistent, and a policy edit made minutes before
  # a launch can still read as denied on the first attempt -- observed today, where a grant
  # that was genuinely in place probed DENIED and aborted the series. Three tries over ~40s
  # distinguishes propagation lag from an actually-missing grant.
  probe=""
  for attempt in 1 2 3; do
    probe=$(ssm_run "$INSTANCE_ID" "
aws dynamodb scan --table-name $TABLE_NAME --limit 1 \
    --projection-expression pk --region $AWS_REGION >/dev/null 2>&1 \
  && echo SCAN_OK || echo SCAN_DENIED
" 2>/dev/null | tr -d '[:space:]')
    [[ "$probe" == *SCAN_OK* ]] && break
    log "  scan probe attempt $attempt: ${probe:-<no output>}; retrying in 20s"
    sleep 20
  done
  [[ "$probe" == *SCAN_OK* ]] || die "the instance role cannot dynamodb:Scan $TABLE_NAME after 3 \
attempts, which segment-scoped reads require (readSegmentsTotal is set in $CONFIG). Add \
dynamodb:Scan to the TableOps statement of the ddblat-scoped role policy -- \
scripts/10-provision.sh has it right for a fresh provision."
  log "scan permission verified"
fi

# Capacity for the WHOLE series: raised once here, dropped once in cleanup. Each run is
# configured with manageCapacity=false below, so no run raises or drops anything itself.
READ_RCU=$(grep -E '^readRcu=' "$CONFIG" | head -n1 | cut -d= -f2- | tr -d '[:space:]')
[[ -n "$READ_RCU" ]] || die "$CONFIG has no readRcu key"
log "raising table to RCU $READ_RCU / WCU 10 for the whole series"
if out=$(aws dynamodb update-table --table-name "$TABLE_NAME" \
           --provisioned-throughput "ReadCapacityUnits=$READ_RCU,WriteCapacityUnits=10" 2>&1); then
  :
elif grep -q "will not change" <<<"$out"; then
  log "already at RCU $READ_RCU / WCU 10"
else
  die "could not raise table capacity: $(tr -d '\n' <<<"$out" | cut -c1-300)"
fi
log "waiting for table ACTIVE at RCU $READ_RCU"
for _ in $(seq 1 120); do
  read -r st rcu wcu <<<"$(aws dynamodb describe-table --table-name "$TABLE_NAME" \
      --query '[Table.TableStatus,Table.ProvisionedThroughput.ReadCapacityUnits,Table.ProvisionedThroughput.WriteCapacityUnits]' \
      --output text 2>/dev/null)"
  [[ "$st" == "ACTIVE" && "$rcu" == "$READ_RCU" && "$wcu" == "10" ]] && { log "table ACTIVE at RCU $rcu / WCU $wcu"; break; }
  sleep 15
done

SUCCEEDED=(); FAILED=(); consecutive_failures=0

for i in $(seq 1 "$N"); do
  RUN_ID="${BASE}-r${i}-$(date -u +%Y%m%dT%H%M%SZ)"
  log "=== run $i/$N : $RUN_ID ==="

  GEN="$DDBLAT_REPO_ROOT/target/ddblat.properties"
  {
    cat "$CONFIG"
    echo ""
    echo "# --- injected by 50-repeat.sh run $i/$N, do not edit ---"
    echo "region=$AWS_REGION"
    echo "s3Bucket=$BUCKET"
    echo "s3Prefix=results/$RUN_ID"
    echo "resultsDir=/opt/ddblat/results"
    echo "checkpointFile=/var/lib/ddblat/checkpoint-$TABLE_NAME"
    # Forced off: the instance has to survive to run the next iteration. cleanup() stops it.
    echo "selfStop=false"
    # Forced off: this script raised capacity for the whole series and drops it in cleanup.
    # A run that dropped capacity at its own TEARDOWN would spend one of the table's 4 daily
    # decreases per run, and the run after it would have to raise capacity again.
    echo "manageCapacity=false"
  } > "$GEN"
  aws s3 cp --no-progress "$GEN" "s3://$BUCKET/deploy/ddblat.properties" >/dev/null

  ssm_run "$INSTANCE_ID" "
set -euo pipefail
aws s3 cp s3://$BUCKET/deploy/ddblat.properties /opt/ddblat/ddblat.properties
/opt/ddblat/start.sh
" >/dev/null || { log "run $i FAILED to start"; FAILED+=("$RUN_ID:start"); consecutive_failures=$((consecutive_failures+1)); continue; }
  log "run $i started"

  # Poll S3 for the DONE marker the harness writes at the end of TEARDOWN. Polling S3 rather
  # than the instance means a run whose JVM died is detected by the process check below
  # rather than by a timeout that would burn the remaining budget.
  deadline=$(( $(date +%s) + 150 * 60 ))
  outcome="timeout"
  while :; do
    if aws s3api head-object --bucket "$BUCKET" --key "results/$RUN_ID/DONE" >/dev/null 2>&1; then
      outcome="done"; break
    fi
    [[ $(date +%s) -ge $deadline ]] && { outcome="timeout"; break; }
    alive=$(ssm_run "$INSTANCE_ID" 'pgrep -f "ddblat\.jar" >/dev/null && echo alive || echo dead' 2>/dev/null | tr -d "[:space:]")
    if [[ "$alive" == "dead" ]]; then
      sleep 20   # DONE upload may still be in flight
      aws s3api head-object --bucket "$BUCKET" --key "results/$RUN_ID/DONE" >/dev/null 2>&1 \
        && { outcome="done"; break; }
      outcome="died"; break
    fi
    printf '  %s  run %d/%d  %s\n' "$(date -u +%H:%M:%S)" "$i" "$N" \
      "$(ssm_run "$INSTANCE_ID" 'grep -E "PHASE-START|SEGMENT-SELECT|SWITCH" /var/log/ddblat.log | tail -1' 2>/dev/null | tr -d "\n" | cut -c1-110)" >&2
    sleep 120
  done

  DEST="$SERIES/$RUN_ID"
  mkdir -p "$DEST"
  if [[ "$outcome" == "done" ]]; then
    aws s3 sync --no-progress "s3://$BUCKET/results/$RUN_ID/" "$DEST/" >/dev/null
    log "run $i OK -> $DEST"
    SUCCEEDED+=("$RUN_ID"); consecutive_failures=0
  else
    log "run $i FAILED ($outcome) -- capturing log"
    ssm_run "$INSTANCE_ID" 'tail -n 200 /var/log/ddblat.log' > "$DEST/failure-tail.log" 2>&1
    aws s3 sync --no-progress "s3://$BUCKET/results/$RUN_ID/" "$DEST/" >/dev/null 2>&1
    FAILED+=("$RUN_ID:$outcome"); consecutive_failures=$((consecutive_failures+1))
  fi

  # Two in a row is systemic, not bad luck; stop burning capacity on it.
  if [[ $consecutive_failures -ge 2 ]]; then
    log "ABORTING: $consecutive_failures consecutive failures"
    break
  fi
done

{
  echo "series:    $SERIES"
  echo "config:    $CONFIG"
  echo "requested: $N"
  echo "succeeded: ${#SUCCEEDED[@]}"
  printf '  %s\n' "${SUCCEEDED[@]:-}"
  echo "failed:    ${#FAILED[@]}"
  printf '  %s\n' "${FAILED[@]:-}"
} | tee "$SERIES/series-manifest.txt"
