#!/usr/bin/env python3
"""
Document Execution Workflow + Signatory Matrix (CLoM R1-14 / F73 / F74) — e2e.

Builds on the existing DocGen / GeneratedDocument. This suite proves:

  A. GENERATE  — a facility-agreement document is generated (reuse /api/docs) and
     human-confirmed (maker != checker), giving an authoritative, confirm-locked artifact.

  B. PACKAGE   — an ExecutionPackage (EXE-*) is opened over the deal from two generated
     documents; it starts OPEN.

  C. MATRIX    — a per-document signatory matrix (INTERNAL + CUSTOMER) is built; each
     signature flips a signatory to SIGNED and, once all have signed, auto-advances the
     document to SIGNED.

  D. LIFECYCLE — the document status stepper is driven PENDING -> SENT (stamps a facade
     e-sign envelope id) -> SIGNED -> RECEIVED; a deferral tag is recorded; the package
     auto-derives to COMPLETED once every document is RECEIVED.

  E. INVARIANT — the source GeneratedDocument (content + confirm-lock) is byte-identical
     before vs after the whole execution run. Execution tracks status only; it never edits
     the authoritative document. This is the safety contract.

  F. AUDIT     — every transition stamps an audit HUMAN event.

Run against a live stack on the gateway (:8080).
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="cad.officer"):
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


# ---------------------------------------------------------------- deal setup
print("== exec 0. Deal setup (counterparty -> application) ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Execution Test Foods Ltd", "legalForm": "PRIVATE_LTD",
    "registrationNo": "U15100MH2012PTC900001", "jurisdiction": "IN-RBI",
    "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "counterparty create")

st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 250_000_000, "currency": "INR", "tenorMonths": 60,
    "purpose": "Capacity expansion", "collateralType": "PROPERTY",
    "collateralValue": 300_000_000, "secured": True}, actor="rm.user")
ref = must(st, app, "application create")["reference"]
check("application created", bool(ref), ref)

# ---------------------------------------------------------------- A. generate + confirm docs
print("== exec 1. Generate two documents (reuse /api/docs) + confirm one (maker!=checker) ==")
st, docA = call("POST", f"/decision/api/docs/applications/{ref}/generate",
                {"templateKey": "FACILITY_AGREEMENT", "variables": {}}, actor="cad.officer")
docA = must(st, docA, "generate docA")
check("docA generated in DRAFT", docA.get("status") == "DRAFT", str(docA.get("status")))
docA_id = docA["id"]

st, confd = call("POST", f"/decision/api/docs/{docA_id}/confirm", {"comment": "wording ok"}, actor="credit.officer")
confd = must(st, confd, "confirm docA")
check("docA confirmed (human confirm-lock)", confd.get("status") == "CONFIRMED", str(confd.get("status")))

st, docB = call("POST", f"/decision/api/docs/applications/{ref}/generate",
                {"templateKey": "FACILITY_AGREEMENT", "variables": {}}, actor="cad.officer")
docB = must(st, docB, "generate docB")
docB_id = docB["id"]

# Snapshot the authoritative source documents BEFORE any execution activity.
st, docA_before = call("GET", f"/decision/api/docs/{docA_id}")
docA_before = must(st, docA_before, "read docA before")
st, docB_before = call("GET", f"/decision/api/docs/{docB_id}")
docB_before = must(st, docB_before, "read docB before")

# ---------------------------------------------------------------- B. open the package
print("== exec 2. Open an execution package over the deal ==")
st, pkg = call("POST", "/decision/api/execution/packages", {
    "subjectRef": ref,
    "documents": [
        {"docRef": str(docA_id), "title": docA["title"]},
        {"docRef": str(docB_id)},  # title resolved from the source GeneratedDocument
    ]}, actor="cad.officer")
pkg = must(st, pkg, "create package")
exec_ref = pkg["executionPackage"]["execRef"]
check("package created (EXE-*, OPEN, 2 documents)",
      exec_ref.startswith("EXE-") and pkg["executionPackage"]["status"] == "OPEN"
      and len(pkg["documents"]) == 2, str(pkg["executionPackage"]))
doc_a = pkg["documents"][0]["document"]
doc_b = pkg["documents"][1]["document"]
check("docB title resolved from the source GeneratedDocument (read-only enrich)",
      doc_b["documentTitle"] == docB["title"], str(doc_b["documentTitle"]))
check("both document executions start PENDING",
      doc_a["status"] == "PENDING" and doc_b["status"] == "PENDING", "")
dexec_a = doc_a["id"]
dexec_b = doc_b["id"]

# ---------------------------------------------------------------- C. signatory matrix on docA
print("== exec 3. Build docA signatory matrix (INTERNAL + CUSTOMER) + sign ==")
st, v = call("POST", f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_a}/signatories",
             {"name": "Helix Bank — Authorised Officer", "role": "RELATIONSHIP_MANAGER", "side": "INTERNAL"},
             actor="cad.officer")
v = must(st, v, "add internal signatory")
st, v = call("POST", f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_a}/signatories",
             {"name": "Borrower — Managing Director", "role": "DIRECTOR", "side": "CUSTOMER"},
             actor="cad.officer")
v = must(st, v, "add customer signatory")
sigs = v["documents"][0]["signatories"]
check("two signatories on docA (one INTERNAL, one CUSTOMER)",
      len(sigs) == 2 and {s["side"] for s in sigs} == {"INTERNAL", "CUSTOMER"}, str(sigs))

# a bad side is rejected 400
st, bad = call("POST", f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_a}/signatories",
               {"name": "X", "side": "NEITHER"}, actor="cad.officer")
check("invalid signatory side -> 400", st == 400, f"{st} {bad}")

# send docA (stamps a facade e-sign envelope id) then sign both signatories
st, v = call("POST", f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_a}/status",
             {"status": "SENT"}, actor="cad.officer")
v = must(st, v, "send docA")
env = v["documents"][0]["document"]["esignEnvelopeId"]
check("docA SENT stamps a facade e-sign envelope id (ESN-*)", bool(env) and env.startswith("ESN-"), str(env))
check("package moved OFF OPEN after first activity",
      v["executionPackage"]["status"] == "IN_PROGRESS", str(v["executionPackage"]["status"]))

for s in sigs:
    st, v = call("POST",
                 f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_a}/signatories/{s['id']}/sign",
                 actor="cad.officer")
    v = must(st, v, f"sign {s['id']}")
signed = v["documents"][0]["signatories"]
check("all docA signatories SIGNED", all(s["status"] == "SIGNED" for s in signed), str(signed))
check("all-signed auto-advances docA to SIGNED",
      v["documents"][0]["document"]["status"] == "SIGNED", str(v["documents"][0]["document"]["status"]))

# double-sign is rejected
st, ds = call("POST",
              f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_a}/signatories/{signed[0]['id']}/sign",
              actor="cad.officer")
check("re-signing an already-SIGNED signatory -> 409", st == 409, f"{st} {ds}")

# docA received
st, v = call("POST", f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_a}/status",
             {"status": "RECEIVED"}, actor="cad.officer")
v = must(st, v, "receive docA")
check("docA RECEIVED", v["documents"][0]["document"]["status"] == "RECEIVED", "")
check("package still IN_PROGRESS (docB outstanding)",
      v["executionPackage"]["status"] == "IN_PROGRESS", str(v["executionPackage"]["status"]))

# ---------------------------------------------------------------- D. deferral tag + drive to COMPLETED
print("== exec 4. Tag a deferral on docB, then drive to RECEIVED -> package COMPLETED ==")
st, v = call("POST", f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_b}/defer",
             {"deferralTag": "PENDING_BOARD_RESOLUTION"}, actor="cad.officer")
v = must(st, v, "defer docB")
check("docB tagged deferred", v["documents"][1]["document"]["deferralTag"] == "PENDING_BOARD_RESOLUTION",
      str(v["documents"][1]["document"]["deferralTag"]))
check("package remains IN_PROGRESS while docB deferred (deferral does not close a doc)",
      v["executionPackage"]["status"] == "IN_PROGRESS", str(v["executionPackage"]["status"]))

st, v = call("POST", f"/decision/api/execution/packages/{exec_ref}/documents/{dexec_b}/status",
             {"status": "RECEIVED"}, actor="cad.officer")
v = must(st, v, "receive docB")
check("all documents RECEIVED -> package COMPLETED",
      v["executionPackage"]["status"] == "COMPLETED", str(v["executionPackage"]["status"]))
check("deferral tag preserved on docB after receipt (annotation survives)",
      v["documents"][1]["document"]["deferralTag"] == "PENDING_BOARD_RESOLUTION", "")

# read-back via GET package
st, got = call("GET", f"/decision/api/execution/packages/{exec_ref}")
got = must(st, got, "get package")
check("package fetchable by execRef with both documents COMPLETED",
      got["executionPackage"]["status"] == "COMPLETED" and len(got["documents"]) == 2, "")
# listable by subject
st, lst = call("GET", f"/decision/api/execution/packages?subjectRef={ref}")
lst = must(st, lst, "list by subject")
check("package listed for the subject", any(p["execRef"] == exec_ref for p in lst), str([p["execRef"] for p in lst]))

# unknown package -> 404
st, nf = call("GET", "/decision/api/execution/packages/EXE-NOPE99")
check("unknown package -> 404", st == 404, f"{st} {nf}")

# ---------------------------------------------------------------- E. invariant: source docs byte-identical
print("== exec 5. Governance invariant — source GeneratedDocument byte-identical after execution ==")
st, docA_after = call("GET", f"/decision/api/docs/{docA_id}")
docA_after = must(st, docA_after, "read docA after")
st, docB_after = call("GET", f"/decision/api/docs/{docB_id}")
docB_after = must(st, docB_after, "read docB after")
check("confirmed source document (docA) byte-identical after the full execution run",
      json.dumps(docA_before, sort_keys=True) == json.dumps(docA_after, sort_keys=True),
      "docA mutated by execution tracking")
check("docA still CONFIRMED (confirm-lock intact)", docA_after.get("status") == "CONFIRMED",
      str(docA_after.get("status")))
check("source document (docB) byte-identical after the full execution run",
      json.dumps(docB_before, sort_keys=True) == json.dumps(docB_after, sort_keys=True),
      "docB mutated by execution tracking")

# ---------------------------------------------------------------- F. audit HUMAN trail
print("== exec 6. Audit trail — every transition is HUMAN-stamped ==")
st, aud = call("GET", f"/decision/api/audit/subject?type=ExecutionPackage&id={exec_ref}")
aud = must(st, aud, "audit subject")
types = {e.get("eventType") for e in aud}
check("EXECUTION_PACKAGE_CREATED recorded", "EXECUTION_PACKAGE_CREATED" in types, str(types))
check("EXECUTION_SIGNATORY_SIGNED recorded", "EXECUTION_SIGNATORY_SIGNED" in types, str(types))
check("EXECUTION_DOCUMENT_DEFERRED recorded", "EXECUTION_DOCUMENT_DEFERRED" in types, str(types))
check("EXECUTION_PACKAGE_COMPLETED recorded", "EXECUTION_PACKAGE_COMPLETED" in types, str(types))
check("all execution audit events are HUMAN-actor",
      all(e.get("actorType") == "HUMAN" for e in aud) if aud else False,
      str([(e.get("eventType"), e.get("actorType")) for e in aud[:3]]))

print(f"\n== Document-execution e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
