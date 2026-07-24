#!/usr/bin/env python3
"""
Admin-configurable rating-override notch limits — e2e (batch4/rating-override).

The per-role override-notch ceiling used by risk-service is NO LONGER hardcoded: it is resolved
at runtime from the OVERRIDE_ROLE CODE_VALUE master (config-service), where each role's `score`
IS its notch limit. The built-in map (ANALYST=1, CREDIT_OFFICER=2, CREDIT_COMMITTEE=99, CRO=99)
survives only as a conservative fallback when config-service is unreachable — byte-identical to
the seeded master, so notch behaviour is preserved during an outage.

This suite proves four things:

  A. ENFORCED-FROM-MASTER — with the seeded OVERRIDE_ROLE (ANALYST notch = 1), an ANALYST
     1-notch override is accepted, a 2-notch ANALYST override is 403, and a CRO large override
     (notch 99) is accepted. The limit is resolved from the AUTHENTICATED actor's ACTOR_ROLE role.

  B. CONFIG-DRIVES-THE-LIMIT — raising ANALYST's `score` to 3 in the master (maker-checker)
     makes a 2-notch ANALYST override succeed; restoring it to 1 makes the SAME 2-notch override
     403 again. Editing the master moves the enforced limit, no code change.

  C. BODY-ROLE-IS-NOT-A-GRANT — an ANALYST that self-attests role="CRO" in the request body is
     STILL notch-capped (403); a genuine CRO with a modest body role="ANALYST" is allowed. The
     body role is an inert advisory claim; authority comes from the actor's real roles.

The OVERRIDE_ROLE master is SNAPSHOTTED at the start and RESTORED (re-authored + approved) in a
finally block, so the shared DB is left with the seeded semantics. This suite is NOT registered
in run_regression (run it standalone against the gateway).
"""
import copy
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0

# Grade ladder (AAA best → D worst); notch magnitude = index distance. Mirrors MasterScale.
LADDER = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]

MASTER_MAKER = "master.maker"
MASTER_CHECKER = "master.checker"


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


def line(v):
    return {"value": v, "sourceDocument": "ovr.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount=40_000_000):
    """cp -> app -> spread -> confirm -> rate; returns (app ref, model grade). No rating confirm needed."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"OVR {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"OVR{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
        per("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    st, rating = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    rating = must(st, rating, "rate")
    return ref, rating["modelGrade"]


def worse(grade, n):
    """The grade n notches worse (down the ladder) than `grade`, clamped to the ladder end."""
    return LADDER[min(LADDER.index(grade) + n, len(LADDER) - 1)]


def override(ref, proposed, actor, role=None):
    body = {"proposedGrade": proposed, "reasonCode": "MANAGEMENT_QUALITY", "note": f"{actor} override"}
    if role is not None:
        body["role"] = role
    return call("POST", f"/risk/api/risk/{ref}/rating/override", body, actor=actor)


def notch_of(grade_from, grade_to):
    return abs(LADDER.index(grade_from) - LADDER.index(grade_to))


# ---------------------------------------------------------------- snapshot the OVERRIDE_ROLE master
st, all_cv = call("GET", "/config/api/masters/CODE_VALUE")
all_cv = must(st, all_cv, "CODE_VALUE masters")
orig = next((r for r in all_cv if r["recordKey"] == "OVERRIDE_ROLE" and r.get("status") == "ACTIVE"), None)
if orig is None:
    print("  ERROR OVERRIDE_ROLE master not seeded"); sys.exit(1)
original_payload = copy.deepcopy(orig["payload"])


def author_override_role(payload, label):
    st, sub = call("POST", "/config/api/masters/CODE_VALUE",
                   {"recordKey": "OVERRIDE_ROLE", "payload": payload}, actor=MASTER_MAKER)
    sub = must(st, sub, label)
    st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor=MASTER_CHECKER)
    return must(st, ap, label + " approve")


def payload_with_analyst(limit):
    p = copy.deepcopy(original_payload)
    for v in p.get("values", []):
        if v.get("code") == "ANALYST":
            v["score"] = limit
    return p


try:
    # ============================================================ A. enforced from the master
    print("\n== A. Notch limit enforced from the actor's ACTOR_ROLE role via OVERRIDE_ROLE master ==")
    st, oroles = call("GET", "/config/api/code-values/OVERRIDE_ROLE")
    oroles = must(st, oroles, "OVERRIDE_ROLE resolver")
    analyst_score = next((v.get("score") for v in oroles["values"] if v["code"] == "ANALYST"), None)
    check("seeded OVERRIDE_ROLE gives ANALYST notch limit = 1", analyst_score == 1, str(analyst_score))

    refA, gA = rated_deal("A")
    check("deal A rated at an investment-ish grade (room to downgrade)",
          LADDER.index(gA) <= 6, gA)

    one = worse(gA, 1)
    st, ov = override(refA, one, actor="analyst.user")
    check("ANALYST 1-notch override accepted (within notch=1)",
          st == 200 and ov["overridden"] and notch_of(gA, one) == 1, f"{st} {ov}")

    two = worse(gA, 2)
    st, r = override(refA, two, actor="analyst.user")
    check("ANALYST 2-notch override blocked -> 403 (exceeds notch=1)",
          st == 403 and notch_of(gA, two) == 2, f"{st} {r}")

    far = worse(gA, 5)
    st, ov = override(refA, far, actor="cro")
    check("CRO large override accepted (notch=99 from the master)",
          st == 200 and ov["overridden"] and ov["finalGrade"] == far, f"{st} {ov}")

    # ============================================================ B. editing the master moves the limit
    print("\n== B. Editing the OVERRIDE_ROLE score changes the enforced limit ==")
    refB, gB = rated_deal("B")
    twoB = worse(gB, 2)

    # Baseline: with ANALYST=1, a 2-notch ANALYST override is blocked.
    st, r = override(refB, twoB, actor="analyst.user")
    check("baseline (ANALYST=1): 2-notch ANALYST override -> 403", st == 403, f"{st} {r}")

    # Raise ANALYST notch limit to 3 via maker-checker.
    author_override_role(payload_with_analyst(3), "raise ANALYST to 3")
    st, chk = call("GET", "/config/api/code-values/OVERRIDE_ROLE")
    raised = next((v.get("score") for v in chk["values"] if v["code"] == "ANALYST"), None)
    check("master now advertises ANALYST notch limit = 3", raised == 3, str(raised))
    st, ov = override(refB, twoB, actor="analyst.user")
    check("after raising the master: 2-notch ANALYST override now ACCEPTED -> 200",
          st == 200 and ov["overridden"] and ov["finalGrade"] == twoB, f"{st} {ov}")

    # Restore ANALYST=1 and prove the same 2-notch override is blocked again.
    author_override_role(payload_with_analyst(1), "restore ANALYST to 1")
    st, r = override(refB, twoB, actor="analyst.user")
    check("after restoring ANALYST=1: the SAME 2-notch override -> 403 again",
          st == 403, f"{st} {r}")

    # ============================================================ C. the body role is not a grant
    print("\n== C. A request-body role claim is NOT a grant (authority = the actor's real roles) ==")
    refC, gC = rated_deal("C")
    farC = worse(gC, 4)

    st, r = override(refC, farC, actor="analyst.user", role="CRO")
    check("ANALYST self-attesting body role=CRO is STILL notch-capped -> 403",
          st == 403, f"{st} {r}")

    st, ov = override(refC, farC, actor="cro", role="ANALYST")
    check("genuine CRO with a modest body role=ANALYST is allowed -> 200 (real role wins)",
          st == 200 and ov["overridden"] and ov["finalGrade"] == farC, f"{st} {ov}")

finally:
    # Restore the seeded OVERRIDE_ROLE master so the shared DB is left untouched.
    author_override_role(original_payload, "restore original OVERRIDE_ROLE")
    st, chk = call("GET", "/config/api/code-values/OVERRIDE_ROLE")
    if st == 200:
        restored = next((v.get("score") for v in chk["values"] if v["code"] == "ANALYST"), None)
        check("OVERRIDE_ROLE restored to seeded semantics (ANALYST notch = 1)",
              restored == 1, str(restored))


print(f"\n== rating-override config e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
