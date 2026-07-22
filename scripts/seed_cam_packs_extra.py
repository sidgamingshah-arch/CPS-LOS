#!/usr/bin/env python3
"""
CAM-format config packs — ADDITIONAL / SPECIALTY-CHANNEL cluster (config-as-data seeder).

Ships the 9 EXTRA Credit-Appraisal-Memo (CAM) formats that the bank-grade CLoM
workbook lists under "Additional CAM formats" but which Helix did not yet carry a
built counterpart for. Like the CORE (scripts/seed_cam_packs_core.py) and
SPECIALTY/FI (scripts/seed_cam_packs_specialty.py) clusters, every format is pure
master data authored through the platform's generic Master-Data engine
(`POST /config/api/masters/{type}` + a DIFFERENT checker approve, maker != checker
for SoD) — there is NO service or frontend code. The EXISTING config-driven engines
(model-config, financial-template, CAD checklist, CP register, credit-proposal
formatter) then emit a segment-appropriate CAM with NO code branch (the product
thesis: "a new regime is overlay data, never a code branch").

THE 9 FORMATS (each = a new SEGMENT + MODEL_DEFINITION + FINANCIAL_TEMPLATE +
CHECKLIST_MASTER + CP_MASTER + PROPOSAL_FORMAT):

  1. Commodity Exchange                 segment COMMODITY_EXCHANGE
  2. Distributor (Supply Chain)         segment DISTRIBUTOR_SCF
  3. Dealer / Retail-Utility Loan       segment DEALER_RETAIL_UTILITY   (workbook "Drul")
  4. Exchange House (money-services)    segment EXCHANGE_HOUSE
  5. Factoring (Supply Chain)           segment FACTORING_SCF
  6. Fully Cash Collateralized          segment FULLY_CASH_COLLATERALIZED
  7. Mutual Funds / AMC                 segment MUTUAL_FUND_AMC
  8. Service Providers                  segment SERVICE_PROVIDER
  9. Stock Exchange Broker              segment STOCK_EXCHANGE_BROKER

Each format's FINANCIAL_TEMPLATE augments the canonical 15-input / 8-derived chart
with *segment-appropriate prudential inputs and formula ratios* — margin / position
limits for a commodity exchange, channel-throughput / stock-turn for the distributor
and dealer channels, turnover ÷ net-worth for the exchange house, advance-rate +
dilution for factoring, cash-cover for the fully-cash-collateralized facility, AUM
growth / expense ratio for the AMC, receivables-days / EBITDA margin for service
providers, and net-capital / margin cover for the stock-exchange broker — reflecting
that these charts differ from a plain corporate chart.

Each MODEL_DEFINITION pins its segment via the `selector` so the SAME model resolver
(`GET /config/api/models/resolve`) that serves the corporate/specialty models picks it
up as the most-specific match, with segment-appropriate qualitative (STANDALONE,
advisory-scored) + quantitative (MODULE, pulled from the template's prudential ratios)
parameters — never producing a credit-consequential figure.

IDEMPOTENT / RE-RUNNABLE
------------------------
Before creating anything the seeder GETs the ACTIVE record for each (type, key) and
SKIPS it if present — a second run finds everything and creates nothing (no version
churn). The SEGMENT CODE_VALUE domain is extended by GET-merge-submit: existing codes
are preserved and only missing segment codes are appended.

DESIGN NOTES (why these keys are contamination-safe)
----------------------------------------------------
  * CHECKLIST_MASTER recordKeys deliberately do NOT contain the substring "SECURED":
    CadService.initiate() globally prefers the first "SECURED" key
    (CORP_TERM_LOAN_SECURED), so these CAM checklists never change which checklist an
    existing CAD case picks.
  * CP_MASTER recordKeys are format/segment codes, NOT facility types, so
    ConditionPrecedentService.pickPack() (which matches recordKey==facilityType) never
    selects them for an ordinary deal.
  * PROPOSAL_FORMAT recordKeys are brand-new format codes and each pins a brand-new
    segment; the startup-seeded formats (STANDARD/PROJECT_FINANCE/LRD/SCF/
    WORKING_CAPITAL/NBFC) and their segment defaults are untouched, and STANDARD stays
    the universal default (a no-format generate is byte-identical).
  * New SEGMENT codes are free-form strings on the resolver path (no
    Enums.Segment.valueOf on any write path), so they resolve MODEL_DEFINITION /
    FINANCIAL_TEMPLATE cleanly and never out-rank the wildcard corporate default for the
    existing corporate/SME/manufacturing segments.

HOW TO RUN
----------
  1. Start the stack:  bash scripts/run-all.sh   (health-gated on :8080-8088)
  2. Seed:             python3 scripts/seed_cam_packs_extra.py
  Gateway override:    HELIX_GATEWAY=http://localhost:PORT python3 scripts/seed_cam_packs_extra.py

The gateway is permissive locally (no login) — only the X-Actor header matters; the two
seeder actors below are distinct so every approval satisfies maker-checker.
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
JURISDICTION = "IN-RBI"

# Two distinct actors so every checker approval satisfies maker != checker (SoD).
MAKER = "cam.maker"
CHECKER = "cam.checker"


def call(method, path, body=None, actor=MAKER):
    """Single HTTP round-trip through the gateway (mirrors the e2e/seed helpers)."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
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


# --------------------------------------------------------------- payload builders

def opt(label, score):
    return {"label": label, "score": score}


def bmin(threshold, score):
    return {"min": threshold, "score": score}


def bmax(threshold, score):
    return {"max": threshold, "score": score}


def standalone(prompt):
    return {"kind": "STANDALONE", "prompt": prompt}


def module(ref):
    return {"kind": "MODULE", "ref": ref}


def dropdown(key, label, weight, required, options, prompt, visible_when=None):
    q = {"key": key, "type": "DROPDOWN", "label": label, "weight": weight,
         "required": required, "options": options, "source": standalone(prompt)}
    if visible_when:
        q["visibleWhen"] = visible_when
    return q


def number_module(key, label, weight, required, bands, ref):
    return {"key": key, "type": "NUMBER", "label": label, "weight": weight,
            "required": required, "scoreBands": bands, "source": module(ref)}


def model(model_key, display_name, segment, qual, quant, mandatory,
          qual_weight=0.4, quant_weight=0.6):
    return {
        "modelKey": model_key,
        "displayName": display_name,
        "selector": {"segment": segment},
        "constraints": {"minAnswered": len(mandatory), "maxAnswered": 40, "mandatory": mandatory},
        "scoring": {"bands": [
            {"band": "STRONG", "min": 67},
            {"band": "ADEQUATE", "min": 45},
            {"band": "WEAK", "min": 0}]},
        "sections": [
            {"key": "QUALITATIVE", "kind": "QUALITATIVE", "label": "Qualitative assessment",
             "weight": qual_weight, "questions": qual},
            {"key": "QUANTITATIVE", "kind": "QUANTITATIVE", "label": "Prudential & financial metrics",
             "weight": quant_weight, "questions": quant}],
    }


def finput(key, label):
    return {"key": key, "label": label}


def fratio(key, label, formula):
    return {"key": key, "label": label, "formula": formula}


def template(template_key, display_name, segment, inputs, ratios, derived=None):
    return {
        "templateKey": template_key,
        "displayName": display_name,
        "selector": {"segment": segment},
        "extraInputLines": inputs,
        "extraDerivedLines": derived or [],
        "extraRatios": ratios,
    }


def cp(code, title, mandatory, description=None):
    m = {"code": code, "title": title, "mandatory": mandatory}
    if description:
        m["description"] = description
    return m


def pfsec(key, title):
    """A credit-proposal section reference (key maps to a CreditProposalService builder)."""
    return {"key": key, "title": title}


def proposal_format(label, segment, sections):
    return {"label": label, "segment": segment, "sections": sections}


# --------------------------------------------------------------- the 9 formats
# Each entry declares: the CAM label, its new SEGMENT code + display label, a realistic
# sector tag, the five master payloads (model / template / checklist / cp / proposal
# format), and the deterministic spread inputs + expected ratio values the e2e uses to
# prove the augmentation COMPUTES through the real spreading engine. Every format is
# fully self-contained so a reviewer can read one block and understand the whole pack.

FORMATS = [
    # --------------------------------------------------------------- 1. Commodity Exchange
    {
        "name": "Commodity Exchange",
        "segment": "COMMODITY_EXCHANGE", "segmentLabel": "Commodity exchange", "sector": "CAPITAL_MARKETS",
        "modelKey": "commodity-exchange-rating-v1", "templateKey": "fin-commodity-exchange",
        "checklistKey": "COMMODITY_EXCHANGE_CAM", "cpKey": "COMMODITY_EXCHANGE",
        "proposalFormatKey": "COMMODITY_EXCHANGE",
        "signatureRatio": "MARGIN_COVER",
        "model": model(
            "commodity-exchange-rating-v1", "Commodity-Exchange Rating", "COMMODITY_EXCHANGE",
            qual=[
                dropdown("regulatory_standing", "Regulatory / exchange recognition", 0.35, True,
                         [opt("Recognised, clean record", 90), opt("Minor observations", 60), opt("Under action", 25)],
                         "Assess the commodity exchange / clearing corporation's regulatory recognition and any disciplinary action."),
                dropdown("risk_management_framework", "Risk-management framework (margining + SGF)", 0.35, True,
                         [opt("Robust", 90), opt("Adequate", 60), opt("Weak", 30)],
                         "Assess the exchange's margining discipline and settlement-guarantee-fund adequacy."),
                dropdown("member_diversification", "Member / participant diversification", 0.30, False,
                         [opt("Broad membership", 85), opt("Moderate", 60), opt("Concentrated", 30)],
                         "Assess concentration across the clearing members whose positions the exchange guarantees."),
            ],
            quant=[
                number_module("margin_cover", "Margin cover (margin collected / member positions, x)", 0.40, True,
                              [bmin(0.12, 90), bmin(0.08, 60), bmin(0, 30)], "RATIO:MARGIN_COVER"),
                number_module("position_leverage", "Position leverage (member positions / net worth, x)", 0.30, True,
                              [bmax(3, 90), bmax(5, 60), bmax(999, 30)], "RATIO:POSITION_LEVERAGE"),
                number_module("sgf_cover", "Settlement-guarantee-fund cover (x)", 0.30, False,
                              [bmin(0.15, 90), bmin(0.1, 60), bmin(0, 30)], "RATIO:SGF_COVER"),
            ],
            mandatory=["regulatory_standing", "risk_management_framework", "margin_cover", "position_leverage"]),
        "template": template(
            "fin-commodity-exchange", "Commodity-exchange margin chart", "COMMODITY_EXCHANGE",
            inputs=[finput("MARGIN_COLLECTED", "Margin collected from members"),
                    finput("MEMBER_POSITIONS", "Aggregate open member positions"),
                    finput("SETTLEMENT_GUARANTEE_FUND", "Settlement guarantee fund (SGF)")],
            ratios=[fratio("MARGIN_COVER", "Margin cover (x)", "MARGIN_COLLECTED / MEMBER_POSITIONS"),
                    fratio("SGF_COVER", "SGF cover (x)", "SETTLEMENT_GUARANTEE_FUND / MEMBER_POSITIONS"),
                    fratio("POSITION_LEVERAGE", "Position leverage (x)", "MEMBER_POSITIONS / NET_WORTH")]),
        "spreadInputs": {"MARGIN_COLLECTED": 1.4e9, "MEMBER_POSITIONS": 7e9,
                         "SETTLEMENT_GUARANTEE_FUND": 1.4e9},
        "expectRatios": [("MARGIN_COVER", 0.2), ("POSITION_LEVERAGE", 2.5)],
        "checklist": ["Exchange / clearing-corporation recognition certificate",
                      "Board resolution & delegated borrowing authority",
                      "Risk-management & margining policy",
                      "Settlement-guarantee-fund adequacy statement",
                      "Latest audited financials", "Exposure limit approval"],
        "cp": [cp("CP-RECOG", "Exchange recognition verified", True,
                  "Valid recognition / registration of the exchange verified and on file."),
               cp("CP-BR", "Board resolution", True),
               cp("CP-SGF", "SGF adequacy confirmation", True,
                  "Settlement-guarantee-fund at or above the regulatory floor."),
               cp("CP-LIMIT", "Exposure limit sanctioned", True,
                  "Counterparty limit approved within the capital-markets exposure framework."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Commodity Exchange CAM", "COMMODITY_EXCHANGE",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Facilities"),
             pfsec("financials", "Financial Analysis"), pfsec("ratios", "Margin & Position Ratios"),
             pfsec("rating", "Rating"), pfsec("capital", "Capital"), pfsec("pricing", "Pricing"),
             pfsec("covenants", "Covenants"), pfsec("routing", "Approval Routing"),
             pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 2. Distributor (Supply Chain)
    {
        "name": "Distributor (Supply Chain)",
        "segment": "DISTRIBUTOR_SCF", "segmentLabel": "Distributor (supply chain)", "sector": "SUPPLY_CHAIN",
        "modelKey": "distributor-scf-rating-v1", "templateKey": "fin-distributor-scf",
        "checklistKey": "DISTRIBUTOR_SCF_CAM", "cpKey": "DISTRIBUTOR_SCF",
        "proposalFormatKey": "DISTRIBUTOR_SCF",
        "signatureRatio": "STOCK_TURN",
        "model": model(
            "distributor-scf-rating-v1", "Distributor (Supply-Chain) Rating", "DISTRIBUTOR_SCF",
            qual=[
                dropdown("principal_credit_strength", "Principal / OEM credit strength", 0.35, True,
                         [opt("Investment-grade principal", 90), opt("Adequate principal", 60), opt("Sub-IG principal", 30)],
                         "Assess the credit quality of the principal / OEM whose products the distributor moves."),
                dropdown("distribution_rights", "Distribution rights", 0.25, True,
                         [opt("Exclusive, long-term", 85), opt("Non-exclusive", 60), opt("At-will", 30)],
                         "Assess the tenure and exclusivity of the distributor's appointment."),
                dropdown("channel_track_record", "Channel track record", 0.20, False,
                         [opt("Established", 85), opt("Developing", 55), opt("New", 30)],
                         "Assess the maturity and conduct of the distribution relationship."),
                dropdown("geographic_spread", "Geographic spread of sub-dealers", 0.20, False,
                         [opt("Wide", 85), opt("Regional", 60), opt("Single-market", 30)],
                         "Assess concentration across the distributor's downstream sub-dealer base."),
            ],
            quant=[
                number_module("stock_turn", "Stock turn (channel throughput / inventory, x)", 0.40, True,
                              [bmin(6, 90), bmin(4, 60), bmin(0, 30)], "RATIO:STOCK_TURN"),
                number_module("channel_throughput_ratio", "Channel throughput / booked revenue (x)", 0.30, False,
                              [bmin(1.0, 90), bmin(0.5, 60), bmin(0, 30)], "RATIO:CHANNEL_THROUGHPUT_RATIO"),
                number_module("net_leverage", "Net leverage (Net debt / EBITDA, x)", 0.30, True,
                              [bmax(2.0, 90), bmax(3.5, 60), bmax(99, 30)], "RATIO:NET_LEVERAGE"),
            ],
            mandatory=["principal_credit_strength", "distribution_rights", "stock_turn", "net_leverage"]),
        "template": template(
            "fin-distributor-scf", "Distributor channel chart", "DISTRIBUTOR_SCF",
            inputs=[finput("CHANNEL_THROUGHPUT", "Annual channel sell-through"),
                    finput("DISTRIBUTOR_INVENTORY", "Distributor inventory (principal stock)"),
                    finput("PAYABLES_TO_PRINCIPAL", "Payables to principal / OEM")],
            ratios=[fratio("STOCK_TURN", "Stock turn (x)", "CHANNEL_THROUGHPUT / DISTRIBUTOR_INVENTORY"),
                    fratio("CHANNEL_THROUGHPUT_RATIO", "Throughput / revenue (x)", "CHANNEL_THROUGHPUT / REVENUE"),
                    fratio("PRINCIPAL_PAYABLE_DAYS", "Principal payable days", "PAYABLES_TO_PRINCIPAL / COGS * 365")]),
        "spreadInputs": {"CHANNEL_THROUGHPUT": 6e9, "DISTRIBUTOR_INVENTORY": 0.75e9,
                         "PAYABLES_TO_PRINCIPAL": 0.8e9},
        "expectRatios": [("STOCK_TURN", 8.0), ("CHANNEL_THROUGHPUT_RATIO", 1.2)],
        "checklist": ["Principal / OEM distribution agreement", "Distributor onboarding & KYC pack",
                      "Hypothecation of channel inventory & receivables", "Stock & receivables statement",
                      "Latest stock audit report", "Facility agreement"],
        "cp": [cp("CP-DA", "Executed distribution agreement", True,
                  "Principal / OEM distribution agreement executed and current."),
               cp("CP-HYP", "Hypothecation of inventory & receivables", True,
                  "Charge over channel stock and book debts perfected."),
               cp("CP-STOCK", "Opening stock audit", False,
                  "Independent stock audit performed at onboarding."),
               cp("CP-ESCROW", "Sale-proceeds routing established", True,
                  "Downstream sale proceeds routed to the designated account."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Distributor (Supply Chain) CAM", "DISTRIBUTOR_SCF",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Programme Facilities"),
             pfsec("scf_program", "Distribution Programme"), pfsec("financials", "Financial Analysis"),
             pfsec("ratios", "Channel Ratios"), pfsec("rating", "Rating"), pfsec("pricing", "Pricing"),
             pfsec("covenants", "Covenants"), pfsec("routing", "Approval Routing"),
             pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 3. Dealer / Retail-Utility Loan (Drul)
    {
        "name": "Dealer / Retail-Utility Loan",
        "segment": "DEALER_RETAIL_UTILITY", "segmentLabel": "Dealer / retail-utility loan", "sector": "SUPPLY_CHAIN",
        "modelKey": "dealer-retail-utility-rating-v1", "templateKey": "fin-dealer-retail-utility",
        "checklistKey": "DEALER_RETAIL_UTILITY_CAM", "cpKey": "DEALER_RETAIL_UTILITY",
        "proposalFormatKey": "DEALER_RETAIL_UTILITY",
        "signatureRatio": "DEALER_STOCK_TURN",
        "model": model(
            "dealer-retail-utility-rating-v1", "Dealer / Retail-Utility Loan Rating", "DEALER_RETAIL_UTILITY",
            qual=[
                dropdown("anchor_oem_strength", "Anchor / OEM credit strength", 0.30, True,
                         [opt("Investment-grade anchor", 90), opt("Adequate anchor", 60), opt("Sub-IG anchor", 30)],
                         "Assess the anchor / OEM credit quality supporting the retail-utility dealer."),
                dropdown("product_liquidity", "Product resale liquidity", 0.25, True,
                         [opt("Highly liquid", 85), opt("Moderate", 60), opt("Illiquid", 30)],
                         "Assess the resale liquidity of the financed retail / utility goods (repossession value)."),
                dropdown("dealer_track_record", "Dealer track record", 0.25, False,
                         [opt("Established", 85), opt("Developing", 55), opt("New", 30)],
                         "Assess the maturity and conduct of the dealer relationship."),
                dropdown("territory_concentration", "Territory concentration", 0.20, False,
                         [opt("Diversified", 85), opt("Moderate", 60), opt("Concentrated", 25)],
                         "Assess concentration across the dealer's sales territory / customer base."),
            ],
            quant=[
                number_module("dealer_stock_turn", "Dealer stock turn (retail sales / inventory, x)", 0.40, True,
                              [bmin(6, 90), bmin(4, 60), bmin(0, 30)], "RATIO:DEALER_STOCK_TURN"),
                number_module("drul_utilisation", "Facility utilisation (debt / drawing limit, x)", 0.30, True,
                              [bmax(0.8, 90), bmax(0.95, 60), bmax(999, 30)], "RATIO:DRUL_UTILISATION"),
                number_module("retail_throughput", "Retail throughput / revenue (x)", 0.30, False,
                              [bmin(0.8, 90), bmin(0.5, 60), bmin(0, 30)], "RATIO:RETAIL_THROUGHPUT"),
            ],
            mandatory=["anchor_oem_strength", "product_liquidity", "dealer_stock_turn", "drul_utilisation"]),
        "template": template(
            "fin-dealer-retail-utility", "Dealer / retail-utility chart", "DEALER_RETAIL_UTILITY",
            inputs=[finput("RETAIL_SALES", "Annual retail sales"),
                    finput("DEALER_INVENTORY", "Dealer inventory (financed stock)"),
                    finput("DRAWING_LIMIT", "Assessed drawing limit")],
            ratios=[fratio("DEALER_STOCK_TURN", "Dealer stock turn (x)", "RETAIL_SALES / DEALER_INVENTORY"),
                    fratio("RETAIL_THROUGHPUT", "Retail throughput / revenue (x)", "RETAIL_SALES / REVENUE"),
                    fratio("DRUL_UTILISATION", "Facility utilisation (x)", "TOTAL_DEBT / DRAWING_LIMIT")]),
        "spreadInputs": {"RETAIL_SALES": 4.5e9, "DEALER_INVENTORY": 0.75e9, "DRAWING_LIMIT": 3.4e9},
        "expectRatios": [("DEALER_STOCK_TURN", 6.0), ("DRUL_UTILISATION", 0.5)],
        "checklist": ["Anchor / OEM dealer agreement", "Dealer onboarding & KYC pack",
                      "Hypothecation of dealer inventory", "Drawing-limit computation",
                      "Insurance of financed stock", "Facility agreement"],
        "cp": [cp("CP-DA", "Executed dealer agreement", True,
                  "Anchor / OEM dealer agreement executed and current."),
               cp("CP-HYP", "Hypothecation of inventory perfected", True,
                  "Charge over the financed retail / utility stock perfected."),
               cp("CP-DL", "Drawing-limit computation signed", True,
                  "Drawing limit working signed off the latest stock statement."),
               cp("CP-INS", "Stock insurance in force", False,
                  "Comprehensive insurance over the financed stock, bank as loss-payee."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Dealer / Retail-Utility Loan CAM", "DEALER_RETAIL_UTILITY",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Dealer Facilities"),
             pfsec("scf_program", "Dealer Programme"), pfsec("financials", "Financial Analysis"),
             pfsec("ratios", "Channel Ratios"), pfsec("rating", "Rating"), pfsec("pricing", "Pricing"),
             pfsec("covenants", "Covenants"), pfsec("routing", "Approval Routing"),
             pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 4. Exchange House
    {
        "name": "Exchange House (money-services / remittance)",
        "segment": "EXCHANGE_HOUSE", "segmentLabel": "Exchange house (money services)", "sector": "FINANCIAL_SERVICES",
        "modelKey": "exchange-house-rating-v1", "templateKey": "fin-exchange-house",
        "checklistKey": "EXCHANGE_HOUSE_CAM", "cpKey": "EXCHANGE_HOUSE",
        "proposalFormatKey": "EXCHANGE_HOUSE",
        "signatureRatio": "TURNOVER_TO_NW",
        "model": model(
            "exchange-house-rating-v1", "Exchange-House Rating", "EXCHANGE_HOUSE",
            qual=[
                dropdown("regulatory_standing", "Money-services regulatory standing", 0.40, True,
                         [opt("Licensed, clean record", 90), opt("Minor observations", 60), opt("Under action", 25)],
                         "Assess the exchange house's money-services / remittance licence standing and any regulatory action."),
                dropdown("aml_cft_framework", "AML / CFT framework", 0.35, True,
                         [opt("Strong", 90), opt("Adequate", 60), opt("Weak", 25)],
                         "Assess the strength of the AML/CFT and transaction-monitoring framework (heightened for remittance)."),
                dropdown("correspondent_network", "Correspondent-bank network", 0.25, False,
                         [opt("Strong", 85), opt("Adequate", 55), opt("Limited", 30)],
                         "Assess the breadth and quality of the correspondent-banking and settlement network."),
            ],
            quant=[
                number_module("turnover_to_nw", "Turnover / net worth (throughput gearing, x)", 0.40, True,
                              [bmax(10, 90), bmax(20, 60), bmax(999, 30)], "RATIO:TURNOVER_TO_NW"),
                number_module("float_to_nw", "Settlement float / net worth (x)", 0.30, True,
                              [bmax(0.75, 90), bmax(1.5, 60), bmax(999, 30)], "RATIO:FLOAT_TO_NW"),
                number_module("capital_to_float", "Regulatory capital / float cover (x)", 0.30, False,
                              [bmin(0.5, 90), bmin(0.25, 60), bmin(0, 30)], "RATIO:CAPITAL_TO_FLOAT"),
            ],
            mandatory=["regulatory_standing", "aml_cft_framework", "turnover_to_nw", "float_to_nw"]),
        "template": template(
            "fin-exchange-house", "Exchange-house turnover chart", "EXCHANGE_HOUSE",
            inputs=[finput("REMITTANCE_TURNOVER", "Annual remittance / FX turnover"),
                    finput("SETTLEMENT_FLOAT", "Average settlement float"),
                    finput("REGULATORY_CAPITAL", "Regulatory capital held")],
            ratios=[fratio("TURNOVER_TO_NW", "Turnover / net worth (x)", "REMITTANCE_TURNOVER / NET_WORTH"),
                    fratio("FLOAT_TO_NW", "Float / net worth (x)", "SETTLEMENT_FLOAT / NET_WORTH"),
                    fratio("CAPITAL_TO_FLOAT", "Capital / float cover (x)", "REGULATORY_CAPITAL / SETTLEMENT_FLOAT")]),
        "spreadInputs": {"REMITTANCE_TURNOVER": 28e9, "SETTLEMENT_FLOAT": 1.4e9, "REGULATORY_CAPITAL": 0.7e9},
        "expectRatios": [("TURNOVER_TO_NW", 10.0), ("FLOAT_TO_NW", 0.5)],
        "checklist": ["Money-services / remittance licence", "Board resolution & borrowing authority",
                      "AML / CFT & sanctions-screening policy", "Correspondent-bank arrangements summary",
                      "Latest audited financials", "Regulatory capital certificate"],
        "cp": [cp("CP-LIC", "Money-services licence verified", True,
                  "Valid remittance / money-services licence verified and on file."),
               cp("CP-BR", "Board resolution", True),
               cp("CP-AML", "AML / CFT compliance confirmation", True,
                  "Independent confirmation of AML/CFT framework and no regulatory action."),
               cp("CP-CAP", "Regulatory capital certificate", True,
                  "Regulatory capital at or above the licensing floor, certified."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Exchange House CAM", "EXCHANGE_HOUSE",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Facilities"),
             pfsec("financials", "Financial Analysis"), pfsec("ratios", "Prudential Ratios"),
             pfsec("rating", "Rating"), pfsec("capital", "Capital"), pfsec("pricing", "Pricing"),
             pfsec("covenants", "Covenants"), pfsec("routing", "Approval Routing"),
             pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 5. Factoring (Supply Chain)
    {
        "name": "Factoring (Supply Chain)",
        "segment": "FACTORING_SCF", "segmentLabel": "Factoring (supply chain)", "sector": "SUPPLY_CHAIN",
        "modelKey": "factoring-scf-rating-v1", "templateKey": "fin-factoring-scf",
        "checklistKey": "FACTORING_SCF_CAM", "cpKey": "FACTORING_SCF",
        "proposalFormatKey": "FACTORING_SCF",
        "signatureRatio": "ADVANCE_RATE",
        "model": model(
            "factoring-scf-rating-v1", "Factoring (Supply-Chain) Rating", "FACTORING_SCF",
            qual=[
                dropdown("debtor_quality", "Underlying debtor quality", 0.35, True,
                         [opt("Investment-grade debtors", 90), opt("Adequate", 60), opt("Weak", 30)],
                         "Assess the credit quality of the debtors on the factored receivables (the primary repayment source)."),
                dropdown("recourse_type", "Recourse type", 0.25, True,
                         [opt("Full recourse", 85), opt("Partial recourse", 65), opt("Non-recourse", 45)],
                         "Assess whether the factoring is with full, partial or no recourse to the seller."),
                dropdown("verification_process", "Invoice verification process", 0.20, False,
                         [opt("Robust", 85), opt("Adequate", 55), opt("Weak", 30)],
                         "Assess the discipline of invoice verification and debtor confirmation."),
                dropdown("debtor_concentration", "Debtor concentration", 0.20, False,
                         [opt("Diversified", 85), opt("Moderate", 60), opt("Concentrated", 25)],
                         "Assess concentration across the debtor pool underlying the factored receivables."),
            ],
            quant=[
                number_module("advance_rate", "Advance rate (advances / factored receivables, x)", 0.40, True,
                              [bmax(0.8, 90), bmax(0.9, 60), bmax(999, 30)], "RATIO:ADVANCE_RATE"),
                number_module("dilution_rate", "Dilution rate (x)", 0.30, True,
                              [bmax(0.03, 90), bmax(0.06, 60), bmax(999, 30)], "RATIO:DILUTION_RATE"),
                number_module("factoring_leverage", "Factoring leverage (advances / net worth, x)", 0.30, False,
                              [bmax(2.0, 90), bmax(3.5, 60), bmax(999, 30)], "RATIO:FACTORING_LEVERAGE"),
            ],
            mandatory=["debtor_quality", "recourse_type", "advance_rate", "dilution_rate"]),
        "template": template(
            "fin-factoring-scf", "Factoring advance / dilution chart", "FACTORING_SCF",
            inputs=[finput("FACTORED_RECEIVABLES", "Factored receivables outstanding"),
                    finput("ADVANCES_DRAWN", "Advances drawn against receivables"),
                    finput("DILUTION_RESERVE", "Dilution / dispute reserve")],
            ratios=[fratio("ADVANCE_RATE", "Advance rate (x)", "ADVANCES_DRAWN / FACTORED_RECEIVABLES"),
                    fratio("DILUTION_RATE", "Dilution rate (x)", "DILUTION_RESERVE / FACTORED_RECEIVABLES"),
                    fratio("FACTORING_LEVERAGE", "Factoring leverage (x)", "ADVANCES_DRAWN / NET_WORTH")]),
        "spreadInputs": {"FACTORED_RECEIVABLES": 5e9, "ADVANCES_DRAWN": 4e9, "DILUTION_RESERVE": 0.25e9},
        "expectRatios": [("ADVANCE_RATE", 0.8), ("DILUTION_RATE", 0.05)],
        "checklist": ["Factoring master agreement", "Debtor onboarding & verification pack",
                      "Assignment / notice of assignment", "Debtor acknowledgement (where notified)",
                      "Dilution reserve confirmation", "Receivables ageing statement"],
        "cp": [cp("CP-FMA", "Executed factoring master agreement", True,
                  "Factoring master agreement executed by seller and financier."),
               cp("CP-ASGN", "Perfected assignment of receivables", True,
                  "Assignment of factored receivables perfected and registered."),
               cp("CP-ACK", "Debtor acknowledgement", False,
                  "Debtor acknowledges the assignment (where the factoring is notified)."),
               cp("CP-DILUT", "Dilution reserve funded", True,
                  "Dilution / dispute reserve funded per programme terms."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Factoring (Supply Chain) CAM", "FACTORING_SCF",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Programme Facilities"),
             pfsec("scf_program", "Factoring Programme"), pfsec("financials", "Financial Analysis"),
             pfsec("ratios", "Advance & Dilution Ratios"), pfsec("rating", "Rating"),
             pfsec("pricing", "Pricing"), pfsec("covenants", "Covenants"),
             pfsec("routing", "Approval Routing"), pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 6. Fully Cash Collateralized
    {
        "name": "Fully Cash Collateralized",
        "segment": "FULLY_CASH_COLLATERALIZED", "segmentLabel": "Fully cash collateralized", "sector": "DIVERSIFIED",
        "modelKey": "fully-cash-collateralized-rating-v1", "templateKey": "fin-fully-cash-collateralized",
        "checklistKey": "FULLY_CASH_COLLATERALIZED_CAM", "cpKey": "FULLY_CASH_COLLATERALIZED",
        "proposalFormatKey": "FULLY_CASH_COLLATERALIZED",
        "signatureRatio": "CASH_COVER",
        "model": model(
            "fully-cash-collateralized-rating-v1", "Fully Cash-Collateralized Rating", "FULLY_CASH_COLLATERALIZED",
            qual=[
                dropdown("lien_perfection", "Lien perfection over cash", 0.40, True,
                         [opt("Perfected, lien-marked", 90), opt("In progress", 55), opt("Unperfected", 20)],
                         "Assess whether the cash collateral is lien-marked and the charge perfected — the sole real risk driver."),
                dropdown("collateral_currency_match", "Collateral currency match", 0.35, True,
                         [opt("Same-currency cash", 90), opt("Hedged mismatch", 65), opt("Unhedged FX mismatch", 35)],
                         "Assess whether the cash collateral currency matches the facility currency (FX-gap risk)."),
                dropdown("margin_call_mechanism", "Margin-call / top-up mechanism", 0.25, False,
                         [opt("Automated", 85), opt("Manual", 60), opt("None", 30)],
                         "Assess the mechanism to restore cover if the facility exposure rises."),
            ],
            quant=[
                number_module("cash_cover", "Cash cover (cash collateral / exposure, x)", 0.60, True,
                              [bmin(1.1, 90), bmin(1.0, 60), bmin(0, 30)], "RATIO:CASH_COVER"),
                number_module("margin_buffer", "Margin buffer over exposure (x)", 0.40, False,
                              [bmin(0.1, 90), bmin(0.05, 60), bmin(0, 30)], "RATIO:MARGIN_BUFFER"),
            ],
            mandatory=["lien_perfection", "cash_cover"]),
        "template": template(
            "fin-fully-cash-collateralized", "Fully cash-collateralized chart", "FULLY_CASH_COLLATERALIZED",
            inputs=[finput("CASH_COLLATERAL", "Lien-marked cash collateral"),
                    finput("FACILITY_EXPOSURE", "Facility exposure covered"),
                    finput("LIEN_MARGIN", "Retained lien margin")],
            ratios=[fratio("CASH_COVER", "Cash cover (x)", "CASH_COLLATERAL / FACILITY_EXPOSURE"),
                    fratio("MARGIN_BUFFER", "Margin buffer (x)", "(CASH_COLLATERAL - FACILITY_EXPOSURE) / FACILITY_EXPOSURE"),
                    fratio("COLLATERAL_TO_DEBT", "Collateral / total debt (x)", "CASH_COLLATERAL / TOTAL_DEBT")]),
        "spreadInputs": {"CASH_COLLATERAL": 2.2e9, "FACILITY_EXPOSURE": 2e9, "LIEN_MARGIN": 0.2e9},
        "expectRatios": [("CASH_COVER", 1.1), ("MARGIN_BUFFER", 0.1)],
        "checklist": ["Cash-collateral / lien agreement", "Lien-marked deposit confirmation",
                      "Board resolution", "Facility agreement",
                      "Margin-maintenance undertaking", "Set-off / netting confirmation"],
        "cp": [cp("CP-LIEN", "Perfected lien over cash collateral", True,
                  "Cash collateral lien-marked and the charge perfected before drawdown."),
               cp("CP-DEP", "Deposit confirmation", True,
                  "Lien-marked deposit balance confirmed at or above the required cover."),
               cp("CP-SETOFF", "Set-off / netting rights", True,
                  "Bank's set-off and netting rights over the deposit documented."),
               cp("CP-TOPUP", "Margin top-up undertaking", False,
                  "Borrower undertaking to restore cover on any shortfall."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Fully Cash Collateralized CAM", "FULLY_CASH_COLLATERALIZED",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Facilities"),
             pfsec("collateral", "Cash Collateral & Lien"), pfsec("ratios", "Cash Cover"),
             pfsec("rating", "Rating"), pfsec("pricing", "Pricing"), pfsec("covenants", "Covenants"),
             pfsec("routing", "Approval Routing"), pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 7. Mutual Funds / AMC
    {
        "name": "Mutual Funds / AMC",
        "segment": "MUTUAL_FUND_AMC", "segmentLabel": "Mutual funds / AMC", "sector": "FINANCIAL_SERVICES",
        "modelKey": "mutual-fund-amc-rating-v1", "templateKey": "fin-mutual-fund-amc",
        "checklistKey": "MUTUAL_FUND_AMC_CAM", "cpKey": "MUTUAL_FUND_AMC",
        "proposalFormatKey": "MUTUAL_FUND_AMC",
        "signatureRatio": "AUM_GROWTH",
        "model": model(
            "mutual-fund-amc-rating-v1", "Mutual-Fund / AMC Rating", "MUTUAL_FUND_AMC",
            qual=[
                dropdown("regulatory_standing", "Regulatory standing (MF regulations)", 0.35, True,
                         [opt("Compliant, clean record", 90), opt("Minor observations", 60), opt("Under action", 25)],
                         "Assess the AMC's mutual-fund regulatory standing and any supervisory action."),
                dropdown("sponsor_strength", "Sponsor strength", 0.30, True,
                         [opt("Strong", 90), opt("Adequate", 60), opt("Weak", 30)],
                         "Assess the strength and commitment of the AMC's sponsor(s)."),
                dropdown("fund_performance_track", "Fund performance track record", 0.35, False,
                         [opt("Top-quartile", 85), opt("Median", 60), opt("Laggard", 30)],
                         "Assess the performance track record across the AMC's fund suite (drives AUM stickiness)."),
            ],
            quant=[
                number_module("aum_growth", "AUM growth (YoY, x)", 0.35, True,
                              [bmin(0.15, 90), bmin(0.05, 60), bmin(-9, 30)], "RATIO:AUM_GROWTH"),
                number_module("expense_ratio", "Expense ratio (opex / AUM, x)", 0.30, True,
                              [bmax(0.015, 90), bmax(0.025, 60), bmax(999, 30)], "RATIO:EXPENSE_RATIO"),
                number_module("fee_margin", "Management-fee margin (fees / AUM, x)", 0.35, False,
                              [bmin(0.012, 90), bmin(0.008, 60), bmin(0, 30)], "RATIO:FEE_MARGIN"),
            ],
            mandatory=["regulatory_standing", "sponsor_strength", "aum_growth", "expense_ratio"]),
        "template": template(
            "fin-mutual-fund-amc", "AMC AUM / expense chart", "MUTUAL_FUND_AMC",
            inputs=[finput("AUM_CURRENT", "Assets under management (current)"),
                    finput("AUM_PRIOR", "Assets under management (prior year)"),
                    finput("MANAGEMENT_FEES", "Management fees"),
                    finput("AMC_OPEX", "AMC operating expenses")],
            ratios=[fratio("AUM_GROWTH", "AUM growth (x)", "(AUM_CURRENT - AUM_PRIOR) / AUM_PRIOR"),
                    fratio("EXPENSE_RATIO", "Expense ratio (x)", "AMC_OPEX / AUM_CURRENT"),
                    fratio("FEE_MARGIN", "Fee margin (x)", "MANAGEMENT_FEES / AUM_CURRENT")]),
        "spreadInputs": {"AUM_CURRENT": 120e9, "AUM_PRIOR": 100e9, "MANAGEMENT_FEES": 1.8e9, "AMC_OPEX": 1.2e9},
        "expectRatios": [("AUM_GROWTH", 0.2), ("EXPENSE_RATIO", 0.01)],
        "checklist": ["AMC registration certificate", "Board resolution & borrowing authority",
                      "Latest audited financials + AUM statement", "Scheme information & offer documents",
                      "Sponsor support / net-worth undertaking", "Regulatory net-worth certificate"],
        "cp": [cp("CP-REG", "AMC registration verified", True,
                  "Valid AMC / mutual-fund registration verified and on file."),
               cp("CP-BR", "Board resolution", True),
               cp("CP-NW", "Regulatory net-worth certificate", True,
                  "AMC net worth at or above the regulatory minimum, certified."),
               cp("CP-SPON", "Sponsor support confirmation", False,
                  "Sponsor support / net-worth undertaking held on file."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Mutual Funds / AMC CAM", "MUTUAL_FUND_AMC",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Facilities"),
             pfsec("financials", "Financial Analysis"), pfsec("ratios", "AUM & Expense Ratios"),
             pfsec("rating", "Rating"), pfsec("capital", "Capital"), pfsec("pricing", "Pricing"),
             pfsec("covenants", "Covenants"), pfsec("routing", "Approval Routing"),
             pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 8. Service Providers
    {
        "name": "Service Providers",
        "segment": "SERVICE_PROVIDER", "segmentLabel": "Service providers", "sector": "SERVICES",
        "modelKey": "service-provider-rating-v1", "templateKey": "fin-service-provider",
        "checklistKey": "SERVICE_PROVIDER_CAM", "cpKey": "SERVICE_PROVIDER",
        "proposalFormatKey": "SERVICE_PROVIDER",
        "signatureRatio": "RECEIVABLE_DAYS",
        "model": model(
            "service-provider-rating-v1", "Service-Providers Rating", "SERVICE_PROVIDER",
            qual=[
                dropdown("contract_quality", "Contract quality & tenure", 0.35, True,
                         [opt("Long-term contracted", 90), opt("Rolling", 60), opt("Spot / ad-hoc", 30)],
                         "Assess the tenure and firmness of the service contracts underpinning revenue."),
                dropdown("delivery_track_record", "Delivery / execution track record", 0.35, True,
                         [opt("Strong", 90), opt("Adequate", 60), opt("Weak", 30)],
                         "Assess the provider's delivery track record and service-level performance."),
                dropdown("client_concentration", "Client concentration", 0.30, False,
                         [opt("Diversified", 85), opt("Moderate", 60), opt("Concentrated", 30)],
                         "Assess concentration across the provider's client base."),
            ],
            quant=[
                number_module("receivable_days", "Receivable days", 0.35, True,
                              [bmax(60, 90), bmax(90, 60), bmax(999, 30)], "RATIO:RECEIVABLE_DAYS"),
                number_module("svc_ebitda_margin", "EBITDA margin (x)", 0.35, True,
                              [bmin(0.18, 90), bmin(0.1, 60), bmin(0, 30)], "RATIO:SVC_EBITDA_MARGIN"),
                number_module("backlog_cover", "Order-backlog cover (x of revenue)", 0.30, False,
                              [bmin(1.5, 90), bmin(1.0, 60), bmin(0, 30)], "RATIO:BACKLOG_COVER"),
            ],
            mandatory=["contract_quality", "delivery_track_record", "receivable_days", "svc_ebitda_margin"]),
        "template": template(
            "fin-service-provider", "Service-provider receivables chart", "SERVICE_PROVIDER",
            inputs=[finput("TRADE_RECEIVABLES", "Trade receivables"),
                    finput("CONTRACT_BACKLOG", "Contracted order backlog")],
            ratios=[fratio("RECEIVABLE_DAYS", "Receivable days", "TRADE_RECEIVABLES / REVENUE * 365"),
                    fratio("SVC_EBITDA_MARGIN", "EBITDA margin (x)", "EBITDA / REVENUE"),
                    fratio("BACKLOG_COVER", "Backlog cover (x)", "CONTRACT_BACKLOG / REVENUE")]),
        "spreadInputs": {"TRADE_RECEIVABLES": 1e9, "CONTRACT_BACKLOG": 7.5e9},
        "expectRatios": [("RECEIVABLE_DAYS", 73.0), ("SVC_EBITDA_MARGIN", 0.18)],
        "checklist": ["Key service contracts / master service agreements", "Board resolution",
                      "Latest audited financials", "Receivables ageing statement",
                      "Order-backlog statement", "Facility agreement"],
        "cp": [cp("CP-FA", "Executed facility agreement", True,
                  "Facility agreement executed by all parties."),
               cp("CP-MSA", "Key service contracts on file", True,
                  "Executed master service agreements underpinning the assessed cash flows."),
               cp("CP-HYP", "Hypothecation of receivables", True,
                  "Charge over trade receivables perfected."),
               cp("CP-ASGN", "Assignment of contract proceeds", False,
                  "Assignment of proceeds from the key contracts, where applicable."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Service Providers CAM", "SERVICE_PROVIDER",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Facilities"),
             pfsec("financials", "Financial Analysis"), pfsec("ratios", "Receivables & Margin Ratios"),
             pfsec("rating", "Rating"), pfsec("pricing", "Pricing"), pfsec("covenants", "Covenants"),
             pfsec("routing", "Approval Routing"), pfsec("provenance", "Provenance")]),
    },
    # --------------------------------------------------------------- 9. Stock Exchange Broker
    {
        "name": "Stock Exchange Broker",
        "segment": "STOCK_EXCHANGE_BROKER", "segmentLabel": "Stock exchange broker", "sector": "CAPITAL_MARKETS",
        "modelKey": "stock-exchange-broker-rating-v1", "templateKey": "fin-stock-exchange-broker",
        "checklistKey": "STOCK_EXCHANGE_BROKER_CAM", "cpKey": "STOCK_EXCHANGE_BROKER",
        "proposalFormatKey": "STOCK_EXCHANGE_BROKER",
        "signatureRatio": "NET_CAPITAL_RATIO",
        "model": model(
            "stock-exchange-broker-rating-v1", "Stock-Exchange-Broker Rating", "STOCK_EXCHANGE_BROKER",
            qual=[
                dropdown("regulatory_standing", "Regulator / exchange standing", 0.40, True,
                         [opt("Registered, clean record", 90), opt("Minor observations", 60), opt("Under action", 25)],
                         "Assess the broker's regulator / exchange registration standing and any disciplinary action."),
                dropdown("risk_controls", "Risk & margin controls", 0.30, True,
                         [opt("Strong systems", 85), opt("Adequate", 55), opt("Weak", 25)],
                         "Assess margin-management, surveillance and risk-control systems."),
                dropdown("client_franchise", "Client franchise", 0.30, False,
                         [opt("Strong institutional", 85), opt("Retail-diversified", 65), opt("Concentrated", 35)],
                         "Assess the breadth and stickiness of the client franchise."),
            ],
            quant=[
                number_module("net_capital_ratio", "Net capital / regulatory minimum (x)", 0.50, True,
                              [bmin(1.5, 90), bmin(1.0, 60), bmin(0, 30)], "RATIO:NET_CAPITAL_RATIO"),
                number_module("margin_cover", "Margin cover (net worth / client funding, x)", 0.25, False,
                              [bmin(2, 90), bmin(1.25, 60), bmin(0, 30)], "RATIO:MARGIN_COVER"),
                number_module("position_leverage", "Proprietary position leverage (x)", 0.25, False,
                              [bmax(3, 90), bmax(5, 60), bmax(999, 30)], "RATIO:POSITION_LEVERAGE"),
            ],
            mandatory=["regulatory_standing", "net_capital_ratio"]),
        "template": template(
            "fin-stock-exchange-broker", "Stock-broker net-capital chart", "STOCK_EXCHANGE_BROKER",
            inputs=[finput("BROKER_NET_CAPITAL", "Net capital (liquid)"),
                    finput("BROKER_MIN_CAPITAL", "Regulatory minimum net capital"),
                    finput("CLIENT_MARGIN_BOOK", "Client margin funding book"),
                    finput("PROP_POSITIONS", "Proprietary trading positions")],
            ratios=[fratio("NET_CAPITAL_RATIO", "Net capital ratio (x)", "BROKER_NET_CAPITAL / BROKER_MIN_CAPITAL"),
                    fratio("MARGIN_COVER", "Margin cover (x)", "NET_WORTH / CLIENT_MARGIN_BOOK"),
                    fratio("POSITION_LEVERAGE", "Position leverage (x)", "PROP_POSITIONS / BROKER_NET_CAPITAL")]),
        "spreadInputs": {"BROKER_NET_CAPITAL": 3e9, "BROKER_MIN_CAPITAL": 2e9,
                         "CLIENT_MARGIN_BOOK": 1.4e9, "PROP_POSITIONS": 6e9},
        "expectRatios": [("NET_CAPITAL_RATIO", 1.5), ("MARGIN_COVER", 2.0)],
        "checklist": ["Board resolution", "Regulator & exchange registration certificates",
                      "Latest net-worth certificate (CA-certified)", "Net-capital / liquid-assets computation",
                      "Client-funding & margin policy", "Pledge of securities / margin arrangement"],
        "cp": [cp("CP-BR", "Board resolution", True),
               cp("CP-REG", "Regulator + exchange registration valid", True,
                  "Active registration + exchange membership; no debarment."),
               cp("CP-NW", "CA-certified net-worth certificate", True,
                  "Net worth at or above the regulatory minimum, CA-certified."),
               cp("CP-PLEDGE", "Pledge / charge over securities", True,
                  "Charge over securities / margin created per sanction."),
               cp("CP-SEGREG", "Client-funds segregation confirmation", True,
                  "Confirmation of client-fund segregation from the broker's own funds."),
               cp("CP-MAC", "No material adverse change", True)],
        "proposalFormat": proposal_format(
            "Stock Exchange Broker CAM", "STOCK_EXCHANGE_BROKER",
            [pfsec("executive_summary", "Executive Summary"), pfsec("facilities", "Facilities"),
             pfsec("collateral", "Securities Pledge"), pfsec("ratios", "Net-Capital Ratios"),
             pfsec("rating", "Rating"), pfsec("pricing", "Pricing"), pfsec("covenants", "Covenants"),
             pfsec("routing", "Approval Routing"), pfsec("provenance", "Provenance")]),
    },
]


# --------------------------------------------------------------- master helpers

class SeedError(RuntimeError):
    pass


def _submit_approve(master_type, record_key, payload, jurisdiction=None):
    """Maker submits, a DIFFERENT checker approves -> ACTIVE. Raises on any failure."""
    body = {"recordKey": record_key, "payload": payload}
    if jurisdiction:
        body["jurisdiction"] = jurisdiction
    st, sub = call("POST", f"/config/api/masters/{master_type}", body, actor=MAKER)
    if st != 200 or not isinstance(sub, dict) or "id" not in sub:
        raise SeedError(f"submit {master_type}/{record_key}: HTTP {st} {sub}")
    st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor=CHECKER)
    if st != 200 or not isinstance(ap, dict) or ap.get("status") != "ACTIVE":
        raise SeedError(f"approve {master_type}/{record_key}: HTTP {st} {ap}")
    return ap


def _active_exists(master_type, record_key):
    st, _ = call("GET", f"/config/api/masters/{master_type}/{record_key}")
    return st == 200


def ensure_master(master_type, record_key, payload, jurisdiction=None, stats=None):
    """Idempotent: skip if an ACTIVE record already exists, else submit + approve."""
    if _active_exists(master_type, record_key):
        if stats is not None:
            stats["skipped"] += 1
        return "skip"
    _submit_approve(master_type, record_key, payload, jurisdiction)
    if stats is not None:
        stats["created"] += 1
    return "created"


def ensure_segment_codes(stats=None):
    """Extend the SEGMENT CODE_VALUE domain with the 9 additional segments.

    GET-merge-submit: read the current ACTIVE SEGMENT record, append only the codes
    that are missing (preserving existing values + order + sortOrder), and submit a new
    governed version only when something actually changed."""
    st, rec = call("GET", "/config/api/masters/CODE_VALUE/SEGMENT")
    if st != 200 or not isinstance(rec, dict) or not isinstance(rec.get("payload"), dict):
        raise SeedError(f"read CODE_VALUE/SEGMENT: HTTP {st} {rec}")
    payload = rec["payload"]
    values = list(payload.get("values") or [])
    existing_codes = {str(v.get("code")) for v in values if isinstance(v, dict)}
    next_sort = 1 + max([int(v.get("sortOrder", i)) for i, v in enumerate(values)
                         if isinstance(v, dict)] or [-1])
    added = []
    for fmt in FORMATS:
        code = fmt["segment"]
        if code in existing_codes:
            continue
        values.append({"code": code, "label": fmt["segmentLabel"], "sortOrder": next_sort})
        existing_codes.add(code)
        added.append(code)
        next_sort += 1
    if not added:
        if stats is not None:
            stats["skipped"] += 1
        return []
    new_payload = {"domain": "SEGMENT",
                   "label": payload.get("label", "Counterparty segments"),
                   "values": values}
    _submit_approve("CODE_VALUE", "SEGMENT", new_payload)
    if stats is not None:
        stats["created"] += 1
    return added


# --------------------------------------------------------------- orchestration

def seed_all(verbose=True):
    """Seed all 9 ADDITIONAL CAM packs. Idempotent + deterministic.

    Returns a summary dict: {created, skipped, segments_added, formats}."""
    stats = {"created": 0, "skipped": 0}

    # Reachability warm-up (also confirms config-service is answering).
    st, _ = call("GET", "/config/api/masters/CODE_VALUE/SEGMENT")
    if st == 0:
        raise SeedError(f"gateway not reachable at {GW} (GET SEGMENT -> connection error)")

    segments_added = ensure_segment_codes(stats)

    for fmt in FORMATS:
        ensure_master("MODEL_DEFINITION", fmt["modelKey"], fmt["model"], stats=stats)
        ensure_master("FINANCIAL_TEMPLATE", fmt["templateKey"], fmt["template"], stats=stats)
        ensure_master("CHECKLIST_MASTER", fmt["checklistKey"],
                      {"items": fmt["checklist"], "segment": fmt["segment"],
                       "camFormat": fmt["name"]}, stats=stats)
        ensure_master("CP_MASTER", fmt["cpKey"],
                      {"items": fmt["cp"], "segment": fmt["segment"],
                       "camFormat": fmt["name"]}, stats=stats)
        ensure_master("PROPOSAL_FORMAT", fmt["proposalFormatKey"], fmt["proposalFormat"], stats=stats)
        if verbose:
            print(f"  [OK]  {fmt['name']:42s} segment={fmt['segment']:26s} "
                  f"model={fmt['modelKey']}  template={fmt['templateKey']}")

    summary = {"created": stats["created"], "skipped": stats["skipped"],
               "segments_added": segments_added, "formats": len(FORMATS)}
    if verbose:
        print("\n" + "=" * 76)
        print(" Helix CAM-format packs · ADDITIONAL / SPECIALTY-CHANNEL cluster")
        print("=" * 76)
        print(f" gateway               : {GW}")
        print(f" formats seeded        : {summary['formats']} "
              f"(MODEL_DEFINITION + FINANCIAL_TEMPLATE + CHECKLIST_MASTER + CP_MASTER + PROPOSAL_FORMAT each)")
        print(f" master records created: {summary['created']}")
        print(f" master records skipped: {summary['skipped']}  (already active — idempotent re-run)")
        print(f" SEGMENT codes added   : {segments_added if segments_added else '(all already present)'}")
        print("=" * 76)
        print(" Re-run is idempotent: existing ACTIVE records are skipped, SEGMENT is GET-merged.")
    return summary


def main():
    st, _ = call("GET", "/config/api/masters/CODE_VALUE/SEGMENT")
    if st == 0:
        print(f"Gateway not reachable at {GW}.")
        print("Start the stack first:  bash scripts/run-all.sh   (health-gated on :8080-8088)")
        return 2
    try:
        seed_all(verbose=True)
    except SeedError as e:
        print(f"SEED FAILED: {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
