#!/usr/bin/env python3
"""
Email-actionable approve/reject with comments (CLoM F12) — e2e.

The helix-common notify outbox gained an additive, opt-in actionable-approval mode: when a
notification is enqueued for an approvable work-item it is minted two one-time, single-use
approve/reject tokens (hashed at rest, exactly like the query external-response callback token)
and the approve/reject links are rendered into the body. A new auto-exposed endpoint
POST /api/notifications/action/{token} records the addressed recipient's decision + comment as a
HUMAN action and best-effort routes to the subject's decision endpoint (fail-soft).

Driven through decision-service's shared notify surface via the test-only enqueue hook (gated by
helix.notify.test-enqueue-enabled, same as the schedule/reminder/center suites).

Proves:
  - an APPROVAL notification carries one-time approve + reject tokens/links, starts action-state
    PENDING, dispatches normally (SENT), and NEVER serialises the token hashes;
  - a forged / unknown token -> 403 (single-use credential; not a generic 400);
  - APPROVE with a comment -> recorded (APPROVED / APPROVE / actor / comment / decidedAt) and
    stamps an audit HUMAN NOTIFICATION_ACTION_APPROVE event;
  - replaying the SAME approve token -> 403 (single-use), and the sibling REJECT link of the same
    notification is now also dead (403) — one decision spends both links;
  - the REJECT link on a fresh approval notification -> recorded REJECTED / REJECT (+ replay 403);
  - a PLAIN (non-approval) notification enqueues with NO token, NO action-state, and byte-identical
    immediate-enqueue behaviour (SENT, unread, no schedule/reminder fields) — no regression.
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
EVENT = "E2E_EMAIL_ACTION"
SUBJECT_TYPE = "E2EEmailAction"
ROLE = f"e2e.ea.{NONCE}"          # unique recipient/role scope so the run is deterministic
APPROVER = f"approver.{NONCE}"    # the addressed human who clicks the action link (X-Actor)


def call(method, path, body=None, actor=APPROVER):
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


def enqueue_approval(subject_ref, dedupe):
    body = {"eventType": EVENT, "templateKey": EVENT, "subjectType": SUBJECT_TYPE,
            "subjectRef": subject_ref, "dedupeKey": dedupe,
            "vars": {"nonce": NONCE, "facility": "TERM_LOAN", "amount": "40,000,000"},
            "recipientRoles": [ROLE], "approval": True}
    st, n = call("POST", f"{SVC}/api/notifications/_test-enqueue", body)
    return must(st, n, f"enqueue approval {dedupe}")


def enqueue_plain(subject_ref, dedupe):
    body = {"eventType": EVENT, "templateKey": EVENT, "subjectType": SUBJECT_TYPE,
            "subjectRef": subject_ref, "dedupeKey": dedupe,
            "vars": {"nonce": NONCE}, "recipientRoles": [ROLE]}
    st, n = call("POST", f"{SVC}/api/notifications/_test-enqueue", body)
    return must(st, n, f"enqueue plain {dedupe}")


print("== 1. an APPROVAL notification is minted one-time approve/reject tokens + links")
app_ref = f"EA-APP-{NONCE}"
n = enqueue_approval(app_ref, "app")
vars0 = n.get("vars") or {}
approve_token = vars0.get("approveToken")
reject_token = vars0.get("rejectToken")
check("approval notification carries an approveToken", bool(approve_token) and len(approve_token) >= 20, approve_token)
check("approval notification carries a rejectToken", bool(reject_token) and len(reject_token) >= 20, reject_token)
check("approve/reject tokens differ", approve_token != reject_token)
check("approveLink is the tokenised action callback",
      "/action/" in str(vars0.get("approveLink", "")) and str(vars0.get("approveLink", "")).endswith(approve_token or "x"),
      vars0.get("approveLink"))
check("rejectLink is the tokenised action callback",
      "/action/" in str(vars0.get("rejectLink", "")) and str(vars0.get("rejectLink", "")).endswith(reject_token or "x"),
      vars0.get("rejectLink"))
check("action-state starts PENDING", n.get("actionState") == "PENDING", n.get("actionState"))
check("no decision recorded yet (kind/actor/comment null)",
      n.get("actionKind") is None and n.get("actionActor") is None and n.get("actionComment") is None, str(n))
check("token hashes are NEVER serialised (approve)", "approveTokenHash" not in n, list(n.keys()))
check("token hashes are NEVER serialised (reject)", "rejectTokenHash" not in n, list(n.keys()))
check("approval notification still dispatches normally (SENT)", n.get("status") == "SENT", n.get("status"))
check("approval notification starts unread (read-state untouched)", n.get("readAt") is None, n.get("readAt"))

print("== 2. a forged / unknown token is rejected 403 (single-use credential, not a 400)")
st, b = call("POST", f"{SVC}/api/notifications/action/not-a-real-token-xxxxxxxxxxxxxxxxxxxx",
             {"comment": "forged"}, actor="attacker")
check("forged token -> 403", st == 403, f"HTTP {st} {b}")
st, still = call("GET", f"{SVC}/api/notifications/{n['id']}")
still = must(st, still, "get after forged action")
check("notification untouched by the forged action (still PENDING)", still.get("actionState") == "PENDING",
      still.get("actionState"))

print("== 3. APPROVE with a comment -> recorded as a HUMAN decision")
st, done = call("POST", f"{SVC}/api/notifications/action/{approve_token}",
                {"comment": "Within appetite — approved"}, actor=APPROVER)
done = must(st, done, "approve via action link")
check("action-state flips to APPROVED", done.get("actionState") == "APPROVED", done.get("actionState"))
check("action-kind recorded APPROVE", done.get("actionKind") == "APPROVE", done.get("actionKind"))
check("action-actor is the X-Actor (addressed recipient)", done.get("actionActor") == APPROVER, done.get("actionActor"))
check("action-comment recorded", done.get("actionComment") == "Within appetite — approved", done.get("actionComment"))
check("actionDecidedAt stamped", done.get("actionDecidedAt") is not None, done.get("actionDecidedAt"))

st, aud = call("GET", f"{SVC}/api/audit/subject?type={SUBJECT_TYPE}&id={app_ref}")
aud = must(st, aud, "audit subject")
approve_events = [e for e in aud if e.get("eventType") == "NOTIFICATION_ACTION_APPROVE"]
check("approve stamped an audit event", len(approve_events) >= 1, [e.get("eventType") for e in aud])
check("approve audit is a HUMAN action", any(e.get("actorType") == "HUMAN" for e in approve_events),
      approve_events[:1])

print("== 4. single-use: replaying the same approve token -> 403; the sibling reject link is also dead")
st, b = call("POST", f"{SVC}/api/notifications/action/{approve_token}", {"comment": "replay"}, actor=APPROVER)
check("replaying the spent approve token -> 403", st == 403, f"HTTP {st} {b}")
st, b = call("POST", f"{SVC}/api/notifications/action/{reject_token}", {"comment": "flip it"}, actor=APPROVER)
check("the sibling reject link of the same notification is now also dead -> 403", st == 403, f"HTTP {st} {b}")
st, chk = call("GET", f"{SVC}/api/notifications/{n['id']}")
chk = must(st, chk, "get after replay attempts")
check("the recorded decision is unchanged by the replays (still APPROVED by the first actor)",
      chk.get("actionState") == "APPROVED" and chk.get("actionActor") == APPROVER, str(chk.get("actionState")))

print("== 5. the REJECT link on a fresh approval notification records a REJECTED decision")
rej_ref = f"EA-REJ-{NONCE}"
n2 = enqueue_approval(rej_ref, "rej")
rt = (n2.get("vars") or {}).get("rejectToken")
st, done2 = call("POST", f"{SVC}/api/notifications/action/{rt}",
                 {"comment": "Outside risk appetite"}, actor=APPROVER)
done2 = must(st, done2, "reject via action link")
check("action-state flips to REJECTED", done2.get("actionState") == "REJECTED", done2.get("actionState"))
check("action-kind recorded REJECT", done2.get("actionKind") == "REJECT", done2.get("actionKind"))
check("reject comment recorded", done2.get("actionComment") == "Outside risk appetite", done2.get("actionComment"))
st, b = call("POST", f"{SVC}/api/notifications/action/{rt}", {"comment": "replay"}, actor=APPROVER)
check("replaying the spent reject token -> 403", st == 403, f"HTTP {st} {b}")
st, aud2 = call("GET", f"{SVC}/api/audit/subject?type={SUBJECT_TYPE}&id={rej_ref}")
aud2 = must(st, aud2, "audit subject reject")
check("reject stamped an audit HUMAN event",
      any(e.get("eventType") == "NOTIFICATION_ACTION_REJECT" and e.get("actorType") == "HUMAN" for e in aud2),
      [e.get("eventType") for e in aud2])

print("== 6. a PLAIN (non-approval) notification enqueues with NO token — unchanged behaviour")
plain_ref = f"EA-PLAIN-{NONCE}"
p = enqueue_plain(plain_ref, "plain")
pv = p.get("vars") or {}
check("plain notification has NO action-state", p.get("actionState") is None, p.get("actionState"))
check("plain notification has NO approve/reject token in vars",
      "approveToken" not in pv and "rejectToken" not in pv
      and "approveLink" not in pv and "rejectLink" not in pv, list(pv.keys()))
check("plain notification dispatches straight to SENT (immediate-enqueue unchanged)",
      p.get("status") == "SENT", p.get("status"))
check("plain notification starts unread (read-state untouched)", p.get("readAt") is None, p.get("readAt"))
check("plain notification carries no schedule/reminder fields",
      p.get("scheduledFor") is None and p.get("reminderEveryHours") is None, str(p))
# There is no token, so the action endpoint cannot be reached for a plain notification — the whole
# actionable surface is invisible unless the enqueuer explicitly opts in.

print(f"\nemail-action e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
