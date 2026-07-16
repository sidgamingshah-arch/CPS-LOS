#!/usr/bin/env python3
"""
Escrow monitoring (portfolio-service /api/escrow) — e2e.

portfolio-service gained an escrow monitoring surface: escrow accounts host append-only
VERSIONED budget lines (a new version supersedes the active baseline for a category and
preserves history) and category-tagged transactions. The signature read is a DETERMINISTIC
budget-vs-actual per category — the sum of tagged transactions vs the active budget line —
with a RAG band from the VALIDATION_PARAMETER thresholds (config-as-data, conservative
fallback). It is a record surface: it NEVER moves an authoritative ECL / exposure / limit.

Proves:
  0. Seed VALIDATION_PARAMETER/ESCROW_UTILISATION RAG thresholds via the generic master API
     (submit + approve, maker != checker) — amber 80% / red 100%.
  1. Create an escrow account (ESC-XXXXXX) + budget lines.
  2. Deterministic budget-vs-actual: DEBITs count as actual spend; CREDITs tracked separately;
     RAG transitions GREEN -> AMBER -> RED as utilisation crosses the seeded thresholds; the
     thresholds are read from the MASTER (source == "MASTER").
  3. Re-versioning a budget line changes the ACTIVE baseline (v2, higher budget) without
     deleting history — both versions remain, v1 superseded, v2 active — and the RAG recovers.
  4. Worst-of overall RAG across multiple categories.
  5. Audit: ESCROW_ACCOUNT_CREATED / ESCROW_BUDGET_VERSIONED / ESCROW_TXN_POSTED stamped HUMAN;
     ESCROW_BUDGET_ASSESSED stamped SYSTEM (deterministic).
  6. INVARIANT: the portfolio summary (an authoritative aggregate) is BYTE-IDENTICAL before
     and after the whole escrow lifecycle — escrow moves no authoritative figure.
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
NONCE = str(int(time.time()))

OWNER = "portfolio.manager"
M_MAKER = "escrow.master.maker"
M_CHECKER = "escrow.master.checker"


def call(method, path, body=None, actor="ops.admin"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt) if txt else None
        except Exception:
            return e.code, txt


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def must(st, b, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {b}")
        sys.exit(1)
    return b


def seed_master(mtype, key, payload):
    """Submit + approve (maker != checker) so the record lands ACTIVE for the runtime reads."""
    st, rec = call("POST", f"/config/api/masters/{mtype}", {"recordKey": key, "payload": payload}, actor=M_MAKER)
    rec = must(st, rec, f"seed {mtype}/{key}")
    st, ap = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor=M_CHECKER)
    must(st, ap, f"approve {mtype}/{key}")


def bva(ref):
    st, s = call("GET", f"/portfolio/api/escrow/accounts/{ref}/budget-vs-actual")
    return must(st, s, "budget-vs-actual")


def cat(summary, category):
    return next((c for c in summary.get("categories", []) if c["category"] == category), None)


# ============================================================ 0. seed thresholds (config-as-data)
print("== 0. Seed VALIDATION_PARAMETER/ESCROW_UTILISATION thresholds (amber 80% / red 100%) ==")
seed_master("VALIDATION_PARAMETER", "ESCROW_UTILISATION",
            {"amberUtilisationPct": 80, "redUtilisationPct": 100})
check("thresholds seeded (submit+approve, maker != checker)", True)

# invariant baseline: an authoritative portfolio aggregate before any escrow activity.
st, sum_before = call("GET", "/portfolio/api/portfolio/summary")
sum_before = must(st, sum_before, "portfolio summary before")
SUMMARY_SNAP = json.dumps(sum_before, sort_keys=True)

# ============================================================ 1. create account + budget lines
print("\n== 1. Create escrow account + budget lines ==")
st, acc = call("POST", "/portfolio/api/escrow/accounts",
               {"subjectType": "FACILITY", "subjectRef": f"FAC-ESC-{NONCE}",
                "purpose": "RERA project escrow", "currency": "INR", "openingBalance": 0}, actor=OWNER)
acc = must(st, acc, "create account")
REF = acc["escrowRef"]
check("escrowRef generated ESC-XXXXXX", REF.startswith("ESC-") and len(REF) == 10, REF)
check("account starts ACTIVE", acc["status"] == "ACTIVE", acc["status"])
check("currency normalised", acc["currency"] == "INR", acc["currency"])

st, bl = call("POST", f"/portfolio/api/escrow/accounts/{REF}/budget-lines",
              {"category": "MATERIALS", "budgetedAmount": 1_000_000, "effectiveFrom": "2026-01-01"}, actor=OWNER)
bl = must(st, bl, "budget MATERIALS")
check("MATERIALS budget line v1", bl["versionNo"] == 1 and bl["active"] is True and bl["budgetedAmount"] == 1_000_000,
      str(bl))
st, bl2 = call("POST", f"/portfolio/api/escrow/accounts/{REF}/budget-lines",
               {"category": "LABOUR", "budgetedAmount": 500_000}, actor=OWNER)
must(st, bl2, "budget LABOUR")

# ============================================================ 2. deterministic budget-vs-actual + RAG
print("\n== 2. Deterministic budget-vs-actual + GREEN -> AMBER -> RED ==")
s = bva(REF)
check("thresholds read from the MASTER (config-as-data)",
      s["thresholdSource"] == "MASTER" and s["amberPct"] == 80 and s["redPct"] == 100, str(s)[:160])
m = cat(s, "MATERIALS")
check("no spend yet -> 0% GREEN", m and m["actual"] == 0 and m["rag"] == "GREEN", str(m))

# DEBIT 500,000 -> 50% GREEN. A CREDIT is tracked separately and does NOT reduce actual spend.
must(*call("POST", f"/portfolio/api/escrow/accounts/{REF}/transactions",
           {"amount": 500_000, "direction": "DEBIT", "category": "MATERIALS", "memo": "supplier 1"}, actor=OWNER)[:2],
     "debit 500k")
must(*call("POST", f"/portfolio/api/escrow/accounts/{REF}/transactions",
           {"amount": 200_000, "direction": "CREDIT", "category": "MATERIALS", "memo": "deposit"}, actor=OWNER)[:2],
     "credit 200k")
m = cat(bva(REF), "MATERIALS")
check("DEBIT 500k of 1,000,000 -> actual 500k, 50%, GREEN (deterministic)",
      m["actual"] == 500_000 and abs(m["utilisationPct"] - 50.0) < 1e-9 and m["rag"] == "GREEN", str(m))
check("CREDIT tracked separately, does not move actual spend",
      m["credited"] == 200_000 and m["debited"] == 500_000, str(m))

# +DEBIT 350,000 -> 850,000 = 85% AMBER.
must(*call("POST", f"/portfolio/api/escrow/accounts/{REF}/transactions",
           {"amount": 350_000, "direction": "DEBIT", "category": "MATERIALS"}, actor=OWNER)[:2], "debit 350k")
m = cat(bva(REF), "MATERIALS")
check("85% utilisation -> AMBER", abs(m["utilisationPct"] - 85.0) < 1e-9 and m["rag"] == "AMBER", str(m))

# +DEBIT 200,000 -> 1,050,000 = 105% RED, variance negative (overspend).
must(*call("POST", f"/portfolio/api/escrow/accounts/{REF}/transactions",
           {"amount": 200_000, "direction": "DEBIT", "category": "MATERIALS"}, actor=OWNER)[:2], "debit 200k")
s = bva(REF)
m = cat(s, "MATERIALS")
check("105% utilisation -> RED with negative variance (overspend)",
      abs(m["utilisationPct"] - 105.0) < 1e-9 and m["rag"] == "RED" and m["variance"] == -50_000, str(m))

# LABOUR: small spend stays GREEN; overall RAG is worst-of (RED while MATERIALS is RED).
must(*call("POST", f"/portfolio/api/escrow/accounts/{REF}/transactions",
           {"amount": 100_000, "direction": "DEBIT", "category": "LABOUR"}, actor=OWNER)[:2], "debit labour 100k")
s = bva(REF)
check("LABOUR 20% GREEN", cat(s, "LABOUR")["rag"] == "GREEN", str(cat(s, "LABOUR")))
check("overall RAG is worst-of categories -> RED", s["overallRag"] == "RED", s["overallRag"])
check("totals aggregate deterministically",
      s["totalBudgeted"] == 1_500_000 and s["totalActual"] == 1_150_000, str(s)[:160])

# ============================================================ 3. re-version the budget baseline (history preserved)
print("\n== 3. Re-version MATERIALS budget (v2, higher) -> new active baseline, history preserved ==")
st, v2 = call("POST", f"/portfolio/api/escrow/accounts/{REF}/budget-lines",
              {"category": "MATERIALS", "budgetedAmount": 1_500_000, "note": "revised scope"}, actor=OWNER)
v2 = must(st, v2, "budget MATERIALS v2")
check("new version is v2 + active", v2["versionNo"] == 2 and v2["active"] is True and v2["budgetedAmount"] == 1_500_000,
      str(v2))
s = bva(REF)
m = cat(s, "MATERIALS")
check("actuals re-baselined against v2: 1,050,000 / 1,500,000 = 70% GREEN",
      m["budgetVersion"] == 2 and m["budgetedAmount"] == 1_500_000
      and abs(m["utilisationPct"] - 70.0) < 1e-9 and m["rag"] == "GREEN", str(m))
check("overall RAG recovers to GREEN (both categories GREEN)", s["overallRag"] == "GREEN", s["overallRag"])

# History: both versions survive; v1 superseded (inactive), v2 active. No deletion.
st, hist = call("GET", f"/portfolio/api/escrow/accounts/{REF}/budget-lines")
hist = must(st, hist, "budget history")
mat_versions = sorted([h for h in hist if h["category"] == "MATERIALS"], key=lambda h: h["versionNo"])
check("MATERIALS history retains BOTH versions (append-only, nothing deleted)",
      len(mat_versions) == 2 and mat_versions[0]["versionNo"] == 1 and mat_versions[1]["versionNo"] == 2, str(mat_versions))
check("v1 superseded (inactive), v2 active — single active pointer per category",
      mat_versions[0]["active"] is False and mat_versions[1]["active"] is True, str(mat_versions))

# The account view exposes only the active budget lines (one per category).
st, av = call("GET", f"/portfolio/api/escrow/accounts/{REF}")
av = must(st, av, "account view")
active_cats = {l["category"]: l for l in av["activeBudgetLines"]}
check("account view lists one active line per category (MATERIALS v2 + LABOUR v1)",
      active_cats["MATERIALS"]["versionNo"] == 2 and active_cats["LABOUR"]["versionNo"] == 1, str(active_cats))
check("account view exposes all transactions", len(av["transactions"]) == 5, len(av["transactions"]))

# ============================================================ 4. audit trail
print("\n== 4. Audit trail ==")
st, trail = call("GET", f"/portfolio/api/audit/subject?type=EscrowAccount&id={REF}")
trail = must(st, trail, "audit subject")
by_type = {}
for e in trail:
    by_type.setdefault(e["eventType"], []).append(e)
check("ESCROW_ACCOUNT_CREATED stamped HUMAN",
      any(e["actorType"] == "HUMAN" for e in by_type.get("ESCROW_ACCOUNT_CREATED", [])), str(list(by_type)))
check("ESCROW_BUDGET_VERSIONED stamped HUMAN (>=3: 2 categories + 1 re-version)",
      len(by_type.get("ESCROW_BUDGET_VERSIONED", [])) >= 3
      and all(e["actorType"] == "HUMAN" for e in by_type["ESCROW_BUDGET_VERSIONED"]),
      len(by_type.get("ESCROW_BUDGET_VERSIONED", [])))
check("ESCROW_TXN_POSTED stamped HUMAN (5 transactions)",
      len(by_type.get("ESCROW_TXN_POSTED", [])) == 5
      and all(e["actorType"] == "HUMAN" for e in by_type["ESCROW_TXN_POSTED"]),
      len(by_type.get("ESCROW_TXN_POSTED", [])))
check("ESCROW_BUDGET_ASSESSED stamped SYSTEM (deterministic read)",
      any(e["actorType"] == "SYSTEM" for e in by_type.get("ESCROW_BUDGET_ASSESSED", [])),
      str(by_type.get("ESCROW_BUDGET_ASSESSED")))

# ============================================================ 5. record-surface invariant
print("\n== 5. INVARIANT: no authoritative figure moved ==")
st, sum_after = call("GET", "/portfolio/api/portfolio/summary")
sum_after = must(st, sum_after, "portfolio summary after")
check("portfolio summary byte-identical before/after the escrow lifecycle",
      json.dumps(sum_after, sort_keys=True) == SUMMARY_SNAP, "portfolio aggregate moved")

# Guardrails: bad inputs rejected deterministically.
st, _ = call("POST", f"/portfolio/api/escrow/accounts/{REF}/transactions",
             {"amount": -5, "direction": "DEBIT"}, actor=OWNER)
check("non-positive amount rejected (400)", st == 400, st)
st, _ = call("POST", f"/portfolio/api/escrow/accounts/{REF}/transactions",
             {"amount": 10, "direction": "SIDEWAYS"}, actor=OWNER)
check("unknown direction rejected (400)", st == 400, st)
st, _ = call("GET", "/portfolio/api/escrow/accounts/ESC-NOPE99")
check("unknown account -> 404", st == 404, st)

print(f"\nescrow monitoring e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
