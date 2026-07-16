#!/usr/bin/env python3
"""
TAT / MIS reporting e2e — the deterministic report surface over the case-management
(WorkItem) layer and the query (RFI) collaboration lane (workflow-service
/api/tasks/mis + the per-service /api/queries/sla-rollup added in helix-common).

Seeds a handful of WorkItems across a run-unique queue (create / complete / leave-open
/ send-back), then asserts that /api/tasks/mis is the exact deterministic function of
that seeded data:
  * completed / open / sent-back counts;
  * avg / median / max cycle-time bucket (CREATED -> COMPLETED);
  * SLA breach count + rate (0 for the within-SLA seed) + open-overdue count;
  * rework distribution + send-back rate after a send-back;
  * throughput (created vs completed) + open backlog;
  * per-queue / per-task-type / assignee-load breakdowns;
  * re-running the report yields byte-identical figures (deterministic).

Plus the query SLA rollup: raising + resolving INTERNAL threads moves the
open / resolved counters by exactly the seeded delta. This is a read-only surface —
it never mutates a WorkItem's state or any authoritative domain figure.
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
SUF = str(int(time.time() * 1000))[-7:]


def call(method, path, body=None, actor=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor is not None:
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


def approx(a, b, tol=1e-9):
    return a is not None and b is not None and abs(a - b) < tol


def stable(m):
    """Canonical form of the report with the volatile generatedAt stripped."""
    m = dict(m)
    m.pop("generatedAt", None)
    return json.dumps(m, sort_keys=True)


# Run-unique identities + queue so the aggregation is isolated from every other suite.
Q = f"TATQ_{SUF}"
alice, bob, carol, dave, sup = (f"{x}.{SUF}" for x in ("alice", "bob", "carol", "dave", "sup"))


def create_task(task_type, assignee, sla=None, actor="rm.user", subject=None):
    body = {"subjectType": "Deal", "subjectRef": subject or f"TAT-{SUF}-{assignee}-{task_type}",
            "taskType": task_type, "queueKey": Q, "assignee": assignee, "slaHours": sla}
    st, t = call("POST", "/workflow/api/tasks", body, actor=actor)
    return must(st, t, f"create {task_type}/{assignee}")


print("== 0. Seed a run-unique queue of WorkItems ==")
# Two REVIEW tasks completed (within a 48h SLA → no breach), cycle time ~ seconds.
t1 = create_task("REVIEW", alice, sla=48)
t2 = create_task("REVIEW", bob, sla=48)
must(*call("POST", f"/workflow/api/tasks/{t1['taskRef']}/complete", {"note": "ok"}, actor=alice), "complete t1")
must(*call("POST", f"/workflow/api/tasks/{t2['taskRef']}/complete", {"note": "ok"}, actor=bob), "complete t2")
# One APPROVAL left open (ASSIGNED).
t3 = create_task("APPROVAL", carol)
# One APPROVAL sent back → opens a rework task (reworkCycle=1) back to the originator (rm.user).
# The assignee (dave) sends it back — authorized under the case-layer rule (assignee or pool
# supervisor); this run-unique queue has no ASSIGNMENT_POOL, so a non-assignee/non-supervisor
# would (correctly) be 403'd.
t4 = create_task("APPROVAL", dave, actor="rm.user")
sb = must(*call("POST", f"/workflow/api/tasks/{t4['taskRef']}/send-back", {"note": "fix figures"}, actor=dave), "send-back t4")
check("send-back opened a rework task with reworkCycle=1",
      sb["rework"]["reworkCycle"] == 1 and sb["original"]["status"] == "SENT_BACK",
      f"{sb.get('rework', {}).get('reworkCycle')} / {sb.get('original', {}).get('status')}")


print("== 1. /api/tasks/mis is the deterministic function of the seeded data ==")
st, mis = call("GET", f"/workflow/api/tasks/mis?queueKey={Q}")
mis = must(st, mis, "mis")
t = mis["totals"]
check("filter echoes the queueKey", mis["filter"]["queueKey"] == Q, f"{mis['filter']}")
check("count == 5 (2 completed + 1 open + 1 sent-back + 1 rework)", t["count"] == 5, f"{t['count']}")
check("completedCount == 2", t["completedCount"] == 2, f"{t['completedCount']}")
check("openCount == 2 (open APPROVAL + rework)", t["openCount"] == 2, f"{t['openCount']}")
check("sentBackCount == 1", t["sentBackCount"] == 1, f"{t['sentBackCount']}")
check("reworkTaskCount == 1", t["reworkTaskCount"] == 1, f"{t['reworkTaskCount']}")
check("sendBackRate == 1/5", approx(t["sendBackRate"], 0.2), f"{t['sendBackRate']}")


print("== 2. SLA: no breaches for the within-SLA seed ==")
check("breachedCount == 0", t["breachedCount"] == 0, f"{t['breachedCount']}")
check("breachRate == 0", approx(t["breachRate"], 0.0), f"{t['breachRate']}")
check("openOverdueCount == 0 (dueAt in the future)", t["openOverdueCount"] == 0, f"{t['openOverdueCount']}")


print("== 3. Cycle-time bucket derived from CREATED -> COMPLETED ==")
check("avgCycleHours present and in the sub-hour bucket",
      t["avgCycleHours"] is not None and 0 <= t["avgCycleHours"] < 1, f"{t['avgCycleHours']}")
check("medianCycleHours present", t["medianCycleHours"] is not None, f"{t['medianCycleHours']}")
check("maxCycleHours present and >= avg",
      t["maxCycleHours"] is not None and t["maxCycleHours"] >= t["avgCycleHours"],
      f"{t['maxCycleHours']} vs {t['avgCycleHours']}")


print("== 4. Throughput + backlog ==")
tp = mis["throughput"]
check("createdInWindow == 5 (all seeded tasks)", tp["createdInWindow"] == 5, f"{tp['createdInWindow']}")
check("completedInWindow == 2", tp["completedInWindow"] == 2, f"{tp['completedInWindow']}")
check("openBacklog == 2", tp["openBacklog"] == 2, f"{tp['openBacklog']}")


print("== 5. Per-task-type + per-queue breakdowns ==")
by_type = {r["taskType"]: r for r in mis["byTaskType"]}
check("REVIEW type: count 2, all completed",
      by_type.get("REVIEW", {}).get("count") == 2 and by_type["REVIEW"]["completedCount"] == 2,
      f"{by_type.get('REVIEW')}")
check("APPROVAL type: count 3 (open + sent-back + rework), 0 completed",
      by_type.get("APPROVAL", {}).get("count") == 3 and by_type["APPROVAL"]["completedCount"] == 0,
      f"{by_type.get('APPROVAL')}")
by_queue = {r["queueKey"]: r for r in mis["byQueue"]}
check("single queue row for the seeded queue with count 5",
      by_queue.get(Q, {}).get("count") == 5, f"{list(by_queue.keys())}")


print("== 6. Rework distribution + assignee load ==")
dist = {r["reworkCycle"]: r["count"] for r in mis["reworkDistribution"]}
check("rework distribution: 4 first-pass + 1 on cycle 1",
      dist.get(0) == 4 and dist.get(1) == 1, f"{dist}")
load = {r["assignee"]: r["openCount"] for r in mis["assigneeLoad"]}
check("assignee load counts open tasks per assignee (carol=1, rm.user=1)",
      load.get(carol) == 1 and load.get("rm.user") == 1, f"{load}")


print("== 7. taskType filter narrows the scope ==")
st, misR = call("GET", f"/workflow/api/tasks/mis?queueKey={Q}&taskType=REVIEW")
misR = must(st, misR, "mis REVIEW")
check("queueKey + taskType filter → count 2, completed 2",
      misR["totals"]["count"] == 2 and misR["totals"]["completedCount"] == 2,
      f"{misR['totals']}")


print("== 8. The report is deterministic (byte-identical across re-runs) ==")
st, mis2 = call("GET", f"/workflow/api/tasks/mis?queueKey={Q}")
mis2 = must(st, mis2, "mis re-run")
check("re-running /mis yields identical figures (only generatedAt differs)",
      stable(mis) == stable(mis2), "report changed across two reads")


print("== 9. Query / RFI SLA rollup (additive helix-common endpoint) ==")
st, r0 = call("GET", "/workflow/api/queries/sla-rollup")
r0 = must(st, r0, "rollup before")
check("rollup shape present (total + byChannel list)",
      "total" in r0 and isinstance(r0.get("byChannel"), list), f"{r0}")
open0, resolved0, total0 = r0["open"], r0["resolved"], r0["total"]

# Raise two INTERNAL threads; resolve one (raiser-only). Net: +1 open, +1 resolved.
st, q1 = call("POST", "/workflow/api/queries",
              {"channel": "INTERNAL", "subjectType": "Deal", "subjectRef": f"TATQ-{SUF}",
               "topic": "TAT", "question": "please confirm docs", "slaHours": 24}, actor="rm.user")
q1 = must(st, q1, "raise q1")
st, q2 = call("POST", "/workflow/api/queries",
              {"channel": "INTERNAL", "subjectType": "Deal", "subjectRef": f"TATQ-{SUF}",
               "topic": "TAT", "question": "second query", "slaHours": 24}, actor="rm.user")
q2 = must(st, q2, "raise q2")
must(*call("POST", f"/workflow/api/queries/{q1['thread']['queryRef']}/resolve",
           {"resolution": "confirmed"}, actor="rm.user"), "resolve q1")

st, r1 = call("GET", "/workflow/api/queries/sla-rollup")
r1 = must(st, r1, "rollup after")
check("rollup total advanced by exactly the two raised threads",
      r1["total"] - total0 == 2, f"{total0} -> {r1['total']}")
check("rollup open advanced by 1 (one still open, one resolved)",
      r1["open"] - open0 == 1, f"{open0} -> {r1['open']}")
check("rollup resolved advanced by 1",
      r1["resolved"] - resolved0 == 1, f"{resolved0} -> {r1['resolved']}")
check("INTERNAL channel present in the byChannel breakdown",
      any(c["channel"] == "INTERNAL" for c in r1["byChannel"]), f"{[c['channel'] for c in r1['byChannel']]}")


print(f"\n==== TAT / MIS e2e: {PASS} passed, {FAIL} failed ====")
sys.exit(1 if FAIL else 0)
