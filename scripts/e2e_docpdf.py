#!/usr/bin/env python3
"""End-to-end test for the print / PDF rendering path (Wave-3).

Drives a deal through the existing lifecycle to a DECIDED (conditional-approve)
state, generates the credit proposal, a facility-agreement document (with the
existing generate + human confirm-lock flow), and a sanction letter, then asserts:

  * the print endpoints return a self-contained, print-optimised standalone
    document reproducing the AUTHORITATIVE artifact verbatim (governance chrome +
    the exact clause / figure text), or a real PDF if a lib is ever wired in
    (checked via the "%PDF" magic bytes);
  * rendering does NOT mutate the source artifact — the generated document and the
    credit proposal are byte-identical before and after the render (the render is a
    pure, read-only projection; the only side effect is an append-only audit event);
  * unknown ids / references return 404 rather than a partial page.

Run against a live stack on the gateway (:8080). Additive — does not touch the
existing docgen / proposal / confirm-lock behaviour.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="test.user"):
    url = GW + path
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
        except json.JSONDecodeError:
            return e.code, txt


def call_text(path, actor="test.user"):
    """GET a text/html (non-JSON) endpoint; returns (status, raw_text)."""
    req = urllib.request.Request(GW + path, method="GET")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def die_if_none(label, obj):
    if obj is None:
        print(f"  ABORT  {label}: null response — cannot continue")
        summary()
        sys.exit(1)


def summary():
    print(f"\n{PASS} passed, {FAIL} failed")


# ------------------------------------------------------------------ deal setup
print("== docpdf 0. Deal setup (counterparty -> rated -> decided) ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Cascade Print Foods Pvt Ltd", "legalForm": "PRIVATE_LTD",
    "registrationNo": "U15100MH2011PTC777001", "jurisdiction": "IN-RBI",
    "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
die_if_none("counterparty create", cp)
check("counterparty created", st == 200 and "id" in cp, f"{st} {cp}")
cp_id = cp["id"]

st, hits = call("POST", f"/counterparty/api/counterparties/{cp_id}/screening/run", actor="compliance.officer")
for h in (hits or []):
    call("POST", f"/counterparty/api/counterparties/screening/{h['id']}/disposition",
         {"disposition": "FALSE_POSITIVE", "note": "no secondary identifier match"}, actor="compliance.officer")
st, cpv = call("POST", f"/counterparty/api/counterparties/{cp_id}/kyc/verify", actor="compliance.officer")
check("KYC verified", st == 200 and cpv.get("kycStatus") == "VERIFIED", f"{st} {cpv.get('kycStatus') if cpv else cpv}")

st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp_id, "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 800_000_000, "currency": "INR", "tenorMonths": 60,
    "purpose": "Capacity expansion", "collateralType": "PROPERTY",
    "collateralValue": 600_000_000, "secured": True}, actor="rm.user")
die_if_none("application create", app)
check("application created", st == 200 and "reference" in app, f"{st} {app}")
ref = app["reference"]


def period(label):
    def line(v):
        return {"value": v, "sourceDocument": "Cascade_FY24.pdf", "sourcePage": "P12",
                "coordinates": "tbl1", "confidence": 0.97}
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(5e9), "COGS": line(3.2e9), "OPERATING_EXPENSES": line(0.9e9),
        "DEPRECIATION": line(0.2e9), "INTEREST_EXPENSE": line(0.15e9), "TAX": line(0.12e9),
        "TOTAL_ASSETS": line(6e9), "CURRENT_ASSETS": line(2.5e9), "CASH": line(0.6e9),
        "CURRENT_LIABILITIES": line(1.5e9), "SHORT_TERM_DEBT": line(0.5e9),
        "LONG_TERM_DEBT": line(1.2e9), "CURRENT_PORTION_LTD": line(0.2e9),
        "NET_WORTH": line(2.8e9), "CFO": line(0.7e9)}}


st, _ = call("POST", f"/origination/api/applications/{ref}/spread",
             {"periods": [period("FY2024"), period("FY2023")]}, actor="analyst.user")
check("spread generated", st == 200, f"{st}")
st, _ = call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
check("spread confirmed", st == 200, f"{st}")

st, _ = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
check("rated", st == 200, f"{st}")
st, _ = call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
check("rating confirmed", st == 200, f"{st}")
call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")

call("POST", f"/decision/api/decisions/{ref}/covenants", {
    "covenantType": "FINANCIAL_MAINTENANCE", "metric": "DSCR", "operator": ">=", "threshold": 1.25,
    "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
    "breachSeverity": "MAJOR", "onBreach": ["notify_RM"]}, actor="analyst.user")
st, dec = call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops")
die_if_none("route", dec)
required = dec["requiredAuthority"]
st, dec = call("POST", f"/decision/api/decisions/{ref}/decide",
               {"outcome": "CONDITIONAL_APPROVE", "role": required,
                "rationale": "Strong coverage; standard conditions.",
                "conditions": ["Maintain DSCR >= 1.25x", "Charge over plant & machinery"]}, actor="cro")
die_if_none("decide", dec)
check("decision recorded (DECIDED)", st == 200 and dec.get("status") == "DECIDED", f"{st} {dec}")

# ------------------------------------------------------------------ artifacts
print("== docpdf 1. Generate credit proposal + document + sanction letter ==")
st, prop = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", actor="analyst.user")
die_if_none("proposal generate", prop)
check("credit proposal generated", st == 200 and prop.get("version") >= 1, f"{st}")

st, gendoc = call("POST", f"/decision/api/docs/applications/{ref}/generate",
                  {"templateKey": "FACILITY_AGREEMENT", "variables": {}}, actor="cad.officer")
die_if_none("doc generate", gendoc)
check("document generated in DRAFT", st == 200 and gendoc.get("status") == "DRAFT", f"{st}")
doc_id = gendoc["id"]
st, confd = call("POST", f"/decision/api/docs/{doc_id}/confirm", {"comment": "wording ok"}, actor="credit.officer")
check("document confirmed (human confirm-lock)", st == 200 and confd.get("status") == "CONFIRMED", f"{st}")

st, letter = call("POST", f"/decision/api/decisions/{ref}/sanction-letter", actor="cad.officer")
die_if_none("sanction letter", letter)
check("sanction letter generated (DRAFT)", st == 200 and letter.get("status") == "DRAFT", f"{st}")
sanction_id = letter["id"]

# ------------------------------------------------------------------ print: confirmed document
print("== docpdf 2. Print endpoint — confirmed generated document ==")
st, page = call_text(f"/decision/api/docs/{doc_id}/print", actor="cad.officer")
check("document print returns 200", st == 200, f"{st}")
is_pdf = page.startswith("%PDF")
is_html = page.lstrip().startswith("<!DOCTYPE html")
check("document print is a standalone document (PDF magic bytes or standalone HTML)", is_pdf or is_html,
      page[:60])
if is_html:
    check("print carries the Helix letterhead governance chrome",
          "HELIX BANK" in page and "DETERMINISTIC FIGURES PRESERVED VERBATIM" in page, "")
    check("confirmed document print shows HUMAN-CONFIRMED provenance",
          "HUMAN-CONFIRMED" in page, "")
    check("print embeds @media print styling for page-to-PDF",
          "@media print" in page and "@page" in page, "")
    # authoritative content reproduced verbatim: the whole document body is embedded.
    check("print reproduces the authoritative document body verbatim",
          confd["html"] in page, "doc body not embedded verbatim")
    check("print carries the borrower name from the authoritative doc",
          "Cascade Print Foods" in page, "")

# ------------------------------------------------------------------ print: credit proposal
print("== docpdf 3. Print endpoint — credit proposal ==")
st, ppage = call_text(f"/decision/api/decisions/{ref}/credit-proposal/print", actor="analyst.user")
check("proposal print returns 200", st == 200, f"{st}")
p_html = ppage.lstrip().startswith("<!DOCTYPE html")
check("proposal print is a standalone document", ppage.startswith("%PDF") or p_html, ppage[:60])
if p_html:
    check("proposal print reproduces the authoritative proposal body verbatim",
          prop["html"] in ppage, "proposal body not embedded verbatim")
    check("proposal print carries grounded governance provenance",
          "QUOTED VERBATIM" in ppage and "AI cannot approve" in ppage, "")

# ------------------------------------------------------------------ print: sanction letter (a generated document)
print("== docpdf 4. Print endpoint — sanction letter ==")
st, spage = call_text(f"/decision/api/docs/{sanction_id}/print", actor="cad.officer")
check("sanction-letter print returns 200", st == 200, f"{st}")
s_html = spage.lstrip().startswith("<!DOCTYPE html")
check("sanction-letter print is a standalone document", spage.startswith("%PDF") or s_html, spage[:60])
if s_html:
    check("sanction-letter print reproduces the authoritative letter body verbatim",
          letter["html"] in spage, "letter body not embedded verbatim")
    check("DRAFT sanction letter print flags advisory + awaiting-confirm",
          "AI-DRAFTED" in spage and "awaiting HUMAN CONFIRM" in spage, "")

# ------------------------------------------------------------------ invariant: render mutates nothing
print("== docpdf 5. Governance invariant — render does not mutate the source artifact ==")
# Confirmed document must be byte-identical before/after a render (incl. @UpdateTimestamp).
st, doc_before = call("GET", f"/decision/api/docs/{doc_id}")
call_text(f"/decision/api/docs/{doc_id}/print", actor="auditor.user")
call_text(f"/decision/api/docs/{doc_id}/print", actor="another.user")
st, doc_after = call("GET", f"/decision/api/docs/{doc_id}")
check("generated document byte-identical after render(s)",
      json.dumps(doc_before, sort_keys=True) == json.dumps(doc_after, sort_keys=True),
      "document mutated by render")
check("document still CONFIRMED (confirm-lock intact after render)",
      doc_after.get("status") == "CONFIRMED", str(doc_after.get("status")))

# Credit proposal must be byte-identical before/after a render.
st, prop_before = call("GET", f"/decision/api/decisions/{ref}/credit-proposal")
prop_ver_before = prop_before.get("version")
call_text(f"/decision/api/decisions/{ref}/credit-proposal/print", actor="auditor.user")
st, prop_after = call("GET", f"/decision/api/decisions/{ref}/credit-proposal")
check("credit proposal byte-identical after render",
      json.dumps(prop_before, sort_keys=True) == json.dumps(prop_after, sort_keys=True),
      "proposal mutated by render")
check("proposal version unchanged (no new version minted by render)",
      prop_after.get("version") == prop_ver_before, f"{prop_ver_before} -> {prop_after.get('version')}")

# ------------------------------------------------------------------ 404s
print("== docpdf 6. Unknown artifacts 404 (no partial page) ==")
st, _ = call_text("/decision/api/docs/999999999/print")
check("print of unknown document -> 404", st == 404, f"{st}")
st, _ = call_text("/decision/api/decisions/APP-DOES-NOT-EXIST/credit-proposal/print")
check("print of proposal for unknown application -> 404", st == 404, f"{st}")

# ------------------------------------------------------------------ audit trail records the render
print("== docpdf 7. Render recorded in the append-only audit trail ==")
st, events = call("GET", f"/decision/api/audit/subject?type=GeneratedDocument&id={doc_id}")
rendered = [e for e in (events or []) if e.get("eventType") == "DOCUMENT_RENDERED"]
check("DOCUMENT_RENDERED audit event(s) present", st == 200 and len(rendered) >= 1, f"{st} {len(rendered)}")
check("render audit actor is the requesting human (X-Actor), not AI",
      all(e.get("actorType") == "HUMAN" for e in rendered) if rendered else False, str(rendered[:1]))

summary()
sys.exit(1 if FAIL else 0)
