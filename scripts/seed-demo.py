#!/usr/bin/env python3
"""Re-seed the demo wallet data with a believable financial story.

Wipes the user/wallet/transaction/ledger tables, then drives the public
API (so every balance and ledger entry is produced by real code paths,
never a raw INSERT) to build, for each of the three demo accounts, a
dense and varied 90-day history: salary and cash deposits, agent
cash-outs, peer transfers to and from the other demo accounts and a
handful of named non-demo contacts, the 1% transfer fee, and one
reversed transfer. Finally it back-dates every row so the activity is
spread across the last ~90 days at varied times of day, with
genuine-looking reference numbers.

Idempotent: truncates first, safe to re-run. Designed to run as root
(peer-authenticated psql via `sudo -u postgres`) from the daily 03:00
UTC cron, and to be run by hand for a one-shot reseed.
"""

import json
import os
import random
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

API = os.environ.get("SEED_API", "http://127.0.0.1:8081")
DB = os.environ.get("SEED_DB", "wallet")
# Local API-flow validation against an ephemeral H2 instance: skip the
# Postgres truncate/back-date so the curl story can be exercised without
# a database. Never set in the deployed cron.
SKIP_DB = os.environ.get("SEED_SKIP_DB") == "1"
# Deterministic story so a reseed looks the same day to day.
random.seed(20260519)


def log(msg):
    print(f"[{datetime.now(timezone.utc):%Y-%m-%dT%H:%M:%SZ}] seed-demo: {msg}",
          flush=True)


def psql(sql):
    if SKIP_DB:
        return
    subprocess.run(
        ["sudo", "-u", "postgres", "psql", "-d", DB, "-v", "ON_ERROR_STOP=1",
         "-q", "-c", sql],
        check=True, stdout=subprocess.DEVNULL)


def api(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            payload = e.read().decode()
            raise RuntimeError(f"{method} {path} -> {e.code}: {payload}")
        except urllib.error.URLError:
            if attempt == 2:
                raise
            time.sleep(2)


def key():
    """Genuine-looking idempotency key -> reference like TRF-9F4A2B1C."""
    return secrets.token_hex(4).upper()


def wait_for_api():
    for _ in range(30):
        try:
            with urllib.request.urlopen(API + "/actuator/health", timeout=5) as r:
                if r.status == 200:
                    return
        except Exception:
            time.sleep(2)
    raise SystemExit("seed-demo: API never became reachable")


# ── account model ────────────────────────────────────────────────────

class Acct:
    def __init__(self, name, email, phone, pin="1234", pwd="pass1234"):
        self.name, self.email, self.phone = name, email, phone
        self.pin, self.pwd = pin, pwd
        self.token = None

    def register(self):
        # Registration returns a JWT directly, so the seed never needs a
        # second /login round-trip. That matters: the login endpoint is
        # IP rate-limited (5/min) and this script creates ~11 accounts in
        # a burst — logging each in would trip the limiter. The tables
        # are always truncated first so registration always succeeds;
        # the login fallback only covers a no-truncate manual run.
        try:
            res = api("POST", "/api/auth/register", body={
                "fullName": self.name, "email": self.email,
                "phoneNumber": self.phone, "password": self.pwd,
                "pin": self.pin})
            self.token = res["data"]["token"]
        except RuntimeError as e:
            if "already" not in str(e).lower():
                raise
            self.token = api("POST", "/api/auth/login", body={
                "email": self.email, "password": self.pwd})["data"]["token"]


# Three advertised demo accounts.
ALICE = Acct("Alice Demo", "alice@demo.local", "+254700000001")
BOB = Acct("Bob Demo", "bob@demo.local", "+254700000002")
CAROL = Acct("Carol Demo", "carol@demo.local", "+254700000003")
DEMO = [ALICE, BOB, CAROL]

# Named non-demo contacts — registered so transfers resolve, but not
# advertised anywhere. They give the demo accounts realistic
# counterparties beyond just each other.
CONTACTS = [
    Acct("James Mwangi", "james.mwangi@contacts.local", "+254711100201"),
    Acct("Mary Njeri", "mary.njeri@contacts.local", "+254711100202"),
    Acct("John Otieno", "john.otieno@contacts.local", "+254711100203"),
    Acct("Grace Achieng", "grace.achieng@contacts.local", "+254711100204"),
    Acct("Peter Kamau", "peter.kamau@contacts.local", "+254711100205"),
    Acct("Faith Chebet", "faith.chebet@contacts.local", "+254711100206"),
    Acct("Daniel Kiprop", "daniel.kiprop@contacts.local", "+254711100207"),
    Acct("Sarah Wambui", "sarah.wambui@contacts.local", "+254711100208"),
]


def deposit(acct, amount, desc):
    api("POST", "/api/wallet/deposit", acct.token,
        {"amount": amount, "idempotencyKey": key(), "description": desc})


def withdraw(acct, amount, desc):
    api("POST", "/api/wallet/withdraw", acct.token,
        {"amount": amount, "pin": acct.pin, "idempotencyKey": key(),
         "description": desc})


def transfer(src, dst, amount, desc):
    res = api("POST", "/api/wallet/transfer", src.token,
              {"recipientPhone": dst.phone, "amount": amount,
               "pin": src.pin, "idempotencyKey": key(), "description": desc})
    return res["data"]["reference"]


def main():
    log("wiping demo tables")
    psql("TRUNCATE TABLE audit_logs, ledger_entries, transactions, "
         "wallets, users RESTART IDENTITY CASCADE;")

    wait_for_api()

    log("registering accounts")
    for a in DEMO + CONTACTS:
        a.register()

    # Seed the contacts with float so they can send to the demo accounts.
    for c in CONTACTS:
        deposit(c, round(random.uniform(45000, 95000), 2), "Cash deposit")

    # Opening balances — deliberately irregular, not round numbers.
    deposit(ALICE, 58420.00, "Salary - April")
    deposit(BOB, 31250.00, "Salary - April")
    deposit(CAROL, 13680.00, "Salary - April")

    INCOME = ["Salary", "M-Pesa top-up", "Cash deposit", "Refund",
              "Business payment", "Cash deposit"]
    CASHOUT = ["Cash withdrawal", "ATM withdrawal", "Agent cash-out",
               "Cash withdrawal"]
    SEND_NOTES = ["Lunch", "Rent share", "Fare", "Groceries", "Airtime",
                  "Thanks!", "Loan repayment", "Shopping", "Help out",
                  "Tickets", "Dinner", "Books"]

    # Build a long interleaved list of (kind, *args) operations per
    # account so that, ordered by row id, the three stories advance
    # together rather than in three separate blocks.
    streams = {a: [] for a in DEMO}

    # Balance-aware planner: track a running balance so an outflow is
    # never larger than what the account holds. That keeps the story
    # dense (no skipped ops) and lands every account on an irregular,
    # believable closing balance rather than a round number.
    def plan(a, opening, peers, n):
        ops, bal = [], opening
        for _ in range(n):
            r = random.random()
            if bal < 2500 or r < 0.30:                       # top up
                amt = round(random.uniform(1200, 9000), 2)
                bal += amt
                ops.append(("dep", a, amt, random.choice(INCOME)))
            elif r < 0.62:                                   # cash out
                amt = round(min(bal * 0.45, random.uniform(500, 9000)), 2)
                bal -= amt
                ops.append(("wd", a, amt, random.choice(CASHOUT)))
            elif r < 0.84:                                   # send out
                amt = round(min(bal * 0.35, random.uniform(300, 6500)), 2)
                bal -= amt * 1.01  # amount + 1% fee
                ops.append(("trf", a, random.choice(peers), amt,
                            random.choice(SEND_NOTES)))
            else:                                            # receive in
                amt = round(random.uniform(400, 6000), 2)
                bal += amt
                ops.append(("trf", random.choice(peers), a, amt,
                            random.choice(SEND_NOTES)))
        return ops

    streams[ALICE] = plan(ALICE, 58420.00, [BOB, CAROL] + CONTACTS[:5],
                          random.randint(26, 32))
    streams[BOB] = plan(BOB, 31250.00, [ALICE, CAROL] + CONTACTS[3:7],
                        random.randint(24, 30))
    streams[CAROL] = plan(CAROL, 13680.00, [ALICE, BOB] + CONTACTS[2:6],
                          random.randint(22, 28))

    # Round-robin interleave.
    interleaved = []
    cursors = {a: 0 for a in DEMO}
    while any(cursors[a] < len(streams[a]) for a in DEMO):
        for a in DEMO:
            if cursors[a] < len(streams[a]):
                interleaved.append(streams[a][cursors[a]])
                cursors[a] += 1

    log(f"running {len(interleaved)} scripted operations")
    reversible_ref = None
    for op in interleaved:
        try:
            if op[0] == "dep":
                deposit(op[1], op[2], op[3])
            elif op[0] == "wd":
                withdraw(op[1], op[2], op[3])
            elif op[0] == "trf":
                ref = transfer(op[1], op[2], op[3], op[4])
                if reversible_ref is None and op[1] is BOB:
                    reversible_ref = ref
        except RuntimeError as e:
            # Insufficient balance / daily cap on an unlucky random draw
            # — skip that single op, the story stays coherent.
            log(f"skipped op {op[0]} ({e})")

    # One reversed transfer so REVERSED status + a REV- row appear.
    if reversible_ref:
        try:
            api("POST",
                f"/api/wallet/transactions/{reversible_ref}/reverse"
                "?reason=Sent%20to%20wrong%20number", BOB.token)
            log(f"reversed {reversible_ref}")
        except RuntimeError as e:
            log(f"reversal skipped ({e})")

    # ── back-date everything across ~90 days ─────────────────────────
    if SKIP_DB:
        log("SKIP_DB set — skipping back-date (local API-flow check only)")
        log("done")
        return
    log("back-dating history across ~90 days")
    rows = subprocess.run(
        ["sudo", "-u", "postgres", "psql", "-d", DB, "-tAc",
         "SELECT id FROM transactions ORDER BY id;"],
        check=True, capture_output=True, text=True).stdout.split()
    ids = [int(x) for x in rows if x.strip()]

    now = datetime.now(timezone.utc)
    start = now - timedelta(days=90)
    span = (now - timedelta(hours=8) - start).total_seconds()
    # Random, ascending timestamps so processing order == chronological
    # order (keeps statement balances monotonic) but spacing and
    # time-of-day look natural rather than evenly clocked.
    offsets = sorted(random.uniform(0, span) for _ in ids)
    values = []
    for tid, off in zip(ids, offsets):
        ts = start + timedelta(seconds=off)
        values.append(f"({tid},'{ts:%Y-%m-%d %H:%M:%S}+00')")
    chunk = 200
    for i in range(0, len(values), chunk):
        vs = ",".join(values[i:i + chunk])
        psql(f"UPDATE transactions t SET created_at = v.ts "
             f"FROM (VALUES {vs}) AS v(id, ts) WHERE t.id = v.id;")
    psql("UPDATE ledger_entries le SET created_at = t.created_at "
         "FROM transactions t WHERE le.transaction_id = t.id;")
    psql("UPDATE wallets SET created_at = "
         "COALESCE((SELECT min(created_at) FROM transactions), now()) "
         "- interval '4 days', updated_at = now();")
    psql("UPDATE users SET created_at = "
         "COALESCE((SELECT min(created_at) FROM transactions), now()) "
         "- interval '4 days';")

    log("done")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001 — cron wants a clear failure line
        log(f"ERROR {e}")
        sys.exit(1)
