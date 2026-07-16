#!/usr/bin/env python3
"""
Monitoring-artifact engine (post-disbursement) — e2e.

portfolio-service gained ONE governed engine for every monitoring-artifact type
(call memo · plant/site visit · LCR · QPR · broker review · stock audit · audit note),
driven by the MONITORING_ARTIFACT_TYPE master (config-as-data — a new type is a new
master row, NOT a code change). Artifacts are records / advisory: the workflow gathers
monitoring evidence and routes it DRAFT -> SUBMITTED -> REVIEWED -> APPROVED (-> AUTHORIZED)
with maker-checker SoD at each gate. It NEVER moves an authoritative figure.

Proves:
  - seed MONITORING_ARTIFACT_TYPE + VENDOR_MASTER rows via the generic master API (NOT code);
  - create CALL_MEMO -> DRAFT with sections materialised from the master (masterVersion pinned);
  - submit -> SUBMITTED;
  - review BY OWNER -> 403 (SoD reviewer != owner), artifact untouched;
  - review by a role-gated reviewer -> REVIEWED;
  - approve BY REVIEWER -> 403 (SoD approver != reviewer), artifact untouched;
  - approve by a role-gated approver -> APPROVED;
  - authorize on a non-requiresAuthorize type -> 409;
  - a requiresAuthorize type (LCR) reaches AUTHORIZED;
  - STOCK_AUDIT (vendorRfq) vendor-rfq raises an EXTERNAL_VENDOR query (a query row + an
    RFI notification/outbox façade row appears) and external-response flows the vendor reply back;
  - CRUCIALLY: an authoritative ECL / IRAC / exposure figure for the subject is BYTE-IDENTICAL
    before and after the whole artifact lifecycle.
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

OWNER = "portfolio.manager"        # PORTFOLIO role
REVIEWER = "credit.officer"        # CREDIT_OFFICER role
APPROVER = "credit.committee"      # CREDIT_COMMITTEE role
AUTHORISER = "cro"                 # CRO / BOARD_COMMITTEE role
M_MAKER = "monitoring.master.maker"
M_CHECKER = "monitoring.master.checker"


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


def seed_master(mtype, key, payload):
    """Submit + approve (maker != checker) so the record lands ACTIVE for the runtime reads."""
    st, rec = call("POST", f"/config/api/masters/{mtype}", {"recordKey": key, "payload": payload}, actor=M_MAKER)
    rec = must(st, rec, f"seed {mtype}/{key}")
    st, ap = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor=M_CHECKER)
    must(st, ap, f"approve {mtype}/{key}")


# ============================================================ 0. seed masters (config-as-data)
print("== 0. Seed MONITORING_ARTIFACT_TYPE + VENDOR_MASTER via the generic master API ==")
seed_master("MONITORING_ARTIFACT_TYPE", "CALL_MEMO", {
    "sections": [{"key": "purpose", "label": "Purpose of call"},
                 {"key": "discussion", "label": "Discussion"},
                 {"key": "actions", "label": "Action points"}],
    "requiresAuthorize": False, "vendorRfq": False})
seed_master("MONITORING_ARTIFACT_TYPE", "PLANT_VISIT", {
    "sections": [{"key": "site", "label": "Site condition"}, {"key": "utilisation", "label": "Capacity utilisation"}],
    "requiresAuthorize": False, "vendorRfq": False})
seed_master("MONITORING_ARTIFACT_TYPE", "LCR", {
    "sections": [{"key": "compliance", "label": "Compliance summary"}, {"key": "exceptions", "label": "Exceptions"}],
    "requiresAuthorize": True, "vendorRfq": False})
seed_master("MONITORING_ARTIFACT_TYPE", "QPR", {
    "sections": [{"key": "performance", "label": "Quarterly performance"}],
    "requiresAuthorize": True, "vendorRfq": False})
seed_master("MONITORING_ARTIFACT_TYPE", "BROKER_REVIEW", {
    "sections": [{"key": "review", "label": "Broker review"}], "requiresAuthorize": False, "vendorRfq": False})
seed_master("MONITORING_ARTIFACT_TYPE", "STOCK_AUDIT", {
    "sections": [{"key": "scope", "label": "Audit scope"}, {"key": "findings", "label": "Findings"}],
    "requiresAuthorize": False, "vendorRfq": True})
seed_master("MONITORING_ARTIFACT_TYPE", "AUDIT_NOTE", {
    "sections": [{"key": "note", "label": "Audit note"}], "requiresAuthorize": False, "vendorRfq": False})
VENDOR = "ACME_STOCK_AUDITORS"
seed_master("VENDOR_MASTER", VENDOR, {"name": "Acme Stock Auditors LLP", "category": "STOCK_AUDIT"})
seed_master("VENDOR_MASTER", "BETA_SURVEYORS", {"name": "Beta Surveyors Pvt Ltd", "category": "STOCK_AUDIT"})
check("masters seeded (submit+approve, maker != checker)", True)

# ============================================================ setup: a booked exposure + ECL
print("\n== 1. Setup: a full deal with a booked exposure + computed ECL (the authoritative figures) ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Kestrel Infra Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"MON-{NONCE}",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 500_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 650_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
REF = app["reference"]
call("POST", f"/origination/api/applications/{REF}/spread", {"periods": [
    {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": {"value": 4e9, "sourceDocument": "m", "confidence": 1.0},
        "COGS": {"value": 2.6e9, "sourceDocument": "m", "confidence": 1.0},
        "OPERATING_EXPENSES": {"value": 0.6e9, "sourceDocument": "m", "confidence": 1.0},
        "DEPRECIATION": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
        "INTEREST_EXPENSE": {"value": 0.12e9, "sourceDocument": "m", "confidence": 1.0},
        "TAX": {"value": 0.1e9, "sourceDocument": "m", "confidence": 1.0},
        "TOTAL_ASSETS": {"value": 5e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_ASSETS": {"value": 2e9, "sourceDocument": "m", "confidence": 1.0},
        "CASH": {"value": 0.5e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_LIABILITIES": {"value": 1.3e9, "sourceDocument": "m", "confidence": 1.0},
        "SHORT_TERM_DEBT": {"value": 0.4e9, "sourceDocument": "m", "confidence": 1.0},
        "LONG_TERM_DEBT": {"value": 1.0e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_PORTION_LTD": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
        "NET_WORTH": {"value": 2.3e9, "sourceDocument": "m", "confidence": 1.0},
        "CFO": {"value": 0.55e9, "sourceDocument": "m", "confidence": 1.0}}}]}, actor="analyst.user")
call("POST", f"/origination/api/applications/{REF}/spread/confirm", actor="analyst.user")
call("POST", f"/risk/api/risk/{REF}/rate", actor="analyst.user")
call("POST", f"/risk/api/risk/{REF}/capital", actor="analyst.user")
call("POST", f"/risk/api/risk/{REF}/pricing", actor="analyst.user")
st, _ = call("POST", f"/portfolio/api/portfolio/exposures/{REF}/register", {"daysPastDue": 0}, actor="credit.ops")
must(st, _, "register exposure")
st, ecl = call("POST", f"/portfolio/api/portfolio/exposures/{REF}/ecl", actor="credit.ops")
ecl = must(st, ecl, "compute ecl")

st, exp0 = call("GET", f"/portfolio/api/portfolio/exposures/{REF}")
exp0 = must(st, exp0, "exposure before")
st, ecl0 = call("GET", f"/portfolio/api/portfolio/exposures/{REF}/ecl/latest")
ecl0 = must(st, ecl0, "ecl before")
EXP_SNAP = json.dumps(exp0, sort_keys=True)
ECL_SNAP = json.dumps(ecl0, sort_keys=True)
check("authoritative exposure booked (EAD + grade present)",
      exp0.get("ead") is not None and exp0.get("finalGrade") is not None, str(exp0)[:120])
check("authoritative ECL computed (stage + reported provision)",
      ecl0.get("stage") is not None and ecl0.get("reportedProvision") is not None, str(ecl0)[:120])

# ============================================================ 2. CALL_MEMO create + lifecycle + SoD
print("\n== 2. CALL_MEMO: create -> submit -> review (owner->403) -> approve (reviewer->403) ==")
st, a = call("POST", "/portfolio/api/monitoring/artifacts",
             {"artifactType": "CALL_MEMO", "subjectType": "OBLIGOR", "subjectRef": REF,
              "title": "Q2 relationship call"}, actor=OWNER)
a = must(st, a, "create CALL_MEMO")
MREF = a["artifactRef"]
check("artifactRef generated MON-XXXXXX", MREF.startswith("MON-") and len(MREF) == 10, MREF)
check("starts DRAFT", a["status"] == "DRAFT", a["status"])
check("owner is the X-Actor", a["owner"] == OWNER, a["owner"])
check("masterVersion pinned (>=1)", a.get("masterVersion", 0) >= 1, a.get("masterVersion"))
check("sections materialised from the master template",
      set((a.get("sections") or {}).keys()) == {"purpose", "discussion", "actions"},
      list((a.get("sections") or {}).keys()))
check("CALL_MEMO does not require authorisation", a.get("requiresAuthorize") is False, a.get("requiresAuthorize"))

st, a = call("PUT", f"/portfolio/api/monitoring/artifacts/{MREF}/sections",
             {"sections": {"purpose": {"label": "Purpose of call", "content": "Review Q2 performance"}}}, actor=OWNER)
must(st, a, "edit sections")

st, a = call("POST", f"/portfolio/api/monitoring/artifacts/{MREF}/submit", actor=OWNER)
a = must(st, a, "submit")
check("submit -> SUBMITTED", a["status"] == "SUBMITTED", a["status"])

# SoD: reviewer must differ from owner.
st, b = call("POST", f"/portfolio/api/monitoring/artifacts/{MREF}/review", {"notes": "self"}, actor=OWNER)
check("review by owner -> 403 (SoD)", st == 403, f"HTTP {st} {b}")
st, chk = call("GET", f"/portfolio/api/monitoring/artifacts/{MREF}")
check("artifact untouched by the forbidden review", chk["artifact"]["status"] == "SUBMITTED",
      chk["artifact"]["status"])

st, a = call("POST", f"/portfolio/api/monitoring/artifacts/{MREF}/review", {"notes": "looks good"}, actor=REVIEWER)
a = must(st, a, "review by reviewer")
check("review by role-gated reviewer -> REVIEWED", a["status"] == "REVIEWED", a["status"])
check("reviewer + reviewedBy stamped", a.get("reviewer") == REVIEWER and a.get("reviewedBy") == REVIEWER, str(a.get("reviewer")))

# SoD: approver must differ from reviewer.
st, b = call("POST", f"/portfolio/api/monitoring/artifacts/{MREF}/approve", {"notes": "self"}, actor=REVIEWER)
check("approve by reviewer -> 403 (SoD)", st == 403, f"HTTP {st} {b}")
st, chk = call("GET", f"/portfolio/api/monitoring/artifacts/{MREF}")
check("artifact untouched by the forbidden approve", chk["artifact"]["status"] == "REVIEWED",
      chk["artifact"]["status"])

st, a = call("POST", f"/portfolio/api/monitoring/artifacts/{MREF}/approve", {"notes": "approved"}, actor=APPROVER)
a = must(st, a, "approve by approver")
check("approve by role-gated approver -> APPROVED", a["status"] == "APPROVED", a["status"])

# CALL_MEMO does not require authorisation -> authorize is a 409.
st, b = call("POST", f"/portfolio/api/monitoring/artifacts/{MREF}/authorize", {}, actor=AUTHORISER)
check("authorize on a non-requiresAuthorize type -> 409", st == 409, f"HTTP {st} {b}")

# ============================================================ 3. LCR reaches AUTHORIZED
print("\n== 3. LCR (requiresAuthorize=true) reaches AUTHORIZED ==")
st, l = call("POST", "/portfolio/api/monitoring/artifacts",
             {"artifactType": "LCR", "subjectType": "OBLIGOR", "subjectRef": REF, "title": "H1 LCR"}, actor=OWNER)
l = must(st, l, "create LCR")
LREF = l["artifactRef"]
check("LCR requires authorisation", l.get("requiresAuthorize") is True, l.get("requiresAuthorize"))
must(*call("POST", f"/portfolio/api/monitoring/artifacts/{LREF}/submit", actor=OWNER)[:2], "submit LCR")
must(*call("POST", f"/portfolio/api/monitoring/artifacts/{LREF}/review", {"notes": "ok"}, actor=REVIEWER)[:2], "review LCR")
must(*call("POST", f"/portfolio/api/monitoring/artifacts/{LREF}/approve", {"notes": "ok"}, actor=APPROVER)[:2], "approve LCR")
# SoD on authorise: authoriser must differ from approver.
st, b = call("POST", f"/portfolio/api/monitoring/artifacts/{LREF}/authorize", {}, actor=APPROVER)
check("authorize by approver -> 403 (SoD)", st == 403, f"HTTP {st} {b}")
st, la = call("POST", f"/portfolio/api/monitoring/artifacts/{LREF}/authorize", {"notes": "authorised"}, actor=AUTHORISER)
la = must(st, la, "authorize LCR")
check("LCR -> AUTHORIZED", la["status"] == "AUTHORIZED", la["status"])
check("authorisedBy stamped", la.get("authorisedBy") == AUTHORISER, la.get("authorisedBy"))

# ============================================================ 4. STOCK_AUDIT vendor RFQ (façade)
print("\n== 4. STOCK_AUDIT vendor RFQ -> EXTERNAL_VENDOR query + outbox façade, then external-response ==")
st, s = call("POST", "/portfolio/api/monitoring/artifacts",
             {"artifactType": "STOCK_AUDIT", "subjectType": "OBLIGOR", "subjectRef": REF, "title": "Annual stock audit"},
             actor=OWNER)
s = must(st, s, "create STOCK_AUDIT")
SREF = s["artifactRef"]
check("STOCK_AUDIT enables vendorRfq", s.get("vendorRfq") is True, s.get("vendorRfq"))

# A human must choose the vendor; an unknown vendor is rejected.
st, b = call("POST", f"/portfolio/api/monitoring/artifacts/{SREF}/vendor-rfq",
             {"vendorId": "NOT_A_VENDOR", "question": "x"}, actor=OWNER)
check("unknown vendor rejected (400)", st == 400, f"HTTP {st} {b}")

st, sv = call("POST", f"/portfolio/api/monitoring/artifacts/{SREF}/vendor-rfq",
              {"vendorId": VENDOR, "question": "Please provide the annual stock-audit report."}, actor=OWNER)
sv = must(st, sv, "vendor rfq")
QREF = sv.get("vendorQueryRef")
check("vendorRef + vendorQueryRef pinned on the artifact",
      sv.get("vendorRef") == VENDOR and str(QREF).startswith("QRY-"), f"{sv.get('vendorRef')} / {QREF}")

# A real EXTERNAL_VENDOR query row exists (in portfolio-service's own query lane). The listing
# is caller-scoped (Fix 2), so read it as the OWNER who raised the RFQ thread.
st, threads = call("GET", f"/portfolio/api/queries?subjectRef={SREF}", actor=OWNER)
must(st, threads, "list queries by subject")
qrow = [t for t in threads if t.get("queryRef") == QREF]
check("an EXTERNAL_VENDOR query row was raised for the RFQ",
      len(qrow) == 1 and qrow[0].get("channel") == "EXTERNAL_VENDOR", [t.get("queryRef") for t in threads])

# The RFI notification / outbox façade row appears (no real transport).
st, notes = call("GET", f"/portfolio/api/notifications?eventType=RFI_REQUEST&subjectRef={QREF}")
must(st, notes, "list RFI notifications")
check("an RFI notification/outbox façade row was produced", len(notes) == 1, [n.get("subjectRef") for n in notes])
check("RFI row rendered + dispatched via the notification lane",
      notes and notes[0].get("status") in ("SENT", "PENDING") and notes[0].get("renderedBody") is not None, notes)

# Fix 1: the vendor reply arrives via the tokenised external-response callback. The one-time
# token is embedded in the outbound RFI notification (the raiser's flow reads it from there).
vtoken = (notes[0].get("vars") or {}).get("responseToken") if notes else None
check("RFI notification carries the one-time response token", bool(vtoken), notes)
st, ev = call("POST", f"/portfolio/api/queries/{QREF}/external-response?token={vtoken}",
              {"body": "Stock-audit report attached; drawing power holds.", "from": VENDOR}, actor="portal.callback")
ev = must(st, ev, "external response")
check("external-response -> RESPONDED", ev["thread"]["status"] == "RESPONDED", ev["thread"]["status"])
inbound = [m for m in ev["messages"] if m.get("inbound") is True]
check("inbound vendor message appended", len(inbound) == 1 and inbound[0].get("author") == VENDOR,
      [m.get("author") for m in ev["messages"]])

# GET /{ref} surfaces the linked query status back on the artifact.
st, sview = call("GET", f"/portfolio/api/monitoring/artifacts/{SREF}")
sview = must(st, sview, "get stock-audit artifact")
check("artifact GET surfaces the linked vendor-query status",
      sview.get("vendorQuery") and sview["vendorQuery"]["thread"]["status"] == "RESPONDED",
      sview.get("vendorQuery", {}).get("thread", {}).get("status") if sview.get("vendorQuery") else None)

# ============================================================ 5. listing + audit trail
print("\n== 5. Listing + audit trail ==")
st, bysubj = call("GET", f"/portfolio/api/monitoring/artifacts?subjectRef={REF}")
must(st, bysubj, "list by subject")
check("subject listing returns CALL_MEMO + LCR + STOCK_AUDIT", len(bysubj) >= 3, len(bysubj))
st, auth = call("GET", f"/portfolio/api/monitoring/artifacts?status=AUTHORIZED")
must(st, auth, "list by status")
check("status=AUTHORIZED listing returns the LCR", any(x["artifactRef"] == LREF for x in auth),
      [x["artifactRef"] for x in auth])
st, audits = call("GET", f"/portfolio/api/audit/subject?type=MonitoringArtifact&id={MREF}")
must(st, audits, "audit subject")
types = [x.get("eventType") for x in audits]
check("MON_ARTIFACT_CREATED + REVIEWED + APPROVED audited as HUMAN",
      {"MON_ARTIFACT_CREATED", "MON_ARTIFACT_REVIEWED", "MON_ARTIFACT_APPROVED"}.issubset(set(types))
      and all(x.get("actorType") == "HUMAN" for x in audits), types)

# ============================================================ 6. the record/advisory invariant
print("\n== 6. INVARIANT: authoritative ECL / IRAC / exposure BYTE-IDENTICAL after the lifecycle ==")
st, exp1 = call("GET", f"/portfolio/api/portfolio/exposures/{REF}")
exp1 = must(st, exp1, "exposure after")
st, ecl1 = call("GET", f"/portfolio/api/portfolio/exposures/{REF}/ecl/latest")
ecl1 = must(st, ecl1, "ecl after")
check("exposure record byte-identical before/after the artifact lifecycle",
      json.dumps(exp1, sort_keys=True) == EXP_SNAP, "exposure mutated")
check("ECL / IRAC / reported provision byte-identical before/after the artifact lifecycle",
      json.dumps(ecl1, sort_keys=True) == ECL_SNAP, "ECL mutated")

print(f"\nmonitoring-artifacts e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
