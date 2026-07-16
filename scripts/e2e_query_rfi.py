#!/usr/bin/env python3
"""
Query / RFI collaboration lane — e2e.

helix-common gained a governed query/RFI module that is auto-exposed on every service exactly
like /api/audit and /api/notifications (this suite drives it through decision-service's shared
surface at /decision/api/queries). One QueryThread + an append-only QueryMessage log, with:
  - an INTERNAL lane (in-app inbox) and EXTERNAL_CUSTOMER / EXTERNAL_VENDOR lanes that dispatch
    an RFI through the governed notification façade (no real transport — the outbox row is it);
  - a lifecycle SCHEDULED -> OPEN -> RESPONDED -> RESOLVED (+ CANCELLED), every write audited;
  - maker-checker on resolve: only the raiser may resolve, an addressee self-resolve is a 403
    forbiddenAutonomy;
  - schedule-later via the SAME notification sweep (no competing scheduler).

Proves:
  - raise INTERNAL -> OPEN, first message logged, QUERY_RAISED audit stamped;
  - reply -> RESPONDED (message appended);
  - addressee self-resolve -> 403 (SoD), thread untouched;
  - raiser resolves -> RESOLVED (+ resolution fields, QUERY_RESOLVED audit);
  - raise EXTERNAL_VENDOR -> OPEN and an RFI notification/outbox row is produced (façade);
  - external-response appends an INBOUND message (author type EXTERNAL, never HUMAN) and flips
    the thread to RESPONDED;
  - external-response on an INTERNAL thread is REJECTED (4xx) and leaves the thread untouched
    (forgery hardening — a fake inbound "HUMAN" reply can't flip an internal thread);
  - a schedule-later query starts SCHEDULED (dispatch deferred to the sweep).
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
SVC = "/decision"
PASS, FAIL = 0, 0
NONCE = str(int(time.time()))

RAISER = "rm.alpha." + NONCE
ADDRESSEE = "analyst.beta." + NONCE


def call(method, path, body=None, actor="ops.admin"):
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


print("== 1. raise an INTERNAL query -> OPEN, first message logged")
raise_body = {"channel": "INTERNAL", "subjectType": "Application", "subjectRef": f"APP-{NONCE}",
              "topic": "Missing FY24 audited financials",
              "question": "Please upload the FY24 audited financials for the borrower.",
              "addressee": ADDRESSEE, "addresseeRole": "credit.analyst", "slaHours": 48}
st, v = call("POST", f"{SVC}/api/queries", raise_body, actor=RAISER)
v = must(st, v, "raise internal")
thread = v["thread"]
ref = thread["queryRef"]
check("queryRef generated QRY-XXXXXX", str(ref).startswith("QRY-") and len(ref) == 10, ref)
check("internal query starts OPEN", thread["status"] == "OPEN", thread["status"])
check("raisedBy is the X-Actor", thread["raisedBy"] == RAISER, thread["raisedBy"])
check("dueAt computed from slaHours", thread.get("dueAt") is not None)
check("first message is the question (HUMAN, not inbound)",
      len(v["messages"]) == 1 and v["messages"][0]["authorType"] == "HUMAN"
      and v["messages"][0]["inbound"] is False, v["messages"])

print("== 2. reply -> RESPONDED (message appended)")
st, v = call("POST", f"{SVC}/api/queries/{ref}/reply", {"body": "Uploading now, give me an hour."},
             actor=ADDRESSEE)
v = must(st, v, "reply")
check("status flips to RESPONDED", v["thread"]["status"] == "RESPONDED", v["thread"]["status"])
check("reply appended to the message log", len(v["messages"]) == 2, len(v["messages"]))

print("== 3. SoD: addressee self-resolve is forbidden (403)")
st, b = call("POST", f"{SVC}/api/queries/{ref}/resolve", {"resolution": "done"}, actor=ADDRESSEE)
check("addressee self-resolve -> 403 forbiddenAutonomy", st == 403, f"HTTP {st} {b}")
st, v = call("GET", f"{SVC}/api/queries/{ref}")
v = must(st, v, "get after forbidden resolve")
check("thread NOT resolved by the forbidden attempt", v["thread"]["status"] == "RESPONDED",
      v["thread"]["status"])

print("== 4. raiser resolves -> RESOLVED (+ resolution fields)")
st, v = call("POST", f"{SVC}/api/queries/{ref}/resolve",
             {"resolution": "Financials received and filed."}, actor=RAISER)
v = must(st, v, "resolve by raiser")
check("raiser resolve -> RESOLVED", v["thread"]["status"] == "RESOLVED", v["thread"]["status"])
check("resolvedBy stamped", v["thread"]["resolvedBy"] == RAISER, v["thread"].get("resolvedBy"))
check("resolvedAt stamped", v["thread"].get("resolvedAt") is not None)
check("resolution text recorded", v["thread"].get("resolution") == "Financials received and filed.")

print("== 5. resolve audit: QUERY_RAISED + QUERY_RESOLVED on the thread subject")
st, audits = call("GET", f"{SVC}/api/audit/subject?type=QueryThread&id={ref}")
must(st, audits, "audit subject")
types = [a.get("eventType") for a in audits]
check("QUERY_RAISED audit stamped", "QUERY_RAISED" in types, types)
check("QUERY_RESOLVED audit stamped", "QUERY_RESOLVED" in types, types)
resolved_audit = [a for a in audits if a.get("eventType") == "QUERY_RESOLVED"]
check("resolve audit is a HUMAN action", resolved_audit and resolved_audit[0].get("actorType") == "HUMAN",
      resolved_audit[0].get("actorType") if resolved_audit else "none")

print("== 6. EXTERNAL_VENDOR raise dispatches an RFI notification (façade, no real transport)")
ext_body = {"channel": "EXTERNAL_VENDOR", "subjectType": "Collateral", "subjectRef": f"COL-{NONCE}",
            "topic": "Valuation certificate", "question": "Please send the latest valuation certificate.",
            "addresseeRole": "vendor.valuer", "recipientRoles": ["vendor.valuer"]}
st, v = call("POST", f"{SVC}/api/queries", ext_body, actor=RAISER)
v = must(st, v, "raise external vendor")
ext_ref = v["thread"]["queryRef"]
check("external query starts OPEN", v["thread"]["status"] == "OPEN", v["thread"]["status"])
st, notes = call("GET", f"{SVC}/api/notifications?eventType=RFI_REQUEST")
must(st, notes, "list RFI notifications")
rfi = [n for n in notes if n.get("subjectRef") == ext_ref]
check("an RFI notification/outbox row was produced for the external query", len(rfi) == 1,
      [n.get("subjectRef") for n in notes])
check("RFI row rendered + dispatched via a transport", rfi and rfi[0].get("status") in ("SENT", "PENDING")
      and rfi[0].get("renderedBody") is not None, rfi)

print("== 7. external-response callback appends an INBOUND message + RESPONDED")
st, v = call("POST", f"{SVC}/api/queries/{ext_ref}/external-response",
             {"body": "Certificate attached, valid to 2027.", "from": "acme.valuers"}, actor="portal.callback")
v = must(st, v, "external response")
check("external response -> RESPONDED", v["thread"]["status"] == "RESPONDED", v["thread"]["status"])
inbound = [m for m in v["messages"] if m.get("inbound") is True]
check("an inbound message was appended", len(inbound) == 1, v["messages"])
check("inbound message carries the sender", inbound and inbound[0].get("author") == "acme.valuers",
      inbound[0].get("author") if inbound else "none")
# Fix 1: a forged/callback inbound reply is stamped EXTERNAL, never HUMAN — it is not a
# named-human action inside the bank, so it can never be recorded as one.
check("inbound message author type is EXTERNAL (never HUMAN)",
      inbound and inbound[0].get("authorType") == "EXTERNAL",
      inbound[0].get("authorType") if inbound else "none")

print("== 7b. external-response is REJECTED on an INTERNAL thread (forgery hardening)")
int_body = {"channel": "INTERNAL", "subjectType": "Application", "subjectRef": f"APP-INT-{NONCE}",
            "topic": "Internal note", "question": "Internal-only question.",
            "addressee": ADDRESSEE, "addresseeRole": "credit.analyst"}
st, iv = call("POST", f"{SVC}/api/queries", int_body, actor=RAISER)
iv = must(st, iv, "raise internal for forgery check")
int_ref = iv["thread"]["queryRef"]
st, b = call("POST", f"{SVC}/api/queries/{int_ref}/external-response",
             {"body": "forged inbound reply", "from": "attacker"}, actor="attacker")
check("external-response on an INTERNAL thread is rejected (4xx)", 400 <= st < 500, f"HTTP {st} {b}")
st, iv2 = call("GET", f"{SVC}/api/queries/{int_ref}")
iv2 = must(st, iv2, "get internal after forged external-response")
check("INTERNAL thread untouched by the forged call (still OPEN)",
      iv2["thread"]["status"] == "OPEN", iv2["thread"]["status"])
check("no forged inbound message appended to the INTERNAL thread",
      all(m.get("inbound") is False for m in iv2["messages"]), iv2["messages"])

print("== 8. schedule-later query starts SCHEDULED (dispatch deferred to the sweep)")
sched_body = {"channel": "EXTERNAL_CUSTOMER", "subjectType": "Application", "subjectRef": f"APP-S-{NONCE}",
              "topic": "Scheduled reminder", "question": "Kindly confirm the board resolution.",
              "addresseeRole": "customer.contact", "scheduleInSeconds": 3600}
st, v = call("POST", f"{SVC}/api/queries", sched_body, actor=RAISER)
v = must(st, v, "raise scheduled")
sched_ref = v["thread"]["queryRef"]
check("scheduled query starts SCHEDULED", v["thread"]["status"] == "SCHEDULED", v["thread"]["status"])
check("scheduleAt recorded", v["thread"].get("scheduleAt") is not None)
# No RFI dispatched yet for a scheduled thread (deferred to the platform sweep).
st, notes = call("GET", f"{SVC}/api/notifications?eventType=RFI_REQUEST")
must(st, notes, "list RFI notifications (scheduled)")
check("no RFI dispatched for the scheduled thread yet",
      len([n for n in notes if n.get("subjectRef") == sched_ref]) == 0)

print("== 9. inbox + subject listing")
st, by_addr = call("GET", f"{SVC}/api/queries?addressee={ADDRESSEE}")
must(st, by_addr, "list by addressee")
check("addressee inbox returns the internal thread", any(t.get("queryRef") == ref for t in by_addr))
st, by_subj = call("GET", f"{SVC}/api/queries?subjectRef={ext_body['subjectRef']}")
must(st, by_subj, "list by subjectRef")
check("subjectRef listing returns the external thread",
      any(t.get("queryRef") == ext_ref for t in by_subj))

print(f"\nquery/RFI e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
