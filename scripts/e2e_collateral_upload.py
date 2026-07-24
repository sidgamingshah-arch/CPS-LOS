#!/usr/bin/env python3
"""
Document-driven collateral intelligence — e2e (batch4/collateral-intel).

Collateral extraction used to accept typed/pasted text only. This suite proves the new
DOCUMENT-DRIVEN path: a real collateral document is uploaded (DMS store + PDFBox/UTF-8/OCR
text extraction, a first-class Document that appears in the deal's document list), then
collateral extraction runs over THAT document's real text via the SAME advisory pipeline.

It asserts:

  1. Real upload — a valid one-page PDF (correct xref offsets) uploaded as actual bytes is
     stored in the DMS (storedDocId + sha256) and PDFBox parses the embedded valuation text.
     The uploaded document appears in the deal's document list.

  2. Document-driven extract — POST /collateral-intel/{ref}/extract with a `documentId`
     derives the collateral fields FROM the document's text: the marketValue VALUE is the
     number embedded in the PDF (NOT a template), the extraction records sourceDocumentId,
     and it is SUGGESTED (advisory, human confirm required).

  3. THE ADVISORY INVARIANT — the authoritative deal envelope (totalCollateralCover +
     collateral count) and the authoritative rating (finalGrade / modelGrade / pd) are
     BYTE-IDENTICAL before vs after the extract. Extraction never writes a figure; only the
     human confirm does.

  4. Human confirm — materialises a real Collateral (the envelope's collateral count +1 and
     totalCollateralCover increases), stamps a HUMAN audit event, and links the extraction.

  5. Guards — a text-less document -> 400 (nothing to derive); a document belonging to a
     DIFFERENT application -> 400; and the legacy typed-text path still works (backward compat).

Assumes the stack is up on the gateway (bash scripts/run-all.sh), like the other suites.
NOT registered in run_regression — run it standalone against the gateway.
"""
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0

# The valuation text embedded in the generated PDF. The content parser must read these back.
# Fields are '|'-separated so the labelled-field parser stops each capture at the delimiter.
MARKET_VALUE = 48000000
PDF_TEXT = (
    "VALUATION REPORT | Property type: Industrial warehouse | "
    "Address: Plot 14 Phase 2 Pune | Market value: INR 48000000 | "
    "Valuation date: 2026-03-15 | Valuer: Knight and Co Surveyors"
)


def call(method, path, body=None, actor="rm.user"):
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


def upload_file(ref, filename, content_type, data_bytes, declared_type=None, actor="collateral.analyst"):
    """Manually-built multipart/form-data POST of the real file bytes."""
    boundary = "----helixColBoundary" + hashlib.sha1(str(time.time()).encode()).hexdigest()[:12]
    parts = []
    if declared_type:
        parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"declaredType\""
                      f"\r\n\r\n{declared_type}\r\n").encode())
    parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                  f"filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n").encode()
                 + data_bytes + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    body = b"".join(parts)
    req = urllib.request.Request(GW + f"/origination/api/applications/{ref}/documents/upload",
                                 data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
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


def make_pdf(text):
    """Build a valid minimal one-page PDF embedding `text`, with CORRECT xref byte offsets."""
    esc = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream_data = ("BT /F1 12 Tf 72 720 Td (" + esc + ") Tj ET").encode("latin-1")
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
         b"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"),
        (b"<< /Length " + str(len(stream_data)).encode() + b" >>\nstream\n"
         + stream_data + b"\nendstream"),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    buf = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objs, start=1):
        offsets.append(len(buf))
        buf += str(i).encode() + b" 0 obj\n" + body + b"\nendobj\n"
    xref_offset = len(buf)
    n = len(objs) + 1
    buf += b"xref\n"
    buf += ("0 %d\n" % n).encode()
    buf += b"0000000000 65535 f \n"
    for off in offsets:
        buf += ("%010d 00000 n \n" % off).encode()
    buf += b"trailer\n"
    buf += ("<< /Size %d /Root 1 0 R >>\n" % n).encode()
    buf += b"startxref\n"
    buf += (str(xref_offset) + "\n").encode()
    buf += b"%%EOF"
    return bytes(buf)


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


def msg_of(b):
    return (b.get("message") if isinstance(b, dict) else str(b)) or ""


def fval(fields, key):
    """The `value` of an extraction field {value,confidence}, or None."""
    cell = (fields or {}).get(key)
    if isinstance(cell, dict):
        return cell.get("value")
    return cell


def line(v):
    return {"value": v, "sourceDocument": "fs.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount):
    """cp -> app -> spread -> confirm -> rate -> rating/confirm; returns the app ref."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"COL {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"COL{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
        per("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    return ref


def env_snapshot(ref):
    st, e = call("GET", f"/origination/api/applications/{ref}/envelope")
    if st != 200 or not isinstance(e, dict):
        return None
    return {
        "totalProposedAmount": e.get("totalProposedAmount"),
        "totalCollateralCover": e.get("totalCollateralCover"),
        "collateralCount": len(e.get("collaterals") or []),
    }


def rating_snapshot(ref):
    st, s = call("GET", f"/risk/api/risk/{ref}")
    if st != 200 or not isinstance(s, dict):
        return None
    r = s.get("rating") or {}
    return {"modelGrade": r.get("modelGrade"), "finalGrade": r.get("finalGrade"), "pd": r.get("pd")}


# ============================================================ 1. real PDF upload -> DMS + PDFBox
print("== 1. Real collateral PDF upload: DMS store + PDFBox parses the actual valuation text ==")
ref = rated_deal("UP", 40_000_000)
env_before = env_snapshot(ref)
rating_before = rating_snapshot(ref)
check("baseline envelope captured", env_before is not None, str(env_before))

pdf_bytes = make_pdf(PDF_TEXT)
sha_local = hashlib.sha256(pdf_bytes).hexdigest()
st, doc = upload_file(ref, "valuation-report-001.pdf", "application/pdf", pdf_bytes,
                      declared_type="SECURITY_DOC", actor="collateral.analyst")
doc = must(st, doc, "upload pdf")
doc_id = doc["id"]
check("upload stored the document in the DMS (storedDocId set)", doc.get("storedDocId") is not None, str(doc))
check("content sha256 matches the local digest", doc.get("sha256") == sha_local,
      f"{doc.get('sha256')} vs {sha_local}")
check("PDFBox parsed the bytes: extractedText contains the embedded market value '48000000'",
      "48000000" in (doc.get("extractedText") or ""), str(doc.get("extractedText"))[:120])

# The uploaded document is a first-class Document that appears in the deal's document list.
st, docs = call("GET", f"/origination/api/applications/{ref}/documents")
docs = docs or []
check("uploaded document appears in the deal's document list",
      any(d.get("id") == doc_id for d in docs), str([d.get("id") for d in docs]))


# ============================================================ 2. document-driven extract
print("\n== 2. Document-driven extract: fields derived FROM the PDF text, records sourceDocumentId ==")
st, ex = call("POST", f"/origination/api/collateral-intel/{ref}/extract",
              {"documentKind": "VALUATION_REPORT", "documentId": doc_id}, actor="collateral.analyst")
ex = must(st, ex, "extract from document")
check("extraction is SUGGESTED (advisory, human confirm required)", ex.get("status") == "SUGGESTED",
      str(ex.get("status")))
check("collateralType mapped to PROPERTY", ex.get("collateralType") == "PROPERTY", str(ex.get("collateralType")))
check("extraction records the source document id (provenance)", ex.get("sourceDocumentId") == doc_id,
      str(ex.get("sourceDocumentId")))
mv = fval(ex.get("fields"), "marketValue")
check("marketValue VALUE is the number parsed from the PDF text (== 48000000, NOT a template)",
      mv == MARKET_VALUE or mv == float(MARKET_VALUE), f"marketValue={mv!r}")
check("no mandatory fields missing (marketValue/valuationDate/valuerName/addressLine all found)",
      (ex.get("missingMandatory") or []) == [], str(ex.get("missingMandatory")))
extraction_id = ex["id"]

# The extract stamps an AI (advisory) audit event.
st, evs = call("GET", f"/origination/api/audit/subject?type=Application&id={ref}")
evs = evs or []
check("COLLATERAL_EXTRACTED stamped as an AI (advisory) event",
      any(e.get("eventType") == "COLLATERAL_EXTRACTED" and e.get("actorType") == "AI" for e in evs),
      str([(e.get("eventType"), e.get("actorType")) for e in evs][:8]))


# ============================================================ 3. ADVISORY INVARIANT
print("\n== 3. ADVISORY INVARIANT: envelope + rating byte-identical before vs after the extract ==")
env_after = env_snapshot(ref)
rating_after = rating_snapshot(ref)
check("authoritative deal envelope UNCHANGED by the extract (no collateral written)",
      env_before is not None and env_before == env_after, f"{env_before} vs {env_after}")
check("authoritative rating UNCHANGED by the extract",
      rating_before is not None and rating_before == rating_after, f"{rating_before} vs {rating_after}")


# ============================================================ 4. human confirm materialises collateral
print("\n== 4. Human confirm materialises a real Collateral (the only writer) + HUMAN audit ==")
st, created = call("POST", f"/origination/api/collateral-intel/extractions/{extraction_id}/confirm",
                   {"note": "verified against the valuation report"}, actor="credit.officer")
created = must(st, created, "confirm extraction")
check("confirm returned a Collateral carrying the extracted market value",
      created.get("marketValue") == MARKET_VALUE or created.get("marketValue") == float(MARKET_VALUE),
      str(created.get("marketValue")))
col_id = created.get("id")

env_confirmed = env_snapshot(ref)
check("envelope collateral count increased by exactly 1 after confirm",
      env_confirmed and env_confirmed["collateralCount"] == env_before["collateralCount"] + 1,
      f"{env_before} -> {env_confirmed}")
check("envelope totalCollateralCover increased after confirm (the write happened here)",
      env_confirmed and env_confirmed["totalCollateralCover"] > env_before["totalCollateralCover"],
      f"{env_before['totalCollateralCover']} -> {env_confirmed['totalCollateralCover']}")

st, lst = call("GET", f"/origination/api/collateral-intel/{ref}/extractions")
lst = lst or []
row = next((e for e in lst if e.get("id") == extraction_id), None)
check("extraction is now CONFIRMED and links the materialised collateral",
      row and row.get("status") == "CONFIRMED" and row.get("linkedCollateralId") == col_id,
      str(row))

st, evs = call("GET", f"/origination/api/audit/subject?type=Application&id={ref}")
evs = evs or []
check("COLLATERAL_EXTRACTION_CONFIRMED stamped HUMAN by the confirming officer",
      any(e.get("eventType") == "COLLATERAL_EXTRACTION_CONFIRMED" and e.get("actorType") == "HUMAN"
          and e.get("actor") == "credit.officer" for e in evs),
      str([(e.get("eventType"), e.get("actorType")) for e in evs][:10]))


# ============================================================ 5. guards + backward compat
print("\n== 5. Guards: text-less doc -> 400, cross-application doc -> 400, typed-text path still works ==")
# A text-less image with the default helix.ocr.provider=none has no extracted text.
png_bytes = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
             b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89" + bytes(range(0, 64)))
st, idoc = upload_file(ref, "scan-blank.png", "image/png", png_bytes, actor="collateral.analyst")
idoc = must(st, idoc, "upload png")
st, r = call("POST", f"/origination/api/collateral-intel/{ref}/extract",
             {"documentKind": "VALUATION_REPORT", "documentId": idoc["id"]}, actor="collateral.analyst")
check("extract over a text-less document -> 400 (nothing to derive)", st == 400, f"{st} {r}")
check("...400 explains there is no extracted text", "no extracted text" in msg_of(r).lower(), msg_of(r))

# A document belonging to a DIFFERENT application must not be usable under this deal.
other = rated_deal("OTHER", 30_000_000)
st, odoc = upload_file(other, "valuation-other.pdf", "application/pdf", make_pdf(PDF_TEXT),
                       actor="collateral.analyst")
odoc = must(st, odoc, "upload other-app doc")
st, r = call("POST", f"/origination/api/collateral-intel/{ref}/extract",
             {"documentKind": "VALUATION_REPORT", "documentId": odoc["id"]}, actor="collateral.analyst")
check("extract with a document from another application -> 400", st == 400, f"{st} {r}")
check("...400 explains the document does not belong to this application",
      "does not belong" in msg_of(r).lower(), msg_of(r))

# Legacy typed-text path (no documentId) is unchanged.
st, tex = call("POST", f"/origination/api/collateral-intel/{ref}/extract",
               {"documentKind": "PG_DEED",
                "text": "PERSONAL GUARANTEE | Guarantor: Ramesh Gupta | "
                        "Guarantee amount: INR 12000000 | Type of guarantee: personal"},
               actor="collateral.analyst")
tex = must(st, tex, "typed-text extract")
check("typed-text extract still works (backward compat) and derives the guarantor",
      tex.get("status") == "SUGGESTED" and tex.get("sourceDocumentId") is None
      and fval(tex.get("fields"), "guarantorName") == "Ramesh Gupta", str(tex.get("fields")))


print(f"\n== collateral-upload e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
