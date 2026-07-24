#!/usr/bin/env python3
"""
RBAC governance gates — e2e (round-3 defect sweep).

Proves the two governance defects raised in tester feedback are closed:

  1. ORIGINATION IS FIRST-LINE — a COMPLIANCE (2nd/3rd-line) actor may NOT originate a deal or
     create a prospect. Enforced server-side with the SOFT ProtectedAction.ORIGINATE gate
     (ActorDirectory.requireRecognised): a positively-recognised control-function actor is denied
     403, while a first-line actor (RM) proceeds. Unroled/unknown actors stay permissive (the
     directory narrows only for a role it recognises), so the harness default is unaffected.

  2. CPT SIGN-OFF IS NOT AN ANALYST'S — confirming/rejecting a Client Planning Template is a
     named sign-off by the relationship owner (or credit officer over them). An ANALYST is denied
     403; an RM proceeds. Hard ProtectedAction.CPT_REVIEW gate.

Standalone against the gateway on :8080; binds no port. Registered in run_regression by the
coordinator (not self-registered).
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


print("== RBAC gates: origination is first-line ==")

# A first-line RM creates a borrower + a deal (both succeed).
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "RBAC Gate Test Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "RBACG1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "counterparty (rm.user)")
cp_ref = cp["reference"]

st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp_ref, "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 500_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 750_000_000, "secured": True}, actor="rm.user")
check("RM (first line) CAN originate a deal", st == 200, f"{st} {app}")

# COMPLIANCE (2nd/3rd line) is denied both origination paths.
st, err = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp_ref, "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 100_000_000, "currency": "INR", "tenorMonths": 36, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 150_000_000, "secured": True},
    actor="compliance.officer")
check("COMPLIANCE CANNOT originate a deal (403)", st == 403, f"{st} {err}")

st, err = call("POST", "/counterparty/api/initiation/prospects", {
    "legalName": "Compliance Should Not Source Ltd", "legalForm": "PRIVATE_LTD",
    "jurisdiction": "IN-RBI", "segment": "SME", "sector": "SERVICES", "country": "IN",
    "borrowerType": "NTB", "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="compliance.officer")
check("COMPLIANCE CANNOT create a prospect (403)", st == 403, f"{st} {err}")

# A first-line RM CAN create a prospect.
st, pr = call("POST", "/counterparty/api/initiation/prospects", {
    "legalName": "RM Sourced Prospect Ltd", "legalForm": "PRIVATE_LTD",
    "jurisdiction": "IN-RBI", "segment": "SME", "sector": "SERVICES", "country": "IN",
    "borrowerType": "NTB", "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
check("RM CAN create a prospect", st == 200, f"{st} {pr}")

print("== RBAC gates: CPT sign-off is not an analyst's ==")

st, cpt = call("POST", f"/decision/api/cpt/{cp_ref}/generate", {}, actor="rm.user")
cpt = must(st, cpt, "CPT generate (rm.user)")
cpt_id = cpt["id"]
check("CPT drafted (DRAFT)", cpt.get("status") == "DRAFT", cpt.get("status"))

st, err = call("POST", f"/decision/api/cpt/templates/{cpt_id}/review",
               {"approve": True, "note": "analyst tries to sign off"}, actor="analyst.user")
check("ANALYST CANNOT approve a CPT (403)", st == 403, f"{st} {err}")

st, conf = call("POST", f"/decision/api/cpt/templates/{cpt_id}/review",
                {"approve": True, "note": "RM signs off the plan"}, actor="rm.user")
check("RM CAN approve a CPT (CONFIRMED)", st == 200 and conf.get("status") == "CONFIRMED", f"{st} {conf}")

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
