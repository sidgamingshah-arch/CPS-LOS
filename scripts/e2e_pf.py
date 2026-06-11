#!/usr/bin/env python3
"""
Project-finance post-drawdown mechanics — e2e.

Proves the PF drawdown gate (layered on top of the CP gate):
  1. Define construction milestones + DSRA/TRA reserves on a PF facility.
  2. A PF drawdown that names an uncertified milestone is blocked (403).
  3. A drawdown is also blocked while a reserve is underfunded.
  4. LIE-certify the milestone + fund the reserves -> drawdown authorises.
  5. Releasing the tranche marks the milestone DRAWN; it can't be re-drawn.
  6. The next tranche's milestone must be certified independently.
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


print("== 0. Setup: PROJECT_FINANCE deal + facility + limit tree + cleared CPs ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Solaris Power SPV Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "PF-SPV-1",
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "sector": "INFRASTRUCTURE", "country": "IN",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "facilityType": "PROJECT_FINANCE",
    "requestedAmount": 5_000_000_000, "currency": "INR", "tenorMonths": 180, "purpose": "Solar park",
    "collateralType": "PROPERTY", "collateralValue": 6_000_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
ref = app["reference"]
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
facs = must(st, facs, "facilities")
fref = facs[0]["reference"]

# Build limit tree (release books utilisation).
call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")

# Seed + clear ALL mandatory CPs so the CP gate is open (we want to test the PF gate).
call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
st, reg = call("GET", f"/decision/api/cps/{ref}")
for c in reg:
    if c["mandatory"] and c["status"] == "OPEN":
        call("POST", f"/decision/api/cps/{c['id']}/clear", {"evidenceRef": "DOC-" + c["code"]}, actor="cad.maker")
print(f"    PF deal {ref}, facility {fref}, CPs cleared")

print("\n== 1. Define milestones + reserves ==")
st, m1 = call("POST", f"/decision/api/pf/{ref}/milestones",
              {"facilityRef": fref, "sequence": 1, "name": "Land + financial close",
               "plannedAmount": 1_000_000_000, "plannedDate": "2026-09-30"},
              actor="credit.ops")
m1 = must(st, m1, "milestone 1")
st, m2 = call("POST", f"/decision/api/pf/{ref}/milestones",
              {"facilityRef": fref, "sequence": 2, "name": "EPC 50% complete",
               "plannedAmount": 2_000_000_000, "plannedDate": "2027-03-31"},
              actor="credit.ops")
m2 = must(st, m2, "milestone 2")
check("milestones start PLANNED", m1["status"] == "PLANNED" and m2["status"] == "PLANNED")
check("plannedDate captured on milestone 1", m1.get("plannedDate") == "2026-09-30", str(m1.get("plannedDate")))
check("plannedDate captured on milestone 2", m2.get("plannedDate") == "2027-03-31", str(m2.get("plannedDate")))
st, dsra = call("POST", f"/decision/api/pf/{ref}/reserves",
                {"accountType": "DSRA", "requiredAmount": 250_000_000}, actor="credit.ops")
dsra = must(st, dsra, "dsra")
check("DSRA starts SHORTFALL (zero balance)", dsra["status"] == "SHORTFALL", str(dsra["status"]))

print("\n== 2. PF drawdown blocked: milestone not certified + DSRA short ==")
st, d1 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 1_000_000_000, "currency": "INR",
               "purpose": "tranche 1", "milestoneSequence": 1}, actor="credit.ops")
d1 = must(st, d1, "request d1")
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="credit.officer")
check("authorise blocked by PF gate (403)", st == 403, f"{st} {body}")
check("403 cites milestone not certified", "NOT_CERTIFIED" in str(body) or "LIE" in str(body), str(body))

st, gate = call("GET", f"/decision/api/pf/gate/{ref}/{fref}?milestoneSequence=1")
check("gate shows milestone + reserve blockers", len(gate["blockers"]) >= 2, str(gate["blockers"]))

print("\n== 3. Certify milestone 1 but DSRA still short -> still blocked ==")
st, _ = call("POST", f"/decision/api/pf/milestones/{m1['id']}/certify",
             {"certificationRef": "LIE-CERT-001", "note": "site inspection passed"}, actor="lie.engineer")
must(st, _, "certify m1")
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="credit.officer")
check("still blocked while DSRA underfunded", st == 403, f"{st} {body}")
check("403 cites DSRA shortfall", "DSRA" in str(body), str(body))

print("\n== 4. Fund DSRA to requirement -> gate opens -> authorise + release ==")
st, funded = call("POST", f"/decision/api/pf/reserves/{dsra['id']}/fund",
                  {"amount": 250_000_000, "note": "initial funding"}, actor="treasury.ops")
funded = must(st, funded, "fund dsra")
check("DSRA now FUNDED", funded["status"] == "FUNDED", str(funded["status"]))

st, gate2 = call("GET", f"/decision/api/pf/gate/{ref}/{fref}?milestoneSequence=1")
check("PF gate now open for milestone 1", gate2["canDrawdown"] is True, str(gate2))

st, d1a = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="credit.officer")
check("drawdown authorises once milestone certified + DSRA funded", st == 200 and d1a["status"] == "AUTHORIZED", f"{st} {d1a}")
st, d1r = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="credit.ops")
check("tranche releases", st == 200 and d1r["status"] == "RELEASED", f"{st} {d1r}")

print("\n== 5. Milestone 1 is now DRAWN and cannot be re-drawn ==")
st, ms = call("GET", f"/decision/api/pf/{ref}/milestones?facilityRef={fref}")
m1_now = next(m for m in ms if m["sequence"] == 1)
check("milestone 1 marked DRAWN", m1_now["status"] == "DRAWN", str(m1_now["status"]))
st, dx = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 100_000_000, "currency": "INR",
               "purpose": "re-draw m1", "milestoneSequence": 1}, actor="credit.ops")
st, body = call("POST", f"/decision/api/disbursement/{dx['id']}/authorize", {}, actor="credit.officer")
check("re-drawing a DRAWN milestone is blocked", st == 403, f"{st} {body}")
check("403 cites already drawn", "ALREADY_DRAWN" in str(body), str(body))

print("\n== 6. Second tranche needs milestone 2 certified independently ==")
st, d2 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 2_000_000_000, "currency": "INR",
               "purpose": "tranche 2", "milestoneSequence": 2}, actor="credit.ops")
d2 = must(st, d2, "request d2")
st, body = call("POST", f"/decision/api/disbursement/{d2['id']}/authorize", {}, actor="credit.officer")
check("tranche 2 blocked until milestone 2 certified", st == 403, f"{st} {body}")
call("POST", f"/decision/api/pf/milestones/{m2['id']}/certify",
     {"certificationRef": "LIE-CERT-002"}, actor="lie.engineer")
st, d2a = call("POST", f"/decision/api/disbursement/{d2['id']}/authorize", {}, actor="credit.officer")
check("tranche 2 authorises after milestone 2 certified", st == 200 and d2a["status"] == "AUTHORIZED", f"{st} {d2a}")

print(f"\n== PF mechanics e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
