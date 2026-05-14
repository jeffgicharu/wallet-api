#!/usr/bin/env bash
# Exercises every wallet-api HTTP endpoint against the live deploy at
# https://wallet-api.jeffgicharu.com. Read-mostly: logs in as alice@demo.local
# and exercises GET endpoints + a tiny 1 KES deposit + same-recipient reversal
# so we leave the live demo data essentially untouched. Rate-limited to
# 2 req/s to be a good citizen on a small-VPS public demo.
#
# Output: markdown table to stdout + scripts/verify-endpoints-result-live.md
# (gitignored).
set -u

API="${API:-https://wallet-api.jeffgicharu.com}"
OUT="$(dirname "$0")/verify-endpoints-result-live.md"
PASS="${LIVE_PASS:-pass1234}"
PIN="${LIVE_PIN:-1234}"
SLEEP_MS=500  # 2 req/s ceiling

log() { printf '%s\n' "$@" >&2; }

# Sleep between calls to stay under 2 req/s.
sleep_throttle() { sleep "$(awk "BEGIN { printf \"%.3f\", $SLEEP_MS / 1000 }")"; }

# run_endpoint METHOD PATH BODY TOKEN ASSERTION_GREP DESCRIPTION
run_endpoint() {
  local method=$1 path=$2 body=$3 token=$4 grep_expr=$5 desc=$6
  local body_file out status millis assertion args
  body_file=$(mktemp)
  args=(-sS -o "$body_file" -w '%{http_code} %{time_total}' -X "$method" "$API$path")
  args+=(-H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-d "$body")
  out=$(curl "${args[@]}" 2>/dev/null || echo "000 0.000")
  status=$(printf '%s' "$out" | awk '{print $1}')
  millis=$(printf '%s' "$out" | awk '{printf "%d", $2 * 1000}')
  if [ -n "$grep_expr" ] && [ "$grep_expr" != "-" ]; then
    if grep -qE "$grep_expr" "$body_file" 2>/dev/null; then
      assertion="PASS"
    else
      assertion="FAIL"
    fi
  else
    assertion="-"
  fi
  printf '| %-6s | %-50s | %3s | %5sms | %-7s | %s |\n' \
    "$method" "${path:0:50}" "$status" "$millis" "$assertion" "$desc" \
    | tee -a "$OUT"
  rm -f "$body_file"
  sleep_throttle
}

login_token() {
  local email=$1
  curl -sS -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$PASS\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['token'])"
}

NOW_MS=$(date +%s%3N)
mkdir -p "$(dirname "$OUT")"
{
  echo "# Live endpoint verification"
  echo
  echo "Target: \`$API\`"
  echo "Generated at: $(date -Iseconds)"
  echo "Throttled to ~2 req/s. Read-mostly: one 1 KES deposit + reversal of the same to keep ledger drift tiny."
  echo
  echo "| Method | Path | Status | Time | Assert | Description |"
  echo "|---|---|---:|---:|---|---|"
} > "$OUT"

log "[verify-live] health probes"
run_endpoint GET    /actuator/health                              '' '' '"status":"UP"'   "Actuator health"
run_endpoint GET    /api-docs                                     '' '' '"openapi"'       "OpenAPI spec served"

log "[verify-live] auth (alice)"
run_endpoint POST   /api/auth/login                               "{\"email\":\"alice@demo.local\",\"password\":\"$PASS\"}" '' '"token"' "Login alice@demo.local"
ALICE_TOKEN=$(login_token alice@demo.local)
BOB_TOKEN=$(login_token bob@demo.local)

log "[verify-live] wallet read"
run_endpoint GET    /api/wallet                                   '' "$ALICE_TOKEN" '"balance"' "Wallet for alice"

log "[verify-live] transactions read"
run_endpoint GET    /api/wallet/transactions                      '' "$ALICE_TOKEN" '"content"' "Transactions for alice (page 0)"
run_endpoint GET    /api/wallet/transactions?type=DEPOSIT         '' "$ALICE_TOKEN" '"content"' "Transactions filtered by type"
run_endpoint GET    /api/wallet/statement                         '' "$ALICE_TOKEN" '\['       "Account statement (ledger)"

log "[verify-live] cross-user lookup (issue #20)"
# alice tries to fetch DEP-seed-bob — should be 404 once #20 is fixed.
run_endpoint GET    /api/wallet/transactions/DEP-seed-bob         '' "$ALICE_TOKEN" '' "Cross-user lookup of DEP-seed-bob (issue #20: 200 today)"

log "[verify-live] tiny deposit + reversal (keep ledger drift small)"
DEP_KEY="verify-live-${NOW_MS}"
DEP_BODY="{\"amount\":1,\"idempotencyKey\":\"$DEP_KEY\"}"
run_endpoint POST   /api/wallet/deposit                           "$DEP_BODY" "$ALICE_TOKEN" '"status":"COMPLETED"' "Deposit 1 KES (verification probe)"
run_endpoint POST   "/api/wallet/transactions/DEP-${DEP_KEY}/reverse?reason=verify-script" '' "$ALICE_TOKEN" '"success":true' "Reverse the verification deposit"

log "[verify-live] admin endpoints (issue #2: alice's JWT is regular user)"
run_endpoint GET    /api/admin/stats                              '' "$ALICE_TOKEN" '' "Admin stats (issue #2: should be 403)"
run_endpoint GET    /api/admin/audit                              '' "$ALICE_TOKEN" '' "Admin audit log (issue #2: should be 403)"
run_endpoint GET    /api/admin/reconcile                          '' "$ALICE_TOKEN" '' "System reconciliation (issue #11)"
run_endpoint GET    "/api/admin/reconcile/wallet/+254700000001"   '' "$ALICE_TOKEN" '' "Per-wallet reconciliation"
run_endpoint GET    /api/admin/users/search?phone=%2B254700000001 '' "$ALICE_TOKEN" '' "Admin user search (issue #2)"

log "[verify-live] negative"
run_endpoint POST   /api/auth/login                               '{"email":"nobody@nowhere.local","password":"wrong"}' '' '' "Invalid login (expect 401)"
run_endpoint GET    /api/wallet                                   '' '' '' "Wallet without JWT (expect 401/403)"

{
  echo
  echo "_Generated by \`scripts/verify-endpoints-live.sh\`._"
} >> "$OUT"

log "[verify-live] result table written to $OUT"
