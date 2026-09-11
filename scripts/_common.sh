#!/usr/bin/env bash
# _common.sh — shared helpers. Sourced, never executed.
#
# DRY_RUN=1 turns every mutating call into a printed command line and every
# discovery call into a printed command line plus a stub id, so the entire
# pipeline can be walked end to end without touching the account. That is the
# test for these scripts: shellcheck proves they parse, DRY_RUN proves they
# sequence.
#
# Do NOT add `set -x` to any script in this directory. 00-preflight-quota.sh
# handles a secret access key, and xtrace would put it in the terminal
# scrollback and in any CI log.

export AWS_PROFILE="${AWS_PROFILE:-ddblat}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
export AWS_PAGER=""

DDBLAT_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DDBLAT_REPO_ROOT="$(cd "$DDBLAT_SCRIPTS_DIR/.." && pwd)"
STATE_FILE="$DDBLAT_SCRIPTS_DIR/.provisioned"
RUN_FILE="$DDBLAT_SCRIPTS_DIR/.run"
PREFLIGHT_FILE="$DDBLAT_SCRIPTS_DIR/.preflight-ok"
export DDBLAT_SCRIPTS_DIR DDBLAT_REPO_ROOT STATE_FILE RUN_FILE PREFLIGHT_FILE

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Mutating call. Under DRY_RUN prints the exact argv and returns success.
run() {
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    { printf 'DRY-RUN     '; printf ' %q' "$@"; printf '\n'; } >&2
    return 0
  fi
  "$@"
}

# Discovery call that a variable is assigned from. First argument is the stub
# value handed back under DRY_RUN so downstream interpolation stays plausible.
query() {
  local stub="$1"; shift
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    { printf 'DRY-RUN(q)  '; printf ' %q' "$@"; printf '   -> %s\n' "$stub"; } >&2
    printf '%s\n' "$stub"
    return 0
  fi
  "$@"
}

state_put() {   # state_put KEY VALUE — upsert into scripts/.provisioned
  local k="$1" v="$2"
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'DRY-RUN(st)  %s=%s\n' "$k" "$v" >&2
    return 0
  fi
  touch "$STATE_FILE"
  sed -i.bak "/^${k}=/d" "$STATE_FILE" && rm -f "$STATE_FILE.bak"
  printf '%s=%s\n' "$k" "$v" >> "$STATE_FILE"
}

load_state() {
  [[ -f "$STATE_FILE" ]] || die "no $STATE_FILE — run scripts/10-provision.sh first"
  set -a
  # shellcheck disable=SC1090
  . "$STATE_FILE"
  set +a
}

load_run() {
  [[ -f "$RUN_FILE" ]] || die "no $RUN_FILE — run scripts/20-deploy.sh first"
  set -a
  # shellcheck disable=SC1090
  . "$RUN_FILE"
  set +a
}

require_preflight() {
  [[ -f "$PREFLIGHT_FILE" ]] || die "quota gate not passed — run scripts/00-preflight-quota.sh first"
}

# Push a shell command to the instance and wait for it. The security group has no
# inbound rule at all, so this is the only channel that exists. The payload is
# JSON-encoded by python rather than by shell quoting because the commands embed
# quotes, newlines and $ signs.
ssm_run() {
  local iid="$1" cmd="$2" params cid status
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'DRY-RUN(ssm %s):\n%s\n' "$iid" "$cmd" >&2
    return 0
  fi
  params=$(printf '%s' "$cmd" | python3 -c \
    'import json,sys; print(json.dumps({"commands":[sys.stdin.read()]}))')
  cid=$(aws ssm send-command \
          --instance-ids "$iid" \
          --document-name AWS-RunShellScript \
          --timeout-seconds 600 \
          --parameters "$params" \
          --query 'Command.CommandId' --output text)
  aws ssm wait command-executed --command-id "$cid" --instance-id "$iid" >/dev/null 2>&1 || true
  status=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$iid" \
             --query 'Status' --output text)
  # StandardOutputContent can lag a beat behind Status flipping to Success --
  # observed in practice as a Success status paired with an empty content
  # field on the very next call. Retry briefly rather than handing the caller
  # a false-empty result; a command whose real output is empty just exhausts
  # the retries and returns empty, same as before.
  local out=""
  if [[ "$status" == "Success" ]]; then
    for _ in 1 2 3 4 5; do
      out=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$iid" \
              --query 'StandardOutputContent' --output text)
      [[ -n "$out" && "$out" != "None" ]] && break
      sleep 1
    done
  else
    out=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$iid" \
            --query 'StandardOutputContent' --output text)
  fi
  printf '%s\n' "$out"
  if [[ "$status" != "Success" ]]; then
    aws ssm get-command-invocation --command-id "$cid" --instance-id "$iid" \
             --query 'StandardErrorContent' --output text >&2
    die "SSM command $cid on $iid ended $status"
  fi
}
