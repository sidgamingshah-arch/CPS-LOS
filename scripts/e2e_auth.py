#!/usr/bin/env python3
"""
Authentication layer — e2e.

Proves the gap "X-Actor is client-asserted" is closed: a login mints a verified
bearer token, and the gateway injects the verified subject as X-Actor while
stripping any client-supplied one. Adapts to the stack's enforcement mode
(/auth/mode) — runs the spoof/tamper assertions in either mode, and the
token-less-write rejection only when ENFORCED.

  1. login mints a token; bad credentials are rejected uniformly.
  2. /me echoes the verified claims + live role resolution.
  3. SPOOF: an authenticated call carrying a conflicting X-Actor is overridden by
     the token subject (the gateway strips the client header).
  4. A tampered / expired token is rejected with 401.
  5. PERMISSIVE: a token-less call passes through with the client's X-Actor.
     ENFORCED: a token-less write is rejected with 401; with a token it succeeds.
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
DEMO_PASSWORD = "Helix@2026"


def call(method, path, body=None, actor=None, token=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor is not None:
        req.add_header("X-Actor", actor)
    if token is not None:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
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
        PASS += 1; print(f"  PASS  {name}")
    else:
        FAIL += 1; print(f"  FAIL  {name}  {detail}")


def must(st, b, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {b}"); sys.exit(1)
    return b


st, mode = call("GET", "/auth/mode")
mode = must(st, mode, "mode")
enforced = mode.get("enforced", False)
print(f"== Auth e2e (stack mode: {mode.get('mode')}) ==")

print("\n== 1. Login mints a token; bad credentials rejected ==")
st, body = call("POST", "/config/api/auth/login",
                {"username": "treasury.ops", "password": "wrong-password"})
check("bad password rejected (401)", st == 401, f"{st} {body}")
st, body = call("POST", "/config/api/auth/login",
                {"username": "no.such.user", "password": DEMO_PASSWORD})
check("unknown user rejected (401)", st == 401, f"{st} {body}")
st, login = call("POST", "/config/api/auth/login",
                 {"username": "treasury.ops", "password": DEMO_PASSWORD})
login = must(st, login, "login treasury.ops")
token = login["token"]
check("login returns a token", bool(token) and "." in token, str(login)[:120])
check("login echoes the actor", login["actor"] == "treasury.ops", str(login.get("actor")))
check("login resolves roles from ACTOR_ROLE", "TREASURY_OPS" in login.get("roles", []), str(login.get("roles")))

print("\n== 2. /me echoes verified claims ==")
st, me = call("GET", "/config/api/auth/me", token=token)
me = must(st, me, "me")
check("me returns the verified actor", me["actor"] == "treasury.ops", str(me))
check("me resolves roles", "TREASURY_OPS" in me.get("roles", []), str(me.get("roles")))
st, body = call("GET", "/config/api/auth/me")
check("me without a token rejected (401)", st == 401, f"{st} {body}")

print("\n== 3. SPOOF: token subject overrides a conflicting client X-Actor ==")
st, who = call("GET", "/config/api/auth/whoami", token=token, actor="cro")
who = must(st, who, "whoami spoof")
check("gateway injected the TOKEN subject, not the client X-Actor",
      who["actor"] == "treasury.ops", f"got {who.get('actor')} (sent X-Actor: cro)")

print("\n== 4. Tampered / expired tokens are rejected ==")
tampered = token[:-4] + ("AAAA" if not token.endswith("AAAA") else "BBBB")
st, body = call("GET", "/config/api/auth/whoami", token=tampered, actor="cro")
check("tampered token rejected (401)", st == 401, f"{st} {body}")
st, shortlived = call("POST", "/config/api/auth/login",
                      {"username": "loan.ops", "password": DEMO_PASSWORD, "ttlSeconds": 1})
shortlived = must(st, shortlived, "short-lived login")
time.sleep(2)
st, body = call("GET", "/config/api/auth/whoami", token=shortlived["token"])
check("expired token rejected (401)", st == 401, f"{st} {body}")

print("\n== 5. Mode-specific enforcement ==")
# A harmless write used to probe enforcement: the RBAC cache invalidate (idempotent).
if enforced:
    st, body = call("POST", "/decision/api/governance/rbac/cache/invalidate")
    check("ENFORCED: token-less write rejected (401)", st == 401, f"{st} {body}")
    st, body = call("POST", "/decision/api/governance/rbac/cache/invalidate", token=token)
    check("ENFORCED: same write with a token succeeds", st == 200, f"{st} {body}")
    # A token-less GET (read) still flows in enforced mode.
    st, _ = call("GET", "/auth/mode")
    check("ENFORCED: token-less GET still allowed", st == 200, f"{st}")
else:
    st, who = call("GET", "/config/api/auth/whoami", actor="rm.user")
    check("PERMISSIVE: token-less call passes with client X-Actor",
          st == 200 and who["actor"] == "rm.user", f"{st} {who}")
    st, body = call("POST", "/decision/api/governance/rbac/cache/invalidate")
    check("PERMISSIVE: token-less write still allowed", st == 200, f"{st} {body}")

print(f"\n== Auth e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
