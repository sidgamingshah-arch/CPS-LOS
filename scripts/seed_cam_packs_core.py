#!/usr/bin/env python3
"""
CAM-format config packs — CORE LENDING cluster (config-as-data seeder).

WHAT THIS IS
------------
Authors 8 Credit-Assessment-Memo (CAM) *formats* purely as configuration data,
through the platform's generic Master-Data engine (POST /config/api/masters/{type}
+ maker-checker approve) — NO service or frontend code. Each CAM format is a
coherent set of master records so the EXISTING engines produce a segment-appropriate
CAM without any code branch (the product thesis: "a new regime is overlay data,
never a code branch"):

  * MODEL_DEFINITION   — the configurable scoring model (qualitative + quantitative
                         sections), resolved by segment (risk-service render + rating).
  * FINANCIAL_TEMPLATE — chart-of-accounts augmentation: segment-specific extra input
                         lines + formula-driven ratios (origination spreading).
  * CHECKLIST_MASTER   — the CAD documentation checklist for the format.
  * CP_MASTER          — the pre-disbursement Conditions-Precedent template, keyed by
                         the format's facility-type (decision-service CP register).
  * CODE_VALUE/SEGMENT — the new segment is appended to the SEGMENT dropdown domain so
                         it is selectable in the UI.

THE 8 CORE-LENDING FORMATS (segment codes are new; none collide with the 6 enum
segments MID_CORPORATE / LARGE_CORPORATE / SME / PROJECT_FINANCE / TRADE_FINANCE /
FINANCIAL_INSTITUTION, so segment-based resolution in other suites is untouched):

  1. Standard Corporate         STANDARD_CORPORATE
  2. NBFC (lending to NBFCs)     NBFC
  3. Working Capital            WORKING_CAPITAL
  4. Term Loan                  TERM_LOAN
  5. Project Finance            PROJECT_FINANCE_CAM
  6. Rental Discounting (LRD)   LEASE_RENTAL_DISCOUNTING
  7. Trade Finance (LC/BG)      TRADE_FINANCE_LCBG
  8. Construction / Real Estate CONSTRUCTION_REAL_ESTATE

SHIPPING CHANNEL
----------------
These packs ship via THIS seed script + the e2e (scripts/e2e_cam_packs_core.py),
NOT via the config-service startup bootstrap (MasterSeeder). Rationale: the packs
add master rows and extend the shared SEGMENT CODE_VALUE record; keeping them out of
the cold-start bootstrap guarantees the count/subset-sensitive suites (e2e_masters,
e2e_modelconfig, e2e_fintemplate, e2e_codevalue, e2e_ratingmodel) — which run against
startup-seeded data only — are byte-for-byte unaffected.

IDEMPOTENCY / RE-RUN SAFETY
---------------------------
Every record is created only if an ACTIVE record for its key is not already present
(checked via GET), and the SEGMENT domain is extended only with codes not already in
its values list. A second run finds everything present and makes no mutating call, so
re-running never versions the masters or explodes the segment list. Deterministic:
no Date.now / random reliance anywhere.

MAKER-CHECKER
-------------
Every master is submitted by MAKER and approved by a DIFFERENT checker (CHECKER), so
segregation-of-duties holds on every approval (the config engine 403s a self-approve).

HOW TO RUN
----------
  1. Start the stack:   bash scripts/run-all.sh   (health-gated on :8080-8088)
  2. Seed:              python3 scripts/seed_cam_packs_core.py
  3. Custom gateway:    HELIX_GATEWAY=http://localhost:9090 python3 scripts/seed_cam_packs_core.py

The seeder is also imported by scripts/e2e_cam_packs_core.py (run_seed()).
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW_DEFAULT = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
MAKER = "cam.maker"
CHECKER = "cam.checker"


# ------------------------------------------------------------------ HTTP helper

def _call(gw, method, path, body=None, actor=MAKER):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(gw + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, (json.loads(txt) if txt else None)
        except Exception:
            return e.code, txt
    except urllib.error.URLError as e:
        return 0, {"message": str(e.reason)}


# ------------------------------------------------------------ payload builders

def _dropdown(key, label, weight, required, options, prompt=None, visible_when=None):
    """A STANDALONE qualitative dropdown (options = [(label, score), ...])."""
    q = {"key": key, "type": "DROPDOWN", "label": label, "weight": weight,
         "required": required, "options": [{"label": l, "score": s} for (l, s) in options],
         "source": {"kind": "STANDALONE", "prompt": prompt or ("Assess " + label + ".")}}
    if visible_when:
        q["visibleWhen"] = visible_when
    return q


def _number(key, label, weight, required, bands, ratio_ref):
    """A MODULE-sourced quantitative number pulled from a spreading ratio.
    bands = [(edge, threshold, score), ...] where edge is 'min' or 'max'."""
    return {"key": key, "type": "NUMBER", "label": label, "weight": weight,
            "required": required,
            "scoreBands": [{edge: thr, "score": sc} for (edge, thr, sc) in bands],
            "source": {"kind": "MODULE", "ref": ratio_ref}}


def _model(model_key, name, segment, qual, quant, mandatory, min_answered=3):
    return {
        "modelKey": model_key, "displayName": name,
        "selector": {"segment": segment},
        "constraints": {"minAnswered": min_answered, "maxAnswered": 40, "mandatory": mandatory},
        "scoring": {"bands": [{"band": "STRONG", "min": 67},
                              {"band": "ADEQUATE", "min": 45},
                              {"band": "WEAK", "min": 0}]},
        "sections": [
            {"key": "QUALITATIVE", "kind": "QUALITATIVE", "label": "Qualitative assessment",
             "weight": 0.4, "questions": qual},
            {"key": "QUANTITATIVE", "kind": "QUANTITATIVE", "label": "Quantitative ratios",
             "weight": 0.6, "questions": quant}],
    }


def _template(template_key, name, segment, extra_inputs, extra_derived, extra_ratios):
    return {
        "templateKey": template_key, "displayName": name,
        "selector": {"segment": segment},
        "extraInputLines": [{"key": k, "label": lbl} for (k, lbl) in extra_inputs],
        "extraDerivedLines": [{"key": k, "label": lbl, "formula": f} for (k, lbl, f) in extra_derived],
        "extraRatios": [{"key": k, "label": lbl, "formula": f} for (k, lbl, f) in extra_ratios],
    }


def _cp(code, title, mandatory, description):
    return {"code": code, "title": title, "mandatory": mandatory, "description": description}


# Reusable qualitative option ladders.
STRONG_ADQ_WEAK = [("Strong", 90), ("Adequate", 60), ("Weak", 30)]
# Reusable quantitative bands (module-sourced ratios).
LEV_BANDS = [("max", 2.0, 90), ("max", 3.5, 60), ("max", 99, 30)]
DSCR_BANDS = [("min", 1.5, 90), ("min", 1.25, 60), ("min", 0, 30)]
CR_BANDS = [("min", 1.5, 90), ("min", 1.1, 60), ("min", 0, 30)]


# ------------------------------------------------------------------- FORMATS
# The single source of truth: each entry fully specifies one CAM format's config
# records + the deterministic inputs/expectations the e2e uses to assert the
# engines picked the config up. `assertRatio` is (key, expected) computed off the
# canonical BASE_LINES below (+ this format's extraInputValues).

# Canonical spread the e2e posts. Derived by the engine: EBITDA=0.9e9, TOTAL_DEBT=1.7e9,
# DEBT_SERVICE=0.35e9, WORKING_CAPITAL=1.0e9, PAT=0.43e9.
BASE_LINES = {
    "REVENUE": 5e9, "COGS": 3.2e9, "OPERATING_EXPENSES": 0.9e9, "DEPRECIATION": 0.2e9,
    "INTEREST_EXPENSE": 0.15e9, "TAX": 0.12e9, "TOTAL_ASSETS": 6e9, "CURRENT_ASSETS": 2.5e9,
    "CASH": 0.6e9, "CURRENT_LIABILITIES": 1.5e9, "SHORT_TERM_DEBT": 0.5e9,
    "LONG_TERM_DEBT": 1.2e9, "CURRENT_PORTION_LTD": 0.2e9, "NET_WORTH": 2.8e9, "CFO": 0.7e9,
}

FORMATS = [
    {
        "name": "Standard Corporate",
        "segment": "STANDARD_CORPORATE",
        "segmentLabel": "Standard Corporate",
        "sector": "DIVERSIFIED",
        "modelKey": "cam-standard-corporate-v1",
        "modelName": "CAM — Standard Corporate",
        "qual": [
            _dropdown("mgmt_quality", "Management quality & track record", 0.5, True, STRONG_ADQ_WEAK),
            _dropdown("business_diversification", "Business & revenue diversification", 0.5, False,
                      [("Diversified", 90), ("Moderate", 60), ("Concentrated", 30)]),
        ],
        "quant": [
            _number("leverage", "Net leverage (Net debt / EBITDA, x)", 0.5, True, LEV_BANDS, "RATIO:NET_LEVERAGE"),
            _number("dscr", "DSCR (x)", 0.5, True, DSCR_BANDS, "RATIO:DSCR"),
        ],
        "mandatory": ["mgmt_quality", "leverage", "dscr"],
        "templateKey": "fin-standard-corporate",
        "templateName": "Standard corporate chart",
        "extraInputs": [("CONTINGENT_LIABILITIES", "Contingent liabilities")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_EBITDA_MARGIN", "EBITDA margin (x)", "EBITDA / REVENUE"),
            ("CAM_TOL_TNW", "Total outside liabilities / TNW", "(TOTAL_DEBT + CURRENT_LIABILITIES) / NET_WORTH"),
        ],
        "extraInputValues": {"CONTINGENT_LIABILITIES": 0.56e9},
        "assertRatio": ("CAM_EBITDA_MARGIN", 0.18),
        "checklistKey": "CAM_STANDARD_CORPORATE",
        "checklistItems": ["Board resolution to borrow", "Audited financial statements (3 years)",
                           "Facility agreement", "Security & charge documents", "Banking arrangement letter"],
        "cpFacilityType": "CAM_CORPORATE_TL",
        "cpItems": [
            _cp("CP-FA", "Executed facility agreement", True, "Facility agreement executed by all parties."),
            _cp("CP-BR", "Board resolution", True, "Borrower board resolution authorising the borrowing."),
            _cp("CP-SEC", "Security perfection", True, "Charges registered with the regulator and on-record."),
            _cp("CP-VAL", "Independent valuation", True, "Empanelled-valuer report at sanction value or better."),
            _cp("CP-MAC", "No material adverse change", True, "RM confirmation of no MAC since sanction."),
        ],
    },
    {
        "name": "NBFC (lending to NBFCs)",
        "segment": "NBFC",
        "segmentLabel": "NBFC",
        "sector": "FINANCIAL_SERVICES",
        "modelKey": "cam-nbfc-v1",
        "modelName": "CAM — NBFC (lending to NBFCs)",
        "qual": [
            _dropdown("parentage_support", "Parentage & promoter support", 0.4, True, STRONG_ADQ_WEAK),
            _dropdown("alm_profile", "Asset-liability management profile", 0.3, True,
                      [("Well-matched", 90), ("Manageable gaps", 60), ("Significant gaps", 30)]),
            _dropdown("asset_quality_trend", "Asset-quality trend", 0.3, False,
                      [("Improving", 90), ("Stable", 60), ("Deteriorating", 30)]),
        ],
        "quant": [
            _number("nbfc_gearing", "Gearing (Total debt / TNW, x)", 0.5, True, LEV_BANDS, "RATIO:GEARING"),
            _number("interest_cover", "Interest coverage (x)", 0.5, False,
                    [("min", 2.5, 90), ("min", 1.5, 60), ("min", 0, 30)], "RATIO:INTEREST_COVERAGE"),
        ],
        "mandatory": ["parentage_support", "alm_profile", "nbfc_gearing"],
        "templateKey": "fin-nbfc",
        "templateName": "NBFC chart (AUM & asset-quality aware)",
        "extraInputs": [("AUM", "Assets under management"), ("GROSS_NPA", "Gross NPA"),
                        ("TIER1_CAPITAL", "Tier-1 capital")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_GNPA_RATIO", "Gross NPA ratio (x)", "GROSS_NPA / AUM"),
            ("CAM_NBFC_GEARING", "NBFC gearing (x)", "TOTAL_DEBT / NET_WORTH"),
        ],
        "extraInputValues": {"AUM": 50e9, "GROSS_NPA": 2e9, "TIER1_CAPITAL": 8e9},
        "assertRatio": ("CAM_GNPA_RATIO", 0.04),
        "checklistKey": "CAM_NBFC",
        "checklistItems": ["RBI Certificate of Registration (CoR)", "ALM statements (latest)",
                           "Board resolution & borrowing powers", "Credit & risk policy",
                           "Promoter/parent support undertaking"],
        "cpFacilityType": "CAM_NBFC_LOAN",
        "cpItems": [
            _cp("CP-COR", "RBI CoR verified", True, "Valid RBI Certificate of Registration verified & on file."),
            _cp("CP-FA", "Executed facility agreement", True, "Facility agreement executed by all parties."),
            _cp("CP-ENDUSE", "End-use undertaking", True, "Undertaking on regulated end-use of proceeds."),
            _cp("CP-ALM", "ALM compliance certificate", True, "Latest ALM statement within regulatory limits."),
            _cp("CP-MAC", "No material adverse change", True, "RM confirmation of no MAC since sanction."),
        ],
    },
    {
        "name": "Working Capital",
        "segment": "WORKING_CAPITAL",
        "segmentLabel": "Working Capital",
        "sector": "TRADING",
        "modelKey": "cam-working-capital-v1",
        "modelName": "CAM — Working Capital",
        "qual": [
            _dropdown("operating_cycle", "Operating cycle & inventory conversion", 0.4, True,
                      [("Short / efficient", 90), ("In line with peers", 60), ("Long / stretched", 30)]),
            _dropdown("banking_conduct", "Banking conduct & account operation", 0.3, True,
                      [("Clean", 90), ("Minor irregularities", 60), ("Adverse", 30)]),
            _dropdown("buyer_concentration", "Buyer / supplier concentration", 0.3, False,
                      [("Well-spread", 90), ("Moderate", 60), ("Concentrated", 30)]),
        ],
        "quant": [
            _number("current_ratio", "Current ratio (x)", 0.5, True, CR_BANDS, "RATIO:CURRENT_RATIO"),
            _number("leverage", "Net leverage (x)", 0.5, True, LEV_BANDS, "RATIO:NET_LEVERAGE"),
        ],
        "mandatory": ["operating_cycle", "current_ratio", "leverage"],
        "templateKey": "fin-working-capital",
        "templateName": "Working-capital chart (drawing-power aware)",
        "extraInputs": [("DRAWING_POWER", "Drawing power (assessed)")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_DP_UTILISATION", "Drawing-power utilisation (x)", "TOTAL_DEBT / DRAWING_POWER"),
            ("CAM_NWC_TO_TOL", "Net working capital / TOL", "WORKING_CAPITAL / (TOTAL_DEBT + CURRENT_LIABILITIES)"),
        ],
        "extraInputValues": {"DRAWING_POWER": 3.4e9},
        "assertRatio": ("CAM_DP_UTILISATION", 0.5),
        "checklistKey": "CAM_WORKING_CAPITAL",
        "checklistItems": ["Stock & receivables statement", "Drawing-power computation",
                           "Hypothecation deed (stock & book debts)", "Insurance of hypothecated assets",
                           "Latest stock audit report"],
        "cpFacilityType": "CAM_WC_LIMIT",
        "cpItems": [
            _cp("CP-FA", "Executed WC agreement", True, "Working-capital facility agreement executed."),
            _cp("CP-DPN", "Demand promissory note", True, "Borrower-executed DPN held on file."),
            _cp("CP-HYPO", "Hypothecation of current assets", True, "Charge over book debts + inventory perfected."),
            _cp("CP-DP", "Drawing-power computation signed", True, "DP working signed off latest stock statement."),
            _cp("CP-MAC", "No material adverse change", True, "RM confirmation of no MAC since sanction."),
        ],
    },
    {
        "name": "Term Loan",
        "segment": "TERM_LOAN",
        "segmentLabel": "Term Loan",
        "sector": "SERVICES",
        "modelKey": "cam-term-loan-v1",
        "modelName": "CAM — Term Loan",
        "qual": [
            _dropdown("asset_viability", "Asset / project viability", 0.5, True, STRONG_ADQ_WEAK),
            _dropdown("repayment_track", "Repayment track record", 0.5, False, STRONG_ADQ_WEAK),
        ],
        "quant": [
            _number("dscr", "DSCR (x)", 0.5, True, DSCR_BANDS, "RATIO:DSCR"),
            _number("leverage", "Net leverage (x)", 0.5, True, LEV_BANDS, "RATIO:NET_LEVERAGE"),
        ],
        "mandatory": ["asset_viability", "dscr", "leverage"],
        "templateKey": "fin-term-loan",
        "templateName": "Term-loan chart (promoter-contribution aware)",
        "extraInputs": [("PROJECT_COST", "Project / asset cost")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_PROMOTER_CONTRIB", "Promoter contribution (x of cost)", "NET_WORTH / PROJECT_COST"),
            ("CAM_TERM_GEARING", "Term gearing (x)", "TOTAL_DEBT / NET_WORTH"),
        ],
        "extraInputValues": {"PROJECT_COST": 5.6e9},
        "assertRatio": ("CAM_PROMOTER_CONTRIB", 0.5),
        "checklistKey": "CAM_TERM_LOAN",
        "checklistItems": ["Sanction letter", "Facility agreement", "Registered mortgage deed",
                           "Disbursement schedule", "End-use certificate"],
        "cpFacilityType": "CAM_TERM_LOAN",
        "cpItems": [
            _cp("CP-FA", "Executed facility agreement", True, "Facility agreement executed by all parties."),
            _cp("CP-SEC", "Security perfection", True, "All collateral charges registered and on-record."),
            _cp("CP-EQ", "Promoter equity infusion", True, "Promoter contribution evidenced before drawdown."),
            _cp("CP-VAL", "Independent valuation", True, "Empanelled-valuer report not older than 90 days."),
            _cp("CP-MAC", "No material adverse change", True, "RM confirmation of no MAC since sanction."),
        ],
    },
    {
        "name": "Project Finance",
        "segment": "PROJECT_FINANCE_CAM",
        "segmentLabel": "Project Finance (CAM)",
        "sector": "INFRASTRUCTURE",
        "modelKey": "cam-project-finance-v1",
        "modelName": "CAM — Project Finance",
        "qual": [
            _dropdown("sponsor_strength", "Sponsor strength & commitment", 0.35, True, STRONG_ADQ_WEAK),
            _dropdown("offtake_arrangement", "Offtake / concession arrangement", 0.35, True,
                      [("Firm long-term", 90), ("Partial / merchant", 60), ("Uncontracted", 30)]),
            _dropdown("construction_risk", "Construction & completion risk", 0.30, False,
                      [("Low (EPC fixed)", 90), ("Moderate", 60), ("High", 30)]),
        ],
        "quant": [
            _number("project_dscr", "Project DSCR (x)", 0.6, True,
                    [("min", 1.3, 90), ("min", 1.1, 60), ("min", 0, 30)], "RATIO:DSCR"),
            _number("interest_cover", "Interest coverage (x)", 0.4, False,
                    [("min", 2.0, 90), ("min", 1.2, 60), ("min", 0, 30)], "RATIO:INTEREST_COVERAGE"),
        ],
        "mandatory": ["sponsor_strength", "offtake_arrangement", "project_dscr"],
        "templateKey": "fin-project-finance",
        "templateName": "Project-finance chart (D:E & DSRA aware)",
        "extraInputs": [("EQUITY_CONTRIBUTION", "Sponsor equity contribution"),
                        ("DSRA_BALANCE", "DSRA balance")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_DEBT_EQUITY", "Debt : equity (x)", "TOTAL_DEBT / EQUITY_CONTRIBUTION"),
            ("CAM_DSRA_COVER", "DSRA cover (x of debt service)", "DSRA_BALANCE / DEBT_SERVICE"),
        ],
        "extraInputValues": {"EQUITY_CONTRIBUTION": 0.85e9, "DSRA_BALANCE": 0.7e9},
        "assertRatio": ("CAM_DEBT_EQUITY", 2.0),
        "checklistKey": "CAM_PROJECT_FINANCE",
        "checklistItems": ["Common Terms Agreement", "Concession / PPA agreement", "EPC contract",
                           "Independent financial model", "Statutory permits & clearances", "DSRA funding evidence"],
        "cpFacilityType": "CAM_PROJECT_FINANCE",
        "cpItems": [
            _cp("CP-CTA", "Common Terms Agreement executed", True, "CTA executed by all lenders."),
            _cp("CP-IA", "Intercreditor / inter-se", True, "Inter-creditor agreement signed by every lender."),
            _cp("CP-EQ", "Sponsor equity tied up", True, "Sponsor equity contribution evidenced."),
            _cp("CP-PERMITS", "Statutory permits", True, "Environmental, land & regulatory approvals on file."),
            _cp("CP-DSRA", "DSRA funded", True, "Debt-service reserve account funded per facility doc."),
            _cp("CP-MAC", "No material adverse change", True, "No MAC since sanction."),
        ],
    },
    {
        "name": "Rental Discounting (LRD)",
        "segment": "LEASE_RENTAL_DISCOUNTING",
        "segmentLabel": "Lease Rental Discounting (LRD)",
        "sector": "REAL_ESTATE",
        "modelKey": "cam-lrd-v1",
        "modelName": "CAM — Lease Rental Discounting",
        "qual": [
            _dropdown("property_quality", "Property quality & location", 0.4, True, STRONG_ADQ_WEAK),
            _dropdown("lessee_quality", "Lessee credit quality", 0.35, True, STRONG_ADQ_WEAK),
            _dropdown("lease_tenor_match", "Lease tenor vs. loan tenor", 0.25, False,
                      [("Lease > loan", 90), ("Broadly matched", 60), ("Lease < loan", 30)]),
        ],
        "quant": [
            _number("rental_dscr", "Rental DSCR (x)", 0.6, True,
                    [("min", 1.35, 90), ("min", 1.1, 60), ("min", 0, 30)], "RATIO:DSCR"),
            _number("leverage", "Net leverage (x)", 0.4, False, LEV_BANDS, "RATIO:NET_LEVERAGE"),
        ],
        "mandatory": ["property_quality", "lessee_quality", "rental_dscr"],
        "templateKey": "fin-lrd",
        "templateName": "LRD chart (rental cover & LTV aware)",
        "extraInputs": [("ANNUAL_RENTAL", "Annual contracted rental"),
                        ("PROPERTY_VALUE", "Property value (valuation)")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_RENTAL_DSCR", "Rental cover (x of debt service)", "ANNUAL_RENTAL / DEBT_SERVICE"),
            ("CAM_LTV", "Loan-to-value (x)", "TOTAL_DEBT / PROPERTY_VALUE"),
        ],
        "extraInputValues": {"ANNUAL_RENTAL": 0.525e9, "PROPERTY_VALUE": 3.4e9},
        "assertRatio": ("CAM_LTV", 0.5),
        "checklistKey": "CAM_LRD",
        "checklistItems": ["Registered lease deed(s)", "Title search report", "Valuation report",
                           "Escrow / rental-assignment agreement", "Occupancy certificate"],
        "cpFacilityType": "CAM_LRD",
        "cpItems": [
            _cp("CP-LEASE", "Registered lease deed(s)", True, "Lease deeds registered and on record."),
            _cp("CP-ESCROW", "Escrow account setup", True, "Rental escrow account opened & operational."),
            _cp("CP-ASSIGN", "Assignment of rentals", True, "Rentals irrevocably assigned to the bank."),
            _cp("CP-MOR", "Registered mortgage", True, "Mortgage over the property registered."),
            _cp("CP-MAC", "No material adverse change", True, "No MAC since sanction."),
        ],
    },
    {
        "name": "Trade Finance (LC/BG)",
        "segment": "TRADE_FINANCE_LCBG",
        "segmentLabel": "Trade Finance (LC/BG)",
        "sector": "TRADING",
        "modelKey": "cam-trade-finance-v1",
        "modelName": "CAM — Trade Finance (LC/BG)",
        "qual": [
            _dropdown("trade_track_record", "Trade track record", 0.4, True, STRONG_ADQ_WEAK),
            _dropdown("counterparty_country_risk", "Counterparty / country risk", 0.35, True,
                      [("Low", 90), ("Moderate", 60), ("High", 30)]),
            _dropdown("trade_genuineness", "Underlying-trade genuineness", 0.25, False, STRONG_ADQ_WEAK),
        ],
        "quant": [
            _number("current_ratio", "Current ratio (x)", 0.5, True, CR_BANDS, "RATIO:CURRENT_RATIO"),
            _number("leverage", "Net leverage (x)", 0.5, False, LEV_BANDS, "RATIO:NET_LEVERAGE"),
        ],
        "mandatory": ["trade_track_record", "counterparty_country_risk", "current_ratio"],
        "templateKey": "fin-trade-finance",
        "templateName": "Trade-finance chart (NFB & margin aware)",
        "extraInputs": [("LC_BG_OUTSTANDING", "LC / BG outstanding"), ("MARGIN_HELD", "Cash margin held")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_MARGIN_COVER", "Margin cover (x of NFB)", "MARGIN_HELD / LC_BG_OUTSTANDING"),
            ("CAM_NFB_TO_TNW", "NFB / TNW (x)", "LC_BG_OUTSTANDING / NET_WORTH"),
        ],
        "extraInputValues": {"LC_BG_OUTSTANDING": 2e9, "MARGIN_HELD": 0.4e9},
        "assertRatio": ("CAM_MARGIN_COVER", 0.2),
        "checklistKey": "CAM_TRADE_LC_BG",
        "checklistItems": ["LC / BG facility application", "Underlying trade documents",
                           "Margin lodgement evidence", "Counter-indemnity", "Counterparty credit report"],
        "cpFacilityType": "CAM_TRADE_LC_BG",
        "cpItems": [
            _cp("CP-AGR", "Executed master facility agreement", True, "LC/BG master agreement executed."),
            _cp("CP-CG", "Counter-guarantee + indemnity", True, "Borrower counter-guarantee + indemnity executed."),
            _cp("CP-MAR", "Margin lodged", True, "Cash margin per sanction lodged in lien-marked account."),
            _cp("CP-TRADE", "Underlying trade documents", True, "Proforma invoice / contract on record."),
            _cp("CP-MAC", "No material adverse change", True, "No MAC since sanction."),
        ],
    },
    {
        "name": "Construction / Real Estate",
        "segment": "CONSTRUCTION_REAL_ESTATE",
        "segmentLabel": "Construction / Real Estate",
        "sector": "REAL_ESTATE",
        "modelKey": "cam-construction-re-v1",
        "modelName": "CAM — Construction / Real Estate",
        "qual": [
            _dropdown("developer_track", "Developer track record", 0.35, True, STRONG_ADQ_WEAK),
            _dropdown("approvals_status", "Project approvals & RERA status", 0.35, True,
                      [("All in place", 90), ("Substantially in place", 60), ("Pending", 30)]),
            _dropdown("sales_velocity", "Sales velocity", 0.30, False,
                      [("Strong", 90), ("Moderate", 60), ("Slow", 30)]),
        ],
        "quant": [
            _number("leverage", "Net leverage (x)", 0.5, True, LEV_BANDS, "RATIO:NET_LEVERAGE"),
            _number("current_ratio", "Current ratio (x)", 0.5, False, CR_BANDS, "RATIO:CURRENT_RATIO"),
        ],
        "mandatory": ["developer_track", "approvals_status", "leverage"],
        "templateKey": "fin-construction-re",
        "templateName": "Construction / RE chart (GDV & sales aware)",
        "extraInputs": [("GROSS_DEVELOPMENT_VALUE", "Gross development value (GDV)"),
                        ("SOLD_VALUE", "Sold value to date"), ("PROJECT_COST_RE", "Total project cost")],
        "extraDerived": [],
        "extraRatios": [
            ("CAM_LOAN_TO_GDV", "Loan-to-GDV (x)", "TOTAL_DEBT / GROSS_DEVELOPMENT_VALUE"),
            ("CAM_SALES_COVER", "Sales cover (x of cost)", "SOLD_VALUE / PROJECT_COST_RE"),
        ],
        "extraInputValues": {"GROSS_DEVELOPMENT_VALUE": 8.5e9, "SOLD_VALUE": 3e9, "PROJECT_COST_RE": 5e9},
        "assertRatio": ("CAM_LOAN_TO_GDV", 0.2),
        "checklistKey": "CAM_CONSTRUCTION_RE",
        "checklistItems": ["RERA registration certificate", "Title & development approvals",
                           "Project cost & sales cash-flow statement", "Architect / engineer certificate",
                           "RERA escrow (70%) account setup"],
        "cpFacilityType": "CAM_CRE",
        "cpItems": [
            _cp("CP-RERA", "RERA registration", True, "Valid RERA registration for the project on file."),
            _cp("CP-MOR", "Project mortgage", True, "Mortgage over project land & receivables registered."),
            _cp("CP-ESCROW", "RERA escrow account", True, "70% RERA escrow account opened & operational."),
            _cp("CP-APPROVALS", "Statutory approvals", True, "All plan sanctions & approvals on file."),
            _cp("CP-MAC", "No material adverse change", True, "No MAC since sanction."),
        ],
    },
]


# --------------------------------------------------------------- seed helpers

class SeedStats:
    def __init__(self):
        self.created = 0
        self.skipped = 0
        self.errors = []

    def note(self, created):
        if created:
            self.created += 1
        else:
            self.skipped += 1


def _ensure_master(gw, mtype, key, payload, stats, maker=MAKER, checker=CHECKER):
    """Create + approve a master record iff no ACTIVE record exists for the key.
    Idempotent: a second run finds it present and makes no mutating call."""
    st, existing = _call(gw, "GET", f"/config/api/masters/{mtype}/{key}")
    if st == 200 and isinstance(existing, dict) and existing.get("status") == "ACTIVE":
        stats.note(created=False)
        return "exists"
    st, sub = _call(gw, "POST", f"/config/api/masters/{mtype}",
                    {"recordKey": key, "payload": payload,
                     "comment": "CAM core-lending pack (seed)"}, actor=maker)
    if st != 200 or not isinstance(sub, dict) or "id" not in sub:
        stats.errors.append(f"submit {mtype}/{key}: HTTP {st} {sub}")
        return "error"
    st, ap = _call(gw, "POST", f"/config/api/masters/records/{sub['id']}/approve", actor=checker)
    if st != 200 or not isinstance(ap, dict) or ap.get("status") != "ACTIVE":
        stats.errors.append(f"approve {mtype}/{key}: HTTP {st} {ap}")
        return "error"
    stats.note(created=True)
    return "created"


def _ensure_segments(gw, stats, maker=MAKER, checker=CHECKER):
    """Append the 8 CAM segments to the SEGMENT CODE_VALUE domain (only codes not
    already present). Preserves existing values + order; versions once if needed."""
    st, rec = _call(gw, "GET", "/config/api/masters/CODE_VALUE/SEGMENT")
    if st != 200 or not isinstance(rec, dict):
        stats.errors.append(f"read CODE_VALUE/SEGMENT: HTTP {st} {rec}")
        return "error"
    values = list(rec.get("payload", {}).get("values", []))
    existing_codes = {v.get("code") for v in values}
    to_add = [f for f in FORMATS if f["segment"] not in existing_codes]
    if not to_add:
        stats.note(created=False)
        return "exists"
    start = len(values)
    for i, f in enumerate(to_add):
        values.append({"code": f["segment"], "label": f["segmentLabel"], "sortOrder": start + i})
    payload = {"domain": "SEGMENT",
               "label": rec.get("payload", {}).get("label", "Counterparty segments"),
               "values": values}
    st, sub = _call(gw, "POST", "/config/api/masters/CODE_VALUE",
                    {"recordKey": "SEGMENT", "payload": payload,
                     "comment": "Add CAM core-lending segments (seed)"}, actor=maker)
    if st != 200 or not isinstance(sub, dict) or "id" not in sub:
        stats.errors.append(f"submit CODE_VALUE/SEGMENT: HTTP {st} {sub}")
        return "error"
    st, ap = _call(gw, "POST", f"/config/api/masters/records/{sub['id']}/approve", actor=checker)
    if st != 200 or not isinstance(ap, dict) or ap.get("status") != "ACTIVE":
        stats.errors.append(f"approve CODE_VALUE/SEGMENT: HTTP {st} {ap}")
        return "error"
    stats.note(created=True)
    return "created"


def run_seed(gw=None, maker=MAKER, checker=CHECKER, verbose=True):
    """Seed all 8 CAM core-lending formats (idempotent). Returns SeedStats."""
    gw = gw or GW_DEFAULT
    stats = SeedStats()

    def log(msg):
        if verbose:
            print(msg)

    log(f"[*] Seeding 8 CAM core-lending config packs via {gw}")
    for f in FORMATS:
        _ensure_master(gw, "MODEL_DEFINITION", f["modelKey"],
                       _model(f["modelKey"], f["modelName"], f["segment"], f["qual"], f["quant"],
                              f["mandatory"]), stats, maker, checker)
        _ensure_master(gw, "FINANCIAL_TEMPLATE", f["templateKey"],
                       _template(f["templateKey"], f["templateName"], f["segment"],
                                 f["extraInputs"], f["extraDerived"], f["extraRatios"]), stats, maker, checker)
        _ensure_master(gw, "CHECKLIST_MASTER", f["checklistKey"],
                       {"items": f["checklistItems"]}, stats, maker, checker)
        _ensure_master(gw, "CP_MASTER", f["cpFacilityType"],
                       {"items": f["cpItems"]}, stats, maker, checker)
        log(f"    - {f['name']:28s} segment={f['segment']} "
            f"model={f['modelKey']} template={f['templateKey']}")

    _ensure_segments(gw, stats, maker, checker)

    log(f"[+] CAM packs seeded: {stats.created} created, {stats.skipped} already present"
        + (f", {len(stats.errors)} error(s)" if stats.errors else ""))
    for e in stats.errors[:10]:
        log(f"    ERROR {e}")
    return stats


def main():
    gw = GW_DEFAULT
    # Reachability check (also warms config-service).
    st, _ = _call(gw, "GET", "/config/api/masters/CODE_VALUE/SEGMENT")
    if st == 0:
        print(f"Gateway not reachable at {gw} (GET SEGMENT -> connection error).")
        print("Start the stack first:  bash scripts/run-all.sh   (health-gated on :8080-8088)")
        return 2
    stats = run_seed(gw)
    return 0 if not stats.errors else 1


if __name__ == "__main__":
    sys.exit(main())
