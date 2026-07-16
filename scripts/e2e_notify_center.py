#!/usr/bin/env python3
"""
Notification center — read-state (unread-count / mark-read / mark-all-read) e2e.

The helix-common notify outbox gained an additive read-state (nullable readAt/readBy) and
three endpoints on the auto-exposed /api/notifications surface so the topbar bell can show a
live unread badge, mark items read on click-through, and clear the badge — WITHOUT changing
enqueue / scheduled-dispatch / reminder behaviour (a read notification is not deleted).

Proves, via decision-service's shared notify surface (test-only enqueue hook gated by
helix.notify.test-enqueue-enabled, same as the schedule/reminder suite):
  - a fresh, uniquely-scoped recipient starts at unread-count 0;
  - enqueue raises the scoped unread-count (recipient and role scopes both match the row);
  - immediate enqueue behaviour is unchanged (SENT, no schedule/reminder fields);
  - mark-read is recipient-owned: a NON-recipient actor is forbidden (403) and the unread-count
    is untouched; a recipient (holds the row's recipientRole) succeeds;
  - POST /{id}/read stamps readAt/readBy, decrements the count, and is idempotent
    (a second read keeps the original readAt/readBy and does not double-decrement);
  - POST /read-all?recipient= zeroes the scoped count and is idempotent (re-run marks 0);
  - a READ row still appears in the full GET /api/notifications list (read != deleted);
  - the global unread-count endpoint responds and is at least the scoped remainder.
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
EVENT = "E2E_NOTIFY_CENTER"
SUBJECT_TYPE = "E2ENotifyCenter"
ROLE = f"e2e.nc.{NONCE}"          # unique recipient/role scope so counts are deterministic
# Fix 2: mark-read / read-all are recipient-owned — the acting user must be a recipient of the
# row (by id or by one of its recipientRoles). The rows below are role-scoped to ROLE, so the
# acting user holds ROLE. A separate NON_RECIPIENT proves the 403 gate.
ACTOR = ROLE
NON_RECIPIENT = f"intruder.{NONCE}"


def call(method, path, body=None, actor=ACTOR):
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


def enqueue(subject_ref, dedupe_key):
    body = {"eventType": EVENT, "templateKey": EVENT, "subjectType": SUBJECT_TYPE,
            "subjectRef": subject_ref, "dedupeKey": dedupe_key,
            "vars": {"nonce": NONCE}, "recipientRoles": [ROLE]}
    st, n = call("POST", f"{SVC}/api/notifications/_test-enqueue", body)
    return must(st, n, f"test-enqueue {dedupe_key}")


def unread(scope=None):
    q = ""
    if scope == "role":
        q = f"?role={ROLE}"
    elif scope == "recipient":
        q = f"?recipient={ROLE}"
    st, r = call("GET", f"{SVC}/api/notifications/unread-count{q}")
    must(st, r, "unread-count")
    return r.get("unread")


def get_notification(nid):
    st, n = call("GET", f"{SVC}/api/notifications/{nid}")
    return must(st, n, f"get notification {nid}")


print("== 1. fresh unique scope starts at zero unread")
check("scoped unread-count is 0 before any enqueue", unread("role") == 0, unread("role"))

print("== 2. enqueue raises the scoped unread-count (existing enqueue behaviour unchanged)")
n1 = enqueue(f"E2E-NC-A-{NONCE}", "a")
n2 = enqueue(f"E2E-NC-B-{NONCE}", "b")
n3 = enqueue(f"E2E-NC-C-{NONCE}", "c")
check("immediate enqueue still dispatches straight to SENT", n1.get("status") == "SENT", n1.get("status"))
check("no schedule/reminder fields on plain enqueue",
      n1.get("scheduledFor") is None and n1.get("reminderEveryHours") is None)
check("enqueued row starts unread (readAt null)", n1.get("readAt") is None, n1.get("readAt"))
check("scoped unread-count reflects 3 enqueued rows (role scope)", unread("role") == 3, unread("role"))
check("scoped unread-count matches on recipient scope too", unread("recipient") == 3, unread("recipient"))

print("== 3. mark-read ownership (403 for a non-recipient), then stamp/decrement/idempotency")
# Fix 2: an enumerable id is not enough — a non-recipient cannot flip an unread row's read-state.
st, forb = call("POST", f"{SVC}/api/notifications/{n1['id']}/read", actor=NON_RECIPIENT)
check("mark-read by a NON-recipient actor -> 403", st == 403, f"HTTP {st} {forb}")
check("scoped unread-count unchanged by the forbidden mark-read (still 3)", unread("role") == 3, unread("role"))
# A recipient (holds the row's recipientRole) marks it read.
st, r = call("POST", f"{SVC}/api/notifications/{n1['id']}/read")
read1 = must(st, r, "mark read n1")
check("mark-read stamps readAt", read1.get("readAt") is not None, read1.get("readAt"))
check("mark-read stamps readBy = X-Actor", read1.get("readBy") == ACTOR, read1.get("readBy"))
check("scoped unread-count decrements to 2 after one read", unread("role") == 2, unread("role"))
# Capture the PERSISTED readAt (DB round-trip truncates to millis) so the idempotency
# comparison is precision-stable rather than comparing an in-memory nanosecond instant.
first_read_at = get_notification(n1["id"]).get("readAt")
st, r2 = call("POST", f"{SVC}/api/notifications/{n1['id']}/read", actor="someone.else")
read1b = must(st, r2, "mark read n1 again")
check("re-reading is idempotent: readAt unchanged", read1b.get("readAt") == first_read_at,
      f"{read1b.get('readAt')} vs {first_read_at}")
check("re-reading is idempotent: readBy unchanged (stays original actor)",
      read1b.get("readBy") == ACTOR, read1b.get("readBy"))
check("scoped unread-count still 2 (no double-decrement)", unread("role") == 2, unread("role"))

print("== 4. a READ row still appears in the full list (read != deleted)")
st, rows = call("GET", f"{SVC}/api/notifications?eventType={EVENT}")
must(st, rows, "list notifications")
listed = [x for x in rows if x.get("id") == n1["id"]]
check("read row is still present in the outbox list", len(listed) == 1, [x.get("id") for x in rows][:8])
check("listed read row carries readAt", listed and listed[0].get("readAt") is not None)

print("== 5. read-all zeroes the scoped count and is idempotent")
st, ra = call("POST", f"{SVC}/api/notifications/read-all?recipient={ROLE}")
rares = must(st, ra, "read-all")
check("read-all reports the remaining 2 rows flipped", rares.get("read") == 2, rares)
check("scoped unread-count is 0 after read-all", unread("role") == 0, unread("role"))
st, ra2 = call("POST", f"{SVC}/api/notifications/read-all?recipient={ROLE}")
rares2 = must(st, ra2, "read-all again")
check("read-all is idempotent (re-run marks 0)", rares2.get("read") == 0, rares2)
check("n2/n3 now readAt-stamped", get_notification(n2["id"]).get("readAt") is not None
      and get_notification(n3["id"]).get("readAt") is not None)

print("== 6. global unread-count endpoint responds")
g = unread(None)
check("global unread-count returns a non-negative integer", isinstance(g, int) and g >= 0, g)

print(f"\nnotify center e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
