#!/usr/bin/env bash
# Populates the local perf environment with a realistic data volume:
#   - 500 users (alice@perf.local, bob@perf.local, …, with phone +254800NNNNNN)
#   - Each user starts with KES 100,000 (one deposit per user)
#   - 4,000 random transfers between users (≈5,000 total transactions counting deposits)
#
# Idempotent: wipes all wallet tables first via psql, then re-creates everything.
# Targets the perf compose stack on http://localhost:8080.
#
# Aims to complete in under 90 s by parallelising registers and deposits with
# xargs -P, and by issuing transfers in parallel batches.

set -euo pipefail

API="${TARGET_BASE_URL:-http://localhost:8080}"
PG_CONTAINER="${PG_CONTAINER:-wallet-api-perf-postgres}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-walletdb_perf}"
USER_COUNT="${USER_COUNT:-500}"
TRANSFER_COUNT="${TRANSFER_COUNT:-4000}"
PARALLEL="${PARALLEL:-20}"
SEED_OUT="${SEED_OUT:-performance/k6/lib/users.json}"

START=$(date +%s)
log() { printf '[%s] %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }

# 1. Wipe DB tables to make this idempotent.
log "Wiping perf DB tables in $PG_CONTAINER ..."
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -c \
  "TRUNCATE TABLE audit_logs, ledger_entries, transactions, wallets, users RESTART IDENTITY CASCADE;" \
  >/dev/null

# 2. Wait for the API to be ready.
log "Waiting for API at $API/actuator/health ..."
for _ in $(seq 1 60); do
  if curl -fsS "$API/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done

# 3. Build user list deterministically (alice000..alice499).
SEEDFILE="$(mktemp)"
trap 'rm -f "$SEEDFILE"' EXIT
for i in $(seq -f "%03g" 0 $((USER_COUNT - 1))); do
  printf '%s\t%s\t%s\n' "perfuser-${i}@perf.local" "+254800${i}000" "$i"
done > "$SEEDFILE"

# 4. Register 500 users in parallel.
log "Registering $USER_COUNT users (parallel=$PARALLEL) ..."
register_one() {
  local email="$1" phone="$2" pin="$3"
  curl -fsS -o /dev/null -X POST "$API/api/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"fullName\":\"Perf User $pin\",\"email\":\"$email\",\"phoneNumber\":\"$phone\",\"password\":\"password123\",\"pin\":\"1234\"}" \
    || true
}
export -f register_one
export API
< "$SEEDFILE" awk '{print $1, $2, $3}' \
  | xargs -P "$PARALLEL" -L 1 bash -c 'register_one "$@"' _

# 5. Login + deposit 100,000 for each user. We need the JWT for each.
log "Capturing tokens and depositing KES 100,000 per user ..."
TOKENS_FILE="$(mktemp)"
login_and_deposit() {
  local email="$1"
  local token
  token=$(curl -fsS -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"password123\"}" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [[ -z "$token" ]]; then return 0; fi
  curl -fsS -o /dev/null -X POST "$API/api/wallet/deposit" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' \
    -d "{\"amount\":100000,\"idempotencyKey\":\"seed-dep-$email\"}" || true
  printf '%s\t%s\n' "$email" "$token"
}
export -f login_and_deposit
< "$SEEDFILE" awk '{print $1}' \
  | xargs -P "$PARALLEL" -I {} bash -c 'login_and_deposit "$@"' _ {} > "$TOKENS_FILE"

ACTUAL_USERS=$(wc -l < "$TOKENS_FILE")
log "Captured tokens for $ACTUAL_USERS users."

# 6. Write users.json for k6 to consume.
log "Writing user list to $SEED_OUT ..."
mkdir -p "$(dirname "$SEED_OUT")"
{
  echo '['
  paste "$SEEDFILE" "$TOKENS_FILE" | awk -F'\t' 'BEGIN{c=0}
    {
      if ($1 == $4) {
        if (c++ > 0) printf ",\n";
        printf "  {\"email\":\"%s\",\"phone\":\"%s\",\"pin\":\"1234\",\"token\":\"%s\"}", $1, $2, $5;
      }
    }
    END{ printf "\n" }'
  echo ']'
} > "$SEED_OUT"

# 7. Issue random transfers. Pick random sender/receiver pairs.
log "Issuing $TRANSFER_COUNT random transfers (parallel=$PARALLEL) ..."
transfer_one() {
  local senderEmail="$1" senderToken="$2" recipientPhone="$3" amount="$4" key="$5"
  curl -fsS -o /dev/null -X POST "$API/api/wallet/transfer" \
    -H "Authorization: Bearer $senderToken" \
    -H 'Content-Type: application/json' \
    -d "{\"recipientPhone\":\"$recipientPhone\",\"amount\":$amount,\"pin\":\"1234\",\"idempotencyKey\":\"$key\"}" \
    || true
}
export -f transfer_one
python3 - "$TOKENS_FILE" "$SEEDFILE" "$TRANSFER_COUNT" <<'PY' > /tmp/transfer-jobs
import sys, random, time
tokens_path, seed_path, count = sys.argv[1], sys.argv[2], int(sys.argv[3])
tokens = {}
with open(tokens_path) as f:
    for line in f:
        e, t = line.rstrip('\n').split('\t', 1)
        tokens[e] = t
phones = {}
with open(seed_path) as f:
    for line in f:
        e, p, _ = line.rstrip('\n').split('\t')
        phones[e] = p
emails = list(tokens)
for i in range(count):
    s = random.choice(emails)
    r = random.choice(emails)
    while r == s:
        r = random.choice(emails)
    amt = random.choice([100, 250, 500, 1000])
    key = f"seed-trf-{int(time.time()*1000)}-{i}"
    print(f"{s}\t{tokens[s]}\t{phones[r]}\t{amt}\t{key}")
PY
< /tmp/transfer-jobs xargs -P "$PARALLEL" -L 1 bash -c 'transfer_one "$@"' _ || true
rm -f /tmp/transfer-jobs "$TOKENS_FILE"

ELAPSED=$(( $(date +%s) - START ))
log "Seed complete in ${ELAPSED}s. users.json: $SEED_OUT"
log "User rows in DB: $(docker exec $PG_CONTAINER psql -U $PG_USER -d $PG_DB -tAc 'SELECT COUNT(*) FROM users')"
log "Transactions in DB: $(docker exec $PG_CONTAINER psql -U $PG_USER -d $PG_DB -tAc 'SELECT COUNT(*) FROM transactions')"
