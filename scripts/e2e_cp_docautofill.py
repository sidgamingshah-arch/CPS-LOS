#!/usr/bin/env python3
"""
Counterparty create-screen document autofill — e2e (batch4).

A named human uploads a trade licence / MOA / AOA (or pastes its text) on the create screen;
the counterparty-scoped, extraction-only endpoint parses the entity's identity fields FROM the
document's real text and RETURNS them as advisory SUGGESTIONS to pre-fill the form. This proves
the governance contract:

  A. DERIVED-FROM-TEXT — POST /api/counterparties/extract-doc {text} returns the legal name, CIN,
     registration no, GSTIN, incorporation date, registered address and directors, and every value
     equals a string that actually appears in the document (not invented / not a canned template).
     A second, different document yields different values — confirming the parse is content-derived.

  B. NOTHING PERSISTED — the extract call creates no counterparty (the list count is unchanged and
     no record with the extracted legal name exists) and stamps an AI audit event (advisory).

  C. CREATE STILL WORKS — a create using the human-reviewed suggestions persists a real
     counterparty whose identifiers round-trip (the suggest → edit → submit flow).

Standalone suite (run against a live gateway); NOT registered in run_regression.
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


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


# Sample MOA / certificate-of-incorporation text. Every asserted value below is a substring of
# THIS text, so a passing assertion proves the parser copies from the document, never invents.
MOA_TEXT = "\n".join([
    "CERTIFICATE OF INCORPORATION",
    "",
    "Name of Company: Meridian Steel Manufacturing Private Limited",
    "Corporate Identity Number (CIN): U27100MH2015PLC123456",
    "Registration Number: 987654",
    "Date of Incorporation: 2015-03-12",
    "Registered Address: Plot 42, MIDC Industrial Area, Pune, Maharashtra 411018",
    "GSTIN: 27ABCDE1234F1Z5",
    "Directors: Anita Sharma, Rajesh Kumar and Vikram Mehta",
])

# A DIFFERENT document — used to prove the values are content-derived, not a fixed template.
MOA_TEXT_2 = "\n".join([
    "MEMORANDUM OF ASSOCIATION",
    "Name of Company: Coastal Textiles Limited",
    "Corporate Identity Number (CIN): L17110GJ2008PLC654321",
    "GSTIN: 24AACDE9876Q1Z2",
])

print("\n== A. Extraction derives fields from the document text ==")
st, sug = call("POST", "/counterparty/api/counterparties/extract-doc",
               {"text": MOA_TEXT, "declaredType": "MOA_AOA"}, actor="rm.user")
sug = must(st, sug, "extract MOA text")
check("legalName parsed verbatim from the text",
      sug.get("legalName") == "Meridian Steel Manufacturing Private Limited", str(sug.get("legalName")))
check("CIN parsed verbatim from the text",
      sug.get("cin") == "U27100MH2015PLC123456", str(sug.get("cin")))
check("registration number parsed verbatim from the text",
      sug.get("registrationNo") == "987654", str(sug.get("registrationNo")))
check("GSTIN parsed verbatim from the text",
      sug.get("gstin") == "27ABCDE1234F1Z5", str(sug.get("gstin")))
check("incorporation date parsed verbatim from the text",
      sug.get("incorporationDate") == "2015-03-12", str(sug.get("incorporationDate")))
check("registered address parsed from the text",
      "Pune" in (sug.get("registeredAddress") or ""), str(sug.get("registeredAddress")))
directors = sug.get("directors") or []
check("directors parsed and split into names",
      "Anita Sharma" in directors and "Rajesh Kumar" in directors and "Vikram Mehta" in directors,
      str(directors))
check("marked content-derived + advisory",
      sug.get("contentDerived") is True and sug.get("advisory") is True, str(sug))
# Every returned value must appear in the source text (derived, never invented).
for key in ("legalName", "cin", "registrationNo", "gstin", "incorporationDate"):
    v = sug.get(key)
    check(f"{key} value is present in the source document (not invented)",
          v is None or v in MOA_TEXT, f"{key}={v}")

# A different document yields different values — the parse is content-derived, not a template.
st, sug2 = call("POST", "/counterparty/api/counterparties/extract-doc",
                {"text": MOA_TEXT_2, "declaredType": "MOA_AOA"}, actor="rm.user")
sug2 = must(st, sug2, "extract second MOA text")
check("second document -> different legal name (content-derived, not a canned template)",
      sug2.get("legalName") == "Coastal Textiles Limited"
      and sug2.get("legalName") != sug.get("legalName"), str(sug2.get("legalName")))
check("second document -> its own CIN",
      sug2.get("cin") == "L17110GJ2008PLC654321", str(sug2.get("cin")))

print("\n== B. Extraction persists nothing + stamps an AI audit event ==")
st, before = call("GET", "/counterparty/api/counterparties")
before = must(st, before, "list before")
count_before = len(before)
# Run the extract again — it must still create no counterparty.
st, _ = call("POST", "/counterparty/api/counterparties/extract-doc",
             {"text": MOA_TEXT, "declaredType": "MOA_AOA"}, actor="rm.user")
must(st, _, "re-extract")
st, after = call("GET", "/counterparty/api/counterparties")
after = must(st, after, "list after")
check("extract created no counterparty (list count unchanged)",
      len(after) == count_before, f"{count_before} -> {len(after)}")
check("no counterparty exists with the extracted legal name (nothing persisted)",
      not any(c.get("legalName") == "Meridian Steel Manufacturing Private Limited" for c in after),
      "a record was unexpectedly persisted by extract")

subject = urllib.parse.quote("Meridian Steel Manufacturing Private Limited")
st, aud = call("GET", f"/counterparty/api/audit/subject?type=Counterparty&id={subject}")
aud = must(st, aud, "audit subject")
ai_events = [e for e in aud if e.get("eventType") == "COUNTERPARTY_DOC_EXTRACTED"]
check("extract stamped an AI audit event (actorType AI)",
      any(e.get("actorType") == "AI" for e in ai_events), str(ai_events[:1]))

print("\n== C. Create still works with the human-reviewed suggestions ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    # The suggested GSTIN is an illustrative sample value; a human reviewing the prefill would drop
    # or correct it before submit (identifiers are optional + checksum-validated on create). We keep
    # the human-verified CIN/registrationNo; the GSTIN suggestion is asserted separately in §A.
    "legalName": sug["legalName"], "legalForm": "PRIVATE_LTD",
    "registrationNo": sug["registrationNo"], "cin": sug["cin"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "create from suggestions")
check("counterparty persisted with a reference",
      isinstance(cp.get("reference"), str) and cp["reference"].startswith("CP"), str(cp.get("reference")))
check("human-confirmed identifiers round-trip onto the persisted record",
      cp.get("cin") == "U27100MH2015PLC123456" and cp.get("legalName") == sug["legalName"],
      f"cin={cp.get('cin')} name={cp.get('legalName')}")
st, again = call("GET", "/counterparty/api/counterparties")
again = must(st, again, "list after create")
check("the create (the real, audited write) increased the list count by one",
      len(again) == count_before + 1, f"{count_before} -> {len(again)}")

print(f"\n== Counterparty doc-autofill e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
