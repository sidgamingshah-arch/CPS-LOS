#!/usr/bin/env python3
"""
CRM as the primary obligor-creation system (pull-and-create) — e2e.

Today the inbound CRM connector only ENRICHES an existing counterparty. This suite exercises
the ability to PULL borrower(s) from CRM and CREATE them as governed prospects, so a bank can
run CRM as its system-of-record for obligor creation.

The whole point is the governance invariant: a pull ALWAYS routes through the existing governed
credit-initiation flow (createProspect) — it yields a PROSPECT, never an approved obligor. Dedup,
negative-check, RM ownership and audit all fire exactly as for a hand-entered prospect; a named
human must still promote prospect -> obligor via /prospects/{id}/approve. Idempotency (CRM id) +
dedup (identifiers) guarantee no duplicate obligors, ever. Simulated is the default (no egress).

Runs against the gateway (:8080) by default like the other suites; for isolated verification set
HELIX_BASE to a direct counterparty-service port and HELIX_CP_PREFIX="".
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


def count_by_regno(regno):
    """How many counterparties carry this registrationNo (duplicate-detector)."""
    st, lst = call("GET", f"{CP}/api/counterparties")
    if st != 200 or not isinstance(lst, list):
        return -1
    return sum(1 for c in lst if c.get("registrationNo") == regno)


def has_pull_audit(reference):
    st, evs = call("GET", f"{CP}/api/audit/subject?type=Counterparty&id={reference}")
    if st != 200 or not isinstance(evs, list):
        return False, evs
    hit = [e for e in evs if e.get("eventType") == "CRM_BORROWER_PULLED"]
    return (len(hit) > 0 and all(e.get("actorType") == "HUMAN" for e in hit)), hit


sfx = str(int(time.time() * 1000))[-9:]

# ============================================================ 1. FRESH PULL -> governed PROSPECT
print("== 1. Pull a fresh borrower from CRM (simulated) -> a governed PROSPECT is created ==")
crm_fresh = f"FRESH-{sfx}"
st, r1 = call("POST", f"{CP}/api/initiation/ingest/crm/pull-borrower", {"crmId": crm_fresh}, actor="rm.alice")
r1 = r1 or {}
check("pull-borrower accepted (200)", st == 200 and r1.get("counterpartyRef"), f"{st} {r1}")
check("created=true, matchedExisting=false, dedupMatches=0", r1.get("created") is True
      and r1.get("matchedExisting") is False and r1.get("dedupMatches") == 0, str(r1))
check("not a negative-list hit (clean borrower)", r1.get("negativeHit") is False, str(r1))
check("result is a PROSPECT (recordType=PROSPECT), NOT an obligor",
      r1.get("recordType") == "PROSPECT", str(r1.get("recordType")))
check("result lifecycleStatus is prospect-stage (DRAFT), NOT ACTIVE/OBLIGOR",
      r1.get("lifecycleStatus") == "DRAFT", str(r1.get("lifecycleStatus")))
fresh_id, fresh_ref = r1.get("counterpartyId"), r1.get("counterpartyRef")

# The created record itself, fetched directly, must be a prospect (governed entry point applied).
st, cp1 = call("GET", f"{CP}/api/counterparties/{fresh_id}")
cp1 = cp1 or {}
check("GET counterparty confirms recordType=PROSPECT + lifecycleStatus=DRAFT",
      cp1.get("recordType") == "PROSPECT" and cp1.get("lifecycleStatus") == "DRAFT", str(cp1))
check("default RM ownership set to the puller (rm.alice) — governed-prospect defaults applied",
      cp1.get("rmId") == "rm.alice", str(cp1.get("rmId")))

# CrmProfile attached (provenance stamped) via the existing ingestion service.
st, prof = call("GET", f"{CP}/api/counterparties/{fresh_id}/crm")
prof = prof or {}
check("CrmProfile attached (200) with provenance", st == 200 and prof.get("sourceSystem") == "CRM", f"{st} {prof}")
check("CrmProfile is advisory INPUT + provenance vendor marks 'simulated' (no egress)",
      prof.get("advisory") is True and "simulated" in str(prof.get("sourceVendor", "")).lower(), str(prof))

ok, ev = has_pull_audit(fresh_ref)
check("CRM_BORROWER_PULLED audit present (actorType HUMAN)", ok, str(ev))

# ============================================================ 2. IDEMPOTENT RE-PULL
print("\n== 2. Re-pull the SAME crmId -> matchedExisting, created=false, NO duplicate ==")
st, r2 = call("POST", f"{CP}/api/initiation/ingest/crm/pull-borrower", {"crmId": crm_fresh}, actor="rm.alice")
r2 = r2 or {}
check("re-pull accepted (200)", st == 200, f"{st} {r2}")
check("matchedExisting=true, created=false", r2.get("matchedExisting") is True and r2.get("created") is False, str(r2))
check("re-pull returns the SAME counterparty (same ref + id)",
      r2.get("counterpartyRef") == fresh_ref and r2.get("counterpartyId") == fresh_id, str(r2))
check("NO duplicate counterparty for the CRM id (exactly one carries the registrationNo)",
      count_by_regno("CRMREG-" + crm_fresh) == 1, f"count={count_by_regno('CRMREG-' + crm_fresh)}")

# ============================================================ 3. DEDUP against an existing obligor
print("\n== 3. Pull a borrower whose identifiers match an existing counterparty -> dedup link ==")
crm_dup = f"DUP-{sfx}"
dup_regno = "CRMREG-" + crm_dup           # the registrationNo the simulated CRM will return for this crmId
# Pre-create an already-onboarded counterparty sharing that registrationNo (distinct name).
st, existing = call("POST", f"{CP}/api/counterparties", {
    "legalName": f"Existing Dedup Target {sfx}", "legalForm": "PRIVATE_LTD",
    "registrationNo": dup_regno, "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
    "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.bob")
existing = existing or {}
check("pre-created existing counterparty (200)", st == 200 and existing.get("reference"), f"{st} {existing}")
existing_ref = existing.get("reference")
before_dup = count_by_regno(dup_regno)
st, r3 = call("POST", f"{CP}/api/initiation/ingest/crm/pull-borrower", {"crmId": crm_dup}, actor="rm.carol")
r3 = r3 or {}
check("dedup pull accepted (200)", st == 200, f"{st} {r3}")
check("created=false, dedupMatches>0 (identifier match, no duplicate created)",
      r3.get("created") is False and r3.get("dedupMatches", 0) > 0, str(r3))
check("dedup pull is NOT flagged matchedExisting (distinct from idempotent replay)",
      r3.get("matchedExisting") is False, str(r3))
check("dedup pull LINKED to the existing counterparty (same reference)",
      r3.get("counterpartyRef") == existing_ref, str(r3))
check("NO duplicate created — registrationNo count unchanged (still exactly one)",
      count_by_regno(dup_regno) == before_dup == 1, f"before={before_dup} after={count_by_regno(dup_regno)}")
st, dprof = call("GET", f"{CP}/api/counterparties/{existing.get('id')}/crm")
check("CRM profile enriched onto the existing counterparty (link/enrich)",
      st == 200 and (dprof or {}).get("sourceSystem") == "CRM", f"{st} {dprof}")

# ============================================================ 4. NEGATIVE-LISTED borrower
print("\n== 4. Pull a negative-listed borrower -> prospect created, negativeHit, NOT auto-approved ==")
crm_neg = f"NEG-{sfx}"                     # 'NEG' sentinel -> simulated sanctioned-country (CU) borrower
st, r4 = call("POST", f"{CP}/api/initiation/ingest/crm/pull-borrower", {"crmId": crm_neg}, actor="rm.alice")
r4 = r4 or {}
check("negative pull accepted (200)", st == 200, f"{st} {r4}")
check("prospect STILL created (created=true) despite the negative hit", r4.get("created") is True, str(r4))
check("negativeHit=true", r4.get("negativeHit") is True, str(r4))
check("NOT auto-approved — recordType=PROSPECT (not OBLIGOR)", r4.get("recordType") == "PROSPECT", str(r4))
check("NOT auto-approved — lifecycleStatus=DRAFT (not ACTIVE)", r4.get("lifecycleStatus") == "DRAFT", str(r4))
st, cp4 = call("GET", f"{CP}/api/counterparties/{r4.get('counterpartyId')}")
cp4 = cp4 or {}
check("GET confirms the negative-listed pull stayed a prospect (no auto-promotion)",
      cp4.get("recordType") == "PROSPECT" and cp4.get("lifecycleStatus") == "DRAFT", str(cp4))

# ============================================================ 5. GOVERNANCE — human is the gate
print("\n== 5. GOVERNANCE: a pulled prospect is not an obligor until a HUMAN approves ==")
crm_gov = f"GOV-{sfx}"
st, r5 = call("POST", f"{CP}/api/initiation/ingest/crm/pull-borrower", {"crmId": crm_gov}, actor="rm.alice")
r5 = r5 or {}
gov_id, gov_ref = r5.get("counterpartyId"), r5.get("counterpartyRef")
check("gov pull created a PROSPECT (not an obligor) — pull path never approves",
      r5.get("created") is True and r5.get("recordType") == "PROSPECT"
      and r5.get("lifecycleStatus") == "DRAFT", str(r5))
# The human gate still works exactly as for a hand-entered prospect.
st, appr = call("POST", f"{CP}/api/initiation/prospects/{gov_id}/approve", None, actor="credit.head")
appr = appr or {}
check("human /approve promotes prospect -> OBLIGOR (200)", st == 200 and appr.get("recordType") == "OBLIGOR", f"{st} {appr}")
check("promoted obligor is now ACTIVE (the human gate is what created the obligor)",
      appr.get("lifecycleStatus") == "ACTIVE", str(appr.get("lifecycleStatus")))
check("promotion recorded on the SAME counterparty the pull created (no duplicate)",
      appr.get("reference") == gov_ref, str(appr.get("reference")))

# ============================================================ 6. BATCH pull -> counts add up
print("\n== 6. Batch pull -> summary counts add up (create + matchedExisting + dedupLinked == total) ==")
batch_ids = [f"BAT1-{sfx}", f"BAT2-{sfx}", f"BAT1-{sfx}"]   # third repeats the first -> matchedExisting
st, b = call("POST", f"{CP}/api/initiation/ingest/crm/pull-batch", {"crmIds": batch_ids}, actor="rm.alice")
b = b or {}
check("batch pull accepted (200)", st == 200 and isinstance(b.get("results"), list), f"{st} {b}")
check("batch total == number of results == number of ids requested",
      b.get("total") == len(b.get("results", [])) == len(batch_ids), str(b))
check("batch counts add up: created + matchedExisting + dedupLinked == total",
      b.get("created", 0) + b.get("matchedExisting", 0) + b.get("dedupLinked", 0) == b.get("total"), str(b))
check("batch created two fresh borrowers, matched the repeated crmId once",
      b.get("created") == 2 and b.get("matchedExisting") == 1, str(b))
check("every batch borrower is a governed PROSPECT (none auto-approved to OBLIGOR)",
      all(x.get("recordType") == "PROSPECT" for x in b.get("results", [])), str(b.get("results")))

# No-body batch -> the simulated source returns its default sample list.
st, b2 = call("POST", f"{CP}/api/initiation/ingest/crm/pull-batch", None, actor="rm.alice")
b2 = b2 or {}
check("no-body batch pull returns the default sample list (total>=1, counts add up)",
      st == 200 and b2.get("total", 0) >= 1
      and b2.get("created", 0) + b2.get("matchedExisting", 0) + b2.get("dedupLinked", 0) == b2.get("total"),
      f"{st} {b2}")

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
