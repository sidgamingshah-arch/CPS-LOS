#!/usr/bin/env python3
"""
Real SSO authentication (OIDC JWT resource server) — e2e.

Proves the profile-gated `helix.security.mode` layer end-to-end, fully self-contained: the
suite spins up its OWN decision-service instances on ephemeral ports (no shared stack, no
external IdP) and mints a LOCALLY-signed HS256 JWT against a symmetric secret the resource
server is pointed at.

  Part A — DEFAULT `none` mode preserves today's behaviour EXACTLY:
     * /api/security/mode reports mode=none.
     * A token-less read AND a token-less write both succeed (no 401) — the X-Actor header
       remains a claimable identity.
     * /api/security/whoami echoes the client-supplied X-Actor verbatim (no override).

  Part B — `oidc` mode is a real OAuth2 resource server:
     * No token -> 401 on a protected endpoint.
     * A valid token -> 200; the actor is derived from the configured username claim and the
       roles are derived from the configured roles claim (claims drive the identity + the role
       model consumed by SoD/ActorDirectory).
     * Token identity WINS: a spoofed X-Actor header is overridden by the token subject.
     * A tampered / expired token -> 401.
     * The unauthenticated bootstrap endpoint /api/security/mode stays open (200).

Pure stdlib only (urllib, hmac, hashlib, base64, subprocess, socket).
"""
import base64
import hashlib
import hmac
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "decision-service" / "target" / "decision-service.jar"
# >= 256 bits, required by the HS256 MAC verifier.
JWT_SECRET = "helix-oidc-e2e-symmetric-signing-secret-0123456789"
PASS, FAIL = 0, 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1; print(f"  PASS  {name}")
    else:
        FAIL += 1; print(f"  FAIL  {name}  {detail}")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def mint_jwt(claims: dict, secret: str) -> str:
    """Mint an HS256 JWT locally (no external library)."""
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode()
    sig = b64url(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


def call(base, method, path, body=None, actor=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor is not None:
        req.add_header("X-Actor", actor)
    if token is not None:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, (json.loads(txt) if txt else None)
        except Exception:
            return e.code, txt


def start_service(port, extra_env):
    env = dict(os.environ)
    env["SERVER_PORT"] = str(port)
    # Isolated DB dir so we never touch the shared ./data.
    data_dir = ROOT / "data" / f"sso-e2e-{port}"
    data_dir.mkdir(parents=True, exist_ok=True)
    env["HELIX_DATA_DIR"] = str(data_dir)
    # Point config-service at an unused port; ActorDirectory cold-starts fail-open (irrelevant here).
    env["CONFIG_SERVICE_URL"] = "http://localhost:1"
    env.update(extra_env)
    log = open(ROOT / "data" / f"sso-e2e-{port}.log", "w")
    proc = subprocess.Popen(["java", "-jar", str(JAR)], env=env, stdout=log, stderr=subprocess.STDOUT)
    return proc


def wait_health(base, proc, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"service exited early (code {proc.returncode})")
        try:
            with urllib.request.urlopen(base + "/actuator/health", timeout=3) as r:
                if r.status == 200 and b'"UP"' in r.read():
                    return True
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("service did not become healthy in time")


def main():
    if not JAR.exists():
        print(f"  ERROR decision-service jar not found at {JAR}; build first "
              f"(mvn -pl decision-service -am package -DskipTests)")
        sys.exit(1)

    procs = []
    try:
        # ---- Part A: DEFAULT none mode ------------------------------------------------------
        print("== Part A: DEFAULT helix.security.mode=none preserves today's behaviour ==")
        port_a = free_port()
        base_a = f"http://localhost:{port_a}"
        pa = start_service(port_a, {"HELIX_SECURITY_MODE": "none"})
        procs.append(pa)
        wait_health(base_a, pa)

        st, mode = call(base_a, "GET", "/api/security/mode")
        check("none: /api/security/mode reports none (unauthenticated)",
              st == 200 and isinstance(mode, dict) and mode.get("mode") == "none", f"{st} {mode}")
        check("none: mode.secured is false", isinstance(mode, dict) and mode.get("secured") is False, str(mode))

        st, who = call(base_a, "GET", "/api/security/whoami", actor="rm.user")
        check("none: token-less read succeeds (no 401)", st == 200, f"{st} {who}")
        check("none: X-Actor passes through verbatim (no override)",
              isinstance(who, dict) and who.get("actor") == "rm.user", str(who))
        check("none: request is anonymous (not authenticated)",
              isinstance(who, dict) and who.get("authenticated") is False, str(who))

        # A token-less WRITE must also still succeed (no CSRF / no 401) — this is the sharpest
        # regression check that the permit-all chain reproduces the pre-security behaviour.
        st, _ = call(base_a, "POST", "/api/governance/rbac/cache/invalidate", actor="rm.user")
        check("none: token-less write succeeds (no 401 / no CSRF block)", st == 200, f"{st}")

        # ---- Part B: oidc mode --------------------------------------------------------------
        print("\n== Part B: helix.security.mode=oidc is a real JWT resource server ==")
        port_b = free_port()
        base_b = f"http://localhost:{port_b}"
        pb = start_service(port_b, {
            "HELIX_SECURITY_MODE": "oidc",
            "HELIX_SECURITY_JWT_SECRET": JWT_SECRET,
            "HELIX_SECURITY_PRINCIPAL_CLAIM": "preferred_username",
            "HELIX_SECURITY_ROLES_CLAIM": "roles",
        })
        procs.append(pb)
        wait_health(base_b, pb)

        # The bootstrap endpoint stays open even under oidc.
        st, mode = call(base_b, "GET", "/api/security/mode")
        check("oidc: /api/security/mode open + reports oidc", st == 200 and mode.get("mode") == "oidc", f"{st} {mode}")
        check("oidc: mode.secured is true", isinstance(mode, dict) and mode.get("secured") is True, str(mode))

        # No token -> 401 on a protected endpoint.
        st, body = call(base_b, "GET", "/api/security/whoami")
        check("oidc: no token -> 401", st == 401, f"{st} {body}")

        # Also 401 for a token-less write.
        st, body = call(base_b, "POST", "/api/governance/rbac/cache/invalidate")
        check("oidc: token-less write -> 401", st == 401, f"{st} {body}")

        # A valid token -> 200; actor + roles derived from claims.
        now = int(time.time())
        token = mint_jwt({
            "sub": "uid-alice-9c3",
            "preferred_username": "alice.oidc",
            "roles": ["CREDIT_OPS", "TREASURY_OPS"],
            "iat": now, "exp": now + 3600,
        }, JWT_SECRET)
        st, who = call(base_b, "GET", "/api/security/whoami", token=token)
        check("oidc: valid token -> 200", st == 200, f"{st} {who}")
        check("oidc: actor derived from the username claim (preferred_username, not sub)",
              isinstance(who, dict) and who.get("actor") == "alice.oidc", str(who))
        roles = set(who.get("roles", [])) if isinstance(who, dict) else set()
        check("oidc: roles derived from the roles claim (drive the SoD/ActorDirectory model)",
              {"CREDIT_OPS", "TREASURY_OPS"}.issubset(roles), str(who))
        check("oidc: request is authenticated", isinstance(who, dict) and who.get("authenticated") is True, str(who))

        # Token identity WINS over a spoofed X-Actor header.
        st, who = call(base_b, "GET", "/api/security/whoami", token=token, actor="cro.super.spoof")
        check("oidc: token subject overrides a spoofed X-Actor header",
              isinstance(who, dict) and who.get("actor") == "alice.oidc",
              f"got {who.get('actor') if isinstance(who, dict) else who} (sent X-Actor: cro.super.spoof)")

        # Tampered token -> 401.
        tampered = token[:-4] + ("AAAA" if not token.endswith("AAAA") else "BBBB")
        st, body = call(base_b, "GET", "/api/security/whoami", token=tampered)
        check("oidc: tampered token -> 401", st == 401, f"{st} {body}")

        # Expired token -> 401.
        expired = mint_jwt({
            "sub": "uid-bob", "preferred_username": "bob.oidc",
            "roles": ["CREDIT_OPS"], "iat": now - 7200, "exp": now - 3600,
        }, JWT_SECRET)
        st, body = call(base_b, "GET", "/api/security/whoami", token=expired)
        check("oidc: expired token -> 401", st == 401, f"{st} {body}")

        # A token signed with the WRONG secret -> 401 (signature validation is real).
        forged = mint_jwt({
            "sub": "uid-mallory", "preferred_username": "mallory",
            "roles": ["CREDIT_COMMITTEE"], "iat": now, "exp": now + 3600,
        }, "a-different-wrong-secret-that-is-also-32-bytes-long")
        st, body = call(base_b, "GET", "/api/security/whoami", token=forged)
        check("oidc: token signed with the wrong key -> 401", st == 401, f"{st} {body}")

    finally:
        for p in procs:
            try:
                p.terminate()
                p.wait(timeout=15)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass

    print(f"\n== SSO auth e2e: {PASS} passed, {FAIL} failed ==")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
