#!/usr/bin/env bash
# 10-provision.sh — VCN, subnet, client instance, Autonomous Database, and the DynamoDB API
# access key. Everything the run needs, created from nothing.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
[[ -f "$OCI_SCRIPTS_DIR/.preflight-ok" ]] || die "run scripts/oci/00-preflight.sh first"
load_state

ECPU="${ECPU:-64}"
STORAGE_GB="${STORAGE_GB:-256}"
OCPUS="${OCPUS:-8}"
MEM_GB="${MEM_GB:-32}"
DB_NAME="${DB_NAME:-ddblatprod}"

# ---- ssh key -----------------------------------------------------------------------------
if [[ ! -f "$OCI_SSH_KEY" ]]; then
  log "generating ssh key at $OCI_SSH_KEY"
  ssh-keygen -t ed25519 -N "" -f "$OCI_SSH_KEY" -C ddblat >/dev/null
fi

# ---- network -----------------------------------------------------------------------------
if [[ -z "${VCN_ID:-}" ]]; then
  log "creating VCN"
  VCN_ID=$(o network vcn create --compartment-id "$TENANCY" --cidr-blocks '["10.20.0.0/16"]' \
    --display-name ddblat-vcn --dns-label ddblat --wait-for-state AVAILABLE --query 'data.id' --raw-output)
  save VCN_ID "$VCN_ID"
  IGW=$(o network internet-gateway create --compartment-id "$TENANCY" --vcn-id "$VCN_ID" \
    --is-enabled true --display-name ddblat-igw --wait-for-state AVAILABLE --query 'data.id' --raw-output)
  save IGW_ID "$IGW"
  RT=$(o network vcn get --vcn-id "$VCN_ID" --query 'data."default-route-table-id"' --raw-output)
  o network route-table update --rt-id "$RT" --force \
    --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$IGW\"}]" >/dev/null
  SL=$(o network vcn get --vcn-id "$VCN_ID" --query 'data."default-security-list-id"' --raw-output)
  # Ingress is SSH only; the harness needs no inbound ports of its own.
  o network security-list update --security-list-id "$SL" --force \
    --egress-security-rules '[{"destination":"0.0.0.0/0","protocol":"all","isStateless":false}]' \
    --ingress-security-rules '[{"source":"0.0.0.0/0","protocol":"6","isStateless":false,"tcpOptions":{"destinationPortRange":{"min":22,"max":22}}}]' >/dev/null
  SUBNET_ID=$(o network subnet create --compartment-id "$TENANCY" --vcn-id "$VCN_ID" \
    --cidr-block 10.20.1.0/24 --display-name ddblat-public --dns-label pub \
    --prohibit-public-ip-on-vnic false --wait-for-state AVAILABLE --query 'data.id' --raw-output)
  save SUBNET_ID "$SUBNET_ID"
  log "network ready"
fi
load_state

# ---- autonomous database -----------------------------------------------------------------
if [[ -z "${ADB_ID:-}" ]]; then
  log "generating ADB admin password (never printed; stored 0600)"
  PWFILE="$HOME/.oci/ddblat-adb-admin.txt"
  umask 077
  python3 -c "
import secrets,string
a=string.ascii_lowercase;A=string.ascii_uppercase;d=string.digits;s='#_-'
pw=''.join([secrets.choice(A),secrets.choice(a),secrets.choice(d),secrets.choice(s)]+
           [secrets.choice(a+A+d+s) for _ in range(16)])
open('$PWFILE','w').write(pw)"
  chmod 600 "$PWFILE"

  log "creating Autonomous Database: $ECPU ECPU, ${STORAGE_GB} GB, Transaction Processing"
  # NOT --is-dev-tier: DynamoDB CreateTable silently never completes on Developer tier.
  # It returns CREATING and the table never appears. See docs.
  ADB_ID=$(o db autonomous-database create --compartment-id "$TENANCY" \
    --db-name "$DB_NAME" --display-name ddblat-adb --db-workload OLTP \
    --compute-model ECPU --compute-count "$ECPU" --data-storage-size-in-gbs "$STORAGE_GB" \
    --admin-password "$(cat "$PWFILE")" --license-model LICENSE_INCLUDED \
    --wait-for-state AVAILABLE --query 'data.id' --raw-output)
  save ADB_ID "$ADB_ID"

  # The DynamoDB API is enabled by a tag applied as an UPDATE. Passing it at create time
  # persists the tag but fires no enablement work request.
  log "enabling the DynamoDB API (tag update; takes up to 10 minutes)"
  o db autonomous-database update --autonomous-database-id "$ADB_ID" --force \
    --freeform-tags '{"adb$feature":"{\"name\":\"DynamoDB_API\",\"enable\":true}"}' >/dev/null
  for _ in $(seq 1 40); do
    st=$(o db autonomous-database get --autonomous-database-id "$ADB_ID" --query 'data."lifecycle-state"' --raw-output)
    [[ "$st" == "AVAILABLE" ]] && break
    sleep 20
  done
  log "database ready"
fi
load_state

# ---- DynamoDB API access key --------------------------------------------------------------
KEYFILE="$HOME/.oci/ddblat-adb-keys.json"
if [[ ! -s "$KEYFILE" ]]; then
  log "minting DynamoDB API access key"
  URL="https://dataaccess.adb.${OCI_REGION}.oraclecloudapps.com/adb/auth/v1/databases/${ADB_ID}/accesskeys"
  umask 077
  for i in $(seq 1 10); do
    code=$(curl -sS -o "$KEYFILE" -w '%{http_code}' -X POST "$URL" \
      --user "ADMIN:$(cat "$HOME/.oci/ddblat-adb-admin.txt")" \
      --header 'Content-Type: application/json' \
      --data-raw '{"name":"ddblat","permissions":[{"actions":["ADMIN_ANY"]}],"expiration_minutes":1440}')
    [[ "$code" == "200" || "$code" == "201" ]] && break
    log "  attempt $i: HTTP $code (API may still be activating)"; sleep 45
  done
  [[ -s "$KEYFILE" ]] || die "could not mint an access key"
  chmod 600 "$KEYFILE"
  log "access key stored at $KEYFILE (never printed)"
fi

# ---- client instance -----------------------------------------------------------------------
if [[ -z "${INSTANCE_ID:-}" ]]; then
  IMG=$(o compute image list --compartment-id "$TENANCY" --operating-system "Oracle Linux" \
    --operating-system-version "9" --shape VM.Standard.E5.Flex --sort-by TIMECREATED --limit 1 \
    --query 'data[0].id' --raw-output)
  log "launching client: VM.Standard.E5.Flex ${OCPUS} OCPU / ${MEM_GB} GB in $AD"
  INSTANCE_ID=$(o compute instance launch --compartment-id "$TENANCY" --availability-domain "$AD" \
    --subnet-id "$SUBNET_ID" --display-name ddblat-client --shape VM.Standard.E5.Flex \
    --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEM_GB}" --image-id "$IMG" \
    --assign-public-ip true --ssh-authorized-keys-file "${OCI_SSH_KEY}.pub" \
    --wait-for-state RUNNING --query 'data.id' --raw-output)
  save INSTANCE_ID "$INSTANCE_ID"
  INSTANCE_IP=$(o compute instance list-vnics --instance-id "$INSTANCE_ID" --query 'data[0]."public-ip"' --raw-output)
  save INSTANCE_IP "$INSTANCE_IP"
  log "client at $INSTANCE_IP"
fi
load_state

log "waiting for ssh"
for _ in $(seq 1 30); do osh 'true' 2>/dev/null && break; sleep 10; done
log "installing JDK 21"
osh 'sudo dnf install -y java-21-openjdk-headless >/dev/null 2>&1; java -version' 2>&1 | tail -1
log "provisioned. next: scripts/oci/20-deploy.sh <config.properties>"
