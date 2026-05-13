#!/usr/bin/env bash
# Quality snapshot — regenerates the metrics tables in QUALITY_DASHBOARD.md.
#
# Pulls live data from whatever local checkouts and tool outputs are present.
# Idempotent: writes between fenced markers so re-runs replace, not append.
#
# Assumes a sibling checkout of wallet-app at /mnt/storage/Software-Projects/wallet-app
# (or override with WALLET_APP_DIR env var). The wallet-app metrics come from
# its own coverage / mutation report files; if those don't exist locally, that
# section is left as "no data" rather than crashing.
#
# Future enhancement: run this from a scheduled CI workflow that uploads the
# JaCoCo / Vitest / Stryker artifacts to a known location, fetches them here,
# and opens an auto-PR to update the dashboard.
#
# Usage:
#   bash scripts/quality-snapshot.sh

set -euo pipefail

WALLET_API_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WALLET_APP_DIR="${WALLET_APP_DIR:-/mnt/storage/Software-Projects/wallet-app}"
DASH="$WALLET_API_DIR/QUALITY_DASHBOARD.md"
STAMP="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
COMMIT="$(git -C "$WALLET_API_DIR" rev-parse --short HEAD)"
BRANCH="$(git -C "$WALLET_API_DIR" rev-parse --abbrev-ref HEAD)"

# Drop-in helper: replaces text between <!-- key:start --> and <!-- key:end -->
# in DASH. Body comes from stdin.
replace_section() {
    local key="$1"
    local tmp
    tmp="$(mktemp)"
    awk -v key="$key" -v body_path="/dev/stdin" '
        BEGIN { in_section = 0 }
        $0 ~ "<!-- " key ":start -->" {
            print
            while ((getline line < body_path) > 0) print line
            in_section = 1
            next
        }
        $0 ~ "<!-- " key ":end -->" {
            in_section = 0
            print
            next
        }
        !in_section { print }
    ' "$DASH" < <(cat) > "$tmp"
    mv "$tmp" "$DASH"
}

log() { printf '[snapshot] %s\n' "$*" >&2; }

# ─── 1. Header (stamp + commit) ────────────────────────────────────────

log "Updating header (commit $COMMIT on $BRANCH)..."
replace_section "header" <<EOF
- Last updated: **$STAMP**
- Generated from: \`$BRANCH\` @ \`$COMMIT\`
EOF

# ─── 2. wallet-api JaCoCo coverage ─────────────────────────────────────

JACOCO_XML="$WALLET_API_DIR/target/site/jacoco/jacoco.xml"
if [ -f "$JACOCO_XML" ]; then
    log "Reading JaCoCo from $JACOCO_XML ..."
    JACOCO_BODY=$(python3 - <<PY
import xml.etree.ElementTree as ET
root = ET.parse("$JACOCO_XML").getroot()
totals = {}
for c in root.findall("counter"):
    t = c.attrib["type"]
    m = int(c.attrib["missed"])
    cov = int(c.attrib["covered"])
    total = m + cov
    pct = round(100 * cov / total, 1) if total else 0.0
    totals[t] = (cov, total, pct)
print("| Counter | Covered | Total | % |")
print("|---|---|---|---|")
for t in ("LINE","BRANCH","INSTRUCTION","METHOD","CLASS"):
    if t in totals:
        c,n,p = totals[t]
        print(f"| {t.title()} | {c} | {n} | **{p} %** |")
PY
)
else
    log "JaCoCo XML missing — run 'mvn verify -B' to produce it."
    JACOCO_BODY="_No JaCoCo XML found at \`target/site/jacoco/jacoco.xml\`. Run \`mvn verify -B\` on this branch then re-run this script._"
fi
replace_section "jacoco" <<<"$JACOCO_BODY"

# ─── 3. wallet-api PIT mutation ────────────────────────────────────────

PIT_XML="$WALLET_API_DIR/target/pit-reports/mutations.xml"
if [ -f "$PIT_XML" ]; then
    log "Reading PIT mutations from $PIT_XML ..."
    PIT_BODY=$(python3 - <<PY
import xml.etree.ElementTree as ET
muts = ET.parse("$PIT_XML").getroot().findall("mutation")
killed   = sum(1 for m in muts if m.attrib["status"] == "KILLED")
survived = sum(1 for m in muts if m.attrib["status"] == "SURVIVED")
no_cov   = sum(1 for m in muts if m.attrib["status"] == "NO_COVERAGE")
total = len(muts)
score = round(100 * killed / total, 1) if total else 0.0
covered = killed + survived
tstr = round(100 * killed / covered, 1) if covered else 0.0
print(f"| Killed | Survived | No coverage | Total | Score | Test strength |")
print(f"|---|---|---|---|---|---|")
print(f"| {killed} | {survived} | {no_cov} | {total} | **{score} %** | {tstr} % |")
PY
)
else
    log "PIT report missing — run 'mvn test-compile org.pitest:pitest-maven:mutationCoverage -B'."
    PIT_BODY="_No PIT mutation report at \`target/pit-reports/mutations.xml\`. Run mutationCoverage and re-run this script._"
fi
replace_section "pit" <<<"$PIT_BODY"

# ─── 4. wallet-app Vitest coverage ─────────────────────────────────────

VITEST_COV="$WALLET_APP_DIR/coverage/coverage-summary.json"
if [ -f "$VITEST_COV" ]; then
    log "Reading Vitest coverage from $VITEST_COV ..."
    VITEST_BODY=$(python3 - <<PY
import json
d = json.load(open("$VITEST_COV"))["total"]
print("| Counter | % |")
print("|---|---|")
for k in ("statements", "branches", "functions", "lines"):
    pct = d.get(k, {}).get("pct", 0)
    print(f"| {k.title()} | **{pct} %** |")
PY
)
else
    log "Vitest coverage-summary.json missing — run 'npm run test:cov' in wallet-app."
    VITEST_BODY="_No Vitest coverage summary at \`$WALLET_APP_DIR/coverage/coverage-summary.json\`. Run \`npm run test:cov\` in wallet-app and re-run this script._"
fi
replace_section "vitest" <<<"$VITEST_BODY"

# ─── 5. wallet-app Stryker mutation ────────────────────────────────────

STRYKER_JSON="$WALLET_APP_DIR/reports/mutation/mutation.json"
if [ -f "$STRYKER_JSON" ]; then
    log "Reading Stryker from $STRYKER_JSON ..."
    STRYKER_BODY=$(python3 - <<PY
import json
d = json.load(open("$STRYKER_JSON"))
killed = survived = no_cov = total = 0
for f in d.get("files", {}).values():
    for m in f.get("mutants", []):
        total += 1
        s = m.get("status", "")
        if s == "Killed": killed += 1
        elif s == "Survived": survived += 1
        elif s == "NoCoverage": no_cov += 1
score = round(100 * killed / total, 1) if total else 0.0
print(f"| Killed | Survived | No coverage | Total | Score |")
print(f"|---|---|---|---|---|")
print(f"| {killed} | {survived} | {no_cov} | {total} | **{score} %** |")
PY
)
else
    log "Stryker report missing — run 'npm run test:mutation' in wallet-app."
    STRYKER_BODY="_No Stryker report at \`$WALLET_APP_DIR/reports/mutation/mutation.json\`. Run \`npm run test:mutation\` in wallet-app and re-run this script._"
fi
replace_section "stryker" <<<"$STRYKER_BODY"

# ─── 6. wallet-api performance (latest k6 run) ─────────────────────────

PERF_LOAD_JSON="$WALLET_API_DIR/performance/results/load.json"
if [ -f "$PERF_LOAD_JSON" ]; then
    log "Reading k6 load result from $PERF_LOAD_JSON ..."
    PERF_BODY=$(python3 - <<PY
import json, os
print("| Test | Total reqs | Throughput | Read p95 | Write p95 | Error rate |")
print("|---|---|---|---|---|---|")
for kind in ("load", "stress", "spike", "workflow"):
    path = f"$WALLET_API_DIR/performance/results/{kind}.json"
    if not os.path.exists(path): continue
    m = json.load(open(path)).get("metrics", {})
    reqs = m.get("http_reqs", {}).get("count", 0)
    rate = m.get("http_reqs", {}).get("rate", 0)
    rp95 = m.get("http_req_duration{kind:read}", {}).get("p(95)", 0)
    wp95 = m.get("http_req_duration{kind:write}", {}).get("p(95)", 0)
    err = m.get("http_req_failed", {}).get("rate", 0)
    print(f"| {kind} | {reqs} | {rate:.1f} req/s | {rp95:.0f} ms | {wp95:.0f} ms | {err*100:.2f} % |")
PY
)
else
    log "No k6 perf results — run the perf suite to populate."
    PERF_BODY="_No k6 results at \`performance/results/load.json\`. Run the perf suite per PERFORMANCE_TESTING.md to populate._"
fi
replace_section "performance" <<<"$PERF_BODY"

# ─── 7. Open issue count (via gh CLI; tolerant of offline) ─────────────

if command -v gh >/dev/null 2>&1; then
    log "Counting open GitHub issues..."
    API_OPEN=$(gh issue list -R jeffgicharu/wallet-api --state open --limit 200 --json number 2>/dev/null | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' || echo "?")
    APP_OPEN=$(gh issue list -R jeffgicharu/wallet-app --state open --limit 200 --json number 2>/dev/null | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' || echo "?")
    ISSUES_BODY="- wallet-api open issues: **$API_OPEN**\n- wallet-app open issues: **$APP_OPEN**"
else
    ISSUES_BODY="_gh CLI not installed; can't count open issues automatically._"
fi
echo -e "$ISSUES_BODY" | replace_section "issues"

log "Done. Updated $DASH."
