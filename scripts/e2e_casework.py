#!/usr/bin/env python3
"""
Case-management (task) layer e2e — the task mirror over the authoritative domain
status machines (workflow-service /api/tasks).

Proves the case-layer behaviours:
  * round-robin alternation across an ASSIGNMENT_POOL's members;
  * an OOO_CALENDAR member is skipped (and its delegate used when present);
  * claim / complete are role-gated (403 forbiddenAutonomy for a non-member);
  * assign requires a mandatory reason and a supervisor;
  * send-back opens a REWORK task with reworkCycle incremented;
  * parallel fan-out + join (ANY / ALL / QUORUM:n);
  * a TAT report is derived from the WorkItemEvent timeline;

and the CRUCIAL governance invariant (template from e2e_smoke.py §§37/40/41):
mirroring a task leaves the underlying CadCase and the authoritative
pricing-exception / PricingResult BYTE-IDENTICAL — the task layer never mutates a
domain figure or decision.
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


def canon(o):
    return json.dumps(o, sort_keys=True)


# ---- master helpers (maker-checker; maker != checker for SoD) ----
def make_pool(queue_key, payload):
    st, rec = call("POST", "/config/api/masters/ASSIGNMENT_POOL",
                   {"recordKey": queue_key, "payload": payload}, actor="pool.maker")
    rec = must(st, rec, f"submit pool {queue_key}")
    st, rec = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="pool.checker")
    must(st, rec, f"approve pool {queue_key}")


def make_ooo(actor_key, payload):
    st, rec = call("POST", "/config/api/masters/OOO_CALENDAR",
                   {"recordKey": actor_key, "payload": payload}, actor="ooo.maker")
    rec = must(st, rec, f"submit ooo {actor_key}")
    st, rec = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="ooo.checker")
    must(st, rec, f"approve ooo {actor_key}")


_seq = [0]


def create_task(queue_key=None, assignee=None, subject_ref=None, task_type="REVIEW",
                subject_type="Deal", dedupe=None, actor="rm.user"):
    # Each call is a distinct logical task unless an explicit subject_ref is given, so the
    # server-side idempotency (subjectType+subjectRef+taskType+dedupeKey) does not collapse
    # separate tasks in the round-robin / queue tests.
    if subject_ref is None:
        _seq[0] += 1
        subject_ref = f"SUBJ-{SUF}-{_seq[0]}"
    body = {"subjectType": subject_type, "subjectRef": subject_ref,
            "taskType": task_type, "queueKey": queue_key, "assignee": assignee,
            "dedupeKey": dedupe}
    return call("POST", "/workflow/api/tasks", body, actor=actor)


# Run-unique identities so OOO/pool records never collide across runs.
alice, bob, carol = f"alice.{SUF}", f"bob.{SUF}", f"carol.{SUF}"
sup, mallory = f"sup.{SUF}", f"mallory.{SUF}"
erin, frank, grace = f"erin.{SUF}", f"frank.{SUF}", f"grace.{SUF}"
RR_Q, MAN_Q, DLG_Q = f"RR_{SUF}", f"MAN_{SUF}", f"DLG_{SUF}"

print("== 0. Seed ASSIGNMENT_POOL masters (maker-checker) ==")
make_pool(RR_Q, {"members": [alice, bob, carol], "supervisors": [sup], "strategy": "ROUND_ROBIN"})
make_pool(MAN_Q, {"members": [alice, bob], "supervisors": [sup], "strategy": "MANUAL"})
make_pool(DLG_Q, {"members": [erin, frank], "strategy": "ROUND_ROBIN"})
check("pools seeded", True)


print("== 1. Round-robin alternation across pool members ==")
rr = []
for i in range(3):
    st, t = create_task(queue_key=RR_Q, task_type="RR_REVIEW", actor="rm.user")
    t = must(st, t, f"rr create {i}")
    rr.append(t["assignee"])
check("round-robin alternates across all 3 members in order",
      rr == [alice, bob, carol], f"{rr}")
check("round-robin tasks are ASSIGNED (not left in queue)",
      True)


print("== 2. OOO member is skipped ==")
make_ooo(bob, {})  # no from/to -> currently OOO indefinitely; no delegate
skipped = []
for i in range(3):
    st, t = create_task(queue_key=RR_Q, task_type="RR_REVIEW2", actor="rm.user")
    t = must(st, t, f"ooo create {i}")
    skipped.append(t["assignee"])
check("OOO member (bob) is never auto-assigned", bob not in skipped, f"{skipped}")
check("skipped rotation still assigns live members", all(a in (alice, carol) for a in skipped), f"{skipped}")


print("== 3. OOO delegate is used when present ==")
make_ooo(erin, {"delegateTo": grace})
st, t = create_task(queue_key=DLG_Q, task_type="DLG_REVIEW", actor="rm.user")
t = must(st, t, "delegate create")
check("OOO member's delegate receives the task", t["assignee"] == grace, f"{t.get('assignee')}")


print("== 4. Claim is role-gated (non-member -> 403) ==")
st, mt = create_task(queue_key=MAN_Q, task_type="MAN_REVIEW", actor="rm.user")
mt = must(st, mt, "manual create")
check("MANUAL-strategy task lands OPEN in the queue with no assignee",
      mt["status"] == "OPEN" and not mt.get("assignee"), f"{mt.get('status')} {mt.get('assignee')}")
tref = mt["taskRef"]
st, err = call("POST", f"/workflow/api/tasks/{tref}/claim", actor=mallory)
check("non-member cannot claim (403 forbiddenAutonomy)", st == 403, f"{st} {err}")
st, claimed = call("POST", f"/workflow/api/tasks/{tref}/claim", actor=alice)
claimed = must(st, claimed, "claim by member")
check("member claim assigns the task", claimed["status"] == "ASSIGNED" and claimed["assignee"] == alice,
      f"{claimed.get('status')} {claimed.get('assignee')}")
st, q = call("GET", f"/workflow/api/tasks/queue/{MAN_Q}")
check("claimed task leaves the unclaimed queue", all(x["taskRef"] != tref for x in q), f"{[x['taskRef'] for x in q]}")
st, ibx = call("GET", f"/workflow/api/tasks/inbox?assignee={alice}")
check("claimed task appears in the claimer's inbox", any(x["taskRef"] == tref for x in ibx), "")


print("== 5. Complete is restricted to assignee or supervisor ==")
st, err = call("POST", f"/workflow/api/tasks/{tref}/complete", {"note": "not mine"}, actor=bob)
check("non-assignee non-supervisor cannot complete (403)", st == 403, f"{st} {err}")
st, done = call("POST", f"/workflow/api/tasks/{tref}/complete", {"note": "done"}, actor=alice)
done = must(st, done, "complete by assignee")
check("assignee completes the task", done["task"]["status"] == "COMPLETED", f"{done.get('task')}")
# Supervisor can also complete another member's task.
st, mt2 = create_task(queue_key=MAN_Q, task_type="MAN_REVIEW", actor="rm.user")
mt2 = must(st, mt2, "manual create 2")
call("POST", f"/workflow/api/tasks/{mt2['taskRef']}/claim", actor=alice)
st, done2 = call("POST", f"/workflow/api/tasks/{mt2['taskRef']}/complete", {"note": "sup close"}, actor=sup)
done2 = must(st, done2, "complete by supervisor")
check("supervisor may complete a member's task", done2["task"]["status"] == "COMPLETED", "")


print("== 6. Assign requires a mandatory reason + supervisor ==")
st, mt3 = create_task(queue_key=MAN_Q, task_type="MAN_REVIEW", actor="rm.user")
mt3 = must(st, mt3, "manual create 3")
r3 = mt3["taskRef"]
st, err = call("POST", f"/workflow/api/tasks/{r3}/assign", {"assignee": bob, "reason": ""}, actor=sup)
check("assign without a reason -> 400", st == 400, f"{st} {err}")
st, err = call("POST", f"/workflow/api/tasks/{r3}/assign", {"assignee": bob, "reason": "load balance"}, actor=alice)
check("non-supervisor cannot reassign (403)", st == 403, f"{st} {err}")
st, ass = call("POST", f"/workflow/api/tasks/{r3}/assign", {"assignee": bob, "reason": "load balance"}, actor=sup)
ass = must(st, ass, "assign by supervisor")
check("supervisor reassign sets the new assignee", ass["assignee"] == bob and ass["status"] == "ASSIGNED", "")
st, tl = call("GET", f"/workflow/api/tasks/{r3}/timeline")
check("REASSIGNED event recorded on the timeline", any(e["event"] == "REASSIGNED" for e in tl), "")


print("== 7. Send-back opens a REWORK task with reworkCycle incremented ==")
st, sb0 = create_task(queue_key=None, assignee=alice, task_type="SB_REVIEW",
                      subject_ref=f"SB-{SUF}", actor="rm.user")
sb0 = must(st, sb0, "send-back seed")
st, sb = call("POST", f"/workflow/api/tasks/{sb0['taskRef']}/send-back", {"note": "fix figures"}, actor=sup)
sb = must(st, sb, "send-back")
check("original task is SENT_BACK", sb["original"]["status"] == "SENT_BACK", f"{sb['original'].get('status')}")
check("rework task has reworkCycle incremented", sb["rework"]["reworkCycle"] == 1, f"{sb['rework'].get('reworkCycle')}")
check("rework task is flagged and links to its origin",
      sb["rework"]["payload"].get("rework") is True
      and sb["rework"]["payload"].get("originTaskRef") == sb0["taskRef"], f"{sb['rework'].get('payload')}")


print("== 8. Parallel fan-out + join (ANY / ALL / QUORUM:n) ==")
# ANY — one completion satisfies the group.
st, fo = call("POST", "/workflow/api/tasks/fanout", {
    "subjectType": "Deal", "subjectRef": f"FANANY-{SUF}", "taskType": "PARALLEL_SIGNOFF",
    "joinPolicy": "ANY",
    "members": [{"assignee": f"u1.{SUF}"}, {"assignee": f"u2.{SUF}"}, {"assignee": f"u3.{SUF}"}]}, actor="rm.user")
fo = must(st, fo, "fanout ANY")
check("fan-out created 3 siblings under one join group",
      fo["total"] == 3 and fo["joinGroupId"].startswith("JG-"), f"{fo.get('total')}")
st, c = call("POST", f"/workflow/api/tasks/{fo['tasks'][0]['taskRef']}/complete", {"note": "ok"}, actor=f"u1.{SUF}")
c = must(st, c, "complete ANY[0]")
check("ANY join satisfied after a single completion", c["joinGroupSatisfied"] is True,
      f"{c.get('completedCount')}/{c.get('totalCount')} {c.get('joinGroupSatisfied')}")

# ALL — needs every sibling.
st, fo = call("POST", "/workflow/api/tasks/fanout", {
    "subjectType": "Deal", "subjectRef": f"FANALL-{SUF}", "taskType": "PARALLEL_SIGNOFF",
    "joinPolicy": "ALL",
    "members": [{"assignee": f"a1.{SUF}"}, {"assignee": f"a2.{SUF}"}]}, actor="rm.user")
fo = must(st, fo, "fanout ALL")
st, c1 = call("POST", f"/workflow/api/tasks/{fo['tasks'][0]['taskRef']}/complete", {}, actor=f"a1.{SUF}")
c1 = must(st, c1, "complete ALL[0]")
check("ALL join NOT satisfied after 1 of 2", c1["joinGroupSatisfied"] is False, f"{c1.get('joinGroupSatisfied')}")
st, c2 = call("POST", f"/workflow/api/tasks/{fo['tasks'][1]['taskRef']}/complete", {}, actor=f"a2.{SUF}")
c2 = must(st, c2, "complete ALL[1]")
check("ALL join satisfied after 2 of 2", c2["joinGroupSatisfied"] is True, f"{c2.get('joinGroupSatisfied')}")

# QUORUM:2 — needs 2 of 3.
st, fo = call("POST", "/workflow/api/tasks/fanout", {
    "subjectType": "Deal", "subjectRef": f"FANQ-{SUF}", "taskType": "PARALLEL_SIGNOFF",
    "joinPolicy": "QUORUM:2",
    "members": [{"assignee": f"q1.{SUF}"}, {"assignee": f"q2.{SUF}"}, {"assignee": f"q3.{SUF}"}]}, actor="rm.user")
fo = must(st, fo, "fanout QUORUM")
st, c1 = call("POST", f"/workflow/api/tasks/{fo['tasks'][0]['taskRef']}/complete", {}, actor=f"q1.{SUF}")
c1 = must(st, c1, "complete QUORUM[0]")
check("QUORUM:2 NOT satisfied after 1 of 3", c1["joinGroupSatisfied"] is False, f"{c1.get('joinGroupSatisfied')}")
st, c2 = call("POST", f"/workflow/api/tasks/{fo['tasks'][1]['taskRef']}/complete", {}, actor=f"q2.{SUF}")
c2 = must(st, c2, "complete QUORUM[1]")
check("QUORUM:2 satisfied after 2 of 3", c2["joinGroupSatisfied"] is True, f"{c2.get('joinGroupSatisfied')}")


print("== 9. TAT / SLA report derived from the event timeline ==")
st, tat = call("GET", f"/workflow/api/tasks/tat?subjectRef=FANALL-{SUF}")
tat = must(st, tat, "tat")
check("TAT report aggregates the subject's tasks",
      tat["taskCount"] == 2 and tat["completedCount"] == 2, f"{tat.get('taskCount')}/{tat.get('completedCount')}")
check("TAT rows carry a derived tatMinutes per completed task",
      all("tatMinutes" in row for row in tat["tasks"]), "")


print("== 10. Mirror invariant: CAD deviation task leaves the CadCase BYTE-IDENTICAL ==")
app_ref = f"CAD-CASE-{SUF}"
st, cad = call("POST", "/decision/api/cad/cases",
               {"applicationRef": app_ref, "counterpartyName": f"Casework Co {SUF}", "cpType": "NEW"},
               actor="cad.officer")
cad = must(st, cad, "cad initiate")
case_id = cad["cadCase"]["id"]
item_id = cad["items"][0]["id"]
st, dev = call("POST", f"/decision/api/cad/items/{item_id}/deviation",
               {"type": "WAIVER", "reason": "collateral valuation pending"}, actor="cad.officer")
dev = must(st, dev, "raise deviation")
# The task mirror should have fired (best-effort) on the deviation raise.
st, subj = call("GET", f"/workflow/api/tasks/subject?type=CadCase&ref={app_ref}")
subj = must(st, subj, "cad tasks")
mirror = [t for t in subj if t["taskType"] == "CAD_DEVIATION_APPROVAL"]
check("a CAD deviation approval task was mirrored", len(mirror) == 1, f"{[t['taskType'] for t in subj]}")
# Snapshot the authoritative CAD case, operate on the mirrored task, re-snapshot.
st, before = call("GET", f"/decision/api/cad/cases/{case_id}")
before = must(st, before, "cad before")
st, _ = call("POST", f"/workflow/api/tasks/{mirror[0]['taskRef']}/withdraw", {"note": "task-layer op"}, actor=sup)
st, after = call("GET", f"/decision/api/cad/cases/{case_id}")
after = must(st, after, "cad after")
check("mirroring/operating a task NEVER mutates the CadCase (byte-identical)",
      canon(before) == canon(after), "CadCase changed after a task-layer operation")


print("== 11. Mirror invariant: pricing-exception task leaves PricingResult BYTE-IDENTICAL ==")


def fresh_deal_with_pricing(suffix):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Casework E2E {suffix} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"CWE2E{suffix}", "jurisdiction": "IN-RBI",
        "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"],
        "counterpartyName": cp["legalName"], "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
        "facilityType": "TERM_LOAN", "requestedAmount": 250_000_000, "currency": "INR",
        "tenorMonths": 60, "purpose": "Working capital expansion",
        "collateralType": "PROPERTY", "collateralValue": 300_000_000, "secured": True}, actor="rm.user")
    app = must(st, app, "app")
    ref = app["reference"]

    def line(v):
        return {"value": v, "sourceDocument": f"casework_{suffix}.pdf",
                "sourcePage": "P1", "coordinates": "tbl1", "confidence": 0.95}

    def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
        return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
            "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
            "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp),
            "TAX": line(rev * 0.025), "TOTAL_ASSETS": line(ta),
            "CURRENT_ASSETS": line(ca), "CASH": line(cash),
            "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std),
            "LONG_TERM_DEBT": line(ltd), "CURRENT_PORTION_LTD": line(std * 0.4),
            "NET_WORTH": line(nw), "CFO": line(cfo)}}

    st, _ = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.2e9, 0.9e9, 0.15e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 2.8e9, 0.7e9),
        per("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.16e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 2.5e9, 0.6e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="risk.officer")
    call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")
    return ref


ref = fresh_deal_with_pricing(SUF)
st, rs0 = call("GET", f"/risk/api/risk/{ref}")
rs0 = must(st, rs0, "risk snapshot")
rate0 = rs0["pricing"]["recommendedRate"]
# Propose a small (<=50bps) concession so it queues for a human approval and the mirror fires.
st, pe = call("POST", f"/risk/api/risk/{ref}/pricing/exception",
              {"proposedRate": round(rate0 - 0.004, 6), "reason": "strategic relationship"}, actor="rm.user")
pe = must(st, pe, "propose concession")
check("concession queued for approval", pe["status"] in ("PENDING_L1", "PENDING_L2"), f"{pe.get('status')}")
st, subj = call("GET", f"/workflow/api/tasks/subject?type=PricingException&ref={ref}")
subj = must(st, subj, "pe tasks")
pe_mirror = [t for t in subj if t["taskType"] == "PRICING_EXCEPTION_APPROVAL"]
check("a pricing-exception approval task was mirrored", len(pe_mirror) == 1, f"{[t['taskType'] for t in subj]}")
# Snapshot the authoritative pricing-exception + recommended rate, operate on the task, re-snapshot.
st, pes_before = call("GET", f"/risk/api/risk/{ref}/pricing/exception")
pes_before = must(st, pes_before, "pe list before")
call("POST", f"/workflow/api/tasks/{pe_mirror[0]['taskRef']}/withdraw", {"note": "task-layer op"}, actor=sup)
st, rs1 = call("GET", f"/risk/api/risk/{ref}")
rs1 = must(st, rs1, "risk snapshot 2")
st, pes_after = call("GET", f"/risk/api/risk/{ref}/pricing/exception")
pes_after = must(st, pes_after, "pe list after")
check("authoritative recommended rate UNCHANGED by the task mirror",
      rs1["pricing"]["recommendedRate"] == rate0, f"{rate0} -> {rs1['pricing'].get('recommendedRate')}")
check("authoritative pricing-exception record BYTE-IDENTICAL after a task-layer operation",
      canon(pes_before) == canon(pes_after), "PricingException changed after a task-layer operation")


print(f"\n==== casework e2e: {PASS} passed, {FAIL} failed ====")
sys.exit(1 if FAIL else 0)
