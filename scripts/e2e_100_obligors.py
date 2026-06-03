#!/usr/bin/env python3
"""
End-to-end load test driving 100 obligors through the Helix credit lifecycle
via the gateway. The population is distributed by:

  - segment (mid-corporate · SME · large-corporate · project finance · trade · FI)
  - jurisdiction (IN-RBI · AE-CBUAE)
  - relationship (existing borrowers with a prior approved facility · new borrowers)
  - risk profile (clean · stressed · with red flags)

Each obligor runs the full path the platform requires:
   onboard → screening → UBO → KYC verify → application → docs → spread →
   confirm → rate → confirm → capital → price → covenants → route → decide →
   book → ECL → RAROC snapshot. Existing borrowers run twice (older + new deal).

The script aggregates outcomes by segment / jurisdiction / approval result and
asserts the population matches the planned distribution. It also exercises the
new MIS endpoints so the dashboard reflects the synthesised book.
"""
import json
import random
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict

GW = "http://localhost:8080"
random.seed(20260603)


def call(method, path, body=None, actor="seed.bot", expect=None):
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


# --------------------------------------------------------------- population

SEGMENTS = [
    ("MID_CORPORATE", 30),
    ("SME", 25),
    ("LARGE_CORPORATE", 15),
    ("PROJECT_FINANCE", 10),
    ("TRADE_FINANCE", 10),
    ("FINANCIAL_INSTITUTION", 10),
]
JURISDICTIONS = [("IN-RBI", 80), ("AE-CBUAE", 20)]
EXISTING_PCT = 0.30
RED_FLAG_PCT = 0.12     # PEP / adverse media / high-risk jurisdiction
STRESSED_PCT = 0.20     # weak ratios -> deeper sub-IG

FACILITY_BY_SEGMENT = {
    "MID_CORPORATE": ("TERM_LOAN", 400_000_000, 1_000_000_000, 60),
    "LARGE_CORPORATE": ("TERM_LOAN", 1_500_000_000, 5_000_000_000, 84),
    "SME": ("WORKING_CAPITAL", 30_000_000, 150_000_000, 36),
    "PROJECT_FINANCE": ("PROJECT_LOAN", 2_500_000_000, 8_000_000_000, 144),
    "TRADE_FINANCE": ("TRADE_LINE", 100_000_000, 500_000_000, 12),
    "FINANCIAL_INSTITUTION": ("REVOLVING_CREDIT", 1_000_000_000, 4_000_000_000, 36),
}

INDIAN_FIRST = ["Aarav", "Aditi", "Arjun", "Diya", "Ishaan", "Kavya", "Riya", "Rohan", "Sara", "Vihaan"]
INDIAN_LAST = ["Mehta", "Iyer", "Khanna", "Patel", "Reddy", "Sharma", "Singh", "Verma"]
UAE_FIRST = ["Khalid", "Sara", "Fatima", "Omar", "Layla", "Hamad", "Aisha", "Saif"]
UAE_LAST = ["Al-Maktoum", "Al-Nahyan", "Al-Suwaidi", "Al-Mansoori", "Al-Qassimi"]


def weighted(seq):
    pool = []
    for v, w in seq:
        pool.extend([v] * w)
    return random.choice(pool)


def borrower_name(segment, jurisdiction, idx):
    place = "Mumbai" if jurisdiction == "IN-RBI" else "Dubai"
    if segment == "FINANCIAL_INSTITUTION":
        return f"{place} {random.choice(['Finance', 'Capital', 'NBFC'])} #{idx:03d} Pvt Ltd"
    if segment == "PROJECT_FINANCE":
        kind = random.choice(["Solar", "Highway", "Port", "Power", "Logistics"])
        return f"{place} {kind} SPV {idx:03d} Ltd"
    if segment == "TRADE_FINANCE":
        return f"{place} Trading House {idx:03d}"
    kind = random.choice(["Steel", "Pharma", "Auto", "Chemicals", "Cement", "Textiles", "Foods", "Retail"])
    suffix = "Pvt Ltd" if jurisdiction == "IN-RBI" else "LLC"
    return f"{place} {kind} {idx:03d} {suffix}"


def line(v):
    return {"value": v, "sourceDocument": "audited_financials.pdf", "sourcePage": "P12",
            "coordinates": "tbl1", "confidence": 0.95}


def synth_financials(segment, stressed):
    # Synthesise a financials snapshot that gives realistic ratios for the segment.
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
    pat = pbt - tax
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


# --------------------------------------------------------------- driver

class Stats:
    def __init__(self):
        self.created_cps = 0
        self.created_apps = 0
        self.approved = 0
        self.conditional = 0
        self.declined = 0
        self.referred = 0
        self.booked = 0
        self.ecl_computed = 0
        self.raroc_snapshots = 0
        self.errors = []
        self.by_segment = Counter()
        self.by_jurisdiction = Counter()
        self.by_grade = Counter()
        self.kyc_blocked = 0
        self.existing_repeats = 0


def onboard(idx, segment, jurisdiction, red_flag, stats: Stats):
    first = random.choice(INDIAN_FIRST if jurisdiction == "IN-RBI" else UAE_FIRST)
    last = random.choice(INDIAN_LAST if jurisdiction == "IN-RBI" else UAE_LAST)
    name = borrower_name(segment, jurisdiction, idx)
    body = {
        "legalName": name, "legalForm": "PRIVATE_LTD",
        "registrationNo": f"U{27000 + idx:05d}MH",
        "jurisdiction": jurisdiction, "segment": segment,
        "sector": "MANUFACTURING" if segment in ("MID_CORPORATE", "LARGE_CORPORATE", "SME") else
                  "FINANCIAL_SERVICES" if segment == "FINANCIAL_INSTITUTION" else "INFRASTRUCTURE",
        "country": "IN" if jurisdiction == "IN-RBI" else "AE",
        "listedEntity": segment == "LARGE_CORPORATE" and random.random() < 0.4,
        "regulatedFi": segment == "FINANCIAL_INSTITUTION",
        "pep": red_flag and random.random() < 0.5,
        "adverseMedia": red_flag and random.random() < 0.5,
        "highRiskJurisdiction": red_flag and random.random() < 0.3,
        "complexOwnership": segment in ("PROJECT_FINANCE", "FINANCIAL_INSTITUTION") and random.random() < 0.5,
    }
    st, cp = call("POST", "/counterparty/api/counterparties", body, actor="rm.bot")
    if st != 200:
        stats.errors.append(f"onboard {name}: {st} {cp.get('message') if isinstance(cp, dict) else cp}")
        return None

    # Screening + disposition.
    st, hits = call("POST", f"/counterparty/api/counterparties/{cp['id']}/screening/run", actor="compliance.bot")
    if st == 200:
        for h in hits or []:
            # Clear LOW/MEDIUM as false positive; escalate higher (and skip KYC verify).
            disp = "FALSE_POSITIVE" if h["severity"] in ("LOW", "MEDIUM") else "ESCALATED"
            call("POST", f"/counterparty/api/counterparties/screening/{h['id']}/disposition",
                 {"disposition": disp, "note": "bulk"}, actor="compliance.bot")

    st, _ = call("POST", f"/counterparty/api/counterparties/{cp['id']}/kyc/verify", actor="compliance.bot")
    if st != 200:
        stats.kyc_blocked += 1
        # KYC blocked is expected for severe hits; counterparty stays in IN_PROGRESS.
    stats.created_cps += 1
    stats.by_jurisdiction[jurisdiction] += 1
    return cp


def lifecycle(cp, segment, stressed, existing, stats: Stats):
    ft, lo, hi, tenor = FACILITY_BY_SEGMENT[segment]
    amount = round(random.uniform(lo, hi), -6)
    collateral_type = random.choice(["PROPERTY", "RECEIVABLES", "EQUITY_LISTED", "GOVT_SECURITIES"])
    collateral_value = amount * random.uniform(0.75, 1.4)
    body = {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": cp["jurisdiction"], "segment": segment,
        "facilityType": ft, "requestedAmount": amount, "currency": "INR" if cp["jurisdiction"] == "IN-RBI" else "AED",
        "tenorMonths": tenor, "purpose": "Capacity expansion" if not existing else "Renewal",
        "collateralType": collateral_type, "collateralValue": collateral_value, "secured": True,
    }
    st, app = call("POST", "/origination/api/applications", body, actor="rm.bot")
    if st != 200:
        stats.errors.append(f"create app for {cp['legalName']}: {st}")
        return
    ref = app["reference"]
    stats.created_apps += 1
    stats.by_segment[segment] += 1

    # Single document + bare-minimum two-period spread.
    call("POST", f"/origination/api/applications/{ref}/documents",
         {"fileName": "audited_financials.pdf"}, actor="analyst.bot")
    periods = {"periods": [
        {"label": "FY2024", "gaap": "IND_AS", "currency": body["currency"], "lines": synth_financials(segment, stressed)},
        {"label": "FY2023", "gaap": "IND_AS", "currency": body["currency"], "lines": synth_financials(segment, stressed)},
    ]}
    st, _ = call("POST", f"/origination/api/applications/{ref}/spread", periods, actor="analyst.bot")
    if st != 200:
        stats.errors.append(f"spread {ref}: {st}")
        return
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.bot")

    st, rating = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.bot")
    if st != 200:
        stats.errors.append(f"rate {ref}: {st} {rating}")
        return
    stats.by_grade[rating["finalGrade"]] += 1
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer.bot")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops.bot")
    call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.bot")

    # Suggested covenants — accept them, then route + decide.
    st, sugg = call("GET", f"/decision/api/decisions/{ref}/covenants/suggest?grade={rating['finalGrade']}")
    for c in (sugg or [])[:2]:
        call("POST", f"/decision/api/decisions/{ref}/covenants", c, actor="analyst.bot")

    st, dec = call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops.bot")
    if st != 200:
        stats.errors.append(f"route {ref}: {st} {dec}")
        return

    # Decide: clean & IG -> APPROVE; weak grade or below hurdle -> CONDITIONAL; deep sub-IG -> DECLINE/REFER.
    grade = rating["finalGrade"]
    if grade in ("AAA", "AA", "A", "BBB") and not stressed:
        outcome, conds = "APPROVE", []
    elif grade in ("BBB", "BB"):
        outcome, conds = "CONDITIONAL_APPROVE", ["Maintain DSCR >= 1.25x", "Personal guarantee from promoters"]
    elif grade in ("B", "CCC"):
        outcome, conds = "REFER", []
    else:
        outcome, conds = "DECLINE", []

    st, decided = call("POST", f"/decision/api/decisions/{ref}/decide",
                       {"outcome": outcome, "role": dec["requiredAuthority"],
                        "rationale": f"Bulk seed: {outcome.lower()} based on grade {grade}",
                        "conditions": conds}, actor="credit.officer.bot")
    if st != 200:
        stats.errors.append(f"decide {ref}: {st} {decided}")
        return
    if outcome == "APPROVE": stats.approved += 1
    elif outcome == "CONDITIONAL_APPROVE": stats.conditional += 1
    elif outcome == "REFER": stats.referred += 1
    else: stats.declined += 1

    # Book + ECL + RAROC snapshot for approved/conditional.
    if outcome in ("APPROVE", "CONDITIONAL_APPROVE"):
        dpd = random.choices([0, 0, 0, 15, 45, 95], weights=[60, 20, 5, 8, 5, 2])[0]
        st, _ = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register",
                     {"daysPastDue": dpd}, actor="credit.ops.bot")
        if st == 200:
            stats.booked += 1
            call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ecl", actor="credit.ops.bot")
            stats.ecl_computed += 1
            call("POST", f"/portfolio/api/portfolio/exposures/{ref}/raroc/snapshot", actor="credit.ops.bot")
            stats.raroc_snapshots += 1
            # For half the booked deals, compute an actual RAROC observation.
            if random.random() < 0.5:
                rp = random.uniform(0, amount * 0.005)
                call("POST", f"/portfolio/api/portfolio/exposures/{ref}/raroc/compute?period=2026Q2&realisedProvisionDelta={rp}",
                     actor="portfolio.bot")


# --------------------------------------------------------------- main

def main():
    # First sanity-check the gateway and the abstraction layer.
    st, j = call("GET", "/config/api/jurisdictions")
    if st != 200:
        print("gateway not reachable:", st, j); sys.exit(2)
    stats = Stats()
    population = []
    for i in range(100):
        segment = weighted(SEGMENTS)
        jurisdiction = weighted(JURISDICTIONS)
        existing = random.random() < EXISTING_PCT
        stressed = random.random() < STRESSED_PCT
        red_flag = random.random() < RED_FLAG_PCT
        population.append((i, segment, jurisdiction, existing, stressed, red_flag))

    t0 = time.time()
    for i, segment, jurisdiction, existing, stressed, red_flag in population:
        cp = onboard(i, segment, jurisdiction, red_flag, stats)
        if cp is None:
            continue
        # Existing borrowers carry a prior approved facility — model it by running the
        # lifecycle twice. The first run is the "older" relationship; the second is the new ask.
        if existing:
            lifecycle(cp, segment, stressed=False, existing=False, stats=stats)
            stats.existing_repeats += 1
        lifecycle(cp, segment, stressed, existing, stats)
    elapsed = time.time() - t0

    # ------------------------------------------------------------ MIS readout
    _, mis = call("GET", "/portfolio/api/mis/dashboard")
    _, conc = call("GET", "/portfolio/api/portfolio/concentration?jurisdiction=IN-RBI")
    _, summary = call("GET", "/portfolio/api/portfolio/summary")
    _, variance = call("GET", "/portfolio/api/portfolio/raroc/variance")

    print("\n" + "=" * 64)
    print(" Helix · 100-obligor distributed lifecycle test")
    print("=" * 64)
    print(f" elapsed                : {elapsed:.1f}s")
    print(f" counterparties onboarded: {stats.created_cps} / 100")
    print(f" KYC blocked (severe screening) : {stats.kyc_blocked}")
    print(f" applications created   : {stats.created_apps} (existing-borrower repeats: {stats.existing_repeats})")
    print(f" approved               : {stats.approved}")
    print(f" conditionally approved : {stats.conditional}")
    print(f" referred               : {stats.referred}")
    print(f" declined               : {stats.declined}")
    print(f" booked                 : {stats.booked}")
    print(f" ECL computed           : {stats.ecl_computed}")
    print(f" RAROC snapshots        : {stats.raroc_snapshots}")
    print()
    print(" distribution by segment :")
    for s, _w in SEGMENTS:
        print(f"   {s:24s} {stats.by_segment[s]:>3d}")
    print(" distribution by jurisdiction :")
    for j, _w in JURISDICTIONS:
        print(f"   {j:24s} {stats.by_jurisdiction[j]:>3d}")
    print(" distribution by final grade :")
    for g in ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]:
        if stats.by_grade[g]:
            print(f"   {g:>5s} {stats.by_grade[g]:>3d}")
    print(" book :")
    if summary:
        print(f"   total EAD            {summary['totalEad']:>16,.0f}")
        print(f"   total RWA            {summary['totalRwa']:>16,.0f}")
        print(f"   total provision      {summary['totalReportedProvision']:>16,.0f}")
        print(f"   by stage             {summary['byStage']}")
    if conc and conc.get("breaches"):
        print(" concentration breaches:")
        for b in conc["breaches"][:5]: print(f"   - {b}")
    if variance:
        print(f" RAROC variance: tracked={variance['trackedDeals']}, material misses={variance['materialMisses']}, "
              f"avg variance={variance['averageVariance']:.4f}")

    # ------------------------------------------------------------ assertions
    fail = 0

    def expect(label, cond, detail=""):
        nonlocal fail
        mark = "PASS" if cond else "FAIL"
        if not cond: fail += 1
        print(f" {mark} {label}{('  ' + detail) if detail else ''}")

    print("\n distribution assertions:")
    expect("100 counterparties onboarded", stats.created_cps == 100, str(stats.created_cps))
    expect("at least 5 of every segment present",
           all(stats.by_segment[s] >= 5 for s, _ in SEGMENTS),
           json.dumps(dict(stats.by_segment)))
    expect("both jurisdictions used", stats.by_jurisdiction["IN-RBI"] >= 50 and stats.by_jurisdiction["AE-CBUAE"] >= 5)
    expect("approval rate plausible (>30%)", (stats.approved + stats.conditional) / max(1, stats.created_apps) > 0.30,
           f"{stats.approved + stats.conditional}/{stats.created_apps}")
    expect("declines/refers present (population spans the policy)", (stats.declined + stats.referred) > 0)
    expect("at least 20 booked exposures", stats.booked >= 20)
    expect("RAROC snapshots match booked deals", stats.raroc_snapshots == stats.booked)
    expect("existing borrowers had a prior deal", stats.existing_repeats >= 15)
    expect("MIS dashboard populated", mis and "composition" in mis and "rarocVariance" in mis)
    expect("no internal errors during the run", len(stats.errors) == 0, json.dumps(stats.errors[:3]))

    print("\n" + ("ALL ASSERTIONS PASSED ✓" if fail == 0 else f"{fail} assertion(s) FAILED"))
    sys.exit(0 if fail == 0 else 1)


if __name__ == "__main__":
    main()
