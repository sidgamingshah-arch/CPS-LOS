#!/usr/bin/env python3
"""
Governed-LLM proof for the centralised helix.llm.* configuration + LlmClient SPI.

This is self-contained: it spins up a LOCAL mock LLM server and its own small stack of
services (config, counterparty, origination, risk, decision, portfolio, copilot) on ALTERNATE
ports (never 8080-8089), in two modes plus an outage phase:

  * DEFAULT  (helix.llm.provider=none): every wired capability takes the EXISTING deterministic
    path — byte-identical to today — and NOTHING calls the external endpoint (the mock records
    zero calls).

  * CONFIGURED (helix.llm.provider=openai, base-url -> the mock): the SAME capabilities CALL the
    model and use its text, while the AI/advisory governance invariant STILL holds — the
    authoritative rating / pricing / spread figures are byte-identical before/after AI, every
    advisory output stays advisory + human-gated, and the deterministic figures each capability
    promises to leave alone are byte-identical to the DEFAULT-profile baseline.

Three "boundary" capabilities are asserted directly (doc-intel extract, commentary, copilot) and
then TWENTY more advisory capabilities across all five capability-hosting services:

  counterparty : screening-rationale · group-identification · ubo-narrative
  origination  : doc-classify · spreading-extract · language-normalise · translation ·
                 doc-checks · collateral-extract · collateral-monitor
  risk         : rag-narrative · macro-narrative · pricing-narrative
  portfolio    : ews-narrative
  decision     : cpt-draft · proposal-draft · group-insights · covenant-extract ·
                 covenant-certificate · docgen-clause

For each the suite proves (a) the model was actually called in CONFIGURED mode (and never in
DEFAULT mode), (b) the model's text landed in the advisory field (or, for label/json capabilities,
the audit is stamped llmDrafted + llmModel), (c) the deterministic outputs the capability promises
to leave alone are byte-identical to the DEFAULT baseline, and (d) the audit trail
(/api/audit/subject) carries llmDrafted=true + llmModel in CONFIGURED mode and NEITHER in DEFAULT.
A mock 5xx (outage) phase confirms every representative capability falls back to deterministic
output with no marker and unchanged figures.

No gateway, no full regression: this drives the service ports directly and manages only the
PIDs it starts. Run:  python3 scripts/e2e_llm.py
"""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
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


def mock_calls():
    with MOCK_LOCK:
        return MOCK_CALLS


def has_marker(s):
    return isinstance(s, str) and MARKER in s


def rnd(x):
    return round(x, 6) if isinstance(x, (int, float)) and not isinstance(x, bool) else x


# ---------------------------------------------------------------- mock LLM ----------------

CAP_RE = re.compile(r"capability=([a-z0-9-]+)")


def _capability_key(system):
    """The wired services embed a 'capability=<key>' marker in their system prompt."""
    m = CAP_RE.search(system or "")
    return m.group(1) if m else None


def _mock_content(cap, user):
    """Domain-accurate reply shape PER capability, so the real service parsers accept it.

    prose  -> f"{MARKER}[<key>] <short echo>"  (proves both marker + key reach the drafted field)
    label  -> a single VALID DocumentType enum token (strictly validated by the service)
    json   -> the exact JSON shape that capability's parser expects, with a MARKER field/value
              somewhere the test can observe downstream.
    """
    snippet = (user or "").strip().replace("\n", " ")
    if cap == "doc-classify":
        # Strictly validated against the DocumentType enum; return a real, distinct label.
        return "FACILITY_DOC"
    if cap == "spreading-extract":
        # Flat lineKey -> value; only ever counted (aiCandidateLines) on the SPREAD_GENERATED audit.
        return json.dumps({"REVENUE": 5_000_000_000, "COGS": 3_200_000_000,
                           "mock_llm_candidate": MARKER + " spreading-extract candidate line"})
    if cap == "collateral-extract":
        # Flat field map; the service wraps each value as {value, confidence}. Includes a marketValue
        # so the human confirm() gate can materialise a collateral, and a MARKER-bearing value.
        return json.dumps({"marketValue": 550_000_000, "valuationDate": "2025-03-31",
                           "valuerName": MARKER + " Valuers LLP", "addressLine": "1 Test Road, Mumbai"})
    if cap == "covenant-extract":
        # metric/operator MUST validate against the fixed taxonomy + operator set.
        return json.dumps({"covenants": [{"metric": "DSCR", "operator": ">=", "threshold": 1.4,
                                          "testFrequency": "QUARTERLY",
                                          "sourceText": MARKER + " DSCR shall be at least 1.40x, tested quarterly"}]})
    if cap == "covenant-certificate":
        # canonical metric (joins to the deterministic parse) + strictly-validated status.
        return json.dumps({"lines": [{"metric": "DSCR",
                                      "reportedLabel": MARKER + " DSCR (borrower reported)",
                                      "reportedValue": 1.85, "reportedStatus": "COMPLIED"}]})
    # prose capabilities
    return "%s[%s] %s" % (MARKER, cap, snippet[:120])


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        global MOCK_CALLS
        with MOCK_LOCK:
            MOCK_CALLS += 1
            fail = MOCK_FAIL
        raw = self._read_body() or b"{}"
        try:
            body = json.loads(raw.decode())
        except Exception:
            body = {}

        if fail:
            self._send(500, {"error": {"message": "mock LLM injected failure"}})
            return

        system, user = self._prompts(body)
        cap = _capability_key(system)
        if cap:
            # Route by capability key first (all 20 wired capabilities carry a marker).
            content = _mock_content(cap, user)
        elif "JSON object" in system:
            # doc-extract capability (pre-existing, no capability marker): return a JSON object
            # of fields (advisory suggestion) — preserve today's behaviour exactly.
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

    def _read_body(self):
        # Spring's SimpleClientHttpRequestFactory streams the request body with
        # Transfer-Encoding: chunked (no Content-Length), so a naive Content-Length
        # read returns nothing — decode chunked bodies too, else the capability marker
        # in the system prompt is never seen and every reply falls back to prose.
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            buf = bytearray()
            while True:
                line = self.rfile.readline()
                if not line:
                    break
                try:
                    size = int(line.strip().split(b";", 1)[0], 16)
                except ValueError:
                    break
                if size == 0:
                    self.rfile.readline()      # consume the trailing CRLF after the last chunk
                    break
                buf.extend(self.rfile.read(size))
                self.rfile.readline()          # consume the CRLF after each chunk
            return bytes(buf)
        length = int(self.headers.get("Content-Length", 0) or 0)
        return self.rfile.read(length) if length else b""

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
    # config, counterparty, origination, risk, decision, portfolio, copilot, mock — one free block
    for base in range(18100, 18900, 20):
        ports = {"config": base + 1, "counterparty": base + 2, "origination": base + 3,
                 "risk": base + 4, "decision": base + 5, "portfolio": base + 6,
                 "copilot": base + 7, "mock": base + 9}
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
    """Start config + the six capability-hosting services with the given LLM env."""
    data_dir = tempfile.mkdtemp(prefix="helix-llm-data-")
    curl = lambda p: f"http://localhost:{ports[p]}"
    inter = {
        "CONFIG_SERVICE_URL": curl("config"),
        "COUNTERPARTY_SERVICE_URL": curl("counterparty"),
        "ORIGINATION_SERVICE_URL": curl("origination"),
        "RISK_SERVICE_URL": curl("risk"),
        "DECISION_SERVICE_URL": curl("decision"),
    }
    start_service("config-service", ports["config"], data_dir, {})
    if not wait_health(ports["config"]):
        raise RuntimeError("config-service did not come up")
    # Every capability-hosting service gets the LLM env AND the inter-service URLs it needs.
    # counterparty ignores the URLs it doesn't consume (config + workflow only); harmless.
    start_service("counterparty-service", ports["counterparty"], data_dir, {**inter, **llm_env})
    start_service("origination-service", ports["origination"], data_dir, {**inter, **llm_env})
    start_service("risk-service", ports["risk"], data_dir, {**inter, **llm_env})
    start_service("decision-service", ports["decision"], data_dir, {**inter, **llm_env})
    start_service("portfolio-service", ports["portfolio"], data_dir, {**inter, **llm_env})
    start_service("copilot-service", ports["copilot"], data_dir, {**inter, **llm_env})
    for name in ("counterparty", "origination", "risk", "decision", "portfolio", "copilot"):
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


def _q(s):
    return urllib.parse.quote(str(s), safe="")


def audit_events(base, subject_type, subject_id):
    _, arr = call(base, "GET", f"/api/audit/subject?type={_q(subject_type)}&id={_q(subject_id)}")
    return arr if isinstance(arr, list) else []


def audit_detail(base, subject_type, subject_id, event_type):
    """Most-recent (detail, summary) for the given event type on a subject (or (None, None))."""
    for e in audit_events(base, subject_type, subject_id):
        if e.get("eventType") == event_type:
            return (e.get("detail") or {}), (e.get("summary") or "")
    return None, None


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


def sample_periods():
    return [
        _period("FY2024", 5e9, 3.2e9, 0.9e9, 0.2e9, 0.15e9, 0.12e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 0.2e9, 2.8e9, 0.7e9),
        _period("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.18e9, 0.16e9, 0.10e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 0.2e9, 2.5e9, 0.6e9)]


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
    st, _ = call(orig, "POST", f"/api/applications/{ref}/spread", {"periods": sample_periods()}, actor="analyst.user")
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


# ---------------------------------------------------------------- setup helpers -----------

def make_counterparty(cpt, suffix, name, reg, pep=False, adverse=False, highrisk=False,
                      sector="STEEL", country="IN"):
    body = {"legalName": name, "registrationNo": reg, "jurisdiction": "IN-RBI",
            "segment": "MID_CORPORATE", "sector": sector, "country": country,
            "presentationCurrency": "INR", "listedEntity": False, "regulatedFi": False,
            "pep": pep, "adverseMedia": adverse, "highRiskJurisdiction": highrisk,
            "complexOwnership": False}
    st, r = call(cpt, "POST", "/api/counterparties", body, actor="rm.user")
    assert st == 200, f"create counterparty {suffix} {st} {r}"
    return r["id"], r["reference"]


def create_light_app(orig, cpref="CP-LIGHT"):
    st, app = call(orig, "POST", "/api/applications", {
        "counterpartyId": 2, "counterpartyRef": cpref, "counterpartyName": "Lumen Light Co Ltd",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 200_000_000, "currency": "INR", "tenorMonths": 36, "purpose": "Working capital",
        "collateralType": "PROPERTY", "collateralValue": 150_000_000, "secured": True}, actor="rm.user")
    assert st == 200, f"light app {st} {app}"
    return app["reference"]


def make_group_with_members(cpt):
    m1, r1 = make_counterparty(cpt, "GM1", "Orion Group Member One Ltd", "U65990MH2017PLC111001", sector="INFRA")
    m2, r2 = make_counterparty(cpt, "GM2", "Orion Group Member Two Ltd", "U65990MH2017PLC111002", sector="INFRA")
    st, g = call(cpt, "POST", "/api/initiation/groups",
                 {"name": "Orion Holdings Group", "groupRmId": "rm.orion", "country": "IN", "multiCountry": False},
                 actor="rm.user")
    assert st == 200, f"create group {st} {g}"
    gid, gref = g["id"], g["reference"]
    call(cpt, "POST", f"/api/initiation/counterparties/{m1}/group/{gid}", actor="rm.user")
    call(cpt, "POST", f"/api/initiation/counterparties/{m2}/group/{gid}", actor="rm.user")
    return gref


# ---------------------------------------------------------------- advisory-capability driver

def drive_advisory_caps(ports, mode, ref, doc_id, baseline):
    """Exercise all 20 wired advisory capabilities in the given mode.

    mode == "none"       : assert the deterministic path (no marker, no llm audit keys) and
                           capture each capability's deterministic invariants into `baseline`.
    mode == "configured" : assert the model was called + its text landed in the advisory field,
                           the deterministic invariants match the DEFAULT-profile baseline, and
                           the audit trail is stamped llmDrafted + llmModel.

    Returns a ctx dict of handles the outage phase reuses.
    """
    cpt = f"http://localhost:{ports['counterparty']}"
    orig = f"http://localhost:{ports['origination']}"
    risk = f"http://localhost:{ports['risk']}"
    dec = f"http://localhost:{ports['decision']}"
    pf = f"http://localhost:{ports['portfolio']}"
    conf = mode == "configured"
    tag = "configured" if conf else "none"
    print(f"\n== {tag} profile: 20 wired advisory capabilities across counterparty/origination/risk/portfolio/decision ==")

    figs_start = authoritative(orig, risk, ref) if conf else None

    def called(name, c0, c1):
        if conf:
            check(f"configured: {name} CALLED the external LLM", c1 > c0, f"calls {c0}->{c1}")
        else:
            check(f"none: {name} made NO external call", c1 == c0, f"calls {c0}->{c1}")

    def prose(name, text):
        if conf:
            check(f"configured: {name} drafted field carries the MARKER", has_marker(text), repr(text)[:100])
        else:
            check(f"none: {name} has no LLM marker (deterministic/absent)",
                  (text is None) or (isinstance(text, str) and MARKER not in text), repr(text)[:100])

    def inv(name, key, value):
        if conf:
            check(f"configured: {name} byte-identical to the DEFAULT-profile baseline",
                  baseline.get(key) == value, f"none={baseline.get(key)} configured={value}")
        else:
            baseline[key] = value

    def aud(name, base, stype, sid, event):
        detail, summary = audit_detail(base, stype, sid, event)
        if conf:
            check(f"configured: {name} audit stamped llmDrafted+llmModel",
                  detail is not None and detail.get("llmDrafted") is True and detail.get("llmModel") == "mock-llm-model",
                  f"detail={detail}")
        else:
            check(f"none: {name} audit carries NO llm keys",
                  detail is not None and "llmDrafted" not in detail and "llmModel" not in detail,
                  f"detail={detail}")
        return detail, summary

    # ---- counterparty: screening-rationale (prose; one LLM call per hit) ----
    scr_id, scr_ref = make_counterparty(cpt, "SCR", "Zenith Screening Test Ltd", "U51909MH2019PLC777001",
                                        pep=True, adverse=True, highrisk=True)
    c0 = mock_calls()
    st, hits = call(cpt, "POST", f"/api/counterparties/{scr_id}/screening/run", actor="compliance.officer")
    c1 = mock_calls()
    check(f"{tag}: screening produced deterministic hits", st == 200 and isinstance(hits, list) and len(hits) >= 4,
          f"{st} {len(hits) if isinstance(hits, list) else hits}")
    called("screening-rationale", c0, c1)
    prose("screening-rationale", (hits[0].get("aiRationale") if hits else None))
    inv("screening match score/severity/disposition", "screening",
        sorted([(h.get("listSource"), rnd(h.get("matchScore")), h.get("severity"), h.get("disposition")) for h in (hits or [])]))
    aud("screening-rationale", cpt, "Counterparty", scr_ref, "SCREENING_RUN")

    # ---- counterparty: group-identification (prose appended to each candidate's signals) ----
    fam = [make_counterparty(cpt, f"FAM{i}", nm, "U27100MH2020PLC900000", sector="STEEL")
           for i, nm in enumerate(["Helix Alpha Trading Ltd", "Helix Alpha Steel Ltd", "Helix Alpha Holdings Ltd"])]
    fam_ids = [i for i, _ in fam]
    fam_refs = [r for _, r in fam]
    c0 = mock_calls()
    st, gs = call(cpt, "POST", f"/api/initiation/counterparties/{fam_ids[0]}/group/suggest", actor="rm.user")
    c1 = mock_calls()
    sibs = (gs or {}).get("ungroupedSiblings") or []
    check(f"{tag}: group-identification found >=2 similar siblings", st == 200 and len(sibs) >= 2,
          f"{st} siblings={len(sibs)}")
    called("group-identification", c0, c1)
    sig_text = " ".join(s for sib in sibs for s in (sib.get("signals") or []))
    prose("group-identification", sig_text if sig_text else None)
    inv("group topScore/recommendation/sibling scores", "group",
        ((gs or {}).get("recommendation"), rnd((gs or {}).get("topScore")),
         sorted(rnd(s.get("score")) for s in sibs)))
    aud("group-identification", cpt, "Counterparty", fam_refs[0], "GROUP_SUGGESTED")

    # ---- counterparty: ubo-narrative (prose replaces the UBO_RESOLVED audit summary) ----
    ubo_id, ubo_ref = make_counterparty(cpt, "UBO", "Pinnacle UBO Test Ltd", "U74999MH2018PLC888001")
    ubo_body = {"nodes": [{"key": "ROOT", "name": "Pinnacle UBO Test Ltd", "type": "ROOT"},
                          {"key": "P1", "name": "Alpha Person", "type": "PERSON"},
                          {"key": "P2", "name": "Beta Person", "type": "PERSON"}],
                "edges": [{"parent": "P1", "child": "ROOT", "ownershipPct": 0.6},
                          {"parent": "P2", "child": "ROOT", "ownershipPct": 0.4}]}
    c0 = mock_calls()
    st, nodes = call(cpt, "POST", f"/api/counterparties/{ubo_id}/ubo", ubo_body, actor="compliance.officer")
    c1 = mock_calls()
    check(f"{tag}: ubo resolved the ownership graph", st == 200 and isinstance(nodes, list) and len(nodes) == 3, f"{st}")
    called("ubo-narrative", c0, c1)
    _, ubo_summary = aud("ubo-narrative", cpt, "Counterparty", ubo_ref, "UBO_RESOLVED")
    prose("ubo-narrative", ubo_summary)
    inv("ubo effective-ownership + UBO flags", "ubo",
        sorted([(n.get("nodeKey"), rnd(n.get("effectiveOwnership")), n.get("ubo")) for n in (nodes or [])]))

    # ---- origination: doc-classify (label) on an isolated light application ----
    light = create_light_app(orig)
    c0 = mock_calls()
    st, dclass = call(orig, "POST", f"/api/applications/{light}/documents",
                      {"fileName": "zzz_unclassified_9.dat", "declaredType": None}, actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: low-confidence document uploaded", st == 200, f"{st} {dclass}")
    called("doc-classify", c0, c1)
    if conf:
        check("configured: doc-classify applied a VALID enum label from the LLM (FACILITY_DOC)",
              dclass.get("classifiedType") == "FACILITY_DOC", dclass.get("classifiedType"))
    else:
        check("none: doc-classify used the deterministic keyword label (OTHER)",
              dclass.get("classifiedType") == "OTHER", dclass.get("classifiedType"))
    inv("doc-classify confidence + needsReview", "docclassify",
        (rnd(dclass.get("classificationConfidence")), dclass.get("needsReview")))
    aud("doc-classify", orig, "Application", light, "DOCUMENT_CLASSIFIED")

    # ---- origination: spreading-extract (json; advisory candidate lines on the audit only) ----
    c0 = mock_calls()
    st, _ = call(orig, "POST", f"/api/applications/{light}/spread", {"periods": sample_periods()}, actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: light-app spread generated", st == 200, f"{st}")
    called("spreading-extract", c0, c1)
    sdet, _ = aud("spreading-extract", orig, "Application", light, "SPREAD_GENERATED")
    if conf:
        check("configured: spreading-extract recorded advisory aiCandidateLines on the audit",
              sdet is not None and sdet.get("aiCandidateLines", 0) >= 1, f"detail={sdet}")
    else:
        check("none: spreading-extract recorded NO aiCandidateLines", sdet is not None and "aiCandidateLines" not in sdet,
              f"detail={sdet}")

    # ---- origination: language-normalise (prose) ----
    c0 = mock_calls()
    st, nz = call(orig, "POST", "/api/doc-intel/normalise-language",
                  {"text": "we will pay the amount of INR 5,000,000 on the due date", "target": "LEGAL"},
                  actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: normalise-language ok", st == 200, f"{st} {nz}")
    called("language-normalise", c0, c1)
    prose("language-normalise", (nz or {}).get("rewritten"))
    inv("normalise target register", "normalise", (nz or {}).get("target"))
    aud("language-normalise", orig, "Text", "n/a", "TEXT_NORMALISED")

    # ---- origination: translation (prose; only when src != tgt) ----
    c0 = mock_calls()
    st, tr = call(orig, "POST", "/api/doc-intel/translate",
                  {"text": "The borrower shall repay the facility on maturity.", "targetLanguage": "fr"},
                  actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: translate ok", st == 200, f"{st} {tr}")
    called("translation", c0, c1)
    prose("translation", (tr or {}).get("translated"))
    inv("translation source/target/confidence", "translate",
        ((tr or {}).get("sourceLanguage"), (tr or {}).get("targetLanguage"), rnd((tr or {}).get("confidence"))))
    aud("translation", orig, "Text", "n/a", "TEXT_TRANSLATED")

    # ---- origination: doc-checks (prose; redraft finding messages, verdict/codes fixed) ----
    # Dedicated declared-type doc so its classifiedType is deterministic (LLM classify skipped),
    # keeping the finding detection identical in both modes.
    st, docb = call(orig, "POST", f"/api/applications/{light}/documents",
                    {"fileName": "borrower_financials.pdf", "declaredType": "FINANCIAL_STATEMENT"}, actor="analyst.user")
    check(f"{tag}: declared-type doc uploaded for checks", st == 200, f"{st} {docb}")
    docb_id = docb["id"]
    c0 = mock_calls()
    st, chk = call(orig, "GET", f"/api/doc-intel/documents/{docb_id}/checks", actor="doc.intel")
    c1 = mock_calls()
    check(f"{tag}: doc-checks ok", st == 200, f"{st} {chk}")
    called("doc-checks", c0, c1)
    findings = (chk or {}).get("findings") or []
    prose("doc-checks", " ".join(f.get("message", "") for f in findings) if findings else None)
    inv("doc-checks passed verdict + finding level/code", "docchecks",
        ((chk or {}).get("passed"), sorted([(f.get("level"), f.get("code")) for f in findings])))
    aud("doc-checks", orig, "Application", light, "DOC_CHECKED")

    # ---- origination: collateral-extract (json; wrapped fields, stays SUGGESTED) ----
    val_text = ("Valuation report: market value INR 550,000,000 as at 31-03-2025; valuer M/s Acme Valuers; "
                "property situated at 1 Test Road, Mumbai.")
    c0 = mock_calls()
    st, cex = call(orig, "POST", f"/api/collateral-intel/{ref}/extract",
                   {"documentKind": "VALUATION_REPORT", "text": val_text}, actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: collateral-extract ok", st == 200, f"{st} {cex}")
    called("collateral-extract", c0, c1)
    cfields = (cex or {}).get("fields") or {}
    if conf:
        check("configured: collateral-extract fields carry the LLM MARKER", MARKER in json.dumps(cfields), json.dumps(cfields)[:120])
    else:
        check("none: collateral-extract deterministic regex fields (no marker)", MARKER not in json.dumps(cfields), "marker leaked")
    check(f"{tag}: collateral-extract stays a SUGGESTED extraction (human confirm gate intact)",
          (cex or {}).get("status") == "SUGGESTED", (cex or {}).get("status"))
    aud("collateral-extract", orig, "Application", ref, "COLLATERAL_EXTRACTED")

    # ---- origination: collateral-monitor (prose narrative on the revaluation audit) ----
    st, coll = call(orig, "POST", f"/api/applications/{ref}/collaterals",
                    {"collateralType": "PROPERTY", "description": "Test warehouse", "marketValue": 400_000_000,
                     "valuationDate": "2025-03-31", "valuationSource": "Acme", "haircut": 0.2, "owner": "Meridian",
                     "location": "Mumbai", "perfectionStatus": "IN_PROGRESS", "facilityId": None}, actor="analyst.user")
    check(f"{tag}: collateral row added for revaluation", st == 200 and coll and coll.get("id"), f"{st} {coll}")
    coll_id = coll.get("id")
    c0 = mock_calls()
    st, reval = call(orig, "POST", f"/api/collateral-intel/collaterals/{coll_id}/revalue",
                     {"newMarketValue": 300_000_000, "drawnExposure": 280_000_000, "trigger": "VALUATION_UPDATE"},
                     actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: collateral revalue ok", st == 200, f"{st} {reval}")
    called("collateral-monitor", c0, c1)
    _, reval_summary = aud("collateral-monitor", orig, "Application", ref, "COLLATERAL_REVALUED")
    prose("collateral-monitor", reval_summary)
    inv("collateral LTV math + alert severity", "collmon",
        (rnd((reval or {}).get("ltvAfter")), (reval or {}).get("alertSeverity"), (reval or {}).get("ltvBreached")))

    # ---- risk: rag-narrative (prose persisted in RagAssessment.factors) ----
    c0 = mock_calls()
    st, rag = call(risk, "POST", f"/api/risk/{ref}/rag", None, actor="risk.analyst")
    c1 = mock_calls()
    check(f"{tag}: rag assessment ok", st == 200, f"{st} {rag}")
    called("rag-narrative", c0, c1)
    factors = (rag or {}).get("factors") or {}
    prose("rag-narrative", factors.get("narrative"))
    inv("rag band + 0-100 score", "rag", ((rag or {}).get("band"), rnd((rag or {}).get("score"))))
    aud("rag-narrative", risk, "Application", ref, "RAG_ASSESSED")

    # ---- risk: macro-narrative (prose replaces MacroImpactAssessment.rationale) ----
    macro_body = {"scenarioName": "Adverse-2026", "interestRateBps": 150, "gdpGrowthDeltaPct": -1.5,
                  "fxDepreciationPct": 8, "sectorOutlook": "NEGATIVE", "commodityShockPct": 12}
    c0 = mock_calls()
    st, macro = call(risk, "POST", f"/api/risk/{ref}/macro-impact", macro_body, actor="risk.analyst")
    c1 = mock_calls()
    check(f"{tag}: macro-impact ok", st == 200, f"{st} {macro}")
    called("macro-narrative", c0, c1)
    prose("macro-narrative", (macro or {}).get("rationale"))
    inv("macro stressedPd/pdDeltaBps/direction/notch", "macro",
        (rnd((macro or {}).get("stressedPd")), rnd((macro or {}).get("pdDeltaBps")),
         (macro or {}).get("direction"), rnd((macro or {}).get("notchEstimate"))))
    aud("macro-narrative", risk, "Application", ref, "MACRO_IMPACT_ASSESSED")

    # ---- risk: pricing-narrative (prose on recommended.breakdown; goal-seek numbers fixed) ----
    c0 = mock_calls()
    st, opt = call(risk, "POST", f"/api/risk/{ref}/pricing/optimise", {"targetRaroc": 0.18}, actor="pricing.analyst")
    c1 = mock_calls()
    check(f"{tag}: pricing optimise ok", st == 200, f"{st} {opt}")
    called("pricing-narrative", c0, c1)
    recommended = (opt or {}).get("recommended") or {}
    breakdown = recommended.get("breakdown") or {}
    prose("pricing-narrative", breakdown.get("recommendationNarrative"))
    inv("pricing baseline + recommended goal-seek numbers", "pricing",
        (rnd((opt or {}).get("baselineRate")), rnd((opt or {}).get("baselineRaroc")), (opt or {}).get("achievable"),
         recommended.get("name"), rnd(recommended.get("rate")), rnd(recommended.get("raroc")),
         rnd(recommended.get("feeBps")), rnd(recommended.get("lgdAfterCollateral"))))
    aud("pricing-narrative", risk, "Application", ref, "PRICING_OPTIMISED")

    # ---- portfolio: ews-narrative (prose replaces each EwsSignal.rationale) ----
    st, exp = call(pf, "POST", f"/api/portfolio/exposures/{ref}/register", {"daysPastDue": 95}, actor="credit.ops")
    check(f"{tag}: exposure booked (daysPastDue=95)", st == 200, f"{st} {exp}")
    c0 = mock_calls()
    st, sigs = call(pf, "POST", f"/api/portfolio/exposures/{ref}/ews/scan", None, actor="portfolio.manager")
    c1 = mock_calls()
    check(f"{tag}: ews scan raised signal(s)", st == 200 and isinstance(sigs, list) and len(sigs) >= 1, f"{st}")
    called("ews-narrative", c0, c1)
    dpd_sig = next((s for s in (sigs or []) if s.get("signalType") == "DAYS_PAST_DUE"), (sigs or [None])[0])
    prose("ews-narrative", (dpd_sig or {}).get("rationale"))
    inv("ews signalType/severity/score/proposedAction", "ews",
        sorted([(s.get("signalType"), s.get("severity"), rnd(s.get("score")), s.get("proposedAction")) for s in (sigs or [])]))
    aud("ews-narrative", pf, "Application", ref, "EWS_SCAN")

    # ---- decision: cpt-draft (prose woven into the CPT markdown) ----
    cpt_id, cpt_ref = make_counterparty(cpt, "CPT", "Vertex Planning Client Ltd", "U72200MH2016PLC222001", sector="IT")
    c0 = mock_calls()
    st, tmpl = call(dec, "POST", f"/api/cpt/{cpt_ref}/generate", None, actor="rm.user")
    c1 = mock_calls()
    check(f"{tag}: cpt generate ok", st == 200, f"{st} {tmpl}")
    called("cpt-draft", c0, c1)
    _, cpt_latest = call(dec, "GET", f"/api/cpt/{cpt_ref}")
    cpt_md = (cpt_latest or {}).get("markdown")
    prose("cpt-draft", cpt_md)
    check(f"{tag}: cpt markdown stays grounded in the client name", "Vertex Planning Client Ltd" in (cpt_md or ""),
          (cpt_md or "")[:80])
    aud("cpt-draft", dec, "Counterparty", cpt_ref, "CPT_GENERATED")

    # ---- decision: proposal-draft (prose woven into the proposal markdown; sections fixed) ----
    c0 = mock_calls()
    st, _ = call(dec, "POST", f"/api/decisions/{ref}/credit-proposal/generate", None, actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: credit-proposal generate ok", st == 200, f"{st}")
    called("proposal-draft", c0, c1)
    _, prop = call(dec, "GET", f"/api/decisions/{ref}/credit-proposal")
    prose("proposal-draft", (prop or {}).get("markdown"))
    inv("proposal sections list", "proposal", (prop or {}).get("sections"))
    aud("proposal-draft", dec, "Application", ref, "CREDIT_PROPOSAL_GENERATED")

    # ---- decision: group-insights (prose narrative; member figures + group grade fixed) ----
    gref = make_group_with_members(cpt)
    c0 = mock_calls()
    st, gi = call(dec, "GET", f"/api/decisions/groups/{gref}/insights", actor="credit.ops")
    c1 = mock_calls()
    check(f"{tag}: group insights ok", st == 200, f"{st} {gi}")
    called("group-insights", c0, c1)
    prose("group-insights", (gi or {}).get("narrative"))
    inv("group insights memberCount/weightedPd/groupGrade", "groupinsights",
        ((gi or {}).get("memberCount"), (gi or {}).get("weightedAveragePd"), (gi or {}).get("groupGrade")))
    aud("group-insights", dec, "Group", gref, "GROUP_INSIGHTS_GENERATED")

    # ---- decision: covenant-extract (json; candidates validated + stay a DRAFT suggestion) ----
    cov_text = "The borrower shall maintain a DSCR of at least 1.40x, tested quarterly."
    c0 = mock_calls()
    st, cands = call(dec, "POST", f"/api/covenants/intel/{ref}/extract", {"text": cov_text}, actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: covenant-extract produced candidate(s)", st == 200 and isinstance(cands, list) and len(cands) >= 1, f"{st}")
    called("covenant-extract", c0, c1)
    if conf:
        blob = json.dumps(cands)
        check("configured: covenant-extract candidate carries MARKER + a valid taxonomy metric",
              MARKER in blob and any(c.get("metric") == "DSCR" for c in cands),
              f"{[c.get('metric') for c in cands]}")
    else:
        check("none: covenant-extract deterministic candidate (no marker)",
              all(MARKER not in (c.get("sourceText") or "") for c in cands), "marker leaked")
    check(f"{tag}: covenant-extract stays an advisory DRAFT (human confirm materialises the covenant)",
          all(c.get("status") == "DRAFT" for c in cands) and all(str(c.get("extractedBy") or "").startswith("ai:") for c in cands),
          f"{[(c.get('status'), c.get('extractedBy')) for c in cands]}")
    aud("covenant-extract", dec, "Application", ref, "COVENANT_EXTRACTED")

    # ---- decision: covenant-certificate (json; overlays borrower-reported fields only) ----
    cert_text = "DSCR complied at 1.85x for the quarter."
    c0 = mock_calls()
    st, lines = call(dec, "POST", f"/api/covenants/intel/{ref}/certificate/assess", {"text": cert_text}, actor="analyst.user")
    c1 = mock_calls()
    check(f"{tag}: covenant-certificate assessed line(s)", st == 200 and isinstance(lines, list) and len(lines) >= 1, f"{st}")
    called("covenant-certificate", c0, c1)
    dscr_line = next((l for l in (lines or []) if l.get("systemMetric") == "DSCR"), None)
    if conf:
        check("configured: covenant-certificate overlaid the borrower-reported label with the MARKER",
              dscr_line is not None and has_marker(dscr_line.get("reportedLabel")), f"{dscr_line}")
    else:
        check("none: covenant-certificate deterministic reported label (no marker)",
              dscr_line is not None and not has_marker(dscr_line.get("reportedLabel")), f"{dscr_line}")
    inv("certificate systemMetric + taxonomyMismatch (deterministic recompute)", "cert",
        None if dscr_line is None else (dscr_line.get("systemMetric"), dscr_line.get("taxonomyMismatch")))
    aud("covenant-certificate", dec, "Application", ref, "CERTIFICATE_ASSESSED")

    # ---- decision: docgen-clause (prose; polishes the analyst's custom clause text) ----
    st, gen = call(dec, "POST", f"/api/docs/applications/{ref}/generate",
                   {"templateKey": "FACILITY_AGREEMENT", "variables": {}}, actor="cad.officer")
    check(f"{tag}: docgen generated a DRAFT document", st == 200 and gen and gen.get("id"), f"{st} {gen}")
    docgen_id = gen.get("id")
    clause_text = "Borrower to submit audited financials within 120 days of financial year end."
    c0 = mock_calls()
    st, doc2 = call(dec, "POST", f"/api/docs/{docgen_id}/clauses",
                    {"clauseRef": "info_undertaking", "customText": clause_text}, actor="cad.officer")
    c1 = mock_calls()
    check(f"{tag}: docgen addClause ok", st == 200, f"{st} {doc2}")
    called("docgen-clause", c0, c1)
    clause = ((doc2 or {}).get("clauses") or {}).get("info_undertaking") or {}
    if conf:
        check("configured: docgen-clause text is LLM-polished (carries the MARKER)", has_marker(clause.get("text")), f"{clause}")
    else:
        check("none: docgen-clause uses the analyst custom text verbatim (no marker)",
              clause.get("text") == clause_text, f"{clause.get('text')!r}")
    aud("docgen-clause", dec, "GeneratedDocument", str(docgen_id), "DOCUMENT_CLAUSE_ADDED")

    if conf:
        figs_after = authoritative(orig, risk, ref)
        check("configured: authoritative rating/pricing/DSCR unchanged after ALL 20 advisory capabilities",
              figs_after == figs_start, f"start={figs_start} after={figs_after}")

    return {"ref": ref, "screening_cp_id": scr_id, "docgen_doc_id": docgen_id}


def advisory_outage(ports, ctx):
    """Outage phase (mock 5xx): a representative capability PER service still returns 200 with
    deterministic output (no marker), figures unchanged. Assumes MOCK_FAIL is already True."""
    cpt = f"http://localhost:{ports['counterparty']}"
    orig = f"http://localhost:{ports['origination']}"
    risk = f"http://localhost:{ports['risk']}"
    dec = f"http://localhost:{ports['decision']}"
    pf = f"http://localhost:{ports['portfolio']}"
    ref = ctx["ref"]
    print("\n-- fail-soft (outage) across the newly-wired services: deterministic fallback, no marker --")

    st, hits = call(cpt, "POST", f"/api/counterparties/{ctx['screening_cp_id']}/screening/run", actor="compliance.officer")
    check("fail-soft: screening-rationale falls back to the deterministic template (no marker)",
          st == 200 and hits and all(MARKER not in (h.get("aiRationale") or "") for h in hits), f"{st}")

    st, cex = call(orig, "POST", f"/api/collateral-intel/{ref}/extract",
                   {"documentKind": "VALUATION_REPORT", "text": "market value INR 550,000,000; valuer Acme; 1 Test Road"},
                   actor="analyst.user")
    check("fail-soft: collateral-extract falls back to deterministic regex (no marker, still SUGGESTED)",
          st == 200 and MARKER not in json.dumps((cex or {}).get("fields") or {}) and (cex or {}).get("status") == "SUGGESTED",
          f"{st}")

    st, rag = call(risk, "POST", f"/api/risk/{ref}/rag", None, actor="risk.analyst")
    check("fail-soft: rag-narrative falls back (no narrative key added to factors)",
          st == 200 and "narrative" not in ((rag or {}).get("factors") or {}), f"{st}")

    st, sigs = call(pf, "POST", f"/api/portfolio/exposures/{ref}/ews/scan", None, actor="portfolio.manager")
    check("fail-soft: ews-narrative falls back to the deterministic rationale (no marker)",
          st == 200 and sigs and all(MARKER not in (s.get("rationale") or "") for s in sigs), f"{st}")

    st, cands = call(dec, "POST", f"/api/covenants/intel/{ref}/extract",
                     {"text": "The borrower shall maintain a DSCR of at least 1.40x, tested quarterly."},
                     actor="analyst.user")
    check("fail-soft: covenant-extract falls back to deterministic candidates (no marker)",
          st == 200 and cands and all(MARKER not in (c.get("sourceText") or "") for c in cands), f"{st}")

    st, doc2 = call(dec, "POST", f"/api/docs/{ctx['docgen_doc_id']}/clauses",
                    {"clauseRef": "outage_clause", "customText": "Outage clause verbatim text."}, actor="cad.officer")
    clause = ((doc2 or {}).get("clauses") or {}).get("outage_clause") or {}
    check("fail-soft: docgen-clause uses the analyst text verbatim on outage (no polish/marker)",
          st == 200 and clause.get("text") == "Outage clause verbatim text.", f"{st} {clause.get('text')!r}")


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

    # the 20 wired advisory capabilities: deterministic path + captured baselines
    baseline = {}
    drive_advisory_caps(ports, "none", ref, doc_id, baseline)

    with MOCK_LOCK:
        calls = MOCK_CALLS
    check("none: ZERO calls hit the external LLM endpoint", calls == 0, f"mock calls={calls}")
    return figs, baseline


def run_configured(ports, none_figs, none_caps):
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

    # -- the 20 wired advisory capabilities: model called, invariants preserved, audit stamped --
    ctx = drive_advisory_caps(ports, "configured", ref, doc_id, none_caps)

    # -- fail-soft: mock 5xx (outage) -> graceful deterministic fallback --
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

    # extended outage coverage across the 20-capability surface (one representative per service)
    advisory_outage(ports, ctx)

    # figures still unchanged after the full outage sweep
    outage_figs = authoritative(orig, risk, ref)
    check("fail-soft: authoritative rating/pricing/DSCR unchanged through the outage sweep",
          outage_figs == before, f"before={before} outage={outage_figs}")
    with MOCK_LOCK:
        MOCK_FAIL = False


def main():
    ports = pick_ports()
    print(f"== e2e_llm: mock LLM :{ports['mock']} · stack ports {ports} ==")
    start_mock(ports["mock"])
    try:
        # DEFAULT profile
        dd1 = start_stack(ports, {"HELIX_LLM_PROVIDER": "none"})
        none_figs, none_caps = run_none(ports)
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
        run_configured(ports, none_figs, none_caps)
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
