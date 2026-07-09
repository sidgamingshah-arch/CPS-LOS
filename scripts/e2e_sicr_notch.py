#!/usr/bin/env python3
"""
SICR-by-notch staging — e2e (P1 item 10, portfolio ECL).

IFRS 9 / Ind AS 109 stage-2 (significant increase in credit risk) is triggered
not only by days-past-due or a weak absolute grade, but by a material RATING
DOWNGRADE relative to origination. This test proves the deterministic notch
rule the EclEngine now applies:

  * ExposureRecord snapshots the grade at FIRST booking (originationGrade),
    immutable across re-registers.
  * When the current grade is >= `sicr_notch_downgrade_stage2` notches worse
    than origination (default 3), the exposure moves to STAGE_2 — even at
    dpd 0 and even when the current grade is not itself a weak/SICR grade.

The scenario is engineered so the notch rule is the SOLE trigger: the exposure
sits at dpd 0 (below the 30-dpd stage-2 gate) and the downgraded grade is "B"
(NOT one of the CCC/CC/C weak grades that force stage-2 on their own). Both the
origination and current grades are set via governed maker-checker overrides
(credit.committee proposes, cro confirms — maker != checker), so the test is
independent of the scorecard's own output.

Proves:
  1. Origination-grade snapshot is set once and survives a re-register unchanged.
  2. At origination (AAA, dpd 0): STAGE_1, no notch downgrade.
  3. After a 5-notch downgrade (AAA -> B) at dpd 0: STAGE_2, driven purely by
     the notch rule (sicrByNotch), with the reasoning echoed in the ECL trace.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
SICR_GRADES = ("CCC", "CC", "C", "D")


def call(method, path, body=None, actor="rm.user"):
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


def line(v):
    return {"value": v, "sourceDocument": "sn.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def override_to(ref, grade, reason="SECTOR_OUTLOOK"):
    """Governed downgrade/upgrade: credit.committee proposes (99-notch authority), cro confirms."""
    st, ov = call("POST", f"/risk/api/risk/{ref}/rating/override",
                  {"proposedGrade": grade, "reasonCode": reason,
                   "note": f"e2e notch scenario -> {grade}", "role": "CREDIT_COMMITTEE"},
                  actor="credit.committee")
    ov = must(st, ov, f"override -> {grade}")
    st, cf = call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="cro")
    cf = must(st, cf, f"confirm {grade}")
    return cf


def ecl_of(ref):
    st, e = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ecl", actor="credit.ops")
    return must(st, e, "ecl")


print("== 1. Build + rate a mid-corporate deal ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Notch Steel Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "SICRN1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 400_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 500_000_000, "secured": True}, actor="rm.user")
ref = must(st, app, "app")["reference"]
call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    per("FY2024", 5e9, 3.2e9, 0.9e9, 0.15e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 2.8e9, 0.7e9),
    per("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.16e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 2.5e9, 0.6e9),
]}, actor="analyst.user")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
st, rated = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
rated = must(st, rated, "rate")
check("deal rated (scorecard model grade recorded)", rated.get("modelGrade") is not None, str(rated))


print("\n== 2. Set origination grade AAA (governed), book at dpd 0 -> STAGE_1 ==")
up = override_to(ref, "AAA")
check("rating overridden to AAA + confirmed", up["finalGrade"] == "AAA" and up["confirmed"],
      f"{up.get('finalGrade')} confirmed={up.get('confirmed')}")
call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
st, exp0 = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": 0}, actor="credit.ops")
exp0 = must(st, exp0, "register #1")
check("origination grade snapshotted = AAA", exp0["originationGrade"] == "AAA", str(exp0.get("originationGrade")))
check("current grade at booking = AAA", exp0["finalGrade"] == "AAA", str(exp0.get("finalGrade")))

ecl0 = ecl_of(ref)
t0 = ecl0.get("trace") or {}
check("origination exposure is STAGE_1 (AAA, dpd 0)", ecl0["stage"] == "STAGE_1", str(ecl0.get("stage")))
check("no notch downgrade at origination", t0.get("sicrByNotch") is False and t0.get("sicrNotchDowngrade") == 0,
      f"byNotch={t0.get('sicrByNotch')} downgrade={t0.get('sicrNotchDowngrade')}")
check("trace echoes origination + current grade", t0.get("originationGrade") == "AAA" and t0.get("currentGrade") == "AAA",
      f"orig={t0.get('originationGrade')} curr={t0.get('currentGrade')}")


print("\n== 3. Downgrade 5 notches (AAA -> B) at dpd 0 -> STAGE_2 by notch rule ==")
down = override_to(ref, "B")
check("rating overridden to B + confirmed", down["finalGrade"] == "B" and down["confirmed"],
      f"{down.get('finalGrade')} confirmed={down.get('confirmed')}")

# Re-register: current grade updates to B, origination grade must stay AAA (immutable).
st, exp1 = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": 0}, actor="credit.ops")
exp1 = must(st, exp1, "register #2")
check("origination grade IMMUTABLE across re-register (still AAA)",
      exp1["originationGrade"] == "AAA", str(exp1.get("originationGrade")))
check("current grade updated to B on re-register", exp1["finalGrade"] == "B", str(exp1.get("finalGrade")))

ecl1 = ecl_of(ref)
t1 = ecl1.get("trace") or {}
check("downgraded exposure is now STAGE_2", ecl1["stage"] == "STAGE_2", str(ecl1.get("stage")))
check("stage-2 trigger is the notch rule (sicrByNotch=true)", t1.get("sicrByNotch") is True, str(t1.get("sicrByNotch")))
check("notch downgrade computed = 5 (AAA -> B)", t1.get("sicrNotchDowngrade") == 5, str(t1.get("sicrNotchDowngrade")))
check("notch threshold read from pack (default 3)", t1.get("sicrNotchThreshold") == 3, str(t1.get("sicrNotchThreshold")))
check("trace shows origination AAA -> current B",
      t1.get("originationGrade") == "AAA" and t1.get("currentGrade") == "B",
      f"orig={t1.get('originationGrade')} curr={t1.get('currentGrade')}")

# Prove the notch rule is the SOLE trigger: dpd below the 30-day gate AND current
# grade is not itself a weak/SICR grade — absent the notch rule this is STAGE_1.
check("dpd 0 is below the stage-2 dpd gate (notch rule not dpd-driven)",
      t1.get("daysPastDue") == 0, str(t1.get("daysPastDue")))
check("current grade B is NOT a weak/SICR grade (notch rule not grade-driven)",
      exp1["finalGrade"] not in SICR_GRADES, str(exp1.get("finalGrade")))


print(f"\n== SICR-by-notch e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
