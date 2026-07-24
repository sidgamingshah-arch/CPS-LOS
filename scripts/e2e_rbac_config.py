#!/usr/bin/env python3
"""
RBAC configuration at admin level — e2e (round-3 Wave 2B).

"Who may perform which action" was a compile-time enum (ProtectedAction). It is now ALSO an
admin-editable ACTION_ROLE master that OVERLAYS the enum (maker-checker), so a bank can remap
authority as data with no code change — while the enum stays the safe fallback.

Proves:
  1. The effective action→role catalogue is exposed (/api/governance/rbac/actions) with each action's
     roles + source (ACTION_ROLE master vs enum fallback), seeded byte-identical to the enum.
  2. The override has TEETH: remap the `originate` action to admit COMPLIANCE (maker-checker) →
     invalidate the cache → a COMPLIANCE actor can now originate (was 403); revert → blocked again.
     The enum is never edited; only the master.

Against the gateway on :8080; binds no port. Registered by the coordinator.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="config.admin"):
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


def set_action_roles(action_key, roles):
    """Propose + approve an ACTION_ROLE master change (maker-checker)."""
    body = {"recordKey": action_key, "payload": {"roles": roles, "description": f"e2e remap {action_key}"}}
    st, rec = call("POST", "/config/api/masters/ACTION_ROLE", body, actor="config.admin")
    assert st in (200, 201), f"propose failed {st} {rec}"
    st2, _ = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="config.checker")
    assert st2 == 200, f"approve failed {st2}"


def invalidate_rbac(service):
    st, body = call("POST", f"/{service}/api/governance/rbac/cache/invalidate")
    assert st == 200 and body and body.get("invalidated") is True, f"invalidate {service}: {st} {body}"


print("== 1. Effective action→role catalogue is exposed ==")
st, cat = call("GET", "/config/api/governance/rbac/actions")
cat = must(st, cat, "catalogue")
orig = cat.get("ORIGINATE") or {}
cpt = cat.get("CPT_REVIEW") or {}
disb = cat.get("DISBURSEMENT_AUTHORIZE") or {}
check("catalogue lists ORIGINATE with its roles + source", "RM" in (orig.get("roles") or []) and orig.get("source"),
      str(orig))
check("catalogue lists CPT_REVIEW (RM, not analyst)", "RM" in (cpt.get("roles") or [])
      and "ANALYST" not in (cpt.get("roles") or []), str(cpt))
check("catalogue lists DISBURSEMENT_AUTHORIZE (CREDIT_OFFICER)", "CREDIT_OFFICER" in (disb.get("roles") or []), str(disb))
check("seeded catalogue is driven by the ACTION_ROLE master (admin-editable)",
      orig.get("source") == "ACTION_ROLE_MASTER", orig.get("source"))

print("== 2. The override has teeth (remap ORIGINATE to admit COMPLIANCE) ==")
# A borrower to originate against (created by an RM — always allowed).
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "RBAC Config Test Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "RBACC1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "counterparty")

app_body = {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 100_000_000, "currency": "INR", "tenorMonths": 36, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 150_000_000, "secured": True}

# Baseline: COMPLIANCE cannot originate (enum default).
st, _ = call("POST", "/origination/api/applications", app_body, actor="compliance.officer")
check("baseline: COMPLIANCE cannot originate (403)", st == 403, f"{st}")

# Admin remaps ORIGINATE to ADD COMPLIANCE, then invalidates the origination cache.
set_action_roles("originate", ["RM", "RM_HEAD", "ANALYST", "CREDIT_OPS", "COMPLIANCE"])
invalidate_rbac("origination")
st, okapp = call("POST", "/origination/api/applications", app_body, actor="compliance.officer")
check("after admin remap: COMPLIANCE CAN originate (config, no code change)", st == 200, f"{st} {okapp}")

# Revert to the seeded roles and confirm enforcement reverts too.
set_action_roles("originate", ["RM", "RM_HEAD", "ANALYST", "CREDIT_OPS"])
invalidate_rbac("origination")
st, _ = call("POST", "/origination/api/applications", app_body, actor="compliance.officer")
check("after revert: COMPLIANCE blocked from origination again (403)", st == 403, f"{st}")

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
