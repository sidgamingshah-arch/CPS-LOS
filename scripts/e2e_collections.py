#!/usr/bin/env python3
"""
Collections / NPA workflow — e2e.

A facility goes overdue; a case is opened; DPD updates restage it (STAGE_1 → 2 → 3);
the workflow fans into RESTRUCTURE (chains the existing FacilityAmendment lane via
the SAME DoA matrix), LEGAL (operational), WRITE-OFF (DoA-routed sub-workflow),
or CURE. Asserts:
  1. Stage transitions track the 30/60/90 cuts as DPD moves.
  2. Restructure goes through FacilityAmendment — the same approver rank applies.
  3. Write-off is DoA-routed on the write-off amount + grade; approval needs
     rank >= required authority and a different human than the opener; on
     approval the limit ledger releases the written-off amount.
  4. Legal needs COLLECTIONS_HEAD or LEGAL, not COLLECTIONS_OPS.
  5. Cure only when DPD = 0 and overdue = 0.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


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
        PASS += 1; print(f"  PASS  {name}")
    else:
        FAIL += 1; print(f"  FAIL  {name}  {detail}")


def must(st, b, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {b}"); sys.exit(1)
    return b


print("== 0. Setup: rated deal + limit tree + 200M released and 50M repaid ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Aravalli Cements Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "COL-AC-1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 400_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 400_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
ref = app["reference"]
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
fref = must(st, facs, "facilities")[0]["reference"]
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
st, tree = call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")
cif = must(st, tree, "limit tree")["cif"]
call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
st, reg = call("GET", f"/decision/api/cps/{ref}")
for c in reg:
    if c["mandatory"] and c["status"] == "OPEN":
        call("POST", f"/decision/api/cps/{c['id']}/clear", {"evidenceRef": "DOC-" + c["code"]}, actor="cad.maker")
st, d1 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 200_000_000, "currency": "INR",
               "purpose": "tranche 1"}, actor="credit.ops")
d1 = must(st, d1, "request d1")
call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="credit.officer")
call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="treasury.ops")
st, rp = call("POST", f"/decision/api/repayments/{ref}/record",
              {"facilityRef": fref, "amount": 50_000_000}, actor="loan.ops")
rp = must(st, rp, "record repayment")
call("POST", f"/decision/api/repayments/{rp['id']}/confirm", actor="loan.checker")
print(f"    deal {ref}, facility {fref}, 150M outstanding")

print("\n== 1. Open a collections case ==")
st, body = call("POST", f"/decision/api/collections/{ref}/open",
                {"facilityRef": fref, "daysPastDue": 5, "overdueAmount": 2_500_000},
                actor="analyst.user")
check("opener without COLLECTIONS_OPS denied", st == 403, f"{st} {body}")
st, c1 = call("POST", f"/decision/api/collections/{ref}/open",
              {"facilityRef": fref, "daysPastDue": 5, "overdueAmount": 2_500_000},
              actor="collections.ops")
c1 = must(st, c1, "open case")
check("case OPEN at STAGE_1 (5 dpd)", c1["status"] == "OPEN" and c1["npaStage"] == "STAGE_1", str(c1))
check("outstandingAtOpen captured (150M)", abs(c1["outstandingAtOpen"] - 150_000_000) < 1, str(c1["outstandingAtOpen"]))

st, body = call("POST", f"/decision/api/collections/{ref}/open",
                {"facilityRef": fref, "daysPastDue": 5, "overdueAmount": 2_500_000},
                actor="collections.ops")
check("only one active case per facility (409)", st == 409, f"{st} {body}")

print("\n== 2. DPD updates restage the case (30/60/90 cuts) ==")
st, c2 = call("POST", f"/decision/api/collections/{c1['id']}/dpd",
              {"daysPastDue": 35, "overdueAmount": 6_000_000, "note": "missed second instalment"},
              actor="collections.ops")
check("35 dpd moves to STAGE_2", c2["npaStage"] == "STAGE_2", str(c2))
st, c3 = call("POST", f"/decision/api/collections/{c1['id']}/dpd",
              {"daysPastDue": 95, "overdueAmount": 12_000_000, "note": "still no payment"},
              actor="collections.ops")
check("95 dpd moves to STAGE_3 (NPA)", c3["npaStage"] == "STAGE_3", str(c3))

print("\n== 3. Legal lane needs COLLECTIONS_HEAD / LEGAL ==")
st, body = call("POST", f"/decision/api/collections/{c1['id']}/legal",
                {"legalRef": "CASE/2026/SARFAESI/1234"}, actor="collections.ops")
check("collections.ops cannot initiate legal (403)", st == 403, f"{st} {body}")
st, body = call("POST", f"/decision/api/collections/{c1['id']}/legal",
                {"legalRef": ""}, actor="legal.counsel")
check("legal needs a reference (400)", st == 400, f"{st} {body}")

print("\n== 4. Restructure chains the FacilityAmendment lane (same DoA matrix) ==")
st, am = call("POST", f"/decision/api/collections/{c1['id']}/restructure/propose",
              {"newTenorMonths": 96, "reason": "stretch to 8 years to reduce instalments"},
              actor="collections.ops")
am = must(st, am, "propose restructure")
check("restructure created an amendment in the FacilityAmendment lane",
      am["status"] == "PROPOSED" and am["amendmentType"] == "TENOR_EXTENSION", str(am))
check("amendment routed via DoA (committee/board)",
      am["requiredAuthority"] in ("CREDIT_OFFICER", "CREDIT_COMMITTEE", "BOARD_COMMITTEE"),
      str(am["requiredAuthority"]))
# Approve through the existing amendment endpoint, with a different human + authority rank.
st, body = call("POST", f"/decision/api/amendments/{am['id']}/approve", {},
                actor="collections.ops")
check("restructure approval needs the routed authority (403)", st == 403, f"{st} {body}")
st, amok = call("POST", f"/decision/api/amendments/{am['id']}/approve",
                {"comment": "stretch approved"}, actor="cro")
check("CRO approves the restructure amendment",
      st == 200 and amok["status"] == "APPROVED", f"{st} {amok}")
st, cR = call("POST", f"/decision/api/collections/{c1['id']}/restructure/applied",
              {}, actor="collections.ops")
check("case flips to RESTRUCTURED", cR["status"] == "RESTRUCTURED", str(cR))

print("\n== 5. Write-off: DoA-routed on the write-off amount, limit ledger releases ==")
# Open a fresh case to run write-off — restructured cases are terminal for new actions.
st, d2 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 100_000_000, "currency": "INR",
               "purpose": "tranche 2"}, actor="credit.ops")
d2 = must(st, d2, "request d2")
call("POST", f"/decision/api/disbursement/{d2['id']}/authorize", {}, actor="credit.officer")
call("POST", f"/decision/api/disbursement/{d2['id']}/release", actor="treasury.ops")
st, c4 = call("POST", f"/decision/api/collections/{ref}/open",
              {"facilityRef": fref, "daysPastDue": 200, "overdueAmount": 25_000_000},
              actor="collections.ops")
c4 = must(st, c4, "open case 2")

# Capture the ledger BEFORE any approval lands so we can see the drop.
st, view0 = call("GET", f"/limits/api/limits/view?cif={cif}")
out_before = view0["totalOutstandingBase"]

st, body = call("POST", f"/decision/api/collections/{c4['id']}/write-off/propose",
                {"amount": 999_999_999, "reason": "test"}, actor="collections.head")
check("write-off above outstanding rejected (400)", st == 400, f"{st} {body}")
st, body = call("POST", f"/decision/api/collections/{c4['id']}/write-off/propose",
                {"amount": 20_000_000, "reason": "speculation"}, actor="rm.user")
check("write-off propose needs a COLLECTIONS / CREDIT_OPS role", st == 403, f"{st} {body}")
st, prop = call("POST", f"/decision/api/collections/{c4['id']}/write-off/propose",
                {"amount": 20_000_000,
                 "reason": "collateral realised, balance unrecoverable"},
                actor="collections.head")
prop = must(st, prop, "propose write-off")
check("write-off routed to a DoA authority", prop["requiredAuthority"] in
      ("RM_HEAD", "CREDIT_OFFICER", "CREDIT_COMMITTEE", "BOARD_COMMITTEE"), str(prop))

# SoD: the proposer (collections.head) cannot also approve.
st, body = call("POST", f"/decision/api/collections/{c4['id']}/write-off/approve",
                {}, actor="collections.head")
check("write-off proposer cannot also approve (403 SoD)", st == 403, f"{st} {body}")

# Rank check: credit.officer either approves at-rank (CREDIT_OFFICER) or is blocked
# for higher routings. Either way, the routed authority gates the decision.
expected_auth = prop["requiredAuthority"]
st, body = call("POST", f"/decision/api/collections/{c4['id']}/write-off/approve",
                {"comment": "officer attempt"}, actor="credit.officer")
if expected_auth == "CREDIT_OFFICER":
    check("CREDIT_OFFICER may approve at-rank write-off",
          st == 200 and body["status"] == "WRITTEN_OFF", f"{st} {body}")
    final = body
else:
    check("under-ranked approver blocked (403)", st == 403, f"{st} {body}")
    st, body = call("POST", f"/decision/api/collections/{c4['id']}/write-off/approve",
                    {"comment": "lossy but final"}, actor="cro")
    check("CRO approves at-rank write-off",
          st == 200 and body["status"] == "WRITTEN_OFF", f"{st} {body}")
    final = body

check("case ends WRITTEN_OFF", final["status"] == "WRITTEN_OFF", str(final))
check("writeOffAmount captured", abs(final["writeOffAmount"] - 20_000_000) < 1, str(final["writeOffAmount"]))

st, view1 = call("GET", f"/limits/api/limits/view?cif={cif}")
check("limit ledger dropped by the written-off amount",
      abs((out_before - view1["totalOutstandingBase"]) - 20_000_000) < 1,
      f"{out_before} -> {view1['totalOutstandingBase']}")

print("\n== 6. Cure: only when DPD = 0 and overdue = 0 ==")
st, d3 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 30_000_000, "currency": "INR",
               "purpose": "tranche 3 (for cure test)"}, actor="credit.ops")
d3 = must(st, d3, "request d3")
call("POST", f"/decision/api/disbursement/{d3['id']}/authorize", {}, actor="credit.officer")
call("POST", f"/decision/api/disbursement/{d3['id']}/release", actor="treasury.ops")
st, c5 = call("POST", f"/decision/api/collections/{ref}/open",
              {"facilityRef": fref, "daysPastDue": 45, "overdueAmount": 3_000_000},
              actor="collections.ops")
c5 = must(st, c5, "open case 3")
st, body = call("POST", f"/decision/api/collections/{c5['id']}/cure",
                {"note": "still overdue"}, actor="collections.ops")
check("cure while overdue stands rejected (409)", st == 409, f"{st} {body}")
call("POST", f"/decision/api/collections/{c5['id']}/dpd",
     {"daysPastDue": 0, "overdueAmount": 0, "note": "all instalments paid"}, actor="collections.ops")
st, cured = call("POST", f"/decision/api/collections/{c5['id']}/cure",
                 {"note": "borrower repaid"}, actor="collections.ops")
check("cure once cleared succeeds", st == 200 and cured["status"] == "CURED", f"{st} {cured}")

print("\n== 7. List + audit ==")
st, lst = call("GET", f"/decision/api/collections?reference={ref}")
check("list returns 3 cases for the deal", st == 200 and len(lst) == 3, str(len(lst) if lst else 0))
st, audit_rows = call("GET", "/decision/api/audit")
check("COLLECTIONS_WRITTEN_OFF stamped HUMAN",
      any(a.get("eventType") == "COLLECTIONS_WRITTEN_OFF" and a.get("actorType") == "HUMAN"
          for a in audit_rows), "")
check("COLLECTIONS_RESTRUCTURED stamped HUMAN",
      any(a.get("eventType") == "COLLECTIONS_RESTRUCTURED" and a.get("actorType") == "HUMAN"
          for a in audit_rows), "")

print(f"\n== Collections e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
