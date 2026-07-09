#!/usr/bin/env python3
"""
Post-sanction facility amendment via DoA — e2e.

  1. An increase routes to the DoA authority for the POST-amendment total
     exposure x current grade (DOA_MATRIX rule pack — regime data, not code).
  2. Approval needs role RANK >= required authority (RBAC) AND a different
     human than the proposer (SoD).
  3. On approval the origination facility of record AND the limit tree update;
     the freed/granted headroom is immediately drawable.
  4. Guards: decrease below committed drawdowns blocked; one PROPOSED
     amendment per facility; tenor extension applies to the limit node.
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


print("== 0. Setup: rated deal, limit tree, 300M drawn ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Vega Auto Components Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "AM-VA-1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 600_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 550_000_000, "secured": True}, actor="rm.user")
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
              {"facilityRef": fref, "amount": 300_000_000, "currency": "INR",
               "purpose": "tranche 1"}, actor="credit.ops")
d1 = must(st, d1, "request d1")
call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="credit.officer")
st, d1r = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="treasury.ops")
must(st, d1r, "release d1")
print(f"    deal {ref}, facility {fref}, 600M sanctioned / 300M drawn")

print("\n== 1. Propose an increase 600M -> 900M — routed by the DoA matrix ==")
st, body = call("POST", f"/decision/api/amendments/{ref}/propose",
                {"facilityRef": fref, "newAmount": 900_000_000, "reason": "intern tries"},
                actor="intern2.user")
check("unknown actor cannot propose (403 role)", st == 403, f"{st} {body}")
st, a1 = call("POST", f"/decision/api/amendments/{ref}/propose",
              {"facilityRef": fref, "newAmount": 900_000_000,
               "reason": "expansion phase 2 working capital"}, actor="rm.user")
a1 = must(st, a1, "propose increase")
check("amendment PROPOSED with type INCREASE", a1["status"] == "PROPOSED" and a1["amendmentType"] == "INCREASE",
      str(a1))
check("routed exposure = post-amendment total (900M)", abs(a1["routedExposure"] - 900_000_000) < 1,
      str(a1["routedExposure"]))
check("900M routes to CREDIT_COMMITTEE (250M < x <= 1B band)",
      a1["requiredAuthority"] == "CREDIT_COMMITTEE", str(a1["requiredAuthority"]))

print("\n== 2. Only one PROPOSED amendment per facility ==")
st, body = call("POST", f"/decision/api/amendments/{ref}/propose",
                {"facilityRef": fref, "newAmount": 950_000_000, "reason": "another"}, actor="rm.user")
check("second concurrent proposal blocked (409)", st == 409, f"{st} {body}")

print("\n== 3. Approval needs authority RANK + a different human ==")
st, body = call("POST", f"/decision/api/amendments/{a1['id']}/approve", {}, actor="rm.user")
check("proposer cannot self-approve (403 SoD)", st == 403, f"{st} {body}")
st, body = call("POST", f"/decision/api/amendments/{a1['id']}/approve", {}, actor="credit.officer")
check("CREDIT_OFFICER (rank below committee) blocked (403)",
      st == 403 and "CREDIT_COMMITTEE" in str(body), f"{st} {body}")
st, a1ok = call("POST", f"/decision/api/amendments/{a1['id']}/approve",
                {"comment": "committee minutes 2026-06-12"}, actor="credit.committee")
check("CREDIT_COMMITTEE approves", st == 200 and a1ok["status"] == "APPROVED", f"{st} {a1ok}")
check("appliedAt stamped", a1ok.get("appliedAt") is not None, str(a1ok.get("appliedAt")))

print("\n== 4. The amendment is APPLIED: facility of record + limit tree + headroom ==")
st, facs2 = call("GET", f"/origination/api/applications/{ref}/facilities")
fac2 = next(f for f in facs2 if f["reference"] == fref)
check("origination facility amount now 900M", abs(fac2["amount"] - 900_000_000) < 1, str(fac2["amount"]))
st, node = call("GET", f"/limits/api/limits/by-facility?applicationRef={ref}&facilityRef={fref}")
check("limit node sanctioned now 900M", abs(node["sanctionedAmount"] - 900_000_000) < 1,
      str(node["sanctionedAmount"]))
st, view = call("GET", f"/limits/api/limits/view?cif={cif}")
check("obligor root rolled up to 900M", abs(view["totalSanctionedBase"] - 900_000_000) < 1,
      str(view.get("totalSanctionedBase")))
# 300M drawn; under the old 600M cap a 550M draw was impossible — now it fits.
st, d2 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 550_000_000, "currency": "INR",
               "purpose": "post-amendment draw"}, actor="credit.ops")
check("new headroom immediately drawable (300+550 <= 900)", st == 200 and d2["status"] == "DRAFT", f"{st} {d2}")
call("POST", f"/decision/api/disbursement/{d2['id']}/cancel", {"reason": "probe"}, actor="credit.ops")

print("\n== 5. Decrease below committed drawdowns is blocked ==")
st, body = call("POST", f"/decision/api/amendments/{ref}/propose",
                {"facilityRef": fref, "newAmount": 250_000_000,
                 "reason": "shrink below the 300M already drawn"}, actor="rm.user")
check("decrease below committed blocked (400)", st == 400 and "committed" in str(body), f"{st} {body}")

print("\n== 6. Tenor extension flows the same lane ==")
st, a2 = call("POST", f"/decision/api/amendments/{ref}/propose",
              {"facilityRef": fref, "newTenorMonths": 84,
               "reason": "repayment re-profiled to 7 years"}, actor="rm.user")
a2 = must(st, a2, "propose tenor extension")
check("type TENOR_EXTENSION, amount unchanged",
      a2["amendmentType"] == "TENOR_EXTENSION" and a2.get("proposedAmount") is None, str(a2))
st, a2ok = call("POST", f"/decision/api/amendments/{a2['id']}/approve",
                {"comment": "ok"}, actor="cro")
check("CRO (committee+board rank) approves the extension", st == 200 and a2ok["status"] == "APPROVED", f"{st}")
st, facs3 = call("GET", f"/origination/api/applications/{ref}/facilities")
fac3 = next(f for f in facs3 if f["reference"] == fref)
check("facility tenor now 84", fac3["tenorMonths"] == 84, str(fac3["tenorMonths"]))
st, node2 = call("GET", f"/limits/api/limits/by-facility?applicationRef={ref}&facilityRef={fref}")
check("limit node tenor now 84", node2["tenorMonths"] == 84, str(node2["tenorMonths"]))

print("\n== 7. Reject lane + history ==")
st, a3 = call("POST", f"/decision/api/amendments/{ref}/propose",
              {"facilityRef": fref, "newAmount": 950_000_000, "reason": "speculative"}, actor="rm.user")
a3 = must(st, a3, "propose for reject")
st, body = call("POST", f"/decision/api/amendments/{a3['id']}/reject",
                {"reason": "no authority"}, actor="loan.ops")
check("actor with no DoA-ladder authority cannot reject (403)", st == 403, f"{st} {body}")
st, a3r = call("POST", f"/decision/api/amendments/{a3['id']}/reject",
               {"reason": "insufficient collateral cover for 950M"}, actor="credit.officer")
check("rejected by an authority holder", st == 200 and a3r["status"] == "REJECTED", f"{st}")
st, facs4 = call("GET", f"/origination/api/applications/{ref}/facilities")
check("rejected amendment did NOT touch the facility",
      abs(next(f for f in facs4 if f["reference"] == fref)["amount"] - 900_000_000) < 1, str(facs4))
st, hist = call("GET", f"/decision/api/amendments/{ref}")
check("history lists all three amendments", st == 200 and len(hist) == 3, str(len(hist) if hist else 0))
st, audit_rows = call("GET", "/decision/api/audit")
check("FACILITY_AMENDMENT_APPROVED stamped HUMAN",
      any(a.get("eventType") == "FACILITY_AMENDMENT_APPROVED" and a.get("actorType") == "HUMAN"
          for a in audit_rows), "")

print(f"\n== Amendment e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
