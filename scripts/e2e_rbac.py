#!/usr/bin/env python3
"""
Role-based actor model (RBAC) — e2e.

Name-equality SoD proves maker != checker; this layer proves each was ALLOWED
to act at all. Asserts:
  1. An actor unknown to the ACTOR_ROLE directory cannot touch a gated action.
  2. A known actor with the WRONG role is blocked (analyst can't authorise,
     credit.ops can't release, cad.maker can't waive).
  3. The right role passes — and SoD still applies ON TOP (an actor holding
     every role still cannot be maker and checker on the same draw).
  4. A role grant flows maker -> checker through the ACTOR_ROLE master and takes
     effect after the rbac cache invalidate.
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


print("== 0. Setup: deal to sanction + limit tree ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Kaveri Agro Foods Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "RB-KA-1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 500_000_000, "currency": "INR", "tenorMonths": 48, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 450_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
ref = app["reference"]
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
fref = must(st, facs, "facilities")[0]["reference"]
call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")
call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
st, reg = call("GET", f"/decision/api/cps/{ref}")
reg = must(st, reg, "cp register")

print("\n== 1. CP clear / waive are role-gated ==")
open_cps = [c for c in reg if c["mandatory"] and c["status"] == "OPEN"]
waive_target = next((c for c in open_cps if c["code"] == "CP-MAC"), open_cps[0])
st, body = call("POST", f"/decision/api/cps/{waive_target['id']}/waive",
                {"reason": "ops shortcut"}, actor="cad.maker")
check("waive by CAD_OPS blocked — waivers need CREDIT_COMMITTEE", st == 403, f"{st} {body}")
check("403 names the missing role", "CREDIT_COMMITTEE" in str(body), str(body)[:200])
st, _ = call("POST", f"/decision/api/cps/{waive_target['id']}/waive",
             {"reason": "committee approved exception"}, actor="credit.committee")
check("waive by CREDIT_COMMITTEE succeeds", st == 200, f"{st}")

st, body = call("POST", f"/decision/api/cps/{open_cps[0]['id'] if open_cps[0]['id'] != waive_target['id'] else open_cps[1]['id']}/clear",
                {"evidenceRef": "DOC-X"}, actor="lie.engineer")
check("clear by LIE blocked — clearing needs CAD_OPS/CREDIT_OPS", st == 403, f"{st} {body}")
for c in open_cps:
    if c["id"] == waive_target["id"]:
        continue
    call("POST", f"/decision/api/cps/{c['id']}/clear", {"evidenceRef": "DOC-" + c["code"]}, actor="cad.maker")
st, gate = call("GET", f"/decision/api/cps/gate/{ref}/{fref}")
check("gate open after role-correct clears", gate["canDrawdown"] is True, str(gate))

print("\n== 2. Unknown actor: the directory denies what it does not know ==")
st, body = call("POST", f"/decision/api/disbursement/{ref}/request",
                {"facilityRef": fref, "amount": 50_000_000, "currency": "INR",
                 "purpose": "intern tries"}, actor="intern.user")
check("unknown actor cannot request a drawdown (403)", st == 403, f"{st} {body}")
check("403 points at the ACTOR_ROLE master", "ACTOR_ROLE" in str(body), str(body)[:200])

print("\n== 3. Known actor, wrong role ==")
st, d1 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 200_000_000, "currency": "INR",
               "purpose": "tranche 1"}, actor="credit.ops")
d1 = must(st, d1, "request d1")
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="analyst.user")
check("ANALYST cannot authorise (403 role)", st == 403 and "role" in str(body).lower(), f"{st} {body}")
st, d1a = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="credit.officer")
check("CREDIT_OFFICER authorises", st == 200 and d1a["status"] == "AUTHORIZED", f"{st}")
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="credit.ops")
check("CREDIT_OPS cannot release — money movement needs TREASURY_OPS",
      st == 403 and "TREASURY_OPS" in str(body), f"{st} {body}")
st, d1r = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="treasury.ops")
check("TREASURY_OPS releases", st == 200 and d1r["status"] == "RELEASED", f"{st}")

print("\n== 4. Repayment lanes are role-gated ==")
st, body = call("POST", f"/decision/api/repayments/{ref}/record",
                {"facilityRef": fref, "amount": 10_000_000}, actor="analyst.user")
check("ANALYST cannot record a repayment (403)", st == 403, f"{st} {body}")
st, rp = call("POST", f"/decision/api/repayments/{ref}/record",
              {"facilityRef": fref, "amount": 10_000_000}, actor="loan.ops")
rp = must(st, rp, "record repayment")
st, body = call("POST", f"/decision/api/repayments/{rp['id']}/confirm", actor="treasury.ops")
check("TREASURY_OPS cannot confirm a repayment — needs LOAN_OPS", st == 403, f"{st} {body}")
st, rpc = call("POST", f"/decision/api/repayments/{rp['id']}/confirm", actor="loan.checker")
check("LOAN_OPS checker confirms", st == 200 and rpc["status"] == "CONFIRMED", f"{st}")

print("\n== 5. SoD still applies ON TOP of roles ==")
# demo.user holds every operational role — but still cannot be maker AND checker.
st, d2 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 100_000_000, "currency": "INR",
               "purpose": "super-user tranche"}, actor="demo.user")
d2 = must(st, d2, "request d2 as demo.user")
st, body = call("POST", f"/decision/api/disbursement/{d2['id']}/authorize", {}, actor="demo.user")
check("all-roles actor still blocked from self-authorising (SoD)", st == 403, f"{st} {body}")
st, _ = call("POST", f"/decision/api/disbursement/{d2['id']}/cancel",
             {"reason": "rbac test cleanup"}, actor="demo.user")
check("cleanup cancel by requester ok", st == 200, f"{st}")

print("\n== 6. Role grant flows maker -> checker through the ACTOR_ROLE master ==")
st, rec = call("POST", "/config/api/masters/ACTOR_ROLE",
               {"recordKey": "intern.user", "jurisdiction": None,
                "payload": {"displayName": "Promoted Intern", "roles": ["CREDIT_OPS"]}},
               actor="config.admin")
rec = must(st, rec, "propose role grant")
st, _ = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="config.checker")
check("role grant approved by a different actor", st == 200, f"{st}")
st, inv = call("POST", "/decision/api/governance/rbac/cache/invalidate")
check("rbac cache invalidated on decision-service", st == 200 and inv.get("invalidated") is True, f"{st} {inv}")
st, d3 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 20_000_000, "currency": "INR",
               "purpose": "intern can now request"}, actor="intern.user")
check("granted role takes effect — intern.user can now request", st == 200 and d3["status"] == "DRAFT", f"{st} {d3}")
call("POST", f"/decision/api/disbursement/{d3['id']}/cancel", {"reason": "cleanup"}, actor="intern.user")
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/reverse",
                {"reason": "intern tries reversal"}, actor="intern.user")
check("granted CREDIT_OPS still cannot reverse (needs TREASURY_OPS)", st == 403, f"{st} {body}")

print(f"\n== RBAC e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
