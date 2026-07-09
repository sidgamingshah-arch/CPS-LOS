#!/usr/bin/env python3
"""
Group grade ladder (D10, P2) — e2e.

Group decisioning derived only a best→weakest grade BAND. It now derives a defensible
GROUP grade on the same AAA..D master ladder from member grades + exposures, per a
config-driven GROUP_GRADE policy (default EXPOSURE_WEIGHTED_NOTCH). Deterministic +
advisory: it reads authoritative member figures and mutates none of them.

Fixture (grades set via governed overrides so the ladder maths is exact):
  member A  AA   exposure 100m
  member B  BBB  exposure 800m   <- large, mid-grade
  member C  B    exposure 100m
  exposure-weighted notch = (1*100 + 3*800 + 5*100)/1000 = 3.0 -> BBB (member B).

Proves: deterministic ladder derivation; band coherence; advisory + SYSTEM audit; member
grades byte-identical after the rollup; and a config method-flip (EXPOSURE_WEIGHTED_NOTCH ->
WORST_OF) moves the group grade to the weakest member. Restores the pack in a finally.
"""
import json
import math
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
LADDER = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]


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
    return {"value": v, "sourceDocument": "gg.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def period(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def member(suffix, amount, grade):
    """Create a rated member and OVERRIDE its authoritative grade to `grade` (governed)."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"GroupGrade {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"GG{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.alice")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "WC",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.4, "secured": True}, actor="rm.alice")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        period("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
        period("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    st, ov = call("POST", f"/risk/api/risk/{ref}/rating/override",
                  {"proposedGrade": grade, "reasonCode": "GROUP_SUPPORT", "note": "e2e group grade",
                   "role": "CREDIT_COMMITTEE"}, actor="credit.committee")
    ov = must(st, ov, f"override {grade}")
    st, cf = call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="cro")
    cf = must(st, cf, f"confirm {grade}")
    return cp["id"], ref, grade


def author_group_grade(method):
    body = {"jurisdiction": "IN-RBI", "type": "GROUP_GRADE", "code": "rbi_group_grade",
            "payload": {"method": method, "rounding": "HALF_UP_WORSE",
                        "parent_support_notches": 0, "min_rated_members": 1}}
    st, draft = call("POST", "/config/api/rulepacks", body, actor="pack.author.alice")
    draft = must(st, draft, f"author GROUP_GRADE {method}")
    did = draft["id"]
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty")
    must(*call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol"),
         "sign GROUP_GRADE")


try:
    print("== 0. Build a 3-member group with distinct grades + exposures ==")
    a_id, a_ref, _ = member("A", 100_000_000, "AA")
    b_id, b_ref, _ = member("B", 800_000_000, "BBB")
    c_id, c_ref, _ = member("C", 100_000_000, "B")
    st, grp = call("POST", "/counterparty/api/initiation/groups",
                   {"name": "GroupGrade Test Group", "groupRmId": "rm.group", "country": "IN",
                    "multiCountry": False}, actor="rm.alice")
    grp = must(st, grp, "group")
    for cid in (a_id, b_id, c_id):
        call("POST", f"/counterparty/api/initiation/counterparties/{cid}/group/{grp['id']}", actor="rm.alice")
    gref = grp["reference"]

    print("\n== 1. Derived group grade = deterministic exposure-weighted notch ==")
    st, gi = call("GET", f"/decision/api/decisions/groups/{gref}/insights", actor="credit.officer")
    gi = must(st, gi, "insights")
    check("group grade derived on the AAA..D ladder + advisory",
          gi.get("groupGrade") in LADDER and gi.get("advisory") is True, str(gi.get("groupGrade")))
    check("method is EXPOSURE_WEIGHTED_NOTCH (default policy)",
          gi.get("groupGradeMethod") == "EXPOSURE_WEIGHTED_NOTCH", str(gi.get("groupGradeMethod")))
    # deterministic recompute from the returned members
    num = den = 0.0
    for m in gi["members"]:
        if m.get("finalGrade") in LADDER and m.get("exposure"):
            num += LADDER.index(m["finalGrade"]) * m["exposure"]
            den += m["exposure"]
    expected = LADDER[max(0, min(9, math.floor(num / den + 0.5)))] if den else None
    check("group grade equals the deterministic ladder computation (expected BBB)",
          gi["groupGrade"] == expected == "BBB", f"got {gi['groupGrade']} expected {expected}")
    hi, lo = LADDER.index(gi["highestGrade"]), LADDER.index(gi["lowestGrade"])
    check("group grade sits inside the [best, weakest] band",
          hi <= LADDER.index(gi["groupGrade"]) <= lo, f"{gi['highestGrade']}..{gi['lowestGrade']} grp {gi['groupGrade']}")
    contribs = gi.get("groupGradeContributions") or []
    check("per-member contributions present, weights sum to ~1.0",
          len(contribs) == 3 and abs(sum(c["weightPct"] for c in contribs) - 1.0) < 0.01,
          str([(c["counterpartyRef"], c["finalGrade"], c["weightPct"]) for c in contribs]))
    check("basis explains the method + rated-member count",
          "Exposure-weighted" in (gi.get("groupGradeBasis") or "") and "3 rated" in (gi.get("groupGradeBasis") or ""),
          str(gi.get("groupGradeBasis")))

    print("\n== 2. Deterministic + SYSTEM-audited (never AI) ==")
    st, trail = call("GET", f"/decision/api/audit/subject?type=Group&id={gref}")
    trail = trail if isinstance(trail, list) else []
    check("GROUP_GRADE_DERIVED stamped by SYSTEM (engine), not AI",
          any(e.get("eventType") == "GROUP_GRADE_DERIVED" and e.get("actorType") == "SYSTEM" for e in trail)
          and not any(e.get("eventType") == "GROUP_GRADE_DERIVED" and e.get("actorType") == "AI" for e in trail),
          str([e.get("actorType") for e in trail if e.get("eventType") == "GROUP_GRADE_DERIVED"]))

    print("\n== 3. Advisory invariant: member authoritative grades unchanged ==")
    before = {m["counterpartyRef"]: m["finalGrade"] for m in gi["members"]}
    st, prop = call("POST", f"/decision/api/decisions/groups/{gref}/combined-proposal/generate",
                    actor="credit.officer")
    prop = must(st, prop, "combined proposal")
    check("combined proposal renders the derived group grade",
          "Group grade" in json.dumps(prop) and "BBB" in json.dumps(prop), "group grade line absent")
    st, gi2 = call("GET", f"/decision/api/decisions/groups/{gref}/insights", actor="credit.officer")
    gi2 = must(st, gi2, "insights 2")
    after = {m["counterpartyRef"]: m["finalGrade"] for m in gi2["members"]}
    check("every member's authoritative grade is byte-identical after the rollup",
          before == after, f"{before} -> {after}")

    print("\n== 4. Config method-flip moves the group grade (governed) ==")
    author_group_grade("WORST_OF")
    st, gi3 = call("GET", f"/decision/api/decisions/groups/{gref}/insights", actor="credit.officer")
    gi3 = must(st, gi3, "insights 3")
    check("method flipped to WORST_OF via the GROUP_GRADE pack",
          gi3.get("groupGradeMethod") == "WORST_OF", str(gi3.get("groupGradeMethod")))
    check("WORST_OF group grade = weakest member (B), moved from BBB",
          gi3["groupGrade"] == gi3["lowestGrade"] == "B" and gi3["groupGrade"] != gi["groupGrade"],
          f"{gi3.get('groupGrade')} vs weakest {gi3.get('lowestGrade')}")
    check("member grades still unchanged after the method flip",
          {m["counterpartyRef"]: m["finalGrade"] for m in gi3["members"]} == before, "member grade moved")

finally:
    # Restore the seeded default so no later run inherits WORST_OF.
    author_group_grade("EXPOSURE_WEIGHTED_NOTCH")
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=GROUP_GRADE")
    if st == 200:
        check("GROUP_GRADE pack restored to EXPOSURE_WEIGHTED_NOTCH",
              chk["payload"]["method"] == "EXPOSURE_WEIGHTED_NOTCH", str(chk["payload"].get("method")))


print(f"\n== group grade ladder (D10) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
