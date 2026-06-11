#!/usr/bin/env python3
"""
AI governance switch — e2e assertion.

Exercises the AI_GOVERNANCE master + AiGovernanceClient enforcement:
  1. Default state: AI capabilities are enabled; an AI endpoint succeeds.
  2. Disable a capability (default record): the same endpoint returns 403; the
     deterministic lifecycle (spread / rate / capital / pricing) still works.
  3. Re-enable: the endpoint succeeds again.
  4. Jurisdiction override: switch off the capability for a specific jurisdiction
     only; an application in that jurisdiction is blocked; an application in
     another jurisdiction (using the default) still works.
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="test.user"):
    url = GW + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
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


def set_capability(jurisdiction, key, enabled, actor="config.admin"):
    """Propose + approve an AI_GOVERNANCE master change (maker-checker)."""
    body = {
        "recordKey": key, "jurisdiction": jurisdiction,
        "payload": {"enabled": enabled, "description": f"toggled by e2e governance test"},
    }
    st, rec = call("POST", "/config/api/masters/AI_GOVERNANCE", body, actor=actor)
    assert st in (200, 201), f"propose failed {st} {rec}"
    # checker (must differ from maker for SoD)
    st2, _ = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="config.checker")
    assert st2 == 200, f"approve failed {st2}"
    # Cache TTL in AiGovernanceClient is 60s — invalidate by waiting or using fresh
    # capabilities. In the test we accept a small delay if needed; the in-process
    # cache invalidate hook would be a follow-up. For our purposes the seed is on
    # the default record (we always re-issue PROPOSE which makes a new ACTIVE row),
    # but the client caches by (cap, jur). Wait a moment for safety.


print("== 1. Capability catalogue + default state ==")
st, caps = call("GET", "/config/api/governance/ai/capabilities")
check("catalogue exposes capabilities", st == 200 and len(caps) >= 5, f"{st}")
st, res = call("GET", "/config/api/governance/ai/resolved")
caps_map = res["capabilities"] if isinstance(res, dict) else {}
check("default DOC_INTEL enabled", caps_map.get("doc-intel", {}).get("enabled") is True, str(caps_map.get("doc-intel")))

print("\n== 2. Seed a minimal deal we can run AI against ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Atlas Industries Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "L99000MH2001PLC900020",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
assert st == 200, cp
cp_id, cp_ref = cp["id"], cp["reference"]
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp_id, "counterpartyRef": cp_ref, "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 600_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Expansion",
    "collateralType": "PROPERTY", "collateralValue": 500_000_000, "secured": True}, actor="rm.user")
assert st == 200, app
ref = app["reference"]

# An AI surface that triggers a deterministic computation first: collateral-intel
# requires the application; if AI is on it returns an extraction.
def call_collateral_extract():
    return call("POST", f"/origination/api/collateral-intel/{ref}/extract",
                {"documentKind": "VALUATION_REPORT", "text": "Property value INR 50,00,00,000 — valuer M/s Knight."},
                actor="analyst.user")

print("\n== 3. AI capability enabled -> endpoint succeeds ==")
st, _ = call_collateral_extract()
check("collateral-intel works when enabled", st == 200, f"{st}")

print("\n== 4. Disable COLLATERAL_INTEL globally -> 403; deterministic path still works ==")
set_capability(None, "collateral-intel", False)
# A clean way to bust the in-process cache: the test waits briefly. The TTL is
# 60s; for a tighter loop a /governance/ai/invalidate endpoint would help.
time.sleep(6)  # > default cache TTL (5s)
st, body = call_collateral_extract()
check("collateral-intel blocked with 403 when disabled", st == 403, f"{st} {body}")
check("403 message names the capability", "collateral-intel" in str(body), str(body))

# Deterministic lifecycle still works while AI is off:
st, _ = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": {"value": 4e9, "sourceDocument": "manual", "confidence": 1.0},
        "COGS": {"value": 2.6e9, "sourceDocument": "manual", "confidence": 1.0},
        "OPERATING_EXPENSES": {"value": 0.6e9, "sourceDocument": "manual", "confidence": 1.0},
        "DEPRECIATION": {"value": 0.15e9, "sourceDocument": "manual", "confidence": 1.0},
        "INTEREST_EXPENSE": {"value": 0.12e9, "sourceDocument": "manual", "confidence": 1.0},
        "TAX": {"value": 0.1e9, "sourceDocument": "manual", "confidence": 1.0},
        "TOTAL_ASSETS": {"value": 5e9, "sourceDocument": "manual", "confidence": 1.0},
        "CURRENT_ASSETS": {"value": 2e9, "sourceDocument": "manual", "confidence": 1.0},
        "CASH": {"value": 0.5e9, "sourceDocument": "manual", "confidence": 1.0},
        "CURRENT_LIABILITIES": {"value": 1.3e9, "sourceDocument": "manual", "confidence": 1.0},
        "SHORT_TERM_DEBT": {"value": 0.4e9, "sourceDocument": "manual", "confidence": 1.0},
        "LONG_TERM_DEBT": {"value": 1.0e9, "sourceDocument": "manual", "confidence": 1.0},
        "CURRENT_PORTION_LTD": {"value": 0.15e9, "sourceDocument": "manual", "confidence": 1.0},
        "NET_WORTH": {"value": 2.3e9, "sourceDocument": "manual", "confidence": 1.0},
        "CFO": {"value": 0.55e9, "sourceDocument": "manual", "confidence": 1.0}}}]},
    actor="analyst.user")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
st_rate, _ = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
check("deterministic rating works while AI off", st_rate == 200, f"{st_rate}")

print("\n== 5. Re-enable -> endpoint succeeds again ==")
set_capability(None, "collateral-intel", True)
time.sleep(6)  # > default cache TTL (5s)
st, _ = call_collateral_extract()
check("collateral-intel works after re-enable", st == 200, f"{st}")

print("\n== 6. Jurisdiction override: block only for AE-CBUAE, leave RBI on ==")
# Disable doc-intel for AE-CBUAE only. The IN-RBI application above should still
# be able to use doc-intel because the default for IN-RBI says enabled.
set_capability("AE-CBUAE", "collateral-intel", False)
time.sleep(6)  # > default cache TTL (5s)
st, _ = call_collateral_extract()
check("IN-RBI deal unaffected by AE-CBUAE override", st == 200, f"{st}")

# Now create an AE-CBUAE application and confirm it IS blocked.
st, cp2 = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Emirates Cement LLC", "legalForm": "LLC", "registrationNo": "AE-CR-9999",
    "jurisdiction": "AE-CBUAE", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "AE",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
st, app2 = call("POST", "/origination/api/applications", {
    "counterpartyId": cp2["id"], "counterpartyRef": cp2["reference"], "counterpartyName": cp2["legalName"],
    "jurisdiction": "AE-CBUAE", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 200_000_000, "currency": "AED", "tenorMonths": 36, "purpose": "Plant",
    "collateralType": "PROPERTY", "collateralValue": 250_000_000, "secured": True}, actor="rm.user")
ref2 = app2["reference"]
st, body = call("POST", f"/origination/api/collateral-intel/{ref2}/extract",
                {"documentKind": "VALUATION_REPORT", "text": "Property value AED 1,50,00,000 valuer."},
                actor="analyst.user")
check("AE-CBUAE deal blocked by jurisdiction override", st == 403, f"{st} {body}")

# clean up: turn AE-CBUAE override back on so the e2e_smoke run isn't affected
set_capability("AE-CBUAE", "collateral-intel", True)

print("\n== 7. Newly-wired gates: every remaining AI capability is enforced ==")
# The deal from section 2 ('Atlas Industries Ltd') is fully rated/capitalised/priced,
# so each gate below has the upstream state it needs.

# 7a. PRICING_EXCEPTION — proposing a concession requires the deal to be priced;
# section 4 only rated it, so compute capital + pricing explicitly here.
call("POST", f"/risk/api/risk/{ref}/capital", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/pricing", actor="analyst.user")

set_capability(None, "pricing-exception", False)
time.sleep(6)
st, body = call("POST", f"/risk/api/risk/{ref}/pricing/exception",
                {"proposedRate": 0.08, "reason": "strategic client"}, actor="rm.user")
check("pricing-exception blocked when disabled", st == 403, f"{st} {body}")
set_capability(None, "pricing-exception", True)
time.sleep(6)

# 7b. COVENANT_INTEL — extraction from CP free text.
set_capability(None, "covenant-intel", False)
time.sleep(6)
st, body = call("POST", f"/decision/api/covenants/intel/{ref}/extract",
                {"text": "Maintain DSCR >= 1.25 throughout."}, actor="analyst.user")
check("covenant-intel blocked when disabled", st == 403, f"{st} {body}")
set_capability(None, "covenant-intel", True)
time.sleep(6)

# 7c. CPT — generated on the counterparty, not a deal.
set_capability(None, "cpt", False)
time.sleep(6)
st, body = call("POST", f"/decision/api/cpt/{cp_ref}/generate", {}, actor="rm.user")
check("cpt blocked when disabled", st == 403, f"{st} {body}")
set_capability(None, "cpt", True)
time.sleep(6)

# 7d. GROUP_SUGGEST — counterparty-service.
set_capability(None, "group-suggest", False)
time.sleep(6)
st, body = call("POST", f"/counterparty/api/initiation/counterparties/{cp_id}/group/suggest",
                {}, actor="rm.user")
check("group-suggest blocked when disabled", st == 403, f"{st} {body}")
set_capability(None, "group-suggest", True)
time.sleep(6)

# 7e. COPILOT — copilot-service.
set_capability(None, "copilot", False)
time.sleep(6)
st, body = call("POST", "/copilot/api/copilot/ask",
                {"persona": "rm.user", "question": "what is my book exposure?"}, actor="rm.user")
check("copilot blocked when disabled", st == 403, f"{st} {body}")
set_capability(None, "copilot", True)
time.sleep(6)

print(f"\n== AI governance e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
