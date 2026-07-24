#!/usr/bin/env python3
"""
Proposal-richness — e2e for the expanded bank-CAM credit proposal (Wave-4).

The credit proposal now carries the richer sections a real committee memo needs, on top
of the original core layout. Section keys map to keyed builders in
CreditProposalService.renderSection(); a PROPOSAL_FORMAT master picks which keys render and
in what order. A new comprehensive FULL_CAM format includes them all. Every new section is
a RENDERING — grounded where the data exists (envelope / risk summary / local CAD·CP·
perfection·annexure records), advisory prose or a graceful "not available" line otherwise —
and NEVER produces or mutates an authoritative figure.

This suite proves:

  1. FULL_CAM is in the format catalogue and lists the new section keys/titles.
  2. Generating with FULL_CAM persists a proposal whose sections == the format list/order
     and whose body renders every new section heading (borrower background, industry outlook,
     financial trend, key risks & mitigants, security & perfection, account conduct,
     deviations, RAROC/profitability, ESG, conditions, recommendation, regulatory compliance).
  3. The MULTI-YEAR financial_trend renders a period-over-period table with >1 period when the
     spread has them (both period labels appear as columns), and quotes a spread figure verbatim.
  4. A section whose data is absent degrades gracefully (a short note), never a 500 — proven on
     a bare rated deal (no CAD case / CP register / perfection case / ESG annexure): FULL_CAM
     still generates 200 and the graceful lines appear.
  5. GOVERNANCE: generating the (much larger) FULL_CAM proposal does NOT mutate the deal's
     authoritative rating / pricing — byte-identical before/after (the safety contract).

Runs through the gateway (HELIX_GATEWAY, default :8080). NOT registered in run_regression —
run standalone against a running stack (needs config-service reseeded so FULL_CAM is present).
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
PASS, FAIL = 0, 0

# The new bank-CAM section headings the FULL_CAM body must render (deterministic content —
# each renders grounded facts or a graceful note even with NO AI narrative provider configured).
NEW_SECTION_HEADINGS = [
    "Borrower background & management (advisory)",
    "Industry outlook (advisory)",
    "Financial trend (multi-year)",
    "Key risks & mitigants (advisory)",
    "Security & perfection status",
    "Account conduct (banking summary)",
    "Deviations & justifications",
    "RAROC & profitability (advisory)",
    "ESG assessment (advisory)",
    "Conditions precedent & subsequent",
    "Recommendation",
    "Regulatory & exposure-norm compliance",
]


def call(method, path, body=None, actor="test.user"):
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
            return e.code, (json.loads(txt) if txt else None)
        except json.JSONDecodeError:
            return e.code, txt


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


def line(v):
    return {"value": v, "sourceDocument": "CAM_FY.pdf", "sourcePage": "P7", "coordinates": "t1", "confidence": 0.96}


def period(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount, periods):
    """cp -> app -> spread(periods) -> confirm -> rate -> confirm -> capital -> price. Returns app ref."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Richness {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"RICH{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    die_if_none("counterparty", cp)
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    die_if_none("application", app)
    ref = app["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": periods}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
    call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")
    return ref


# ------------------------------------------------------------------ setup
print("== proposal-sections 0. Deal setup (two-period spread) ==")
P24 = period("FY2024", 5.0e9, 3.0e9, 0.8e9, 0.12e9, 6.0e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9)
P23 = period("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9)
ref = rated_deal("A", 40_000_000, [P24, P23])
print(f"   deal = {ref}")

st, env = call("GET", f"/origination/api/applications/{ref}/envelope")
die_if_none("envelope", env)
periods_env = env.get("periodFinancials") or []
check("envelope now carries multi-period financials (>1 period)", len(periods_env) > 1,
      f"periodFinancials len={len(periods_env)}")
dscr = (env.get("ratios") or {}).get("DSCR")

# ------------------------------------------------------------------ 1. FULL_CAM in the catalogue
print("== proposal-sections 1. FULL_CAM format present with the new section keys ==")
st, formats = call("GET", "/decision/api/decisions/proposal-formats")
die_if_none("proposal-formats", formats)
full = next((f for f in formats if f["code"] == "FULL_CAM"), None)
die_if_none("FULL_CAM format def", full)
check("FULL_CAM listed in the format catalogue", full is not None, str(sorted(f["code"] for f in formats)))
full_keys = {s["key"] for s in full["sections"]}
for k in ["borrower_background", "industry_outlook", "financial_trend", "key_risks_mitigants",
          "security_perfection", "account_conduct", "deviations", "raroc_profitability", "esg",
          "conditions", "recommendation", "regulatory_compliance"]:
    check(f"FULL_CAM includes new section key '{k}'", k in full_keys, str(sorted(full_keys)))

# ------------------------------------------------------------------ 2. generate with FULL_CAM
print("== proposal-sections 2. Generate under FULL_CAM renders every new section ==")
st, prop = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate",
                {"format": "FULL_CAM"}, actor="analyst.user")
die_if_none("FULL_CAM proposal", prop)
check("FULL_CAM proposal generated (200, versioned)", st == 200 and prop.get("version", 0) >= 1, f"{st}")
check("persisted format == FULL_CAM", prop.get("format") == "FULL_CAM", str(prop.get("format")))
check("persisted sections == FULL_CAM format list/order",
      prop.get("sections") == [s["title"] for s in full["sections"]],
      f"{prop.get('sections')}")
md = prop.get("markdown", "")
for heading in NEW_SECTION_HEADINGS:
    check(f"body renders new section: '{heading}'", ("## " + heading) in md, "heading missing")

# ------------------------------------------------------------------ 3. multi-year financial_trend table
print("== proposal-sections 3. Multi-year financial trend renders >1 period ==")
check("financial-trend section shows the FY2024 period column", "FY2024" in md, "FY2024 column missing")
check("financial-trend section shows the FY2023 comparative column", "FY2023" in md, "FY2023 column missing")
check("trend table renders a Revenue row (grounded line)", "Revenue" in md, "Revenue row missing")
if dscr is not None:
    check("proposal quotes the authoritative DSCR verbatim (figure is a rendering)",
          f"{dscr:.2f}" in md, f"DSCR {dscr:.2f} not quoted")

# ------------------------------------------------------------------ 4. graceful degradation (bare deal)
print("== proposal-sections 4. Missing data degrades gracefully (no CAD/CP/perfection/ESG) ==")
# Single-period spread, no CAD case / CP register / perfection case / ESG annexure created.
bare = rated_deal("B", 30_000_000, [period("FY2024", 3.0e9, 1.9e9, 0.5e9, 0.1e9, 4.0e9, 1.8e9, 0.4e9, 1.0e9, 0.3e9, 0.7e9, 2.0e9, 0.6e9)])
st, propB = call("POST", f"/decision/api/decisions/{bare}/credit-proposal/generate",
                 {"format": "FULL_CAM"}, actor="analyst.user")
die_if_none("bare FULL_CAM proposal", propB)
check("FULL_CAM still generates 200 on a bare deal (no crash)", st == 200, f"{st}")
mdB = propB.get("markdown", "")
check("deviations degrades gracefully (no waivers recorded)", "No deviations or waivers recorded" in mdB, "no graceful line")
check("conditions degrades gracefully (nothing registered)",
      "No conditions precedent or subsequent registered" in mdB, "no graceful line")
check("account-conduct renders its graceful placeholder", "Account-conduct" in mdB, "no conduct placeholder")
check("single-period trend still renders the section heading",
      "## Financial trend (multi-year)" in mdB, "trend heading missing on single period")

# ------------------------------------------------------------------ 5. governance — figures unchanged
print("== proposal-sections 5. Governance — richer sections do NOT mutate authoritative figures ==")
st, rs_before = call("GET", f"/risk/api/risk/{ref}")
die_if_none("risk before", rs_before)
# regenerate the big FULL_CAM proposal (+ a STANDARD) and re-read the authoritative figures
call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", {"format": "FULL_CAM"}, actor="analyst.user")
call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", {"format": "STANDARD"}, actor="analyst.user")
st, rs_after = call("GET", f"/risk/api/risk/{ref}")
die_if_none("risk after", rs_after)
check("authoritative rating byte-identical before/after FULL_CAM generation (grade/PD)",
      json.dumps(rs_before.get("rating"), sort_keys=True) == json.dumps(rs_after.get("rating"), sort_keys=True),
      "rating mutated by proposal generation")
check("authoritative pricing byte-identical before/after FULL_CAM generation (rate/RAROC)",
      json.dumps(rs_before.get("pricing"), sort_keys=True) == json.dumps(rs_after.get("pricing"), sort_keys=True),
      "pricing mutated by proposal generation")
check("authoritative capital byte-identical before/after FULL_CAM generation",
      json.dumps(rs_before.get("capital"), sort_keys=True) == json.dumps(rs_after.get("capital"), sort_keys=True),
      "capital mutated by proposal generation")

print(f"\n== proposal-sections e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
