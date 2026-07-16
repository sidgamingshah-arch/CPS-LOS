#!/usr/bin/env python3
"""
DMS document store + CRM write-back — e2e.

Two product gaps closed, both config-gated with regression-safe defaults:

  A. DMS (helix.dms.store=filesystem default | s3): a real document store auto-exposed at
     /api/documents in every service (like /api/audit). Upload stores bytes + metadata
     (subject / filename / contentType / size / sha256 / uploadedBy); download returns the
     bytes byte-identically; list by subjectRef; every op is X-Actor-audited
     (DOCUMENT_STORED / DOCUMENT_RETRIEVED). This suite exercises the filesystem default.

  B. CRM write-back (helix.crm.mode=simulated default | live): a CrmConnector on the canonical
     export contract auto-exposed at /api/crm. simulated mode records the would-be call as an
     idempotent row (exactly like the downstream export facade); re-running the same as-of day
     returns the same row. If service jars are present, a self-contained live-mode section boots
     an isolated config-service pointed at a local mock CRM and asserts a real POST is delivered.

Assumes the stack is up on the gateway (bash scripts/run-all.sh), like the other suites.
"""
import base64
import hashlib
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

GW = os.environ.get("HELIX_GW", "http://localhost:8080")
ROOT = Path(__file__).resolve().parents[1]
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="dms.user", base=GW, headers=None):
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        return e.code, (json.loads(txt) if txt else None)


def download(path, actor="dms.user", base=GW):
    """Raw-bytes GET for document download; returns (status, bytes, headers)."""
    req = urllib.request.Request(base + path, method="GET")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


# ============================================================ A. DMS (filesystem default)
print("== 1. DMS upload/store (base64 JSON) — bytes + sha256 + metadata (filesystem default) ==")
# Binary payload incl. non-UTF8 bytes to prove the store is binary-safe, not text-only.
raw = bytes(range(0, 256)) * 8 + b"Helix CAM proposal PDF bytes \x00\x01\x02"
sha_local = hashlib.sha256(raw).hexdigest()
subject_ref = "HLX-DMS-" + hashlib.sha1(str(time.time()).encode()).hexdigest()[:8].upper()

st, up = call("POST", "/portfolio/api/documents", {
    "subjectType": "LoanApplication", "subjectRef": subject_ref,
    "filename": "credit-proposal.pdf", "contentType": "application/pdf",
    "contentBase64": base64.b64encode(raw).decode()}, actor="rm.alice")
up = up or {}
check("upload returns stored-document metadata (200)", st == 200 and up.get("id") is not None, f"{st} {up}")
check("size recorded matches uploaded bytes", up.get("sizeBytes") == len(raw), f"{up.get('sizeBytes')} vs {len(raw)}")
check("server-computed sha256 matches local digest", up.get("sha256") == sha_local, f"{up.get('sha256')}")
check("metadata captured (filename/contentType/subject/uploadedBy)",
      up.get("filename") == "credit-proposal.pdf" and up.get("contentType") == "application/pdf"
      and up.get("subjectRef") == subject_ref and up.get("uploadedBy") == "rm.alice", str(up))
check("stored via the filesystem backend by default", up.get("storageBackend") == "FILESYSTEM", str(up.get("storageBackend")))
doc_id = up.get("id")

print("\n== 2. DMS download — byte-identical round-trip ==")
st, got, hdrs = download(f"/portfolio/api/documents/{doc_id}", actor="ops.bob")
check("download returns 200", st == 200, f"{st}")
check("downloaded bytes are byte-identical to upload", got == raw, f"len {len(got)} vs {len(raw)}")
check("downloaded bytes hash to the same sha256", hashlib.sha256(got).hexdigest() == sha_local, "hash mismatch")
check("download advertises the stored content-type",
      "application/pdf" in (hdrs.get("Content-Type", "")), hdrs.get("Content-Type"))
check("integrity header echoes the stored sha256", hdrs.get("X-Document-Sha256") == sha_local, hdrs.get("X-Document-Sha256"))

print("\n== 3. DMS list by subjectRef + metadata read ==")
st, lst = call("GET", f"/portfolio/api/documents?subjectRef={subject_ref}")
check("list by subjectRef returns the document", st == 200 and any(d["id"] == doc_id for d in (lst or [])), f"{st} {lst}")
st, meta = call("GET", f"/portfolio/api/documents/{doc_id}/meta")
check("metadata endpoint returns the row", st == 200 and meta.get("sha256") == sha_local, f"{st}")

print("\n== 4. DMS multipart upload path ==")
boundary = "----helixDMSBoundary7MA4YWxkTrZu0gW"
mp_bytes = b"\x89PNG\r\n\x1a\n multipart binary sample \xff\xd8\xff"
parts = []
parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"subjectType\"\r\n\r\nCounterparty\r\n".encode())
parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"subjectRef\"\r\n\r\n{subject_ref}\r\n".encode())
parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"kyc.png\"\r\n"
              f"Content-Type: image/png\r\n\r\n").encode() + mp_bytes + b"\r\n")
parts.append(f"--{boundary}--\r\n".encode())
mp_body = b"".join(parts)
mp_req = urllib.request.Request(GW + "/portfolio/api/documents/multipart", data=mp_body, method="POST")
mp_req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
mp_req.add_header("X-Actor", "rm.alice")
try:
    with urllib.request.urlopen(mp_req, timeout=30) as r:
        mp = json.loads(r.read().decode())
        mp_st = r.status
except urllib.error.HTTPError as e:
    mp_st, mp = e.code, {}
check("multipart upload stores the file", mp_st == 200 and mp.get("filename") == "kyc.png"
      and mp.get("sizeBytes") == len(mp_bytes), f"{mp_st} {mp}")
if mp.get("id"):
    _, mpgot, _ = download(f"/portfolio/api/documents/{mp['id']}")
    check("multipart bytes round-trip byte-identical", mpgot == mp_bytes, f"len {len(mpgot)}")

print("\n== 5. DMS audit — DOCUMENT_STORED + DOCUMENT_RETRIEVED stamped with actor ==")
st, evs = call("GET", f"/portfolio/api/audit/subject?type=StoredDocument&id={doc_id}")
evs = evs or []
stored = [e for e in evs if e.get("eventType") == "DOCUMENT_STORED"]
retrieved = [e for e in evs if e.get("eventType") == "DOCUMENT_RETRIEVED"]
check("DOCUMENT_STORED audit present, HUMAN actor rm.alice",
      any(e.get("actorType") == "HUMAN" and e.get("actor") == "rm.alice" for e in stored), str(evs))
check("DOCUMENT_RETRIEVED audit present, HUMAN actor ops.bob",
      any(e.get("actorType") == "HUMAN" and e.get("actor") == "ops.bob" for e in retrieved), str(evs))

print("\n== 6. DMS negative cases ==")
st, _ = call("POST", "/portfolio/api/documents", {"filename": "x.pdf", "contentBase64": ""})
check("empty content rejected (400)", st == 400, f"{st}")
st, _ = call("GET", "/portfolio/api/documents/999999999")
check("unknown document id -> 404", st == 404, f"{st}")


# ============================================================ B. CRM write-back (simulated default)
print("\n== 7. CRM write-back (simulated default) — idempotent record on the export contract ==")
case_ref = "CASE-" + hashlib.sha1(str(time.time()).encode()).hexdigest()[:8].upper()
wb_body = {"subjectType": "CadCase", "subjectRef": subject_ref, "caseRef": case_ref,
           "stage": "DECISION", "status": "APPROVED", "decision": "APPROVE",
           "decisionBy": "credit.head", "decisionAt": "2026-07-16T10:00:00Z",
           "comments": "Approved within DoA; limits released."}
st, wb = call("POST", "/decision/api/crm/writeback", wb_body, actor="decision.svc")
wb = wb or {}
check("write-back created (200)", st == 200 and wb.get("id") is not None, f"{st} {wb}")
check("simulated mode records only (no egress)", wb.get("mode") == "SIMULATED"
      and wb.get("deliveryStatus") == "SIMULATED", str(wb))
check("routed to the CRM destination on the export contract",
      wb.get("destination") == "CRM" and wb.get("feedType") == "CASE_STATUS" and wb.get("recordCount") == 1, str(wb))
check("canonical envelope carries the typed case-status record + payload version",
      wb.get("envelope", {}).get("payloadVersion") == "1.0"
      and wb["envelope"]["records"][0]["status"] == "APPROVED"
      and wb["envelope"]["records"][0]["caseRef"] == case_ref, str(wb.get("envelope", {}).get("destination")))
wb_id = wb.get("id")

print("\n== 8. CRM write-back idempotency (same case + as-of day) ==")
st, wb2 = call("POST", "/decision/api/crm/writeback", wb_body, actor="decision.svc")
check("re-trigger returns the SAME row (idempotent)", st == 200 and wb2.get("id") == wb_id, f"{wb_id} vs {wb2.get('id')}")
# A distinct case produces a distinct row.
wb_body2 = dict(wb_body, caseRef="CASE-" + hashlib.sha1(str(time.time() + 1).encode()).hexdigest()[:8].upper())
st, wb3 = call("POST", "/decision/api/crm/writeback", wb_body2, actor="decision.svc")
check("a distinct case yields a distinct row", st == 200 and wb3.get("id") != wb_id, f"{wb3.get('id')}")

print("\n== 9. CRM write-back list + detail + audit ==")
st, lst = call("GET", f"/decision/api/crm/writebacks?subjectRef={subject_ref}")
check("write-backs listable by subjectRef", st == 200 and any(w["id"] == wb_id for w in (lst or [])), f"{st}")
st, one = call("GET", f"/decision/api/crm/writebacks/{wb_id}")
check("detail returns full envelope", st == 200 and one["envelope"]["destination"] == "CRM", f"{st}")
st, evs = call("GET", f"/decision/api/audit/subject?type=CrmWriteBack&id={wb_id}")
check("CRM_WRITEBACK_SIMULATED audit stamped (SYSTEM/engine, triggeredBy recorded)",
      any(e.get("eventType") == "CRM_WRITEBACK_SIMULATED" and e.get("actorType") == "SYSTEM" for e in (evs or [])),
      str(evs))

print("\n== 10. CRM write-back negative case ==")
st, _ = call("POST", "/decision/api/crm/writeback", {"stage": "DECISION"})
check("write-back with neither caseRef nor subjectRef -> 400", st == 400, f"{st}")


# ============================================================ B(live). CRM live-mode POST to a local mock (best effort)
print("\n== 11. CRM live-mode -> real POST to a local mock CRM (self-contained) ==")
jar = ROOT / "config-service" / "target" / "config-service.jar"
if not jar.exists():
    print("  SKIP  config-service jar not present; live-mode section skipped (build the reactor to enable)")
else:
    received = []
    mock_port = int(os.environ.get("HELIX_CRM_MOCK_PORT", "8099"))
    live_port = int(os.environ.get("HELIX_CRM_LIVE_PORT", "8091"))

    class MockCrm(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"  # JDK HttpClient expects HTTP/1.1 + Content-Length

        def _read_body(self):
            te = self.headers.get("Transfer-Encoding", "").lower()
            if "chunked" in te:  # JDK HttpClient streams the request body chunked
                data = b""
                while True:
                    line = self.rfile.readline().strip()
                    if not line:
                        continue
                    size = int(line, 16)
                    if size == 0:
                        self.rfile.readline()
                        break
                    data += self.rfile.read(size)
                    self.rfile.read(2)
                return data
            length = int(self.headers.get("Content-Length", 0))
            return self.rfile.read(length) if length else b""

        def do_POST(self):
            payload = self._read_body()
            try:
                body = json.loads(payload.decode()) if payload else None
            except Exception:
                body = None
            received.append((self.path, body))
            resp = b'{"ok":true}'
            self.send_response(200)
            self.send_header("X-Crm-Ref", "CRM-OK-12345")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)

        def log_message(self, *a):
            pass

    httpd = HTTPServer(("127.0.0.1", mock_port), MockCrm)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()

    data_dir = ROOT / "data" / f"crm-live-{live_port}"
    data_dir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ,
               SERVER_PORT=str(live_port),
               HELIX_DATA_DIR=str(data_dir),
               HELIX_CRM_MODE="live",
               HELIX_CRM_BASE_URL=f"http://127.0.0.1:{mock_port}",
               HELIX_CRM_PATH="/cases/status")
    proc = subprocess.Popen(["java", "-jar", str(jar)], env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        up = False
        for _ in range(60):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{live_port}/actuator/health", timeout=2) as r:
                    if r.status == 200:
                        up = True
                        break
            except Exception:
                time.sleep(1)
        check("isolated live-mode config-service came up", up, "health never green")
        if up:
            base = f"http://127.0.0.1:{live_port}"
            live_case = "CASE-LIVE-" + hashlib.sha1(str(time.time()).encode()).hexdigest()[:8].upper()
            st, lwb = call("POST", "/api/crm/writeback",
                           {"subjectType": "CadCase", "subjectRef": subject_ref, "caseRef": live_case,
                            "stage": "DECISION", "status": "APPROVED", "decisionBy": "credit.head"},
                           actor="decision.svc", base=base)
            lwb = lwb or {}
            check("live write-back POST delivered", st == 200 and lwb.get("mode") == "LIVE"
                  and lwb.get("deliveryStatus") == "DELIVERED", f"{st} {lwb}")
            check("mock CRM received the canonical envelope at the configured path",
                  any(p == "/cases/status" and b and b.get("destination") == "CRM" for p, b in received),
                  str(received))
            check("delivered row captured the CRM provider reference",
                  lwb.get("providerRef") == "CRM-OK-12345", str(lwb.get("providerRef")))
            st, lwb2 = call("POST", "/api/crm/writeback",
                            {"subjectType": "CadCase", "subjectRef": subject_ref, "caseRef": live_case,
                             "stage": "DECISION", "status": "APPROVED", "decisionBy": "credit.head"},
                            actor="decision.svc", base=base)
            check("live write-back is idempotent (same row, no second POST)",
                  lwb2.get("id") == lwb.get("id") and len(received) == 1, f"rows={lwb2.get('id')} posts={len(received)}")
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=20)
        except Exception:
            proc.kill()
        httpd.shutdown()


print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
