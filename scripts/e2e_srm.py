#!/usr/bin/env python3
"""
SRM — Structured Review / renewal (CLoM Wave 2) — e2e through the gateway.

SRM is a renewal decision built ON the existing Noting engine (no new engine): creating
an SRM review materialises a checklist from the SRM_CHECKLIST master (config-as-data) and
raises a linked NOTING_TYPE=SRM_RENEWAL noting via NotingService (in-process). The governed
approval runs through /api/notings. When that noting reaches AUTHORIZED, a minimal, additive
MER hook advances the subject's next review / renewal due date.

Asserts:
  0. seed SRM_CHECKLIST + SRM_RENEWAL (cadRequired) masters via the generic master API.
  1. set up the MER register: a RENEWAL_REVIEW for the subject (should advance), a
     CONDITION_SUBSEQUENT for the same subject and a RENEWAL_REVIEW for a DIFFERENT subject
     (both must stay unchanged — proving the hook is type- and subject-scoped).
  2. create SRM review -> checklist materialised from the master + a linked SRM_RENEWAL
     noting created in DRAFT.
  3. mark a checklist item -> persisted on the review.
  4. submit the renewal noting (delegated to the Noting engine) -> PENDING_APPROVAL,
     cadRequired resolved from the master.
  5. SoD/role gates on the noting: approve by the raiser -> 403; by a non-authority -> 403.
  6. drive the noting to AUTHORIZED via /api/notings (approve -> PENDING_CAD -> cad-authorize).
  7. refresh the SRM -> COMPLETED; MER RENEWAL_REVIEW ADVANCED for the subject.
  8. the non-SRM MER entries are BYTE-IDENTICAL (type + subject scoping proven).
  9. idempotent: a second refresh does NOT advance the date again.
 10. every SRM_* and the MER_RENEWAL_ADVANCED audit event is stamped HUMAN.
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GW", "http://localhost:8080")
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


def seed_master(master_type, key, payload):
    """Maker submits, a different checker approves (SoD). Supersede semantics make this idempotent."""
    st, rec = call("POST", f"/config/api/masters/{master_type}",
                   {"recordKey": key, "payload": payload}, actor="master.maker")
    rec = must(st, rec, f"submit {master_type} {key}")
    st, _ = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="master.checker")
    must(st, _, f"approve {master_type} {key}")


def plus_one_year(iso):
    y, m, d = iso.split("-")
    return f"{int(y) + 1:04d}-{m}-{d}"


def mer_items(reference):
    st, rows = call("GET", f"/decision/api/mer/{reference}")
    return must(st, rows, f"list MER for {reference}")


def find_item(rows, item_type):
    return next((m for m in rows if m["itemType"] == item_type), None)


SUBJECT = "SRM-SUBJ-A"
OTHER = "SRM-SUBJ-B"

print("== 0. Seed SRM_CHECKLIST + SRM_RENEWAL masters (generic master API — NO code change) ==")
CHECKLIST_ITEMS = [
    "Updated audited financials obtained",
    "Account conduct reviewed",
    "Covenant compliance certified",
    "Security & insurance current",
]
seed_master("SRM_CHECKLIST", "STANDARD", {"items": CHECKLIST_ITEMS})
# SRM_RENEWAL requires CAD authorisation so the noting can reach AUTHORIZED (config-as-data).
seed_master("NOTING_TYPE", "SRM_RENEWAL",
            {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER",
             "cadRequired": True, "fields": ["renewalDate"]})
print("    SRM_CHECKLIST/STANDARD + NOTING_TYPE/SRM_RENEWAL(cadRequired) ACTIVE")


print("\n== 1. Seed the MER register (renewal to advance + two controls that must NOT move) ==")
st, review_a = call("POST", "/decision/api/mer/raise", {
    "applicationRef": SUBJECT, "counterpartyName": "Helios Cements Ltd", "itemType": "RENEWAL_REVIEW",
    "category": "RENEWAL", "description": "Annual facility review", "criticality": "HIGH",
    "dueDate": "2026-09-01", "recurring": True, "renewalFrequency": "ANNUAL", "owner": "rm.alice"},
    actor="rm.alice")
review_a = must(st, review_a, "raise RENEWAL_REVIEW for subject")
DUE_BEFORE = review_a["dueDate"]
check("subject RENEWAL_REVIEW seeded (recurring, ANNUAL)",
      review_a["itemType"] == "RENEWAL_REVIEW" and DUE_BEFORE == "2026-09-01", str(review_a))

st, cond_a = call("POST", "/decision/api/mer/raise", {
    "applicationRef": SUBJECT, "counterpartyName": "Helios Cements Ltd", "itemType": "CONDITION_SUBSEQUENT",
    "category": "CONDITION", "description": "Post-sanction CP: mortgage perfection", "criticality": "MEDIUM",
    "dueDate": "2026-10-15", "recurring": False, "owner": "rm.alice"}, actor="rm.alice")
cond_a = must(st, cond_a, "raise CONDITION_SUBSEQUENT for subject")
COND_DUE_BEFORE = cond_a["dueDate"]

st, review_b = call("POST", "/decision/api/mer/raise", {
    "applicationRef": OTHER, "counterpartyName": "Nimbus Logistics Ltd", "itemType": "RENEWAL_REVIEW",
    "category": "RENEWAL", "description": "Annual facility review", "criticality": "HIGH",
    "dueDate": "2026-09-01", "recurring": True, "renewalFrequency": "ANNUAL", "owner": "rm.bob"},
    actor="rm.bob")
review_b = must(st, review_b, "raise RENEWAL_REVIEW for a different subject")
OTHER_DUE_BEFORE = review_b["dueDate"]
print(f"    review(A)={DUE_BEFORE}  condition(A)={COND_DUE_BEFORE}  review(B,other)={OTHER_DUE_BEFORE}")


print("\n== 2. Create the SRM review -> checklist materialised + SRM_RENEWAL noting raised (DRAFT) ==")
st, srm = call("POST", "/decision/api/srm/reviews", {
    "subjectType": "Counterparty", "subjectRef": SUBJECT, "counterpartyName": "Helios Cements Ltd",
    "checklistKey": "STANDARD", "title": "FY2026 structured review"}, actor="rm.user")
srm = must(st, srm, "create SRM review")
sid = srm["id"]
items = (srm.get("checklist") or {}).get("items") or []
check("SRM created OPEN with SRM- ref", srm["status"] == "OPEN" and srm["srmRef"].startswith("SRM-"), str(srm))
check("checklist materialised from the SRM_CHECKLIST master",
      len(items) == len(CHECKLIST_ITEMS) and items[0]["label"] == CHECKLIST_ITEMS[0], str(items))
check("linked SRM_RENEWAL noting created in DRAFT",
      isinstance(srm.get("notingRef"), str) and srm["notingRef"].startswith("NTG-")
      and srm["notingStatus"] == "DRAFT", str(srm))
NREF = srm["notingRef"]


print("\n== 3. Mark a checklist item -> persisted on the review ==")
st, marked = call("POST", f"/decision/api/srm/reviews/{sid}/checklist/SRM-1", {"done": True}, actor="rm.user")
marked = must(st, marked, "mark checklist item SRM-1")
m_items = (marked.get("checklist") or {}).get("items") or []
check("checklist item SRM-1 marked done (persisted)",
      any(it["code"] == "SRM-1" and it["done"] for it in m_items), str(m_items))
st, bad = call("POST", f"/decision/api/srm/reviews/{sid}/checklist/SRM-99", {"done": True}, actor="rm.user")
check("marking an unknown checklist item -> 404", bad and st == 404, f"{st} {bad}")


print("\n== 4. Submit the renewal noting (delegated to the Noting engine) ==")
st, sub = call("POST", f"/decision/api/srm/reviews/{sid}/submit-noting", actor="rm.user")
sub = must(st, sub, "submit renewal noting")
check("SRM -> NOTING_RAISED, noting PENDING_APPROVAL",
      sub["status"] == "NOTING_RAISED" and sub["notingStatus"] == "PENDING_APPROVAL", str(sub))
st, n = call("GET", f"/decision/api/notings/{NREF}")
n = must(st, n, "get linked noting")
check("cadRequired resolved from the master + routed FIXED_ROLE CREDIT_OFFICER",
      n["cadRequired"] is True and n["routing"] == "FIXED_ROLE" and n["approverRole"] == "CREDIT_OFFICER", str(n))


print("\n== 5. SoD + role gates on the linked noting (via /api/notings) ==")
st, b = call("POST", f"/decision/api/notings/{NREF}/approve", {}, actor="rm.user")
check("approve by the RAISER -> 403 (SoD)", st == 403, f"{st} {b}")
st, b = call("POST", f"/decision/api/notings/{NREF}/approve", {}, actor="analyst.user")
check("approve by NON-authorised (ANALYST) -> 403 (role gate)", st == 403, f"{st} {b}")


print("\n== 6. Drive the noting to AUTHORIZED via /api/notings ==")
st, appd = call("POST", f"/decision/api/notings/{NREF}/approve", {"note": "renewal ok"}, actor="credit.officer")
appd = must(st, appd, "approve noting")
check("approve of a cadRequired noting -> PENDING_CAD", appd["status"] == "PENDING_CAD", str(appd))
st, b = call("POST", f"/decision/api/notings/{NREF}/cad-authorize", {}, actor="rm.user")
check("cad-authorize by a non-CAD actor -> 403", st == 403, f"{st} {b}")
st, auth = call("POST", f"/decision/api/notings/{NREF}/cad-authorize", {"note": "docs perfected"}, actor="cad.maker")
auth = must(st, auth, "cad-authorize noting")
check("cad-authorize by CAD_OPS -> AUTHORIZED", auth["status"] == "AUTHORIZED", str(auth))


print("\n== 7. Refresh the SRM -> COMPLETED + MER next-review ADVANCED ==")
st, done = call("POST", f"/decision/api/srm/reviews/{sid}/refresh", actor="rm.user")
done = must(st, done, "refresh SRM")
EXPECTED = plus_one_year(DUE_BEFORE)
check("SRM COMPLETED, noting AUTHORIZED, renewal date carried",
      done["status"] == "COMPLETED" and done["notingStatus"] == "AUTHORIZED"
      and done["renewalDueDate"] == EXPECTED, str(done))

rows_a = mer_items(SUBJECT)
rev_after = find_item(rows_a, "RENEWAL_REVIEW")
check("subject RENEWAL_REVIEW due date ADVANCED (+1 cycle)",
      rev_after and rev_after["dueDate"] == EXPECTED and rev_after["dueDate"] > DUE_BEFORE
      and rev_after["cycleCount"] == 1 and rev_after["status"] == "OPEN",
      f"{rev_after.get('dueDate') if rev_after else None} vs {DUE_BEFORE}")


print("\n== 8. INVARIANT: the non-SRM MER entries are UNCHANGED (type + subject scoped) ==")
cond_after = find_item(rows_a, "CONDITION_SUBSEQUENT")
check("same-subject CONDITION_SUBSEQUENT unchanged (item-type scoped)",
      cond_after and cond_after["dueDate"] == COND_DUE_BEFORE and cond_after["cycleCount"] == 0,
      f"{cond_after.get('dueDate') if cond_after else None} vs {COND_DUE_BEFORE}")
rows_b = mer_items(OTHER)
other_after = find_item(rows_b, "RENEWAL_REVIEW")
check("different-subject RENEWAL_REVIEW unchanged (subject scoped)",
      other_after and other_after["dueDate"] == OTHER_DUE_BEFORE and other_after["cycleCount"] == 0,
      f"{other_after.get('dueDate') if other_after else None} vs {OTHER_DUE_BEFORE}")


print("\n== 9. Idempotent: a second refresh does NOT advance the date again ==")
st, again = call("POST", f"/decision/api/srm/reviews/{sid}/refresh", actor="rm.user")
again = must(st, again, "refresh SRM again")
rev_again = find_item(mer_items(SUBJECT), "RENEWAL_REVIEW")
check("re-refresh is a no-op for the hook (date + cycle unchanged)",
      rev_again and rev_again["dueDate"] == EXPECTED and rev_again["cycleCount"] == 1,
      f"{rev_again.get('dueDate') if rev_again else None} cycle={rev_again.get('cycleCount') if rev_again else None}")


print("\n== 10. Governance: SRM_* + MER_RENEWAL_ADVANCED audit events are HUMAN ==")
st, audit = call("GET", "/decision/api/audit")
audit = audit or []
srm_events = [a for a in audit if str(a.get("eventType", "")).startswith("SRM_")]
check("SRM lifecycle emitted audit events", len(srm_events) >= 3, str(len(srm_events)))
check("every SRM_* audit event is stamped HUMAN (no AI/SYSTEM figure path)",
      all(a.get("actorType") == "HUMAN" for a in srm_events), "")
adv = [a for a in audit if a.get("eventType") == "MER_RENEWAL_ADVANCED"]
check("MER_RENEWAL_ADVANCED stamped once, HUMAN",
      len(adv) == 1 and adv[0].get("actorType") == "HUMAN", str(adv))


print(f"\n== SRM e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
