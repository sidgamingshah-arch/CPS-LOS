#!/usr/bin/env python3
"""
Config-driven dynamic screen behaviour (FIELD_POLICY) — e2e.

Proves:
 0. The FIELD_POLICY master is seeded (ORIGINATION_APPLICATION form) in config-service.
 1. GET /origination/api/field-policy/ORIGINATION_APPLICATION returns the seeded field specs,
    including the label overrides (requestedAmount -> "Facility amount"; collateralValue ->
    "Collateral value (INR)" + help) and the conditional-required rule
    (collateralValue requiredWhen collateralType PRESENT).
 2. Creating an application WITH collateralType but WITHOUT collateralValue -> 400. The
    conditional-required rule is enforced SERVER-SIDE (FieldPolicyService.enforce) and cannot
    be bypassed by the client hiding the field.
 3. Creating an UNSECURED application (no collateralType, no collateralValue) -> 200:
    collateralValue is not required when collateralType is blank (visibleWhen/requiredWhen absent-of-target).
 4. Creating a SECURED application with BOTH -> 200.
 4b. Regression guard: collateralType present with an explicit collateralValue = 0 -> 200
    (an explicit 0 is "present", not blank — this is exactly what e2e_smoke sends as
    collateralType="NONE", collateralValue=0, so this rule never rejects a current payload).
 5. GET /origination/api/field-policy/UNKNOWN_FORM -> empty fields (fail-open; no enforcement
    for a form with no policy).

Runs against the gateway (:8080) by default. For the self-contained build check, point it at the
services directly with HELIX_ORIG_BASE / HELIX_CONFIG_BASE (no gateway prefix).
"""
import json
import os
import random
import string
import sys
import urllib.error
import urllib.request

ORIG = os.environ.get("HELIX_ORIG_BASE", "http://localhost:8080/origination")
CONFIG = os.environ.get("HELIX_CONFIG_BASE", "http://localhost:8080/config")
PASS, FAIL = 0, 0


def call(base, method, path, body=None, actor="rm.user"):
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


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


RUN = "".join(random.choices(string.ascii_uppercase, k=5))


def base_app(**extra):
    """A complete, valid application-create body (all @NotBlank/@NotNull/@Positive fields).
    Collateral fields are added via **extra so a case can include/omit them precisely."""
    b = {"counterpartyId": 990001, "counterpartyRef": f"CP-FP-{RUN}",
         "counterpartyName": f"Field Policy Co {RUN}", "jurisdiction": "IN-RBI",
         "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
         "requestedAmount": 250_000_000, "currency": "INR", "tenorMonths": 60,
         "purpose": "Capex"}
    b.update(extra)
    return b


print("== 0. FIELD_POLICY master seeded in config-service ==")
st, recs = call(CONFIG, "GET", "/api/masters/FIELD_POLICY")
rec = next((r for r in recs if r.get("recordKey") == "ORIGINATION_APPLICATION"), None) \
    if isinstance(recs, list) else None
check("FIELD_POLICY/ORIGINATION_APPLICATION active", st == 200 and rec is not None, f"{st} {recs}")

print("== 1. field specs served via origination (incl. label overrides) ==")
st, body = call(ORIG, "GET", "/api/field-policy/ORIGINATION_APPLICATION")
ok_shape = st == 200 and isinstance(body, dict) \
    and body.get("formKey") == "ORIGINATION_APPLICATION" and isinstance(body.get("fields"), list)
check("GET returns 200 with formKey + fields list", ok_shape, f"{st} {body}")
specs = {f.get("field"): f for f in body.get("fields", [])} if isinstance(body, dict) else {}
check("requestedAmount label overridden to 'Facility amount'",
      specs.get("requestedAmount", {}).get("label") == "Facility amount",
      str(specs.get("requestedAmount")))
cv = specs.get("collateralValue", {})
check("collateralValue label + help overridden",
      cv.get("label") == "Collateral value (INR)"
      and cv.get("help") == "Market value of the pledged security", str(cv))
check("collateralValue requiredWhen collateralType PRESENT (ERROR)",
      (cv.get("requiredWhen") or {}).get("field") == "collateralType"
      and (cv.get("requiredWhen") or {}).get("op") == "PRESENT"
      and cv.get("requiredSeverity") == "ERROR", str(cv))

print("== 2. conditional-required enforced server-side (client cannot bypass) ==")
st, err = call(ORIG, "POST", "/api/applications", base_app(collateralType="PROPERTY", secured=True))
check("collateralType present + collateralValue absent -> 400", st == 400, f"{st} {err}")
msg = err.get("message", "") if isinstance(err, dict) else str(err)
check("400 names the (label-overridden) collateral value field",
      "Collateral value" in msg or "collateralValue" in msg, msg)

print("== 3. unsecured application accepted (collateralValue not required) ==")
st, app = call(ORIG, "POST", "/api/applications", base_app(secured=False))
check("no collateralType + no collateralValue -> 200",
      st == 200 and isinstance(app, dict) and app.get("reference"), f"{st} {app}")

print("== 4. secured application with both accepted ==")
st, app2 = call(ORIG, "POST", "/api/applications",
                base_app(collateralType="PROPERTY", collateralValue=300_000_000, secured=True))
check("collateralType + collateralValue -> 200",
      st == 200 and isinstance(app2, dict) and app2.get("reference"), f"{st} {app2}")

print("== 4b. regression: collateralType present with explicit 0 is accepted ==")
st, app3 = call(ORIG, "POST", "/api/applications",
                base_app(collateralType="NONE", collateralValue=0, secured=False))
check("collateralType='NONE' + collateralValue=0 -> 200 (explicit 0 is present, not blank)",
      st == 200 and isinstance(app3, dict) and app3.get("reference"), f"{st} {app3}")

print("== 5. unknown form -> empty specs (fail-open, no enforcement) ==")
st, body = call(ORIG, "GET", "/api/field-policy/UNKNOWN_FORM")
check("unknown form returns 200 with empty fields",
      st == 200 and isinstance(body, dict) and body.get("fields") == [], f"{st} {body}")

print(f"\n== FIELD_POLICY dynamic-screen e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
