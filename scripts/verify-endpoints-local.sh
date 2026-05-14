#!/usr/bin/env bash
# Exercises every wallet-api HTTP endpoint against a local docker-compose stack
# (http://localhost:8080 by default). Registers a fresh throwaway user, deposits,
# transfers to a second throwaway user, reads the ledger, exercises the admin
# routes (which today still 200 for a non-admin JWT — issue #2), and prints a
# markdown table of status + wall-clock time + a one-line correctness assertion.
#
# Idempotent: each run uses unique idempotency keys + a unique phone suffix so
# it can run repeatedly without colliding with previous runs.
set -u

API="${API:-http://localhost:8080}"
OUT="$(dirname "$0")/verify-endpoints-result.md"
NOW_MS=$(date +%s%3N)
SUFFIX="${NOW_MS:5:7}"
PHONE_A="+25471${SUFFIX}"
PHONE_B="+25472${SUFFIX}"
EMAIL_A="verify-a-${NOW_MS}@local"
EMAIL_B="verify-b-${NOW_MS}@local"
PASS="pass1234"
PIN="1234"

log() { printf '%s\n' "$@" >&2; }

# run_endpoint METHOD PATH BODY TOKEN ASSERTION_GREP DESCRIPTION
#   TOKEN: pass "" for no auth, otherwise the bearer token (no "Bearer " prefix).
#   ASSERTION_GREP: regex run against the response body; "-" / "" disables.
run_endpoint() {
  local method=$1 path=$2 body=$3 token=$4 grep_expr=$5 desc=$6
  local body_file status millis assertion args
  body_file=$(mktemp)
  args=(-sS -o "$body_file" -w '%{http_code} %{time_total}' -X "$method" "$API$path")
  args+=(-H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-d "$body")
  local out
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
}

login_token() {
  local email=$1
  curl -sS -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$PASS\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['token'])"
}

extract_ref() {
  python3 -c "import sys,json; d=json.load(open('$1')); print(d['data']['reference'])"
}

mkdir -p "$(dirname "$OUT")"
{
  echo "# Local endpoint verification"
  echo
  echo "Target: \`$API\`"
  echo "Generated at: $(date -Iseconds)"
  echo
  echo "| Method | Path | Status | Time | Assert | Description |"
  echo "|---|---|---:|---:|---|---|"
} > "$OUT"

log "[verify-local] precheck"
run_endpoint GET    /actuator/health                              '' '' '"status":"UP"'       "Actuator health"
run_endpoint GET    /api-docs                                     '' '' '"openapi"'           "OpenAPI spec served"

log "[verify-local] auth"
BODY_A="{\"fullName\":\"Verify A\",\"email\":\"$EMAIL_A\",\"phoneNumber\":\"$PHONE_A\",\"password\":\"$PASS\",\"pin\":\"$PIN\"}"
BODY_B="{\"fullName\":\"Verify B\",\"email\":\"$EMAIL_B\",\"phoneNumber\":\"$PHONE_B\",\"password\":\"$PASS\",\"pin\":\"$PIN\"}"
run_endpoint POST   /api/auth/register                            "$BODY_A" '' '"success":true' "Register user A"
run_endpoint POST   /api/auth/register                            "$BODY_B" '' '"success":true' "Register user B"
run_endpoint POST   /api/auth/login                               "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS\"}" '' '"token"' "Login user A"
TOKEN_A=$(login_token "$EMAIL_A")
TOKEN_B=$(login_token "$EMAIL_B")

log "[verify-local] wallet read"
run_endpoint GET    /api/wallet                                   '' "$TOKEN_A" '"balance"' "Wallet balance for A"

log "[verify-local] deposit"
DEP_KEY="local-verify-dep-${NOW_MS}"
DEP_BODY="{\"amount\":10000,\"idempotencyKey\":\"$DEP_KEY\"}"
run_endpoint POST   /api/wallet/deposit                           "$DEP_BODY" "$TOKEN_A" '"status":"COMPLETED"' "Deposit 10000 KES"
DEP_REF="DEP-${DEP_KEY}"   # wallet-api derives the reference from the idempotency key

log "[verify-local] transactions"
run_endpoint GET    /api/wallet/transactions                      '' "$TOKEN_A" '"content"' "Transaction history (page 0)"
run_endpoint GET    /api/wallet/transactions?type=DEPOSIT         '' "$TOKEN_A" '"content"' "Transaction history filtered by type"
if [ -n "$DEP_REF" ]; then
  run_endpoint GET  "/api/wallet/transactions/$DEP_REF"           '' "$TOKEN_A" '"reference"' "Transaction lookup by reference"
  run_endpoint GET  "/api/wallet/transactions/$DEP_REF"           '' "$TOKEN_B" '"reference"' "Cross-user lookup (issue #20)"
fi

log "[verify-local] statement"
run_endpoint GET    /api/wallet/statement                         '' "$TOKEN_A" '\[' "Account statement (ledger)"

log "[verify-local] transfer A->B"
TRF_KEY="local-verify-trf-${NOW_MS}"
TRF_BODY="{\"recipientPhone\":\"$PHONE_B\",\"amount\":500,\"pin\":\"$PIN\",\"idempotencyKey\":\"$TRF_KEY\"}"
run_endpoint POST   /api/wallet/transfer                          "$TRF_BODY" "$TOKEN_A" '"status":"COMPLETED"' "Transfer 500 KES A->B"
TRF_REF="TRF-${TRF_KEY}"

log "[verify-local] idempotency replay (issue #10)"
run_endpoint POST   /api/wallet/transfer                          "$TRF_BODY" "$TOKEN_A" '' "Duplicate idempotency key (issue #10: 409 today)"

log "[verify-local] reversal"
if [ -n "$TRF_REF" ]; then
  run_endpoint POST "/api/wallet/transactions/$TRF_REF/reverse?reason=verify-script" '' "$TOKEN_A" '"success":true' "Reverse the transfer"
fi

log "[verify-local] withdrawal"
WD_KEY="local-verify-wd-${NOW_MS}"
WD_BODY="{\"amount\":200,\"pin\":\"$PIN\",\"idempotencyKey\":\"$WD_KEY\"}"
run_endpoint POST   /api/wallet/withdraw                          "$WD_BODY" "$TOKEN_A" '"status":"COMPLETED"' "Withdrawal 200 KES"

log "[verify-local] admin endpoints (issue #2: any authed JWT)"
run_endpoint GET    "/api/admin/users/search?phone=$PHONE_A"      '' "$TOKEN_A" '' "Admin user search (issue #2: should be 403)"
run_endpoint GET    /api/admin/stats                              '' "$TOKEN_A" '' "Admin stats (issue #2: should be 403)"
run_endpoint GET    /api/admin/audit                              '' "$TOKEN_A" '' "Admin audit log (issue #2: should be 403)"
run_endpoint GET    /api/admin/reconcile                          '' "$TOKEN_A" '' "System reconciliation (issue #11: balanced=false)"
run_endpoint GET    "/api/admin/reconcile/wallet/$PHONE_A"        '' "$TOKEN_A" '' "Per-wallet reconciliation"

log "[verify-local] negative"
run_endpoint POST   /api/auth/login                               '{"email":"nobody@nowhere.local","password":"wrong"}' '' '' "Invalid login (expect 401)"
run_endpoint GET    /api/wallet                                   '' '' '' "Wallet without JWT (expect 401)"

{
  echo
  echo "_Generated by \`scripts/verify-endpoints-local.sh\`._"
} >> "$OUT"

log "[verify-local] result table written to $OUT"
