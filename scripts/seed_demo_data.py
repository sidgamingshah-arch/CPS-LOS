#!/usr/bin/env python3
"""
Helix demo-data seeder — India-specific wholesale-credit book, driven through the
REAL gateway APIs (http://localhost:8080), parking obligors across every stage of
the credit lifecycle.

WHAT THIS IS (and is NOT)
-------------------------
This script seeds a demo book the same way `scripts/e2e_100_obligors.py` and
`scripts/demo_one_obligor.py` do: exclusively via the platform's HTTP APIs through
the Spring Cloud Gateway, with an `X-Actor` header on every write. There are NO
direct database writes and NO hardcoded credit figures — financials, ratings,
capital, pricing, ECL and RAROC are all produced by the real deterministic engines.
The script only supplies *illustrative inputs* (identity + raw financial lines that
are themselves procedurally synthesised for realistic per-segment ratios) and reads
the engine outputs back. References (counterparty/application) are server-generated
and are captured from each response — never recomputed on the client.

INDIA-SPECIFIC
--------------
Every obligor is booked under jurisdiction "IN-RBI" / country "IN". Currency defaults
to INR and GAAP to IND_AS on origination/spreads. Company names are generated
PROCEDURALLY (Indian city / promoter surname + sector + running index + Pvt Ltd/Ltd)
from small pools — there is no hand-written list of 100 names. Registration numbers
are CIN-shaped (U#####MH<year>PLC######). Names containing "Acme Shell" (on the demo
negative list) are never produced.

THE STAGE FUNNEL (the headline ask)
-----------------------------------
Obligors are deliberately LEFT PARKED at different lifecycle stages so the demo shows
a realistic pipeline, not just a booked book:

  PROSPECT    - credit-initiation prospects parked at DRAFT (a few taken to DROPPED),
                never approved to obligor.
  ONBOARDED   - obligor onboarded + screening dispositioned + KYC verified, no application.
  SPREADING   - application + document + financial spread posted, spread NOT confirmed.
  RATING      - spread confirmed + rated + rating confirmed, no capital/pricing/route.
  PENDING     - capital + pricing + covenants + routed for approval, NOT decided.
  DECISIONED  - decided with a MIX of outcomes (APPROVE / CONDITIONAL / REFER / DECLINE),
                NOT booked in portfolio.
  BOOKED      - full path: register + ECL + RAROC snapshot (+ some RAROC compute),
                with varied days-past-due so some sit in SMA/NPA buckets. A subset are
                "existing" borrowers carrying a prior booked deal (lifecycle run twice).

IDEMPOTENCY / RE-RUN SAFETY
---------------------------
There is no server-side counterparty dedup, so the seeder self-guards: before creating
anything it GETs the existing counterparty list and SKIPS any generated legalName that
already exists. Names are deterministic (fixed random seed) and carry a running index,
so a second run regenerates the same names, finds them all present, and skips them —
running the seeder twice does NOT explode the book.

HOW TO RUN
----------
  1. Start the stack:   bash scripts/run-all.sh   (health-gated on :8080-8088)
  2. Seed:              python3 scripts/seed_demo_data.py
  3. Preview only:      python3 scripts/seed_demo_data.py --dry-run
                        (prints the population plan + per-stage funnel + sample names
                         and makes ZERO network calls)

The gateway is permissive locally (no login) — only the X-Actor header is required.
"""
import argparse
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from collections import Counter

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
SEED = 20260714
RUN_TAG = os.environ.get("HELIX_SEED_TAG", "").strip()   # optional namespace for parallel demo books


def call(method, path, body=None, actor="seed.bot"):
    """Single HTTP round-trip through the gateway. Mirrors e2e_100_obligors.call()."""
    url = GW + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, {"message": txt}
    except urllib.error.URLError as e:
        return 0, {"message": str(e.reason)}


# --------------------------------------------------------------- population plan

# Per-stage target counts. Total counterparties = 116 (100 obligors + 16 prospects),
# comfortably clearing the "at least 100 borrowers" bar. Tune here; the plan and the
# dry-run readout both derive from this map, so counts stay consistent.
STAGE_PLAN = [
    ("PROSPECT",   16),   # parked at DRAFT (~25% -> DROPPED); never approved to obligor
    ("ONBOARDED",  16),   # obligor + screening + KYC only; no application
    ("SPREADING",  16),   # application + document + spread (unconfirmed)
    ("RATING",     16),   # + spread confirm + rate + rating confirm
    ("PENDING",    12),   # + capital + pricing + covenants + route (not decided)
    ("DECISIONED", 16),   # + decide (mixed outcomes); not booked
    ("BOOKED",     24),   # full path incl. register + ECL + RAROC
]
DROPPED_PCT = 0.25       # of PROSPECT cohort taken to DROPPED
EXISTING_PCT = 0.30      # of BOOKED cohort carrying a prior booked deal (lifecycle x2)
STRESSED_PCT = 0.22      # weak ratios -> deeper sub-IG
RED_FLAG_PCT = 0.45      # only applied to PROSPECT / ONBOARDED cohorts (they stop early)

# India segment distribution (weights). All six wholesale segments appear.
SEGMENTS = [
    ("MID_CORPORATE", 30),
    ("SME", 25),
    ("LARGE_CORPORATE", 15),
    ("PROJECT_FINANCE", 10),
    ("TRADE_FINANCE", 10),
    ("FINANCIAL_INSTITUTION", 10),
]

# (facilityType, lo, hi, tenorMonths) — illustrative sizing bands per segment. The
# amount is drawn in-band; every downstream figure (RWA, RAROC, ECL) is computed by
# the engines, never here. Mirrors e2e_100_obligors.FACILITY_BY_SEGMENT.
FACILITY_BY_SEGMENT = {
    "MID_CORPORATE": ("TERM_LOAN", 400_000_000, 1_000_000_000, 60),
    "LARGE_CORPORATE": ("TERM_LOAN", 1_500_000_000, 5_000_000_000, 84),
    "SME": ("WORKING_CAPITAL", 30_000_000, 150_000_000, 36),
    "PROJECT_FINANCE": ("PROJECT_LOAN", 2_500_000_000, 8_000_000_000, 144),
    "TRADE_FINANCE": ("TRADE_LINE", 100_000_000, 500_000_000, 12),
    "FINANCIAL_INSTITUTION": ("REVOLVING_CREDIT", 1_000_000_000, 4_000_000_000, 36),
}

SECTOR_BY_SEGMENT = {
    "MID_CORPORATE": "MANUFACTURING",
    "LARGE_CORPORATE": "MANUFACTURING",
    "SME": "MANUFACTURING",
    "FINANCIAL_INSTITUTION": "FINANCIAL_SERVICES",
    "PROJECT_FINANCE": "INFRASTRUCTURE",
    "TRADE_FINANCE": "TRADING",
}

# Indian company-name building blocks (procedural — no fixed 100-name list).
INDIAN_CITIES = ["Mumbai", "Pune", "Ahmedabad", "Surat", "Bengaluru", "Chennai",
                 "Hyderabad", "Kolkata", "Delhi", "Jaipur", "Indore", "Nagpur",
                 "Coimbatore", "Ludhiana", "Vadodara"]
INDIAN_LAST = ["Mehta", "Iyer", "Khanna", "Patel", "Reddy", "Sharma", "Singh",
               "Verma", "Agarwal", "Nair", "Desai", "Gupta"]
CORP_KINDS = ["Steel", "Pharma", "Auto Components", "Chemicals", "Cement",
              "Textiles", "Foods", "Retail", "Packaging", "Engineering"]
PROJECT_KINDS = ["Solar", "Highway", "Port", "Power", "Logistics", "Metro Rail"]
FI_KINDS = ["Finance", "Capital", "NBFC", "Housing Finance"]

# Committee-tier voters (seeded ACTOR_ROLE identities holding committee authority; NOT
# the router credit.ops.bot, so router != voter SoD holds). Used only to meet quorum.
COMMITTEE_VOTERS = {
    "BOARD_COMMITTEE": ["credit.officer.bot", "cro"],
    "CREDIT_COMMITTEE": ["credit.officer.bot", "cro", "credit.committee"],
}


def weighted(seq):
    pool = []
    for v, w in seq:
        pool.extend([v] * w)
    return random.choice(pool)


def borrower_name(segment, idx):
    """Procedural India company name: place/surname + sector-kind + index + Pvt Ltd/Ltd."""
    place = random.choice(INDIAN_CITIES)
    tag = f" {RUN_TAG}" if RUN_TAG else ""
    if segment == "FINANCIAL_INSTITUTION":
        return f"{place} {random.choice(FI_KINDS)}{tag} {idx:03d} Ltd"
    if segment == "PROJECT_FINANCE":
        return f"{place} {random.choice(PROJECT_KINDS)} SPV{tag} {idx:03d} Pvt Ltd"
    if segment == "TRADE_FINANCE":
        return f"{random.choice(INDIAN_LAST)} Trading House{tag} {idx:03d} Pvt Ltd"
    kind = random.choice(CORP_KINDS)
    lead = random.choice([place, random.choice(INDIAN_LAST)])
    suffix = "Ltd" if segment == "LARGE_CORPORATE" else "Pvt Ltd"
    return f"{lead} {kind}{tag} {idx:03d} {suffix}"


def reg_no(idx):
    """CIN-shaped registration number (unique via the running index)."""
    year = 1990 + (idx % 30)
    return f"U{27000 + idx:05d}MH{year}PLC{100000 + idx:06d}"


def line(v):
    return {"value": v, "sourceDocument": "audited_financials.pdf", "sourcePage": "P12",
            "coordinates": "tbl1", "confidence": 0.95}


def synth_financials(segment, stressed):
    """Synthesise a financials snapshot giving realistic per-segment ratios. The values
    are illustrative INPUTS; the rating/capital engines derive every credit figure."""
    scale = {
        "MID_CORPORATE": 5e9, "LARGE_CORPORATE": 25e9, "SME": 0.4e9,
        "PROJECT_FINANCE": 8e9, "TRADE_FINANCE": 3e9, "FINANCIAL_INSTITUTION": 15e9,
    }[segment]
    rev = scale * random.uniform(0.85, 1.2)
    margin = random.uniform(0.06, 0.10) if stressed else random.uniform(0.13, 0.22)
    ebitda = rev * margin
    cogs = rev - ebitda - (rev * random.uniform(0.04, 0.08))
    opex = rev - cogs - ebitda
    dep = ebitda * random.uniform(0.10, 0.18)
    debt_total = ebitda * (random.uniform(4.5, 6.5) if stressed else random.uniform(1.6, 3.0))
    interest = debt_total * 0.085
    pbt = ebitda - dep - interest
    tax = max(0, pbt * 0.22)
    cash = rev * random.uniform(0.04, 0.08)
    current_assets = rev * 0.5
    current_liab = rev * 0.35
    cfo = ebitda * (random.uniform(0.55, 0.75) if stressed else random.uniform(0.85, 1.05))
    net_worth = max(rev * 0.30, ebitda * 4)
    short_debt = debt_total * 0.30
    long_debt = debt_total * 0.70
    cpltd = long_debt * 0.10
    return {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(dep), "INTEREST_EXPENSE": line(interest), "TAX": line(tax),
        "TOTAL_ASSETS": line(rev * 1.2), "CURRENT_ASSETS": line(current_assets),
        "CASH": line(cash), "CURRENT_LIABILITIES": line(current_liab),
        "SHORT_TERM_DEBT": line(short_debt), "LONG_TERM_DEBT": line(long_debt),
        "CURRENT_PORTION_LTD": line(cpltd), "NET_WORTH": line(net_worth), "CFO": line(cfo),
    }


def build_plan():
    """Deterministically build the full population plan (all name/segment/flag draws
    happen here, before any network I/O). Returns a list of borrower dicts."""
    random.seed(SEED)
    plan = []
    idx = 1
    for stage, count in STAGE_PLAN:
        for _ in range(count):
            segment = weighted(SEGMENTS)
            early_stop = stage in ("PROSPECT", "ONBOARDED")
            plan.append({
                "idx": idx,
                "stage": stage,
                "segment": segment,
                "name": borrower_name(segment, idx),
                "regNo": reg_no(idx),
                "stressed": random.random() < STRESSED_PCT,
                "redFlag": early_stop and random.random() < RED_FLAG_PCT,
                "dropped": stage == "PROSPECT" and random.random() < DROPPED_PCT,
                "existing": stage == "BOOKED" and random.random() < EXISTING_PCT,
            })
            idx += 1
    return plan


# --------------------------------------------------------------- lifecycle stats

class Stats:
    def __init__(self):
        self.landed = Counter()          # stage label -> obligors parked there
        self.by_segment = Counter()
        self.by_grade = Counter()
        self.outcomes = Counter()        # decision outcomes
        self.dpd_buckets = Counter()     # STANDARD / SMA / NPA
        self.booked_exposures = 0
        self.existing_repeats = 0
        self.skipped_existing = 0
        self.kyc_blocked = 0
        self.errors = []
        # --- advanced-artifact phase (showcase obligors) ---
        self.booked_deals = []           # [{cp, ref, grade, segment}] captured at BOOK time
        self.seeded = Counter()          # advanced artifact type -> count seeded
        self.warnings = []               # non-fatal advanced-artifact warnings

    def err(self, msg):
        self.errors.append(msg)

    def warn(self, msg):
        self.warnings.append(msg)


# --------------------------------------------------------------- stage drivers

def create_prospect(b, stats):
    """Credit-initiation prospect parked at DRAFT; a subset taken to DROPPED. Never approved."""
    body = {
        "legalName": b["name"], "legalForm": "PRIVATE_LTD", "registrationNo": b["regNo"],
        "jurisdiction": "IN-RBI", "segment": b["segment"], "sector": SECTOR_BY_SEGMENT[b["segment"]],
        "country": "IN", "borrowerType": "NTB",
        "pep": b["redFlag"] and random.random() < 0.5,
        "adverseMedia": b["redFlag"] and random.random() < 0.5,
        "highRiskJurisdiction": b["redFlag"] and random.random() < 0.3,
        "complexOwnership": random.random() < 0.2,
    }
    st, cp = call("POST", "/counterparty/api/initiation/prospects", body, actor="rm.bot")
    if st != 200:
        stats.err(f"prospect {b['name']}: {st} {msg(cp)}")
        return
    pid = cp["id"]
    # Advisory reads (cheap GETs) — enrich the audit trail / demo, no figures produced.
    call("GET", f"/counterparty/api/initiation/prospects/{pid}/dedup")
    call("GET", f"/counterparty/api/initiation/prospects/{pid}/negative-check")
    call("GET", f"/counterparty/api/initiation/prospects/{pid}/summary")
    if b["dropped"]:
        call("POST", f"/counterparty/api/initiation/prospects/{pid}/decision",
             {"proceed": False, "reason": "RM dropped at initiation — appetite/fit"}, actor="rm.bot")
        stats.landed["PROSPECT_DROPPED"] += 1
    else:
        stats.landed["PROSPECT_DRAFT"] += 1
    stats.by_segment[b["segment"]] += 1


def onboard(b, stats):
    """Create a real obligor and run screening + dispositions + KYC verify. Returns the
    counterparty dict (even if KYC is blocked by a severe hit), else None."""
    body = {
        "legalName": b["name"],
        "legalForm": "PUBLIC_LTD" if b["segment"] == "LARGE_CORPORATE" else "PRIVATE_LTD",
        "registrationNo": b["regNo"], "jurisdiction": "IN-RBI", "segment": b["segment"],
        "sector": SECTOR_BY_SEGMENT[b["segment"]], "country": "IN",
        "listedEntity": b["segment"] == "LARGE_CORPORATE",
        "regulatedFi": b["segment"] == "FINANCIAL_INSTITUTION",
        "pep": b["redFlag"] and random.random() < 0.5,
        "adverseMedia": b["redFlag"] and random.random() < 0.5,
        "highRiskJurisdiction": b["redFlag"] and random.random() < 0.3,
        "complexOwnership": b["segment"] in ("PROJECT_FINANCE", "FINANCIAL_INSTITUTION") and random.random() < 0.5,
    }
    st, cp = call("POST", "/counterparty/api/counterparties", body, actor="rm.bot")
    if st != 200:
        stats.err(f"onboard {b['name']}: {st} {msg(cp)}")
        return None
    st, hits = call("POST", f"/counterparty/api/counterparties/{cp['id']}/screening/run", actor="compliance.bot")
    if st == 200:
        for h in hits or []:
            disp = "FALSE_POSITIVE" if h.get("severity") in ("LOW", "MEDIUM") else "ESCALATED"
            call("POST", f"/counterparty/api/counterparties/screening/{h['id']}/disposition",
                 {"disposition": disp, "note": "seed disposition"}, actor="compliance.bot")
    st, _ = call("POST", f"/counterparty/api/counterparties/{cp['id']}/kyc/verify", actor="compliance.bot")
    if st != 200:
        stats.kyc_blocked += 1   # expected for severe hits; obligor stays IN_PROGRESS
    stats.by_segment[b["segment"]] += 1
    return cp


def post_application(cp, b, existing=False):
    """Create the application + primary facility. Returns the application reference or None."""
    ft, lo, hi, tenor = FACILITY_BY_SEGMENT[b["segment"]]
    amount = round(random.uniform(lo, hi), -6)
    collateral_type = random.choice(["PROPERTY", "RECEIVABLES", "EQUITY_LISTED", "GOVT_SECURITIES"])
    body = {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": b["segment"], "facilityType": ft,
        "requestedAmount": amount, "currency": "INR", "tenorMonths": tenor,
        "purpose": "Renewal" if existing else "Capacity expansion",
        "collateralType": collateral_type, "collateralValue": amount * random.uniform(0.75, 1.4),
        "secured": True,
    }
    st, app = call("POST", "/origination/api/applications", body, actor="rm.bot")
    if st != 200:
        return None, amount
    return app["reference"], amount


def spread(ref, b):
    """Document + two-period IND_AS / INR spread (unconfirmed). Returns True on success."""
    call("POST", f"/origination/api/applications/{ref}/documents",
         {"fileName": "audited_financials.pdf"}, actor="analyst.bot")
    periods = {"periods": [
        {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": synth_financials(b["segment"], b["stressed"])},
        {"label": "FY2023", "gaap": "IND_AS", "currency": "INR", "lines": synth_financials(b["segment"], b["stressed"])},
    ]}
    st, _ = call("POST", f"/origination/api/applications/{ref}/spread", periods, actor="analyst.bot")
    return st == 200


def approving_outcome(grade, stressed):
    """Choose an APPROVING outcome (committee tiers finalise only on approving votes)."""
    if stressed or grade in ("BBB", "BB", "B", "CCC", "CC", "C", "D"):
        return "CONDITIONAL_APPROVE", ["Maintain DSCR >= 1.25x", "Personal guarantee from promoters"]
    return "APPROVE", []


def mixed_outcome(grade, stressed):
    """Full mix for single-authority tiers so DECISIONED spans APPROVE/CONDITIONAL/REFER/DECLINE."""
    if grade in ("AAA", "AA", "A", "BBB") and not stressed:
        return "APPROVE", []
    if grade in ("BBB", "BB"):
        return "CONDITIONAL_APPROVE", ["Maintain DSCR >= 1.25x", "Additional collateral top-up"]
    if grade in ("B", "CCC"):
        return "REFER", []
    return "DECLINE", []


def finalise_decision(ref, route_resp, grade, stressed, force_approving, stats):
    """Route response drives committee vs single-authority handling. For committee tiers,
    cast distinct approving votes until quorum (finalises only on approving votes). For
    single-authority tiers, one decide finalises any outcome. Returns (outcome, decided)."""
    committee = bool(route_resp.get("committeeMode"))
    authority = route_resp.get("requiredAuthority")
    if committee:
        outcome, conds = approving_outcome(grade, stressed)
        voters = COMMITTEE_VOTERS.get(authority, ["credit.officer.bot", "cro"])
        decided = False
        for v in voters:
            st, dec = call("POST", f"/decision/api/decisions/{ref}/decide",
                           {"outcome": outcome, "role": authority,
                            "rationale": f"Committee vote: {outcome.lower()} on grade {grade}",
                            "conditions": conds}, actor=v)
            if st != 200:
                stats.err(f"committee vote {ref} ({v}): {st} {msg(dec)}")
                continue
            if dec and dec.get("status") == "DECIDED":
                decided = True
                break
        return outcome, decided
    outcome, conds = approving_outcome(grade, stressed) if force_approving else mixed_outcome(grade, stressed)
    st, dec = call("POST", f"/decision/api/decisions/{ref}/decide",
                   {"outcome": outcome, "role": authority,
                    "rationale": f"Seed decision: {outcome.lower()} on grade {grade}",
                    "conditions": conds}, actor="credit.officer.bot")
    if st != 200:
        stats.err(f"decide {ref}: {st} {msg(dec)}")
        return outcome, False
    return outcome, dec is not None and dec.get("status") == "DECIDED"


def run_lifecycle(cp, b, stop_at, stats, existing=False):
    """Walk the origination->risk->decision->portfolio spine, stopping at `stop_at`.
    Every credit figure is engine-computed; the client only supplies illustrative inputs.
    Returns the stage label the obligor actually landed at."""
    ref, amount = post_application(cp, b, existing)
    if ref is None:
        stats.err(f"application for {cp['legalName']}: create failed")
        return None

    if not spread(ref, b):
        stats.err(f"spread {ref}: failed")
        return None
    if stop_at == "SPREADING":
        return "SPREADING"   # parked: spread posted but NOT confirmed

    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.bot")

    st, rating = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.bot")
    if st != 200:
        stats.err(f"rate {ref}: {st} {msg(rating)}")
        return None
    grade = rating["finalGrade"]
    stats.by_grade[grade] += 1
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer.bot")
    if stop_at == "RATING":
        return "RATING"   # parked: rated + confirmed, no capital/pricing/route

    call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops.bot")
    call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.bot")

    st, sugg = call("GET", f"/decision/api/decisions/{ref}/covenants/suggest?grade={grade}")
    for c in (sugg or [])[:2]:
        call("POST", f"/decision/api/decisions/{ref}/covenants", c, actor="analyst.bot")

    st, route = call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops.bot")
    if st != 200:
        stats.err(f"route {ref}: {st} {msg(route)}")
        return None
    if stop_at == "PENDING":
        return "PENDING"   # parked: routed for approval, NOT decided

    force_approving = stop_at == "BOOKED"
    outcome, decided = finalise_decision(ref, route, grade, b["stressed"], force_approving, stats)
    if decided:
        stats.outcomes[outcome] += 1
    if stop_at == "DECISIONED":
        return "DECISIONED" if decided else "PENDING"

    # BOOKED: register + ECL + RAROC. Register needs a rating (present); we also flip the
    # application status to APPROVED to mirror the operational flow.
    if not (decided and outcome in ("APPROVE", "CONDITIONAL_APPROVE")):
        return "DECISIONED"   # non-approving/undecided deal cannot be booked
    call("PATCH", f"/origination/api/applications/{ref}/status", {"status": "APPROVED"}, actor="credit.ops.bot")
    dpd = random.choices([0, 0, 0, 15, 45, 95], weights=[55, 20, 8, 8, 5, 4])[0]
    st, exp = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register",
                   {"daysPastDue": dpd}, actor="credit.ops.bot")
    if st != 200:
        stats.err(f"register {ref}: {st} {msg(exp)}")
        return "DECISIONED"
    stats.booked_exposures += 1
    stats.dpd_buckets["STANDARD" if dpd < 30 else "SMA" if dpd < 90 else "NPA"] += 1
    call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ecl", actor="credit.ops.bot")
    call("POST", f"/portfolio/api/portfolio/exposures/{ref}/raroc/snapshot", actor="credit.ops.bot")
    if random.random() < 0.5:
        rp = random.uniform(0, amount * 0.005)
        call("POST", f"/portfolio/api/portfolio/exposures/{ref}/raroc/compute"
                     f"?period=2026Q2&realisedProvisionDelta={rp}", actor="portfolio.bot")
    # Capture the fully-booked (approved + registered + capital + priced) deal so the
    # advanced-artifact phase can layer showcase artifacts onto REAL seeded refs.
    stats.booked_deals.append({"cp": cp, "ref": ref, "grade": grade, "segment": b["segment"]})
    return "BOOKED"


def msg(b):
    return b.get("message") if isinstance(b, dict) else b


# =============================================================================
# ADVANCED ARTIFACTS — showcase obligors
# =============================================================================
# The core loop above parks a full India book across the 7-stage funnel, but ~14
# ADVANCED screens (Banking ASR, Risk Note, CAM Annexure, Escrow, SCF, IP Note, MOE
# Perfection, COI, Monitoring Artifacts, Notings, Global Cashflow, Exceptions,
# Disbursement, Integration Hub, Exports, GenAI/XAI surfaces, Role dashboards) open
# EMPTY unless their records exist. This phase layers those artifacts onto ~5 REAL
# already-BOOKED obligors captured above (never throwaway obligors), driving each to a
# mid/approved state so a client click-through lands on POPULATED data everywhere.
#
# Every sequence here is LIFTED verbatim (bodies/paths) from the known-correct
# scripts/e2e_*.py drivers — only the target ref/actor is repointed at seeded refs.
# All figures stay engine-computed (advisory artifacts never mutate an authoritative
# figure). Each block is wrapped so one optional artifact erroring never hard-fails the
# demo seed; failures degrade to a logged warning.

# CHECKLIST_MASTER PERFECTION_MOE steps (verbatim from e2e_perfection.py).
MOE_STEPS_DEMO = [
    {"key": "TITLE_SEARCH", "title": "Title search report (master)", "ownerRole": "LEGAL"},
    {"key": "LEGAL_OPINION", "title": "Legal opinion on title (master)", "ownerRole": "LEGAL"},
    {"key": "VALUATION", "title": "Property valuation (master)", "ownerRole": "VENDOR"},
    {"key": "MOE_EXECUTION", "title": "MOE execution (master)", "ownerRole": "LMO"},
    {"key": "MOE_VETTING", "title": "MOE vetting (master)", "ownerRole": "CAD_OPS"},
    {"key": "CERSAI_FILING", "title": "CERSAI charge filing (master)", "ownerRole": "CAD_OPS"},
]


def api(method, path, body=None, actor="seed.bot", ok=(200, 201)):
    """Strict variant of call(): raises on an unexpected status so the enclosing
    artifact block aborts cleanly into its try/except (logged as a warning)."""
    st, b = call(method, path, body, actor=actor)
    if st not in ok:
        raise RuntimeError(f"{method} {path} -> {st} {msg(b)}")
    return b


def _safe(stats, label, fn, *args):
    """Run one artifact block; a failure is a logged warning, never fatal."""
    try:
        fn(*args)
        return True
    except Exception as e:  # noqa: BLE001 — a demo seed must never hard-fail on an optional artifact
        stats.warn(f"{label}: {e!r}")
        return False


def _iso_in(days):
    return time.strftime("%Y-%m-%d", time.localtime(time.time() + days * 86400))


def sm(mtype, key, payload, maker, checker, jurisdiction=None):
    """Submit + approve a master record (maker != checker for SoD) -> ACTIVE.
    Mirrors the seed_master helper every e2e_*.py uses; re-seeding advances the version."""
    body = {"recordKey": key, "payload": payload}
    if jurisdiction is not None or mtype == "ACTOR_ROLE":
        body["jurisdiction"] = jurisdiction
    rec = api("POST", f"/config/api/masters/{mtype}", body, actor=maker)
    return api("POST", f"/config/api/masters/records/{rec['id']}/approve", actor=checker)


def seed_actor_role(actor_key, display_name, roles):
    """Grant an actor its role(s) via the ACTOR_ROLE master (maker != checker)."""
    rec = api("POST", "/config/api/masters/ACTOR_ROLE",
              {"recordKey": actor_key, "jurisdiction": None,
               "payload": {"displayName": display_name or actor_key, "roles": roles}},
              actor="config.admin")
    return api("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="config.checker")


def seed_prereq_masters(stats):
    """Seed the config-as-data masters + role grants the advanced artifacts depend on
    (all idempotent; re-seeding a key just advances its version)."""
    _safe(stats, "master:ANNEXURE_TYPE/CRI_SHEET", lambda: sm(
        "ANNEXURE_TYPE", "CRI_SHEET",
        {"label": "CRI Sheet", "sections": [{"key": k, "title": k.replace("_", " ").title()} for k in
         ["borrower_profile", "exposure_summary", "rating_summary", "key_risks", "recommendation"]]},
        "annexure.master.maker", "annexure.master.checker"))
    _safe(stats, "master:MONITORING_ARTIFACT_TYPE/CALL_MEMO", lambda: sm(
        "MONITORING_ARTIFACT_TYPE", "CALL_MEMO",
        {"sections": [{"key": "purpose", "label": "Purpose of call"},
                      {"key": "discussion", "label": "Discussion"},
                      {"key": "actions", "label": "Action points"}],
         "requiresAuthorize": False, "vendorRfq": False},
        "monitoring.master.maker", "monitoring.master.checker"))
    _safe(stats, "master:MONITORING_ARTIFACT_TYPE/LCR", lambda: sm(
        "MONITORING_ARTIFACT_TYPE", "LCR",
        {"sections": [{"key": "compliance", "label": "Compliance summary"},
                      {"key": "exceptions", "label": "Exceptions"}],
         "requiresAuthorize": True, "vendorRfq": False},
        "monitoring.master.maker", "monitoring.master.checker"))
    _safe(stats, "master:NOTING_TYPE/CAM_NOTE", lambda: sm(
        "NOTING_TYPE", "CAM_NOTE",
        {"routing": "FIXED_ROLE", "approverRole": "CREDIT_OFFICER", "cadRequired": False,
         "fields": ["reviewPeriod", "summary"]},
        "noting.master.maker", "noting.master.checker"))
    _safe(stats, "master:VALIDATION_PARAMETER/ESCROW_UTILISATION", lambda: sm(
        "VALIDATION_PARAMETER", "ESCROW_UTILISATION",
        {"amberUtilisationPct": 80, "redUtilisationPct": 100},
        "escrow.master.maker", "escrow.master.checker"))
    _safe(stats, "master:SCF_ELIGIBILITY/DEFAULT", lambda: sm(
        "SCF_ELIGIBILITY", "DEFAULT",
        {"minSpokeAmount": 1_000_000, "maxSpokeAmount": 50_000_000,
         "maxSpokePctOfProgram": 30, "allowedProgramTypes": ["VENDOR", "DEALER"]},
        "scf.master.maker", "scf.master.checker"))
    _safe(stats, "master:CHECKLIST_MASTER/PERFECTION_MOE", lambda: sm(
        "CHECKLIST_MASTER", "PERFECTION_MOE", {"steps": MOE_STEPS_DEMO},
        "perf.master.maker", "perf.master.checker"))
    # Role grants the SCF-approve + MOE-perfection role gates require (verbatim from
    # e2e_scf.py / e2e_perfection.py). legal.counsel already holds LEGAL (base-seeded).
    _safe(stats, "role:credit.head", lambda: seed_actor_role("credit.head", "Credit Head", ["CREDIT_HEAD"]))
    _safe(stats, "role:cad.ops", lambda: seed_actor_role("cad.ops", "CAD Ops", ["CAD_OPS"]))
    _safe(stats, "role:lmo.user", lambda: seed_actor_role("lmo.user", "LMO", ["LMO", "CAD_OPS"]))
    _safe(stats, "role:vendor.user", lambda: seed_actor_role("vendor.user", "Vendor", ["VENDOR"]))
    # Drop the directory snapshots so the grants take effect at once.
    _safe(stats, "rbac:invalidate", lambda: (
        call("POST", "/decision/api/governance/rbac/cache/invalidate"),
        call("POST", "/origination/api/governance/rbac/cache/invalidate")))
    # Delegation: round-robin pools whose MEMBERS are the demo login identities, plus a
    # MANUAL queue and an OOO delegate (verbatim shapes from e2e_casework.py).
    _safe(stats, "master:ASSIGNMENT_POOL", seed_pools_and_ooo)


def seed_pools_and_ooo():
    sm("ASSIGNMENT_POOL", "DEMO_CREDIT_QUEUE",
       {"members": ["rm.user", "analyst.user", "credit.head"], "supervisors": ["credit.head"],
        "strategy": "ROUND_ROBIN"}, "pool.maker", "pool.checker")
    sm("ASSIGNMENT_POOL", "DEMO_MONITORING_QUEUE",
       {"members": ["analyst.user", "credit.head"], "supervisors": ["credit.head"],
        "strategy": "MANUAL"}, "pool.maker", "pool.checker")
    sm("ASSIGNMENT_POOL", "DEMO_DELEGATION_QUEUE",
       {"members": ["vacation.rm", "credit.head"], "supervisors": ["credit.head"],
        "strategy": "ROUND_ROBIN"}, "pool.maker", "pool.checker")
    # vacation.rm is OOO -> its tasks route to the demo login rm.user (delegation demo).
    sm("OOO_CALENDAR", "vacation.rm", {"delegateTo": "rm.user"}, "ooo.maker", "ooo.checker")


# --------------------------------------------------------------- per-obligor artifacts

def seed_banking_asr(d, stats):
    """Banking ASR (account-statement review) — compute + advisory summary + confirm.
    Lifted from e2e_banking_asr.py; metrics are engine-computed from the known lines."""
    ref = d["ref"]
    lines = [
        {"monthLabel": "M1", "openingBalance": 10_000_000, "closingBalance": 20_000_000,
         "totalCredit": 30_000_000, "totalDebit": 25_000_000, "peakBalance": 22_000_000,
         "minBalanceInMonth": 8_000_000, "drawn": 50_000_000,
         "chequeReturnsInward": 1, "chequeReturnsOutward": 0, "transactionCount": 40},
        {"monthLabel": "M2", "openingBalance": 20_000_000, "closingBalance": 30_000_000,
         "totalCredit": 40_000_000, "totalDebit": 35_000_000, "peakBalance": 33_000_000,
         "minBalanceInMonth": 15_000_000, "drawn": 60_000_000,
         "chequeReturnsInward": 0, "chequeReturnsOutward": 1, "transactionCount": 50},
        {"monthLabel": "M3", "openingBalance": 30_000_000, "closingBalance": 40_000_000,
         "totalCredit": 50_000_000, "totalDebit": 45_000_000, "peakBalance": 44_000_000,
         "minBalanceInMonth": 25_000_000, "drawn": 70_000_000,
         "chequeReturnsInward": 2, "chequeReturnsOutward": 0, "transactionCount": 60},
    ]
    a = api("POST", "/origination/api/banking-asr", {
        "applicationRef": ref, "bankName": "State Bank of India", "accountNoMasked": "XXXXXX1234",
        "sanctionedLimit": 100_000_000, "currency": "INR", "periodFrom": "2025-01",
        "periodTo": "2025-03", "lines": lines}, actor="analyst.user")
    asr_ref = a["asrRef"]
    api("POST", f"/origination/api/banking-asr/{asr_ref}/summary", actor="analyst.user")  # advisory (AI)
    api("POST", f"/origination/api/banking-asr/{asr_ref}/confirm",
        {"note": "conduct reviewed — satisfactory"}, actor="credit.officer")
    stats.seeded["banking_asr"] += 1


def seed_risk_note(d, stats):
    """Independent Risk Note — DRAFT -> SUBMITTED -> REVIEWED -> APPROVED (SoD-clean)."""
    ref = d["ref"]
    n = api("POST", "/risk/api/risk-notes",
            {"subjectRef": ref, "recommendedAction": "SUPPORT_WITH_CONDITIONS"}, actor="risk.analyst")
    rn = n["riskNoteRef"]
    api("PUT", f"/risk/api/risk-notes/{rn}/sections", {"sections": {
        "RISK_OPINION": "Mid-corporate manufacturer with adequate coverage; leverage manageable.",
        "KEY_RISKS": "Cyclical demand and input-cost volatility; refinancing concentration.",
        "MITIGANTS": "Property security at 1.5x; committed offtake; conservative amortisation.",
        "RECOMMENDATION": "Support with standard financial covenants and a security-perfection CP.",
    }, "recommendedAction": "SUPPORT_WITH_CONDITIONS"}, actor="risk.analyst")
    api("POST", f"/risk/api/risk-notes/{rn}/submit", actor="risk.analyst")
    api("POST", f"/risk/api/risk-notes/{rn}/review", actor="risk.reviewer")
    api("POST", f"/risk/api/risk-notes/{rn}/approve", {"note": "endorsed by risk head"}, actor="risk.head")
    stats.seeded["risk_note"] += 1


def seed_cam_annexure(d, stats):
    """CAM Annexure (CRI_SHEET) — DRAFT -> SUBMITTED -> REVIEWED -> APPROVED (SoD-clean)."""
    ref = d["ref"]
    a = api("POST", "/decision/api/annexures",
            {"annexureType": "CRI_SHEET", "subjectType": "APPLICATION", "subjectRef": ref,
             "title": f"CRI Sheet for {ref}"}, actor="rm.user")
    anx = a["annexureRef"]
    api("PUT", f"/decision/api/annexures/{anx}/sections",
        {"sections": {"recommendation": {"title": "Recommendation",
                      "content": "Recommend approval within appetite; standard covenants."}}}, actor="rm.user")
    api("POST", f"/decision/api/annexures/{anx}/submit", actor="rm.user")
    api("POST", f"/decision/api/annexures/{anx}/review", {"notes": "looks good"}, actor="credit.officer")
    api("POST", f"/decision/api/annexures/{anx}/approve", {"notes": "approved"}, actor="credit.committee")
    stats.seeded["cam_annexure"] += 1


def seed_monitoring_artifact(d, stats):
    """Monitoring Artifact (CALL_MEMO) — DRAFT -> SUBMITTED -> REVIEWED -> APPROVED (SoD-clean)."""
    ref = d["ref"]
    a = api("POST", "/portfolio/api/monitoring/artifacts",
            {"artifactType": "CALL_MEMO", "subjectType": "OBLIGOR", "subjectRef": ref,
             "title": "Quarterly relationship call"}, actor="portfolio.manager")
    m = a["artifactRef"]
    api("PUT", f"/portfolio/api/monitoring/artifacts/{m}/sections",
        {"sections": {"purpose": {"label": "Purpose of call",
                      "content": "Review quarterly performance and utilisation."}}}, actor="portfolio.manager")
    api("POST", f"/portfolio/api/monitoring/artifacts/{m}/submit", actor="portfolio.manager")
    api("POST", f"/portfolio/api/monitoring/artifacts/{m}/review", {"notes": "looks good"}, actor="credit.officer")
    api("POST", f"/portfolio/api/monitoring/artifacts/{m}/approve", {"notes": "approved"}, actor="credit.committee")
    stats.seeded["monitoring_artifact"] += 1


def seed_noting(d, stats):
    """Noting (CAM_NOTE, fixed-role CREDIT_OFFICER) — DRAFT -> PENDING_APPROVAL -> APPROVED."""
    ref = d["ref"]
    n = api("POST", "/decision/api/notings",
            {"notingType": "CAM_NOTE", "subjectType": "Application", "subjectRef": ref,
             "title": "Annual CAM note", "narrative": "Credit assessment memo for annual review.",
             "payload": {"reviewPeriod": "FY2025"}}, actor="rm.user")
    nref = n["notingRef"]
    api("POST", f"/decision/api/notings/{nref}/submit", actor="rm.user")
    api("POST", f"/decision/api/notings/{nref}/approve", {"note": "reviewed, approved"}, actor="credit.officer")
    stats.seeded["noting"] += 1


# --------------------------------------------------------------- GenAI + XAI surfaces

def _model_answers(view):
    """Best-effort: derive an answer per resolved-model question (first option / neutral)."""
    if not isinstance(view, dict):
        return []
    src = view.get("payload") if isinstance(view.get("payload"), dict) else view
    sections = src.get("sections") or view.get("sections") or []
    qs = []
    for sec in (sections or []):
        for q in (sec.get("questions") or []):
            qs.append(q)
    if not qs:
        qs = view.get("questions") or src.get("questions") or []
    answers = []
    for q in qs:
        key = q.get("key") or q.get("questionKey")
        if not key:
            continue
        opts = q.get("options") or q.get("allowedValues")
        if opts:
            first = opts[0]
            val = first.get("label") if isinstance(first, dict) else first
        elif str(q.get("type", "")).upper() in ("NUMERIC", "NUMBER"):
            val = 1
        else:
            val = "Strong"
        answers.append({"questionKey": key, "value": val})
    return answers


def seed_genai_xai(d, stats):
    """Populate the GenAI + Explainable-AI surfaces on a showcase deal: credit proposal,
    AI commentary, doc-intel extraction, advisory RAG band, macro-impact scenario, and a
    resolved+auto-answered scoring model. All advisory — the authoritative rating/pricing
    are never mutated (proven by e2e_smoke §§37/39/41). Each sub-step self-guards so one
    disabled/absent capability never blocks the rest."""
    ref = d["ref"]
    seg = d["segment"]

    def step(label, fn):
        try:
            fn()
        except Exception as e:  # noqa: BLE001
            stats.warn(f"{label} {ref}: {e!r}")

    def _proposal():
        api("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", actor="analyst.user")
        stats.seeded["credit_proposal"] += 1

    def _commentary():
        api("POST", f"/decision/api/commentary/applications/{ref}/draft",
            {"section": "financial_commentary"}, actor="analyst.user")
        stats.seeded["commentary"] += 1

    def _doc_intel():
        doc = api("POST", f"/origination/api/applications/{ref}/documents",
                  {"fileName": "audited_financials.pdf", "declaredType": "FINANCIAL_STATEMENT"},
                  actor="analyst.user")
        api("POST", f"/origination/api/doc-intel/documents/{doc['id']}/extract", actor="doc.intel")
        stats.seeded["doc_intel"] += 1

    def _rag():
        api("POST", f"/risk/api/risk/{ref}/rag", actor="risk.analyst")
        stats.seeded["rag"] += 1

    def _macro():
        api("POST", f"/risk/api/risk/{ref}/macro-impact",
            {"scenarioName": "Stagflation", "interestRateBps": 200, "gdpGrowthDeltaPct": -2,
             "sectorOutlook": "DETERIORATING"}, actor="risk.analyst")
        stats.seeded["macro_impact"] += 1

    def _model():
        # Best-effort: resolve the deal's rating model then auto-answer it. We do NOT confirm
        # the instance, so an advisory OR a model-of-record definition is left completely
        # unable to move the already-booked authoritative grade. If no model resolves for the
        # deal's (segment, sector) the surface is simply left as-is.
        st, view = call("POST", f"/risk/api/risk/{ref}/model/resolve", actor="risk.analyst")
        if st != 200 or not isinstance(view, dict):
            return
        answers = _model_answers(view) or [{"questionKey": "risk_view", "value": "Strong"}]
        st2, _ = call("POST", f"/risk/api/risk/{ref}/model/answer",
                      {"answers": answers}, actor="risk.analyst")
        if st2 == 200:
            stats.seeded["scoring_model"] += 1

    step("credit_proposal", _proposal)
    step("commentary", _commentary)
    step("doc_intel", _doc_intel)
    step("rag", _rag)
    step("macro_impact", _macro)
    step("scoring_model", _model)


# --------------------------------------------------------------- singleton artifacts

def seed_escrow(showcase, stats):
    """Escrow account + versioned budget lines + category-tagged transactions (deterministic
    budget-vs-actual RAG). Lifted from e2e_escrow.py; needs the ESCROW_UTILISATION master."""
    for d in showcase[:2]:
        acc = api("POST", "/portfolio/api/escrow/accounts",
                  {"subjectType": "FACILITY", "subjectRef": f"FAC-{d['ref']}",
                   "purpose": "RERA project escrow", "currency": "INR", "openingBalance": 0},
                  actor="portfolio.manager")
        ref = acc["escrowRef"]
        api("POST", f"/portfolio/api/escrow/accounts/{ref}/budget-lines",
            {"category": "MATERIALS", "budgetedAmount": 1_000_000, "effectiveFrom": "2026-01-01"},
            actor="portfolio.manager")
        api("POST", f"/portfolio/api/escrow/accounts/{ref}/budget-lines",
            {"category": "LABOUR", "budgetedAmount": 500_000}, actor="portfolio.manager")
        for amt, cat in ((500_000, "MATERIALS"), (350_000, "MATERIALS"), (100_000, "LABOUR")):
            api("POST", f"/portfolio/api/escrow/accounts/{ref}/transactions",
                {"amount": amt, "direction": "DEBIT", "category": cat}, actor="portfolio.manager")
        api("POST", f"/portfolio/api/escrow/accounts/{ref}/transactions",
            {"amount": 200_000, "direction": "CREDIT", "category": "MATERIALS", "memo": "deposit"},
            actor="portfolio.manager")
        api("GET", f"/portfolio/api/escrow/accounts/{ref}/budget-vs-actual")  # SYSTEM assessment
        stats.seeded["escrow"] += 1


def seed_scf(d, stats):
    """SCF anchor programme + spokes -> submit -> approve (credit.head/CREDIT_HEAD).
    Lifted from e2e_scf.py; anchor is a real seeded obligor."""
    anchor = d["cp"]
    v = api("POST", "/origination/api/scf/programs",
            {"anchorRef": anchor["reference"], "anchorName": anchor["legalName"],
             "programType": "VENDOR", "programLimit": 500_000_000, "perSpokeCap": 40_000_000,
             "currency": "INR"}, actor="rm.user")
    scf = v["program"]["scfRef"]
    api("POST", f"/origination/api/scf/programs/{scf}/spokes",
        {"spokeRef": "CP-SPOKE-DEMO-1", "spokeName": "Anvil Components Pvt Ltd",
         "requestedAmount": 20_000_000}, actor="rm.user")
    api("POST", f"/origination/api/scf/programs/{scf}/spokes",
        {"spokeRef": "CP-SPOKE-DEMO-2", "spokeName": "Cobalt Castings Pvt Ltd",
         "requestedAmount": 30_000_000}, actor="rm.user")
    api("POST", f"/origination/api/scf/programs/{scf}/submit", actor="rm.user")
    api("POST", f"/origination/api/scf/programs/{scf}/approve",
        {"note": "credit committee ok"}, actor="credit.head")
    stats.seeded["scf"] += 1


def seed_ip_note(d, stats):
    """In-Principle note sponsoring a real obligor -> submit -> approve. Lifted from e2e_ip_note.py."""
    anchor = d["cp"]
    n = api("POST", "/origination/api/ip-notes", {
        "counterpartyId": anchor["id"], "counterpartyRef": anchor["reference"],
        "counterpartyName": anchor["legalName"], "jurisdiction": "IN-RBI", "segment": d["segment"],
        "facilityType": "TERM_LOAN", "proposedAmount": 400_000_000, "currency": "INR",
        "tenorMonths": 60, "purpose": "Capex",
        "prospectSummary": "Long-standing relationship; strong repayment record.",
        "payload": {"collateralType": "PROPERTY", "collateralValue": 380_000_000, "secured": True},
    }, actor="rm.user")
    ipn = n["ipNoteRef"]
    api("POST", f"/origination/api/ip-notes/{ipn}/submit", actor="rm.user")
    api("POST", f"/origination/api/ip-notes/{ipn}/approve",
        {"note": "credit sign-off — proceed to full application"}, actor="credit.officer")
    stats.seeded["ip_note"] += 1


def seed_perfection(stats):
    """MOE security-perfection case -> every role-gated step DONE -> COMPLETED.
    Lifted from e2e_perfection.py; needs the PERFECTION_MOE checklist + role grants."""
    tag = str(int(time.time()))
    cv = api("POST", "/decision/api/perfection/cases",
             {"subjectType": "COLLATERAL", "subjectRef": f"COL-DEMO-{tag}",
              "applicationRef": f"PERF-DEMO-{tag}"}, actor="cad.ops")
    perf = cv["perfectionCase"]["perfRef"]
    api("POST", f"/decision/api/perfection/cases/{perf}/steps/TITLE_SEARCH/complete",
        {"role": "LEGAL", "evidence": "DMS-TS-1"}, actor="legal.counsel")
    api("POST", f"/decision/api/perfection/cases/{perf}/steps/LEGAL_OPINION/complete",
        {"role": "LEGAL", "evidence": "DMS-LO-1"}, actor="legal.counsel")
    api("POST", f"/decision/api/perfection/cases/{perf}/steps/VALUATION/complete",
        {"role": "VENDOR", "evidence": "VAL-CERT-1"}, actor="vendor.user")
    api("POST", f"/decision/api/perfection/cases/{perf}/steps/MOE_EXECUTION/complete",
        {"role": "LMO", "evidence": "DMS-MOE-1"}, actor="lmo.user")
    # MOE-vetting SoD: vetting actor (cad.ops) must differ from the MOE-execution actor (lmo.user).
    api("POST", f"/decision/api/perfection/cases/{perf}/steps/MOE_VETTING/complete",
        {"role": "CAD_OPS", "evidence": "DMS-VET-1"}, actor="cad.ops")
    api("POST", f"/decision/api/perfection/cases/{perf}/steps/CERSAI_FILING/complete",
        {"role": "CAD_OPS", "evidence": "CERSAI-REF-1"}, actor="cad.ops")
    stats.seeded["perfection"] += 1


def seed_coi(d, stats):
    """Conflict-of-interest attestation (DECLARED_MANAGED record) on a showcase deal.
    Lifted from e2e_coi.py — the record path only; we never toggle the DOA pack."""
    api("POST", "/decision/api/coi",
        {"subjectType": "application", "subjectRef": d["ref"], "role": "CREDIT_COMMITTEE",
         "declaration": "DECLARED_MANAGED", "note": "Prior mandate; recused from pricing."},
        actor="credit.committee")
    stats.seeded["coi"] += 1


def seed_exception(stats):
    """Unified exception register: a real open MER item + a manual tickler (assigned).
    Lifted from e2e_exceptions.py."""
    subj = f"EXC-DEMO-{int(time.time())}"
    api("POST", "/decision/api/mer/raise", {
        "applicationRef": subj, "counterpartyName": "Demo Exceptions Ltd",
        "itemType": "DEFERRED_DOCUMENT", "category": "DOCUMENT",
        "description": "Board resolution pending", "criticality": "HIGH",
        "dueDate": _iso_in(10), "recurring": False, "owner": "rm.user"}, actor="cad.officer")
    t = api("POST", "/portfolio/api/exceptions/ticklers", {
        "subjectRef": subj, "title": "Chase board resolution", "description": "follow up with RM",
        "priority": "HIGH", "dueAt": _iso_in(7)}, actor="portfolio.manager")
    api("POST", f"/portfolio/api/exceptions/ticklers/{t['ticklerRef']}/assign",
        {"toActor": "rm.user"}, actor="portfolio.manager")
    stats.seeded["exception"] += 1


def seed_group_and_cashflow(showcase, stats):
    """Tag 2-3 showcase obligors into ONE borrower group, then render Global Cashflow
    (deterministic consolidation) + group decisioning (insights + combined proposal).
    Lifted from e2e_global_cashflow.py + e2e_smoke.py §43. Members' spreads are untouched."""
    members = showcase[:3]
    if len(members) < 2:
        return
    grp = api("POST", "/counterparty/api/initiation/groups",
              {"name": "Helix Demo Borrower Group", "groupRmId": "rm.user", "country": "IN",
               "multiCountry": False}, actor="rm.user")
    gid, gref = grp["id"], grp["reference"]
    for m in members:
        api("POST", f"/counterparty/api/initiation/counterparties/{m['cp']['id']}/group/{gid}",
            actor="rm.user")
    stats.seeded["group"] += 1
    _safe(stats, "global_cashflow", lambda: (
        api("POST", "/portfolio/api/portfolio/global-cashflow", {"groupReference": gref},
            actor="portfolio.manager"), stats.seeded.update({"global_cashflow": 1})))
    _safe(stats, "group_decisioning", lambda: (
        api("GET", f"/decision/api/decisions/groups/{gref}/insights", actor="credit.head"),
        api("POST", f"/decision/api/decisions/groups/{gref}/combined-proposal/generate",
            actor="analyst.user"), stats.seeded.update({"group_proposal": 1})))


def seed_monitoring_tail(d, stats):
    """The 'golden deal' monitoring tail: limit tree + CP register + one drawdown release +
    a CAD case (with waiver) + an MER register materialised from CAD. So Limits / Disbursement
    / CAD / Monitoring don't dead-end on empty states. Lifted from e2e_predisbursement.py +
    e2e_smoke.py §§28/33."""
    ref = d["ref"]
    _safe(stats, "limit_tree", lambda: (
        api("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops", ok=(200, 201, 409)),
        stats.seeded.update({"limit_tree": 1})))

    def _disbursement():
        facs = api("GET", f"/origination/api/applications/{ref}/facilities")
        if not facs:
            return
        facility_ref = facs[0]["reference"]
        reg = api("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
        for c in (reg or []):
            if c.get("mandatory") and c.get("status") == "OPEN":
                if c.get("code") == "CP-MAC":
                    api("POST", f"/decision/api/cps/{c['id']}/waive",
                        {"reason": "RM letter confirms no MAC"}, actor="credit.committee")
                else:
                    api("POST", f"/decision/api/cps/{c['id']}/clear",
                        {"evidenceRef": "DOC-" + str(c.get('code', 'CP')), "note": "evidence on file"},
                        actor="cad.maker")
        dd = api("POST", f"/decision/api/disbursement/{ref}/request",
                 {"facilityRef": facility_ref, "amount": 150_000_000, "currency": "INR",
                  "purpose": "first tranche", "narrative": "Civil works tranche"}, actor="credit.ops")
        # request / authorise / release must be three distinct humans (SoD).
        api("POST", f"/decision/api/disbursement/{dd['id']}/authorize",
            {"note": "first tranche authorised"}, actor="credit.officer")
        api("POST", f"/decision/api/disbursement/{dd['id']}/release", actor="treasury.ops")
        stats.seeded["drawdown"] += 1

    _safe(stats, "disbursement", _disbursement)

    def _cad_mer():
        cad = api("POST", "/decision/api/cad/cases",
                  {"applicationRef": ref, "counterpartyName": d["cp"]["legalName"], "cpType": "NEW"},
                  actor="cad.officer")
        case_id = cad["cadCase"]["id"]
        items = cad.get("items") or []
        for it in items[:-1]:
            api("POST", f"/decision/api/cad/items/{it['id']}",
                {"status": "COMPLIED", "docRef": "DMS-CAD-1"}, actor="cad.officer")
        if items:
            last = items[-1]
            dev = api("POST", f"/decision/api/cad/items/{last['id']}/deviation",
                      {"type": "WAIVER", "reason": "Insurance assignment pending; 30-day cure"},
                      actor="cad.officer")
            # 2-level deviation approval (L1 != L2 != raiser).
            api("POST", f"/decision/api/cad/deviations/{dev['id']}/decision",
                {"approve": True, "comment": "ok L1"}, actor="cad.l1")
            api("POST", f"/decision/api/cad/deviations/{dev['id']}/decision",
                {"approve": True, "comment": "ok L2"}, actor="cad.l2")
        api("POST", f"/decision/api/cad/cases/{case_id}/complete", actor="cad.officer")
        stats.seeded["cad_case"] += 1
        # Materialise an MER register from the CAD case (deferred docs + renewals).
        api("POST", f"/decision/api/mer/generate/from-cad/{case_id}?owner=rm.user", actor="cad.officer")
        stats.seeded["mer"] += 1

    _safe(stats, "cad_mer", _cad_mer)


def seed_delegation_and_tasks(showcase, stats):
    """Populate RoleDashboard 'My Tasks' + the delegation flows for the DEMO LOGIN identities
    (rm.user / analyst.user / credit.head — NOT the *.bot seed actors): round-robin auto-assign,
    directly-assigned WorkItems on real showcase refs, OPEN queue tasks, and OOO-delegated tasks.
    Lifted from e2e_casework.py."""
    def task(body):
        api("POST", "/workflow/api/tasks", body, actor="workflow.seed")

    # 1. Round-robin queue -> auto-assigns rotating across the demo identities.
    _safe(stats, "rr_tasks", lambda: [task(
        {"subjectType": "Deal", "subjectRef": f"DEMO-RR-{i + 1}", "taskType": "CREDIT_REVIEW",
         "queueKey": "DEMO_CREDIT_QUEUE", "assignee": None, "dedupeKey": None})
        for i in range(6)] and stats.seeded.update({"rr_tasks": 6}))
    # 2. Directly-assigned WorkItems on real showcase deal refs (their My-Tasks inboxes).
    ids = ["rm.user", "analyst.user", "credit.head"]
    for i, d in enumerate(showcase):
        # Counter.update({k: n}) ADDS n — pass a constant 1 per call, never a running total.
        _safe(stats, "assigned_task", lambda d=d, who=ids[i % len(ids)]: (task(
            {"subjectType": "Application", "subjectRef": d["ref"], "taskType": "CREDIT_REVIEW",
             "queueKey": None, "assignee": who, "dedupeKey": f"REVIEW:{d['ref']}"}),
            stats.seeded.update({"assigned_task": 1})))
    # 3. OPEN queue tasks (MANUAL pool) — sit unclaimed in the queue.
    _safe(stats, "queue_tasks", lambda: [task(
        {"subjectType": "Deal", "subjectRef": f"DEMO-Q-{i + 1}", "taskType": "MONITORING_REVIEW",
         "queueKey": "DEMO_MONITORING_QUEUE", "assignee": None, "dedupeKey": None})
        for i in range(2)] and stats.seeded.update({"queue_tasks": 2}))
    # 4. Delegation: tasks on a pool whose OOO member (vacation.rm) delegates to rm.user.
    _safe(stats, "delegation_tasks", lambda: [task(
        {"subjectType": "Deal", "subjectRef": f"DEMO-DLG-{i + 1}", "taskType": "CREDIT_REVIEW",
         "queueKey": "DEMO_DELEGATION_QUEUE", "assignee": None, "dedupeKey": None})
        for i in range(2)] and stats.seeded.update({"delegation_tasks": 2}))


def seed_integrations(showcase, stats):
    """Auto-Data-Fetch panel / Integration Hub: bureau + CRM pulls (simulated, no external
    call) on a couple of showcase obligors. Lifted from e2e_source_ingest.py."""
    for d in showcase[:2]:
        cid = d["cp"]["id"]
        _safe(stats, "bureau_pull", lambda cid=cid: (
            api("POST", f"/counterparty/api/counterparties/{cid}/ingest/bureau/pull", actor="rm.user"),
            stats.seeded.update({"bureau_pull": 1})))
        _safe(stats, "crm_pull", lambda cid=cid: (
            api("POST", f"/counterparty/api/counterparties/{cid}/ingest/crm/pull", actor="rm.user"),
            stats.seeded.update({"crm_pull": 1})))


def seed_exports(stats):
    """Downstream canonical export feeds (ERM / Finance-GL / CPR / CRILC) once exposures +
    ECL are booked, so the Integration Hub / Exports screen opens populated. Lifted from
    e2e_smoke.py §42 + e2e_india_rbi.py."""
    for feed in ("erm", "finance-gl", "cpr", "crilc"):
        _safe(stats, f"export_{feed}", lambda feed=feed: (
            api("POST", f"/portfolio/api/exports/{feed}", actor="export.batch"),
            stats.seeded.update({f"export_{feed}": 1})))


def pick_showcase(stats, n=5):
    """First n distinct-counterparty fully-booked deals -> the showcase obligors."""
    seen, out = set(), []
    for d in stats.booked_deals:
        cid = (d.get("cp") or {}).get("id")
        if cid in seen:
            continue
        seen.add(cid)
        out.append(d)
        if len(out) >= n:
            break
    return out


def seed_showcase(stats):
    """Orchestrate the advanced-artifact phase over ~5 showcase obligors captured from the
    BOOKED cohort. Idempotent-friendly: on a re-run every core name is skipped, so no fresh
    deals are booked, `booked_deals` is empty, and this phase is a graceful no-op."""
    showcase = pick_showcase(stats, 5)
    if not showcase:
        print("\n (showcase) no freshly-booked obligors captured this run — "
              "advanced artifacts skipped (idempotent re-run).")
        return
    names = ", ".join((d["cp"].get("legalName") or d["ref"]) for d in showcase)
    print(f"\n seeding advanced demo artifacts on {len(showcase)} showcase obligor(s):")
    print(f"   {names}")

    seed_prereq_masters(stats)

    # Per-obligor governed artifacts (each driven to a mid/approved state, not just DRAFT).
    for d in showcase:
        _safe(stats, "banking_asr", seed_banking_asr, d, stats)
        _safe(stats, "risk_note", seed_risk_note, d, stats)
        _safe(stats, "cam_annexure", seed_cam_annexure, d, stats)
        _safe(stats, "monitoring_artifact", seed_monitoring_artifact, d, stats)
        _safe(stats, "noting", seed_noting, d, stats)

    # GenAI + XAI surfaces on the first 2-3 showcase deals (self-guarding per capability).
    for d in showcase[:3]:
        seed_genai_xai(d, stats)

    # Singleton / relationship-level artifacts.
    _safe(stats, "escrow", seed_escrow, showcase, stats)
    _safe(stats, "scf", seed_scf, showcase[0], stats)
    _safe(stats, "ip_note", seed_ip_note, showcase[0], stats)
    _safe(stats, "perfection", seed_perfection, stats)
    _safe(stats, "coi", seed_coi, showcase[0], stats)
    _safe(stats, "exception", seed_exception, stats)
    _safe(stats, "group_cashflow", seed_group_and_cashflow, showcase, stats)
    _safe(stats, "monitoring_tail", seed_monitoring_tail, showcase[0], stats)   # golden deal
    _safe(stats, "delegation_tasks", seed_delegation_and_tasks, showcase, stats)
    _safe(stats, "integrations", seed_integrations, showcase, stats)
    _safe(stats, "exports", seed_exports, stats)


# --------------------------------------------------------------- dry-run preview

def print_plan(plan):
    print("\n" + "=" * 68)
    print(" Helix demo-data seeder · DRY RUN (no HTTP calls made)")
    print("=" * 68)
    total = len(plan)
    obligors = sum(1 for p in plan if p["stage"] != "PROSPECT")
    print(f" gateway target        : {GW}")
    print(f" random seed           : {SEED}"
          f"{('  run-tag=' + RUN_TAG) if RUN_TAG else ''}")
    print(f" total counterparties  : {total}  (obligors {obligors} + prospects {total - obligors})")
    print("\n planned stage funnel  :")
    per_stage = Counter(p["stage"] for p in plan)
    for stage, _ in STAGE_PLAN:
        print(f"   {stage:12s} {per_stage[stage]:>3d}")
    dropped = sum(1 for p in plan if p["dropped"])
    existing = sum(1 for p in plan if p["existing"])
    print(f"   (of PROSPECT: ~{dropped} -> DROPPED, rest parked at DRAFT)")
    print(f"   (of BOOKED: ~{existing} existing borrowers run the lifecycle twice)")
    print("\n by segment            :")
    per_seg = Counter(p["segment"] for p in plan)
    for seg, _ in SEGMENTS:
        print(f"   {seg:24s} {per_seg[seg]:>3d}")
    print("\n sample generated names (India-specific, procedural):")
    step = max(1, total // 12)
    for p in plan[::step][:12]:
        print(f"   [{p['stage']:11s}] {p['name']:44s} CIN {p['regNo']}")
    assert not any("Acme Shell" in p["name"] for p in plan), "negative-list name generated"
    print("\n No network calls were made. Start the stack and drop --dry-run to seed.")
    print("=" * 68)


# --------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description="Seed the Helix demo book via the real gateway APIs.")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print the population plan + funnel + sample names and make ZERO HTTP calls.")
    args = parser.parse_args()

    plan = build_plan()
    if args.dry_run:
        print_plan(plan)
        return 0

    # Gateway reachability check (also warms config-service).
    st, _ = call("GET", "/config/api/jurisdictions")
    if st != 200:
        print(f"Gateway not reachable at {GW} (GET /config/api/jurisdictions -> {st}).")
        print("Start the stack first:  bash scripts/run-all.sh   (health-gated on :8080-8088)")
        return 2

    # Idempotency self-guard: skip any generated legalName that already exists.
    st, existing_cps = call("GET", "/counterparty/api/counterparties")
    existing_names = {c.get("legalName") for c in existing_cps} if isinstance(existing_cps, list) else set()

    stats = Stats()
    t0 = time.time()
    for b in plan:
        if b["name"] in existing_names:
            stats.skipped_existing += 1
            continue

        if b["stage"] == "PROSPECT":
            create_prospect(b, stats)
            continue

        cp = onboard(b, stats)
        if cp is None:
            continue
        if b["stage"] == "ONBOARDED":
            stats.landed["ONBOARDED"] += 1
            continue

        # Existing borrowers carry a prior booked deal — model it by running the lifecycle
        # twice: an earlier clean/approved facility, then the new ask.
        if b["existing"]:
            run_lifecycle(cp, dict(b, stressed=False), stop_at="BOOKED", stats=stats, existing=False)
            stats.existing_repeats += 1
        landed = run_lifecycle(cp, b, stop_at=b["stage"], stats=stats, existing=b["existing"])
        if landed:
            stats.landed[landed] += 1

    # Advanced-artifact phase: layer showcase artifacts onto the freshly-booked obligors so
    # the ~14 advanced screens open POPULATED for a client click-through. Fully guarded — a
    # failure here degrades to a warning and never aborts the core seed above.
    _safe(stats, "showcase-phase", seed_showcase, stats)
    elapsed = time.time() - t0

    # ------------------------------------------------------------ readout
    _, mis = call("GET", "/portfolio/api/mis/dashboard")
    _, summary = call("GET", "/portfolio/api/portfolio/summary")

    print("\n" + "=" * 68)
    print(" Helix demo-data seeder · India book")
    print("=" * 68)
    print(f" elapsed                : {elapsed:.1f}s")
    print(f" skipped (already exist): {stats.skipped_existing}")
    print(f" KYC blocked (screening): {stats.kyc_blocked}")
    print(f" existing repeats       : {stats.existing_repeats}")
    print("\n obligors parked by stage:")
    stage_order = ["PROSPECT_DRAFT", "PROSPECT_DROPPED", "ONBOARDED", "SPREADING",
                   "RATING", "PENDING", "DECISIONED", "BOOKED"]
    for s in stage_order:
        print(f"   {s:18s} {stats.landed[s]:>3d}")
    total_landed = sum(stats.landed.values())
    print(f"   {'TOTAL':18s} {total_landed:>3d}")
    print("\n decision outcomes       :")
    for o in ["APPROVE", "CONDITIONAL_APPROVE", "REFER", "DECLINE"]:
        if stats.outcomes[o]:
            print(f"   {o:20s} {stats.outcomes[o]:>3d}")
    print("\n booked exposures        :")
    print(f"   total booked         {stats.booked_exposures:>3d}")
    for bkt in ["STANDARD", "SMA", "NPA"]:
        print(f"   {bkt:20s} {stats.dpd_buckets[bkt]:>3d}")
    print("\n by segment              :")
    for seg, _ in SEGMENTS:
        print(f"   {seg:24s} {stats.by_segment[seg]:>3d}")
    print("\n by final grade          :")
    for g in ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]:
        if stats.by_grade[g]:
            print(f"   {g:>5s} {stats.by_grade[g]:>3d}")
    if isinstance(summary, dict) and summary.get("totalEad") is not None:
        print("\n book (portfolio summary):")
        print(f"   total EAD            {summary.get('totalEad', 0):>18,.0f}")
        print(f"   total RWA            {summary.get('totalRwa', 0):>18,.0f}")
        print(f"   total provision      {summary.get('totalReportedProvision', 0):>18,.0f}")
        print(f"   by stage             {summary.get('byStage')}")
    if isinstance(mis, dict) and "composition" in mis:
        print(" MIS dashboard populated : yes")
    if stats.seeded:
        print("\n advanced demo artifacts  :  (showcase obligors — advanced screens now populated)")
        for k in sorted(stats.seeded):
            print(f"   {k:22s} {stats.seeded[k]:>3d}")
    if stats.errors:
        print(f"\n {len(stats.errors)} non-fatal error(s) during the run (first 5):")
        for e in stats.errors[:5]:
            print(f"   - {e}")
    if stats.warnings:
        print(f"\n {len(stats.warnings)} advanced-artifact warning(s) (non-fatal; first 8):")
        for w in stats.warnings[:8]:
            print(f"   - {w}")
    print("=" * 68)
    print(f" Seeded {total_landed} obligors across {len([s for s in stage_order if stats.landed[s]])} stages"
          f" + {sum(stats.seeded.values())} advanced artifacts. "
          f"Re-run is idempotent (existing names skipped).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
