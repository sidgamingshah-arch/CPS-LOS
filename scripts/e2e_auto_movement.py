#!/usr/bin/env python3
"""
Generalized automatic case movement (workflow-service) — self-contained e2e.

Closes CLoM gap #72: config-driven, condition-based automatic case movement
(auto-advance a stage after N hours; auto-lapse a request/work-item after N hours
of inactivity) layered additively on top of the existing SLA-breach sweep.

This suite is FULLY self-contained. It boots ONLY:
  * config-service  (to author + dual-sign the bespoke WORKFLOW_DEFINITION packs), and
  * workflow-service (the feature under test),
on ALT ports (never 8080-8089), drives the REST API directly, and stops what it started.

It proves the three non-negotiables:

  1. AUTO-LAPSE — a stage (and a work-item) that declares `autoLapseAfterHours` is
     auto-lapsed by the sweep; an append-only transition/event is written and the audit
     actorType is SYSTEM.
  2. REGRESSION SAFETY — a definition / work-item WITHOUT any auto key is byte-identical
     before and after the sweep (the core safety proof — this is why the feature can ship
     default-ON).
  3. AUTO-ADVANCE — a stage that declares `autoAdvanceAfterHours` is auto-advanced (SYSTEM),
     while a human-gated stage that declares the SAME key is NEVER auto-advanced.

Elapsed time is simulated with a threshold of 0 hours (eligible the instant dwell > 0);
the suite sleeps briefly so `now` is strictly after the stage-entry / task-creation stamp.

Not registered in run_regression (the coordinator registers suites centrally); run standalone.
"""
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASS, FAIL = 0, 0
SUF = str(int(time.time() * 1000))[-7:]

PROCS = []
TMPDIRS = []


# ------------------------------------------------------------------ tiny harness
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
        shutdown()
        sys.exit(1)
    return b


def _call(base, method, path, body=None, actor=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
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


# ------------------------------------------------------------------ process mgmt
def free_port():
    while True:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        p = s.getsockname()[1]
        s.close()
        if not (8080 <= p <= 8089):   # never collide with the shared dev stack
            return p


def start_service(jar, port, extra_env=None, name=""):
    data_dir = tempfile.mkdtemp(prefix=f"helix-{name}-")
    TMPDIRS.append(data_dir)
    env = dict(os.environ)
    env["SERVER_PORT"] = str(port)
    env["HELIX_DATA_DIR"] = data_dir
    if extra_env:
        env.update(extra_env)
    log = open(os.path.join(data_dir, "out.log"), "w")
    proc = subprocess.Popen(["java", "-jar", jar], env=env, stdout=log, stderr=subprocess.STDOUT)
    PROCS.append(proc)
    return proc


def wait_health(port, proc, timeout=90):
    base = f"http://127.0.0.1:{port}"
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            print(f"  ERROR service on :{port} exited early (rc={proc.returncode})")
            return False
        try:
            st, b = _call(base, "GET", "/actuator/health")
            if st == 200 and isinstance(b, dict) and b.get("status") == "UP":
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def shutdown():
    for p in PROCS:
        try:
            p.send_signal(signal.SIGTERM)
        except Exception:
            pass
    for p in PROCS:
        try:
            p.wait(timeout=20)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
    for d in TMPDIRS:
        shutil.rmtree(d, ignore_errors=True)


# ------------------------------------------------------------------ pack authoring
def author_pack(cfg, jur, seg, code, stages):
    st, draft = _call(cfg, "POST", "/api/rulepacks",
                      {"jurisdiction": jur, "type": "WORKFLOW_DEFINITION", "code": code,
                       "payload": {"segment": seg, "stages": stages}}, actor="wf.author")
    draft = must(st, draft, f"author pack {code}")
    pid = draft["id"]
    st, _ = _call(cfg, "POST", f"/api/rulepacks/{pid}/signoff?control=policy", actor="wf.policy")
    must(st, _, f"policy signoff {code}")
    st, signed = _call(cfg, "POST", f"/api/rulepacks/{pid}/signoff?control=model-risk", actor="wf.modelrisk")
    signed = must(st, signed, f"model-risk signoff {code}")
    return signed


def stg(key, **kw):
    s = {"key": key, "label": key.title(), "autonomy": kw.get("autonomy", "A"),
         "ai": False, "humanGate": kw.get("humanGate", False), "slaHours": kw.get("slaHours", 24)}
    for k in ("autoAdvanceAfterHours", "autoLapseAfterHours", "autoLapseToStatus"):
        if k in kw:
            s[k] = kw[k]
    return s


def main():
    global PASS, FAIL
    cfg_port, wf_port = free_port(), free_port()
    while wf_port == cfg_port:
        wf_port = free_port()
    CFG = f"http://127.0.0.1:{cfg_port}"
    WF = f"http://127.0.0.1:{wf_port}"

    cfg_jar = os.path.join(ROOT, "config-service", "target", "config-service.jar")
    wf_jar = os.path.join(ROOT, "workflow-service", "target", "workflow-service.jar")
    for jar in (cfg_jar, wf_jar):
        if not os.path.exists(jar):
            print(f"  ERROR missing jar {jar} — build with: "
                  f"mvn -q -pl workflow-service,config-service -am package -DskipTests -Dmaven.compiler.release=21")
            sys.exit(1)

    print(f"== 0. Boot config-service :{cfg_port} + workflow-service :{wf_port} (alt ports) ==")
    cfg_proc = start_service(cfg_jar, cfg_port, name="config")
    if not wait_health(cfg_port, cfg_proc):
        print("  ERROR config-service did not become healthy")
        shutdown()
        sys.exit(1)
    wf_proc = start_service(wf_jar, wf_port,
                            extra_env={"CONFIG_SERVICE_URL": CFG}, name="workflow")
    if not wait_health(wf_port, wf_proc):
        print("  ERROR workflow-service did not become healthy")
        shutdown()
        sys.exit(1)
    check("both services booted healthy on alt ports (not 8080-8089)", True)

    # helpers bound to the two bases
    def wf(method, path, body=None, actor=None):
        return _call(WF, method, path, body, actor)

    def view(ref):
        st, v = wf("GET", f"/api/workflow/instances/{ref}")
        v = must(st, v, f"view {ref}")
        return {s["stageKey"]: s for s in v["stages"]}, v["instance"], v["transitions"]

    def audit_of(ref, subj_type="WorkflowInstance"):
        st, a = wf("GET", f"/api/audit/subject?type={subj_type}&id={ref}")
        return a if st == 200 and isinstance(a, list) else []

    # ------------------------------------------------------------------ seed packs
    print("\n== 1. Author + dual-sign bespoke WORKFLOW_DEFINITION packs (with auto keys) ==")
    J_LAP, J_NOK, J_ADV, J_GAT, J_EXP = (f"ZZ-LAP-{SUF}", f"ZZ-NOK-{SUF}", f"ZZ-ADV-{SUF}",
                                         f"ZZ-GAT-{SUF}", f"ZZ-EXP-{SUF}")
    S_LAP, S_NOK, S_ADV, S_GAT, S_EXP = "LAPSEG", "NOKSEG", "ADVSEG", "GATSEG", "EXPSEG"

    p1 = author_pack(CFG, J_LAP, S_LAP, f"wf_lap_{SUF}",
                     [stg("S1", autoLapseAfterHours=0), stg("S2")])
    check("LAPSE pack is dual-signed + active", p1.get("active") is True, str(p1.get("active")))

    p2 = author_pack(CFG, J_NOK, S_NOK, f"wf_nok_{SUF}",
                     [stg("N1"), stg("N2")])
    check("NO-KEYS pack is dual-signed + active", p2.get("active") is True, str(p2.get("active")))
    check("NO-KEYS pack stages declare NO auto keys (regression baseline)",
          all("autoAdvanceAfterHours" not in s and "autoLapseAfterHours" not in s
              for s in p2["payload"]["stages"]), str(p2["payload"]["stages"]))

    p3 = author_pack(CFG, J_ADV, S_ADV, f"wf_adv_{SUF}",
                     [stg("A1", autoAdvanceAfterHours=0), stg("A2"), stg("A3")])
    check("ADVANCE pack is dual-signed + active", p3.get("active") is True, str(p3.get("active")))

    p4 = author_pack(CFG, J_GAT, S_GAT, f"wf_gat_{SUF}",
                     [stg("G1", humanGate=True, autonomy="D", autoAdvanceAfterHours=0), stg("G2")])
    check("HUMAN-GATE pack is dual-signed + active", p4.get("active") is True, str(p4.get("active")))

    p5 = author_pack(CFG, J_EXP, S_EXP, f"wf_exp_{SUF}",
                     [stg("E1", autoLapseAfterHours=0, autoLapseToStatus="EXPIRED"), stg("E2")])
    check("EXPIRE pack (custom autoLapseToStatus) is dual-signed + active",
          p5.get("active") is True, str(p5.get("active")))

    # ------------------------------------------------------------------ materialise
    print("\n== 2. Materialise one instance per pack ==")
    LAP, NOK, ADV, GAT, EXP = (f"LAP-{SUF}", f"NOK-{SUF}", f"ADV-{SUF}", f"GAT-{SUF}", f"EXP-{SUF}")
    for ref, jur, seg in ((LAP, J_LAP, S_LAP), (NOK, J_NOK, S_NOK), (ADV, J_ADV, S_ADV),
                          (GAT, J_GAT, S_GAT), (EXP, J_EXP, S_EXP)):
        st, m = wf("POST", "/api/workflow/instances",
                   {"applicationReference": ref, "jurisdiction": jur, "segment": seg}, actor="rm.user")
        m = must(st, m, f"materialise {ref}")
        check(f"{ref} materialised ACTIVE from its bespoke pack (not the linear fallback)",
              m["status"] == "ACTIVE" and m["definitionVersion"] >= 1, str(m.get("status")))

    lap_before, _, _ = view(LAP)
    check("LAPSE stage S1 read back its autoLapseAfterHours=0 key from the pack",
          lap_before["S1"].get("autoLapseAfterHours") == 0, str(lap_before["S1"].get("autoLapseAfterHours")))
    adv_by, _, _ = view(ADV)
    check("ADVANCE stage A1 read back its autoAdvanceAfterHours=0 key",
          adv_by["A1"].get("autoAdvanceAfterHours") == 0, str(adv_by["A1"].get("autoAdvanceAfterHours")))

    # NO-KEYS: capture a full byte-level snapshot BEFORE the sweep (the regression baseline).
    nok_stages_before, nok_inst_before, nok_tx_before = view(NOK)
    nok_snapshot = json.dumps({
        "instanceStatus": nok_inst_before["status"],
        "currentStageKey": nok_inst_before["currentStageKey"],
        "stages": {k: (v["status"], v.get("enteredAt"), v.get("completedAt")) for k, v in nok_stages_before.items()},
        "txCount": len(nok_tx_before),
    }, sort_keys=True)

    # ------------------------------------------------------------------ work-items
    print("\n== 3. Create work-items (one WITH autoLapseAfterHours, one WITHOUT) ==")
    st, wi_lapse = wf("POST", "/api/tasks",
                      {"subjectType": "AutoTest", "subjectRef": f"WI-{SUF}", "taskType": "LAPSE_ME",
                       "autoLapseAfterHours": 0}, actor="rm.user")
    wi_lapse = must(st, wi_lapse, "create auto-lapse task")
    check("auto-lapse task created OPEN with autoLapseAfterHours=0",
          wi_lapse["status"] in ("OPEN", "ASSIGNED") and wi_lapse.get("autoLapseAfterHours") == 0,
          str((wi_lapse.get("status"), wi_lapse.get("autoLapseAfterHours"))))
    LAPSE_TASK = wi_lapse["taskRef"]

    st, wi_keep = wf("POST", "/api/tasks",
                     {"subjectType": "AutoTest", "subjectRef": f"WI-{SUF}", "taskType": "KEEP_ME"},
                     actor="rm.user")
    wi_keep = must(st, wi_keep, "create normal task")
    check("normal task created with autoLapseAfterHours null (no opt-in)",
          wi_keep.get("autoLapseAfterHours") is None, str(wi_keep.get("autoLapseAfterHours")))
    KEEP_TASK = wi_keep["taskRef"]

    # ------------------------------------------------------------------ the sweep
    print("\n== 4. Auto-movement sweep ==")
    time.sleep(1.5)   # ensure now > entry/creation stamps for the 0-hour thresholds
    st, sweep = wf("POST", "/api/workflow/auto-movement/sweep")
    sweep = must(st, sweep, "auto-movement sweep")
    check("sweep endpoint returns a {moved: n} count (mirrors /sla-sweep)",
          "moved" in sweep and isinstance(sweep["moved"], int), str(sweep))
    check("sweep moved exactly 4 cases (LAPSE + ADVANCE + EXPIRE instances + 1 work-item)",
          sweep.get("moved") == 4, f"moved={sweep.get('moved')}")

    # ------------------------------------------------------------------ 1. AUTO-LAPSE (stage)
    print("\n== 5. AUTO-LAPSE proven (stage -> whole instance) ==")
    lap_by, lap_inst, lap_tx = view(LAP)
    check("LAPSE instance is now LAPSED", lap_inst["status"] == "LAPSED", str(lap_inst["status"]))
    lap_moves = [t for t in lap_tx if t["kind"] == "AUTO_LAPSED"]
    check("an append-only AUTO_LAPSED transition was recorded", len(lap_moves) == 1,
          str([t["kind"] for t in lap_tx]))
    check("...the transition actorType is SYSTEM (actor system.auto-movement)",
          lap_moves and lap_moves[0]["actorType"] == "SYSTEM"
          and lap_moves[0]["actor"] == "system.auto-movement", str(lap_moves[:1]))
    lap_aud = [e for e in audit_of(LAP) if e.get("eventType") == "WORKFLOW_AUTO_LAPSED"]
    check("an audit WORKFLOW_AUTO_LAPSED event with actorType SYSTEM was stamped",
          any(e.get("actorType") == "SYSTEM" for e in lap_aud), str(lap_aud[:1]))

    # ------------------------------------------------------------------ 2. REGRESSION SAFETY
    print("\n== 6. REGRESSION: a def WITHOUT auto keys is byte-identical after the sweep ==")
    nok_stages_after, nok_inst_after, nok_tx_after = view(NOK)
    nok_snapshot_after = json.dumps({
        "instanceStatus": nok_inst_after["status"],
        "currentStageKey": nok_inst_after["currentStageKey"],
        "stages": {k: (v["status"], v.get("enteredAt"), v.get("completedAt")) for k, v in nok_stages_after.items()},
        "txCount": len(nok_tx_after),
    }, sort_keys=True)
    check("NO-KEYS instance is byte-identical before vs after the sweep",
          nok_snapshot == nok_snapshot_after, f"{nok_snapshot} != {nok_snapshot_after}")
    check("NO-KEYS instance still ACTIVE (not lapsed)", nok_inst_after["status"] == "ACTIVE",
          str(nok_inst_after["status"]))
    check("NO-KEYS stages untouched (N1 IN_PROGRESS, N2 PENDING)",
          nok_stages_after["N1"]["status"] == "IN_PROGRESS" and nok_stages_after["N2"]["status"] == "PENDING",
          str([(k, v["status"]) for k, v in nok_stages_after.items()]))

    # ------------------------------------------------------------------ 3. AUTO-ADVANCE
    print("\n== 7. AUTO-ADVANCE proven (non-gated stage) ==")
    adv_by, adv_inst, adv_tx = view(ADV)
    check("ADVANCE stage A1 is now COMPLETE", adv_by["A1"]["status"] == "COMPLETE", str(adv_by["A1"]["status"]))
    check("...completed BY the SYSTEM auto-movement actor",
          adv_by["A1"].get("completedByType") == "SYSTEM"
          and adv_by["A1"].get("completedBy") == "system.auto-movement",
          str((adv_by["A1"].get("completedByType"), adv_by["A1"].get("completedBy"))))
    check("next stage A2 auto-entered IN_PROGRESS", adv_by["A2"]["status"] == "IN_PROGRESS",
          str(adv_by["A2"]["status"]))
    check("instance cursor advanced to A2 and stayed ACTIVE",
          adv_inst["currentStageKey"] == "A2" and adv_inst["status"] == "ACTIVE",
          str((adv_inst.get("currentStageKey"), adv_inst.get("status"))))
    adv_moves = [t for t in adv_tx if t["kind"] == "AUTO_ADVANCED" and t["actorType"] == "SYSTEM"]
    check("an append-only AUTO_ADVANCED transition (actorType SYSTEM) was recorded",
          len(adv_moves) == 1, str([t["kind"] for t in adv_tx]))
    adv_aud = [e for e in audit_of(ADV) if e.get("eventType") == "WORKFLOW_AUTO_ADVANCED"]
    check("an audit WORKFLOW_AUTO_ADVANCED event with actorType SYSTEM was stamped",
          any(e.get("actorType") == "SYSTEM" for e in adv_aud), str(adv_aud[:1]))

    # ------------------------------------------------------------------ human-gate protection
    print("\n== 8. HUMAN GATE is never auto-advanced (a human still owns it) ==")
    gat_by, gat_inst, gat_tx = view(GAT)
    check("human-gated G1 (declares autoAdvanceAfterHours) is STILL IN_PROGRESS — not auto-advanced",
          gat_by["G1"]["status"] == "IN_PROGRESS", str(gat_by["G1"]["status"]))
    check("human-gate instance stayed ACTIVE at G1",
          gat_inst["status"] == "ACTIVE" and gat_inst["currentStageKey"] == "G1",
          str((gat_inst.get("status"), gat_inst.get("currentStageKey"))))
    check("no AUTO_ADVANCED transition exists for the human-gated instance",
          not any(t["kind"] in ("AUTO_ADVANCED", "AUTO_COMPLETED") for t in gat_tx),
          str([t["kind"] for t in gat_tx]))

    # ------------------------------------------------------------------ custom lapse status
    print("\n== 9. Custom autoLapseToStatus honoured ==")
    _, exp_inst, exp_tx = view(EXP)
    check("EXPIRE instance lapsed to the pack's custom status EXPIRED", exp_inst["status"] == "EXPIRED",
          str(exp_inst["status"]))
    check("EXPIRE instance has an AUTO_LAPSED transition",
          any(t["kind"] == "AUTO_LAPSED" for t in exp_tx), str([t["kind"] for t in exp_tx]))

    # ------------------------------------------------------------------ work-item lapse + keep
    print("\n== 10. AUTO-LAPSE (work-item) + the non-opted-in item is untouched ==")
    st, t_lapse = wf("GET", f"/api/tasks/{LAPSE_TASK}")
    t_lapse = must(st, t_lapse, "get lapsed task")
    check("auto-lapse work-item is now LAPSED", t_lapse["status"] == "LAPSED", str(t_lapse["status"]))
    st, tl = wf("GET", f"/api/tasks/{LAPSE_TASK}/timeline")
    tl = must(st, tl, "task timeline")
    lapse_events = [e for e in tl if e["event"] == "AUTO_LAPSED"]
    check("work-item timeline appended an AUTO_LAPSED event with actorType SYSTEM",
          lapse_events and lapse_events[0]["actorType"] == "SYSTEM", str(lapse_events[:1]))
    wi_aud = [e for e in audit_of(LAPSE_TASK, "WorkItem") if e.get("eventType") == "TASK_AUTO_LAPSED"]
    check("work-item auto-lapse stamped an audit TASK_AUTO_LAPSED (SYSTEM) event",
          any(e.get("actorType") == "SYSTEM" for e in wi_aud), str(wi_aud[:1]))
    st, t_keep = wf("GET", f"/api/tasks/{KEEP_TASK}")
    t_keep = must(st, t_keep, "get kept task")
    check("the non-opted-in work-item is UNTOUCHED (still open)",
          t_keep["status"] in ("OPEN", "ASSIGNED"), str(t_keep["status"]))

    # ------------------------------------------------------------------ idempotency
    print("\n== 11. Idempotency: re-running the sweep moves nothing further ==")
    st, sweep2 = wf("POST", "/api/workflow/auto-movement/sweep")
    sweep2 = must(st, sweep2, "second sweep")
    check("a second sweep moves 0 cases (terminal instances/items are skipped, "
          "and A2/G1/N1 declare no rule)", sweep2.get("moved") == 0, f"moved={sweep2.get('moved')}")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        print(f"  ERROR unhandled: {e}")
        FAIL += 1
    finally:
        shutdown()
    print(f"\n== Auto-movement e2e: {PASS} passed, {FAIL} failed ==")
    sys.exit(0 if FAIL == 0 else 1)
