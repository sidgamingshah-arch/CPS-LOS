#!/usr/bin/env python3
"""
Configurable, parameter-routed SCORING (score) APPROVAL — driven through the gateway.

The rating (score) approval is no longer a flat CREDIT_OFFICER rank check. A bank configures,
in the SCORING_APPROVAL_POLICY master, WHICH scores need approval and BY WHOM across parameters
(exposure, grade, score-band, override magnitude, segment, jurisdiction). The risk-service
evaluates the ordered, first-match-wins rules against each scored rating, persists
{approvalRequired, requiredAuthority, approvalStatus}, raises a routed RATING_APPROVAL work-item,
and gates confirmation on the resolved authority — while the authoritative grade / PD never move.

Asserts:
  1. Policy resolves; an ordinary deal -> default CREDIT_OFFICER, confirm by credit.officer works
     (behaviour-preserving — exactly today's flat gate).
  2. A large-exposure score -> approvalRequired=true, requiredAuthority CREDIT_COMMITTEE,
     approvalStatus PENDING_APPROVAL, and a RATING_APPROVAL work-item exists.
  3. Confirm by CREDIT_OFFICER on a committee-required score -> 403 (forbiddenAutonomy); confirm by
     the required authority -> APPROVED + task completed + the credit decision can now route.
  4. Deep-override (>=2 notches) -> CRO; SoD — the actor who overrode cannot confirm; a distinct
     required authority confirms.
  5. Re-authoring the policy (raise the exposure threshold) moves an approval from committee back
     to CREDIT_OFFICER (config-over-code); then restore so the suite is idempotent.
  6. Governance: the authoritative finalGrade / PD are byte-identical before vs after the approval
     routing (approval is a GATE, not a figure change).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GW", "http://localhost:8080")
PASS, FAIL = 0, 0

# AAA..D master ladder (best -> worst), mirrored from MasterScale.
SCALE = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]


def call(method, path, body=None, actor="test.user"):
    url = GW + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        return e.code, (json.loads(txt) if txt else None)


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
    return {"value": v, "sourceDocument": "sap.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.96}


def healthy_period(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def new_deal(suffix, amount):
    """cp -> app -> spread -> confirm-spread. Healthy financials -> investment grade. Returns app ref."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Scoring {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"SCORE{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capacity expansion",
        "collateralType": "PROPERTY", "collateralValue": amount * 0.8, "secured": True}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    # Scale the spread with the facility so leverage/DSCR stay healthy regardless of ticket size.
    s = max(1.0, amount / 8e8)
    must(*call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        healthy_period("FY2024", 5e9 * s, 3.0e9 * s, 0.8e9 * s, 0.12e9 * s, 6e9 * s, 2.6e9 * s,
                       0.7e9 * s, 1.4e9 * s, 0.45e9 * s, 1.1e9 * s, 3.0e9 * s, 0.9e9 * s),
        healthy_period("FY2023", 4.5e9 * s, 2.8e9 * s, 0.78e9 * s, 0.13e9 * s, 5.6e9 * s, 2.4e9 * s,
                       0.6e9 * s, 1.4e9 * s, 0.5e9 * s, 1.15e9 * s, 2.7e9 * s, 0.8e9 * s)]},
        actor="analyst.user")[:2], "spread")
    must(*call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")[:2], "confirm-spread")
    return ref


def scoring_approval(ref):
    st, sa = call("GET", f"/risk/api/risk/{ref}/scoring-approval")
    return must(st, sa, "scoring-approval")


def rating_tasks(ref):
    st, tasks = call("GET", f"/workflow/api/tasks/subject?type=Rating&ref={ref}")
    return tasks if (st == 200 and isinstance(tasks, list)) else []


def reauthor_policy(rules):
    """Maker submits a new SCORING_APPROVAL_POLICY:default version; a distinct checker approves."""
    st, rec = call("POST", "/config/api/masters/SCORING_APPROVAL_POLICY",
                   {"recordKey": "default", "payload": {"rules": rules}}, actor="policy.maker")
    rec = must(st, rec, "policy submit")
    st, ap = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="policy.checker")
    must(st, ap, "policy approve")


ORIGINAL_RULES = [
    {"id": "large-exposure", "when": {"exposureGte": 10000000000}, "requireApproval": True, "approverAuthority": "CREDIT_COMMITTEE"},
    {"id": "deep-override", "when": {"overrideNotchesGte": 2}, "requireApproval": True, "approverAuthority": "CRO"},
    {"id": "sub-investment", "when": {"gradeWorseThan": "BB"}, "requireApproval": True, "approverAuthority": "CREDIT_COMMITTEE"},
    {"id": "default", "when": {}, "requireApproval": True, "approverAuthority": "CREDIT_OFFICER"},
]

# TTL of the risk-service policy cache is 5s (helix.scoring-approval.cache-ttl-seconds); wait it out.
CACHE_TTL_WAIT = 7


print("== 0. Policy resolves from the SCORING_APPROVAL_POLICY master ==")
st, recs = call("GET", "/config/api/masters/SCORING_APPROVAL_POLICY")
check("SCORING_APPROVAL_POLICY seeded", st == 200 and isinstance(recs, list) and len(recs) >= 1, f"{st} {recs}")
default_rec = next((r for r in (recs or []) if r.get("recordKey") == "default"), None)
rules = (default_rec or {}).get("payload", {}).get("rules", [])
check("default policy carries 4 ordered rules (large-exposure, deep-override, sub-investment, default)",
      len(rules) == 4 and [r.get("id") for r in rules] ==
      ["large-exposure", "deep-override", "sub-investment", "default"], str([r.get("id") for r in rules]))


print("== 1. Ordinary deal -> default CREDIT_OFFICER (behaviour-preserving flat gate) ==")
d1 = new_deal("ORD", 800_000_000)
st, r1 = call("POST", f"/risk/api/risk/{d1}/rate", actor="analyst.user")
r1 = must(st, r1, "rate d1")
check("ordinary deal is investment grade", r1["modelGrade"] in ("AAA", "AA", "A", "BBB"), r1.get("modelGrade"))
sa1 = scoring_approval(d1)
check("ordinary deal: approvalRequired=true", sa1["approvalRequired"] is True, str(sa1))
check("ordinary deal: routed to CREDIT_OFFICER (default rule)", sa1["requiredAuthority"] == "CREDIT_OFFICER", str(sa1))
check("ordinary deal: PENDING_APPROVAL before confirm", sa1["approvalStatus"] == "PENDING_APPROVAL", str(sa1))
# Behaviour-preserving: a sub-CREDIT_OFFICER actor is still blocked (unchanged from the old flat gate).
st, _ = call("POST", f"/risk/api/risk/{d1}/rating/confirm", actor="analyst.user")
check("ordinary deal: analyst (sub-officer) still 403", st == 403, f"{st}")
st, cf1 = call("POST", f"/risk/api/risk/{d1}/rating/confirm", actor="credit.officer")
check("ordinary deal: credit.officer confirms (200)", st == 200 and cf1["confirmed"], f"{st} {cf1}")
sa1b = scoring_approval(d1)
check("ordinary deal: APPROVED after confirm", sa1b["approvalStatus"] == "APPROVED", str(sa1b))


print("== 2. Large-exposure score -> CREDIT_COMMITTEE + routed RATING_APPROVAL work-item ==")
d2 = new_deal("BIG", 15_000_000_000)   # EAD 15bn >= 10bn large-exposure threshold
st, r2 = call("POST", f"/risk/api/risk/{d2}/rate", actor="analyst.user")
r2 = must(st, r2, "rate d2")
# Capture the authoritative figures right after scoring (before any approval action) — test 6.
before = {"finalGrade": r2["finalGrade"], "pd": r2["pd"], "lgd": r2["lgd"], "ead": r2["ead"], "modelGrade": r2["modelGrade"]}
sa2 = scoring_approval(d2)
check("large-exposure: approvalRequired=true", sa2["approvalRequired"] is True, str(sa2))
check("large-exposure: routed to CREDIT_COMMITTEE (higher than default)",
      sa2["requiredAuthority"] == "CREDIT_COMMITTEE", str(sa2))
check("large-exposure: PENDING_APPROVAL", sa2["approvalStatus"] == "PENDING_APPROVAL", str(sa2))
tasks2 = rating_tasks(d2)
ra_task = next((t for t in tasks2 if t.get("taskType") == "RATING_APPROVAL"), None)
check("large-exposure: a RATING_APPROVAL work-item exists", ra_task is not None, str(tasks2))
check("work-item routed to the committee queue",
      ra_task is not None and ra_task.get("queueKey") == "RATING_APPROVAL_CREDIT_COMMITTEE", str(ra_task))


print("== 3. Sub-authority confirm 403; required authority approves; decision can then route ==")
# Before approval the credit decision cannot route (rating not yet confirmed).
st, _ = call("POST", f"/decision/api/decisions/{d2}/route", actor="credit.ops")
check("committee-required score: credit decision cannot route before approval (409)", st == 409, f"{st}")
# A CREDIT_OFFICER (rank 2) cannot approve a CREDIT_COMMITTEE-required score.
st, _ = call("POST", f"/risk/api/risk/{d2}/rating/confirm", actor="credit.officer")
check("committee-required score: credit.officer confirm -> 403 (forbiddenAutonomy)", st == 403, f"{st}")
# The routed authority approves.
st, cf2 = call("POST", f"/risk/api/risk/{d2}/rating/confirm", actor="credit.committee")
check("committee-required score: credit.committee confirms (200)", st == 200 and cf2["confirmed"], f"{st} {cf2}")
sa2b = scoring_approval(d2)
check("committee-required score: APPROVED after confirm", sa2b["approvalStatus"] == "APPROVED", str(sa2b))
tasks2b = rating_tasks(d2)
ra_task2 = next((t for t in tasks2b if t.get("taskType") == "RATING_APPROVAL"), None)
check("RATING_APPROVAL work-item completed on approval",
      ra_task2 is not None and ra_task2.get("status") == "COMPLETED", str(ra_task2))
# Now the credit decision can route.
st, dec = call("POST", f"/decision/api/decisions/{d2}/route", actor="credit.ops")
check("credit decision can route after approval (200)", st == 200 and "requiredAuthority" in (dec or {}), f"{st} {dec}")


print("== 4. Deep-override (>=2 notches) -> CRO; SoD (overrider cannot confirm) ==")
d3 = new_deal("OVR", 800_000_000)
st, r3 = call("POST", f"/risk/api/risk/{d3}/rate", actor="analyst.user")
r3 = must(st, r3, "rate d3")
model_grade = r3["modelGrade"]
worse2 = SCALE[min(SCALE.index(model_grade) + 2, len(SCALE) - 1)]
st, ov = call("POST", f"/risk/api/risk/{d3}/rating/override",
              {"proposedGrade": worse2, "reasonCode": "SECTOR_OUTLOOK", "role": "CREDIT_COMMITTEE",
               "note": "2-notch governed downgrade"}, actor="credit.committee")
ov = must(st, ov, "override d3")
check("2-notch override applied", abs(ov["overrideNotches"]) >= 2 and ov["finalGrade"] == worse2, str(ov))
sa3 = scoring_approval(d3)
check("deep-override: routed to CRO", sa3["requiredAuthority"] == "CRO", str(sa3))
check("deep-override: PENDING_APPROVAL", sa3["approvalStatus"] == "PENDING_APPROVAL", str(sa3))
# SoD: the actor who overrode (credit.committee) cannot confirm their own score.
st, _ = call("POST", f"/risk/api/risk/{d3}/rating/confirm", actor="credit.committee")
check("SoD: overrider (credit.committee) cannot confirm their own override (403)", st == 403, f"{st}")
# Authority: a CREDIT_OFFICER cannot approve a CRO-required score.
st, _ = call("POST", f"/risk/api/risk/{d3}/rating/confirm", actor="credit.officer")
check("deep-override: credit.officer confirm -> 403 (sub-authority)", st == 403, f"{st}")
# A distinct actor holding CRO approves.
st, cf3 = call("POST", f"/risk/api/risk/{d3}/rating/confirm", actor="cro")
check("deep-override: cro confirms (200)", st == 200 and cf3["confirmed"], f"{st} {cf3}")
sa3b = scoring_approval(d3)
check("deep-override: APPROVED after cro confirm", sa3b["approvalStatus"] == "APPROVED", str(sa3b))


print("== 5. Re-authoring the policy moves an approval from committee back to CREDIT_OFFICER ==")
d4 = new_deal("REAU", 20_000_000_000)   # 20bn -> large-exposure under the original policy
st, r4 = call("POST", f"/risk/api/risk/{d4}/rate", actor="analyst.user")
must(st, r4, "rate d4")
sa4 = scoring_approval(d4)
check("re-author: 20bn score routes to CREDIT_COMMITTEE under the original policy",
      sa4["requiredAuthority"] == "CREDIT_COMMITTEE", str(sa4))
# Raise the large-exposure threshold to 1e12 so a 20bn deal no longer trips it.
raised = [dict(r) for r in ORIGINAL_RULES]
raised[0] = {"id": "large-exposure", "when": {"exposureGte": 1000000000000},
             "requireApproval": True, "approverAuthority": "CREDIT_COMMITTEE"}
reauthor_policy(raised)
time.sleep(CACHE_TTL_WAIT)   # wait out the risk-service policy TTL cache
st, r4b = call("POST", f"/risk/api/risk/{d4}/rate", actor="analyst.user")
must(st, r4b, "re-rate d4")
sa4b = scoring_approval(d4)
check("re-author: after raising the threshold the same 20bn score falls to the default CREDIT_OFFICER",
      sa4b["requiredAuthority"] == "CREDIT_OFFICER", str(sa4b))
# Restore the original policy so the suite is idempotent across re-runs.
reauthor_policy(ORIGINAL_RULES)
time.sleep(CACHE_TTL_WAIT)
st, r4c = call("POST", f"/risk/api/risk/{d4}/rate", actor="analyst.user")
must(st, r4c, "re-rate d4 restored")
sa4c = scoring_approval(d4)
check("re-author: original policy restored (20bn score routes to CREDIT_COMMITTEE again)",
      sa4c["requiredAuthority"] == "CREDIT_COMMITTEE", str(sa4c))


print("== 6. Governance: approval is a GATE, not a figure change (finalGrade/PD unchanged) ==")
st, after = call("GET", f"/risk/api/risk/{d2}/rating")
after = must(st, after, "d2 rating after approval")
check("large-exposure: finalGrade byte-identical before vs after the approval routing",
      after["finalGrade"] == before["finalGrade"], f"{before['finalGrade']} vs {after.get('finalGrade')}")
check("large-exposure: PD byte-identical before vs after", after["pd"] == before["pd"],
      f"{before['pd']} vs {after.get('pd')}")
check("large-exposure: LGD/EAD byte-identical before vs after",
      after["lgd"] == before["lgd"] and after["ead"] == before["ead"],
      f"lgd {before['lgd']}/{after.get('lgd')} ead {before['ead']}/{after.get('ead')}")
check("large-exposure: model grade unchanged by routing", after["modelGrade"] == before["modelGrade"],
      f"{before['modelGrade']} vs {after.get('modelGrade')}")


print(f"\n== e2e scoring approval: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
