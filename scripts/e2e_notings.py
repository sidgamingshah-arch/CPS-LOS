#!/usr/bin/env python3
"""
Noting engine (governed decision RECORDS) — e2e through the gateway.

A Noting (TOD/intraday excess, CAM note, product paper, deferral extension/waiver/
closure, second-stage disbursement, SRM renewal) routes for approval per its
NOTING_TYPE master (config-as-data — DoA or fixed-role), captures a named human
approval + optional CAD authorisation, and can be rejected / reversed / withdrawn.

Asserts:
  1. create -> submit -> approve happy path (fixed-role CREDIT_OFFICER).
  2. approve by the RAISER -> 403 (segregation of duties).
  3. approve by a NON-AUTHORISED actor -> 403 (role gate).
  4. a cadRequired type goes PENDING_CAD, then cad-authorize -> AUTHORIZED;
     cad-authorize by a non-CAD actor -> 403.
  5. reject needs a reason (blank -> 400; with reason -> REJECTED).
  6. reverse an APPROVED noting (blank -> 400; non-authority -> 403; -> REVERSED).
  7. withdraw is raiser-only (non-raiser -> 403; raiser -> WITHDRAWN).
  8. DOA-routed type: rank gate (analyst -> 403; board-rank actor -> APPROVED).
  9. CRUCIAL invariant — a linked authoritative figure (the subject's origination
     facility amount AND its risk summary) is BYTE-IDENTICAL before and after the
     full noting lifecycle. A noting is a RECORD, never a figure mutation.
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


def canon(o):
    return json.dumps(o, sort_keys=True)


# ---- NOTING_TYPE master seeding (config-as-data; maker != checker for SoD) ----
def seed_type(key, payload):
    st, rec = call("POST", "/config/api/masters/NOTING_TYPE",
                   {"recordKey": key, "payload": payload}, actor="master.maker")
    rec = must(st, rec, f"submit NOTING_TYPE {key}")
    st, _ = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="master.checker")
    must(st, _, f"approve NOTING_TYPE {key}")


print("== 0. Seed the NOTING_TYPE master rows (generic master API — NO code change) ==")
FIELDS = ["excessAmount", "tenorDays", "justification"]
seed_type("TOD_INTRADAY", {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
                           "cadRequired": False, "fields": FIELDS})
seed_type("CAM_NOTE", {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
                       "cadRequired": False, "fields": ["reviewPeriod", "summary"]})
seed_type("PRODUCT_PAPER", {"routing": "DOA", "cadRequired": False,
                            "fields": ["product", "riskAssessment"]})
seed_type("DEFERRAL_EXTENSION", {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
                                 "cadRequired": False, "fields": ["docRef", "newDate"]})
seed_type("DEFERRAL_WAIVER", {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
                              "cadRequired": True, "fields": ["docRef", "reason"]})
seed_type("DEFERRAL_CLOSURE", {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
                               "cadRequired": False, "fields": ["docRef"]})
seed_type("DISB_SECOND_STAGE", {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
                                "cadRequired": True, "fields": ["milestone", "amount"]})
seed_type("SRM_RENEWAL", {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
                          "cadRequired": False, "fields": ["renewalDate"]})
print("    8 NOTING_TYPE rows ACTIVE")


print("\n== 1. Setup: a rated deal — capture the authoritative figures BEFORE any noting ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Orion Textiles Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "NTG-OT-1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 400_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 380_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
ref = app["reference"]
call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": {"value": 3e9, "sourceDocument": "m", "confidence": 1.0},
        "COGS": {"value": 2.0e9, "sourceDocument": "m", "confidence": 1.0},
        "OPERATING_EXPENSES": {"value": 0.5e9, "sourceDocument": "m", "confidence": 1.0},
        "DEPRECIATION": {"value": 0.1e9, "sourceDocument": "m", "confidence": 1.0},
        "INTEREST_EXPENSE": {"value": 0.1e9, "sourceDocument": "m", "confidence": 1.0},
        "TAX": {"value": 0.08e9, "sourceDocument": "m", "confidence": 1.0},
        "TOTAL_ASSETS": {"value": 4e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_ASSETS": {"value": 1.6e9, "sourceDocument": "m", "confidence": 1.0},
        "CASH": {"value": 0.4e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_LIABILITIES": {"value": 1.0e9, "sourceDocument": "m", "confidence": 1.0},
        "SHORT_TERM_DEBT": {"value": 0.3e9, "sourceDocument": "m", "confidence": 1.0},
        "LONG_TERM_DEBT": {"value": 0.8e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_PORTION_LTD": {"value": 0.1e9, "sourceDocument": "m", "confidence": 1.0},
        "NET_WORTH": {"value": 1.9e9, "sourceDocument": "m", "confidence": 1.0},
        "CFO": {"value": 0.45e9, "sourceDocument": "m", "confidence": 1.0}}}]},
     actor="analyst.user")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
must(*call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user"), "rate")

st, facs_before = call("GET", f"/origination/api/applications/{ref}/facilities")
facs_before = must(st, facs_before, "facilities before")
st, risk_before = call("GET", f"/risk/api/risk/{ref}")
risk_before = must(st, risk_before, "risk summary before")
FIG_BEFORE = canon({"facilities": facs_before, "risk": risk_before})
print(f"    deal {ref} rated; grade={risk_before.get('rating', {}).get('finalGrade')} "
      f"facility={facs_before[0]['amount']:.0f} — figures snapshotted")


print("\n== 2. create -> submit -> approve (CAM_NOTE, fixed-role CREDIT_OFFICER) ==")
st, n = call("POST", "/decision/api/notings", {
    "notingType": "CAM_NOTE", "subjectType": "Application", "subjectRef": ref,
    "title": "Annual CAM note", "narrative": "Credit assessment memo for annual review.",
    "payload": {"reviewPeriod": "FY2025"}}, actor="rm.user")
n = must(st, n, "create CAM_NOTE")
nref = n["notingRef"]
check("created DRAFT with NTG- ref", n["status"] == "DRAFT" and nref.startswith("NTG-"), str(n))
st, sub = call("POST", f"/decision/api/notings/{nref}/submit", actor="rm.user")
sub = must(st, sub, "submit")
check("submit -> PENDING_APPROVAL, routed FIXED_ROLE CREDIT_OFFICER",
      sub["status"] == "PENDING_APPROVAL" and sub["routing"] == "FIXED_ROLE"
      and sub["approverRole"] == "CREDIT_OFFICER", str(sub))
# (SoD + role gates asserted in §3 before the real approval below)
st, appd = call("POST", f"/decision/api/notings/{nref}/approve",
                {"note": "reviewed, approved"}, actor="credit.officer")
appd = must(st, appd, "approve")
check("approved by CREDIT_OFFICER -> APPROVED", appd["status"] == "APPROVED"
      and appd["approver"] == "credit.officer", str(appd))
CAM_APPROVED = nref   # reused in §6 (reverse)


print("\n== 3. SoD + role gates on approve ==")
st, n2 = call("POST", "/decision/api/notings", {
    "notingType": "CAM_NOTE", "subjectRef": ref, "title": "CAM note (gate probe)"}, actor="rm.user")
n2 = must(st, n2, "create gate-probe")
n2ref = n2["notingRef"]
call("POST", f"/decision/api/notings/{n2ref}/submit", actor="rm.user")
st, b = call("POST", f"/decision/api/notings/{n2ref}/approve", {}, actor="rm.user")
check("approve by the RAISER -> 403 (SoD)", st == 403, f"{st} {b}")
st, b = call("POST", f"/decision/api/notings/{n2ref}/approve", {}, actor="analyst.user")
check("approve by NON-authorised (ANALYST) -> 403 (role gate)", st == 403, f"{st} {b}")
st, ok = call("POST", f"/decision/api/notings/{n2ref}/approve", {}, actor="credit.officer")
check("then a real CREDIT_OFFICER approves", st == 200 and ok["status"] == "APPROVED", f"{st} {ok}")


print("\n== 4. cadRequired type -> PENDING_CAD -> cad-authorize -> AUTHORIZED ==")
st, d = call("POST", "/decision/api/notings", {
    "notingType": "DISB_SECOND_STAGE", "subjectRef": ref, "title": "Tranche-2 milestone note",
    "payload": {"milestone": "civil works 60%", "amount": 120_000_000}}, actor="rm.user")
d = must(st, d, "create DISB_SECOND_STAGE")
dref = d["notingRef"]
st, ds = call("POST", f"/decision/api/notings/{dref}/submit", actor="rm.user")
check("cadRequired flag resolved from master", ds["cadRequired"] is True, str(ds))
st, da = call("POST", f"/decision/api/notings/{dref}/approve", {"note": "credit ok"}, actor="credit.officer")
check("approve of a cadRequired type -> PENDING_CAD", st == 200 and da["status"] == "PENDING_CAD", f"{st} {da}")
st, b = call("POST", f"/decision/api/notings/{dref}/cad-authorize", {}, actor="rm.user")
check("cad-authorize by a non-CAD actor -> 403", st == 403, f"{st} {b}")
st, dz = call("POST", f"/decision/api/notings/{dref}/cad-authorize",
              {"note": "docs perfected"}, actor="cad.maker")
check("cad-authorize by CAD_OPS -> AUTHORIZED", st == 200 and dz["status"] == "AUTHORIZED"
      and dz["authorisedBy"] == "cad.maker", f"{st} {dz}")


print("\n== 5. reject requires a mandatory reason ==")
st, r = call("POST", "/decision/api/notings", {
    "notingType": "DEFERRAL_EXTENSION", "subjectRef": ref, "title": "Defer valuation"}, actor="rm.user")
r = must(st, r, "create for reject")
rref = r["notingRef"]
call("POST", f"/decision/api/notings/{rref}/submit", actor="rm.user")
st, b = call("POST", f"/decision/api/notings/{rref}/reject", {"reason": "  "}, actor="credit.officer")
check("reject with blank reason -> 400", st == 400, f"{st} {b}")
st, rj = call("POST", f"/decision/api/notings/{rref}/reject",
              {"reason": "valuation must be current for renewal"}, actor="credit.officer")
check("reject with a reason -> REJECTED", st == 200 and rj["status"] == "REJECTED", f"{st} {rj}")


print("\n== 6. reverse an APPROVED noting (reason + role gated) ==")
st, b = call("POST", f"/decision/api/notings/{CAM_APPROVED}/reverse", {"reason": ""}, actor="credit.officer")
check("reverse with blank reason -> 400", st == 400, f"{st} {b}")
st, b = call("POST", f"/decision/api/notings/{CAM_APPROVED}/reverse",
             {"reason": "superseded"}, actor="loan.ops")
check("reverse by a non-authority actor -> 403", st == 403, f"{st} {b}")
st, rv = call("POST", f"/decision/api/notings/{CAM_APPROVED}/reverse",
              {"reason": "superseded by revised CAM"}, actor="credit.officer")
check("reverse by CREDIT_OFFICER -> REVERSED", st == 200 and rv["status"] == "REVERSED", f"{st} {rv}")


print("\n== 7. withdraw is raiser-only, pre-approval ==")
st, w = call("POST", "/decision/api/notings", {
    "notingType": "TOD_INTRADAY", "subjectRef": ref, "title": "Intraday excess note",
    "payload": {"excessAmount": 5_000_000, "tenorDays": 1}}, actor="rm.user")
w = must(st, w, "create for withdraw")
wref = w["notingRef"]
call("POST", f"/decision/api/notings/{wref}/submit", actor="rm.user")
st, b = call("POST", f"/decision/api/notings/{wref}/withdraw", {}, actor="credit.officer")
check("withdraw by a non-raiser -> 403", st == 403, f"{st} {b}")
st, wd = call("POST", f"/decision/api/notings/{wref}/withdraw", {}, actor="rm.user")
check("withdraw by the raiser -> WITHDRAWN", st == 200 and wd["status"] == "WITHDRAWN", f"{st} {wd}")


print("\n== 8. DOA-routed type: rank gate (PRODUCT_PAPER) ==")
st, p = call("POST", "/decision/api/notings", {
    "notingType": "PRODUCT_PAPER", "subjectRef": ref, "title": "New structured-trade product",
    "payload": {"amount": 300_000_000, "grade": "BBB", "jurisdiction": "IN-RBI"}}, actor="rm.user")
p = must(st, p, "create PRODUCT_PAPER")
pref = p["notingRef"]
st, ps = call("POST", f"/decision/api/notings/{pref}/submit", actor="rm.user")
check("DOA routing resolved to a ladder authority",
      ps["routing"] == "DOA" and ps["approverRole"] in
      ("RM_HEAD", "CREDIT_OFFICER", "CREDIT_COMMITTEE", "BOARD_COMMITTEE"), str(ps))
st, b = call("POST", f"/decision/api/notings/{pref}/approve", {}, actor="analyst.user")
check("DOA approve by insufficient rank (ANALYST) -> 403", st == 403, f"{st} {b}")
st, pz = call("POST", f"/decision/api/notings/{pref}/approve", {"note": "board ok"}, actor="cro")
check("DOA approve by board-rank actor (cro) -> APPROVED",
      st == 200 and pz["status"] == "APPROVED", f"{st} {pz}")


print("\n== 9. list / get filters ==")
st, mine = call("GET", f"/decision/api/notings?subjectRef={ref}")
mine = must(st, mine, "list by subject")
check("all notings for the subject are listed (>=6)", len(mine) >= 6, str(len(mine)))
st, rejd = call("GET", f"/decision/api/notings?subjectRef={ref}&status=REJECTED")
check("status filter returns only REJECTED", st == 200 and all(x["status"] == "REJECTED" for x in rejd)
      and len(rejd) >= 1, str(rejd))
st, tod = call("GET", "/decision/api/notings?type=TOD_INTRADAY")
check("type filter returns only TOD_INTRADAY", st == 200 and all(x["notingType"] == "TOD_INTRADAY" for x in tod),
      str(len(tod) if tod else 0))
st, one = call("GET", f"/decision/api/notings/{CAM_APPROVED}")
check("get by ref returns the record", st == 200 and one["notingRef"] == CAM_APPROVED, str(one))


print("\n== 10. INVARIANT: the authoritative figures are BYTE-IDENTICAL after the full lifecycle ==")
st, facs_after = call("GET", f"/origination/api/applications/{ref}/facilities")
facs_after = must(st, facs_after, "facilities after")
st, risk_after = call("GET", f"/risk/api/risk/{ref}")
risk_after = must(st, risk_after, "risk summary after")
FIG_AFTER = canon({"facilities": facs_after, "risk": risk_after})
check("origination facility + risk summary unchanged (record, not mutation)",
      FIG_AFTER == FIG_BEFORE,
      "figures diverged — a noting mutated an authoritative figure!")

st, audit_rows = call("GET", "/decision/api/audit")
check("every NOTING_* audit event is stamped HUMAN (no AI/SYSTEM figure path)",
      all(a.get("actorType") == "HUMAN" for a in (audit_rows or [])
          if str(a.get("eventType", "")).startswith("NOTING_")), "")


print(f"\n== Notings e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
