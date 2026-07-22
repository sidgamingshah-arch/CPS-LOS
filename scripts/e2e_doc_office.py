#!/usr/bin/env python3
"""End-to-end test for the Word / Excel / CSV office-output rendering path (CLoM F32 · U12).

The print endpoints for the credit proposal and generated documents already served a
print-optimised standalone HTML page (browser print-to-PDF). This suite exercises the
additive ?format= query param that re-emits the SAME authoritative artifact body as:

  * ?format=rtf   -> a Word-openable RTF document (starts with '{\\rtf');
  * ?format=xlsx  -> a SpreadsheetML 2003 XML workbook (Excel-openable, no OOXML lib):
                     starts with '<?xml' and carries the Workbook spreadsheet-namespace root;
  * ?format=csv   -> the tabular content as CSV (OWASP formula-injection guarded);
  * (no format / html) -> the pre-existing standalone HTML, byte-identical.

It drives a deal through the existing lifecycle to a DECIDED (conditional-approve) state,
generates the credit proposal + a facility-agreement document, and asserts:

  * each format renders with the right Content-Type + attachment Content-Disposition;
  * rendering does NOT mutate the source artifact — the credit proposal (version + content)
    and the generated document are byte-identical before and after all renders (a render is
    a pure, read-only projection; the only side effect is an append-only audit event);
  * a DOCUMENT_RENDERED audit event (actorType HUMAN, the X-Actor) is stamped;
  * human/AI free text is escaped per format — an injected <script> is inert and a
    formula-trigger cell (=…) is defanged in CSV;
  * unknown id / reference -> 404, an unsupported format -> 400.

Runs through the gateway (HELIX_GATEWAY, default :8080). Additive — does not touch the
existing docgen / proposal / HTML-print behaviour.
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
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


def call_text_full(path, actor="test.user"):
    """GET a non-JSON endpoint; returns (status, raw_text, headers). headers.get() is case-insensitive."""
    req = urllib.request.Request(GW + path, method="GET")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode("utf-8", "replace"), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), e.headers


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


def ct(hdrs):
    return (hdrs.get("Content-Type") or "").lower()


def cd(hdrs):
    return hdrs.get("Content-Disposition") or ""


# ------------------------------------------------------------------ deal setup
print("== doc-office 0. Deal setup (counterparty -> rated -> decided) ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Meridian Office Docs Pvt Ltd", "legalForm": "PRIVATE_LTD",
    "registrationNo": "U15100MH2019PTC909112", "jurisdiction": "IN-RBI",
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
        return {"value": v, "sourceDocument": "Meridian_FY24.pdf", "sourcePage": "P12",
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
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
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
                "conditions": ["Maintain DSCR >= 1.25x"]}, actor="cro")
die_if_none("decide", dec)
check("decision recorded (DECIDED)", st == 200 and dec.get("status") == "DECIDED", f"{st} {dec}")

# ------------------------------------------------------------------ 1. credit proposal office output
print("== doc-office 1. Credit proposal — Word / Excel / CSV output ==")
st, prop = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", actor="analyst.user")
die_if_none("proposal generate", prop)
check("credit proposal generated", st == 200 and prop.get("version") >= 1, f"{st}")

PP = f"/decision/api/decisions/{ref}/credit-proposal/print"

# HTML default still works and is a standalone document (behaviour-preserving).
st, html_page, hh = call_text_full(PP, actor="analyst.user")
check("proposal default (no format) still returns standalone HTML",
      st == 200 and html_page.lstrip().startswith("<!DOCTYPE html"), f"{st} {html_page[:40]}")
check("proposal HTML print reproduces the authoritative body verbatim",
      prop["html"] in html_page, "proposal body not embedded verbatim")

# RTF
st, rtf, rh = call_text_full(PP + "?format=rtf", actor="analyst.user")
check("proposal ?format=rtf returns 200", st == 200, f"{st}")
check("proposal RTF starts with {\\rtf", rtf.startswith("{\\rtf"), rtf[:40])
check("proposal RTF Content-Type is RTF", "application/rtf" in ct(rh), ct(rh))
check("proposal RTF is an attachment with an .rtf filename",
      "attachment" in cd(rh).lower() and ".rtf" in cd(rh).lower(), cd(rh))
check("proposal RTF carries the Helix letterhead + a quoted figure",
      "HELIX BANK" in rtf and "Meridian Office Docs" in rtf, "letterhead/borrower missing")

# XLSX (SpreadsheetML 2003 XML)
st, xlsx, xh = call_text_full(PP + "?format=xlsx", actor="analyst.user")
check("proposal ?format=xlsx returns 200", st == 200, f"{st}")
check("proposal XLSX starts with the XML prolog", xlsx.lstrip().startswith("<?xml"), xlsx[:40])
check("proposal XLSX carries the SpreadsheetML Workbook root",
      '<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"' in xlsx, xlsx[:200])
check("proposal XLSX Content-Type is an Excel type",
      "excel" in ct(xh) or "spreadsheet" in ct(xh), ct(xh))
check("proposal XLSX is an attachment with an .xls filename",
      "attachment" in cd(xh).lower() and ".xls" in cd(xh).lower(), cd(xh))
check("proposal XLSX carries String cells (no ss:Formula -> injection-immune)",
      'ss:Type="String"' in xlsx and "ss:Formula" not in xlsx, "unexpected formula cell")

# CSV
st, csv, ch = call_text_full(PP + "?format=csv", actor="analyst.user")
check("proposal ?format=csv returns 200", st == 200, f"{st}")
check("proposal CSV Content-Type is text/csv", "text/csv" in ct(ch), ct(ch))
check("proposal CSV is an attachment with a .csv filename",
      "attachment" in cd(ch).lower() and ".csv" in cd(ch).lower(), cd(ch))
check("proposal CSV carries comma-separated tabular content", "," in csv and len(csv.strip()) > 0, csv[:80])

# An unsupported format value -> 400 (not a silent HTML fallback).
st, _ = call("GET", PP + "?format=potato", actor="analyst.user")
check("proposal unsupported format -> 400", st == 400, f"{st}")

# ------------------------------------------------------------------ 2. proposal render mutates nothing
print("== doc-office 2. Governance invariant — proposal byte-identical after every office render ==")
st, prop_before = call("GET", f"/decision/api/decisions/{ref}/credit-proposal")
ver_before = prop_before.get("version")
for f in ("rtf", "xlsx", "csv", ""):
    q = ("?format=" + f) if f else ""
    call_text_full(PP + q, actor="auditor.user")
st, prop_after = call("GET", f"/decision/api/decisions/{ref}/credit-proposal")
check("credit proposal byte-identical after all renders",
      json.dumps(prop_before, sort_keys=True) == json.dumps(prop_after, sort_keys=True),
      "proposal mutated by render")
check("proposal version unchanged (no new version minted by a render)",
      prop_after.get("version") == ver_before, f"{ver_before} -> {prop_after.get('version')}")

# ------------------------------------------------------------------ 3. generated document office output + escaping
print("== doc-office 3. Generated document — Word / Excel / CSV output + escaping ==")
st, gendoc = call("POST", f"/decision/api/docs/applications/{ref}/generate",
                  {"templateKey": "FACILITY_AGREEMENT", "variables": {}}, actor="cad.officer")
die_if_none("doc generate", gendoc)
check("document generated in DRAFT", st == 200 and gendoc.get("status") == "DRAFT", f"{st}")
doc_id = gendoc["id"]

# Inject three free-text clauses, each probing one format's escaping discipline:
#   * a <script> payload (HTML/XML escaping),
#   * a leading spreadsheet-formula trigger as its OWN clause so it begins a CSV field
#     (a CSV-injection vector only when the FIELD starts with = + - @),
#   * RTF control chars (backslash + braces).
XSS = "<script>alert('pwn')</script>"
FORMULA = "=2+5+HACK"          # its own clause -> starts a CSV field
RTFPAY = "\\danger{X}"          # backslash + braces -> must be RTF control-word escaped
for cref, ctitle, ctext in (("injected_web", "Injected Web", XSS),
                            ("injected_formula", "Injected Formula", FORMULA),
                            ("injected_rtf", "Injected Rtf", RTFPAY)):
    st, xadd = call("POST", f"/decision/api/docs/{doc_id}/clauses",
                    {"clauseRef": cref, "customTitle": ctitle, "customText": ctext}, actor="cad.officer")
    check(f"custom clause '{cref}' added",
          st == 200 and cref in (xadd.get("clauseOrder") or []), f"{st}")
# The stored authoritative html escapes the <script> at the source (defense-in-depth).
st, docv = call("GET", f"/decision/api/docs/{doc_id}")
check("stored document html escapes the injected <script>",
      "&lt;script&gt;" in (docv.get("html") or "") and "<script>alert" not in (docv.get("html") or ""),
      "raw <script> in stored html")

DP = f"/decision/api/docs/{doc_id}/print"

# RTF — Word-openable; a <script> is inert *text* (RTF is not HTML), so we do not forbid the
# substring; we assert the RTF-special chars (\\ { }) are control-word escaped.
st, drtf, drh = call_text_full(DP + "?format=rtf", actor="cad.officer")
check("document ?format=rtf returns 200 and starts with {\\rtf",
      st == 200 and drtf.startswith("{\\rtf"), f"{st} {drtf[:30]}")
check("document RTF Content-Type + .rtf attachment",
      "application/rtf" in ct(drh) and ".rtf" in cd(drh).lower(), f"{ct(drh)} {cd(drh)}")
check("document RTF escapes injected backslash + braces (\\\\danger\\{X\\})",
      "\\\\danger" in drtf and "\\{X\\}" in drtf, "RTF control chars not escaped")

# XLSX
st, dxlsx, dxh = call_text_full(DP + "?format=xlsx", actor="cad.officer")
check("document ?format=xlsx returns SpreadsheetML",
      st == 200 and dxlsx.lstrip().startswith("<?xml")
      and '<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"' in dxlsx, f"{st}")
check("document XLSX escapes the injected <script> (inert, not raw)",
      "&lt;script&gt;" in dxlsx and "<script>alert" not in dxlsx, "raw <script> leaked into XLSX")

# CSV — the formula trigger must be defanged (OWASP CSV-injection guard).
st, dcsv, dch = call_text_full(DP + "?format=csv", actor="cad.officer")
check("document ?format=csv returns 200 (text/csv)", st == 200 and "text/csv" in ct(dch), f"{st} {ct(dch)}")
check("document CSV defangs the spreadsheet-formula trigger (prefixed with ')",
      ("'" + FORMULA) in dcsv, "formula trigger not defanged")
check("document CSV has no field that begins a line with a bare formula trigger",
      not any(ln.startswith(FORMULA) for ln in dcsv.splitlines()), "bare =… field present")

# ------------------------------------------------------------------ 4. document render mutates nothing
print("== doc-office 4. Governance invariant — document byte-identical after office renders ==")
st, doc_before = call("GET", f"/decision/api/docs/{doc_id}")
for f in ("rtf", "xlsx", "csv"):
    call_text_full(DP + "?format=" + f, actor="another.user")
st, doc_after = call("GET", f"/decision/api/docs/{doc_id}")
check("generated document byte-identical after office render(s)",
      json.dumps(doc_before, sort_keys=True) == json.dumps(doc_after, sort_keys=True),
      "document mutated by render")

# ------------------------------------------------------------------ 5. audit trail records the render
print("== doc-office 5. Renders recorded in the append-only audit trail (DOCUMENT_RENDERED, HUMAN) ==")
st, dev = call("GET", f"/decision/api/audit/subject?type=GeneratedDocument&id={doc_id}")
doc_rendered = [e for e in (dev or []) if e.get("eventType") == "DOCUMENT_RENDERED"]
check("document DOCUMENT_RENDERED audit event(s) present", st == 200 and len(doc_rendered) >= 1, f"{st} {len(doc_rendered)}")
check("document render audit actorType is HUMAN (the X-Actor)",
      all(e.get("actorType") == "HUMAN" for e in doc_rendered) if doc_rendered else False, str(doc_rendered[:1]))

st, pev = call("GET", f"/decision/api/audit/subject?type=Application&id={ref}")
prop_rendered = [e for e in (pev or []) if e.get("eventType") == "DOCUMENT_RENDERED"]
check("proposal office-render stamped a DOCUMENT_RENDERED audit event", st == 200 and len(prop_rendered) >= 1,
      f"{st} {len(prop_rendered)}")
check("proposal render audit actorType is HUMAN",
      all(e.get("actorType") == "HUMAN" for e in prop_rendered) if prop_rendered else False, str(prop_rendered[:1]))

# ------------------------------------------------------------------ 6. unknown id -> 404
print("== doc-office 6. Unknown artifacts -> 404 ==")
st, _ = call("GET", "/decision/api/docs/999999999/print?format=rtf")
check("office render of unknown document -> 404", st == 404, f"{st}")
st, _ = call("GET", "/decision/api/decisions/APP-DOES-NOT-EXIST/credit-proposal/print?format=xlsx")
check("office render of proposal for unknown application -> 404", st == 404, f"{st}")

summary()
sys.exit(1 if FAIL else 0)
