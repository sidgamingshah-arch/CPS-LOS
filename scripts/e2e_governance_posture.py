#!/usr/bin/env python3
"""
Fail-closed governance posture toggle (G7, P2) — e2e.

The platform's authority resolution fails OPEN on a cold-start directory outage (humans
must be able to work; name-equality SoD still applies). A regulator-cautious bank can flip
the governed GOVERNANCE_POSTURE to FAIL-CLOSED so the outage DENIES instead. This proves
both postures deterministically by SIMULATING the ACTOR_ROLE outage (a test-only hook,
gated by helix.rbac.simulate-outage-enabled) while config-service stays up — so the posture
master itself reads fresh and the fail-closed decision is honoured.

Proves: posture observable; default fail-open (unknown actor allowed during the simulated
outage — the contrast to the healthy-directory 403); governed maker-checker flip to
fail-closed + cache-invalidate; the SAME call now 403s with an RBAC_POSTURE_DENY audit; and
a clean restore to the fail-open baseline. Behaviour-preserving: default posture unchanged.
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


def posture():
    st, p = call("GET", "/decision/api/governance/rbac/posture")
    return must(st, p, "posture")


def simulate(enabled):
    st, r = call("POST", "/decision/api/governance/rbac/_simulate-outage", {"enabled": enabled}, actor="ops.admin")
    return must(st, r, "simulate-outage")


def set_posture(fail_closed):
    """Governed maker-checker flip of GOVERNANCE_POSTURE.rbac + cache-invalidate on decision."""
    st, rec = call("POST", "/config/api/masters/GOVERNANCE_POSTURE",
                   {"recordKey": "rbac", "payload": {"failClosed": fail_closed, "description": "e2e posture flip"}},
                   actor="config.admin")
    rec = must(st, rec, "propose posture")
    must(*call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="config.checker"), "approve posture")
    must(*call("POST", "/decision/api/governance/rbac/cache/invalidate", actor="config.admin"), "invalidate")


def drawdown_request(actor):
    """A role-gated write: the unknown-actor probe. 403 (ACTOR_ROLE) under a healthy directory."""
    return call("POST", f"/decision/api/disbursement/{REF}/request",
                {"facilityRef": FREF, "amount": 25_000_000, "currency": "INR", "purpose": "posture probe"},
                actor=actor)


print("== 0. Setup: a deal with a facility + limit tree ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Posture Test Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "G7-1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 300_000_000, "currency": "INR", "tenorMonths": 48, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 350_000_000, "secured": True}, actor="rm.user")
REF = must(st, app, "app")["reference"]
st, facs = call("GET", f"/origination/api/applications/{REF}/facilities")
FREF = must(st, facs, "facilities")[0]["reference"]
call("POST", f"/limits/api/limits/build/{REF}", actor="credit.ops")
call("POST", f"/decision/api/cps/{REF}/seed", actor="credit.ops")


print("\n== 1. Posture is observable; default is FAIL-OPEN ==")
p0 = posture()
check("posture endpoint reports the effective posture on this service",
      "failClosed" in p0 and "simulateOutage" in p0 and p0.get("service"), str(p0))
check("default posture is FAIL-OPEN", p0["failClosed"] is False, str(p0))
check("no outage simulated at baseline", p0["simulateOutage"] is False, str(p0))


print("\n== 2. Simulate the ACTOR_ROLE directory outage (posture master stays readable) ==")
simulate(True)
p1 = posture()
check("outage simulated", p1["simulateOutage"] is True, str(p1))
check("posture still readable during the simulated ACTOR_ROLE outage (fail-open)",
      p1["failClosed"] is False, str(p1))


print("\n== 3. FAIL-OPEN: an unknown actor is ALLOWED during the outage ==")
st, d = drawdown_request("posture.ghost")
check("unknown actor's role-gated write succeeds under fail-open + outage", st == 200, f"{st} {d}")


print("\n== 4. Flip to FAIL-CLOSED (governed maker-checker) + observe ==")
set_posture(True)
p2 = posture()
check("posture flipped to FAIL-CLOSED via the master", p2["failClosed"] is True, str(p2))
check("posture source is the MASTER (governed)", p2["source"] == "MASTER", str(p2))


print("\n== 5. FAIL-CLOSED: the SAME call now 403s on the outage ==")
st, body = drawdown_request("posture.ghost")
check("unknown actor denied under fail-closed + outage (403)", st == 403, f"{st} {body}")
check("403 explains the fail-closed posture", "FAIL-CLOSED" in str(body).upper() or "POSTURE" in str(body).upper(),
      str(body)[:200])


print("\n== 6. The posture deny is audit-stamped (examiner trail) ==")
st, trail = call("GET", "/decision/api/audit")
trail = trail if isinstance(trail, list) else []
deny = [e for e in trail if e.get("eventType") == "RBAC_POSTURE_DENY"]
check("an RBAC_POSTURE_DENY audit event was written", len(deny) >= 1, f"{len(deny)} events")
check("deny event records the FAIL_CLOSED posture + outage reason + SYSTEM actor",
      any((e.get("detail") or {}).get("posture") == "FAIL_CLOSED"
          and (e.get("detail") or {}).get("reason") == "DIRECTORY_OUTAGE"
          and e.get("actorType") == "SYSTEM" for e in deny),
      str([e.get("detail") for e in deny[:2]]))


print("\n== 7. Restore: fail-open baseline, outage cleared ==")
simulate(False)
set_posture(False)
p3 = posture()
check("posture restored to FAIL-OPEN", p3["failClosed"] is False, str(p3))
check("outage simulation cleared", p3["simulateOutage"] is False, str(p3))
# With a HEALTHY directory again, the unknown actor is denied for the ORDINARY reason (ACTOR_ROLE),
# not the posture — proving we are back on the e2e_rbac baseline, not stuck fail-closed.
st, body = drawdown_request("posture.ghost")
check("healthy directory denies the unknown actor for the ordinary ACTOR_ROLE reason (403)",
      st == 403 and "ACTOR_ROLE" in str(body), f"{st} {str(body)[:160]}")


print(f"\n== governance posture (G7) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
