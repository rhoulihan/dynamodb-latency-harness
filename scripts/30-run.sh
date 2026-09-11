#!/usr/bin/env bash
# 30-run.sh — start the harness on the instance, detached, and confirm it is up.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"
require_preflight
load_state
load_run

# Generated locally, staged through S3, executed by SSM. Staging keeps the SSM
# payload to a single fixed line, so nothing in this multi-line script has to
# survive shell-inside-JSON-inside-shell quoting.
START="$DDBLAT_REPO_ROOT/target/start.sh"
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  cat > "$START" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd /opt/ddblat

if pgrep -f 'ddblat\.jar' >/dev/null 2>&1; then
  echo "harness already running:"; pgrep -af 'ddblat\.jar'; exit 1
fi

: > /var/log/ddblat.log

# JVM flags verbatim from the plan's Global Constraints. ZGC is generational by
# default on JDK 25. AlwaysPreTouch over 24 GiB costs ~15s before the first line.
nohup setsid java \
  -XX:+UseZGC \
  -Xms24g -Xmx24g \
  -XX:+AlwaysPreTouch \
  -XX:StartFlightRecording=settings=profile,filename=/opt/ddblat/results/run.jfr,maxsize=2G \
  -jar /opt/ddblat/ddblat.jar --config /opt/ddblat/ddblat.properties \
  >> /var/log/ddblat.log 2>&1 < /dev/null &

sleep 20
pgrep -af 'ddblat\.jar' || { echo "harness did not start"; tail -n 60 /var/log/ddblat.log; exit 1; }
echo "--- first 20 log lines ---"
head -n 20 /var/log/ddblat.log
EOF
fi

run aws s3 cp "$START" "s3://$BUCKET/deploy/start.sh"
ssm_run "$INSTANCE_ID" "
set -euo pipefail
aws s3 cp s3://$BUCKET/deploy/start.sh /opt/ddblat/start.sh
chmod +x /opt/ddblat/start.sh
/opt/ddblat/start.sh
"

cat <<EOF

Harness started. run_id=$RUN_ID table=$TABLE_NAME

Tail it (no SSH exists; both of these go through SSM):

  # one-shot, scriptable
  aws ssm send-command --instance-ids $INSTANCE_ID \\
    --document-name AWS-RunShellScript \\
    --parameters 'commands=["tail -n 40 /var/log/ddblat.log"]' \\
    --query Command.CommandId --output text

  # continuous, from this repo
  while :; do "$SCRIPT_DIR/30-run.sh" --tail; sleep 30; done

  # interactive shell, then plain \`tail -f /var/log/ddblat.log\`
  aws ssm start-session --target $INSTANCE_ID
EOF

# --tail is a convenience mode so the loop above needs no second script.
if [[ "${1:-}" == "--tail" ]]; then
  ssm_run "$INSTANCE_ID" 'tail -n 40 /var/log/ddblat.log'
fi
