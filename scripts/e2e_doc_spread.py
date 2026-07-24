#!/usr/bin/env python3
"""
Doc-capture honesty + AI-EXTRACT -> GRID -> HUMAN-CONFIRM link — e2e (U-G).

Closes two demo-robustness gaps in the document-intelligence / spreading story:

  A. VARIED EXTRACTION — the deterministic doc-intel fallback (LLM provider=none) now derives
     its sample field VALUES from the borrower/segment/requested-amount (a stable per-deal hash),
     so extraction across different deals shows DIFFERENT, plausible figures instead of one canned
     constant. The field KEYS / confidences / model are unchanged (contract preserved).

  B. REAL, TRUTHFUL AI->GRID LINK — POST /api/applications/{ref}/spread/from-extraction maps a
     CONFIRMED DocExtraction's figure fields onto canonical INPUT taxonomy keys and rebuilds the
     working spread as an UNCONFIRMED DRAFT (source DOC_INTEL). The governance invariant is proved
     directly:
       * confirming a FINANCIAL_STATEMENT extraction AUTO-drafts the spread from it (advisory DRAFT,
         spreadConfirmed=false — the authoritative confirm-spread gate is untouched),
       * from-extraction lands a DRAFT (spreadConfirmed=false) — never auto-confirmed,
       * the authoritative gate (spreadConfirmed=true) is set ONLY by the separate spread/confirm,
       * re-running from-extraction always returns to DRAFT (never leaves an authoritative figure),
       * the source extraction row is byte-identical after populating the grid.

  C. GUARDS — an extraction from another deal -> 400; an unknown id -> 404; a non-financial
     (KYC) extraction with no figure fields -> 400 (nothing invented).

Correct-by-construction against the gateway on :8080 (this suite binds no ports). Registered in
run_regression immediately before e2e_lifecycle_rekyc.
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


def msg_of(b):
    return (b.get("message") if isinstance(b, dict) else str(b)) or ""


def fval(ext, key):
    f = (ext.get("fields") or {}).get(key)
    return f.get("value") if isinstance(f, dict) else f


def cell(analysis, key):
    for p in analysis.get("periods", []):
        for c in p.get("lines", []):
            if c.get("taxonomyKey") == key:
                return c
    return None


def make_deal(suffix, amount):
    """counterparty -> application; returns (counterparty, appRef). Unique per run."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"DocSpread {suffix} {TOKEN} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"DSPREAD{TOKEN}{suffix}", "jurisdiction": "IN-RBI",
        "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp " + suffix)
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    ref = must(st, app, "app " + suffix)["reference"]
    return cp, ref


def upload_extract(ref, filename, declared):
    st, doc = call("POST", f"/origination/api/applications/{ref}/documents",
                   {"fileName": filename, "declaredType": declared}, actor="analyst.user")
    doc = must(st, doc, "upload " + filename)
    st, ext = call("POST", f"/origination/api/doc-intel/documents/{doc['id']}/extract", actor="doc.intel")
    ext = must(st, ext, "extract " + filename)
    return doc, ext


FS_KEYS = {"reporting_period", "revenue", "ebitda", "total_debt", "auditor"}

# ============================================================ A. varied extraction per deal
print("\n== A. Deterministic extraction VALUES vary per borrower/segment (contract keys unchanged) ==")
# Revenue ranges are disjoint (A in [480M,1.11B], B in [3.2B,7.4B]) so a collision is impossible.
cpA, refA = make_deal("A", 300_000_000)
cpB, refB = make_deal("B", 2_000_000_000)
_, extA = upload_extract(refA, f"DocSpreadA_{TOKEN}_FY2025_financials.pdf", "FINANCIAL_STATEMENT")
_, extB = upload_extract(refB, f"DocSpreadB_{TOKEN}_FY2025_financials.pdf", "FINANCIAL_STATEMENT")

check("A: extraction is a SUGGESTED advisory row (human confirm required)",
      extA["status"] == "SUGGESTED" and extA["model"] == "doc-intel-v1", str(extA.get("status")))
check("A: FS extraction carries the canonical field KEYS (contract preserved)",
      FS_KEYS.issubset(set((extA.get("fields") or {}).keys())), str(list((extA.get("fields") or {}).keys())))
check("A: extraction confidence is advisory, > 0 (not auto-applied)", extA["overallConfidence"] > 0, str(extA.get("overallConfidence")))

revA, revB = fval(extA, "revenue"), fval(extB, "revenue")
check("two different deals extract DIFFERENT revenue (values vary, not one canned constant)",
      revA is not None and revB is not None and revA != revB, f"A={revA} B={revB}")
check("neither deal returns the old hardcoded 1,250,000,000 constant",
      revA != 1_250_000_000 and revB != 1_250_000_000, f"A={revA} B={revB}")
check("extracted ebitda/total_debt also differ across the two deals",
      fval(extA, "ebitda") != fval(extB, "ebitda") and fval(extA, "total_debt") != fval(extB, "total_debt"),
      f"A=({fval(extA,'ebitda')},{fval(extA,'total_debt')}) B=({fval(extB,'ebitda')},{fval(extB,'total_debt')})")

# ============================================================ B. AI-extract -> DRAFT grid -> human confirm
print("\n== B. AI-EXTRACT -> GRID -> HUMAN-CONFIRM (extraction never auto-applies; gate on confirm) ==")

st, an0 = call("GET", f"/origination/api/applications/{refA}/analysis")
an0 = must(st, an0, "analysis(A) pre-spread")
check("fresh deal has NO spread yet (grid empty)", an0.get("periods") == [] and an0["spreadConfirmed"] is False,
      f"periods={len(an0.get('periods') or [])} confirmed={an0.get('spreadConfirmed')}")

# from-extraction BEFORE the extraction is confirmed -> 400 (needs a CONFIRMED extraction)
st, r = call("POST", f"/origination/api/applications/{refA}/spread/from-extraction", {}, actor="analyst.user")
check("from-extraction refuses when no CONFIRMED extraction exists -> 400", st == 400, f"{st} {r}")
check("...400 explains a confirmed extraction is required",
      "confirm" in msg_of(r).lower(), msg_of(r))

# confirm the EXTRACTION (doc-intel review) — this records review accountability ONLY
st, cext = call("POST", f"/origination/api/doc-intel/extractions/{extA['id']}/confirm",
                {"note": "reviewed for spread pre-fill"}, actor="analyst.user")
cext = must(st, cext, "confirm extraction A")
check("extraction confirmed (CONFIRMED, records the human reviewer)",
      cext["status"] == "CONFIRMED" and cext["reviewedBy"] == "analyst.user", str(cext.get("status")))

st, an1 = call("GET", f"/origination/api/applications/{refA}/analysis")
an1 = must(st, an1, "analysis(A) after extraction-confirm")
# AI LARGER ROLE: confirming a FINANCIAL_STATEMENT extraction now AUTO-drafts the spread from it.
# It lands as an UNCONFIRMED DRAFT (advisory); the authoritative confirm-spread gate is untouched.
check("confirming a FINANCIAL_STATEMENT extraction AUTO-drafts a spread (advisory, not review-only)",
      len(an1.get("periods") or []) > 0, f"periods={len(an1.get('periods') or [])}")
check("the auto-drafted spread is an UNCONFIRMED DRAFT (never auto-confirmed)",
      an1.get("spreadConfirmed") is False, str(an1.get("spreadConfirmed")))
an1_rev = cell(an1, "REVENUE")
check("the auto-drafted DRAFT REVENUE equals the extracted revenue (figure carried through)",
      an1_rev is not None and abs(an1_rev["value"] - float(revA)) < 1.0, f"cell={an1_rev} rev={revA}")

# re-running from-extraction explicitly still returns a DRAFT (idempotent-advisory)
st, draft = call("POST", f"/origination/api/applications/{refA}/spread/from-extraction", {}, actor="analyst.user")
draft = must(st, draft, "from-extraction A")
check("from-extraction produces a spread that is a DRAFT (spreadConfirmed=false, never auto-confirmed)",
      draft["spreadConfirmed"] is False, str(draft.get("spreadConfirmed")))
rev_cell = cell(draft, "REVENUE")
ltd_cell = cell(draft, "LONG_TERM_DEBT")
td_cell = cell(draft, "TOTAL_DEBT")
check("the DRAFT REVENUE line equals the extracted revenue (figure carried through faithfully)",
      rev_cell is not None and abs(rev_cell["value"] - float(revA)) < 1.0, f"cell={rev_cell} rev={revA}")
check("extracted total_debt seeded the LONG_TERM_DEBT input line (analyst splits before confirm)",
      ltd_cell is not None and abs(ltd_cell["value"] - float(fval(extA, "total_debt"))) < 1.0, f"cell={ltd_cell}")
check("the DERIVED TOTAL_DEBT is computed by the engine (never seeded), == long-term debt here",
      td_cell is not None and td_cell["derived"] is True and abs(td_cell["value"] - ltd_cell["value"]) < 1.0, f"cell={td_cell}")

# the source extraction row is byte-identical after populating the grid
st, exlist = call("GET", f"/origination/api/doc-intel/documents/{extA['documentId']}/extractions")
exlist = must(st, exlist, "extractions(A)")
same = next((e for e in exlist if e["id"] == extA["id"]), None)
check("the source extraction row is unchanged after populating the grid (still CONFIRMED, same fields)",
      same is not None and same["status"] == "CONFIRMED" and same["fields"] == cext["fields"], str(same and same.get("status")))

# authoritative gate is set ONLY by the separate spread/confirm
st, conf = call("POST", f"/origination/api/applications/{refA}/spread/confirm", actor="analyst.user")
conf = must(st, conf, "confirm spread A")
check("the authoritative figure is set only on the separate human spread/confirm (spreadConfirmed=true)",
      conf["spreadConfirmed"] is True, str(conf.get("spreadConfirmed")))

# re-running from-extraction ALWAYS returns to DRAFT (it can never leave an authoritative figure)
st, redraft = call("POST", f"/origination/api/applications/{refA}/spread/from-extraction", {}, actor="analyst.user")
redraft = must(st, redraft, "re-run from-extraction A")
check("re-running from-extraction resets the deal to DRAFT (advisory, never authoritative)",
      redraft["spreadConfirmed"] is False, str(redraft.get("spreadConfirmed")))

# audit: SPREAD_DRAFTED_FROM_EXTRACTION stamped as an AI event; DOC_EXTRACTED present
st, aud = call("GET", f"/origination/api/audit/subject?type=Application&id={refA}")
aud = must(st, aud, "audit subject A")
drafted = [e for e in aud if e.get("eventType") == "SPREAD_DRAFTED_FROM_EXTRACTION"]
check("populating the grid stamped an AI SPREAD_DRAFTED_FROM_EXTRACTION audit event",
      any(e.get("actorType") == "AI" for e in drafted), str(drafted[:1]))
check("the AI event records it stayed advisory (spreadConfirmed=false in the detail)",
      any((e.get("detail") or {}).get("spreadConfirmed") is False for e in drafted), str(drafted[:1]))
check("the deal also carries the DOC_EXTRACTED AI event (extraction provenance)",
      any(e.get("eventType") == "DOC_EXTRACTED" and e.get("actorType") == "AI" for e in aud), "")

# ============================================================ C. guards
print("\n== C. Guards: cross-deal / unknown id / non-financial extraction ==")
# confirm B's extraction, then try to use it on deal A -> 400 (belongs-to guard)
st, cextB = call("POST", f"/origination/api/doc-intel/extractions/{extB['id']}/confirm", {}, actor="analyst.user")
must(st, cextB, "confirm extraction B")
st, r = call("POST", f"/origination/api/applications/{refA}/spread/from-extraction",
             {"extractionId": extB["id"]}, actor="analyst.user")
check("an extraction from another deal is rejected -> 400", st == 400, f"{st} {r}")
check("...400 says it does not belong to this application", "does not belong" in msg_of(r).lower(), msg_of(r))

st, r = call("POST", f"/origination/api/applications/{refA}/spread/from-extraction",
             {"extractionId": 999999999}, actor="analyst.user")
check("an unknown extraction id -> 404", st == 404, f"{st} {r}")

# a KYC extraction has no figure fields -> 400 (nothing invented)
cpC, refC = make_deal("C", 500_000_000)
_, extC = upload_extract(refC, f"DocSpreadC_{TOKEN}_kyc_id.pdf", "KYC_ID")
st, cextC = call("POST", f"/origination/api/doc-intel/extractions/{extC['id']}/confirm", {}, actor="analyst.user")
must(st, cextC, "confirm extraction C (kyc)")
st, r = call("POST", f"/origination/api/applications/{refC}/spread/from-extraction", {}, actor="analyst.user")
check("a non-financial (KYC) extraction has no mappable figure fields -> 400 (nothing invented)",
      st == 400 and "figure fields" in msg_of(r).lower(), f"{st} {msg_of(r)}")
st, anC = call("GET", f"/origination/api/applications/{refC}/analysis")
anC = must(st, anC, "analysis(C)")
check("KYC deal grid stays empty after the rejected populate (the failed populate created nothing)",
      anC.get("periods") == [], str(len(anC.get("periods") or [])))

print(f"\n== Doc-capture honesty + AI->grid e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
