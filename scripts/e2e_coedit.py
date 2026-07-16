#!/usr/bin/env python3
"""End-to-end test for the SharePoint / Excel-Online co-edit SPI (helix-common /api/coedit).

Self-contained: boots decision-service standalone (it includes helix-common, so it
auto-exposes /api/coedit) twice — once in the DEFAULT `none` config and once in `graph`
config pointed at a LOCAL mock Microsoft Graph server this script spins up. No gateway and
no other services are required, because the co-edit endpoint takes the artifact bytes in
the request body (exactly the local export the existing docgen HTML / charge-Excel CSV
flow produces) rather than fetching them.

Assertions:
  DEFAULT `none`
    - GET /api/coedit/provider reports `none`
    - POST returns the local artifact verbatim (mode=LOCAL, provider=none, no coEditUrl),
      the echoed base64 content decodes byte-for-byte to what was submitted (HTML + CSV),
      localUrl is echoed, fallback=false, and NO request ever hit the mock Graph server.
    - a COEDIT_LOCAL audit event is stamped under the subject with the X-Actor.
  `graph`
    - GET /api/coedit/provider reports `graph`
    - POST performs the real token -> upload -> link sequence against the mock and returns
      the mock webUrl as the co-edit URL (mode=COEDIT, fallback=false); an Excel (CSV)
      artifact additionally opens a workbook session.
    - a mock 5xx on upload -> graceful fallback to the local artifact (mode=LOCAL,
      fallback=true, warning present, echoed bytes intact) — the call never breaks.
"""
import base64
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

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(REPO, "decision-service", "target", "decision-service.jar")

PASS, FAIL = 0, 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# --------------------------------------------------------------------------- mock Graph

MOCK = {
    "requests": [],          # ordered (method, kind) of what the provider called
    "fail_upload": False,    # flip to make the upload return 500 (outage simulation)
    "web_url": "https://contoso.sharepoint.com/sites/credit/_layouts/15/Doc.aspx"
               "?sourcedoc=%7BABC123%7D&action=edit",
    "item_id": "01HELIXITEMID0000000000",
    "session_id": "workbook-session-xyz",
}


class MockGraphHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # quiet

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        _ = self.rfile.read(length) if length else b""
        path = self.path
        if path.endswith("/oauth2/v2.0/token"):
            MOCK["requests"].append(("POST", "token"))
            self._send(200, {"access_token": "fake-token-123",
                             "token_type": "Bearer", "expires_in": 3599})
        elif "/workbook/createSession" in path:
            MOCK["requests"].append(("POST", "session"))
            self._send(200, {"id": MOCK["session_id"]})
        else:
            self._send(404, {"error": "unexpected POST " + path})

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", 0))
        _ = self.rfile.read(length) if length else b""
        if self.path.endswith("/content"):
            if MOCK["fail_upload"]:
                MOCK["requests"].append(("PUT", "upload-500"))
                self._send(500, {"error": {"code": "serviceNotAvailable",
                                           "message": "simulated Graph outage"}})
            else:
                MOCK["requests"].append(("PUT", "upload"))
                self._send(201, {"id": MOCK["item_id"], "name": "artifact",
                                 "webUrl": MOCK["web_url"]})
        else:
            self._send(404, {"error": "unexpected PUT " + self.path})


# --------------------------------------------------------------------------- service ctl

def start_decision(port, extra_env):
    env = dict(os.environ)
    data_dir = tempfile.mkdtemp(prefix="coedit-data-")
    env["HELIX_DATA_DIR"] = data_dir
    env["SERVER_PORT"] = str(port)
    # keep the notification sweeper quiet / deterministic; irrelevant to this test
    env["HELIX_NOTIFY_SWEEP_ENABLED"] = "false"
    env.update(extra_env)
    log = open(os.path.join(data_dir, "svc.log"), "w")
    proc = subprocess.Popen(["java", "-jar", JAR], env=env, stdout=log, stderr=subprocess.STDOUT)
    proc._data_dir = data_dir  # type: ignore[attr-defined]
    proc._log = log            # type: ignore[attr-defined]
    return proc


def wait_health(port, timeout=90):
    deadline = time.time() + timeout
    url = f"http://127.0.0.1:{port}/actuator/health"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                if r.status == 200 and b'"UP"' in r.read():
                    return True
        except Exception:
            time.sleep(1.0)
    return False


def stop(proc):
    if proc is None:
        return
    try:
        proc.terminate()
        proc.wait(timeout=25)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass
    try:
        proc._log.close()
        shutil.rmtree(proc._data_dir, ignore_errors=True)
    except Exception:
        pass


def req(method, port, path, body=None, actor="rm.user"):
    url = f"http://127.0.0.1:{port}{path}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    r.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            txt = resp.read().decode()
            return resp.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        return e.code, (json.loads(txt) if txt else None)


# --------------------------------------------------------------------------- artifacts

HTML_ARTIFACT = ("<article class=\"helix-doc\"><h1>Facility Agreement — Meridian Steel</h1>"
                 "<section><h2>1. Facility</h2><p>The Lender agrees to make available INR "
                 "250,000,000.</p></section></article>")
CSV_ARTIFACT = ("Application,Borrower,Collateral ID,Type,Market Value,Haircut\r\n"
                "APP-COEDIT-1,Meridian Steel,C-101,PROPERTY,100000000,0.50\r\n")


def main():
    if not os.path.exists(JAR):
        print(f"decision-service jar not found at {JAR} — build it first "
              f"(mvn -q -pl decision-service -am package -DskipTests -Dmaven.compiler.release=21)")
        return 2

    # spin up the mock Graph server
    mock_port = free_port()
    httpd = ThreadingHTTPServer(("127.0.0.1", mock_port), MockGraphHandler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    mock_base = f"http://127.0.0.1:{mock_port}"

    dec = None
    try:
        # ============================================================ DEFAULT: none
        print("== 1. DEFAULT provider=none (today's local-export behaviour, no external call) ==")
        port = free_port()
        dec = start_decision(port, {})  # provider unset -> none via matchIfMissing
        if not wait_health(port):
            print("  FAIL  decision-service (none) did not become healthy")
            return 1

        st, prov = req("GET", port, "/api/coedit/provider")
        check("provider reports 'none'", st == 200 and prov.get("provider") == "none", f"{st} {prov}")

        st, res = req("POST", port, "/api/coedit",
                      {"subjectType": "GeneratedDocument", "subjectId": "77",
                       "fileName": "facility-agreement.html", "contentType": "text/html",
                       "content": HTML_ARTIFACT, "encoding": "text",
                       "localUrl": "/api/docs/77/print"}, actor="cad.officer")
        ok = (st == 200 and res["provider"] == "none" and res["mode"] == "LOCAL"
              and res["fallback"] is False and res["coEditUrl"] is None
              and res["localUrl"] == "/api/docs/77/print")
        check("none: HTML co-edit returns the local artifact (mode=LOCAL, no coEditUrl)", ok, f"{st} {res}")
        decoded = base64.b64decode(res["contentBase64"]).decode() if res and res.get("contentBase64") else ""
        check("none: echoed bytes decode byte-for-byte to the submitted HTML",
              decoded == HTML_ARTIFACT, "content mismatch")

        st, res_csv = req("POST", port, "/api/coedit",
                          {"subjectType": "Application", "subjectId": "APP-COEDIT-1",
                           "fileName": "charge-APP-COEDIT-1.csv", "contentType": "text/csv",
                           "content": base64.b64encode(CSV_ARTIFACT.encode()).decode(),
                           "encoding": "base64",
                           "localUrl": "/api/collateral-intel/APP-COEDIT-1/charge-excel"},
                          actor="credit.ops")
        csv_decoded = (base64.b64decode(res_csv["contentBase64"]).decode()
                       if res_csv and res_csv.get("contentBase64") else "")
        check("none: charge-Excel CSV returns the local artifact verbatim (base64 round-trip)",
              st == 200 and res_csv["mode"] == "LOCAL" and csv_decoded == CSV_ARTIFACT, f"{st}")

        check("none: NO request ever reached the mock Graph server", len(MOCK["requests"]) == 0,
              f"mock saw {MOCK['requests']}")

        st, events = req("GET", port, "/api/audit/subject?type=GeneratedDocument&id=77")
        check("none: COEDIT_LOCAL audit event stamped with the X-Actor",
              st == 200 and any(e["eventType"] == "COEDIT_LOCAL" and e["actor"] == "cad.officer"
                                and e["actorType"] == "HUMAN" for e in events), f"{st}")

        st, bad = req("POST", port, "/api/coedit",
                      {"subjectType": "X", "subjectId": "1", "fileName": "x", "content": ""},
                      actor="rm.user")
        check("none: empty content rejected (400)", st == 400, f"{st}")

        stop(dec)
        dec = None

        # ============================================================ graph -> mock
        print("== 2. provider=graph -> local mock Graph (token -> upload -> link) ==")
        MOCK["requests"].clear()
        MOCK["fail_upload"] = False
        port = free_port()
        dec = start_decision(port, {
            "HELIX_COEDIT_PROVIDER": "graph",
            "HELIX_COEDIT_TENANT_ID": "test-tenant",
            "HELIX_COEDIT_CLIENT_ID": "test-client",
            "HELIX_COEDIT_CLIENT_SECRET": "test-secret",
            "HELIX_COEDIT_DRIVE_ID": "test-drive",
            "HELIX_COEDIT_GRAPH_BASE_URL": mock_base,
            "HELIX_COEDIT_TOKEN_URL": mock_base,
        })
        if not wait_health(port):
            print("  FAIL  decision-service (graph) did not become healthy")
            return 1

        st, prov = req("GET", port, "/api/coedit/provider")
        check("provider reports 'graph'", st == 200 and prov.get("provider") == "graph", f"{st} {prov}")

        st, res = req("POST", port, "/api/coedit",
                      {"subjectType": "GeneratedDocument", "subjectId": "88",
                       "fileName": "sanction-letter.html", "contentType": "text/html",
                       "content": HTML_ARTIFACT, "encoding": "text",
                       "localUrl": "/api/docs/88/print"}, actor="cad.officer")
        ok = (st == 200 and res["provider"] == "graph" and res["mode"] == "COEDIT"
              and res["fallback"] is False and res["coEditUrl"] == MOCK["web_url"]
              and res["webUrl"] == MOCK["web_url"])
        check("graph: HTML publish returns the SharePoint co-edit URL (mode=COEDIT)", ok, f"{st} {res}")
        seq = MOCK["requests"]
        check("graph: provider performed token -> upload sequence in order",
              seq[:2] == [("POST", "token"), ("PUT", "upload")], f"seq={seq}")

        MOCK["requests"].clear()
        st, res = req("POST", port, "/api/coedit",
                      {"subjectType": "Application", "subjectId": "APP-COEDIT-2",
                       "fileName": "charge-APP-COEDIT-2.csv", "contentType": "text/csv",
                       "content": CSV_ARTIFACT, "encoding": "text",
                       "localUrl": "/api/collateral-intel/APP-COEDIT-2/charge-excel"},
                      actor="credit.ops")
        check("graph: Excel (CSV) publish returns co-edit URL + opens a workbook session",
              st == 200 and res["mode"] == "COEDIT" and res["coEditUrl"] == MOCK["web_url"]
              and res["workbookSessionId"] == MOCK["session_id"], f"{st} {res}")
        seq = MOCK["requests"]
        check("graph: Excel path performed token -> upload -> workbook session",
              seq == [("POST", "token"), ("PUT", "upload"), ("POST", "session")], f"seq={seq}")

        st, events = req("GET", port, "/api/audit/subject?type=GeneratedDocument&id=88")
        check("graph: COEDIT_PUBLISHED audit event stamped",
              st == 200 and any(e["eventType"] == "COEDIT_PUBLISHED" for e in events), f"{st}")

        # ============================================================ graph outage -> fallback
        print("== 3. graph outage (mock 5xx) -> graceful fallback to the local artifact ==")
        MOCK["requests"].clear()
        MOCK["fail_upload"] = True
        st, res = req("POST", port, "/api/coedit",
                      {"subjectType": "GeneratedDocument", "subjectId": "99",
                       "fileName": "sanction-letter.html", "contentType": "text/html",
                       "content": HTML_ARTIFACT, "encoding": "text",
                       "localUrl": "/api/docs/99/print"}, actor="cad.officer")
        fb_decoded = (base64.b64decode(res["contentBase64"]).decode()
                      if res and res.get("contentBase64") else "")
        ok = (st == 200 and res["mode"] == "LOCAL" and res["fallback"] is True
              and res["coEditUrl"] is None and fb_decoded == HTML_ARTIFACT
              and res["warnings"] and len(res["warnings"]) >= 1)
        check("graph 5xx: falls back to local artifact (mode=LOCAL, fallback=true, bytes intact)",
              ok, f"{st} {res}")
        check("graph 5xx: the upload was actually attempted before falling back",
              ("PUT", "upload-500") in MOCK["requests"], f"seq={MOCK['requests']}")
        st, events = req("GET", port, "/api/audit/subject?type=GeneratedDocument&id=99")
        check("graph 5xx: COEDIT_FALLBACK audit event stamped",
              st == 200 and any(e["eventType"] == "COEDIT_FALLBACK" for e in events), f"{st}")

        stop(dec)
        dec = None
    finally:
        stop(dec)
        httpd.shutdown()

    print(f"\n{'='*60}\n  co-edit e2e: {PASS} passed, {FAIL} failed\n{'='*60}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
