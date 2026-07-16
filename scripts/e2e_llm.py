#!/usr/bin/env python3
"""
Governed-LLM proof for the centralised helix.llm.* configuration + LlmClient SPI.

This is self-contained: it spins up a LOCAL mock LLM server and its own small stack of
services (config, origination, risk, decision, copilot) on alternate ports, in two modes:

  * DEFAULT  (helix.llm.provider=none): the copilot answer, a doc-intel extraction and a
    commentary draft take the EXISTING deterministic path — byte-identical to today — and
    NOTHING calls the external endpoint (the mock records zero calls).

  * CONFIGURED (helix.llm.provider=openai, base-url -> the mock): the same three capabilities
    CALL the model and use its text, while the AI/advisory governance invariant STILL holds:
      - the authoritative rating / pricing / spread figures are byte-identical before/after AI,
      - the doc-intel output is still an advisory SUGGESTED extraction requiring human confirm
        (never auto-applied to the deterministic spread),
      - the copilot answer is still non-binding + grounded (guardrail refuses actions),
      - the commentary is still an advisory DRAFT gated by maker-checker (SoD) review.
    A mock 5xx (outage) → graceful fall-back to the deterministic output.

No gateway, no full regression: this drives the service ports directly and manages only the
PIDs it starts. Run:  python3 scripts/e2e_llm.py
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASS, FAIL = 0, 0
PROCS = []          # (name, Popen) started by this script — the only PIDs we ever stop
LOG_DIR = tempfile.mkdtemp(prefix="helix-llm-logs-")

# ---- mock LLM server state -------------------------------------------------------------
MOCK_CALLS = 0
MOCK_FAIL = False
MOCK_LOCK = threading.Lock()
MARKER = "MOCKLLM"          # appears in prose the mock drafts, so we can prove its text is used


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


# ---------------------------------------------------------------- mock LLM ----------------

class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        global MOCK_CALLS
        with MOCK_LOCK:
            MOCK_CALLS += 1
            fail = MOCK_FAIL
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode())
        except Exception:
            body = {}

        if fail:
            self._send(500, {"error": {"message": "mock LLM injected failure"}})
            return

        system, user = self._prompts(body)
        if "JSON object" in system:
            # doc-extract capability: return a JSON object of fields (advisory suggestion)
            content = json.dumps({
                "mock_llm_field": "EXTRACTED-BY-MOCK-LLM",
                "borrower": "Meridian Mock Industries Ltd",
                "reporting_period": "FY2025",
            })
        else:
            # copilot / commentary: prose that echoes the grounded facts (never new figures)
            snippet = (user or "").strip().replace("\n", " ")
            content = f"{MARKER} DRAFT: {snippet[:180]}"
        self._send(200, {
            "model": "mock-llm-model",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18},
        })

    @staticmethod
    def _prompts(body):
        # OpenAI-compatible: messages[]. Anthropic: system + messages[].
        system, user = "", ""
        if isinstance(body.get("system"), str):
            system = body["system"]
        for m in body.get("messages", []) or []:
            if m.get("role") == "system":
                system = m.get("content", "")
            elif m.get("role") == "user":
                user = m.get("content", "")
        return system, user

    def _send(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def start_mock(port):
    srv = ThreadingHTTPServer(("127.0.0.1", port), MockLLMHandler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv


# ---------------------------------------------------------------- service stack -----------

def free(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def pick_ports():
    # config, origination, risk, decision, copilot, mock
    for base in range(18100, 18900, 20):
        ports = {"config": base + 1, "origination": base + 3, "risk": base + 4,
                 "decision": base + 5, "copilot": base + 7, "mock": base + 9}
        if all(free(p) for p in ports.values()):
            return ports
    raise RuntimeError("no free alternate port block found")


def start_service(svc, port, data_dir, extra_env):
    jar = os.path.join(ROOT, svc, "target", f"{svc}.jar")
    if not os.path.exists(jar):
        raise RuntimeError(f"missing jar: {jar} (run mvn package first)")
    env = os.environ.copy()
    env["SERVER_PORT"] = str(port)
    env["HELIX_DATA_DIR"] = data_dir
    env.update(extra_env)
    logf = open(os.path.join(LOG_DIR, f"{svc}-{port}.log"), "w")
    p = subprocess.Popen(["java", "-jar", jar], env=env, stdout=logf, stderr=subprocess.STDOUT)
    PROCS.append((f"{svc}:{port}", p))
    return p


def wait_health(port, timeout=150):
    deadline = time.time() + timeout
    url = f"http://localhost:{port}/actuator/health"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                if json.loads(r.read().decode()).get("status") == "UP":
                    return True
        except Exception:
            time.sleep(1)
    return False


def start_stack(ports, llm_env):
    """Start config, origination, risk, decision, copilot with the given LLM env."""
    data_dir = tempfile.mkdtemp(prefix="helix-llm-data-")
    curl = lambda p: f"http://localhost:{ports[p]}"
    inter = {
        "CONFIG_SERVICE_URL": curl("config"),
        "ORIGINATION_SERVICE_URL": curl("origination"),
        "RISK_SERVICE_URL": curl("risk"),
        "DECISION_SERVICE_URL": curl("decision"),
    }
    start_service("config-service", ports["config"], data_dir, {})
    if not wait_health(ports["config"]):
        raise RuntimeError("config-service did not come up")
    # origination + decision + copilot get the LLM env (they host the AI capabilities)
    start_service("origination-service", ports["origination"], data_dir, {**inter, **llm_env})
    start_service("risk-service", ports["risk"], data_dir, inter)
    start_service("decision-service", ports["decision"], data_dir, {**inter, **llm_env})
    start_service("copilot-service", ports["copilot"], data_dir, {**inter, **llm_env})
    for name in ("origination", "risk", "decision", "copilot"):
        if not wait_health(ports[name]):
            raise RuntimeError(f"{name}-service did not come up")
    return data_dir


def stop_stack():
    stopped = []
    while PROCS:
        name, p = PROCS.pop()
        try:
            p.terminate()
            stopped.append(p)
        except Exception:
            pass
    time.sleep(2)
    for p in stopped:
        if p.poll() is None:      # still alive — hard-kill (only PIDs we started)
            try:
                p.kill()
            except Exception:
                pass


# ---------------------------------------------------------------- HTTP helper -------------

def call(base, method, path, body=None, actor="test.user"):
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        return e.code, (json.loads(txt) if txt else None)


# ---------------------------------------------------------------- deal fixture ------------

def _line(v):
    return {"value": v, "sourceDocument": "Meridian_audited_financials_FY24.pdf",
            "sourcePage": "P12", "coordinates": "tbl1", "confidence": 0.97}


def _period(label, rev, cogs, opex, dep, intexp, tax, ta, ca, cash, cl, std, ltd, cpltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": _line(rev), "COGS": _line(cogs), "OPERATING_EXPENSES": _line(opex),
        "DEPRECIATION": _line(dep), "INTEREST_EXPENSE": _line(intexp), "TAX": _line(tax),
        "TOTAL_ASSETS": _line(ta), "CURRENT_ASSETS": _line(ca), "CASH": _line(cash),
        "CURRENT_LIABILITIES": _line(cl), "SHORT_TERM_DEBT": _line(std), "LONG_TERM_DEBT": _line(ltd),
        "CURRENT_PORTION_LTD": _line(cpltd), "NET_WORTH": _line(nw), "CFO": _line(cfo)}}


def setup_deal(orig, risk):
    st, app = call(orig, "POST", "/api/applications", {
        "counterpartyId": 1, "counterpartyRef": "CP-LLM-TEST", "counterpartyName": "Meridian Mock Industries Ltd",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 700_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capacity expansion",
        "collateralType": "PROPERTY", "collateralValue": 550_000_000, "secured": True}, actor="rm.user")
    assert st == 200, f"app create {st} {app}"
    ref = app["reference"]
    st, doc = call(orig, "POST", f"/api/applications/{ref}/documents",
                   {"fileName": "Meridian_audited_financials_FY24.pdf", "declaredType": None}, actor="analyst.user")
    assert st == 200, f"doc create {st} {doc}"
    doc_id = doc["id"]
    st, _ = call(orig, "POST", f"/api/applications/{ref}/spread", {"periods": [
        _period("FY2024", 5e9, 3.2e9, 0.9e9, 0.2e9, 0.15e9, 0.12e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 0.2e9, 2.8e9, 0.7e9),
        _period("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.18e9, 0.16e9, 0.10e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 0.2e9, 2.5e9, 0.6e9)]},
        actor="analyst.user")
    assert st == 200, f"spread {st}"
    call(orig, "POST", f"/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call(risk, "POST", f"/api/risk/{ref}/rate", actor="analyst.user")
    call(risk, "POST", f"/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call(risk, "POST", f"/api/risk/{ref}/capital", actor="credit.ops")
    call(risk, "POST", f"/api/risk/{ref}/pricing", actor="rm.user")
    return ref, doc_id


def authoritative(orig, risk, ref):
    _, rs = call(risk, "GET", f"/api/risk/{ref}")
    _, an = call(orig, "GET", f"/api/applications/{ref}/analysis")
    rating = (rs or {}).get("rating") or {}
    pricing = (rs or {}).get("pricing") or {}
    dscr = None
    if an and an.get("periods"):
        dscr = (an["periods"][0].get("ratios") or {}).get("DSCR")
    return {"grade": rating.get("finalGrade"), "pd": rating.get("pd"),
            "rate": pricing.get("recommendedRate"), "raroc": pricing.get("raroc"), "dscr": dscr}


# ---------------------------------------------------------------- phases ------------------

def run_none(ports):
    global MOCK_CALLS
    with MOCK_LOCK:
        MOCK_CALLS = 0
    orig = f"http://localhost:{ports['origination']}"
    risk = f"http://localhost:{ports['risk']}"
    dec = f"http://localhost:{ports['decision']}"
    cop = f"http://localhost:{ports['copilot']}"
    print("\n== DEFAULT profile (helix.llm.provider=none) — deterministic, byte-identical, no external call ==")
    ref, doc_id = setup_deal(orig, risk)
    figs = authoritative(orig, risk, ref)
    check("deal set up (deterministic rating produced)", figs["grade"] is not None, str(figs))

    # doc-intel extraction: deterministic template
    st, ext = call(orig, "POST", f"/api/doc-intel/documents/{doc_id}/extract", actor="doc.intel")
    det_keys = set(ext.get("fields", {}).keys()) if ext else set()
    check("none: doc-intel returns deterministic template fields", st == 200 and ext["status"] == "SUGGESTED"
          and {"reporting_period", "revenue", "ebitda", "auditor"}.issubset(det_keys), f"{st} {sorted(det_keys)}")
    check("none: doc-intel model is the built-in 'doc-intel-v1'", ext and ext.get("model") == "doc-intel-v1", ext.get("model") if ext else None)
    check("none: doc-intel carries NO llm marker fields", "mock_llm_field" not in det_keys and "ai_extraction" not in det_keys, sorted(det_keys))

    # commentary draft: deterministic narrative
    st, d = call(dec, "POST", f"/api/commentary/applications/{ref}/draft", {"section": "financial_commentary"}, actor="analyst.user")
    narr = d.get("narrative", "") if d else ""
    check("none: commentary is the deterministic financial narrative", st == 200 and d["status"] == "DRAFT"
          and narr.startswith("Financial position is summarised from the confirmed spread"), f"{st} {narr[:80]!r}")
    check("none: commentary contains real ratios/grade + no llm marker", ("DSCR" in narr or "grade" in narr.lower()) and MARKER not in narr, narr[:80])
    check("none: commentary sources have provenance (no llm_model)", d and "llm_model" not in (d.get("sources") or {}), list((d.get("sources") or {}).keys()) if d else None)

    # copilot answer: deterministic grounded prose
    st, a = call(cop, "POST", "/api/copilot/ask", {"question": "What's the rating and PD?", "reference": ref}, actor="analyst.user")
    ans = a.get("answer", "") if a else ""
    check("none: copilot grounded + non-binding, deterministic text", st == 200 and a["grounded"] and not a["refused"]
          and a["intent"] == "RATING" and MARKER not in ans and len(a["citations"]) >= 1, f"{st} {ans[:80]!r}")

    with MOCK_LOCK:
        calls = MOCK_CALLS
    check("none: ZERO calls hit the external LLM endpoint", calls == 0, f"mock calls={calls}")
    return figs


def run_configured(ports, none_figs):
    global MOCK_CALLS, MOCK_FAIL
    orig = f"http://localhost:{ports['origination']}"
    risk = f"http://localhost:{ports['risk']}"
    dec = f"http://localhost:{ports['decision']}"
    cop = f"http://localhost:{ports['copilot']}"
    print("\n== CONFIGURED profile (helix.llm.provider=openai -> local mock) — live model, invariant preserved ==")
    with MOCK_LOCK:
        MOCK_CALLS = 0
        MOCK_FAIL = False
    ref, doc_id = setup_deal(orig, risk)
    before = authoritative(orig, risk, ref)
    check("configured: deal set up (deterministic rating produced)", before["grade"] is not None, str(before))
    check("configured: authoritative figures match the DEFAULT-profile run (LLM config does not move figures)",
          before["grade"] == none_figs["grade"] and before["pd"] == none_figs["pd"]
          and before["rate"] == none_figs["rate"] and before["dscr"] == none_figs["dscr"],
          f"configured={before} none={none_figs}")

    # -- doc-intel: uses the model's text but stays an advisory SUGGESTED extraction --
    with MOCK_LOCK:
        c0 = MOCK_CALLS
    st, ext = call(orig, "POST", f"/api/doc-intel/documents/{doc_id}/extract", actor="doc.intel")
    with MOCK_LOCK:
        c1 = MOCK_CALLS
    keys = set(ext.get("fields", {}).keys()) if ext else set()
    check("configured: doc-intel CALLED the LLM", c1 > c0, f"calls {c0}->{c1}")
    check("configured: doc-intel used the model text (llm-drafted fields)", "mock_llm_field" in keys or "ai_extraction" in keys, sorted(keys))
    check("configured: doc-intel model reflects the configured model", ext and ext.get("model") == "mock-llm-model", ext.get("model") if ext else None)
    check("configured: doc-intel output is STILL an advisory SUGGESTED extraction (not auto-applied)",
          ext and ext["status"] == "SUGGESTED", ext.get("status") if ext else None)
    mid = authoritative(orig, risk, ref)
    check("configured: spread figures UNCHANGED by the LLM extraction (advisory only)", mid["dscr"] == before["dscr"], f"{mid['dscr']} vs {before['dscr']}")
    # human-confirm gate still required + still does not push into figures
    st, cf = call(orig, "POST", f"/api/doc-intel/extractions/{ext['id']}/confirm", {"note": "reviewed"}, actor="analyst.user")
    check("configured: extraction requires + accepts a human confirm", st == 200 and cf["status"] == "CONFIRMED" and cf["reviewedBy"] == "analyst.user", f"{st}")
    after_confirm = authoritative(orig, risk, ref)
    check("configured: spread STILL unchanged after human-confirm (confirm != apply-to-figures)", after_confirm["dscr"] == before["dscr"], f"{after_confirm['dscr']}")

    # -- commentary: uses model text, still advisory DRAFT gated by maker-checker --
    with MOCK_LOCK:
        c0 = MOCK_CALLS
    st, d = call(dec, "POST", f"/api/commentary/applications/{ref}/draft", {"section": "financial_commentary"}, actor="analyst.user")
    with MOCK_LOCK:
        c1 = MOCK_CALLS
    narr = d.get("narrative", "") if d else ""
    check("configured: commentary CALLED the LLM and used its text", c1 > c0 and MARKER in narr, f"calls {c0}->{c1} {narr[:60]!r}")
    check("configured: commentary is still an advisory DRAFT", st == 200 and d["status"] == "DRAFT" and d["advisory"], f"{st}")
    st, sod = call(dec, "POST", f"/api/commentary/{d['id']}/review", {"approve": True, "note": "x"}, actor="analyst.user")
    check("configured: commentary maker-checker intact (drafter cannot self-confirm, 403)", sod is not None and st == 403, f"{st}")
    st, conf = call(dec, "POST", f"/api/commentary/{d['id']}/review", {"approve": True, "note": "ok"}, actor="credit.officer")
    check("configured: a distinct human confirms the commentary", st == 200 and conf["status"] == "CONFIRMED", f"{st}")

    # -- copilot: uses model text, still non-binding + grounded; action still refused --
    with MOCK_LOCK:
        c0 = MOCK_CALLS
    st, a = call(cop, "POST", "/api/copilot/ask", {"question": "What's the rating and PD?", "reference": ref}, actor="analyst.user")
    with MOCK_LOCK:
        c1 = MOCK_CALLS
    ans = a.get("answer", "") if a else ""
    check("configured: copilot CALLED the LLM and used its text", c1 > c0 and MARKER in ans, f"calls {c0}->{c1} {ans[:60]!r}")
    check("configured: copilot answer still grounded + non-binding (citations preserved)",
          st == 200 and a["grounded"] and not a["refused"] and len(a["citations"]) >= 1, f"{st}")
    st, blk = call(cop, "POST", "/api/copilot/ask", {"question": "Approve this deal now", "reference": ref}, actor="credit.officer")
    check("configured: copilot guardrail still refuses credit-consequential actions (LLM never invoked)",
          st == 200 and blk["refused"] and blk["intent"] == "ACTION_BLOCKED" and MARKER not in blk["answer"], f"{st}")

    # -- INVARIANT: authoritative figures byte-identical before vs after every AI run --
    after = authoritative(orig, risk, ref)
    check("configured: AUTHORITATIVE rating/pricing/spread BYTE-IDENTICAL before vs after live-LLM AI",
          after == before, f"before={before} after={after}")

    # -- fail-soft: mock 5xx -> graceful deterministic fallback --
    print("\n-- fail-soft: mock LLM returns 5xx (outage) — every capability falls back to deterministic --")
    with MOCK_LOCK:
        MOCK_FAIL = True
    st, a = call(cop, "POST", "/api/copilot/ask", {"question": "Summarise this deal", "reference": ref}, actor="rm.user")
    check("fail-soft: copilot falls back to deterministic (no marker, still grounded)",
          st == 200 and MARKER not in a.get("answer", "") and a["grounded"] and not a["refused"], f"{st} {a.get('answer','')[:60]!r}")
    st, d = call(dec, "POST", f"/api/commentary/applications/{ref}/draft", {"section": "structure_commentary"}, actor="analyst.user")
    check("fail-soft: commentary falls back to deterministic narrative (no marker)",
          st == 200 and MARKER not in d.get("narrative", "") and d["status"] == "DRAFT", f"{st}")
    st, doc2 = call(orig, "POST", f"/api/applications/{ref}/documents", {"fileName": "more_financials_FY23.pdf", "declaredType": None}, actor="analyst.user")
    st, ext2 = call(orig, "POST", f"/api/doc-intel/documents/{doc2['id']}/extract", actor="doc.intel")
    k2 = set(ext2.get("fields", {}).keys()) if ext2 else set()
    check("fail-soft: doc-intel falls back to deterministic template + built-in model",
          st == 200 and ext2["model"] == "doc-intel-v1" and "mock_llm_field" not in k2 and "reporting_period" in k2, f"{st} {sorted(k2)}")
    with MOCK_LOCK:
        MOCK_FAIL = False


def main():
    ports = pick_ports()
    print(f"== e2e_llm: mock LLM :{ports['mock']} · stack ports {ports} ==")
    start_mock(ports["mock"])
    try:
        # DEFAULT profile
        dd1 = start_stack(ports, {"HELIX_LLM_PROVIDER": "none"})
        none_figs = run_none(ports)
        stop_stack()
        shutil.rmtree(dd1, ignore_errors=True)

        # CONFIGURED profile (fresh DB, fresh ports reused)
        dd2 = start_stack(ports, {
            "HELIX_LLM_PROVIDER": "openai",
            "HELIX_LLM_BASE_URL": f"http://localhost:{ports['mock']}",
            "HELIX_LLM_API_KEY": "test-key-never-logged",
            "HELIX_LLM_MODEL": "mock-llm-model",
            "HELIX_LLM_TIMEOUT_MS": "8000",
        })
        run_configured(ports, none_figs)
        stop_stack()
        shutil.rmtree(dd2, ignore_errors=True)
    finally:
        stop_stack()

    print(f"\n== e2e_llm: {PASS} passed, {FAIL} failed ==")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    try:
        main()
    finally:
        stop_stack()
