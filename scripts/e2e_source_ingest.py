#!/usr/bin/env python3
"""
Inbound source-system connectors — e2e (credit-bureau + inbound CRM).

Both ride the EXISTING canonical ingestion contract (Envelope / Provenance / Connector /
IngestionGuard), mirroring the screening + core-banking adapters. Each supports PUSH (an
external caller POSTs a raw vendor payload) and PULL (Helix fetches OUT to the source;
config-gated — simulated sample by default so it is demoable with no external system, live
endpoint via env base-url, fail-soft).

Asserted governance invariants:
  * idempotent + provenance-stamped on every ingest (reuse IngestionGuard);
  * ingested external data is INPUT/advisory carrying provenance — it never becomes an
    authoritative figure by itself and never mutates the counterparty (no rating side effect);
  * simulated is the default (no external call), marked as such in the provenance vendor.

Runs against the gateway (:8080) by default like the other suites; for isolated verification
set HELIX_BASE to a direct counterparty-service port and HELIX_CP_PREFIX="".
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("HELIX_BASE", os.environ.get("HELIX_GW", "http://localhost:8080"))
# Path prefix for counterparty-service routes. Through the gateway it is "/counterparty"
# (StripPrefix=1); hitting the service directly it is "".
CP = os.environ.get("HELIX_CP_PREFIX", "/counterparty")
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="rm.user"):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, (json.loads(txt) if txt else None)
        except Exception:
            return e.code, {"raw": txt}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def new_cp(name_suffix):
    """Create a fresh OBLIGOR counterparty and return (id, reference, full-record)."""
    st, cp = call("POST", f"{CP}/api/counterparties", {
        "legalName": f"Bureau Test Co {name_suffix}", "legalForm": "PRIVATE_LTD",
        "registrationNo": f"U27100MH2009PTC{name_suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    if st != 200 or not cp:
        print(f"  FATAL  could not create counterparty ({st} {cp})")
        sys.exit(2)
    return cp["id"], cp["reference"], cp


sfx = str(int(time.time() * 1000))[-9:]

# ============================================================ 1. BUREAU PUSH
print("== 1. Credit-bureau PUSH — raw vendor payload -> canonical -> advisory BureauRecord ==")
cp_id, cp_ref, _ = new_cp(sfx)
bureau_key = f"BUREAU-{sfx}-A"
push_env = {
    "source": "CREDIT_BUREAU", "vendor": "CIBIL", "idempotencyKey": bureau_key, "payloadVersion": "1.0",
    "payload": {"bureauName": "CIBIL", "subjectName": "Bureau Test Co", "subjectId": cp_ref,
                "score": 782, "scoreModel": "CIBIL-TransUnion-3.0", "enquiries6m": 2,
                "delinquencies24m": 0, "tradelines": 6, "outstanding": 12500000.0, "oldestAcctMonths": 84}}
st, res = call("POST", f"{CP}/api/counterparties/{cp_id}/ingest/bureau", push_env, actor="rm.alice")
res = res or {}
check("bureau push accepted (200, accepted=true, duplicate=false)",
      st == 200 and res.get("accepted") is True and res.get("duplicate") is False, f"{st} {res}")
check("result carries the source system + idempotency key",
      res.get("source") == "CREDIT_BUREAU" and res.get("idempotencyKey") == bureau_key, str(res))

print("\n== 2. GET latest bureau report — score + provenance visible ==")
st, rec = call("GET", f"{CP}/api/counterparties/{cp_id}/bureau")
rec = rec or {}
check("GET bureau returns the ingested record (200)", st == 200 and rec.get("id") is not None, f"{st} {rec}")
check("credit score returned verbatim (input/provenance data)", rec.get("creditScore") == 782, str(rec.get("creditScore")))
check("scoreModel + conduct fields carried through",
      rec.get("scoreModel") == "CIBIL-TransUnion-3.0" and rec.get("openTradelines") == 6
      and rec.get("inquiriesLast6m") == 2, str(rec))
check("provenance: sourceSystem CREDIT_BUREAU + vendor CIBIL + sourceReference == idempotencyKey",
      rec.get("sourceSystem") == "CREDIT_BUREAU" and rec.get("sourceVendor") == "CIBIL"
      and rec.get("sourceReference") == bureau_key, str(rec))
check("record flagged advisory INPUT (never an authoritative figure)", rec.get("advisory") is True, str(rec.get("advisory")))

print("\n== 3. Idempotent replay of the same idempotencyKey -> duplicate, NOT re-applied ==")
# Replay with a DIFFERENT score under the SAME key; the guard must short-circuit so the stored
# figure is unchanged (proves not-re-applied, not merely a duplicate flag).
replay_env = json.loads(json.dumps(push_env))
replay_env["payload"]["score"] = 999
replay_env["vendor"] = "SHOULD-NOT-OVERWRITE"
st, res2 = call("POST", f"{CP}/api/counterparties/{cp_id}/ingest/bureau", replay_env, actor="rm.alice")
res2 = res2 or {}
check("replay returns duplicate=true (idempotent)", st == 200 and res2.get("duplicate") is True, f"{st} {res2}")
st, rec2 = call("GET", f"{CP}/api/counterparties/{cp_id}/bureau")
rec2 = rec2 or {}
check("stored score UNCHANGED after replay (not re-applied)", rec2.get("creditScore") == 782, str(rec2.get("creditScore")))
check("stored vendor UNCHANGED after replay", rec2.get("sourceVendor") == "CIBIL", str(rec2.get("sourceVendor")))

print("\n== 3b. Missing idempotencyKey -> clear 400 (fail-soft, never a raw 500) ==")
bad_env = json.loads(json.dumps(push_env))
bad_env["idempotencyKey"] = ""
st, _ = call("POST", f"{CP}/api/counterparties/{cp_id}/ingest/bureau", bad_env, actor="rm.alice")
check("blank idempotencyKey rejected with 400 (not 500)", st == 400, f"{st}")

print("\n== 4. BUREAU PULL (simulated default, no base-url) — accepted, ZERO external calls ==")
pull_id, pull_ref, _ = new_cp(sfx + "P")
st, pres = call("POST", f"{CP}/api/counterparties/{pull_id}/ingest/bureau/pull", actor="rm.bob")
pres = pres or {}
check("bureau pull accepted (200, accepted=true)", st == 200 and pres.get("accepted") is True, f"{st} {pres}")
st, prec = call("GET", f"{CP}/api/counterparties/{pull_id}/bureau")
prec = prec or {}
check("pulled record has a score present", isinstance(prec.get("creditScore"), int), str(prec.get("creditScore")))
check("provenance vendor marks 'simulated' (no external system was called)",
      "simulated" in str(prec.get("sourceVendor", "")).lower(), str(prec.get("sourceVendor")))
check("pulled record flagged advisory INPUT", prec.get("advisory") is True, str(prec.get("advisory")))
check("pulled record stamped sourceSystem CREDIT_BUREAU", prec.get("sourceSystem") == "CREDIT_BUREAU", str(prec))

print("\n== 5. GOVERNANCE — a bureau ingest moves NO authoritative figure on the obligor ==")
gov_id, gov_ref, before = new_cp(sfx + "G")
st, before = call("GET", f"{CP}/api/counterparties/{gov_id}")
gov_key = f"BUREAU-{sfx}-GOV"
gov_env = {
    "source": "CREDIT_BUREAU", "vendor": "Experian", "idempotencyKey": gov_key, "payloadVersion": "1.0",
    "payload": {"bureauName": "Experian", "subjectName": "Bureau Test Co", "subjectId": gov_ref,
                "score": 640, "scoreModel": "Experian-2.0", "enquiries6m": 5, "delinquencies24m": 2,
                "tradelines": 3, "outstanding": 90000000.0, "oldestAcctMonths": 30}}
st, gres = call("POST", f"{CP}/api/counterparties/{gov_id}/ingest/bureau", gov_env, actor="rm.alice")
check("governance-subject bureau push accepted", st == 200 and (gres or {}).get("accepted") is True, f"{st} {gres}")
st, after = call("GET", f"{CP}/api/counterparties/{gov_id}")
check("counterparty record BYTE-IDENTICAL before vs after bureau ingest (no rating/figure side effect)",
      before == after, f"before != after")
# The counterparty carries no rating/grade/PD field, and the ingest never creates one.
check("no rating / grade / PD field appeared on the obligor from the ingest",
      not any(k in (after or {}) for k in ("rating", "grade", "pd", "internalGrade")), str(list((after or {}).keys())))

print("\n== 6. CRM PUSH — raw vendor payload -> canonical -> advisory CrmProfile ==")
crm_id, crm_ref, _ = new_cp(sfx + "C")
crm_key = f"CRM-{sfx}-A"
crm_env = {
    "source": "CRM", "vendor": "salesforce", "idempotencyKey": crm_key, "payloadVersion": "1.0",
    "payload": {"crmId": "SF-000123", "accountName": "Acme Manufacturing", "relationshipManager": "rm.jane",
                "segment": "CORPORATE", "relationshipValue": 25000000.0, "primaryContactName": "John Doe",
                "primaryContactEmail": "john@acme.example.com",
                "productsHeld": ["WORKING_CAPITAL", "TERM_LOAN"], "lifecycleStage": "ACTIVE"}}
st, cres = call("POST", f"{CP}/api/counterparties/{crm_id}/ingest/crm", crm_env, actor="rm.alice")
cres = cres or {}
check("crm push accepted (200, accepted=true)", st == 200 and cres.get("accepted") is True, f"{st} {cres}")
check("result carries source CRM + idempotency key",
      cres.get("source") == "CRM" and cres.get("idempotencyKey") == crm_key, str(cres))
st, crec = call("GET", f"{CP}/api/counterparties/{crm_id}/crm")
crec = crec or {}
check("GET crm returns the profile (200)", st == 200 and crec.get("id") is not None, f"{st} {crec}")
check("profile carries relationshipManager + segment", crec.get("relationshipManager") == "rm.jane"
      and crec.get("segment") == "CORPORATE", str(crec))
check("profile carries productsHeld list", crec.get("productsHeld") == ["WORKING_CAPITAL", "TERM_LOAN"], str(crec.get("productsHeld")))
check("provenance: sourceSystem CRM + vendor salesforce", crec.get("sourceSystem") == "CRM"
      and crec.get("sourceVendor") == "salesforce", str(crec))
check("crm record flagged advisory INPUT", crec.get("advisory") is True, str(crec.get("advisory")))

print("\n== 6b. CRM idempotent replay -> duplicate, not re-applied ==")
crm_replay = json.loads(json.dumps(crm_env))
crm_replay["payload"]["relationshipManager"] = "rm.should.not.overwrite"
st, cres2 = call("POST", f"{CP}/api/counterparties/{crm_id}/ingest/crm", crm_replay, actor="rm.alice")
check("crm replay returns duplicate=true", st == 200 and (cres2 or {}).get("duplicate") is True, f"{st} {cres2}")
st, crec2 = call("GET", f"{CP}/api/counterparties/{crm_id}/crm")
check("crm relationshipManager UNCHANGED after replay", (crec2 or {}).get("relationshipManager") == "rm.jane",
      str((crec2 or {}).get("relationshipManager")))

print("\n== 7. CRM PULL (simulated default) — accepted, provenance marks 'simulated' ==")
cpull_id, cpull_ref, _ = new_cp(sfx + "CP")
st, cpres = call("POST", f"{CP}/api/counterparties/{cpull_id}/ingest/crm/pull", actor="rm.bob")
check("crm pull accepted (200)", st == 200 and (cpres or {}).get("accepted") is True, f"{st} {cpres}")
st, cprec = call("GET", f"{CP}/api/counterparties/{cpull_id}/crm")
cprec = cprec or {}
check("pulled crm profile has relationshipManager + segment",
      bool(cprec.get("relationshipManager")) and bool(cprec.get("segment")), str(cprec))
check("crm pull provenance vendor marks 'simulated' (no external system called)",
      "simulated" in str(cprec.get("sourceVendor", "")).lower(), str(cprec.get("sourceVendor")))
check("pulled crm record flagged advisory INPUT + sourceSystem CRM",
      cprec.get("advisory") is True and cprec.get("sourceSystem") == "CRM", str(cprec))

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
