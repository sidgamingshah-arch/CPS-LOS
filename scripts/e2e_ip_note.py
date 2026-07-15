#!/usr/bin/env python3
"""
In-Principle (IP) note engine — e2e through the gateway.

An IP note is a lightweight sponsorship record raised by the RM BEFORE the full
application. It routes for a named-human credit sign-off (SoD approver != raiser
+ authority-tier gate), and once APPROVED is CONVERTED into a real LoanApplication
via the existing origination application-creation path — the application carries the
originating ipNoteRef and the note is stamped with the created applicationRef.

Asserts:
  1. create -> submit -> approve happy path (IPN- ref; DRAFT -> PENDING_APPROVAL -> APPROVED).
  2. approve by the RAISER -> 403 (segregation of duties).
  3. approve by a NON-authorised actor -> 403 (authority-tier gate).
  4. convert BEFORE approve -> 409 (APPROVED-only).
  5. convert an APPROVED note -> CONVERTED; a NEW LoanApplication exists, linked both ways
     (note.applicationRef == app.reference AND app.ipNoteRef == note.ipNoteRef).
  6. reject requires a mandatory reason (blank -> 400; with reason -> REJECTED).
  7. withdraw is raiser-only, pre-approval (non-raiser -> 403; raiser -> WITHDRAWN).
  8. terminal gates: submit a CONVERTED / withdraw a REJECTED note -> 409.
  9. list / get filters; every IP_NOTE_* audit event is stamped HUMAN.
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


def new_note(actor="rm.user", **over):
    body = {
        "counterpartyId": CP_ID, "counterpartyRef": CP_REF, "counterpartyName": CP_NAME,
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "proposedAmount": 400_000_000, "currency": "INR", "tenorMonths": 60,
        "purpose": "Capex", "prospectSummary": "Long-standing relationship; strong repayment record.",
        "payload": {"collateralType": "PROPERTY", "collateralValue": 380_000_000, "secured": True},
    }
    body.update(over)
    st, n = call("POST", "/origination/api/ip-notes", body, actor=actor)
    return must(st, n, "create IP note")


print("== 0. Setup: a prospect counterparty to sponsor ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Meridian Infra Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "IPN-MI-1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "INFRASTRUCTURE", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "counterparty")
CP_ID = cp["id"]
CP_REF = cp["reference"]
CP_NAME = cp["legalName"]
print(f"    prospect {CP_REF} (id={CP_ID})")


print("\n== 1. create -> submit -> approve happy path ==")
n = new_note(actor="rm.user")
ref = n["ipNoteRef"]
check("created DRAFT with IPN- ref", n["status"] == "DRAFT" and ref.startswith("IPN-"), str(n))
check("prospect + proposed structure captured",
      n["counterpartyRef"] == CP_REF and n["proposedAmount"] == 400_000_000
      and n["facilityType"] == "TERM_LOAN", str(n))
st, sub = call("POST", f"/origination/api/ip-notes/{ref}/submit", actor="rm.user")
sub = must(st, sub, "submit")
check("submit -> PENDING_APPROVAL", sub["status"] == "PENDING_APPROVAL", str(sub))


print("\n== 2/3. SoD + authority gates on approve ==")
st, b = call("POST", f"/origination/api/ip-notes/{ref}/approve", {}, actor="rm.user")
check("approve by the RAISER -> 403 (SoD)", st == 403, f"{st} {b}")
st, b = call("POST", f"/origination/api/ip-notes/{ref}/approve", {}, actor="analyst.user")
check("approve by NON-authorised (ANALYST) -> 403 (authority gate)", st == 403, f"{st} {b}")
st, appd = call("POST", f"/origination/api/ip-notes/{ref}/approve",
                {"note": "credit sign-off — proceed to full application"}, actor="credit.officer")
appd = must(st, appd, "approve")
check("approved by CREDIT_OFFICER -> APPROVED", appd["status"] == "APPROVED"
      and appd["decidedBy"] == "credit.officer", str(appd))


print("\n== 4. convert BEFORE approve -> 409 (APPROVED-only) ==")
p = new_note(actor="rm.user")
pref = p["ipNoteRef"]
call("POST", f"/origination/api/ip-notes/{pref}/submit", actor="rm.user")
st, b = call("POST", f"/origination/api/ip-notes/{pref}/convert", {}, actor="credit.officer")
check("convert a PENDING_APPROVAL note -> 409", st == 409, f"{st} {b}")


print("\n== 5. convert an APPROVED note -> a NEW LoanApplication, linked both ways ==")
st, conv = call("POST", f"/origination/api/ip-notes/{ref}/convert", {}, actor="credit.officer")
conv = must(st, conv, "convert")
app_ref = conv.get("applicationRef")
check("convert -> CONVERTED with applicationRef set",
      conv["status"] == "CONVERTED" and bool(app_ref), str(conv))
st, app = call("GET", f"/origination/api/applications/{app_ref}")
app = must(st, app, "get created application")
check("created application exists and carries the ipNoteRef back-link",
      app["reference"] == app_ref and app["ipNoteRef"] == ref, str(app))
check("application inherits the proposed structure verbatim",
      app["requestedAmount"] == 400_000_000 and app["facilityType"] == "TERM_LOAN"
      and app["counterpartyRef"] == CP_REF, str(app))
st, b = call("POST", f"/origination/api/ip-notes/{ref}/convert", {}, actor="credit.officer")
check("re-convert a CONVERTED note -> 409 (idempotent guard)", st == 409, f"{st} {b}")


print("\n== 6. reject requires a mandatory reason ==")
r = new_note(actor="rm.user")
rref = r["ipNoteRef"]
call("POST", f"/origination/api/ip-notes/{rref}/submit", actor="rm.user")
st, b = call("POST", f"/origination/api/ip-notes/{rref}/reject", {"reason": "  "}, actor="credit.officer")
check("reject with blank reason -> 400", st == 400, f"{st} {b}")
st, rj = call("POST", f"/origination/api/ip-notes/{rref}/reject",
              {"reason": "insufficient covenant headroom for the ask"}, actor="credit.officer")
check("reject with a reason -> REJECTED", st == 200 and rj["status"] == "REJECTED", f"{st} {rj}")


print("\n== 7. withdraw is raiser-only, pre-approval ==")
w = new_note(actor="rm.user")
wref = w["ipNoteRef"]
call("POST", f"/origination/api/ip-notes/{wref}/submit", actor="rm.user")
st, b = call("POST", f"/origination/api/ip-notes/{wref}/withdraw", {}, actor="credit.officer")
check("withdraw by a non-raiser -> 403", st == 403, f"{st} {b}")
st, wd = call("POST", f"/origination/api/ip-notes/{wref}/withdraw", {}, actor="rm.user")
check("withdraw by the raiser -> WITHDRAWN", st == 200 and wd["status"] == "WITHDRAWN", f"{st} {wd}")


print("\n== 8. terminal gates ==")
st, b = call("POST", f"/origination/api/ip-notes/{ref}/submit", actor="rm.user")
check("submit a CONVERTED note -> 409", st == 409, f"{st} {b}")
st, b = call("POST", f"/origination/api/ip-notes/{rref}/withdraw", {}, actor="rm.user")
check("withdraw a REJECTED note -> 409", st == 409, f"{st} {b}")


print("\n== 9. list / get filters + audit provenance ==")
st, mine = call("GET", f"/origination/api/ip-notes?counterpartyRef={CP_REF}")
mine = must(st, mine, "list by counterparty")
check("all IP notes for the prospect are listed (>=4)", len(mine) >= 4, str(len(mine)))
st, conv_only = call("GET", f"/origination/api/ip-notes?counterpartyRef={CP_REF}&status=CONVERTED")
check("status filter returns only CONVERTED", st == 200 and len(conv_only) >= 1
      and all(x["status"] == "CONVERTED" for x in conv_only), str(conv_only))
st, one = call("GET", f"/origination/api/ip-notes/{ref}")
check("get by ref returns the record", st == 200 and one["ipNoteRef"] == ref, str(one))

st, audit_rows = call("GET", "/origination/api/audit")
ipn_events = [a for a in (audit_rows or []) if str(a.get("eventType", "")).startswith("IP_NOTE_")]
check("IP_NOTE_* audit events exist and are all stamped HUMAN",
      len(ipn_events) >= 1 and all(a.get("actorType") == "HUMAN" for a in ipn_events),
      f"{len(ipn_events)} events")


print(f"\n== IP-note e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
