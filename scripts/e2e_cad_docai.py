#!/usr/bin/env python3
"""
CAD document-AI (signature + title/property-doc verification) — e2e (Wave-3).

CAD document verification is an ADVISORY AI overlay on a checklist item. It is governed by the
CAD_DOC_AI capability, produces a separate advisory CadDocVerification finding (DRAFT), and is
human-gated (accept/reject). The governance invariant it must uphold:

    the AI run NEVER moves the checklist item to COMPLIED — it only produces an advisory finding;
    a named human still sets the item status through the existing CAD workflow.

This suite proves:

  A. OPEN — a CAD case opens with its master-driven checklist (items PENDING).

  B. SIGNATURE verify — an advisory finding is returned (MATCH for a matching specimen,
     MISMATCH for a different name), advisory=true + DRAFT, and the AI run stamps an audit AI
     event on the checklist item. The item status is UNCHANGED by the AI run.

  C. PROPERTY_DOC verify — key title fields are extracted from the doc text (COMPLETE when all
     mandatory fields are present, INCOMPLETE + missingFields when some are absent). Advisory,
     DRAFT, audit AI stamped, item status unchanged.

  D. HUMAN GATE — a human ACCEPTS the finding (audit HUMAN event); the checklist item status is
     STILL unchanged by the accept. The human then COMPLIES the item through the normal CAD
     endpoint — proving compliance is a human act, never the AI's. A second finding is REJECTED.

Run against a live stack on the gateway (:8080). Additive — does not touch existing CAD behaviour.
This suite is NOT registered in run_regression (the coordinator registers it).
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


def item_status(case_id, item_id):
    st, view = call("GET", f"/decision/api/cad/cases/{case_id}")
    view = must(st, view, "cad view")
    for it in view["items"]:
        if it["id"] == item_id:
            return it["status"]
    return None


APP_REF = "APP-CADAI-001"

# ============================================================ A. open CAD case
print("\n== A. Open a CAD case with its master-driven checklist ==")
st, opened = call("POST", "/decision/api/cad/cases",
                  {"applicationRef": APP_REF, "counterpartyName": "Cadai Verify Ltd", "cpType": "NEW"},
                  actor="cad.officer")
opened = must(st, opened, "open cad case")
case_id = opened["cadCase"]["id"]
items = opened["items"]
check("CAD case opened with checklist items (all PENDING)",
      len(items) >= 1 and all(i["status"] == "PENDING" for i in items),
      str([(i["description"], i["status"]) for i in items]))

# Pick a signature-target item and a property-doc-target item.
sig_item = next((i for i in items if "resolution" in i["description"].lower()
                 or "sanction" in i["description"].lower()), items[0])
prop_item = next((i for i in items if "mortgage" in i["description"].lower()
                  or "deed" in i["description"].lower()), items[-1])
sig_item_id = sig_item["id"]
prop_item_id = prop_item["id"]

# ============================================================ B. signature verification (advisory)
print("\n== B. Signature verification — advisory finding, item status unchanged ==")
before = item_status(case_id, sig_item_id)
st, sig = call("POST", f"/decision/api/cad/cases/{case_id}/items/{sig_item_id}/verify-doc",
               {"verificationType": "SIGNATURE",
                "claimedSignatory": "Mr. Rajesh Kumar",
                "specimenSignatory": "Rajesh Kumar"}, actor="cad.officer")
sig = must(st, sig, "signature verify")
check("signature verify returns an advisory DRAFT finding",
      sig["advisory"] is True and sig["status"] == "DRAFT" and sig["verificationType"] == "SIGNATURE",
      str(sig.get("status")))
check("matching specimen -> advisory verdict MATCH with a confidence",
      sig["verdict"] == "MATCH" and sig.get("confidence", 0) > 0.5, str(sig.get("verdict")))
after = item_status(case_id, sig_item_id)
check("checklist item status UNCHANGED by the signature AI run (still PENDING)",
      before == "PENDING" and after == "PENDING", f"{before} -> {after}")

# A mismatching name -> advisory MISMATCH (still advisory, still does not touch the item).
st, mism = call("POST", f"/decision/api/cad/cases/{case_id}/items/{sig_item_id}/verify-doc",
                {"verificationType": "SIGNATURE",
                 "claimedSignatory": "John Smith",
                 "specimenSignatory": "Rajesh Kumar"}, actor="cad.officer")
mism = must(st, mism, "signature mismatch verify")
check("non-matching signatory -> advisory verdict MISMATCH", mism["verdict"] == "MISMATCH", str(mism.get("verdict")))
check("checklist item STILL PENDING after a MISMATCH finding (AI never complies)",
      item_status(case_id, sig_item_id) == "PENDING")

# audit: the AI run stamped an AI event on the checklist item.
st, aud = call("GET", f"/decision/api/audit/subject?type=CadItem&id={sig_item_id}")
aud = must(st, aud, "audit for sig item")
ai_events = [e for e in aud if e.get("eventType") == "CAD_DOC_VERIFIED"]
check("signature AI run stamped an audit AI event (actorType AI)",
      len(ai_events) >= 1 and any(e.get("actorType") == "AI" for e in ai_events), str(ai_events[:1]))

# ============================================================ C. property/title-doc verification
print("\n== C. Title/property-document verification — field extraction + completeness ==")
COMPLETE_DOC = ("Title deed of the property. Owner: Rajesh Kumar. "
                "Address: Plot 42, MG Road, Pune, Maharashtra. "
                "Survey No: 123/4B. Deed No: REG-2021-8842. "
                "Area: 2400 sq ft. The property is free of encumbrance.")
before = item_status(case_id, prop_item_id)
st, prop = call("POST", f"/decision/api/cad/cases/{case_id}/items/{prop_item_id}/verify-doc",
                {"verificationType": "PROPERTY_DOC", "docText": COMPLETE_DOC}, actor="cad.officer")
prop = must(st, prop, "property verify complete")
check("property verify returns an advisory DRAFT finding",
      prop["advisory"] is True and prop["status"] == "DRAFT" and prop["verificationType"] == "PROPERTY_DOC",
      str(prop.get("status")))
check("all mandatory fields extracted -> advisory verdict COMPLETE",
      prop["verdict"] == "COMPLETE" and not prop.get("missingFields"),
      f"{prop.get('verdict')} missing={prop.get('missingFields')}")
check("extracted the owner from the document text",
      "OWNER" in (prop.get("extractedFields") or {}), str(prop.get("extractedFields")))
check("checklist item status UNCHANGED by the property-doc AI run",
      before == "PENDING" and item_status(case_id, prop_item_id) == "PENDING")

# An incomplete document -> INCOMPLETE with a non-empty missingFields list.
INCOMPLETE_DOC = "Title document. Owner: Rajesh Kumar. Address: Plot 42, MG Road, Pune."
st, inc = call("POST", f"/decision/api/cad/cases/{case_id}/items/{prop_item_id}/verify-doc",
               {"verificationType": "PROPERTY_DOC", "docText": INCOMPLETE_DOC}, actor="cad.officer")
inc = must(st, inc, "property verify incomplete")
check("missing mandatory fields -> advisory verdict INCOMPLETE + missingFields listed",
      inc["verdict"] == "INCOMPLETE" and len(inc.get("missingFields") or []) >= 1,
      f"{inc.get('verdict')} missing={inc.get('missingFields')}")

# ============================================================ D. human gate (accept / reject)
print("\n== D. Human gate — accept the finding; the human (not the AI) complies the item ==")
st, acc = call("POST", f"/decision/api/cad/doc-verifications/{prop['id']}/accept",
               {"note": "Title fields verified against DMS scan"}, actor="cad.checker")
acc = must(st, acc, "accept finding")
check("human accept transitions DRAFT -> ACCEPTED (records the human actor)",
      acc["status"] == "ACCEPTED" and acc["reviewedBy"] == "cad.checker", str(acc.get("status")))
check("checklist item STILL PENDING after the human accept (accept is not a comply)",
      item_status(case_id, prop_item_id) == "PENDING")

st, aud = call("GET", f"/decision/api/audit/subject?type=CadItem&id={prop_item_id}")
aud = must(st, aud, "audit for prop item")
human_events = [e for e in aud if e.get("eventType") == "CAD_DOC_VERIFICATION_ACCEPTED"]
check("accept stamped an audit HUMAN event",
      len(human_events) >= 1 and any(e.get("actorType") == "HUMAN" for e in human_events), str(human_events[:1]))

# The human now complies the item through the existing CAD endpoint — compliance is a human act.
st, complied = call("POST", f"/decision/api/cad/items/{prop_item_id}",
                    {"status": "COMPLIED", "docRef": "DMS-TITLE-01", "comment": "verified"}, actor="cad.officer")
complied = must(st, complied, "human comply item")
check("the HUMAN sets the checklist item to COMPLIED (never the AI)",
      complied["status"] == "COMPLIED", str(complied.get("status")))

# Reject path on the signature finding.
st, rej = call("POST", f"/decision/api/cad/doc-verifications/{sig['id']}/reject",
               {"note": "specimen out of date, re-capture"}, actor="cad.checker")
rej = must(st, rej, "reject finding")
check("human reject transitions DRAFT -> REJECTED", rej["status"] == "REJECTED", str(rej.get("status")))
st, redo = call("POST", f"/decision/api/cad/doc-verifications/{sig['id']}/accept", {}, actor="cad.checker")
check("a decided finding cannot be re-decided (409)", redo is not None and st == 409, f"{st}")

# The doc-verifications for the case are listable.
st, lst = call("GET", f"/decision/api/cad/cases/{case_id}/doc-verifications")
lst = must(st, lst, "list doc verifications")
check("doc verifications are listable for the CAD case", len(lst) >= 3, str(len(lst)))

print(f"\n== CAD document-AI e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
