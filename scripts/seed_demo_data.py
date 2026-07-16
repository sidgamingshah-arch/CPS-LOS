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

    def err(self, msg):
        self.errors.append(msg)


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
    return "BOOKED"


def msg(b):
    return b.get("message") if isinstance(b, dict) else b


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
    if stats.errors:
        print(f"\n {len(stats.errors)} non-fatal error(s) during the run (first 5):")
        for e in stats.errors[:5]:
            print(f"   - {e}")
    print("=" * 68)
    print(f" Seeded {total_landed} obligors across {len([s for s in stage_order if stats.landed[s]])} stages. "
          f"Re-run is idempotent (existing names skipped).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
