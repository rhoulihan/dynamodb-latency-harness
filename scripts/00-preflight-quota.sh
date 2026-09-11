#!/usr/bin/env bash
# 00-preflight-quota.sh — install the AWS CLI, configure the ddblat profile from
# the admin key, and refuse to let the rest of the pipeline proceed unless all
# four DynamoDB capacity quotas admit 40,000.
#
# The admin key is used only here and only from the Mac (spec §9). It is never
# echoed, never passed on a command line where `ps` could read it, and never
# copied to the instance.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"

CSV="${DDBLAT_CSV:-$HOME/Downloads/admin_accessKeys.csv}"
PROFILE="ddblat"
REGION="us-east-1"
REQUIRED=40000

# ---------------------------------------------------------------- AWS CLI v2 --
if ! command -v aws >/dev/null 2>&1; then
  log "AWS CLI not found — installing v2 via Homebrew"
  run brew install awscli
fi
aws --version 2>&1 | grep -q '^aws-cli/2\.' \
  || die "aws-cli v2 required, found: $(aws --version 2>&1)"
log "aws-cli: $(aws --version 2>&1)"

# ------------------------------------------------------------- named profile --
# Written with python's configparser rather than `aws configure set` so the
# secret never appears in argv. The CSV is read as utf-8-sig, which strips the
# UTF-8 BOM that the IAM console prepends.
[[ -f "$CSV" ]] || die "credentials CSV not found at $CSV"
if [[ "${DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN: would write profile [$PROFILE] into ~/.aws/credentials from $CSV"
else
  python3 - "$CSV" "$PROFILE" "$REGION" <<'PY'
import configparser, csv, os, sys, stat

csv_path, profile, region = sys.argv[1], sys.argv[2], sys.argv[3]

with open(csv_path, newline="", encoding="utf-8-sig") as fh:   # utf-8-sig eats the BOM
    rows = list(csv.reader(fh))
rows = [r for r in rows if r and any(c.strip() for c in r)]
if len(rows) != 2:
    sys.exit(f"expected header + exactly one data row in {csv_path}, got {len(rows)} rows")
header = [c.strip().lower() for c in rows[0]]
if header != ["access key id", "secret access key"]:
    sys.exit(f"unexpected header {rows[0]!r}")
key_id, secret = (c.strip() for c in rows[1])
if not key_id.startswith("AKIA") or len(key_id) != 20 or len(secret) != 40:
    sys.exit("credential row does not look like an IAM long-term access key")

aws_dir = os.path.expanduser("~/.aws")
os.makedirs(aws_dir, exist_ok=True)

def upsert(path, section, values):
    cp = configparser.ConfigParser()
    if os.path.exists(path):
        cp.read(path)
    if not cp.has_section(section):
        cp.add_section(section)
    for k, v in values.items():
        cp.set(section, k, v)
    with open(path, "w") as fh:
        cp.write(fh)
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)          # 0600

upsert(os.path.join(aws_dir, "credentials"), profile,
       {"aws_access_key_id": key_id, "aws_secret_access_key": secret})
upsert(os.path.join(aws_dir, "config"), f"profile {profile}",
       {"region": region, "output": "json"})

print(f"profile [{profile}] written for key {key_id[:8]}****{key_id[-4:]}")
PY
fi

# ------------------------------------------------------------------ identity --
log "verifying identity"
query '{"Account":"111122223333"}' aws sts get-caller-identity --output json

# -------------------------------------------------------------------- quotas --
QLIST="$(mktemp -t ddblat-quotas)"
trap 'rm -f "$QLIST"' EXIT

if [[ "${DRY_RUN:-0}" == "1" ]]; then
  {
    printf 'L-CF0CBE56\t40000.0\tTable-level read throughput limit\n'
    printf 'L-AB614373\t40000.0\tTable-level write throughput limit\n'
    printf 'L-34F6A552\t80000.0\tAccount-level read throughput limit (Provisioned mode)\n'
    printf 'L-34F8CCC8\t80000.0\tAccount-level write throughput limit (Provisioned mode)\n'
  } > "$QLIST"
  log "DRY-RUN: using synthetic quota values"
else
  aws service-quotas list-service-quotas --service-code dynamodb \
      --query 'Quotas[].[QuotaCode,Value,QuotaName]' --output text > "$QLIST"
fi

echo
echo "DynamoDB capacity/throughput quotas as applied to this account:"
grep -Ei 'capacity|throughput' "$QLIST" || echo "  (none matched — see full list below)"
echo

fail=0
check_quota() {
  local code="$1" label="$2" value
  value=$(awk -F'\t' -v c="$code" '$1 == c { print $2; exit }' "$QLIST")
  if [[ -z "$value" ]]; then
    # A quota that has never been adjusted can be absent from the applied list;
    # fall back to the account value, then to the AWS default. Routed through
    # query() (stubbed to $REQUIRED) even though this is unreachable under the
    # current DRY_RUN synthetic QLIST -- a future edit that calls check_quota
    # with a code absent from that synthetic list must still not be able to
    # make a real call under DRY_RUN.
    value=$(query "$REQUIRED" aws service-quotas get-service-quota --service-code dynamodb \
              --quota-code "$code" --query 'Quota.Value' --output text 2>/dev/null \
            || query "$REQUIRED" aws service-quotas get-aws-default-service-quota --service-code dynamodb \
              --quota-code "$code" --query 'Quota.Value' --output text 2>/dev/null || true)
  fi
  if [[ -z "$value" || "$value" == "None" ]]; then
    printf '  %-58s %12s   required %d   UNRESOLVED\n' "$label" "?" "$REQUIRED"
    fail=1
    return
  fi
  if awk -v v="$value" -v r="$REQUIRED" 'BEGIN { exit !(v + 0 >= r) }'; then
    printf '  %-58s %12.0f   required %d   OK\n' "$label" "$value" "$REQUIRED"
  else
    printf '  %-58s %12.0f   required %d   TOO LOW\n' "$label" "$value" "$REQUIRED"
    fail=1
  fi
}

echo "Gate: every quota below must be >= $REQUIRED"
check_quota L-CF0CBE56 "Table-level read throughput limit (RCU per table)"
check_quota L-AB614373 "Table-level write throughput limit (WCU per table)"
check_quota L-34F6A552 "Account-level read throughput limit (provisioned)"
check_quota L-34F8CCC8 "Account-level write throughput limit (provisioned)"
echo

if [[ "$fail" -ne 0 ]]; then
  cat >&2 <<EOF
QUOTA GATE FAILED.

At least one DynamoDB capacity quota is below $REQUIRED, or could not be
resolved by quota code. Do not provision anything.

  1. Confirm the codes still map to the right quotas:
       aws service-quotas list-service-quotas --service-code dynamodb \\
         --query "Quotas[?contains(QuotaName,'throughput')].[QuotaCode,Value,QuotaName]" \\
         --output table
  2. Request an increase to $REQUIRED for each failing quota:
       aws service-quotas request-service-quota-increase --service-code dynamodb \\
         --quota-code <CODE> --desired-value $REQUIRED
  3. Turnaround is a support ticket, historically days not hours (spec §13 risk 1).
     Re-run this script once the increase is approved.
EOF
  exit 1
fi

run touch "$PREFLIGHT_FILE"
log "quota gate PASSED — wrote $PREFLIGHT_FILE"
