#!/usr/bin/env python3
"""
Generic master-engine jurisdiction-versioning — e2e.

Proves the fix for overrides vs defaults colliding on recordKey:
  1. A null-jurisdiction DEFAULT record can be created + approved → ACTIVE.
  2. A jurisdiction-specific OVERRIDE for the SAME recordKey can be created +
     approved → ACTIVE, and the default STAYS ACTIVE (no collision: the two are
     independent version lines, not the same record).
  3. Jurisdiction-aware resolution: GET ...?jurisdiction=<jur> returns the
     OVERRIDE; GET ...?jurisdiction=<other> falls back to the DEFAULT; GET with
     no jurisdiction returns the DEFAULT.
  4. Re-submitting + approving a NEW version of the default supersedes only the
     prior default (the override is untouched), and vice-versa.
  5. Maker-checker SoD still applies (maker != checker).
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
TYPE = "E2E_JUR_TEST"
KEY = "param_alpha"


def call(method, path, body=None, actor="master.maker"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
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


def submit(jur, payload, maker="master.maker"):
    body = {"recordKey": KEY, "payload": payload}
    if jur is not None:
        body["jurisdiction"] = jur
    st, rec = call("POST", f"/config/api/masters/{TYPE}", body, actor=maker)
    return must(st, rec, "submit")


def approve(rec_id, checker="master.checker"):
    st, rec = call("POST", f"/config/api/masters/records/{rec_id}/approve", actor=checker)
    return st, rec


print("== 1. Default (null-jurisdiction) record: submit → approve ==")
d1 = submit(None, {"weight": 10, "scope": "DEFAULT"})
check("default submitted PENDING v1", d1["status"] == "PENDING_APPROVAL" and d1["version"] == 1, d1)
st, d1a = approve(d1["id"])
d1a = must(st, d1a, "approve default")
check("default ACTIVE", d1a["status"] == "ACTIVE")

print("== 2. Jurisdiction override for the SAME key: submit → approve ==")
o1 = submit("AE-CBUAE", {"weight": 25, "scope": "OVERRIDE"})
check("override submitted PENDING v1 (own version line)", o1["status"] == "PENDING_APPROVAL" and o1["version"] == 1, o1)
st, o1a = approve(o1["id"])
o1a = must(st, o1a, "approve override")
check("override ACTIVE", o1a["status"] == "ACTIVE")

print("== 3. The default must STILL be ACTIVE (no collision) ==")
st, hist = call("GET", f"/config/api/masters/{TYPE}/{KEY}/history")
hist = must(st, hist, "history")
active = [r for r in hist if r["status"] == "ACTIVE"]
default_active = [r for r in active if not r.get("jurisdiction")]
override_active = [r for r in active if r.get("jurisdiction") == "AE-CBUAE"]
check("default + override BOTH active", len(default_active) == 1 and len(override_active) == 1,
      f"active={[{'jur': r.get('jurisdiction'), 'v': r['version']} for r in active]}")

print("== 4. Jurisdiction-aware resolution ==")
st, res_ae = call("GET", f"/config/api/masters/{TYPE}/{KEY}?jurisdiction=AE-CBUAE")
res_ae = must(st, res_ae, "resolve AE")
check("AE resolves to OVERRIDE", res_ae["payload"]["scope"] == "OVERRIDE", res_ae["payload"])
st, res_in = call("GET", f"/config/api/masters/{TYPE}/{KEY}?jurisdiction=IN-RBI")
res_in = must(st, res_in, "resolve IN")
check("unrelated jur falls back to DEFAULT", res_in["payload"]["scope"] == "DEFAULT", res_in["payload"])
st, res_def = call("GET", f"/config/api/masters/{TYPE}/{KEY}")
res_def = must(st, res_def, "resolve default")
check("no-jurisdiction resolves to DEFAULT", res_def["payload"]["scope"] == "DEFAULT", res_def["payload"])

print("== 5. Versioning the default supersedes only the default (override untouched) ==")
d2 = submit(None, {"weight": 11, "scope": "DEFAULT"})
check("default v2 submitted", d2["version"] == 2, d2)
st, d2a = approve(d2["id"])
d2a = must(st, d2a, "approve default v2")
st, hist2 = call("GET", f"/config/api/masters/{TYPE}/{KEY}/history")
hist2 = must(st, hist2, "history2")
active2 = [r for r in hist2 if r["status"] == "ACTIVE"]
default_active2 = [r for r in active2 if not r.get("jurisdiction")]
override_active2 = [r for r in active2 if r.get("jurisdiction") == "AE-CBUAE"]
check("only one active default after versioning", len(default_active2) == 1 and default_active2[0]["version"] == 2,
      [{'jur': r.get('jurisdiction'), 'v': r['version']} for r in active2])
check("override STILL active v1 (not superseded by default versioning)",
      len(override_active2) == 1 and override_active2[0]["version"] == 1,
      [{'jur': r.get('jurisdiction'), 'v': r['version']} for r in active2])

print("== 6. Versioning the override supersedes only the override (default untouched) ==")
o2 = submit("AE-CBUAE", {"weight": 26, "scope": "OVERRIDE"})
check("override v2 submitted (own line)", o2["version"] == 2, o2)
st, o2a = approve(o2["id"])
o2a = must(st, o2a, "approve override v2")
st, res_ae2 = call("GET", f"/config/api/masters/{TYPE}/{KEY}?jurisdiction=AE-CBUAE")
res_ae2 = must(st, res_ae2, "resolve AE v2")
check("AE resolves to override v2", res_ae2["version"] == 2 and res_ae2["payload"]["weight"] == 26, res_ae2["payload"])
st, res_def2 = call("GET", f"/config/api/masters/{TYPE}/{KEY}")
res_def2 = must(st, res_def2, "resolve default after override v2")
check("default still v2 (untouched by override versioning)", res_def2["version"] == 2, res_def2)

print("== 7. Maker-checker SoD still enforced ==")
d3 = submit(None, {"weight": 12, "scope": "DEFAULT"}, maker="alice")
st, sod = approve(d3["id"], checker="alice")
check("self-approval blocked (403)", st == 403, f"HTTP {st}")
st, ok = approve(d3["id"], checker="bob")
check("different checker approves (200)", st == 200, f"HTTP {st}")

print(f"\n== masters jurisdiction e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
