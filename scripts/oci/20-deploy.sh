#!/usr/bin/env bash
# 20-deploy.sh <config.properties> — build the shaded jar and stage it, the config and the
# access key onto the client instance.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
load_state
CONFIG="${1:-}"; [[ -n "$CONFIG" && -f "$CONFIG" ]] || die "usage: $0 <config.properties>"

cd "$DDBLAT_REPO_ROOT"
log "building shaded jar"
mvn -q clean package || die "build failed"

# The OCID and key path are account-specific, so they are injected here rather than checked in.
GEN="$DDBLAT_REPO_ROOT/target/ddblat-oci.properties"
{
  grep -vE '^(ociDatabaseOcid|ociKeyFile|region|provider|resultsDir)=' "$CONFIG"
  echo ""
  echo "# --- injected by scripts/oci/20-deploy.sh ---"
  echo "provider=oci"
  echo "region=$OCI_REGION"
  echo "ociDatabaseOcid=$ADB_ID"
  echo "ociKeyFile=/home/opc/ddblat-keys.json"
  echo "resultsDir=/home/opc/results"
} > "$GEN"

log "staging to $INSTANCE_IP"
osh 'mkdir -p ~/results'
oscp target/ddblat.jar "$GEN" "$HOME/.oci/ddblat-adb-keys.json" "opc@${INSTANCE_IP}:~/" >/dev/null
osh 'mv -f ~/ddblat-adb-keys.json ~/ddblat-keys.json && chmod 600 ~/ddblat-keys.json
     mv -f ~/ddblat-oci.properties ~/ddblat.properties'
LOCAL=$(shasum -a 256 target/ddblat.jar | cut -d' ' -f1)
REMOTE=$(osh 'sha256sum ~/ddblat.jar' | cut -d' ' -f1)
[[ "$LOCAL" == "$REMOTE" ]] || die "jar sha mismatch: $LOCAL vs $REMOTE"
log "jar verified: $LOCAL"
log "deployed. next: scripts/oci/30-run.sh"
