#!/usr/bin/env python3
"""
Real notification transport (SMTP email + SMS) — e2e, self-contained.

The helix-common notify lane gained pluggable REAL transports behind the same SPI:
  helix.notify.transport = outbox (DEFAULT) | smtp | sms | all
The default (outbox) is byte-identical to before — render + persist, NO transmission — while
smtp/sms/all actually transmit the already-rendered message and record delivery status
(SENT/FAILED + provider id / error) on the outbox row. Transmission is best-effort + fail-soft:
a transport error records FAILED and never breaks the enqueue transaction.

This test boots a single consumer service (decision-service) three times against a
dependency-free, in-process capture, one mode at a time (no external SMTP/SMS, no config-service):
  * a raw-socket SMTP sink captures RCPT + DATA;
  * a tiny HTTP server captures the SMS gateway POST.

Proves:
  1. DEFAULT outbox — enqueue dispatches OUTBOX/SENT with providerRef 'outbox:<id>', and the
     SMTP sink receives NOTHING even though a mail server is configured (no send attempted:
     identical to today).
  2. smtp mode — an enqueued notification is actually transmitted: the SMTP sink captures the
     recipient + rendered subject/body, and the outbox row flips to SENT with an 'smtp:' provider
     ref + sentAt. No resolvable address degrades to FAILED (HTTP 200 — business tx intact).
  3. sms mode — the SMS gateway receives the POST (phone + message); the row is SENT with an
     'sms:' provider ref. No resolvable phone degrades to FAILED.

Exit non-zero on any failure. Only this test's own child PIDs are started/stopped.
"""
import json
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "decision-service", "target", "decision-service.jar")
SCRATCH = os.environ.get("SCRATCH_DIR", "/tmp/claude-notify-transport")
os.makedirs(SCRATCH, exist_ok=True)

PASS, FAIL = 0, 0
CHILDREN = []       # (proc, name) started by this test


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
    p = s.getsockname()[1]
    s.close()
    return p


# --------------------------------------------------------------------------- in-process SMTP sink
class SmtpSink:
    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.port = self.sock.getsockname()[1]
        self.sock.listen(8)
        self.sock.settimeout(0.5)
        self.messages = []
        self.lock = threading.Lock()
        self._stop = False
        self.t = threading.Thread(target=self._serve, daemon=True)
        self.t.start()

    def _serve(self):
        while not self._stop:
            try:
                conn, _ = self.sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                self._handle(conn)
            except Exception:
                pass
            finally:
                try:
                    conn.close()
                except Exception:
                    pass

    def _handle(self, conn):
        f = conn.makefile("rb")

        def send(s):
            conn.sendall(s.encode())

        send("220 helix-smtp-sink ESMTP\r\n")
        rcpts = []
        while True:
            line = f.readline()
            if not line:
                break
            cmd = line.decode(errors="replace").strip()
            up = cmd.upper()
            if up.startswith(("EHLO", "HELO")):
                send("250 helix-smtp-sink\r\n")
            elif up.startswith("MAIL"):
                send("250 OK\r\n")
            elif up.startswith("RCPT"):
                m = re.search(r"<([^>]*)>", cmd)
                rcpts.append(m.group(1) if m else cmd)
                send("250 OK\r\n")
            elif up.startswith("DATA"):
                send("354 End data with <CR><LF>.<CR><LF>\r\n")
                buf = []
                while True:
                    dl = f.readline()
                    if not dl or dl in (b".\r\n", b".\n"):
                        break
                    buf.append(dl)
                data = b"".join(buf).decode(errors="replace")
                with self.lock:
                    self.messages.append({"rcpts": list(rcpts), "data": data})
                send("250 OK queued\r\n")
            elif up.startswith("QUIT"):
                send("221 Bye\r\n")
                break
            elif up.startswith("RSET"):
                rcpts = []
                send("250 OK\r\n")
            else:
                send("250 OK\r\n")

    def count(self):
        with self.lock:
            return len(self.messages)

    def snapshot(self):
        with self.lock:
            return list(self.messages)

    def stop(self):
        self._stop = True
        try:
            self.sock.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------- in-process SMS sink
class SmsSink:
    def __init__(self):
        self.requests = []
        self.lock = threading.Lock()
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _read_body(self):
                te = (self.headers.get("Transfer-Encoding", "") or "").lower()
                if "chunked" in te:
                    data = b""
                    while True:
                        size_line = self.rfile.readline().strip()
                        if not size_line:
                            break
                        try:
                            size = int(size_line.split(b";")[0], 16)
                        except ValueError:
                            break
                        if size == 0:
                            self.rfile.readline()   # trailing CRLF
                            break
                        data += self.rfile.read(size)
                        self.rfile.readline()       # CRLF after each chunk
                    return data.decode(errors="replace")
                n = int(self.headers.get("Content-Length", 0) or 0)
                return self.rfile.read(n).decode(errors="replace") if n else ""

            def do_POST(self):
                body = self._read_body()
                with outer.lock:
                    outer.requests.append({"path": self.path,
                                           "headers": {k: v for k, v in self.headers.items()},
                                           "body": body})
                    idx = len(outer.requests)
                resp = json.dumps({"id": f"gw-{idx}", "status": "accepted"}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)

        self.httpd = HTTPServer(("127.0.0.1", 0), H)
        self.port = self.httpd.server_address[1]
        self.t = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.t.start()

    def count(self):
        with self.lock:
            return len(self.requests)

    def snapshot(self):
        with self.lock:
            return list(self.requests)

    def stop(self):
        try:
            self.httpd.shutdown()
        except Exception:
            pass


# ------------------------------------------------------------------------------ service lifecycle
def start_service(name, extra_env):
    port = free_port()
    data_dir = os.path.join(SCRATCH, f"data-{name}-{int(time.time()*1000)}")
    os.makedirs(data_dir, exist_ok=True)
    env = os.environ.copy()
    env["SERVER_PORT"] = str(port)
    env["HELIX_DATA_DIR"] = data_dir
    env["HELIX_NOTIFY_TEST_ENQUEUE_ENABLED"] = "true"
    # No config-service in this self-contained test — point the (lazy) clients at a refused
    # endpoint so they fail fast; the notify template/contact resolvers degrade gracefully.
    env["CONFIG_SERVICE_URL"] = "http://127.0.0.1:9"
    # Quiet the recurring background sweeper so it never races the deterministic assertions.
    env["HELIX_NOTIFY_SWEEP_ENABLED"] = "false"
    env.update(extra_env)
    logf = open(os.path.join(SCRATCH, f"{name}.log"), "w")
    proc = subprocess.Popen(["java", "-jar", JAR], env=env, stdout=logf, stderr=subprocess.STDOUT)
    CHILDREN.append((proc, name))
    base = f"http://127.0.0.1:{port}"
    if not wait_health(base):
        print(f"  ERROR {name} failed to become healthy on {port}; see {SCRATCH}/{name}.log")
        tail_log(name)
        stop_all()
        sys.exit(1)
    return base, proc


def wait_health(base, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base + "/actuator/health", timeout=3) as r:
                if r.status == 200 and b'"UP"' in r.read():
                    return True
        except Exception:
            pass
        time.sleep(1)
    return False


def tail_log(name, n=40):
    try:
        with open(os.path.join(SCRATCH, f"{name}.log")) as f:
            print("".join(f.readlines()[-n:]))
    except Exception:
        pass


def stop_all():
    for proc, name in CHILDREN:
        try:
            proc.send_signal(signal.SIGTERM)
        except Exception:
            pass
    time.sleep(2)
    for proc, name in CHILDREN:
        try:
            if proc.poll() is None:
                proc.kill()
        except Exception:
            pass


def call(base, method, path, body=None, actor="ops.admin"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
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


def enqueue(base, subject_ref, dedupe, recipients, roles=None, subj_tok="Notify", body_tok="hello"):
    body = {"eventType": "E2E_TRANSPORT", "templateKey": "E2E_TRANSPORT",
            "subjectType": "E2ETransport", "subjectRef": subject_ref, "dedupeKey": dedupe,
            "vars": {"subjTok": subj_tok, "bodyTok": body_tok},
            "recipientRoles": roles or [], "recipients": recipients}
    st, n = call(base, "POST", "/api/notifications/_test-enqueue", body)
    return st, n


NONCE = str(int(time.time()))


def main():
    if not os.path.exists(JAR):
        print(f"ERROR: {JAR} not found — build decision-service first")
        sys.exit(1)

    smtp = SmtpSink()
    sms = SmsSink()
    print(f"SMTP sink :{smtp.port}   SMS sink :{sms.port}")

    try:
        # ---------------------------------------------------------- 1. DEFAULT outbox (no send)
        print("\n== 1. DEFAULT outbox: render + persist, NO transmission (byte-identical) ==")
        base, _ = start_service("outbox", {
            # A mail server IS configured but transport stays default (outbox) — proving the
            # default never transmits even when it could.
            "SPRING_MAIL_HOST": "127.0.0.1", "SPRING_MAIL_PORT": str(smtp.port),
        })
        before = smtp.count()
        st, n = enqueue(base, f"OBX-{NONCE}", "obx", ["ops@helix.test"])
        check("outbox enqueue returns 200", st == 200, f"HTTP {st} {n}")
        check("transport recorded OUTBOX", n and n.get("transport") == "OUTBOX", n.get("transport") if n else None)
        check("status SENT (record-only)", n and n.get("status") == "SENT", n.get("status") if n else None)
        check("providerRef is the synthetic outbox ref", n and str(n.get("providerRef", "")).startswith("outbox:"),
              n.get("providerRef") if n else None)
        time.sleep(1)
        check("NO SMTP transmission attempted in outbox mode (sink unchanged)", smtp.count() == before,
              f"{before} -> {smtp.count()}")
        stop_all()
        CHILDREN.clear()

        # ------------------------------------------------------------------- 2. smtp mode (send)
        print("\n== 2. smtp mode: actually transmit via SMTP, row flips to SENT ==")
        base, _ = start_service("smtp", {
            "HELIX_NOTIFY_TRANSPORT": "smtp",
            "SPRING_MAIL_HOST": "127.0.0.1", "SPRING_MAIL_PORT": str(smtp.port),
        })
        before = smtp.count()
        st, n = enqueue(base, f"SMTP-{NONCE}", "smtp", ["credit.officer@helix.test"],
                        subj_tok="SmtpProof", body_tok="body-INR-42")
        check("smtp enqueue returns 200", st == 200, f"HTTP {st} {n}")
        check("transport recorded SMTP", n and n.get("transport") == "SMTP", n.get("transport") if n else None)
        check("outbox row flipped to SENT", n and n.get("status") == "SENT", n.get("status") if n else None)
        check("providerRef is an smtp: delivery ref", n and str(n.get("providerRef", "")).startswith("smtp:"),
              n.get("providerRef") if n else None)
        check("sentAt stamped on SMTP dispatch", n and n.get("sentAt") is not None)
        deadline = time.time() + 10
        while smtp.count() <= before and time.time() < deadline:
            time.sleep(0.3)
        check("SMTP sink actually received a message (real transmission)", smtp.count() == before + 1,
              f"{before} -> {smtp.count()}")
        msgs = smtp.snapshot()
        last = msgs[-1] if msgs else {"rcpts": [], "data": ""}
        check("captured RCPT is the resolved recipient address",
              "credit.officer@helix.test" in last["rcpts"], last["rcpts"])
        check("captured DATA carries the rendered subject + body (not a placeholder)",
              "SmtpProof" in last["data"] and "body-INR-42" in last["data"] and "{{" not in last["data"],
              last["data"][:200])
        # persisted row reflects the delivery too
        st, row = call(base, "GET", f"/api/notifications/{n['id']}")
        check("persisted row records SMTP/SENT + delivery ref",
              row and row.get("transport") == "SMTP" and row.get("status") == "SENT"
              and str(row.get("providerRef", "")).startswith("smtp:"), row)

        print("   -- degrade gracefully when no address resolves --")
        st, n2 = enqueue(base, f"SMTP-NOADDR-{NONCE}", "noaddr", [])   # no recipients, no roles
        check("no-address enqueue still returns 200 (business tx intact)", st == 200, f"HTTP {st} {n2}")
        check("no-address row recorded FAILED (fail-soft, not thrown)",
              n2 and n2.get("status") == "FAILED", n2.get("status") if n2 else None)
        check("FAILED row carries a failureReason", n2 and n2.get("failureReason"),
              n2.get("failureReason") if n2 else None)
        stop_all()
        CHILDREN.clear()

        # -------------------------------------------------------------------- 3. sms mode (send)
        print("\n== 3. sms mode: actually POST to the HTTP SMS gateway, row flips to SENT ==")
        base, _ = start_service("sms", {
            "HELIX_NOTIFY_TRANSPORT": "sms",
            "HELIX_NOTIFY_SMS_GATEWAY_URL": f"http://127.0.0.1:{sms.port}/send",
            "HELIX_NOTIFY_SMS_API_KEY": "test-key-123",
        })
        before = sms.count()
        st, n = enqueue(base, f"SMS-{NONCE}", "sms", ["+15551230000"],
                        subj_tok="SmsProof", body_tok="sms-body-99")
        check("sms enqueue returns 200", st == 200, f"HTTP {st} {n}")
        check("transport recorded SMS", n and n.get("transport") == "SMS", n.get("transport") if n else None)
        check("outbox row flipped to SENT", n and n.get("status") == "SENT", n.get("status") if n else None)
        check("providerRef records the gateway id (sms:gw-*)",
              n and str(n.get("providerRef", "")).startswith("sms:gw-"), n.get("providerRef") if n else None)
        deadline = time.time() + 10
        while sms.count() <= before and time.time() < deadline:
            time.sleep(0.3)
        check("SMS gateway actually received a POST (real transmission)", sms.count() == before + 1,
              f"{before} -> {sms.count()}")
        reqs = sms.snapshot()
        last = reqs[-1] if reqs else {"body": "", "headers": {}}
        check("gateway payload carries the phone number", "+15551230000" in last["body"], last["body"][:200])
        check("gateway payload carries the rendered message", "sms-body-99" in last["body"], last["body"][:200])
        check("api-key header forwarded to the gateway",
              last["headers"].get("X-Api-Key") == "test-key-123", last["headers"].get("X-Api-Key"))

        print("   -- degrade gracefully when no phone resolves --")
        st, n2 = enqueue(base, f"SMS-NOPHONE-{NONCE}", "nophone", ["not-a-phone@x.test"])  # email, no phone
        check("no-phone enqueue still returns 200", st == 200, f"HTTP {st} {n2}")
        check("no-phone row recorded FAILED (fail-soft)", n2 and n2.get("status") == "FAILED",
              n2.get("status") if n2 else None)
        stop_all()
        CHILDREN.clear()

    finally:
        stop_all()
        smtp.stop()
        sms.stop()

    print(f"\n== notify transport (SMTP + SMS) e2e: {PASS} passed, {FAIL} failed ==")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
