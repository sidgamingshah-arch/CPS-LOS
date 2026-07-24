#!/usr/bin/env python3
"""
Customer / vendor self-service PORTAL — e2e (CLoM gap #23; the R1-02 / R1-14 / R3-08
"response by customer" rows).

helix-common gained a token-scoped portal auto-exposed at /api/portal on every service (exactly
like /api/audit, /api/queries, /api/documents). An external party — who never authenticates —
presents the one-time RFI response token minted for their EXTERNAL_CUSTOMER / EXTERNAL_VENDOR
query and can then, scoped STRICTLY to that one thread:
  - GET  /api/portal/{token}            → view the single RFI (topic/question/timeline/deadline)
  - POST /api/portal/{token}/respond    → append a response  (thread -> RESPONDED)
  - POST /api/portal/{token}/documents  → upload a document into the governed DMS store

This suite is SELF-CONTAINED: it boots ONE service that includes helix-common (counterparty-service)
on an ALT port with an isolated data dir, and stops it in a finally block. It never binds 8080-8089.

Proves (with the security assertions explicit):
  1. raise EXTERNAL_CUSTOMER -> a one-time token; GET /api/portal/{token} returns THIS thread only;
  2. respond -> RESPONDED, recorded as an EXTERNAL actor (never an internal HUMAN);
  3. document upload -> stored (storedDocId + sha), tagged to the thread, byte-identical round-trip;
  4. SECURITY: a token for thread A cannot read/respond to thread B (no IDOR); invalid / withdrawn /
     resolved token -> denied with NO thread data leaked in the body;
  5. the internal /api/queries lane and the legacy single-use external-response path are UNCHANGED.
"""
import base64
import hashlib
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORT = int(os.environ.get("HELIX_PORTAL_PORT", "8092"))
BASE = f"http://127.0.0.1:{PORT}"
PASS, FAIL = 0, 0
NONCE = str(int(time.time()))


def call(method, path, body=None, actor="portal.tester", base=BASE, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor is not None:
        req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
            return r.status, (txt if raw else (json.loads(txt) if txt else None))
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        if raw:
            return e.code, txt
        try:
            return e.code, json.loads(txt) if txt else None
        except Exception:
            return e.code, txt


def upload_multipart(path, filename, content_type, raw_bytes, actor="portal.tester", base=BASE):
    boundary = "----helixPortalBoundary7MA4YWxkTrZu0gW"
    parts = [(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
              f"Content-Type: {content_type}\r\n\r\n").encode() + raw_bytes + b"\r\n",
             f"--{boundary}--\r\n".encode()]
    body = b"".join(parts)
    req = urllib.request.Request(base + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    if actor is not None:
        req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        return e.code, (json.loads(txt) if txt else txt)


def download(path, base=BASE):
    req = urllib.request.Request(base + path, method="GET")
    req.add_header("X-Actor", "portal.tester")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


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


def raise_external(channel, topic, question, subject_ref, actor="rm.alpha." + NONCE):
    body = {"channel": channel, "subjectType": "Application", "subjectRef": subject_ref,
            "topic": topic, "question": question, "addresseeRole": "customer.contact",
            "recipientRoles": ["customer.contact"], "slaHours": 72}
    st, v = call("POST", "/api/queries", body, actor=actor)
    v = must(st, v, f"raise {channel}")
    return v["thread"]["queryRef"], v["thread"].get("responseToken")


# ============================================================ boot the isolated service
jar = ROOT / "counterparty-service" / "target" / "counterparty-service.jar"
if not jar.exists():
    print(f"  ERROR counterparty-service jar not present at {jar} — build the reactor first")
    sys.exit(1)

data_dir = ROOT / "data" / f"portal-e2e-{PORT}"
data_dir.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, SERVER_PORT=str(PORT), HELIX_DATA_DIR=str(data_dir),
           # point config-service at a dead port so cross-service reads fail-soft fast (never used)
           CONFIG_SERVICE_URL="http://127.0.0.1:1", WORKFLOW_SERVICE_URL="http://127.0.0.1:1")
proc = subprocess.Popen(["java", "-jar", str(jar)], env=env,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

try:
    up = False
    for _ in range(90):
        try:
            with urllib.request.urlopen(f"{BASE}/actuator/health", timeout=2) as r:
                if r.status == 200:
                    up = True
                    break
        except Exception:
            time.sleep(1)
    check("isolated helix-common service (counterparty) came up on the alt port", up, "health never green")
    if not up:
        raise SystemExit("service did not start")

    # ============================================================ 1. raise + tokened VIEW
    print("== 1. raise EXTERNAL_CUSTOMER -> one-time token; GET /api/portal/{token} returns THIS thread only")
    topic_a = "FY24 audited financials"
    q_a = "Please provide the FY24 audited financial statements for the borrower."
    ref_a, token_a = raise_external("EXTERNAL_CUSTOMER", topic_a, q_a, f"APP-A-{NONCE}")
    check("raise surfaced a one-time portal token", bool(token_a) and len(token_a) >= 20, str(token_a))

    st, ctx = call("GET", f"/api/portal/{token_a}", actor=None)
    ctx = must(st, ctx, "portal view A")
    check("portal context is THIS thread (reference matches)", ctx.get("reference") == ref_a, str(ctx.get("reference")))
    check("portal context carries the topic", ctx.get("topic") == topic_a, str(ctx.get("topic")))
    check("portal context carries the question", ctx.get("question") == q_a, str(ctx.get("question")))
    check("portal context carries the deadline", ctx.get("deadline") is not None, str(ctx.get("deadline")))
    check("portal context carries the message timeline (the question)",
          isinstance(ctx.get("messages"), list) and len(ctx["messages"]) >= 1, str(ctx.get("messages")))
    check("portal advertises the allowed actions (VIEW/RESPOND/UPLOAD_DOCUMENT)",
          set(ctx.get("allowedActions") or []) == {"VIEW", "RESPOND", "UPLOAD_DOCUMENT"},
          str(ctx.get("allowedActions")))
    check("channel is EXTERNAL_CUSTOMER", ctx.get("channel") == "EXTERNAL_CUSTOMER", str(ctx.get("channel")))
    # No internal-username leak: the raiser id must NOT appear anywhere in the external context.
    check("no internal RM username leaked in the context (from-labels redacted to BANK/YOU)",
          ("rm.alpha." + NONCE) not in json.dumps(ctx),
          "raiser id present in context")
    check("timeline from-labels are party-redacted (BANK/YOU only)",
          all(m.get("from") in ("BANK", "YOU") for m in ctx.get("messages", [])),
          str([m.get("from") for m in ctx.get("messages", [])]))

    print("== 1b. GET is a SAFE idempotent read — does not consume the token")
    st2, ctx2 = call("GET", f"/api/portal/{token_a}", actor=None)
    ctx2 = must(st2, ctx2, "portal view A (repeat)")
    check("repeat GET still returns the thread (token not consumed by a read)",
          ctx2.get("reference") == ref_a, str(ctx2.get("reference")))

    # ============================================================ 2. respond
    print("== 2. POST respond -> RESPONDED, recorded as an EXTERNAL actor (never internal HUMAN)")
    st, r = call("POST", f"/api/portal/{token_a}/respond",
                 {"message": "Attached below; audited by our statutory auditors."}, actor=None)
    r = must(st, r, "portal respond A")
    check("respond flips the thread to RESPONDED", r.get("status") == "RESPONDED", str(r.get("status")))
    check("respond appended the external message to the timeline (now >= 2)",
          len(r.get("messages", [])) >= 2, str(len(r.get("messages", []))))
    check("the external message shows as YOU (external party), not BANK",
          any(m.get("from") == "YOU" for m in r.get("messages", [])), str(r.get("messages")))
    # Underlying query message is authorType EXTERNAL, inbound=true (checked via /api/queries).
    st, qv = call("GET", f"/api/queries/{ref_a}")
    qv = must(st, qv, "query view A")
    inbound = [m for m in qv["messages"] if m.get("inbound") is True]
    check("underlying query message is authorType EXTERNAL (never HUMAN)",
          inbound and all(m.get("authorType") == "EXTERNAL" for m in inbound), str(qv["messages"]))
    # Audit: PORTAL_RESPONSE_RECEIVED stamped actorType EXTERNAL on the QueryThread subject.
    st, aud = call("GET", f"/api/audit/subject?type=QueryThread&id={ref_a}")
    aud = must(st, aud, "audit A")
    resp_ev = [e for e in aud if e.get("eventType") == "PORTAL_RESPONSE_RECEIVED"]
    check("PORTAL_RESPONSE_RECEIVED audit stamped actorType EXTERNAL (not HUMAN)",
          resp_ev and resp_ev[0].get("actorType") == "EXTERNAL",
          str([(e.get("eventType"), e.get("actorType")) for e in aud]))

    # ============================================================ 3. document upload
    print("== 3. POST document upload -> stored (storedDocId + sha), tagged to the thread")
    raw = bytes(range(256)) * 4 + b"portal upload \x00\x01 evidence"
    sha_local = hashlib.sha256(raw).hexdigest()
    st, up = upload_multipart(f"/api/portal/{token_a}/documents", "financials-fy24.pdf",
                              "application/pdf", raw, actor=None)
    up = must(st, up, "portal upload A")
    check("upload returns a stored-document id", up.get("storedDocId") is not None, str(up))
    check("upload server-sha256 matches the local digest", up.get("sha256") == sha_local, str(up.get("sha256")))
    check("upload size matches the uploaded bytes", up.get("sizeBytes") == len(raw), str(up.get("sizeBytes")))
    check("upload keeps the thread RESPONDED", up.get("status") == "RESPONDED", str(up.get("status")))
    doc_id = up.get("storedDocId")
    # Tagged to the thread: listable in the DMS by subjectRef = the query ref, subjectType QueryThread.
    st, docs = call("GET", f"/api/documents?subjectRef={ref_a}&subjectType=QueryThread")
    docs = must(st, docs, "dms list A")
    check("stored document is tagged to the thread (subjectRef=queryRef)",
          any(d.get("id") == doc_id for d in docs), str([d.get("subjectRef") for d in docs]))
    # Byte-identical round-trip through the governed DMS.
    st, got = download(f"/api/documents/{doc_id}")
    check("uploaded bytes round-trip byte-identical through the DMS", st == 200 and got == raw,
          f"HTTP {st} len {len(got) if isinstance(got, bytes) else got}")
    # Audit: PORTAL_DOCUMENT_UPLOADED stamped EXTERNAL.
    st, aud = call("GET", f"/api/audit/subject?type=QueryThread&id={ref_a}")
    up_ev = [e for e in (aud or []) if e.get("eventType") == "PORTAL_DOCUMENT_UPLOADED"]
    check("PORTAL_DOCUMENT_UPLOADED audit stamped actorType EXTERNAL",
          up_ev and up_ev[0].get("actorType") == "EXTERNAL",
          str([(e.get("eventType"), e.get("actorType")) for e in (aud or [])]))

    print("== 3b. base64-JSON upload path also works and is thread-scoped")
    raw2 = b"\x89PNG\r\n portal json upload \xff\xd8"
    st, up2 = call("POST", f"/api/portal/{token_a}/documents",
                   {"filename": "board-resolution.png", "contentType": "image/png",
                    "contentBase64": base64.b64encode(raw2).decode()}, actor=None)
    up2 = must(st, up2, "portal upload A (json)")
    check("base64-JSON upload stored with matching sha",
          up2.get("sha256") == hashlib.sha256(raw2).hexdigest() and up2.get("storedDocId") is not None, str(up2))

    # ============================================================ 4. SECURITY
    print("== 4. SECURITY: one token -> exactly one thread (no IDOR); denials leak no data")
    topic_b = "Insurance certificate"
    q_b = "Please send the current property insurance certificate."
    ref_b, token_b = raise_external("EXTERNAL_VENDOR", topic_b, q_b, f"APP-B-{NONCE}")
    check("second thread B raised with its own distinct token",
          bool(token_b) and token_b != token_a and ref_b != ref_a, str(token_b))

    # 4a: token A only ever sees thread A — thread B's ref/topic never appear.
    st, ca = call("GET", f"/api/portal/{token_a}", actor=None)
    ca = must(st, ca, "view A for IDOR check")
    ca_txt = json.dumps(ca)
    check("token A view contains ONLY thread A (no thread-B ref/topic — no IDOR)",
          ca.get("reference") == ref_a and ref_b not in ca_txt and topic_b not in ca_txt, "cross-thread leak via token A")
    # 4b: token B only ever sees thread B.
    st, cb = call("GET", f"/api/portal/{token_b}", actor=None)
    cb = must(st, cb, "view B for IDOR check")
    cb_txt = json.dumps(cb)
    check("token B view contains ONLY thread B (no thread-A ref/topic/question — no IDOR)",
          cb.get("reference") == ref_b and ref_a not in cb_txt and topic_a not in cb_txt and q_a not in cb_txt,
          "cross-thread leak via token B")
    # 4c: responding with token A cannot touch thread B — B stays OPEN.
    st, _ = call("POST", f"/api/portal/{token_a}/respond", {"message": "another A note"}, actor=None)
    st, qb = call("GET", f"/api/queries/{ref_b}")
    qb = must(st, qb, "query B after A respond")
    check("responding with token A did NOT mutate thread B (B still OPEN)",
          qb["thread"]["status"] == "OPEN", str(qb["thread"]["status"]))

    # 4d: an INVALID / garbage token -> denied, no thread data in the body.
    st, err = call("GET", "/api/portal/not-a-real-token-zzzzzzzzzzzzzzzzzzzzzzzz", actor=None, raw=True)
    check("invalid token GET is denied (403/404)", st in (403, 404), f"HTTP {st}")
    check("invalid-token denial body leaks NO thread data (no topic/question/ref)",
          topic_a not in err and q_a not in err and ref_a not in err and topic_b not in err and ref_b not in err,
          "thread data present in error body")
    st, err = call("POST", "/api/portal/not-a-real-token-zzzzzzzzzzzzzzzzzzzzzzzz/respond",
                   {"message": "hi"}, actor=None, raw=True)
    check("invalid token respond is denied (403/404)", st in (403, 404), f"HTTP {st}")

    # 4e: a WITHDRAWN (cancelled) thread's token -> denied, no leak.
    topic_c = "Withdrawn RFI"
    ref_c, token_c = raise_external("EXTERNAL_CUSTOMER", topic_c, "cancel me", f"APP-C-{NONCE}")
    st, _ = call("POST", f"/api/queries/{ref_c}/cancel", {"reason": "duplicate"}, actor="rm.alpha." + NONCE)
    st, err = call("GET", f"/api/portal/{token_c}", actor=None, raw=True)
    check("withdrawn (CANCELLED) thread token -> denied (403)", st == 403, f"HTTP {st} {err}")
    check("withdrawn-token denial leaks no thread data", topic_c not in err, "topic leaked on withdrawn thread")

    # 4f: a RESOLVED (closed) thread's token -> denied.
    topic_d = "Resolved RFI"
    ref_d, token_d = raise_external("EXTERNAL_CUSTOMER", topic_d, "resolve me", f"APP-D-{NONCE}")
    st, _ = call("POST", f"/api/portal/{token_d}/respond", {"message": "here you go"}, actor=None)
    st, _ = call("POST", f"/api/queries/{ref_d}/resolve", {"resolution": "received"}, actor="rm.alpha." + NONCE)
    st, err = call("GET", f"/api/portal/{token_d}", actor=None, raw=True)
    check("resolved (closed) thread token -> denied (403)", st == 403, f"HTTP {st} {err}")

    # ============================================================ 5. regression: legacy paths unchanged
    print("== 5. regression: internal /api/queries + the legacy single-use external-response are unchanged")
    ib = {"channel": "INTERNAL", "subjectType": "Application", "subjectRef": f"APP-INT-{NONCE}",
          "topic": "Internal note", "question": "internal only", "addressee": "analyst." + NONCE}
    st, iv = call("POST", "/api/queries", ib, actor="rm.alpha." + NONCE)
    iv = must(st, iv, "raise internal")
    int_ref = iv["thread"]["queryRef"]
    check("internal query still starts OPEN", iv["thread"]["status"] == "OPEN", str(iv["thread"]["status"]))
    check("internal query has NO portal token (external-only)", iv["thread"].get("responseToken") is None,
          str(iv["thread"].get("responseToken")))
    st, rep = call("POST", f"/api/queries/{int_ref}/reply", {"body": "on it"}, actor="analyst." + NONCE)
    check("internal reply still flips to RESPONDED", rep and rep["thread"]["status"] == "RESPONDED",
          str(rep["thread"]["status"] if rep else None))
    # An internal thread has no token, so the portal can never address it.
    st, err = call("GET", f"/api/portal/{int_ref}", actor=None, raw=True)
    check("portal cannot address an internal thread by its ref (denied, no leak)",
          st in (403, 404) and "internal only" not in err, f"HTTP {st}")

    # Legacy single-use external-response path: raise E, spend its token via /api/queries, replay -> 403.
    ref_e, token_e = raise_external("EXTERNAL_CUSTOMER", "Legacy path", "legacy respond", f"APP-E-{NONCE}")
    st, ext = call("POST", f"/api/queries/{ref_e}/external-response?token={token_e}",
                   {"body": "legacy reply", "from": "acme"}, actor="portal.callback")
    ext = must(st, ext, "legacy external-response")
    check("legacy external-response still accepts the token -> RESPONDED", ext["thread"]["status"] == "RESPONDED",
          str(ext["thread"]["status"]))
    st, b = call("POST", f"/api/queries/{ref_e}/external-response?token={token_e}",
                 {"body": "replay", "from": "acme"}, actor="portal.callback")
    check("legacy external-response is still single-use (replay -> 403)", st == 403, f"HTTP {st} {b}")
    # And a token spent on the legacy path is no longer usable on the portal (hash cleared) -> denied.
    st, err = call("GET", f"/api/portal/{token_e}", actor=None, raw=True)
    check("a token spent on the legacy single-use path is denied by the portal too", st in (403, 404), f"HTTP {st}")

except SystemExit:
    raise
finally:
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=20)
    except Exception:
        proc.kill()

print(f"\ncustomer-portal e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
