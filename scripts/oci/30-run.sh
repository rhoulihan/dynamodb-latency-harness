#!/usr/bin/env bash
# 30-run.sh — start the harness on the client instance, detached.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
load_state
osh 'cd ~ && pgrep -f ddblat.jar >/dev/null && { echo "already running"; exit 1; }
     rm -f load.checkpoint; rm -rf results; mkdir -p results
     nohup java -XX:+UseZGC -Xms24g -Xmx24g -XX:+AlwaysPreTouch \
       -XX:StartFlightRecording=settings=profile,filename=/home/opc/results/run.jfr,maxsize=2G \
       -jar ddblat.jar --config ddblat.properties > ddblat.log 2>&1 < /dev/null &
     sleep 20; pgrep -af ddblat.jar | head -1'
cat <<EOF

Harness started on $INSTANCE_IP. Tail it:
  ssh -i $OCI_SSH_KEY opc@$INSTANCE_IP 'tail -f ~/ddblat.log'
Then: scripts/oci/40-collect.sh
EOF
