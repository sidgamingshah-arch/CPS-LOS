#!/usr/bin/env python3
"""
Financial extraction AGAINST the FINANCIAL_TEMPLATE master + a larger AI role in the
extraction → draft flow — e2e (batch4/financials-template).

What this proves (all through the gateway on :8080; this suite binds no ports and is
NOT registered in run_regression — run it standalone against a rebuilt stack):

  1. TEMPLATE-DRIVEN MAPPING — the resolved FINANCIAL_TEMPLATE master now carries the
     extraction-field → canonical-key mapping (payload.extractionMap). The doc-intel →
     spread bridge maps against THAT governed master (mappingSource=TEMPLATE_MASTER),
     not a hard-coded table. The built-in default remains the per-field fallback so
     existing spreads are byte-identical.

  2. AI LARGER ROLE (auto-classify+extract on upload, auto-draft on confirm) —
       * uploading a document CHAINS classify → extract, so a SUGGESTED extraction
         exists without a separate manual "Extract" click;
       * confirming a FINANCIAL_STATEMENT extraction AUTO-drafts a spread from it — it
         lands as an UNCONFIRMED DRAFT (spreadConfirmed=false, source DOC_INTEL) whose
         REVENUE equals the extracted revenue;
       * re-running from-extraction always returns a DRAFT (never authoritative).

  3. THE ADVISORY INVARIANT IS INTACT — the auto-draft NEVER touches an authoritative
     figure: on a deal that already carries a CONFIRMED spread + rating, confirming a
     further FS extraction is GUARDED (no auto-draft), so the authoritative confirmed
     spread REVENUE + confirm flag and the authoritative rating are BYTE-IDENTICAL
     before vs after. Confirming the extraction alone never flips the confirmed spread.
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
TOKEN = str(int(time.time() * 1000))[-9:]


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


def fval(ext, key):
    f = (ext.get("fields") or {}).get(key)
    return f.get("value") if isinstance(f, dict) else f


def cell(analysis, key):
    for p in analysis.get("periods", []):
        for c in p.get("lines", []):
            if c.get("taxonomyKey") == key:
                return c
    return None


def line(v):
    return {"value": v, "sourceDocument": "fs.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def make_deal(suffix, amount, segment="MID_CORPORATE", sector="SERVICES"):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"FinTmpl {suffix} {TOKEN} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"FTMPL{TOKEN}{suffix}", "jurisdiction": "IN-RBI",
        "segment": segment, "sector": sector, "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp " + suffix)
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": segment, "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    return cp, must(st, app, "app " + suffix)["reference"]


def rated_deal(suffix, amount):
    """cp -> app -> manual spread -> confirm -> rate -> rating confirm; returns ref."""
    _, ref = make_deal(suffix, amount)
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
    c = cell(a, "REVENUE")
    return {"spreadConfirmed": a.get("spreadConfirmed"), "revenue": c.get("value") if c else None}


def suggested_fs_extraction(ref, doc_id):
    """Return the newest SUGGESTED extraction for a document (created by auto-extract on upload)."""
    st, exs = call("GET", f"/origination/api/doc-intel/documents/{doc_id}/extractions")
    exs = must(st, exs, "extractions " + ref)
    for e in exs:               # findByDocumentIdOrderByIdDesc -> newest first
        if e.get("status") == "SUGGESTED":
            return e
    return exs[0] if exs else None


# ============================================================ 1. template carries the extraction map
print("== 1. Resolved FINANCIAL_TEMPLATE carries the extraction mapping (governed master) ==")
st, rec = call("GET", "/config/api/financial-templates/resolve?jurisdiction=IN-RBI&segment=MID_CORPORATE")
rec = must(st, rec, "resolve fin-default")
payload = rec.get("payload") or {}
emap = payload.get("extractionMap") or {}
check("MID_CORPORATE/SERVICES resolves the default chart", payload.get("templateKey") == "fin-default",
      str(payload.get("templateKey")))
check("the resolved template carries an extractionMap (extraction happens against the master)",
      isinstance(emap, dict) and len(emap) > 0, str(list(emap)[:6]))
check("extractionMap maps revenue/turnover → REVENUE",
      emap.get("revenue") == "REVENUE" and emap.get("turnover") == "REVENUE", str({k: emap.get(k) for k in ("revenue", "turnover")}))
check("extractionMap maps total_debt → LONG_TERM_DEBT (analyst splits before confirm) and cfo → CFO",
      emap.get("total_debt") == "LONG_TERM_DEBT" and emap.get("cfo") == "CFO",
      str({k: emap.get(k) for k in ("total_debt", "cfo")}))


# ============================================================ 2. auto-extract on upload + auto-draft on confirm
print("\n== 2. Upload auto-extracts; confirming a FS extraction AUTO-drafts a DRAFT spread ==")
cp1, ref1 = make_deal("A", 300_000_000)

# fresh deal has no spread yet
st, an0 = call("GET", f"/origination/api/applications/{ref1}/analysis")
an0 = must(st, an0, "analysis pre-upload")
check("fresh deal has NO spread yet", an0.get("periods") == [] and an0.get("spreadConfirmed") is False,
      f"periods={len(an0.get('periods') or [])}")

# upload ONLY (no manual extract) — the upload must chain classify -> extract
st, doc = call("POST", f"/origination/api/applications/{ref1}/documents",
               {"fileName": f"FinTmplA_{TOKEN}_FY2025_financials.pdf", "declaredType": "FINANCIAL_STATEMENT"},
               actor="analyst.user")
doc = must(st, doc, "upload FS (deal A)")
ext1 = suggested_fs_extraction(ref1, doc["id"])
check("upload auto-created a SUGGESTED extraction (classify→extract chained, no manual click)",
      ext1 is not None and ext1.get("status") == "SUGGESTED", str(ext1 and ext1.get("status")))
ex_rev = fval(ext1, "revenue")
ex_debt = fval(ext1, "total_debt")
check("auto-extraction carries a numeric revenue figure", isinstance(ex_rev, (int, float)) and ex_rev > 0, f"revenue={ex_rev!r}")

# confirming the extraction AUTO-drafts the spread
st, conf1 = call("POST", f"/origination/api/doc-intel/extractions/{ext1['id']}/confirm",
                 {"note": "verified for auto-draft"}, actor="analyst.user")
conf1 = must(st, conf1, "confirm extraction A")
check("extraction confirmed (CONFIRMED, records the reviewer)",
      conf1.get("status") == "CONFIRMED" and conf1.get("reviewedBy") == "analyst.user", str(conf1.get("status")))

st, an1 = call("GET", f"/origination/api/applications/{ref1}/analysis")
an1 = must(st, an1, "analysis after extraction-confirm")
check("confirming a FINANCIAL_STATEMENT extraction AUTO-produced a spread",
      len(an1.get("periods") or []) > 0, f"periods={len(an1.get('periods') or [])}")
check("the auto-drafted spread is an UNCONFIRMED DRAFT (spreadConfirmed=false — gate untouched)",
      an1.get("spreadConfirmed") is False, str(an1.get("spreadConfirmed")))
rev_cell = cell(an1, "REVENUE")
check("the DRAFT REVENUE equals the extracted revenue (figure carried through faithfully)",
      rev_cell is not None and abs(rev_cell["value"] - float(ex_rev)) < 1.0, f"cell={rev_cell} rev={ex_rev}")
ltd_cell = cell(an1, "LONG_TERM_DEBT")
check("extracted total_debt seeded LONG_TERM_DEBT via the template mapping",
      ltd_cell is not None and abs(ltd_cell["value"] - float(ex_debt)) < 1.0, f"cell={ltd_cell} debt={ex_debt}")

# version timeline source is DOC_INTEL
st, vers = call("GET", f"/origination/api/applications/{ref1}/spread/versions")
vers = must(st, vers, "spread versions A")
latest = max(vers, key=lambda v: v["versionNo"]) if vers else {}
check("the auto-drafted spread version is source DOC_INTEL",
      latest.get("source") == "DOC_INTEL", str(latest.get("source")))

# audit: SPREAD_DRAFTED_FROM_EXTRACTION AI event, mapped against the TEMPLATE master
st, aud = call("GET", f"/origination/api/audit/subject?type=Application&id={ref1}")
aud = must(st, aud, "audit subject A")
drafted = [e for e in aud if e.get("eventType") == "SPREAD_DRAFTED_FROM_EXTRACTION"]
check("auto-draft stamped an AI SPREAD_DRAFTED_FROM_EXTRACTION event",
      any(e.get("actorType") == "AI" for e in drafted), str(drafted[:1]))
check("the AI event records the mapping came from the TEMPLATE master (fin-default)",
      any((e.get("detail") or {}).get("mappingSource") == "TEMPLATE_MASTER"
          and (e.get("detail") or {}).get("financialTemplate") == "fin-default" for e in drafted),
      str([e.get("detail") for e in drafted][:1]))


# ============================================================ 3. re-running from-extraction returns a DRAFT
print("\n== 3. Re-running from-extraction always returns a DRAFT (never authoritative) ==")
st, redraft = call("POST", f"/origination/api/applications/{ref1}/spread/from-extraction", {}, actor="analyst.user")
redraft = must(st, redraft, "re-run from-extraction A")
check("re-running from-extraction resets to DRAFT (spreadConfirmed=false)",
      redraft.get("spreadConfirmed") is False, str(redraft.get("spreadConfirmed")))


# ============================================================ 4. INVARIANT — auto-draft never touches an authoritative spread
print("\n== 4. GUARD: a confirmed extraction never clobbers an already-CONFIRMED spread/rating ==")
ref2 = rated_deal("B", 40_000_000)
rating_before = rating_snapshot(ref2)
spread_before = spread_snapshot(ref2)
check("deal B starts with a CONFIRMED spread + rating",
      spread_before and spread_before["spreadConfirmed"] is True and rating_before and rating_before["finalGrade"],
      f"{spread_before} {rating_before}")

# upload a FS doc (auto-extracts) and confirm it — the guard must SKIP the auto-draft
st, doc2 = call("POST", f"/origination/api/applications/{ref2}/documents",
                {"fileName": f"FinTmplB_{TOKEN}_FY2025_financials.pdf", "declaredType": "FINANCIAL_STATEMENT"},
                actor="analyst.user")
doc2 = must(st, doc2, "upload FS (deal B)")
ext2 = suggested_fs_extraction(ref2, doc2["id"])
check("deal B upload also auto-extracted a SUGGESTED row", ext2 is not None and ext2.get("status") == "SUGGESTED",
      str(ext2 and ext2.get("status")))
st, conf2 = call("POST", f"/origination/api/doc-intel/extractions/{ext2['id']}/confirm", {}, actor="analyst.user")
conf2 = must(st, conf2, "confirm extraction B")
check("extraction B confirmed", conf2.get("status") == "CONFIRMED", str(conf2.get("status")))

rating_after = rating_snapshot(ref2)
spread_after = spread_snapshot(ref2)
check("confirming the EXTRACTION alone never flipped the authoritative confirmed spread",
      spread_after and spread_after["spreadConfirmed"] is True, str(spread_after))
check("ADVISORY INVARIANT: confirmed-spread REVENUE + confirm flag byte-identical before vs after the auto-draft",
      spread_before == spread_after, f"{spread_before} vs {spread_after}")
check("ADVISORY INVARIANT: authoritative rating byte-identical before vs after the auto-draft",
      rating_before == rating_after, f"{rating_before} vs {rating_after}")


print(f"\n== financials-template (extraction-against-master + AI draft) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
