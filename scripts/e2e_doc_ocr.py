#!/usr/bin/env python3
"""
Real OCR / file upload for document capture — e2e (feat/real-ocr).

Document "upload" used to send only a filename string, and extraction returned per-deal-varied
but content-agnostic template values. This suite proves the capture is now REAL:

  * A valid one-page PDF (built here with correct xref byte offsets) is uploaded as actual bytes.
    The server stores it in the governed DMS (storedDocId + sha256) and PDFBox parses the bytes —
    the persisted extractedText really CONTAINS the embedded string (method PDFBOX).
  * Classification is content-based (the FINANCIAL_STATEMENT label comes from the text keywords,
    not the neutral file name).
  * doc-intel extract() derives the fields FROM the text: the `revenue` VALUE == 1250000000, the
    exact number embedded in the PDF — NOT a template constant. The extraction is SUGGESTED and a
    human confirm stamps a HUMAN audit event.
  * THE ADVISORY INVARIANT: the application's authoritative rating (finalGrade/modelGrade/pd) and
    confirmed spread (REVENUE cell + confirm flag) are BYTE-IDENTICAL before vs after the whole
    AI extract→confirm run. Extraction never writes a figure.
  * A plain .txt upload extracts (method TEXT). A text-less image upload with the default
    helix.ocr.provider=none degrades gracefully (method OCR_NONE + a note) — no crash.

Assumes the stack is up on the gateway (bash scripts/run-all.sh), like the other suites.
"""
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0

# The known string embedded in the generated PDF. The content parser must read these back verbatim.
PDF_TEXT = ("Revenue: 1250000000 EBITDA: 312000000 "
            "Reporting Period: FY2025 Auditor: Sterling Audit LLP")


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


def upload_file(ref, filename, content_type, data_bytes, declared_type=None, actor="analyst.user"):
    """Manually-built multipart/form-data POST of the real file bytes."""
    boundary = "----helixOCRBoundary" + hashlib.sha1(str(time.time()).encode()).hexdigest()[:12]
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
    """Build a valid minimal one-page PDF embedding `text`, with CORRECT xref byte offsets.

    Objects: 1 Catalog, 2 Pages, 3 Page, 4 Contents stream, 5 Helvetica font. Byte offsets are
    computed as the objects are appended, so the xref table + startxref are exact — a strict
    parser (and PDFBox's PDFTextStripper) reads it as a real text PDF."""
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
    buf += b"0000000000 65535 f \n"                 # 20-byte free entry
    for off in offsets:
        buf += ("%010d 00000 n \n" % off).encode()  # 20-byte in-use entries
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


def fval(fields, key):
    """The `value` of an extraction field {value,confidence,sourcePage}, or None."""
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
        "legalName": f"OCR {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"OCR{suffix}",
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


def rating_snapshot(ref):
    st, s = call("GET", f"/risk/api/risk/{ref}")
    if st != 200 or not isinstance(s, dict):
        return None
    r = s.get("rating") or {}
    return {"modelGrade": r.get("modelGrade"), "finalGrade": r.get("finalGrade"), "pd": r.get("pd")}


def spread_snapshot(ref):
    st, a = call("GET", f"/origination/api/applications/{ref}/analysis")
    if st != 200 or not isinstance(a, dict):
        return None
    rev = None
    periods = a.get("periods") or []
    if periods:
        for cell in (periods[0].get("lines") or []):
            if cell.get("taxonomyKey") == "REVENUE":
                rev = cell.get("value")
                break
    return {"spreadConfirmed": a.get("spreadConfirmed"), "revenue": rev}


# ============================================================ 1. PDF upload -> DMS store + PDFBox
print("== 1. Real PDF upload: DMS store (storedDocId + sha256) + PDFBox parses the actual bytes ==")
ref = rated_deal("PDF", 40_000_000)
rating_before = rating_snapshot(ref)
spread_before = spread_snapshot(ref)

pdf_bytes = make_pdf(PDF_TEXT)
sha_local = hashlib.sha256(pdf_bytes).hexdigest()
check("generated PDF starts with %PDF and ends with %%EOF (valid container)",
      pdf_bytes.startswith(b"%PDF-") and pdf_bytes.rstrip().endswith(b"%%EOF"), pdf_bytes[:8])

st, doc = upload_file(ref, "scan-fs-001.pdf", "application/pdf", pdf_bytes)
doc = must(st, doc, "upload pdf")
check("upload stored the document in the DMS (storedDocId set)", doc.get("storedDocId") is not None, str(doc))
check("content sha256 recorded and matches the local digest", doc.get("sha256") == sha_local,
      f"{doc.get('sha256')} vs {sha_local}")
check("sizeBytes matches the uploaded bytes", doc.get("sizeBytes") == len(pdf_bytes),
      f"{doc.get('sizeBytes')} vs {len(pdf_bytes)}")
check("extractionMethod == PDFBOX (real text PDF, not a filename guess)",
      doc.get("extractionMethod") == "PDFBOX", str(doc.get("extractionMethod")))
check("pageCount == 1", doc.get("pageCount") == 1, str(doc.get("pageCount")))
check("PDFBox really parsed the bytes: extractedText CONTAINS 'Revenue'",
      "Revenue" in (doc.get("extractedText") or ""), str(doc.get("extractedText"))[:120])
check("...and CONTAINS the embedded number '1250000000'",
      "1250000000" in (doc.get("extractedText") or ""), str(doc.get("extractedText"))[:120])
check("classifiedType == FINANCIAL_STATEMENT (content-based, from the text keywords)",
      doc.get("classifiedType") == "FINANCIAL_STATEMENT", str(doc.get("classifiedType")))
doc_id = doc["id"]

# The DMS itself stamps DOCUMENT_STORED; the upload action stamps a HUMAN DOCUMENT_UPLOADED.
st, evs = call("GET", f"/origination/api/audit/subject?type=Application&id={ref}")
evs = evs or []
check("DOCUMENT_UPLOADED audit stamped HUMAN by the uploader",
      any(e.get("eventType") == "DOCUMENT_UPLOADED" and e.get("actorType") == "HUMAN"
          and e.get("actor") == "analyst.user" for e in evs), str([e.get("eventType") for e in evs][:8]))


# ============================================================ 2. content-derived extraction
print("\n== 2. doc-intel extract() derives fields FROM the real text (not a template constant) ==")
st, ex = call("POST", f"/origination/api/doc-intel/documents/{doc_id}/extract", actor="doc.intel")
ex = must(st, ex, "extract")
check("extraction is SUGGESTED (advisory, human confirm required)", ex.get("status") == "SUGGESTED", str(ex.get("status")))
check("extraction is marked document-derived (model doc-intel-ocr-v1)",
      ex.get("model") == "doc-intel-ocr-v1", str(ex.get("model")))
rev = fval(ex.get("fields"), "revenue")
check("revenue field VALUE is the number parsed from the PDF text (== 1250000000, NOT a template)",
      rev == 1250000000 or rev == 1250000000.0, f"revenue={rev!r}")
eb = fval(ex.get("fields"), "ebitda")
check("ebitda field VALUE parsed from the text (== 312000000)", eb == 312000000 or eb == 312000000.0, f"ebitda={eb!r}")
check("reporting_period parsed from the text (FY2025)", fval(ex.get("fields"), "reporting_period") == "FY2025",
      str(fval(ex.get("fields"), "reporting_period")))
extraction_id = ex["id"]


# ============================================================ 3. confirm -> HUMAN audit + INVARIANT
print("\n== 3. Human confirm stamps a HUMAN audit; authoritative rating + spread UNCHANGED ==")
st, conf = call("POST", f"/origination/api/doc-intel/extractions/{extraction_id}/confirm",
                {"note": "verified against the PDF"}, actor="credit.analyst")
conf = must(st, conf, "confirm")
check("extraction confirmed (CONFIRMED)", conf.get("status") == "CONFIRMED", str(conf.get("status")))
st, evs = call("GET", f"/origination/api/audit/subject?type=Application&id={ref}")
evs = evs or []
check("DOC_EXTRACTION_CONFIRMED stamped HUMAN by the confirming analyst",
      any(e.get("eventType") == "DOC_EXTRACTION_CONFIRMED" and e.get("actorType") == "HUMAN"
          and e.get("actor") == "credit.analyst" for e in evs),
      str([(e.get("eventType"), e.get("actorType")) for e in evs][:8]))

rating_after = rating_snapshot(ref)
spread_after = spread_snapshot(ref)
check("ADVISORY INVARIANT: authoritative rating byte-identical before vs after the AI run",
      rating_before is not None and rating_before == rating_after, f"{rating_before} vs {rating_after}")
check("ADVISORY INVARIANT: confirmed spread (REVENUE + confirm flag) byte-identical before vs after",
      spread_before is not None and spread_before == spread_after, f"{spread_before} vs {spread_after}")


# ============================================================ 4. plain .txt upload -> TEXT method
print("\n== 4. Plain .txt upload extracts as real UTF-8 text (method TEXT) ==")
txt_body = b"Balance Sheet FY2024\nTotal Revenue: 987654321\nEBITDA: 111111111\n"
st, tdoc = upload_file(ref, "financials.txt", "text/plain", txt_body)
tdoc = must(st, tdoc, "upload txt")
check("txt extractionMethod == TEXT", tdoc.get("extractionMethod") == "TEXT", str(tdoc.get("extractionMethod")))
check("txt extractedText contains the real content ('987654321')",
      "987654321" in (tdoc.get("extractedText") or ""), str(tdoc.get("extractedText"))[:120])
check("txt storedDocId + sha256 recorded", tdoc.get("storedDocId") is not None and tdoc.get("sha256"),
      str(tdoc.get("sha256")))


# ============================================================ 5. text-less image -> graceful OCR_NONE
print("\n== 5. Text-less image with helix.ocr.provider=none degrades gracefully (no crash) ==")
png_bytes = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
             b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89" + bytes(range(0, 64)))
st, idoc = upload_file(ref, "scan-page.png", "image/png", png_bytes)
idoc = must(st, idoc, "upload png")
check("image upload succeeds (no crash, 200)", st == 200 and idoc.get("id") is not None, f"{st}")
check("image extractionMethod == OCR_NONE (provider not configured — the default)",
      idoc.get("extractionMethod") == "OCR_NONE", str(idoc.get("extractionMethod")))
check("image has no fabricated text (extractedText null/blank — nothing invented)",
      not (idoc.get("extractedText") or "").strip(), str(idoc.get("extractedText")))
check("image bytes still stored in the DMS (storedDocId + sha256)",
      idoc.get("storedDocId") is not None and idoc.get("sha256"), str(idoc.get("sha256")))

# The extraction path over a text-less doc must not crash and must not derive figures from nothing:
st, ex2 = call("POST", f"/origination/api/doc-intel/documents/{idoc['id']}/extract", actor="doc.intel")
ex2 = must(st, ex2, "extract text-less")
check("text-less extraction still SUGGESTED and falls back to the template (no crash)",
      ex2.get("status") == "SUGGESTED" and ex2.get("model") == "doc-intel-v1", str(ex2.get("model")))


# ============================================================ 6. legacy filename-only path unchanged
print("\n== 6. Legacy filename-only upload path is UNCHANGED (additive feature) ==")
st, ldoc = call("POST", f"/origination/api/applications/{ref}/documents",
                {"fileName": "balance-sheet-2024.pdf", "declaredType": "FINANCIAL_STATEMENT"},
                actor="analyst.user")
ldoc = must(st, ldoc, "legacy upload")
check("legacy filename-only upload still works (declared type honoured, 200)",
      ldoc.get("classifiedType") == "FINANCIAL_STATEMENT", str(ldoc.get("classifiedType")))
check("legacy record carries no stored bytes (storedDocId null) — genuinely the old path",
      ldoc.get("storedDocId") is None, str(ldoc.get("storedDocId")))


print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
