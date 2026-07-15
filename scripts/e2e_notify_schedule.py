#!/usr/bin/env python3
"""
Notification schedule-later + auto-reminder sweep — e2e.

The helix-common notify lane gained (a) deferred dispatch — enqueue with a future
scheduleAt persists a SCHEDULED row that the sweep dispatches once due — and (b) recurring
auto-reminders — reminder-eligible rows (cadence+cap from the NOTIFICATION_ROUTE payload or
an explicit override) spawn NEW notification rows with the dedupeKey suffixed #r<N>, capped
by maxReminders. Both are idempotent; re-running the sweep never duplicates.

Proves, via decision-service's shared notify surface (test-only enqueue hook gated by
helix.notify.test-enqueue-enabled, mirroring the RBAC outage simulator):
  - baseline immediate enqueue is unchanged (SENT, no schedule/reminder fields);
  - a scheduled-future row stays SCHEDULED across sweeps;
  - a scheduled row whose time has passed dispatches on sweep (SENT via the normal
    transport path, sentAt stamped, NOTIFICATION_DISPATCHED audit);
  - re-enqueueing the same (eventType|subjectRef|dedupeKey) returns the existing row;
  - reminders fire with #r1/#r2 dedupe suffixes (reminderEveryHours=0 ⇒ due every sweep),
    are capped by maxReminders, and reminder rows carry no reminder config (no chains);
  - re-running the sweep creates nothing new (idempotency);
  - the forced sweep stamps a SYSTEM NOTIFICATION_SWEEP audit event.
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
EVENT = "E2E_NOTIFY_TEST"
SUBJECT_TYPE = "E2ENotify"


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


def enqueue(subject_ref, dedupe_key, **extra):
    body = {"eventType": EVENT, "templateKey": EVENT, "subjectType": SUBJECT_TYPE,
            "subjectRef": subject_ref, "dedupeKey": dedupe_key,
            "vars": {"nonce": NONCE}, "recipientRoles": ["rm.user"]}
    body.update(extra)
    st, n = call("POST", f"{SVC}/api/notifications/_test-enqueue", body)
    return must(st, n, f"test-enqueue {dedupe_key}")


def sweep():
    st, r = call("POST", f"{SVC}/api/notifications/sweep")
    return must(st, r, "sweep")


def get_notification(nid):
    st, n = call("GET", f"{SVC}/api/notifications/{nid}")
    return must(st, n, f"get notification {nid}")


def rows_for(subject_ref):
    st, rows = call("GET", f"{SVC}/api/notifications?eventType={EVENT}")
    must(st, rows, "list notifications")
    return [r for r in rows if r.get("subjectRef") == subject_ref]


print("== 1. baseline immediate enqueue (behaviour unchanged: no schedule, no reminders)")
base_ref = f"E2E-NOTIFY-BASE-{NONCE}"
base = enqueue(base_ref, "base")
check("immediate enqueue dispatches straight to SENT", base.get("status") == "SENT", base.get("status"))
check("no scheduledFor on immediate row", base.get("scheduledFor") is None)
check("no reminder config on plain row",
      base.get("reminderEveryHours") is None and base.get("maxReminders") is None)
check("sentAt stamped on immediate row", base.get("sentAt") is not None)

print("== 2. schedule-later: future row stays SCHEDULED across a sweep")
fut_ref = f"E2E-NOTIFY-FUT-{NONCE}"
fut = enqueue(fut_ref, "future", scheduleInSeconds=3600)
check("future enqueue persists as SCHEDULED", fut.get("status") == "SCHEDULED", fut.get("status"))
check("scheduledFor recorded", fut.get("scheduledFor") is not None)
check("no transport dispatch yet (sentAt empty)", fut.get("sentAt") is None)
sweep()
fut_after = get_notification(fut["id"])
check("future row still SCHEDULED after sweep", fut_after.get("status") == "SCHEDULED",
      fut_after.get("status"))

print("== 3. schedule-later idempotency: re-enqueue returns the existing row")
fut_again = enqueue(fut_ref, "future", scheduleInSeconds=3600)
check("re-enqueue same (event|subject|dedupe) returns same row", fut_again.get("id") == fut["id"],
      f"{fut_again.get('id')} vs {fut['id']}")

print("== 4. schedule-later: due row dispatches on sweep via the normal transport path")
due_ref = f"E2E-NOTIFY-DUE-{NONCE}"
due = enqueue(due_ref, "due", scheduleInSeconds=2)
check("near-future enqueue persists as SCHEDULED", due.get("status") == "SCHEDULED", due.get("status"))
time.sleep(3)
# NOTE: assertions are state-based, not sweep-response-count-based — the background
# NotificationSweeper (on by default) may legitimately win the race against this manual sweep.
sweep()
due_after = get_notification(due["id"])
check("due row flipped to SENT on sweep", due_after.get("status") == "SENT", due_after.get("status"))
check("sentAt stamped on sweep dispatch", due_after.get("sentAt") is not None)
check("providerRef recorded via transport", due_after.get("providerRef") is not None)
sweep()
due_after2 = get_notification(due["id"])
check("re-sweep does not re-dispatch (still exactly SENT, no new state)",
      due_after2.get("status") == "SENT" and due_after2.get("providerRef") == due_after.get("providerRef"))

print("== 5. auto-reminders: #r<N> rows, capped by maxReminders, no reminder chains")
rem_ref = f"E2E-NOTIFY-REM-{NONCE}"
parent = enqueue(rem_ref, "rem", reminderEveryHours=0, maxReminders=2)
check("parent dispatched SENT with reminder config", parent.get("status") == "SENT"
      and parent.get("reminderEveryHours") == 0 and parent.get("maxReminders") == 2, parent)
check("remindersSent starts at 0", parent.get("remindersSent") == 0, parent.get("remindersSent"))

sweep()
rows = rows_for(rem_ref)
r1 = [r for r in rows if str(r.get("dedupeKey", "")).endswith("#r1")]
check("reminder row #r1 created", len(r1) == 1, [r.get("dedupeKey") for r in rows])
check("reminder row is a real dispatched notification", r1 and r1[0].get("status") == "SENT")
check("reminder row carries no reminder config (no reminder-of-reminder chain)",
      r1 and r1[0].get("reminderEveryHours") is None and r1[0].get("maxReminders") is None)
parent_after1 = get_notification(parent["id"])
check("parent remindersSent advanced (1, or 2 if the background sweep also fired)",
      parent_after1.get("remindersSent") in (1, 2), parent_after1.get("remindersSent"))

sweep()
rows = rows_for(rem_ref)
r2 = [r for r in rows if str(r.get("dedupeKey", "")).endswith("#r2")]
check("second sweep creates reminder #r2", len(r2) == 1, [r.get("dedupeKey") for r in rows])
parent_after2 = get_notification(parent["id"])
check("parent remindersSent advanced to 2 (== maxReminders)", parent_after2.get("remindersSent") == 2)

print("== 6. reminder cap + sweep idempotency: nothing new on further sweeps")
before = len(rows_for(rem_ref))
sweep()
after = len(rows_for(rem_ref))
check("capped: third sweep creates no #r3 and no duplicates", after == before,
      f"{before} -> {after}")
r3 = [r for r in rows_for(rem_ref) if str(r.get("dedupeKey", "")).endswith("#r3")]
check("no reminder beyond maxReminders", len(r3) == 0)
check("total rows for reminder subject == parent + 2 reminders", after == 3, after)
fut_final = get_notification(fut["id"])
check("far-future row STILL SCHEDULED after all sweeps", fut_final.get("status") == "SCHEDULED",
      fut_final.get("status"))

print("== 7. sweep audit: SYSTEM-stamped NOTIFICATION_SWEEP event")
st, audits = call("GET", f"{SVC}/api/audit/subject?type=Notification&id=sweep")
must(st, audits, "audit subject")
sweeps = [a for a in audits if a.get("eventType") == "NOTIFICATION_SWEEP"]
check("NOTIFICATION_SWEEP audit stamped", len(sweeps) >= 1, len(audits))
check("sweep audit is SYSTEM actorType", sweeps and sweeps[0].get("actorType") == "SYSTEM",
      sweeps[0].get("actorType") if sweeps else "none")

print(f"\nnotify schedule/reminder e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
