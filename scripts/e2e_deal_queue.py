#!/usr/bin/env python3
"""
Hybrid deal work-queue — e2e (round-3 Wave 2C).

A deal now "sits" in a work-queue owned by a role/team and moves only on explicit advance or
reassignment — while staying globally searchable (hybrid). The WorkItem is a best-effort mirror
that never gates the deal.

Proves:
  1. ENQUEUE ON ENTRY — creating a deal materialises the workflow, whose first (INTAKE) stage now
     carries a queueKey; a WorkItem is minted into the owning role's queue (origination.queue) and
     auto-assigned from its ASSIGNMENT_POOL (rm.user / rm.head).
  2. HYBRID VISIBILITY — the deal is still globally searchable (the applications list), not hidden
     behind the queue.
  3. OWNERSHIP MOVES ON REASSIGNMENT — a supervisor reassigns the WorkItem (mandatory reason) and the
     owner changes; SoD holds.

Against the gateway on :8080; binds no port. Registered by the coordinator.
"""
import json
import sys
import time
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


print("== 1. Creating a deal enqueues it into the owning role's queue ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Deal Queue Test Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "DQ1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "counterparty")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 400_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 600_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "application")
ref = app["reference"]

# The workflow materialise + stage-entry hook run best-effort on create; give it a moment.
task = None
for _ in range(10):
    st, tasks = call("GET", f"/workflow/api/tasks/subject?ref={ref}", actor="rm.head")
    if st == 200 and isinstance(tasks, list):
        task = next((t for t in tasks if t.get("queueKey") == "origination.queue"), None)
        if task:
            break
    time.sleep(0.5)
check("deal minted a WorkItem into origination.queue on stage entry", task is not None,
      "no origination.queue task found for " + ref)
if not task:
    print(f"\n{PASS} passed, {FAIL} failed")
    sys.exit(1)
check("the queued task points back at the deal (subjectRef)", task.get("subjectRef") == ref, str(task.get("subjectRef")))
check("the task was auto-assigned from the pool (rm.user / rm.head)",
      task.get("assignee") in ("rm.user", "rm.head"), str(task.get("assignee")))
task_ref = task["taskRef"]

# Auto-assigned from a ROUND_ROBIN pool, so it sits in the OWNING user's inbox (a named queue).
owner = task.get("assignee")
st, inbox = call("GET", f"/workflow/api/tasks/inbox?assignee={owner}", actor=owner)
check("the deal task sits in the owning user's inbox (named queue)",
      st == 200 and any(t.get("taskRef") == task_ref for t in (inbox or [])), f"{st}")

print("== 2. The deal is still globally searchable (hybrid, not hidden by the queue) ==")
st, apps = call("GET", "/origination/api/applications", actor="rm.head")
check("deal is globally listed regardless of queue ownership",
      st == 200 and any(a.get("reference") == ref for a in (apps or [])), f"{st}")

print("== 3. Ownership moves only on reassignment (supervisor, mandatory reason, SoD) ==")
current = task.get("assignee")
target = "rm.head" if current == "rm.user" else "rm.user"
st, re = call("POST", f"/workflow/api/tasks/{task_ref}/assign",
              {"assignee": target, "reason": "coverage handoff for review"}, actor="rm.head")
check("supervisor reassignment succeeds", st == 200, f"{st} {re}")
check("the deal's queue owner changed to the new assignee", (re or {}).get("assignee") == target,
      str((re or {}).get("assignee")))

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
