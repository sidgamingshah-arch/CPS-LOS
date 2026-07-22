#!/usr/bin/env python3
"""
CAM Annexure engine (CLoM R1-09) — e2e.

decision-service gained ONE governed authoring engine for every CAM-annexure type
(CRI sheet · industry-scenario · ESG · exchange-risk · project-deferment · group-analysis),
driven by the ANNEXURE_TYPE master (config-as-data — a new type is a new master row, NOT a
code change; the six rows are also seeded additively by config-service's MasterSeeder). An
annexure is an advisory authoring artefact attached to a deal/proposal: an author drafts the
sections (optionally with a governed AI draft at the LLM boundary), then the workflow routes
DRAFT -> SUBMITTED -> REVIEWED -> APPROVED (or -> REJECTED) with maker-checker SoD at each gate.
It NEVER moves the subject deal's authoritative grade / PD / spread.

Proves:
  - the ANNEXURE_TYPE master types are listed (seeded / master-driven, NOT code);
  - creating EACH of the six types materialises its section skeleton from the master, with the
    master version pinned (typeVersion >= 1);
  - DRAFT -> SUBMITTED -> REVIEWED -> APPROVED works end-to-end;
  - review BY AUTHOR -> 403 (SoD reviewer != author), annexure untouched;
  - approve BY AUTHOR -> 403 (SoD approver != author), annexure untouched;
  - reject without a reason -> 400; reject with a reason -> REJECTED;
  - CRUCIALLY: the subject application's authoritative grade / PD / spread is BYTE-IDENTICAL
    before vs after the whole annexure lifecycle;
  - the create / submit / review / approve gates each stamp an audit HUMAN event.

This suite is registered in run_regression (runs against the live gateway).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GW", "http://localhost:8080")
PASS, FAIL = 0, 0
NONCE = str(int(time.time()))

AUTHOR = "rm.user"
REVIEWER = "credit.officer"
APPROVER = "credit.committee"
M_MAKER = "annexure.master.maker"
M_CHECKER = "annexure.master.checker"

ANNEXURE_TYPES = {
    "CRI_SHEET": ["borrower_profile", "exposure_summary", "rating_summary", "key_risks", "recommendation"],
    "INDUSTRY_SCENARIO": ["industry_overview", "demand_supply", "regulatory_landscape", "outlook", "borrower_positioning"],
    "ESG_ASSESSMENT": ["environmental", "social", "governance", "esg_risk_rating", "mitigants"],
    "EXCHANGE_RISK": ["fx_exposure", "hedging_policy", "natural_hedge", "stress_scenario", "residual_risk"],
    "PROJECT_DEFERMENT": ["project_status", "deferment_reason", "revised_timeline", "cost_overrun", "impact_on_repayment"],
    "GROUP_ANALYSIS": ["group_structure", "intra_group_exposure", "group_financials", "related_party", "group_risk_assessment"],
}


def call(method, path, body=None, actor="ops.admin"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
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


def seed_annexure_type(key, section_keys):
    """Submit + approve (maker != checker) so the ANNEXURE_TYPE row is ACTIVE for runtime reads.
    Idempotent enough for a shared DB: re-seeding an existing key simply advances its version."""
    payload = {"label": key.replace("_", " ").title(),
               "sections": [{"key": sk, "title": sk.replace("_", " ").title()} for sk in section_keys]}
    st, rec = call("POST", "/config/api/masters/ANNEXURE_TYPE", {"recordKey": key, "payload": payload}, actor=M_MAKER)
    rec = must(st, rec, f"seed ANNEXURE_TYPE/{key}")
    st, ap = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor=M_CHECKER)
    must(st, ap, f"approve ANNEXURE_TYPE/{key}")


def line(v):
    return {"value": v, "sourceDocument": "anx.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


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
        "legalName": f"Annexure {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"ANX{suffix}{NONCE}",
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


# ============================================================ 0. seed + list the ANNEXURE_TYPE master
print("== 0. ANNEXURE_TYPE master (config-as-data): seed the six types + list them ==")
for key, secs in ANNEXURE_TYPES.items():
    seed_annexure_type(key, secs)
st, listed = call("GET", "/config/api/masters/ANNEXURE_TYPE")
listed = must(st, listed, "list ANNEXURE_TYPE")
listed_keys = {r.get("recordKey") for r in listed}
check("all six ANNEXURE_TYPE master types are listed",
      set(ANNEXURE_TYPES).issubset(listed_keys), sorted(listed_keys))

# ============================================================ 1. a rated deal (authoritative figures)
print("\n== 1. Setup: a rated deal with an authoritative grade / PD / spread ==")
REF = rated_deal("R1", 500_000_000)
st, risk0 = call("GET", f"/risk/api/risk/{REF}")
risk0 = must(st, risk0, "risk summary before")
st, env0 = call("GET", f"/origination/api/applications/{REF}/envelope")
env0 = must(st, env0, "envelope before")
RISK_SNAP = json.dumps(risk0, sort_keys=True)
ENV_SNAP = json.dumps(env0, sort_keys=True)
check("authoritative grade present on the subject deal",
      risk0.get("rating") and risk0["rating"].get("finalGrade") is not None, str(risk0.get("rating"))[:120])
check("authoritative spread ratios present on the subject deal",
      env0.get("ratios") is not None, str(env0.get("ratios"))[:120])

# ============================================================ 2. create each type -> DRAFT + sections
print("\n== 2. Create EACH annexure type -> DRAFT with sections materialised + version pinned ==")
created = {}
for key, expected_secs in ANNEXURE_TYPES.items():
    st, a = call("POST", "/decision/api/annexures",
                 {"annexureType": key, "subjectType": "APPLICATION", "subjectRef": REF,
                  "title": f"{key} for {REF}"}, actor=AUTHOR)
    a = must(st, a, f"create {key}")
    created[key] = a["annexureRef"]
    ok_ref = a["annexureRef"].startswith("ANX-") and len(a["annexureRef"]) == 10
    ok_draft = a["status"] == "DRAFT"
    ok_author = a["author"] == AUTHOR
    ok_ver = a.get("typeVersion", 0) >= 1
    ok_adv = a.get("advisory") is True
    ok_secs = set((a.get("sections") or {}).keys()) == set(expected_secs)
    check(f"{key}: ANX-* ref · DRAFT · author=X-Actor · advisory · typeVersion pinned · sections materialised",
          ok_ref and ok_draft and ok_author and ok_ver and ok_adv and ok_secs,
          f"ref={a['annexureRef']} status={a['status']} v={a.get('typeVersion')} secs={list((a.get('sections') or {}).keys())}")

CRI = created["CRI_SHEET"]

# ============================================================ 3. full lifecycle + SoD on CRI_SHEET
print("\n== 3. CRI_SHEET: edit -> submit -> review (author->403) -> approve (author->403) -> APPROVED ==")
# author edits a section
st, a = call("PUT", f"/decision/api/annexures/{CRI}/sections",
             {"sections": {"recommendation": {"title": "Recommendation", "content": "Recommend approval within appetite."}}},
             actor=AUTHOR)
must(st, a, "edit sections")

# a non-author cannot edit (author-only edit gate)
st, b = call("PUT", f"/decision/api/annexures/{CRI}/sections",
             {"sections": {"key_risks": {"title": "Key risks", "content": "hijack"}}}, actor=REVIEWER)
check("edit by a non-author -> 403 (author-only)", st == 403, f"HTTP {st} {b}")

st, a = call("POST", f"/decision/api/annexures/{CRI}/submit", actor=AUTHOR)
a = must(st, a, "submit")
check("submit -> SUBMITTED", a["status"] == "SUBMITTED", a["status"])

# SoD: reviewer must differ from author.
st, b = call("POST", f"/decision/api/annexures/{CRI}/review", {"notes": "self"}, actor=AUTHOR)
check("review by author -> 403 (SoD reviewer != author)", st == 403, f"HTTP {st} {b}")
check("...403 is a segregation-of-duties denial",
      "segregation of duties" in msg_of(b) or "differ from the author" in msg_of(b), msg_of(b))
st, chk = call("GET", f"/decision/api/annexures/{CRI}")
check("annexure untouched by the forbidden review", chk["status"] == "SUBMITTED", chk["status"])

st, a = call("POST", f"/decision/api/annexures/{CRI}/review", {"notes": "looks good"}, actor=REVIEWER)
a = must(st, a, "review by reviewer")
check("review by a non-author -> REVIEWED", a["status"] == "REVIEWED", a["status"])
check("reviewer stamped", a.get("reviewer") == REVIEWER, str(a.get("reviewer")))

# SoD: approver must differ from author.
st, b = call("POST", f"/decision/api/annexures/{CRI}/approve", {"notes": "self"}, actor=AUTHOR)
check("approve by author -> 403 (SoD approver != author)", st == 403, f"HTTP {st} {b}")
st, chk = call("GET", f"/decision/api/annexures/{CRI}")
check("annexure untouched by the forbidden approve", chk["status"] == "REVIEWED", chk["status"])

st, a = call("POST", f"/decision/api/annexures/{CRI}/approve", {"notes": "approved"}, actor=APPROVER)
a = must(st, a, "approve")
check("approve by a non-author -> APPROVED", a["status"] == "APPROVED", a["status"])
check("approvedBy stamped", a.get("approvedBy") == APPROVER, str(a.get("approvedBy")))

# ============================================================ 4. reject flow (mandatory reason)
print("\n== 4. ESG_ASSESSMENT: submit -> reject (reason mandatory) ==")
ESG = created["ESG_ASSESSMENT"]
must(*call("POST", f"/decision/api/annexures/{ESG}/submit", actor=AUTHOR)[:2], "submit ESG")
st, b = call("POST", f"/decision/api/annexures/{ESG}/reject", {"reason": ""}, actor=REVIEWER)
check("reject without a reason -> 400 (reason mandatory)", st == 400, f"HTTP {st} {b}")
st, b = call("POST", f"/decision/api/annexures/{ESG}/reject", {"reason": "insufficient ESG data"}, actor=AUTHOR)
check("reject by author -> 403 (SoD)", st == 403, f"HTTP {st} {b}")
st, r = call("POST", f"/decision/api/annexures/{ESG}/reject", {"reason": "insufficient ESG data"}, actor=REVIEWER)
r = must(st, r, "reject ESG")
check("reject with a reason -> REJECTED", r["status"] == "REJECTED" and r.get("rejectReason") == "insufficient ESG data",
      f"{r['status']} / {r.get('rejectReason')}")

# ============================================================ 5. listing + audit HUMAN on the gates
print("\n== 5. Listing + audit HUMAN on the gates ==")
st, bysubj = call("GET", f"/decision/api/annexures?subjectRef={REF}")
bysubj = must(st, bysubj, "list by subject")
check("subject listing returns all six annexures", len(bysubj) >= 6, len(bysubj))
st, byapproved = call("GET", "/decision/api/annexures?status=APPROVED")
byapproved = must(st, byapproved, "list by status")
check("status=APPROVED listing returns the CRI_SHEET", any(x["annexureRef"] == CRI for x in byapproved),
      [x["annexureRef"] for x in byapproved][:8])

st, aud = call("GET", f"/decision/api/audit/subject?type=Annexure&id={CRI}")
aud = must(st, aud, "audit subject")
types = [x.get("eventType") for x in aud]
check("ANNEXURE_CREATED + SUBMITTED + REVIEWED + APPROVED audited",
      {"ANNEXURE_CREATED", "ANNEXURE_SUBMITTED", "ANNEXURE_REVIEWED", "ANNEXURE_APPROVED"}.issubset(set(types)), types)
check("every annexure audit event on the gates is actorType HUMAN",
      all(x.get("actorType") == "HUMAN" for x in aud), [(x.get("eventType"), x.get("actorType")) for x in aud])

# ============================================================ 6. the advisory invariant
print("\n== 6. INVARIANT: subject deal grade / PD / spread BYTE-IDENTICAL after the annexure lifecycle ==")
st, risk1 = call("GET", f"/risk/api/risk/{REF}")
risk1 = must(st, risk1, "risk summary after")
st, env1 = call("GET", f"/origination/api/applications/{REF}/envelope")
env1 = must(st, env1, "envelope after")
check("authoritative grade / PD / capital / pricing byte-identical before/after the annexure lifecycle",
      json.dumps(risk1, sort_keys=True) == RISK_SNAP, "risk summary mutated")
check("authoritative spread / ratios byte-identical before/after the annexure lifecycle",
      json.dumps(env1, sort_keys=True) == ENV_SNAP, "spread mutated")

print(f"\nannexures e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
