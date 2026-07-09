#!/usr/bin/env python3
"""
Decisioning loop closure — e2e (P1 track 2).

Three connected additions to the credit-decision lane, all behaviour-preserving
(inert unless the new path is exercised):

  1. CONDITIONAL_APPROVE (or APPROVE) carrying structured conditions of sanction
     materialises them into the pre-disbursement CP register (source=SANCTION),
     so the existing gate enforces them. A blank facilityRef fans out to every
     facility on the deal.
  2. A sanction letter is generated from the approved decision through the
     existing DocGen machinery — DRAFT + advisory, quoting the deterministic
     approved facilities/pricing + conditions, human-confirmed with maker≠checker.
  3. A COMMITTEE authority tier (flagged in the DOA_MATRIX pack) requires a
     QUORUM of distinct approving votes; the router of the deal cannot vote and
     no member votes twice (SoD); the decision finalises only on quorum.

The committee DOA_MATRIX flag is authored into a transient IN-RBI pack version at
the start and RESTORED (original payload re-authored + dual-signed) in a finally
block, so the shared DB is left with the seeded single-approver semantics and no
other suite is perturbed. This suite is registered LAST in run_regression.
"""
import copy
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


def line(v):
    return {"value": v, "sourceDocument": "dec.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount):
    """cp -> app -> spread -> confirm -> rate -> rating/confirm; returns (ref, facility_ref)."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Decisioning {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"DEC{suffix}",
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
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
    facs = must(st, facs, "facilities")
    return ref, facs[0]["reference"]


# ============================================================ committee-pack author/restore

st, cur = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=DOA_MATRIX")
cur = must(st, cur, "current DOA_MATRIX")
original_payload = copy.deepcopy(cur["payload"])
pack_code = cur["code"]
levels = cur["payload"]["levels"]


def max_for(auth):
    for lv in levels:
        if lv.get("authority") == auth:
            return lv.get("max_amount")
    return None


co_max = max_for("CREDIT_OFFICER") or 250_000_000
cc_max = max_for("CREDIT_COMMITTEE") or 1_000_000_000
committee_amount = (co_max + cc_max) / 2.0     # lands in the CREDIT_COMMITTEE band


def author_committee_pack():
    payload = copy.deepcopy(original_payload)
    for lv in payload["levels"]:
        if lv.get("authority") == "CREDIT_COMMITTEE":
            lv["committee"] = True
            lv["quorum"] = 2
    st, draft = call("POST", "/config/api/rulepacks",
                     {"jurisdiction": "IN-RBI", "type": "DOA_MATRIX", "code": pack_code, "payload": payload},
                     actor="pack.author.alice")
    draft = must(st, draft, "author committee pack")
    did = draft["id"]
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty")
    st, signed = call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol")
    must(st, signed, "sign committee pack")


def restore_original_pack():
    st, draft = call("POST", "/config/api/rulepacks",
                     {"jurisdiction": "IN-RBI", "type": "DOA_MATRIX", "code": pack_code,
                      "payload": original_payload}, actor="pack.author.alice")
    if st != 200:
        print(f"  WARN  could not re-author original DOA_MATRIX ({st})")
        return
    did = draft["id"]
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty")
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol")


try:
    author_committee_pack()
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=DOA_MATRIX")
    chk = must(st, chk, "committee pack active")
    cc = next((lv for lv in chk["payload"]["levels"] if lv.get("authority") == "CREDIT_COMMITTEE"), {})
    check("committee DOA_MATRIX active (CREDIT_COMMITTEE flagged, quorum 2)",
          cc.get("committee") is True and cc.get("quorum") == 2, str(cc))

    # ============================================================ 3. committee / quorum
    print("\n== 3. Committee / quorum decision mode ==")
    cref, _ = rated_deal("CMTE", committee_amount)
    # Route by a committee member -> that member becomes the proposer and cannot vote (SoD).
    st, routed = call("POST", f"/decision/api/decisions/{cref}/route", actor="credit.committee")
    routed = must(st, routed, "route committee deal")
    check("deal routed to CREDIT_COMMITTEE in committee mode",
          routed["requiredAuthority"] == "CREDIT_COMMITTEE" and routed["committeeMode"] is True,
          f"{routed.get('requiredAuthority')} committee={routed.get('committeeMode')}")
    check("quorum required = 2, status PENDING_APPROVAL, router recorded",
          routed["quorumRequired"] == 2 and routed["status"] == "PENDING_APPROVAL"
          and routed["routedBy"] == "credit.committee", str(routed))

    body = {"outcome": "APPROVE", "role": "CREDIT_COMMITTEE", "rationale": "Within appetite"}
    st, r = call("POST", f"/decision/api/decisions/{cref}/decide", body, actor="credit.committee")
    check("the member who routed the deal cannot vote (SoD) -> 403", st == 403, f"{st} {r}")
    st, r = call("POST", f"/decision/api/decisions/{cref}/decide", body, actor="analyst.user")
    check("insufficient-authority actor cannot vote -> 403", st == 403, f"{st} {r}")

    st, v1 = call("POST", f"/decision/api/decisions/{cref}/decide", body, actor="cro")
    v1 = must(st, v1, "vote 1 (cro)")
    check("first approving vote leaves the decision PENDING_APPROVAL (quorum not met)",
          v1["status"] == "PENDING_APPROVAL" and v1.get("outcome") is None, str(v1.get("status")))
    st, dup = call("POST", f"/decision/api/decisions/{cref}/decide", body, actor="cro")
    check("a member cannot vote twice (SoD) -> 403", st == 403, f"{st}")

    st, v2 = call("POST", f"/decision/api/decisions/{cref}/decide", body, actor="credit.officer.bot")
    v2 = must(st, v2, "vote 2 (credit.officer.bot)")
    check("second approving vote meets quorum -> DECIDED",
          v2["status"] == "DECIDED" and v2["outcome"] == "APPROVE", str(v2))
    check("finalised as a COMMITTEE decision", v2["decidedBy"] == "COMMITTEE", str(v2.get("decidedBy")))
    st, after = call("POST", f"/decision/api/decisions/{cref}/decide", body, actor="demo.user")
    check("no vote accepted after the decision is DECIDED -> 409", st == 409, f"{st}")
    st, votes = call("GET", f"/decision/api/decisions/{cref}/votes")
    votes = must(st, votes, "votes")
    voters = sorted(v["voter"] for v in votes)
    check("exactly the two qualifying votes recorded (no rejected attempts persisted)",
          voters == ["credit.officer.bot", "cro"], str(voters))

    # ============================================================ 1. conditions -> CP register
    print("\n== 1. CONDITIONAL_APPROVE conditions -> CP register ==")
    ref, fac1 = rated_deal("COND", 40_000_000)
    # add a second facility so the fan-out (blank facilityRef) is observable
    st, fac2obj = call("POST", f"/origination/api/applications/{ref}/facilities",
                       {"facilityType": "WORKING_CAPITAL", "amount": 15_000_000, "currency": "INR",
                        "tenorMonths": 12, "purpose": "WC"}, actor="rm.user")
    fac2 = must(st, fac2obj, "second facility")["reference"]
    call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops")

    st, neg = call("POST", f"/decision/api/decisions/{ref}/decide",
                   {"outcome": "CONDITIONAL_APPROVE", "role": "CREDIT_OFFICER",
                    "rationale": "conditions to follow"}, actor="credit.officer")
    check("CONDITIONAL_APPROVE with neither free-text nor structured conditions -> 400", st == 400, f"{st} {neg}")

    st, decided = call("POST", f"/decision/api/decisions/{ref}/decide", {
        "outcome": "CONDITIONAL_APPROVE", "role": "CREDIT_OFFICER",
        "rationale": "Sanctioned subject to conditions",
        "conditionsPrecedent": [
            {"code": "COS-CHARGE", "title": "Perfect first charge over new plant & machinery", "mandatory": True},
            {"facilityRef": fac1, "code": "COS-EQUITY", "title": "Promoter equity infusion evidenced",
             "mandatory": True}]}, actor="credit.officer")
    decided = must(st, decided, "decide conditional")
    check("deal DECIDED (single-approver, non-committee tier)",
          decided["status"] == "DECIDED" and decided["outcome"] == "CONDITIONAL_APPROVE"
          and decided.get("committeeMode") is False, str(decided.get("status")))

    st, reg = call("GET", f"/decision/api/cps/{ref}")
    reg = must(st, reg, "cp register")
    sanction = [c for c in reg if c.get("source") == "SANCTION"]
    codes = sorted({c["code"] for c in sanction})
    check("conditions materialised into the CP register as source=SANCTION",
          codes == ["COS-CHARGE", "COS-EQUITY"], str(codes))
    charge = [c for c in sanction if c["code"] == "COS-CHARGE"]
    charge_facs = sorted(c["facilityRef"] for c in charge)
    check("blank-facilityRef condition fanned out to BOTH facilities",
          charge_facs == sorted([fac1, fac2]), str(charge_facs))
    equity = [c for c in sanction if c["code"] == "COS-EQUITY"]
    check("pinned condition attached only to its facility", len(equity) == 1 and equity[0]["facilityRef"] == fac1,
          str([c["facilityRef"] for c in equity]))
    check("materialised CPs are mandatory + OPEN", all(c["mandatory"] and c["status"] == "OPEN" for c in sanction))

    st, gate = call("GET", f"/decision/api/cps/gate/{ref}/{fac1}")
    gate = must(st, gate, "gate fac1")
    blocker_codes = {b["code"] for b in gate.get("blockers", [])}
    check("the pre-disbursement gate now blocks on the sanction conditions",
          gate["canDrawdown"] is False and "COS-CHARGE" in blocker_codes and "COS-EQUITY" in blocker_codes,
          f"canDraw={gate.get('canDrawdown')} blockers={blocker_codes}")

    # clear the sanction CPs on fac1 -> gate opens (proves they're normal, clearable CPs)
    for c in [c for c in sanction if c["facilityRef"] == fac1]:
        call("POST", f"/decision/api/cps/{c['id']}/clear",
             {"evidenceRef": "DMS-DEC", "note": "evidence on file"}, actor="credit.ops")
    st, gate2 = call("GET", f"/decision/api/cps/gate/{ref}/{fac1}")
    gate2 = must(st, gate2, "gate fac1 after clear")
    check("clearing the sanction CPs opens the gate", gate2["canDrawdown"] is True, str(gate2.get("canDrawdown")))

    # ============================================================ 2. sanction letter
    print("\n== 2. Sanction letter on the approved deal ==")
    st, risk_before = call("GET", f"/risk/api/risk/{ref}")
    grade_before = must(st, risk_before, "risk before")["rating"]["finalGrade"]

    st, letter = call("POST", f"/decision/api/decisions/{ref}/sanction-letter", actor="cad.officer")
    letter = must(st, letter, "sanction letter")
    check("sanction letter generated DRAFT + advisory",
          letter["status"] == "DRAFT" and letter["advisory"] is True
          and letter["templateKey"] == "SANCTION_LETTER", str(letter.get("status")))
    check("letter carries the sanction clauses (facilities + conditions)",
          "approved_facilities" in letter["clauseOrder"] and "conditions_precedent" in letter["clauseOrder"],
          str(letter.get("clauseOrder")))
    html = letter.get("html", "")
    check("letter body quotes borrower, a facility, and a condition of sanction",
          "Decisioning COND Ltd" in html and fac1 in html and "COS-CHARGE" in html,
          f"borrower={'Decisioning COND Ltd' in html} fac={fac1 in html} cond={'COS-CHARGE' in html}")

    st, risk_after = call("GET", f"/risk/api/risk/{ref}")
    grade_after = must(st, risk_after, "risk after")["rating"]["finalGrade"]
    check("advisory invariant: authoritative grade unchanged by letter generation",
          grade_after == grade_before, f"{grade_before} -> {grade_after}")
    st, dec_after = call("GET", f"/decision/api/decisions/{ref}")
    check("advisory invariant: decision outcome unchanged by letter generation",
          dec_after["outcome"] == "CONDITIONAL_APPROVE", str(dec_after.get("outcome")))

    st, self_conf = call("POST", f"/decision/api/docs/{letter['id']}/confirm", actor="cad.officer")
    check("generator cannot confirm own sanction letter (maker != checker) -> 403", st == 403, f"{st}")
    st, conf = call("POST", f"/decision/api/docs/{letter['id']}/confirm", actor="credit.officer")
    conf = must(st, conf, "confirm letter")
    check("a different named human confirms the letter -> CONFIRMED",
          conf["status"] == "CONFIRMED" and conf["confirmedBy"] == "credit.officer", str(conf.get("status")))

    # negative: sanction letter only for a DECIDED approval
    uref, _ = rated_deal("UND", 40_000_000)
    call("POST", f"/decision/api/decisions/{uref}/route", actor="credit.ops")
    st, no = call("POST", f"/decision/api/decisions/{uref}/sanction-letter", actor="cad.officer")
    check("sanction letter refused on an un-decided deal -> 409", st == 409, f"{st}")

finally:
    restore_original_pack()
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=DOA_MATRIX")
    if st == 200:
        cc = next((lv for lv in chk["payload"]["levels"] if lv.get("authority") == "CREDIT_COMMITTEE"), {})
        restored = not cc.get("committee")
        check("DOA_MATRIX restored to seeded single-approver semantics", restored, str(cc))


print(f"\n== decisioning loop closure e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
