#!/usr/bin/env python3
"""
Monitoring loop closure — e2e.

Closes the open end of the monitoring chain: covenant/conduct signal -> EWS
watchlist -> auto-opened collections case. The EWS scan already raises the
signals; this proves the escalation that was missing — a monitoring sweep turns
a SEVERE signal (90+ DPD here) into a SYSTEM-opened collections case, idempotently,
and the case is then workable through the normal collections workflow.

  1. A distressed exposure (95 DPD) swept -> EWS signal raised AND a collections
     case auto-opened by SYSTEM at STAGE_3, with the trigger recorded.
  2. Re-sweeping is idempotent — the case is refreshed, not duplicated.
  3. The auto-opened case is a normal OPEN case (workable: legal / restructure /
     write-off via the existing human + DoA gated flows).
  4. A healthy exposure swept -> no escalation, no case.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="portfolio.manager"):
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
        PASS += 1; print(f"  PASS  {name}")
    else:
        FAIL += 1; print(f"  FAIL  {name}  {detail}")


def must(st, b, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {b}"); sys.exit(1)
    return b


def book(name, reg, dpd):
    """Drive a deal to a booked exposure with the given days-past-due."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": name, "legalForm": "PUBLIC_LTD", "registrationNo": reg, "jurisdiction": "IN-RBI",
        "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN", "listedEntity": True,
        "regulatedFi": False, "pep": False, "adverseMedia": False, "highRiskJurisdiction": False,
        "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, f"cp {name}")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": name,
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 400_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "x",
        "collateralType": "PROPERTY", "collateralValue": 500_000_000, "secured": True}, actor="rm.user")
    app = must(st, app, f"app {name}")
    ref = app["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
            "REVENUE": {"value": 4e9, "sourceDocument": "m", "confidence": 1.0},
            "COGS": {"value": 2.6e9, "sourceDocument": "m", "confidence": 1.0},
            "OPERATING_EXPENSES": {"value": 0.6e9, "sourceDocument": "m", "confidence": 1.0},
            "DEPRECIATION": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
            "INTEREST_EXPENSE": {"value": 0.12e9, "sourceDocument": "m", "confidence": 1.0},
            "TAX": {"value": 0.1e9, "sourceDocument": "m", "confidence": 1.0},
            "TOTAL_ASSETS": {"value": 5e9, "sourceDocument": "m", "confidence": 1.0},
            "CURRENT_ASSETS": {"value": 2e9, "sourceDocument": "m", "confidence": 1.0},
            "CASH": {"value": 0.5e9, "sourceDocument": "m", "confidence": 1.0},
            "CURRENT_LIABILITIES": {"value": 1.3e9, "sourceDocument": "m", "confidence": 1.0},
            "SHORT_TERM_DEBT": {"value": 0.4e9, "sourceDocument": "m", "confidence": 1.0},
            "LONG_TERM_DEBT": {"value": 1.0e9, "sourceDocument": "m", "confidence": 1.0},
            "CURRENT_PORTION_LTD": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
            "NET_WORTH": {"value": 2.3e9, "sourceDocument": "m", "confidence": 1.0},
            "CFO": {"value": 0.55e9, "sourceDocument": "m", "confidence": 1.0}}}]},
         actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="analyst.user")
    st, _ = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register",
                 {"daysPastDue": dpd}, actor="credit.ops")
    must(st, _, f"register {name}")
    return ref


print("== 0. Book a distressed (95 dpd) and a healthy (0 dpd) exposure ==")
bad = book("Cascade Metals Ltd", "MON-1", 95)
good = book("Summit Foods Ltd", "MON-2", 0)
print(f"    distressed {bad}, healthy {good}")

print("\n== 1. Sweep the distressed deal: EWS signal -> auto-opened collections case ==")
st, sweep = call("POST", f"/portfolio/api/portfolio/monitoring/sweep/{bad}")
sweep = must(st, sweep, "sweep distressed")
check("sweep raised at least one signal", sweep["signalsRaised"] >= 1, str(sweep))
check("sweep escalated to collections", sweep["escalated"] is True, str(sweep))
check("escalation cites the DPD signal", "DAYS_PAST_DUE" in sweep["escalationSignals"], str(sweep["escalationSignals"]))
check("a collections case id was returned", sweep.get("collectionsCaseId") is not None, str(sweep))
case_id = sweep["collectionsCaseId"]

st, case = call("GET", f"/decision/api/collections/{case_id}")
case = must(st, case, "get case")
check("case opened by SYSTEM monitoring", "SYSTEM" in (case.get("openedBy") or ""), str(case.get("openedBy")))
check("case staged STAGE_3 from 95 dpd", case["npaStage"] == "STAGE_3", str(case["npaStage"]))
check("trigger reason records the EWS escalation", "EWS" in (case.get("triggerReason") or ""), str(case.get("triggerReason")))
check("case is OPEN (workable)", case["status"] == "OPEN", str(case["status"]))

print("\n== 2. Re-sweep is idempotent — refresh, not duplicate ==")
st, sweep2 = call("POST", f"/portfolio/api/portfolio/monitoring/sweep/{bad}")
sweep2 = must(st, sweep2, "re-sweep")
check("re-sweep returns the SAME case id", sweep2.get("collectionsCaseId") == case_id,
      f"{sweep2.get('collectionsCaseId')} vs {case_id}")
st, cases = call("GET", f"/decision/api/collections?reference={bad}")
cases = must(st, cases, "list cases")
check("exactly one collections case for the distressed deal", len(cases) == 1, str(len(cases)))

print("\n== 3. The auto-opened case flows through the normal workflow ==")
# It's a normal OPEN case — a human can take it to legal (needs COLLECTIONS_HEAD/LEGAL).
st, legal = call("POST", f"/decision/api/collections/{case_id}/legal",
                 {"legalRef": "CASE/2026/MON/9001", "note": "from monitoring auto-open"},
                 actor="collections.head")
check("auto-opened case can be escalated to legal by an authorised actor",
      st == 200 and legal["status"] == "LEGAL", f"{st} {legal}")

print("\n== 4. Healthy deal: swept, no escalation, no case ==")
st, sweepH = call("POST", f"/portfolio/api/portfolio/monitoring/sweep/{good}")
sweepH = must(st, sweepH, "sweep healthy")
check("healthy deal not escalated", sweepH["escalated"] is False, str(sweepH))
check("healthy deal opened no case", sweepH.get("collectionsCaseId") is None, str(sweepH))
st, casesH = call("GET", f"/decision/api/collections?reference={good}")
check("no collections case on the healthy deal", st == 200 and len(casesH) == 0, str(casesH))

print("\n== 5. Sweep-all + audit trail ==")
st, sweepAll = call("POST", "/portfolio/api/portfolio/monitoring/sweep")
sweepAll = must(st, sweepAll, "sweep all")
check("sweep-all covers both deals", len(sweepAll) >= 2, str(len(sweepAll)))
st, audit = call("GET", "/portfolio/api/audit")
check("MONITORING_SWEEP stamped on the audit trail",
      any(a.get("eventType") == "MONITORING_SWEEP" for a in audit), "")
st, daudit = call("GET", "/decision/api/audit")
check("COLLECTIONS_CASE_AUTO_OPENED stamped (engine/SYSTEM)",
      any(a.get("eventType") == "COLLECTIONS_CASE_AUTO_OPENED" for a in daudit), "")

print(f"\n== Monitoring loop e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
