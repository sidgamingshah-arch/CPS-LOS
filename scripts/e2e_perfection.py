#!/usr/bin/env python3
"""
Mortgage / MOE security-perfection — e2e (decision-service, via the gateway).

Proves the Wave-2 perfection module end-to-end:
  1. Seed the CHECKLIST_MASTER PERFECTION_MOE row (maker → checker, SoD).
  2. Create a case → steps are materialised IN ORDER from the master (version pinned).
  3. Steps are role-gated to their ownerRole (wrong role → 403; correct role → DONE).
  4. A VENDOR step raises an EXTERNAL_VENDOR QueryThread; the vendor report returns via
     the existing external-response callback.
  5. MOE-vetting SoD: vetting by the MOE-execution actor → 403 forbiddenAutonomy.
  6. Case flips to COMPLETED once every step is DONE / WAIVED.
  7. The OPTIONAL limit-release gate is DEFAULT-OFF: a flow with NO perfectionRequired config
     releases exactly as before — even with an INCOMPLETE perfection case linked to the deal.
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
NONCE = str(int(time.time()))


def call(method, path, body=None, actor="cad.ops"):
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


MOE_STEPS = [
    {"key": "TITLE_SEARCH", "title": "Title search report (master)", "ownerRole": "LEGAL"},
    {"key": "LEGAL_OPINION", "title": "Legal opinion on title (master)", "ownerRole": "LEGAL"},
    {"key": "VALUATION", "title": "Property valuation (master)", "ownerRole": "VENDOR"},
    {"key": "MOE_EXECUTION", "title": "MOE execution (master)", "ownerRole": "LMO"},
    {"key": "MOE_VETTING", "title": "MOE vetting (master)", "ownerRole": "CAD_OPS"},
    {"key": "CERSAI_FILING", "title": "CERSAI charge filing (master)", "ownerRole": "CAD_OPS"},
]


print("== 1. Seed CHECKLIST_MASTER PERFECTION_MOE (maker -> checker, SoD) ==")
st, sub = call("POST", "/config/api/masters/CHECKLIST_MASTER",
               {"recordKey": "PERFECTION_MOE", "payload": {"steps": MOE_STEPS}}, actor="master.maker")
sub = must(st, sub, "submit PERFECTION_MOE")
check("PERFECTION_MOE submitted PENDING_APPROVAL", sub["status"] == "PENDING_APPROVAL", sub.get("status"))
rec_id = sub["id"]
# SoD: maker cannot self-approve
st, sod = call("POST", f"/config/api/masters/records/{rec_id}/approve", actor="master.maker")
check("maker cannot self-approve the master (403)", st == 403, f"{st}")
st, appr = call("POST", f"/config/api/masters/records/{rec_id}/approve", actor="master.checker")
appr = must(st, appr, "approve PERFECTION_MOE")
check("PERFECTION_MOE ACTIVE after checker approval", appr["status"] == "ACTIVE", appr.get("status"))
master_version = appr["version"]

print("== 2. Create a perfection case -> steps materialised IN ORDER from the master ==")
subject = f"COL-{NONCE}"
st, cv = call("POST", "/decision/api/perfection/cases",
              {"subjectType": "COLLATERAL", "subjectRef": subject, "applicationRef": f"PERF-APP-{NONCE}"},
              actor="cad.ops")
cv = must(st, cv, "create case")
case = cv["perfectionCase"]
perf_ref = case["perfRef"]
steps = cv["steps"]
check("perfRef generated PRF-XXXXXX", perf_ref.startswith("PRF-") and len(perf_ref) == 10, perf_ref)
check("case opens OPEN", case["status"] == "OPEN", case["status"])
check("6 steps materialised", len(steps) == 6, len(steps))
check("steps in order with keys from the master",
      [s["stepKey"] for s in steps] == [m["key"] for m in MOE_STEPS],
      [s["stepKey"] for s in steps])
check("step titles came from the master (not the built-in fallback)",
      [s["title"] for s in steps] == [m["title"] for m in MOE_STEPS],
      [s["title"] for s in steps])
check("step order 0..5", [s["stepOrder"] for s in steps] == list(range(6)),
      [s["stepOrder"] for s in steps])
check("master version pinned onto the case", case.get("masterVersion") == master_version,
      f"{case.get('masterVersion')} vs {master_version}")

print("== 3. Steps are role-gated to their ownerRole ==")
# Wrong role on TITLE_SEARCH (LEGAL) -> 403
st, wr = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/TITLE_SEARCH/complete",
              {"role": "LMO", "evidence": "x"}, actor="legal.counsel")
check("wrong-role completion rejected (403)", st == 403, f"{st} {wr}")
# Correct role -> DONE
st, ok = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/TITLE_SEARCH/complete",
              {"role": "LEGAL", "evidence": "DMS-TS-1"}, actor="legal.counsel")
ok = must(st, ok, "complete TITLE_SEARCH")
ts = next(s for s in ok["steps"] if s["stepKey"] == "TITLE_SEARCH")
check("TITLE_SEARCH DONE by LEGAL", ts["status"] == "DONE" and ts["completedBy"] == "legal.counsel", ts)
check("case moved to IN_PROGRESS after first action", ok["perfectionCase"]["status"] == "IN_PROGRESS",
      ok["perfectionCase"]["status"])
# LEGAL_OPINION (LEGAL) -> DONE
st, ok = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/LEGAL_OPINION/complete",
              {"role": "LEGAL", "evidence": "DMS-LO-1"}, actor="legal.counsel")
must(st, ok, "complete LEGAL_OPINION")

print("== 4. VENDOR step raises an EXTERNAL_VENDOR query; report returns via external-response ==")
st, ok = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/VALUATION/vendor-rfq",
              {"vendorId": "acme.valuers", "question": "Please provide the valuation certificate."},
              actor="cad.ops")
ok = must(st, ok, "vendor rfq")
val = next(s for s in ok["steps"] if s["stepKey"] == "VALUATION")
q_ref = val["vendorQueryRef"]
check("VALUATION step stored a vendorQueryRef", bool(q_ref) and q_ref.startswith("QRY-"), q_ref)
st, q = call("GET", f"/decision/api/queries/{q_ref}")
q = must(st, q, "get vendor query")
check("query is an EXTERNAL_VENDOR thread, OPEN", q["thread"]["channel"] == "EXTERNAL_VENDOR"
      and q["thread"]["status"] == "OPEN", q["thread"])
# Vendor report returns via the existing external-response callback
st, q = call("POST", f"/decision/api/queries/{q_ref}/external-response",
             {"body": "Valuation INR 4.2cr, valid to 2027.", "from": "acme.valuers"}, actor="portal.callback")
q = must(st, q, "external response")
check("external-response flips the query to RESPONDED", q["thread"]["status"] == "RESPONDED", q["thread"]["status"])
check("an inbound vendor message was appended", any(m.get("inbound") for m in q["messages"]), q["messages"])
# Now the VENDOR-owned step is completed (role VENDOR)
st, ok = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/VALUATION/complete",
              {"role": "VENDOR", "evidence": q_ref}, actor="vendor.user")
ok = must(st, ok, "complete VALUATION")
check("VALUATION DONE by VENDOR", next(s for s in ok["steps"] if s["stepKey"] == "VALUATION")["status"] == "DONE")

print("== 5. MOE execution, then MOE-vetting SoD (vetting != execution actor) ==")
st, ok = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/MOE_EXECUTION/complete",
              {"role": "LMO", "evidence": "DMS-MOE-1"}, actor="lmo.user")
ok = must(st, ok, "complete MOE_EXECUTION")
check("MOE_EXECUTION DONE by LMO (lmo.user)",
      next(s for s in ok["steps"] if s["stepKey"] == "MOE_EXECUTION")["completedBy"] == "lmo.user")
# SoD: the MOE-execution actor cannot also vet (role declared correctly as CAD_OPS to isolate the SoD)
st, sod = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/MOE_VETTING/complete",
               {"role": "CAD_OPS", "evidence": "x"}, actor="lmo.user")
check("MOE-vetting by the execution actor -> 403 (SoD)", st == 403, f"{st} {sod}")
# A different CAD_OPS actor vets successfully
st, ok = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/MOE_VETTING/complete",
              {"role": "CAD_OPS", "evidence": "DMS-VET-1"}, actor="cad.ops")
ok = must(st, ok, "complete MOE_VETTING")
check("MOE_VETTING DONE by a different CAD_OPS actor",
      next(s for s in ok["steps"] if s["stepKey"] == "MOE_VETTING")["status"] == "DONE")

print("== 6. Case COMPLETED once every step is DONE / WAIVED ==")
st, ok = call("POST", f"/decision/api/perfection/cases/{perf_ref}/steps/CERSAI_FILING/complete",
              {"role": "CAD_OPS", "evidence": "CERSAI-REF-1"}, actor="cad.ops")
ok = must(st, ok, "complete CERSAI_FILING")
check("case COMPLETED after last step", ok["perfectionCase"]["status"] == "COMPLETED",
      ok["perfectionCase"]["status"])
st, fin = call("GET", f"/decision/api/perfection/cases/{perf_ref}")
check("GET reflects COMPLETED + all steps terminal", fin["perfectionCase"]["status"] == "COMPLETED"
      and all(s["status"] in ("DONE", "WAIVED") for s in fin["steps"]),
      [s["status"] for s in fin["steps"]])

print("== 7. DEFAULT-OFF limit-release gate: no perfectionRequired -> release unchanged ==")
# A fresh deal with an INCOMPLETE perfection case linked. With perfectionRequired ABSENT
# (every seeded DOA pack), CAD limit-release must succeed exactly as before — the incomplete
# perfection case must NOT block, proving the gate is genuinely default-off.
gate_app = f"PERF-GATE-{NONCE}"
st, gcase = call("POST", "/decision/api/perfection/cases",
                 {"subjectType": "FACILITY", "subjectRef": f"FAC-{NONCE}", "applicationRef": gate_app},
                 actor="cad.ops")
gcase = must(st, gcase, "create gate perfection case")
check("gate perfection case is NOT completed (still OPEN)", gcase["perfectionCase"]["status"] == "OPEN",
      gcase["perfectionCase"]["status"])
# Stand up a CAD case for the same deal and drive it to a limit release.
st, cad = call("POST", "/decision/api/cad/cases",
               {"applicationRef": gate_app, "counterpartyName": "Gatehouse Ltd", "cpType": "NEW"}, actor="cad.officer")
cad = must(st, cad, "open CAD case")
cad_id = cad["cadCase"]["id"]
for it in cad["items"]:
    call("POST", f"/decision/api/cad/items/{it['id']}", {"status": "COMPLIED", "docRef": "DMS-G"}, actor="cad.officer")
st, comp = call("POST", f"/decision/api/cad/cases/{cad_id}/complete", actor="cad.officer")
comp = must(st, comp, "complete CAD case")
check("CAD case completed", comp["cadCase"]["status"] == "COMPLETED", comp["cadCase"]["status"])
st, rel = call("POST", f"/decision/api/cad/cases/{cad_id}/limit-release",
               {"processingFeeAmortised": True, "lienMarked": True, "cashMarginCaptured": True, "comment": "ok"},
               actor="cad.officer")
check("DEFAULT-OFF: limit-release succeeds despite an incomplete perfection case",
      st == 200 and rel["cadCase"]["status"] == "LIMIT_RELEASED", f"{st} {rel}")

print(f"\nperfection e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
