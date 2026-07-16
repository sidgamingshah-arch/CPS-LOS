#!/usr/bin/env python3
"""
Workflow-ENGINE e2e — exercises the WORKFLOW_DEFINITION engine's parallel-stage
paths (parallelGroup / joinPolicy / queueKey) and the send-back / withdraw guards
that a security review found missing.

It seeds a bespoke, dual-signed WORKFLOW_DEFINITION pack for a run-unique
jurisdiction whose stages fan out into a parallel group (START → {REVIEW_A ||
REVIEW_B, join=ANY} → FINALIZE, with a queueKey on the parallel stages so the
stage-entry case-management mirror fires), then proves:

  * co-entry — completing START opens BOTH parallel siblings IN_PROGRESS;
  * a stage-entry mirror WorkItem is opened on the parallel stage's queue;
  * ANY join — completing one sibling SKIPS the still-open sibling (it is never
    left IN_PROGRESS forever) and advances the cursor past the whole group (fix #5);
  * forward send-back is REJECTED — the target must sit strictly before the
    current stage; a same-ordinal or ahead target is refused (fix #4);
  * send-back / withdraw both require a named actor (blank X-Actor -> 403) (fix #4);
  * a backwards send-back re-enters the stage and REOPENS a fresh mirror task
    (rework) rather than returning the stale COMPLETED one (fix #7);
  * withdraw with a named actor terminates an ACTIVE instance.

Register this suite with the coordinator (run_regression.py SUITES) as
`e2e_workflow_engine`.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
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


def view(ref):
    st, v = call("GET", f"/workflow/api/workflow/instances/{ref}")
    v = must(st, v, f"view {ref}")
    return {s["stageKey"]: s for s in v["stages"]}, v["instance"]


def stage_mirrors(ref, stage_key):
    st, subj = call("GET", f"/workflow/api/tasks/subject?type=WorkflowInstance&ref={ref}", actor="engine.workflow")
    subj = must(st, subj, f"subject tasks {ref}")
    return [t for t in subj if t["taskType"] == "STAGE_" + stage_key]


JUR = f"ZZ-ENG-{SUF}"
SEG = "ENGTEST"
CODE = f"workflow_engtest_{SUF}"
Q_A, Q_B = f"ENG_A_{SUF}", f"ENG_B_{SUF}"

print("== 0. Seed a bespoke parallel WORKFLOW_DEFINITION pack (dual-signed) ==")
stages = [
    {"key": "START", "label": "Start", "autonomy": "A", "ai": False, "humanGate": False, "slaHours": 24},
    {"key": "REVIEW_A", "label": "Review A", "autonomy": "A", "ai": False, "humanGate": False,
     "slaHours": 24, "parallelGroup": "G1", "joinPolicy": "ANY", "queueKey": Q_A},
    {"key": "REVIEW_B", "label": "Review B", "autonomy": "A", "ai": False, "humanGate": False,
     "slaHours": 24, "parallelGroup": "G1", "joinPolicy": "ANY", "queueKey": Q_B},
    {"key": "FINALIZE", "label": "Finalize", "autonomy": "A", "ai": False, "humanGate": False, "slaHours": 24},
]
st, draft = call("POST", "/config/api/rulepacks",
                 {"jurisdiction": JUR, "type": "WORKFLOW_DEFINITION", "code": CODE,
                  "payload": {"segment": SEG, "stages": stages}}, actor="wf.author")
draft = must(st, draft, "draft pack")
pack_id = draft["id"]
st, _ = call("POST", f"/config/api/rulepacks/{pack_id}/signoff?control=policy", actor="wf.policy")
must(st, _, "policy signoff")
st, signed = call("POST", f"/config/api/rulepacks/{pack_id}/signoff?control=model-risk", actor="wf.modelrisk")
signed = must(st, signed, "model-risk signoff")
check("pack is dual-signed and active", signed.get("active") is True, f"{signed.get('active')}")


print("== 1. Materialise an instance from the bespoke pack ==")
APP1 = f"ENG-APP1-{SUF}"
st, mat = call("POST", "/workflow/api/workflow/instances",
               {"applicationReference": APP1, "jurisdiction": JUR, "segment": SEG}, actor="rm.user")
mat = must(st, mat, "materialise APP1")
check("instance ACTIVE from the bespoke pack (not the linear fallback)",
      mat["status"] == "ACTIVE" and mat["definitionCode"] == CODE, f"code={mat.get('definitionCode')}")
by_key, _ = view(APP1)
check("4 stages materialised", len(by_key) == 4, f"{list(by_key)}")
check("START is IN_PROGRESS, parallel siblings PENDING",
      by_key["START"]["status"] == "IN_PROGRESS"
      and by_key["REVIEW_A"]["status"] == "PENDING"
      and by_key["REVIEW_B"]["status"] == "PENDING", f"{[(k, v['status']) for k, v in by_key.items()]}")


print("== 2. Complete START -> BOTH parallel siblings co-enter; a mirror opens ==")
st, adv = call("POST", f"/workflow/api/workflow/instances/{APP1}/advance",
               {"stageKey": "START", "actorType": "SYSTEM", "note": "start done"}, actor="engine.workflow")
adv = must(st, adv, "advance START")
by_key, _ = view(APP1)
check("both parallel siblings are IN_PROGRESS (co-entered)",
      by_key["REVIEW_A"]["status"] == "IN_PROGRESS" and by_key["REVIEW_B"]["status"] == "IN_PROGRESS",
      f"A={by_key['REVIEW_A']['status']} B={by_key['REVIEW_B']['status']}")
mirrors_a = stage_mirrors(APP1, "REVIEW_A")
check("a stage-entry mirror WorkItem opened on the parallel stage's queue",
      len(mirrors_a) == 1 and mirrors_a[0]["queueKey"] == Q_A,
      f"{[(t['taskRef'], t['queueKey'], t['status']) for t in mirrors_a]}")


print("== 3. Send-back guards: blank actor + forward target are rejected (fix #4) ==")
# Blank X-Actor -> 403 regardless of target.
st, err = call("POST", f"/workflow/api/workflow/instances/{APP1}/send-back", {"toStageKey": "START", "note": "x"})
check("send-back with blank actor -> 403", st == 403, f"{st} {err}")
# Forward target (FINALIZE sits after the current REVIEW_A) -> rejected.
st, err = call("POST", f"/workflow/api/workflow/instances/{APP1}/send-back",
               {"toStageKey": "FINALIZE", "note": "jump forward"}, actor="credit.head")
check("forward send-back (ahead of current) -> rejected", st in (400, 409), f"{st} {err}")
# Same-ordinal target (the current stage itself) is not 'backwards' -> rejected.
st, err = call("POST", f"/workflow/api/workflow/instances/{APP1}/send-back",
               {"toStageKey": "REVIEW_A", "note": "same stage"}, actor="credit.head")
check("send-back to the current stage (not backwards) -> rejected", st in (400, 409), f"{st} {err}")
by_key, inst = view(APP1)
check("rejected send-backs left the instance untouched (still ACTIVE at REVIEW_A/B)",
      inst["status"] == "ACTIVE" and by_key["REVIEW_A"]["status"] == "IN_PROGRESS", f"{inst.get('status')}")


print("== 4. ANY join: one completion SKIPS the open sibling + advances past the group (fix #5) ==")
st, adv = call("POST", f"/workflow/api/workflow/instances/{APP1}/advance",
               {"stageKey": "REVIEW_A", "actorType": "SYSTEM", "note": "review A done"}, actor="engine.workflow")
adv = must(st, adv, "advance REVIEW_A")
by_key, inst = view(APP1)
check("winning sibling REVIEW_A is COMPLETE", by_key["REVIEW_A"]["status"] == "COMPLETE",
      f"{by_key['REVIEW_A']['status']}")
check("non-winning sibling REVIEW_B is SKIPPED (not left IN_PROGRESS)",
      by_key["REVIEW_B"]["status"] == "SKIPPED", f"{by_key['REVIEW_B']['status']}")
check("cursor advanced past the group to FINALIZE",
      inst["currentStageKey"] == "FINALIZE" and by_key["FINALIZE"]["status"] == "IN_PROGRESS",
      f"cur={inst.get('currentStageKey')} fin={by_key['FINALIZE']['status']}")
# A later advance() on the passed (SKIPPED) sibling must be refused.
st, err = call("POST", f"/workflow/api/workflow/instances/{APP1}/advance",
               {"stageKey": "REVIEW_B", "actorType": "SYSTEM", "note": "too late"}, actor="engine.workflow")
check("advancing an already-SKIPPED sibling -> rejected", st in (400, 409), f"{st} {err}")


print("== 5. Backwards send-back re-enters the stage + REOPENS a fresh mirror (fix #7) ==")
before = len(stage_mirrors(APP1, "REVIEW_A"))
st, sb = call("POST", f"/workflow/api/workflow/instances/{APP1}/send-back",
              {"toStageKey": "REVIEW_A", "note": "rework the review"}, actor="credit.head")
sb = must(st, sb, "backwards send-back")
by_key, inst = view(APP1)
check("send-back re-armed REVIEW_A to IN_PROGRESS and reset downstream to PENDING",
      inst["currentStageKey"] == "REVIEW_A" and by_key["REVIEW_A"]["status"] == "IN_PROGRESS"
      and by_key["FINALIZE"]["status"] == "PENDING", f"cur={inst.get('currentStageKey')}")
after = len(stage_mirrors(APP1, "REVIEW_A"))
check("re-entry opened a NEW mirror task (rework), not the stale COMPLETED one",
      after == before + 1, f"before={before} after={after}")


print("== 6. Withdraw requires a named actor (fix #4) ==")
APP2 = f"ENG-APP2-{SUF}"
st, _ = call("POST", "/workflow/api/workflow/instances",
             {"applicationReference": APP2, "jurisdiction": JUR, "segment": SEG}, actor="rm.user")
must(st, _, "materialise APP2")
st, err = call("POST", f"/workflow/api/workflow/instances/{APP2}/withdraw", {"note": "no actor"})
check("withdraw with blank actor -> 403", st == 403, f"{st} {err}")
st, wd = call("POST", f"/workflow/api/workflow/instances/{APP2}/withdraw", {"note": "abandon"}, actor="credit.head")
wd = must(st, wd, "withdraw with actor")
check("withdraw with a named actor terminates the instance", wd["status"] == "WITHDRAWN", f"{wd.get('status')}")


print(f"\n==== workflow-engine e2e: {PASS} passed, {FAIL} failed ====")
sys.exit(1 if FAIL else 0)
