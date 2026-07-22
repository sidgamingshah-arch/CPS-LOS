#!/usr/bin/env python3
"""
Banking ASR (Account Statement Review) — e2e through the gateway (CLoM R1-10).

A banking ASR captures a borrower's banking-arrangement monthly statement lines during
origination and computes account-conduct metrics DETERMINISTICALLY (no LLM in the figure
path). An OPTIONAL advisory narrative summary may be drafted at the AI boundary; it is
advisory-only and never mutates a computed metric. A named human confirms the review.

Asserts:
  1. Create an ASR with KNOWN monthly lines -> every metric is hand-computable
     (averageBankBalance, avg/peak utilisation, credit/debit summations, cheque-return
     split, min/max balance, transaction count). Status DRAFT, advisory=true, ASR-* ref.
  2. get by ref + list by applicationRef return the record.
  3. The advisory summary is advisory and does NOT change any metric (byte-identical
     metric snapshot before vs after summarising); the summary text is populated.
  4. The parent application's authoritative rating (modelGrade / finalGrade / pd) is
     byte-identical before vs after the advisory run.
  5. confirm flips DRAFT -> CONFIRMED (records the actor); re-confirm -> 409.
  6. audit: BANKING_ASR_COMPUTED is SYSTEM, BANKING_ASR_SUMMARISED is AI,
     BANKING_ASR_CONFIRMED is HUMAN.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="analyst.user"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
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


def approx(a, b, tol=1e-6):
    if a is None or b is None:
        return False
    return abs(a - b) <= tol * max(1.0, abs(b))


def line(v):
    return {"value": v, "sourceDocument": "asr.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount):
    """cp -> app -> spread -> confirm -> rate -> rating/confirm; returns the app ref."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"ASR {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"ASR{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "WORKING_CAPITAL",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 36, "purpose": "Working capital",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
        per("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    return ref


def grade_of(r):
    if not isinstance(r, dict):
        return None
    return (r.get("modelGrade"), r.get("finalGrade"), r.get("pd"))


# ---- known monthly lines (hand-computable metrics) ----
SANCTIONED = 100_000_000
LINES = [
    {"monthLabel": "M1", "openingBalance": 10_000_000, "closingBalance": 20_000_000,
     "totalCredit": 30_000_000, "totalDebit": 25_000_000, "peakBalance": 22_000_000,
     "minBalanceInMonth": 8_000_000, "drawn": 50_000_000,
     "chequeReturnsInward": 1, "chequeReturnsOutward": 0, "transactionCount": 40},
    {"monthLabel": "M2", "openingBalance": 20_000_000, "closingBalance": 30_000_000,
     "totalCredit": 40_000_000, "totalDebit": 35_000_000, "peakBalance": 33_000_000,
     "minBalanceInMonth": 15_000_000, "drawn": 60_000_000,
     "chequeReturnsInward": 0, "chequeReturnsOutward": 1, "transactionCount": 50},
    {"monthLabel": "M3", "openingBalance": 30_000_000, "closingBalance": 40_000_000,
     "totalCredit": 50_000_000, "totalDebit": 45_000_000, "peakBalance": 44_000_000,
     "minBalanceInMonth": 25_000_000, "drawn": 70_000_000,
     "chequeReturnsInward": 2, "chequeReturnsOutward": 0, "transactionCount": 60},
]
# Expected (hand-computed):
#   monthlyAvgBal = (10+20)/2, (20+30)/2, (30+40)/2 -> 15M, 25M, 35M ; mean = 25M
#   utilisation   = 50/100, 60/100, 70/100 -> 0.5, 0.6, 0.7 ; mean = 0.6 ; peak = 0.7
EXP = {
    "averageBankBalance": 25_000_000, "avgUtilisationPct": 0.6, "peakUtilisationPct": 0.7,
    "totalCredits": 120_000_000, "totalDebits": 105_000_000, "creditSummationMonthlyAvg": 40_000_000,
    "chequeReturnsInward": 3, "chequeReturnsOutward": 1, "minBalance": 8_000_000,
    "maxBalance": 44_000_000, "transactionCount": 150, "sanctionedLimit": 100_000_000,
}
METRIC_KEYS = list(EXP.keys())


print("== 0. Setup: a rated application to attach the ASR to ==")
aref = rated_deal("ONE", 400_000_000)
st, rating_before = call("GET", f"/risk/api/risk/{aref}/rating")
rating_before = must(st, rating_before, "rating before")
g_before = grade_of(rating_before)
print(f"    application {aref} rated {g_before}")


print("\n== 1. create ASR with known lines -> deterministic, hand-computable metrics ==")
st, a = call("POST", "/origination/api/banking-asr", {
    "applicationRef": aref, "bankName": "State Bank", "accountNoMasked": "XXXXXX1234",
    "sanctionedLimit": SANCTIONED, "currency": "INR", "periodFrom": "2025-01", "periodTo": "2025-03",
    "lines": LINES}, actor="analyst.user")
a = must(st, a, "create ASR")
asr_ref = a["asrRef"]
check("created DRAFT with ASR- ref, advisory=true", a["status"] == "DRAFT"
      and asr_ref.startswith("ASR-") and a.get("advisory") is True, str(a))
check("captured 3 monthly lines with derived utilisationPct",
      len(a.get("lines", [])) == 3
      and approx(a["lines"][0]["utilisationPct"], 0.5)
      and approx(a["lines"][2]["utilisationPct"], 0.7), str(a.get("lines")))
for k in METRIC_KEYS:
    check(f"metric {k} == {EXP[k]} (deterministic)", approx(a.get(k), EXP[k]), f"got {a.get(k)}")
check("per-line chequeReturns is inward+outward (M1=1, M2=1, M3=2)",
      approx(a["lines"][0]["chequeReturns"], 1) and approx(a["lines"][1]["chequeReturns"], 1)
      and approx(a["lines"][2]["chequeReturns"], 2), str([l["chequeReturns"] for l in a["lines"]]))


print("\n== 2. get by ref + list by applicationRef ==")
st, one = call("GET", f"/origination/api/banking-asr/{asr_ref}")
check("get by ref returns the record", st == 200 and one["asrRef"] == asr_ref, str(one))
st, lst = call("GET", f"/origination/api/banking-asr?applicationRef={aref}")
lst = must(st, lst, "list by applicationRef")
check("ASR listed for the application", any(x["asrRef"] == asr_ref for x in lst), str([x["asrRef"] for x in lst]))


print("\n== 3. advisory summary is advisory and does NOT change any metric ==")
metrics_before = {k: a.get(k) for k in METRIC_KEYS}
st, sm = call("POST", f"/origination/api/banking-asr/{asr_ref}/summary", actor="analyst.user")
sm = must(st, sm, "summary")
check("advisory summary populated + advisory=true",
      bool(sm.get("advisorySummary")) and sm.get("advisory") is True, str(sm.get("advisorySummary")))
check("every metric byte-identical after the advisory summary",
      all(sm.get(k) == metrics_before[k] for k in METRIC_KEYS),
      str({k: (metrics_before[k], sm.get(k)) for k in METRIC_KEYS if sm.get(k) != metrics_before[k]}))


print("\n== 4. authoritative rating byte-identical after the advisory run ==")
st, rating_after = call("GET", f"/risk/api/risk/{aref}/rating")
rating_after = must(st, rating_after, "rating after")
check("modelGrade / finalGrade / pd byte-identical (advisory never touches a figure)",
      grade_of(rating_after) == g_before, f"{g_before} -> {grade_of(rating_after)}")


print("\n== 5. human confirm gate ==")
st, cf = call("POST", f"/origination/api/banking-asr/{asr_ref}/confirm",
              {"note": "conduct reviewed — satisfactory"}, actor="credit.officer")
cf = must(st, cf, "confirm")
check("confirm -> CONFIRMED and records the actor",
      cf["status"] == "CONFIRMED" and cf["confirmedBy"] == "credit.officer", str(cf))
check("metrics still byte-identical after confirm",
      all(cf.get(k) == metrics_before[k] for k in METRIC_KEYS), "metrics moved on confirm")
st, b = call("POST", f"/origination/api/banking-asr/{asr_ref}/confirm", {}, actor="credit.officer")
check("re-confirm a CONFIRMED ASR -> 409", st == 409, f"{st} {b}")


print("\n== 6. audit provenance (SYSTEM compute, AI summary, HUMAN confirm) ==")
st, aud = call("GET", f"/origination/api/audit/subject?type=Application&id={aref}")
aud = must(st, aud, "audit subject")
computed = [e for e in aud if e.get("eventType") == "BANKING_ASR_COMPUTED"]
summarised = [e for e in aud if e.get("eventType") == "BANKING_ASR_SUMMARISED"]
confirmed = [e for e in aud if e.get("eventType") == "BANKING_ASR_CONFIRMED"]
check("BANKING_ASR_COMPUTED stamped SYSTEM",
      len(computed) >= 1 and all(e.get("actorType") == "SYSTEM" for e in computed), str(computed[:1]))
check("BANKING_ASR_SUMMARISED stamped AI",
      len(summarised) >= 1 and all(e.get("actorType") == "AI" for e in summarised), str(summarised[:1]))
check("BANKING_ASR_CONFIRMED stamped HUMAN",
      len(confirmed) >= 1 and all(e.get("actorType") == "HUMAN" for e in confirmed), str(confirmed[:1]))


print(f"\n== Banking ASR e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
