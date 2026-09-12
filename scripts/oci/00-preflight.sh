#!/usr/bin/env bash
# 00-preflight.sh — refuse to let the pipeline proceed unless the account can support the test.
#
# The OCI analogue of the AWS quota gate. Checks the three things that actually stop a run:
# an authenticated CLI, ATP ECPU headroom, and compute headroom.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

ECPU_NEEDED="${ECPU_NEEDED:-64}"
OCPU_NEEDED="${OCPU_NEEDED:-8}"

log "checking CLI authentication (profile=$OCI_PROFILE region=$OCI_REGION)"
TENANCY=$(o iam region-subscription list --query 'data[0]."tenancy-id"' --raw-output 2>/dev/null) \
  || die "oci CLI cannot authenticate. Create an API key and a [$OCI_PROFILE] profile in ~/.oci/config."
[[ -n "$TENANCY" ]] || TENANCY=$(grep -m1 '^tenancy=' ~/.oci/config | cut -d= -f2)
log "tenancy: ${TENANCY:0:28}..."
save TENANCY "$TENANCY"

avail() {
  o limits resource-availability get --compartment-id "$TENANCY" --service-name "$1" \
    --limit-name "$2" ${3:+--availability-domain "$3"} 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"].get("available") or 0)' 2>/dev/null || echo 0
}

E=$(avail database atp-ecpu-count)
log "ATP ECPU available: $E (need $ECPU_NEEDED)"
(( E >= ECPU_NEEDED )) || die "not enough ATP ECPU headroom: $E < $ECPU_NEEDED"

AD=$(o iam availability-domain list --compartment-id "$TENANCY" --query 'data[0].name' --raw-output)
C=$(avail compute standard-e5-core-count "$AD")
log "E5 compute cores available in $AD: $C (need $OCPU_NEEDED)"
(( C >= OCPU_NEEDED )) || die "not enough compute headroom: $C < $OCPU_NEEDED"

save AD "$AD"
touch "$OCI_SCRIPTS_DIR/.preflight-ok"
log "preflight PASSED"
