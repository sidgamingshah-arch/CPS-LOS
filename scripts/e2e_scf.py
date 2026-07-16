#!/usr/bin/env python3
"""
Supply-Chain Finance (SCF) product paper — e2e through the gateway (:8080).

An anchor-backed VENDOR/DEALER programme is drafted; spokes (suppliers/distributors)
are judged DETERMINISTICALLY against the pinned SCF_ELIGIBILITY snapshot + per-spoke
cap (PASS/FAIL + reasons); the programme is submitted (raising a linked PRODUCT_PAPER
noting in decision-service); and approved under segregation of duties + credit authority.
On approval the programme limit is registered into limit-service's OWN governed limit
tree (best-effort — SCF never writes an authoritative figure itself).

Asserts:
  0. Seed the SCF_ELIGIBILITY master (generic master API — maker != checker for SoD).
  1. create programme -> DRAFT with an SCF- reference; eligibility snapshot pinned.
  2. add spoke within bounds -> PASS (deterministic); over the per-spoke cap -> FAIL + reasons.
  3. submit -> PENDING_APPROVAL AND a linked PRODUCT_PAPER noting is created in
     decision-service (notingRef stored; the noting is retrievable + subject-linked).
  4. approve by the RAISER -> 403 (segregation of duties).
  5. approve by a NON-authority actor -> 403 (credit-authority gate).
  6. approve by a credit authority -> APPROVED; limit registration is best-effort.
  7. reject path: SoD (raiser -> 403) then a credit authority rejects -> REJECTED.
  8. withdraw is raiser-only (non-raiser -> 403; raiser -> WITHDRAWN).
  9. every SCF_* audit event is stamped HUMAN (no AI/SYSTEM in the SCF write path).
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


# ---- SCF_ELIGIBILITY master seeding (config-as-data; maker != checker for SoD) ----
def seed_eligibility(key, payload):
    st, rec = call("POST", "/config/api/masters/SCF_ELIGIBILITY",
                   {"recordKey": key, "payload": payload}, actor="master.maker")
    rec = must(st, rec, f"submit SCF_ELIGIBILITY {key}")
    st, _ = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="master.checker")
    must(st, _, f"approve SCF_ELIGIBILITY {key}")


print("== 0. Seed the SCF_ELIGIBILITY master (generic master API — NO code change) ==")
seed_eligibility("DEFAULT", {
    "minSpokeAmount": 1_000_000,
    "maxSpokeAmount": 50_000_000,
    "maxSpokePctOfProgram": 30,
    "allowedProgramTypes": ["VENDOR", "DEALER"],
})
print("    SCF_ELIGIBILITY/DEFAULT ACTIVE")


print("\n== 1. Create the anchor + a DRAFT SCF programme ==")
st, anchor = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Meridian Steel Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "SCF-AN-1",
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
anchor = must(st, anchor, "anchor counterparty")
anchor_ref = anchor["reference"]

st, v = call("POST", "/origination/api/scf/programs", {
    "anchorRef": anchor_ref, "anchorName": "Meridian Steel Ltd", "programType": "VENDOR",
    "programLimit": 500_000_000, "perSpokeCap": 40_000_000, "currency": "INR"}, actor="rm.user")
v = must(st, v, "create programme")
scf_ref = v["program"]["scfRef"]
check("programme created DRAFT with SCF- ref", v["program"]["status"] == "DRAFT"
      and scf_ref.startswith("SCF-"), str(v["program"]))
check("eligibility criteria pinned from the master snapshot",
      v["program"]["eligibilitySnapshot"].get("maxSpokePctOfProgram") == 30
      and v["program"]["eligibilitySnapshot"].get("maxSpokeAmount") == 50_000_000,
      str(v["program"]["eligibilitySnapshot"]))

# guard: programme limit must be positive
st, b = call("POST", "/origination/api/scf/programs", {
    "anchorRef": anchor_ref, "programType": "VENDOR", "programLimit": 0}, actor="rm.user")
check("programme with non-positive limit -> 400", st == 400, f"{st} {b}")
# guard: unknown programme type
st, b = call("POST", "/origination/api/scf/programs", {
    "anchorRef": anchor_ref, "programType": "FACTORING", "programLimit": 1_000_000}, actor="rm.user")
check("unknown programme type -> 400", st == 400, f"{st} {b}")


print("\n== 2. Add spokes — deterministic eligibility (PASS within bounds, FAIL over the cap) ==")
st, _ = call("POST", f"/origination/api/scf/programs/{scf_ref}/spokes", {
    "spokeRef": "CP-SPOKE-A", "spokeName": "Anvil Components", "requestedAmount": 20_000_000}, actor="rm.user")
must(st, _, "add spoke A")
st, v = call("POST", f"/origination/api/scf/programs/{scf_ref}/spokes", {
    "spokeRef": "CP-SPOKE-B", "spokeName": "Cobalt Castings", "requestedAmount": 45_000_000}, actor="rm.user")
v = must(st, v, "add spoke B")
spokes = {s["spokeRef"]: s for s in v["spokes"]}
check("spoke within bounds -> PASS with approved cap = requested",
      spokes["CP-SPOKE-A"]["eligibilityResult"] == "PASS"
      and spokes["CP-SPOKE-A"]["approvedCap"] == 20_000_000, str(spokes.get("CP-SPOKE-A")))
check("spoke over the per-spoke cap -> FAIL with reasons + zero approved cap",
      spokes["CP-SPOKE-B"]["eligibilityResult"] == "FAIL"
      and len(spokes["CP-SPOKE-B"]["reasons"]) >= 1
      and spokes["CP-SPOKE-B"]["approvedCap"] == 0, str(spokes.get("CP-SPOKE-B")))
check("roll-ups: 2 spokes, 1 eligible", v["spokeCount"] == 2 and v["eligibleCount"] == 1, str(v))


print("\n== 3. Submit -> PENDING_APPROVAL + a linked PRODUCT_PAPER noting is created ==")
st, v = call("POST", f"/origination/api/scf/programs/{scf_ref}/submit", actor="rm.user")
v = must(st, v, "submit programme")
noting_ref = v["program"].get("notingRef")
check("submit -> PENDING_APPROVAL", v["program"]["status"] == "PENDING_APPROVAL", str(v["program"]))
check("a PRODUCT_PAPER noting was created + linked (notingRef stored)",
      bool(noting_ref) and noting_ref.startswith("NTG-"), f"notingRef={noting_ref}")
if noting_ref:
    st, n = call("GET", f"/decision/api/notings/{noting_ref}")
    n = must(st, n, "fetch linked noting")
    check("linked noting is PRODUCT_PAPER + subject-linked to the SCF programme",
          n["notingType"] == "PRODUCT_PAPER" and n["subjectRef"] == scf_ref, str(n))


print("\n== 4-6. Approval: SoD + credit-authority gate, then a real approval ==")
st, b = call("POST", f"/origination/api/scf/programs/{scf_ref}/approve", {}, actor="rm.user")
check("approve by the RAISER -> 403 (segregation of duties)", st == 403, f"{st} {b}")
st, b = call("POST", f"/origination/api/scf/programs/{scf_ref}/approve", {}, actor="analyst.user")
check("approve by a NON-authority actor -> 403 (credit-authority gate)", st == 403, f"{st} {b}")
st, v = call("POST", f"/origination/api/scf/programs/{scf_ref}/approve",
             {"note": "credit committee ok"}, actor="credit.head")
v = must(st, v, "approve programme")
check("approve by a credit authority -> APPROVED", v["program"]["status"] == "APPROVED"
      and v["program"]["decidedBy"] == "credit.head", str(v["program"]))

# Best-effort limit registration: assert what is deterministic (approval stands regardless);
# if a limit node WAS registered, verify it is retrievable via limit-service.
limit_ref = v["program"].get("registeredLimitRef")
if limit_ref:
    st, node = call("GET", f"/limits/api/limits/line/{limit_ref}")
    check("best-effort limit node (when registered) is a real limit-service node",
          st == 200 and node is not None, f"{st} {node}")
    print(f"    programme limit registered as limit node {limit_ref}")
else:
    check("approval stands even when the limit hook did not register (best-effort)",
          v["program"]["status"] == "APPROVED", "approval must never block on the limit hook")
    print("    no limit node linked (best-effort hook — environment-dependent)")

# terminal guard: cannot re-approve an APPROVED programme
st, b = call("POST", f"/origination/api/scf/programs/{scf_ref}/approve", {}, actor="credit.head")
check("re-approve an APPROVED programme -> 409", st == 409, f"{st} {b}")


print("\n== 7. Reject path: SoD then a credit authority rejects ==")
st, v2 = call("POST", "/origination/api/scf/programs", {
    "anchorRef": anchor_ref, "programType": "DEALER", "programLimit": 300_000_000,
    "perSpokeCap": 30_000_000, "currency": "INR"}, actor="rm.user")
v2 = must(st, v2, "create programme 2")
scf2 = v2["program"]["scfRef"]
must(*call("POST", f"/origination/api/scf/programs/{scf2}/spokes",
           {"spokeRef": "CP-SPOKE-C", "requestedAmount": 10_000_000}, actor="rm.user"), "spoke C")
must(*call("POST", f"/origination/api/scf/programs/{scf2}/submit", actor="rm.user"), "submit 2")
st, b = call("POST", f"/origination/api/scf/programs/{scf2}/reject", {"note": "x"}, actor="rm.user")
check("reject by the RAISER -> 403 (SoD)", st == 403, f"{st} {b}")
st, rj = call("POST", f"/origination/api/scf/programs/{scf2}/reject",
              {"note": "anchor rating under review"}, actor="credit.head")
rj = must(st, rj, "reject 2")
check("reject by a credit authority -> REJECTED", rj["program"]["status"] == "REJECTED", str(rj["program"]))


print("\n== 8. Withdraw is raiser-only ==")
st, v3 = call("POST", "/origination/api/scf/programs", {
    "anchorRef": anchor_ref, "programType": "VENDOR", "programLimit": 100_000_000,
    "perSpokeCap": 10_000_000, "currency": "INR"}, actor="rm.user")
v3 = must(st, v3, "create programme 3")
scf3 = v3["program"]["scfRef"]
st, b = call("POST", f"/origination/api/scf/programs/{scf3}/withdraw", actor="credit.head")
check("withdraw by a non-raiser -> 403", st == 403, f"{st} {b}")
st, wd = call("POST", f"/origination/api/scf/programs/{scf3}/withdraw", actor="rm.user")
wd = must(st, wd, "withdraw 3")
check("withdraw by the raiser -> WITHDRAWN", wd["program"]["status"] == "WITHDRAWN", str(wd["program"]))


print("\n== 9. Governance: every SCF_* audit event is stamped HUMAN ==")
st, audit_rows = call("GET", "/origination/api/audit")
audit_rows = audit_rows or []
scf_events = [a for a in audit_rows if str(a.get("eventType", "")).startswith("SCF_")]
check("SCF_* audit events exist and are ALL HUMAN (no AI/SYSTEM in the SCF write path)",
      len(scf_events) >= 1 and all(a.get("actorType") == "HUMAN" for a in scf_events),
      str([a.get("eventType") for a in scf_events]))


print(f"\n== SCF e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
