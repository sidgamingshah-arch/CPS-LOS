#!/usr/bin/env python3
"""
CAM-format config packs — SPECIALTY / FI cluster (config-as-data seeder).

Ships the 8 SPECIALTY/FI Credit-Appraisal-Memo (CAM) formats as pure master
data so the EXISTING config-driven engines (model-config, financial-template,
CAD checklist, CP register) emit a segment-appropriate CAM with NO code branch.
There is NO service or frontend code here — every format is authored through the
generic Master-Data engine (`POST /config/api/masters/{type}` + checker approve,
maker != checker for SoD), exactly like `scripts/seed_demo_data.py` drives the
platform only through the gateway.

THE 8 FORMATS (each = a new SEGMENT + MODEL_DEFINITION + FINANCIAL_TEMPLATE +
CHECKLIST_MASTER + CP_MASTER):

  1. SCF - Vendor Financing        segment SCF_VENDOR
  2. SCF - Dealer Financing        segment SCF_DEALER
  3. IBPC (Inter-Bank Participation Certificate)   segment IBPC
  4. Bank / FI (lending to banks / FIs)            segment BANK_FI
  5. Insurance (lending to insurers)               segment INSURANCE
  6. Broker / Capital Market                       segment BROKER_CAPITAL_MARKET
  7. ECB / Structured Finance                      segment ECB_STRUCTURED
  8. Infrastructure / Priority Sector              segment INFRA_PRIORITY

Each format's FINANCIAL_TEMPLATE augments the canonical 15-input / 8-derived
chart with *segment-appropriate prudential inputs and formula ratios* — CRAR /
Tier-1 / GNPA / LCR for banks, ASM/RSM solvency + combined ratio for insurers,
net-capital / margin-cover for brokers, hedge-ratio / structured-DSCR for ECB,
project-DSCR / debt-equity / PLF for infra, anchor-dependence / dilution for SCF
— reflecting that FI/bank/insurance/broker charts differ from a corporate chart.

Each MODEL_DEFINITION pins its segment via the `selector` so the SAME model
resolver (`GET /config/api/models/resolve`) that serves the corporate models
picks it up as the most-specific match, with segment-appropriate qualitative
(STANDALONE, advisory-scored) + quantitative (MODULE, pulled from the template's
prudential ratios) parameters — never producing a credit-consequential figure.

IDEMPOTENT / RE-RUNNABLE
------------------------
Before creating anything the seeder GETs the ACTIVE record for each (type, key)
and SKIPS it if present — a second run finds everything and creates nothing (no
version churn). The SEGMENT CODE_VALUE domain is extended by GET-merge-submit:
existing codes are preserved and only missing segment codes are appended.

DESIGN NOTES (why these keys are contamination-safe)
----------------------------------------------------
  * CHECKLIST_MASTER recordKeys deliberately do NOT contain the substring
    "SECURED": CadService.initiate() globally prefers the first "SECURED" key
    (CORP_TERM_LOAN_SECURED), so these CAM checklists never change which
    checklist an existing CAD case picks.
  * CP_MASTER recordKeys are format codes, NOT facility types, so
    ConditionPrecedentService.pickPack() (which matches recordKey==facilityType)
    never selects them for an ordinary deal.
  * New SEGMENT codes are free-form strings on the resolver path (no
    Enums.Segment.valueOf on any write path), so they resolve MODEL_DEFINITION /
    FINANCIAL_TEMPLATE cleanly and never out-rank the wildcard corporate default
    for the existing corporate/SME/manufacturing segments.

HOW TO RUN
----------
  1. Start the stack:  bash scripts/run-all.sh   (health-gated on :8080-8088)
  2. Seed:             python3 scripts/seed_cam_packs_specialty.py
  Gateway override:    HELIX_GATEWAY=http://localhost:PORT python3 scripts/seed_cam_packs_specialty.py

The gateway is permissive locally (no login) — only the X-Actor header matters;
the two seeder actors below are distinct so every approval satisfies maker-checker.
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


# --------------------------------------------------------------- the 8 formats
# Each entry declares: the CAM label, its new SEGMENT code + display label, a
# realistic sector tag, and the four master payloads. Every format is fully
# self-contained so a reviewer can read one block and understand the whole pack.

FORMATS = [
    # ------------------------------------------------------------------ 1. SCF Vendor
    {
        "name": "SCF - Vendor Financing",
        "segment": "SCF_VENDOR", "segmentLabel": "SCF - Vendor financing", "sector": "SUPPLY_CHAIN",
        "modelKey": "scf-vendor-rating-v1", "templateKey": "fin-scf-vendor",
        "checklistKey": "SCF_VENDOR_CAM", "cpKey": "SCF_VENDOR",
        "model": model(
            "scf-vendor-rating-v1", "SCF Rating - Vendor Financing", "SCF_VENDOR",
            qual=[
                dropdown("anchor_credit_strength", "Anchor buyer credit strength", 0.35, True,
                         [opt("Investment-grade anchor", 90), opt("Adequate anchor", 60), opt("Sub-IG anchor", 30)],
                         "Assess the anchor buyer's credit quality — in vendor financing the anchor's obligation to pay is the primary risk driver."),
                dropdown("programme_track_record", "Programme track record", 0.25, True,
                         [opt("Established (>3y)", 85), opt("Developing", 55), opt("New programme", 30)],
                         "Assess vintage and repayment behaviour of the anchor's vendor-finance programme."),
                dropdown("dilution_history", "Invoice dilution history", 0.20, False,
                         [opt("Negligible", 85), opt("Occasional", 55), opt("Frequent", 25)],
                         "Assess historical dilution (returns, disputes, short-payments) on assigned receivables."),
                dropdown("vendor_concentration", "Vendor pool concentration", 0.20, False,
                         [opt("Diversified pool", 85), opt("Moderate", 55), opt("Concentrated", 25)],
                         "Assess concentration across the vendor spokes financed under the programme."),
            ],
            quant=[
                number_module("anchor_dependence", "Anchor dependence (anchor receivables / revenue, x)", 0.40, True,
                              [bmax(0.5, 90), bmax(0.75, 60), bmax(1.0, 30)], "RATIO:ANCHOR_DEPENDENCE"),
                number_module("net_leverage", "Net leverage (Net debt / EBITDA, x)", 0.30, True,
                              [bmax(2.0, 90), bmax(3.5, 60), bmax(99, 30)], "RATIO:NET_LEVERAGE"),
                number_module("current_ratio", "Current ratio (x)", 0.30, False,
                              [bmin(1.5, 90), bmin(1.1, 60), bmin(0, 30)], "RATIO:CURRENT_RATIO"),
            ],
            mandatory=["anchor_credit_strength", "programme_track_record", "anchor_dependence", "net_leverage"]),
        "template": template(
            "fin-scf-vendor", "SCF vendor-financing chart", "SCF_VENDOR",
            inputs=[finput("RECEIVABLES_FROM_ANCHOR", "Receivables due from anchor"),
                    finput("DILUTION_RESERVE", "Dilution / dispute reserve"),
                    finput("ANCHOR_PURCHASES", "Annual purchases by anchor")],
            ratios=[fratio("ANCHOR_DEPENDENCE", "Anchor dependence (x)", "RECEIVABLES_FROM_ANCHOR / REVENUE"),
                    fratio("DILUTION_RATE", "Dilution rate (x)", "DILUTION_RESERVE / RECEIVABLES_FROM_ANCHOR"),
                    fratio("RECEIVABLE_DAYS", "Anchor receivable days", "RECEIVABLES_FROM_ANCHOR / REVENUE * 365")]),
        "checklist": ["Anchor programme agreement", "Vendor onboarding & KYC pack",
                      "Assignment of receivables", "Anchor acknowledgement of assignment",
                      "Dilution reserve confirmation", "Invoice financing master agreement"],
        "cp": [cp("CP-APA", "Executed anchor programme agreement", True,
                  "Master SCF programme agreement executed by anchor and financier."),
               cp("CP-ASGN", "Perfected assignment of receivables", True,
                  "Assignment of vendor receivables perfected and registered."),
               cp("CP-ACK", "Anchor acknowledgement of assignment", True,
                  "Anchor acknowledges the assignment and agrees to pay into the designated account."),
               cp("CP-ESCROW", "Collection / escrow account live", True,
                  "Designated collection account operational for programme proceeds."),
               cp("CP-DILUT", "Dilution reserve funded", False,
                  "Dilution / dispute reserve funded per programme terms."),
               cp("CP-MAC", "No material adverse change", True)],
    },
    # ------------------------------------------------------------------ 2. SCF Dealer
    {
        "name": "SCF - Dealer Financing",
        "segment": "SCF_DEALER", "segmentLabel": "SCF - Dealer financing", "sector": "SUPPLY_CHAIN",
        "modelKey": "scf-dealer-rating-v1", "templateKey": "fin-scf-dealer",
        "checklistKey": "SCF_DEALER_CAM", "cpKey": "SCF_DEALER",
        "model": model(
            "scf-dealer-rating-v1", "SCF Rating - Dealer Financing", "SCF_DEALER",
            qual=[
                dropdown("anchor_credit_strength", "Anchor (OEM) credit strength", 0.30, True,
                         [opt("Investment-grade anchor", 90), opt("Adequate anchor", 60), opt("Sub-IG anchor", 30)],
                         "Assess the anchor / OEM credit quality supporting the dealer channel."),
                dropdown("buyback_support", "Anchor buyback / repurchase support", 0.25, True,
                         [opt("Full buyback", 90), opt("Partial buyback", 60), opt("No buyback", 30)],
                         "Assess the anchor's stock-repurchase / buyback support for unsold dealer inventory."),
                dropdown("channel_track_record", "Dealer channel track record", 0.25, False,
                         [opt("Established", 85), opt("Developing", 55), opt("New", 30)],
                         "Assess the maturity and conduct of the anchor's dealer-finance channel."),
                dropdown("dealer_concentration", "Dealer pool concentration", 0.20, False,
                         [opt("Diversified", 85), opt("Moderate", 55), opt("Concentrated", 25)],
                         "Assess concentration across the dealers financed under the programme."),
            ],
            quant=[
                number_module("inventory_turnover", "Inventory turnover (channel sales / inventory, x)", 0.40, True,
                              [bmin(6, 90), bmin(4, 60), bmin(0, 30)], "RATIO:INVENTORY_TURNOVER"),
                number_module("stock_holding_days", "Stock holding days", 0.30, False,
                              [bmax(45, 90), bmax(75, 60), bmax(999, 30)], "RATIO:STOCK_HOLDING_DAYS"),
                number_module("net_leverage", "Net leverage (Net debt / EBITDA, x)", 0.30, True,
                              [bmax(2.0, 90), bmax(3.5, 60), bmax(99, 30)], "RATIO:NET_LEVERAGE"),
            ],
            mandatory=["anchor_credit_strength", "buyback_support", "inventory_turnover", "net_leverage"]),
        "template": template(
            "fin-scf-dealer", "SCF dealer-financing chart", "SCF_DEALER",
            inputs=[finput("DEALER_INVENTORY", "Dealer inventory (anchor stock)"),
                    finput("CHANNEL_SALES", "Annual channel sales"),
                    finput("PAYABLES_TO_ANCHOR", "Payables to anchor")],
            ratios=[fratio("INVENTORY_TURNOVER", "Inventory turnover (x)", "CHANNEL_SALES / DEALER_INVENTORY"),
                    fratio("STOCK_HOLDING_DAYS", "Stock holding days", "DEALER_INVENTORY / CHANNEL_SALES * 365"),
                    fratio("PAYABLE_DAYS", "Anchor payable days", "PAYABLES_TO_ANCHOR / COGS * 365")]),
        "checklist": ["Anchor dealer programme agreement", "Dealer onboarding & KYC pack",
                      "Hypothecation of dealer inventory", "Anchor buyback / repurchase undertaking",
                      "Stock audit report", "Dealer financing master agreement"],
        "cp": [cp("CP-DPA", "Executed dealer programme agreement", True,
                  "Master dealer-finance programme agreement executed."),
               cp("CP-HYP", "Hypothecation of inventory perfected", True,
                  "Charge over the dealer stock financed under the programme perfected."),
               cp("CP-BUYBACK", "Anchor buyback undertaking on file", True,
                  "Anchor stock-repurchase undertaking executed and held."),
               cp("CP-STOCK", "Opening stock audit", False,
                  "Independent stock audit performed at onboarding."),
               cp("CP-ESCROW", "Sale-proceeds routing established", True,
                  "Dealer sale proceeds routed to the designated account."),
               cp("CP-MAC", "No material adverse change", True)],
    },
    # ------------------------------------------------------------------ 3. IBPC
    {
        "name": "IBPC (Inter-Bank Participation Certificate)",
        "segment": "IBPC", "segmentLabel": "IBPC (inter-bank participation)", "sector": "BANKING",
        "modelKey": "ibpc-rating-v1", "templateKey": "fin-ibpc",
        "checklistKey": "IBPC_CAM", "cpKey": "IBPC",
        "model": model(
            "ibpc-rating-v1", "IBPC Rating - Inter-Bank Participation", "IBPC",
            qual=[
                dropdown("issuer_bank_strength", "Issuing bank standalone strength", 0.35, True,
                         [opt("Strong / well-capitalised", 90), opt("Adequate", 60), opt("Weak", 30)],
                         "Assess the issuing bank's standalone credit strength — the primary obligor on the certificate."),
                dropdown("participation_type", "Participation type", 0.30, True,
                         [opt("Non-risk-sharing", 90), opt("Risk-sharing", 60)],
                         "Risk-sharing IBPC carries the underlying advances' risk; non-risk-sharing is issuing-bank risk only."),
                dropdown("underlying_asset_quality", "Underlying asset quality", 0.35, False,
                         [opt("Priority-sector performing", 85), opt("Standard", 60), opt("Weak", 30)],
                         "For a risk-sharing IBPC, assess the quality of the underlying pool of advances.",
                         visible_when="participation_type == 'Risk-sharing'"),
            ],
            quant=[
                number_module("issuer_crar", "Issuing bank CRAR (%)", 0.60, True,
                              [bmin(15, 90), bmin(11.5, 60), bmin(0, 30)], "RATIO:ISSUER_CRAR_PCT"),
                number_module("issuer_gnpa", "Issuing bank Gross NPA (%)", 0.40, False,
                              [bmax(4, 90), bmax(8, 60), bmax(999, 30)], "RATIO:ISSUER_GNPA_RATIO"),
            ],
            mandatory=["issuer_bank_strength", "participation_type", "issuer_crar"],
            qual_weight=0.5, quant_weight=0.5),
        "template": template(
            "fin-ibpc", "IBPC issuing-bank chart", "IBPC",
            inputs=[finput("CAPITAL_FUNDS", "Regulatory capital funds"),
                    finput("RWA_TOTAL", "Total risk-weighted assets"),
                    finput("GROSS_NPA", "Gross NPAs"),
                    finput("GROSS_ADVANCES", "Gross advances")],
            ratios=[fratio("ISSUER_CRAR_PCT", "Issuer CRAR (%)", "CAPITAL_FUNDS / RWA_TOTAL * 100"),
                    fratio("ISSUER_GNPA_RATIO", "Issuer Gross NPA (%)", "GROSS_NPA / GROSS_ADVANCES * 100")]),
        "checklist": ["IBPC issuance agreement", "Issuing bank board / authority resolution",
                      "Underlying advances schedule", "Participation certificate",
                      "Issuer bank latest financials & CRAR certificate",
                      "RBI / IBA IBPC guideline compliance confirmation"],
        "cp": [cp("CP-IBPC-AGR", "Executed IBPC agreement", True,
                  "Inter-bank participation agreement executed per RBI / IBA guidelines."),
               cp("CP-SCHED", "Underlying advances schedule", True,
                  "Schedule of the underlying advances (risk-sharing) held on file."),
               cp("CP-ISSUER-FS", "Issuer bank financials + CRAR certificate", True,
                  "Latest audited financials and capital-adequacy certificate of the issuing bank."),
               cp("CP-TENOR", "Tenor within permitted band", True,
                  "Participation tenor within the RBI-permitted 91-180 day band."),
               cp("CP-MAC", "No material adverse change", True)],
    },
    # ------------------------------------------------------------------ 4. Bank / FI
    {
        "name": "Bank / FI (lending to banks / FIs)",
        "segment": "BANK_FI", "segmentLabel": "Bank / FI", "sector": "BANKING",
        "modelKey": "bank-fi-rating-v1", "templateKey": "fin-bank-fi",
        "checklistKey": "BANK_FI_CAM", "cpKey": "BANK_FI",
        "model": model(
            "bank-fi-rating-v1", "Bank / FI Rating", "BANK_FI",
            qual=[
                dropdown("regulatory_standing", "Regulatory standing", 0.40, True,
                         [opt("Clean, no supervisory action", 90), opt("Minor observations", 60), opt("Under PCA / action", 25)],
                         "Assess the bank / FI's regulatory standing and any supervisory action (e.g. RBI Prompt Corrective Action)."),
                dropdown("franchise_strength", "Funding franchise", 0.35, True,
                         [opt("Strong deposit franchise", 90), opt("Moderate", 60), opt("Wholesale-dependent", 35)],
                         "Assess the stability of the funding franchise (CASA / deposit mix vs wholesale reliance)."),
                dropdown("management_governance", "Management & governance", 0.25, False,
                         [opt("Strong", 85), opt("Adequate", 55), opt("Weak", 25)],
                         "Assess board independence, management depth and risk governance."),
            ],
            quant=[
                number_module("crar", "CRAR / capital adequacy (%)", 0.30, True,
                              [bmin(15, 90), bmin(11.5, 60), bmin(0, 30)], "RATIO:CRAR_PCT"),
                number_module("tier1", "Tier-1 ratio (%)", 0.20, False,
                              [bmin(11, 90), bmin(9.5, 60), bmin(0, 30)], "RATIO:TIER1_RATIO"),
                number_module("gnpa", "Gross NPA (%)", 0.30, True,
                              [bmax(4, 90), bmax(8, 60), bmax(999, 30)], "RATIO:GNPA_RATIO"),
                number_module("nnpa", "Net NPA (%)", 0.20, False,
                              [bmax(1, 90), bmax(3, 60), bmax(999, 30)], "RATIO:NNPA_RATIO"),
            ],
            mandatory=["regulatory_standing", "franchise_strength", "crar", "gnpa"]),
        "template": template(
            "fin-bank-fi", "Bank / FI prudential chart", "BANK_FI",
            inputs=[finput("CAPITAL_FUNDS", "Regulatory capital funds"),
                    finput("TIER1_CAPITAL", "Tier-1 capital"),
                    finput("RWA_TOTAL", "Total risk-weighted assets"),
                    finput("GROSS_NPA", "Gross NPAs"),
                    finput("NET_NPA", "Net NPAs"),
                    finput("GROSS_ADVANCES", "Gross advances"),
                    finput("HQLA", "High-quality liquid assets"),
                    finput("NET_CASH_OUTFLOWS_30D", "Stressed 30-day net cash outflows")],
            ratios=[fratio("CRAR_PCT", "CRAR (%)", "CAPITAL_FUNDS / RWA_TOTAL * 100"),
                    fratio("TIER1_RATIO", "Tier-1 ratio (%)", "TIER1_CAPITAL / RWA_TOTAL * 100"),
                    fratio("GNPA_RATIO", "Gross NPA (%)", "GROSS_NPA / GROSS_ADVANCES * 100"),
                    fratio("NNPA_RATIO", "Net NPA (%)", "NET_NPA / GROSS_ADVANCES * 100"),
                    fratio("LCR", "Liquidity coverage ratio (%)", "HQLA / NET_CASH_OUTFLOWS_30D * 100")]),
        "checklist": ["Board resolution / delegated authority",
                      "Latest audited financials + Basel III Pillar-3 disclosure",
                      "CRAR & Tier-1 capital certificate",
                      "Regulatory standing confirmation (no PCA)",
                      "Inter-bank / FI exposure limit approval",
                      "ISDA / GMRA master agreements where applicable"],
        "cp": [cp("CP-BR", "Board / delegated authority resolution", True),
               cp("CP-BASEL", "Basel III disclosure + CRAR certificate", True,
                  "Latest Pillar-3 disclosure and capital-adequacy certificate on file."),
               cp("CP-PCA", "No PCA / supervisory-action confirmation", True,
                  "Confirmation the counterparty bank / FI is not under RBI Prompt Corrective Action."),
               cp("CP-LIMIT", "Inter-bank / FI exposure limit sanctioned", True,
                  "Counterparty limit approved within the FI exposure framework."),
               cp("CP-ISDA", "ISDA / GMRA executed (if applicable)", False,
                  "Master agreements for treasury / repo lines where relevant."),
               cp("CP-MAC", "No material adverse change", True)],
    },
    # ------------------------------------------------------------------ 5. Insurance
    {
        "name": "Insurance (lending to insurers)",
        "segment": "INSURANCE", "segmentLabel": "Insurance", "sector": "INSURANCE",
        "modelKey": "insurance-rating-v1", "templateKey": "fin-insurance",
        "checklistKey": "INSURANCE_CAM", "cpKey": "INSURANCE",
        "model": model(
            "insurance-rating-v1", "Insurance Rating", "INSURANCE",
            qual=[
                dropdown("regulatory_standing", "IRDAI regulatory standing", 0.40, True,
                         [opt("Compliant, no action", 90), opt("Minor observations", 60), opt("Under action", 25)],
                         "Assess the insurer's IRDAI regulatory standing and any supervisory action."),
                dropdown("business_mix", "Business mix & diversification", 0.30, False,
                         [opt("Diversified", 85), opt("Focused", 60), opt("Single-line concentrated", 35)],
                         "Assess diversification across life / non-life / health lines and geographies."),
                dropdown("reinsurance_quality", "Reinsurance panel quality", 0.30, False,
                         [opt("Strong panel", 85), opt("Adequate", 55), opt("Weak", 30)],
                         "Assess the strength of the reinsurance panel and net retention discipline."),
            ],
            quant=[
                number_module("solvency_ratio", "Solvency ratio (ASM / RSM, x)", 0.50, True,
                              [bmin(1.8, 90), bmin(1.5, 60), bmin(0, 30)], "RATIO:SOLVENCY_RATIO"),
                number_module("claims_ratio", "Claims ratio (x)", 0.25, False,
                              [bmax(0.7, 90), bmax(0.9, 60), bmax(999, 30)], "RATIO:CLAIMS_RATIO"),
                number_module("combined_ratio", "Combined ratio (x)", 0.25, False,
                              [bmax(0.95, 90), bmax(1.0, 60), bmax(999, 30)], "RATIO:COMBINED_RATIO"),
            ],
            mandatory=["regulatory_standing", "solvency_ratio"]),
        "template": template(
            "fin-insurance", "Insurer solvency chart", "INSURANCE",
            inputs=[finput("AVAILABLE_SOLVENCY_MARGIN", "Available solvency margin (ASM)"),
                    finput("REQUIRED_SOLVENCY_MARGIN", "Required solvency margin (RSM)"),
                    finput("NET_EARNED_PREMIUM", "Net earned premium"),
                    finput("CLAIMS_INCURRED", "Claims incurred"),
                    finput("INSURANCE_OPEX", "Insurance operating + commission expenses"),
                    finput("INVESTMENT_ASSETS", "Investment assets")],
            ratios=[fratio("SOLVENCY_RATIO", "Solvency ratio (x)", "AVAILABLE_SOLVENCY_MARGIN / REQUIRED_SOLVENCY_MARGIN"),
                    fratio("CLAIMS_RATIO", "Claims ratio (x)", "CLAIMS_INCURRED / NET_EARNED_PREMIUM"),
                    fratio("COMBINED_RATIO", "Combined ratio (x)", "(CLAIMS_INCURRED + INSURANCE_OPEX) / NET_EARNED_PREMIUM"),
                    fratio("INVESTMENT_LEVERAGE", "Investment leverage (x)", "INVESTMENT_ASSETS / NET_WORTH")]),
        "checklist": ["Board resolution", "IRDAI registration certificate",
                      "Latest audited financials + solvency return",
                      "Appointed-actuary solvency certificate",
                      "Reinsurance treaty summary", "Investment portfolio statement"],
        "cp": [cp("CP-BR", "Board resolution", True),
               cp("CP-IRDAI", "IRDAI registration & good standing", True,
                  "Valid IRDAI registration; no regulatory action outstanding."),
               cp("CP-SOLV", "Appointed-actuary solvency certificate", True,
                  "Solvency ratio at or above the IRDAI floor, certified by the appointed actuary."),
               cp("CP-REINS", "Reinsurance arrangements confirmed", False,
                  "Current reinsurance treaty summary held on file."),
               cp("CP-MAC", "No material adverse change", True)],
    },
    # ------------------------------------------------------------------ 6. Broker / Capital market
    {
        "name": "Broker / Capital Market",
        "segment": "BROKER_CAPITAL_MARKET", "segmentLabel": "Broker / capital market", "sector": "CAPITAL_MARKETS",
        "modelKey": "broker-cm-rating-v1", "templateKey": "fin-broker-cm",
        "checklistKey": "BROKER_CM_CAM", "cpKey": "BROKER_CAPITAL_MARKET",
        "model": model(
            "broker-cm-rating-v1", "Broker / Capital-Market Rating", "BROKER_CAPITAL_MARKET",
            qual=[
                dropdown("regulatory_standing", "SEBI / exchange standing", 0.40, True,
                         [opt("Registered, clean record", 90), opt("Minor observations", 60), opt("Under action", 25)],
                         "Assess the broker's SEBI / exchange registration standing and any disciplinary action."),
                dropdown("client_franchise", "Client franchise", 0.30, False,
                         [opt("Strong institutional", 85), opt("Retail-diversified", 65), opt("Concentrated", 35)],
                         "Assess the breadth and stickiness of the client franchise."),
                dropdown("risk_controls", "Risk & margin controls", 0.30, False,
                         [opt("Strong systems", 85), opt("Adequate", 55), opt("Weak", 25)],
                         "Assess margin-management, surveillance and risk-control systems."),
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
            "fin-broker-cm", "Broker net-capital chart", "BROKER_CAPITAL_MARKET",
            inputs=[finput("NET_CAPITAL", "Net capital (liquid)"),
                    finput("REGULATORY_MIN_CAPITAL", "Regulatory minimum net worth"),
                    finput("CLIENT_MARGIN_FUNDING", "Client margin funding book"),
                    finput("PROPRIETARY_POSITIONS", "Proprietary trading positions")],
            ratios=[fratio("NET_CAPITAL_RATIO", "Net capital ratio (x)", "NET_CAPITAL / REGULATORY_MIN_CAPITAL"),
                    fratio("MARGIN_COVER", "Margin cover (x)", "NET_WORTH / CLIENT_MARGIN_FUNDING"),
                    fratio("POSITION_LEVERAGE", "Position leverage (x)", "PROPRIETARY_POSITIONS / NET_CAPITAL")]),
        "checklist": ["Board resolution", "SEBI & exchange registration certificates",
                      "Latest net-worth certificate (CA-certified)",
                      "Net-capital / liquid-assets computation",
                      "Client-funding & margin policy",
                      "Pledge of securities / margin arrangement"],
        "cp": [cp("CP-BR", "Board resolution", True),
               cp("CP-SEBI", "SEBI + exchange registration valid", True,
                  "Active SEBI registration + exchange membership; no debarment."),
               cp("CP-NW", "CA-certified net-worth certificate", True,
                  "Net worth at or above the regulatory minimum, CA-certified."),
               cp("CP-PLEDGE", "Pledge / charge over securities", True,
                  "Charge over securities / margin created per sanction."),
               cp("CP-SEGREG", "Client-funds segregation confirmation", True,
                  "Confirmation of client-fund segregation from the broker's own funds."),
               cp("CP-MAC", "No material adverse change", True)],
    },
    # ------------------------------------------------------------------ 7. ECB / Structured finance
    {
        "name": "ECB / Structured Finance",
        "segment": "ECB_STRUCTURED", "segmentLabel": "ECB / structured finance", "sector": "STRUCTURED_FINANCE",
        "modelKey": "ecb-structured-rating-v1", "templateKey": "fin-ecb-structured",
        "checklistKey": "ECB_STRUCTURED_CAM", "cpKey": "ECB_STRUCTURED",
        "model": model(
            "ecb-structured-rating-v1", "ECB / Structured-Finance Rating", "ECB_STRUCTURED",
            qual=[
                dropdown("ecb_compliance", "RBI ECB-framework compliance", 0.40, True,
                         [opt("Fully within framework", 90), opt("Minor gaps", 55), opt("Non-compliant", 20)],
                         "Assess compliance with the RBI ECB framework: eligible borrower / lender, permitted end-use, all-in-cost ceiling and minimum average maturity (MAMP)."),
                dropdown("structure_complexity", "Structural complexity", 0.30, False,
                         [opt("Plain vanilla", 85), opt("Moderate", 60), opt("Highly structured", 35)],
                         "Assess subordination, step-in rights and overall structural complexity."),
                dropdown("sponsor_strength", "Sponsor strength", 0.30, False,
                         [opt("Strong", 85), opt("Adequate", 55), opt("Weak", 30)],
                         "Assess the sponsor's financial strength and commitment to the structure."),
            ],
            quant=[
                number_module("hedge_ratio", "FX hedge ratio (x)", 0.35, True,
                              [bmin(0.7, 90), bmin(0.5, 60), bmin(0, 30)], "RATIO:HEDGE_RATIO"),
                number_module("structured_dscr", "Structured DSCR (x)", 0.40, True,
                              [bmin(1.5, 90), bmin(1.2, 60), bmin(0, 30)], "RATIO:STRUCTURED_DSCR"),
                number_module("fx_exposure_to_nw", "Unhedged FX exposure / net worth (x)", 0.25, False,
                              [bmax(0.25, 90), bmax(0.5, 60), bmax(999, 30)], "RATIO:FX_EXPOSURE_TO_NW"),
            ],
            mandatory=["ecb_compliance", "hedge_ratio", "structured_dscr"]),
        "template": template(
            "fin-ecb-structured", "ECB / structured-finance chart", "ECB_STRUCTURED",
            inputs=[finput("ECB_AMOUNT", "ECB / structured facility amount"),
                    finput("HEDGED_AMOUNT", "Hedged FX amount"),
                    finput("FX_EXPOSURE", "Unhedged FX exposure"),
                    finput("STRUCTURED_CASHFLOW", "Ring-fenced cashflow available"),
                    finput("STRUCTURED_DEBT_SERVICE", "Structured debt service")],
            ratios=[fratio("HEDGE_RATIO", "Hedge ratio (x)", "HEDGED_AMOUNT / ECB_AMOUNT"),
                    fratio("STRUCTURED_DSCR", "Structured DSCR (x)", "STRUCTURED_CASHFLOW / STRUCTURED_DEBT_SERVICE"),
                    fratio("FX_EXPOSURE_TO_NW", "FX exposure / net worth (x)", "FX_EXPOSURE / NET_WORTH")]),
        "checklist": ["ECB / structured facility agreement", "RBI Loan Registration Number (LRN) evidence",
                      "Form ECB filing acknowledgement", "Hedging policy & confirmations",
                      "All-in-cost & MAMP compliance workings",
                      "Security / cashflow-waterfall documentation"],
        "cp": [cp("CP-ECB-AGR", "Executed ECB / structured facility agreement", True),
               cp("CP-LRN", "RBI Loan Registration Number obtained", True,
                  "LRN allotted by RBI prior to the first drawdown."),
               cp("CP-FORM-ECB", "Form ECB filed with the AD bank", True,
                  "Form ECB filed and acknowledged by the authorised dealer bank."),
               cp("CP-HEDGE", "Hedging in place per policy", True,
                  "FX hedges executed to at least the approved hedge ratio."),
               cp("CP-WATERFALL", "Cashflow waterfall / TRA established", True,
                  "Ring-fenced account and payment waterfall operational."),
               cp("CP-MAC", "No material adverse change", True)],
    },
    # ------------------------------------------------------------------ 8. Infrastructure / Priority sector
    {
        "name": "Infrastructure / Priority Sector",
        "segment": "INFRA_PRIORITY", "segmentLabel": "Infrastructure / priority sector", "sector": "INFRASTRUCTURE",
        "modelKey": "infra-priority-rating-v1", "templateKey": "fin-infra-priority",
        "checklistKey": "INFRA_PRIORITY_CAM", "cpKey": "INFRA_PRIORITY",
        "model": model(
            "infra-priority-rating-v1", "Infrastructure / Priority-Sector Rating", "INFRA_PRIORITY",
            qual=[
                dropdown("concession_strength", "Concession / offtake strength", 0.40, True,
                         [opt("Strong concession / offtake", 90), opt("Adequate", 60), opt("Weak", 30)],
                         "Assess the concession / PPA / offtake agreement strength and the offtaker's credit quality."),
                dropdown("construction_risk", "Construction / execution risk", 0.30, False,
                         [opt("Operational / low", 90), opt("Under construction, contained", 55), opt("High", 30)],
                         "Assess the project's construction / execution stage and residual completion risk."),
                dropdown("psl_classification", "Priority-sector classification", 0.30, False,
                         [opt("Qualifies as priority sector", 80), opt("Partial", 60), opt("Non-PSL", 50)],
                         "Assess RBI priority-sector-lending classification (advisory; capital treatment unchanged)."),
            ],
            quant=[
                number_module("project_dscr", "Project DSCR (x)", 0.40, True,
                              [bmin(1.5, 90), bmin(1.2, 60), bmin(0, 30)], "RATIO:PROJECT_DSCR"),
                number_module("debt_equity", "Debt / equity (x)", 0.30, True,
                              [bmax(2.33, 90), bmax(3.0, 60), bmax(999, 30)], "RATIO:DEBT_EQUITY"),
                number_module("plant_load_factor", "Plant load factor / capacity utilisation (x)", 0.30, False,
                              [bmin(0.8, 90), bmin(0.6, 60), bmin(0, 30)], "RATIO:PLANT_LOAD_FACTOR"),
            ],
            mandatory=["concession_strength", "project_dscr", "debt_equity"]),
        "template": template(
            "fin-infra-priority", "Infrastructure project chart", "INFRA_PRIORITY",
            inputs=[finput("PROJECT_COST", "Total project cost"),
                    finput("EQUITY_CONTRIBUTION", "Sponsor equity contribution"),
                    finput("ANNUAL_DEBT_SERVICE", "Annual debt service"),
                    finput("DESIGN_CAPACITY", "Design capacity output"),
                    finput("ACTUAL_OUTPUT", "Actual / expected output")],
            ratios=[fratio("PROJECT_DSCR", "Project DSCR (x)", "CFO / ANNUAL_DEBT_SERVICE"),
                    fratio("DEBT_EQUITY", "Debt / equity (x)", "TOTAL_DEBT / EQUITY_CONTRIBUTION"),
                    fratio("PLANT_LOAD_FACTOR", "Plant load factor (x)", "ACTUAL_OUTPUT / DESIGN_CAPACITY")]),
        "checklist": ["Common loan / facility agreement", "Concession agreement / PPA / offtake contract",
                      "Financial model & lender's-engineer report",
                      "All statutory & environmental clearances",
                      "Trust & retention account (TRA) agreement",
                      "Priority-sector classification note"],
        "cp": [cp("CP-CTA", "Common terms / facility agreement", True),
               cp("CP-CONCESSION", "Concession / PPA / offtake executed", True,
                  "Concession or offtake agreement executed and effective."),
               cp("CP-CLEAR", "Statutory & environmental clearances", True,
                  "All required permits and clearances held on file."),
               cp("CP-EQUITY", "Sponsor equity infused", True,
                  "Sponsor equity contribution evidenced ahead of debt drawdown."),
               cp("CP-TRA", "TRA / DSRA established", True,
                  "Trust-and-retention and debt-service-reserve accounts funded."),
               cp("CP-LIE", "Lender's engineer sign-off", False,
                  "LIE certification for the drawdown milestone."),
               cp("CP-MAC", "No material adverse change", True)],
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
    """Extend the SEGMENT CODE_VALUE domain with the 8 specialty segments.

    GET-merge-submit: read the current ACTIVE SEGMENT record, append only the
    codes that are missing (preserving existing values + order + sortOrder), and
    submit a new governed version only when something actually changed."""
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
    """Seed all 8 SPECIALTY/FI CAM packs. Idempotent + deterministic.

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
        if verbose:
            print(f"  [OK]  {fmt['name']:38s} segment={fmt['segment']:22s} "
                  f"model={fmt['modelKey']}  template={fmt['templateKey']}")

    summary = {"created": stats["created"], "skipped": stats["skipped"],
               "segments_added": segments_added, "formats": len(FORMATS)}
    if verbose:
        print("\n" + "=" * 72)
        print(" Helix CAM-format packs · SPECIALTY / FI cluster")
        print("=" * 72)
        print(f" gateway               : {GW}")
        print(f" formats seeded        : {summary['formats']} "
              f"(MODEL_DEFINITION + FINANCIAL_TEMPLATE + CHECKLIST_MASTER + CP_MASTER each)")
        print(f" master records created: {summary['created']}")
        print(f" master records skipped: {summary['skipped']}  (already active — idempotent re-run)")
        print(f" SEGMENT codes added   : {segments_added if segments_added else '(all already present)'}")
        print("=" * 72)
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
