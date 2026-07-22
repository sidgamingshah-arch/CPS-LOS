#!/usr/bin/env python3
"""End-to-end test for the FORMAT-SELECTABLE credit proposal (CAM formats).

The credit proposal is now assembled per a chosen CAM format's section layout,
resolved from a PROPOSAL_FORMAT master. This suite drives deals through the
existing lifecycle (counterparty -> spread -> rate -> price -> decide) and asserts:

  1. GET /api/decisions/proposal-formats lists the seeded formats (STANDARD +
     PROJECT_FINANCE + LRD + SCF + WORKING_CAPITAL + NBFC), each with a section list.
  2. Generating with an explicit format (PROJECT_FINANCE) returns a proposal whose
     persisted `sections` match that format's section list/order (incl. the
     segment-specific DSCR-waterfall section) and whose persisted `format` reflects it.
  3. Generating with NO format resolves the default STANDARD layout — byte-identical
     section set to the pre-existing universal proposal (behaviour-preserving); an
     explicit "STANDARD" is byte-identical to the no-format default.
  4. A deal in a segment with a matching format (segment=PROJECT_FINANCE) defaults to
     that format when no explicit format is passed.
  5. Governance invariant: the authoritative figures the proposal quotes (grade / PD /
     pricing / DSCR) are UNCHANGED by the format choice — the proposal is a rendering,
     not a figure source.
  6. The print endpoint still returns a standalone HTML document for the formatted proposal.

Runs through the gateway (HELIX_GATEWAY, default :8080). Additive — the empty-body
generate call keeps working (-> default STANDARD).
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
PASS, FAIL = 0, 0

# The pre-format universal proposal's section titles, in order. The STANDARD format
# must reproduce this EXACTLY (behaviour-preserving default).
STANDARD_SECTIONS = [
    "Executive summary", "Facilities proposed", "Collateral and security",
    "Financials", "Ratios", "Rating", "Capital projection", "Pricing",
    "Covenants", "Routing & decision", "Provenance",
]


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
    req = urllib.request.Request(GW + path, method="GET")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode(), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), e.headers


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
        print(f"\n{PASS} passed, {FAIL} failed")
        sys.exit(1)


def period(label):
    def line(v):
        return {"value": v, "sourceDocument": "PF_FY24.pdf", "sourcePage": "P12",
                "coordinates": "tbl1", "confidence": 0.97}
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(5e9), "COGS": line(3.2e9), "OPERATING_EXPENSES": line(0.9e9),
        "DEPRECIATION": line(0.2e9), "INTEREST_EXPENSE": line(0.15e9), "TAX": line(0.12e9),
        "TOTAL_ASSETS": line(6e9), "CURRENT_ASSETS": line(2.5e9), "CASH": line(0.6e9),
        "CURRENT_LIABILITIES": line(1.5e9), "SHORT_TERM_DEBT": line(0.5e9),
        "LONG_TERM_DEBT": line(1.2e9), "CURRENT_PORTION_LTD": line(0.2e9),
        "NET_WORTH": line(2.8e9), "CFO": line(0.7e9)}}


def setup_deal(name, reg_no, segment, decide=True):
    """Create -> screen -> KYC -> app -> spread -> rate -> capital -> price (+decide).
    Returns the application reference of a rated, priced deal."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": name, "legalForm": "PRIVATE_LTD", "registrationNo": reg_no,
        "jurisdiction": "IN-RBI", "segment": segment, "sector": "INFRASTRUCTURE", "country": "IN",
        "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    die_if_none("counterparty create", cp)
    cp_id = cp["id"]
    st, hits = call("POST", f"/counterparty/api/counterparties/{cp_id}/screening/run", actor="compliance.officer")
    for h in (hits or []):
        call("POST", f"/counterparty/api/counterparties/screening/{h['id']}/disposition",
             {"disposition": "FALSE_POSITIVE", "note": "no secondary identifier match"}, actor="compliance.officer")
    call("POST", f"/counterparty/api/counterparties/{cp_id}/kyc/verify", actor="compliance.officer")

    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp_id, "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": segment, "facilityType": "TERM_LOAN",
        "requestedAmount": 800_000_000, "currency": "INR", "tenorMonths": 60,
        "purpose": "Capacity expansion", "collateralType": "PROPERTY",
        "collateralValue": 600_000_000, "secured": True}, actor="rm.user")
    die_if_none("application create", app)
    ref = app["reference"]

    call("POST", f"/origination/api/applications/{ref}/spread",
         {"periods": [period("FY2024"), period("FY2023")]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    st, _ = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    check(f"[{segment}] rated", st == 200, f"{st}")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
    call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")
    call("POST", f"/decision/api/decisions/{ref}/covenants", {
        "covenantType": "FINANCIAL_MAINTENANCE", "metric": "DSCR", "operator": ">=", "threshold": 1.25,
        "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
        "breachSeverity": "MAJOR", "onBreach": ["notify_RM"]}, actor="analyst.user")
    if decide:
        st, dec = call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops")
        die_if_none("route", dec)
        required = dec["requiredAuthority"]
        call("POST", f"/decision/api/decisions/{ref}/decide",
             {"outcome": "CONDITIONAL_APPROVE", "role": required,
              "rationale": "Strong coverage; standard conditions.",
              "conditions": ["Maintain DSCR >= 1.25x"]}, actor="cro")
    return ref


# ------------------------------------------------------------------ setup
print("== proposal-format 0. Deal setup ==")
ref_a = setup_deal("Meridian Corporate Ltd", "U15100MH2011PTC880001", "MID_CORPORATE", decide=True)
ref_b = setup_deal("Skyline Project Finance Ltd", "U45100MH2012PTC880002", "PROJECT_FINANCE", decide=False)
print(f"   deal A (MID_CORPORATE) = {ref_a}")
print(f"   deal B (PROJECT_FINANCE) = {ref_b}")

# The authoritative DSCR the proposal must merely quote (never recompute).
st, env_a = call("GET", f"/origination/api/applications/{ref_a}/envelope")
die_if_none("envelope A", env_a)
dscr_a = (env_a.get("ratios") or {}).get("DSCR")
check("envelope A carries a DSCR figure to quote", dscr_a is not None, str(env_a.get("ratios")))

# ------------------------------------------------------------------ 1. list formats
print("== proposal-format 1. Available formats listing ==")
st, formats = call("GET", "/decision/api/decisions/proposal-formats")
die_if_none("proposal-formats", formats)
check("proposal-formats returns 200 + a list", st == 200 and isinstance(formats, list), f"{st}")
codes = {f["code"] for f in (formats or [])}
for expected in ["STANDARD", "PROJECT_FINANCE", "LRD", "SCF", "WORKING_CAPITAL", "NBFC"]:
    check(f"format catalogue includes {expected}", expected in codes, str(sorted(codes)))
pf_def = next((f for f in formats if f["code"] == "PROJECT_FINANCE"), None)
die_if_none("PROJECT_FINANCE format def", pf_def)
pf_titles = [s["title"] for s in pf_def["sections"]]
check("PROJECT_FINANCE format defines its section layout",
      len(pf_titles) >= 8 and "DSCR & Cashflow Waterfall" in pf_titles, str(pf_titles))
std_def = next((f for f in formats if f["code"] == "STANDARD"), None)
die_if_none("STANDARD format def", std_def)
check("STANDARD format section titles == the universal layout",
      [s["title"] for s in std_def["sections"]] == STANDARD_SECTIONS,
      str([s["title"] for s in std_def["sections"]]))

# segment-ranked listing: PROJECT_FINANCE recommended for that segment
st, formats_pf = call("GET", "/decision/api/decisions/proposal-formats?segment=PROJECT_FINANCE")
check("segment listing flags the segment default as recommended",
      st == 200 and any(f["code"] == "PROJECT_FINANCE" and f.get("recommended") for f in (formats_pf or [])),
      str([(f["code"], f.get("recommended")) for f in (formats_pf or [])]))

# ------------------------------------------------------------------ 2. explicit format
print("== proposal-format 2. Explicit format (PROJECT_FINANCE) shapes the layout ==")
st, prop_pf = call("POST", f"/decision/api/decisions/{ref_a}/credit-proposal/generate",
                   {"format": "PROJECT_FINANCE"}, actor="analyst.user")
die_if_none("PF proposal generate", prop_pf)
check("explicit-format proposal generated", st == 200 and prop_pf.get("version") >= 1, f"{st}")
check("persisted format == PROJECT_FINANCE", prop_pf.get("format") == "PROJECT_FINANCE", str(prop_pf.get("format")))
check("proposal sections match the PROJECT_FINANCE format list/order",
      prop_pf.get("sections") == pf_titles, f"{prop_pf.get('sections')} != {pf_titles}")
check("segment-specific DSCR-waterfall section rendered in the body",
      "DSCR & cashflow waterfall (advisory)" in prop_pf.get("markdown", ""), "waterfall heading missing")
check("PROJECT_FINANCE layout subsets out the collateral section (not universal)",
      "3. Collateral and security" not in prop_pf.get("markdown", ""), "collateral leaked into PF layout")

# ------------------------------------------------------------------ 3. no-format default == STANDARD (byte-identical)
print("== proposal-format 3. No-format default resolves STANDARD (behaviour-preserving) ==")
st, prop_std = call("POST", f"/decision/api/decisions/{ref_a}/credit-proposal/generate", actor="analyst.user")
die_if_none("default proposal generate", prop_std)
check("no-format generate still works (empty body)", st == 200 and prop_std.get("version") >= 1, f"{st}")
check("no-format resolves STANDARD", prop_std.get("format") == "STANDARD", str(prop_std.get("format")))
check("STANDARD sections == the pre-format universal layout (byte-identical set/order)",
      prop_std.get("sections") == STANDARD_SECTIONS, str(prop_std.get("sections")))
for marker in ["## 1. Executive summary", "## 2. Facilities proposed", "## 3. Collateral and security",
               "## Ratios", "## 10. Provenance and governance"]:
    check(f"universal body marker present: '{marker}'", marker in prop_std.get("markdown", ""), "marker missing")
# explicit STANDARD must be byte-identical to the no-format default (same markdown/html/sections)
st, prop_std2 = call("POST", f"/decision/api/decisions/{ref_a}/credit-proposal/generate",
                     {"format": "STANDARD"}, actor="analyst.user")
die_if_none("explicit STANDARD generate", prop_std2)
check("explicit STANDARD markdown == no-format default markdown (byte-identical)",
      prop_std2.get("markdown") == prop_std.get("markdown"), "markdown differs")
check("explicit STANDARD html == no-format default html (byte-identical)",
      prop_std2.get("html") == prop_std.get("html"), "html differs")

# ------------------------------------------------------------------ 4. segment default
print("== proposal-format 4. Deal segment defaults its matching format ==")
st, prop_b = call("POST", f"/decision/api/decisions/{ref_b}/credit-proposal/generate", actor="analyst.user")
die_if_none("deal B default generate", prop_b)
check("PROJECT_FINANCE-segment deal defaults to the PROJECT_FINANCE format (no explicit arg)",
      prop_b.get("format") == "PROJECT_FINANCE", str(prop_b.get("format")))
check("segment-defaulted proposal carries the DSCR-waterfall section",
      "DSCR & Cashflow Waterfall" in (prop_b.get("sections") or []), str(prop_b.get("sections")))

# ------------------------------------------------------------------ 5. governance: figures unchanged by format
print("== proposal-format 5. Governance — the format is a rendering, not a figure source ==")
st, rs_before = call("GET", f"/risk/api/risk/{ref_a}")
# regenerate under two different formats
call("POST", f"/decision/api/decisions/{ref_a}/credit-proposal/generate", {"format": "STANDARD"}, actor="analyst.user")
st, prop_pf2 = call("POST", f"/decision/api/decisions/{ref_a}/credit-proposal/generate",
                    {"format": "PROJECT_FINANCE"}, actor="analyst.user")
st, rs_after = call("GET", f"/risk/api/risk/{ref_a}")
check("authoritative rating unchanged across format choices (grade/PD)",
      json.dumps(rs_before.get("rating"), sort_keys=True) == json.dumps(rs_after.get("rating"), sort_keys=True),
      "rating mutated by proposal formatting")
check("authoritative pricing unchanged across format choices (rate/RAROC)",
      json.dumps(rs_before.get("pricing"), sort_keys=True) == json.dumps(rs_after.get("pricing"), sort_keys=True),
      "pricing mutated by proposal formatting")
# the SAME DSCR figure is quoted in the STANDARD (Ratios) and PF (waterfall) layouts
if dscr_a is not None:
    dscr_str = f"{dscr_a:.2f}"
    check("STANDARD layout quotes the authoritative DSCR verbatim",
          dscr_str in prop_std.get("markdown", ""), f"DSCR {dscr_str} not quoted in STANDARD")
    check("PROJECT_FINANCE waterfall quotes the SAME authoritative DSCR verbatim",
          dscr_str in prop_pf2.get("markdown", ""), f"DSCR {dscr_str} not quoted in PF waterfall")

# ------------------------------------------------------------------ 6. print endpoint on the formatted proposal
print("== proposal-format 6. Print endpoint renders the formatted proposal ==")
st, page, hdrs = call_text_full(f"/decision/api/decisions/{ref_a}/credit-proposal/print", actor="analyst.user")
check("proposal print returns 200", st == 200, f"{st}")
is_html = page.lstrip().startswith("<!DOCTYPE html")
check("proposal print is a standalone document (HTML or %PDF)", page.startswith("%PDF") or is_html, page[:60])
if is_html:
    # latest proposal on deal A is the PROJECT_FINANCE one from section 5
    check("print reproduces the (formatted) proposal body verbatim",
          prop_pf2["html"] in page, "formatted proposal body not embedded verbatim")

# ------------------------------------------------------------------ 7. non-persisting preview (compare surface)
print("== proposal-format 7. Preview renders a format WITHOUT persisting a version ==")
# The Credit-Proposal screen's side-by-side compare renders formats via a read-only preview so
# comparing never spams proposal versions. Assert the preview returns the format's sections/body
# but creates NO new version, while generate still persists.
st, versions_before = call("GET", f"/decision/api/decisions/{ref_a}/credit-proposal/versions")
n_before = len(versions_before or [])
check("version listing readable before preview", st == 200 and isinstance(versions_before, list), f"{st}")

st, prev_pf = call("GET", f"/decision/api/decisions/{ref_a}/credit-proposal/preview?format=PROJECT_FINANCE")
die_if_none("preview PROJECT_FINANCE", prev_pf)
check("preview returns 200", st == 200, f"{st}")
check("preview reflects the requested format", prev_pf.get("format") == "PROJECT_FINANCE", str(prev_pf.get("format")))
check("preview returns the PROJECT_FINANCE format's sections (same as the format def)",
      prev_pf.get("sections") == pf_titles, f"{prev_pf.get('sections')} != {pf_titles}")
check("preview body carries the segment-specific DSCR-waterfall section",
      "DSCR & cashflow waterfall (advisory)" in prev_pf.get("markdown", ""), "waterfall heading missing")
check("preview renders HTML + markdown bodies", bool(prev_pf.get("html")) and bool(prev_pf.get("markdown")), "empty render")
check("preview carries source citations (grounding)", isinstance(prev_pf.get("citations"), dict) and len(prev_pf["citations"]) > 0, str(prev_pf.get("citations")))
if dscr_a is not None:
    check("preview quotes the authoritative DSCR verbatim (figure is a rendering)",
          f"{dscr_a:.2f}" in prev_pf.get("markdown", ""), "DSCR not quoted in preview")

# A no-format preview resolves the STANDARD layout (behaviour-preserving), still non-persisting.
st, prev_std = call("GET", f"/decision/api/decisions/{ref_a}/credit-proposal/preview")
die_if_none("preview default", prev_std)
check("no-format preview resolves STANDARD", prev_std.get("format") == "STANDARD", str(prev_std.get("format")))
check("no-format preview sections == the universal layout", prev_std.get("sections") == STANDARD_SECTIONS, str(prev_std.get("sections")))

# CRITICAL: none of the previews above created a proposal version.
st, versions_mid = call("GET", f"/decision/api/decisions/{ref_a}/credit-proposal/versions")
n_mid = len(versions_mid or [])
check("preview created NO new proposal version (version count unchanged)",
      n_mid == n_before, f"before={n_before} after-preview={n_mid}")

# ...while generate DOES persist a new version (the real action still works).
st, gen = call("POST", f"/decision/api/decisions/{ref_a}/credit-proposal/generate",
               {"format": "PROJECT_FINANCE"}, actor="analyst.user")
die_if_none("generate after preview", gen)
st, versions_after = call("GET", f"/decision/api/decisions/{ref_a}/credit-proposal/versions")
n_after = len(versions_after or [])
check("generate DOES persist a new version (count increments by 1)",
      n_after == n_mid + 1, f"after-preview={n_mid} after-generate={n_after}")

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
