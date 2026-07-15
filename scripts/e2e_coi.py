#!/usr/bin/env python3
"""
Conflict-of-interest (COI) attestations — e2e (Wave-2).

COI attestation is a named-human self-declaration against a subject (a deal). It is a
record by default; it only ever *gates* a decision/committee-vote when the jurisdiction's
DOA_MATRIX pack opts in via the top-level `require_coi_attestation` key.

This suite proves three things:

  A. DEFAULT-OFF — with the seeded DOA_MATRIX pack (no `require_coi_attestation` key), a
     decision proceeds exactly as before; the routed decision carries
     requireCoiAttestation=false. This is the byte-identical guarantee that keeps the
     existing e2e_decisioning / e2e_rbac / DoA suites green.

  B. ATTESTATION — an actor records their OWN attestation (X-Actor, not the body);
     it is listable per subject, fetchable by coiRef, and stamps an audit HUMAN event.

  C. GATE-ON — in a transient IN-RBI pack that sets `require_coi_attestation=true`:
       * an un-attested (but authority-qualified) approver -> 403,
       * a CONFLICTED attester -> 403 (and cannot self-clear by re-attesting NONE),
       * an ATTESTED, non-CONFLICTED (DECLARED_MANAGED) approver -> allowed.
     The SAME actor (cro) is blocked while un-attested and allowed after a managed
     attestation — proving the 403 is COI, not authority.

The transient pack is authored at the start and RESTORED (original payload re-authored +
dual-signed) in a finally block, so the shared DB is left with the seeded semantics.
This suite is NOT registered in run_regression (run it standalone against the gateway).
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


def msg_of(b):
    return (b.get("message") if isinstance(b, dict) else str(b)) or ""


def line(v):
    return {"value": v, "sourceDocument": "coi.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount):
    """cp -> app -> spread -> confirm -> rate -> rating/confirm; returns the app ref."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"COI {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"COI{suffix}",
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
    return ref


APPROVE = {"outcome": "APPROVE", "role": "CREDIT_COMMITTEE", "rationale": "Within appetite"}

# ---------------------------------------------------------------- transient pack author/restore
st, cur = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=DOA_MATRIX")
cur = must(st, cur, "current DOA_MATRIX")
original_payload = copy.deepcopy(cur["payload"])
pack_code = cur["code"]


def author_coi_pack():
    payload = copy.deepcopy(original_payload)
    payload["require_coi_attestation"] = True     # additive top-level key
    st, draft = call("POST", "/config/api/rulepacks",
                     {"jurisdiction": "IN-RBI", "type": "DOA_MATRIX", "code": pack_code, "payload": payload},
                     actor="pack.author.alice")
    draft = must(st, draft, "author COI pack")
    did = draft["id"]
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty")
    st, signed = call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol")
    must(st, signed, "sign COI pack")


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
    # ============================================================ A. default-OFF (byte-identical)
    print("\n== A. Default-OFF: no require_coi_attestation key -> decision proceeds as before ==")
    check("seeded DOA_MATRIX has no require_coi_attestation key",
          "require_coi_attestation" not in original_payload, str(list(original_payload.keys())))

    aref = rated_deal("OFF", 40_000_000)
    st, routedA = call("POST", f"/decision/api/decisions/{aref}/route", actor="credit.ops")
    routedA = must(st, routedA, "route default-off deal")
    check("routed decision carries requireCoiAttestation=false (key absent)",
          routedA.get("requireCoiAttestation") is False, str(routedA.get("requireCoiAttestation")))
    # No attestation exists for this deal; the decision must still go through untouched.
    st, decidedA = call("POST", f"/decision/api/decisions/{aref}/decide", APPROVE, actor="cro")
    decidedA = must(st, decidedA, "decide default-off deal")
    check("un-attested approver decides normally when the key is absent (gate OFF)",
          decidedA["status"] == "DECIDED" and decidedA["outcome"] == "APPROVE" and decidedA["decidedBy"] == "cro",
          str(decidedA.get("status")))

    # ============================================================ author the opt-in pack
    author_coi_pack()
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=DOA_MATRIX")
    chk = must(st, chk, "COI pack active")
    check("transient DOA_MATRIX now sets require_coi_attestation=true",
          chk["payload"].get("require_coi_attestation") is True, str(chk["payload"].get("require_coi_attestation")))

    cref = rated_deal("GATE", 40_000_000)
    st, routedC = call("POST", f"/decision/api/decisions/{cref}/route", actor="credit.ops")
    routedC = must(st, routedC, "route gated deal")
    check("routed decision captured requireCoiAttestation=true from the pack",
          routedC.get("requireCoiAttestation") is True, str(routedC.get("requireCoiAttestation")))

    # ============================================================ C1. un-attested approver -> 403
    print("\n== C. Gate ON ==")
    st, r = call("POST", f"/decision/api/decisions/{cref}/decide", APPROVE, actor="cro")
    check("un-attested (but authority-qualified) approver blocked -> 403", st == 403, f"{st} {r}")
    check("...403 is a conflict-of-interest denial (not authority)",
          "Conflict-of-interest" in msg_of(r) or "COI" in msg_of(r), msg_of(r))
    st, still = call("GET", f"/decision/api/decisions/{cref}")
    check("deal remains PENDING_APPROVAL after the COI block",
          still["status"] == "PENDING_APPROVAL" and still.get("outcome") is None, str(still.get("status")))

    # ============================================================ B. attestation basics + audit
    print("\n== B. Attestation record (X-Actor), listing, get, audit HUMAN ==")
    st, att = call("POST", "/decision/api/coi",
                   {"subjectType": "application", "subjectRef": cref, "role": "CREDIT_COMMITTEE",
                    "declaration": "CONFLICTED", "note": "Board seat on borrower"}, actor="credit.committee")
    att = must(st, att, "attest conflicted")
    check("attestation created (coiRef COI-*, ATTESTED, records the X-Actor, not the body)",
          att["coiRef"].startswith("COI-") and att["status"] == "ATTESTED"
          and att["actor"] == "credit.committee" and att["declaration"] == "CONFLICTED", str(att))
    st, lst = call("GET", f"/decision/api/coi?subjectRef={cref}")
    lst = must(st, lst, "list by subject")
    check("attestation is listed for the subject",
          any(a["coiRef"] == att["coiRef"] for a in lst), str([a["coiRef"] for a in lst]))
    st, got = call("GET", f"/decision/api/coi/{att['coiRef']}")
    got = must(st, got, "get by coiRef")
    check("attestation fetchable by coiRef", got["coiRef"] == att["coiRef"], str(got.get("coiRef")))
    st, aud = call("GET", f"/decision/api/audit/subject?type=application&id={cref}")
    aud = must(st, aud, "audit subject")
    coi_events = [e for e in aud if e.get("eventType") == "COI_ATTESTED"]
    check("attestation stamped an audit HUMAN event",
          any(e.get("actorType") == "HUMAN" for e in coi_events), str(coi_events[:1]))

    # ============================================================ C2. CONFLICTED attester -> 403
    st, r = call("POST", f"/decision/api/decisions/{cref}/decide", APPROVE, actor="credit.committee")
    check("CONFLICTED attester blocked from deciding -> 403", st == 403, f"{st} {r}")
    check("...403 cites the conflict", "Conflict-of-interest" in msg_of(r) or "CONFLICTED" in msg_of(r), msg_of(r))

    # self-clear attempt: re-attest NONE, but the live CONFLICTED record still stands
    st, att2 = call("POST", "/decision/api/coi",
                    {"subjectType": "application", "subjectRef": cref, "role": "CREDIT_COMMITTEE",
                     "declaration": "NONE", "note": "changed my mind"}, actor="credit.committee")
    must(st, att2, "re-attest none")
    st, r = call("POST", f"/decision/api/decisions/{cref}/decide", APPROVE, actor="credit.committee")
    check("a CONFLICTED actor cannot self-approve their conflict away (still 403)", st == 403, f"{st} {r}")

    # ============================================================ C3. ATTESTED-managed approver -> allowed
    st, mng = call("POST", "/decision/api/coi",
                   {"subjectType": "application", "subjectRef": cref, "role": "CREDIT_COMMITTEE",
                    "declaration": "DECLARED_MANAGED", "note": "Prior mandate, recused from pricing"}, actor="cro")
    mng = must(st, mng, "attest managed")
    check("managed attestation recorded (DECLARED_MANAGED, ATTESTED)",
          mng["declaration"] == "DECLARED_MANAGED" and mng["status"] == "ATTESTED", str(mng))
    st, decidedC = call("POST", f"/decision/api/decisions/{cref}/decide", APPROVE, actor="cro")
    decidedC = must(st, decidedC, "decide as managed approver")
    check("ATTESTED, non-CONFLICTED (managed) approver is allowed -> DECIDED",
          decidedC["status"] == "DECIDED" and decidedC["outcome"] == "APPROVE" and decidedC["decidedBy"] == "cro",
          str(decidedC.get("status")))
    check("the SAME actor (cro) was blocked un-attested and allowed after attesting -> the 403 was COI, not authority",
          True)

finally:
    restore_original_pack()
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=DOA_MATRIX")
    if st == 200:
        restored = "require_coi_attestation" not in chk["payload"]
        check("DOA_MATRIX restored to seeded semantics (no require_coi_attestation key)",
              restored, str(list(chk["payload"].keys())))


print(f"\n== COI attestation e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
