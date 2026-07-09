#!/usr/bin/env python3
"""
One counterparty, full lifecycle, end-to-end through the live gateway.

Drives a single named obligor through every stage and prints the REAL
deterministic-engine outputs at each step (rating PD/LGD/grade, capital RWA,
RAROC pricing, ECL stage/provision, limit tree, workflow position). The
counterparty identity + financial figures are illustrative INPUTS; every
figure printed under "engine ->" is computed by the real services.

Includes a foreign (USD) comparative year to exercise the period-end FX
restatement that was just built.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"


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


def step(n, title):
    print(f"\n{'='*72}\n{n}. {title}\n{'='*72}")


def ok(st, b, label):
    if st not in (200, 201):
        print(f"  !! {label}: HTTP {st}\n     {json.dumps(b)[:300]}")
        sys.exit(1)
    return b


def inr(v):
    return f"INR {v:,.0f}" if v is not None else "—"


def pct(v, dp=2):
    return f"{v*100:.{dp}f}%" if v is not None else "—"


def line(v, doc="Bharat_Forgings_FY24_audited.pdf", page="P&L"):
    return {"value": v, "sourceDocument": doc, "sourcePage": page,
            "coordinates": "auto", "confidence": 0.96}


# ------------------------------------------------------------------ 1. ONBOARD
step(1, "Counterparty onboarding · KYC · screening")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Bharat Forgings & Castings Ltd",
    "legalForm": "PUBLIC_LTD",
    "registrationNo": "U27310MH1998PLC114521",
    "jurisdiction": "IN-RBI",
    "segment": "MID_CORPORATE",
    "sector": "MANUFACTURING",
    "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False,
    "adverseMedia": False, "highRiskJurisdiction": False, "complexOwnership": False,
}, actor="rm.anita")
cp = ok(st, cp, "create counterparty")
cid, cref = cp["id"], cp["reference"]
print(f"  onboarded     : {cp['legalName']}  (ref {cref}, id {cid})")
print(f"  jurisdiction  : {cp['jurisdiction']} · segment {cp['segment']} · sector {cp['sector']}")
print(f"  engine ->  CDD tier        : {cp.get('cddTier')}")
print(f"  engine ->  KYC status      : {cp.get('kycStatus')}")
print(f"  engine ->  presentation ccy: {cp.get('presentationCurrency')}  (Level-1 analysis currency)")

st, scr = call("POST", f"/counterparty/api/counterparties/{cid}/screening/run", actor="kyc.ops")
scr = ok(st, scr, "screening")
print(f"  engine ->  screening hits  : {len(scr) if isinstance(scr, list) else scr} "
      f"(AI-advisory, human dispositions)")
st, kyc = call("POST", f"/counterparty/api/counterparties/{cid}/kyc/verify", actor="kyc.ops")
kyc = ok(st, kyc, "kyc")
print(f"  engine ->  KYC verified     -> {kyc.get('kycStatus')}")


# ------------------------------------------------------------------ 2. APPLICATION
step(2, "Credit application · proposed facility")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cid, "counterpartyRef": cref, "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
    "facilityType": "TERM_LOAN", "requestedAmount": 1_200_000_000, "currency": "INR",
    "tenorMonths": 84, "purpose": "Capacity expansion — new forging line",
    "collateralType": "PROPERTY", "collateralValue": 1_500_000_000, "secured": True,
}, actor="rm.anita")
app = ok(st, app, "create application")
ref = app["reference"]
print(f"  application   : {ref}")
print(f"  facility      : TERM_LOAN  {inr(1_200_000_000)}  · 84m · secured by PROPERTY {inr(1_500_000_000)}")
print(f"  engine ->  status         : {app.get('status')}")


# ------------------------------------------------------------------ 3. SPREADING (with USD comparative)
step(3, "Financial spreading · multi-currency (FY24 INR, FY23 USD @ period-end)")
# FY24 reported in INR; FY23 reported in USD (group filing) -> restated at the
# dated FX_RATE point for 2023-03-31. Figures are realistic for a ~INR 14bn-revenue forger.
fy24 = {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "periodEnd": "2024-03-31", "lines": {
    "REVENUE": line(14_200_000_000), "COGS": line(9_100_000_000),
    "OPERATING_EXPENSES": line(2_350_000_000), "DEPRECIATION": line(560_000_000),
    "INTEREST_EXPENSE": line(430_000_000), "TAX": line(330_000_000),
    "TOTAL_ASSETS": line(16_800_000_000), "CURRENT_ASSETS": line(6_900_000_000),
    "CASH": line(1_250_000_000), "CURRENT_LIABILITIES": line(4_200_000_000),
    "SHORT_TERM_DEBT": line(1_600_000_000), "LONG_TERM_DEBT": line(2_900_000_000),
    "CURRENT_PORTION_LTD": line(620_000_000), "NET_WORTH": line(7_400_000_000),
    "CFO": line(1_880_000_000)}}
# FY23 in USD: ~ the prior-year INR figures / 82 (the 2023-03-31 dated rate), so
# restatement should recover sensible INR magnitudes ~10% below FY24.
usd23 = 82.0
def usd(v_inr):
    return round(v_inr / usd23, 2)
fy23 = {"label": "FY2023", "gaap": "IND_AS", "currency": "USD", "periodEnd": "2023-03-31", "lines": {
    "REVENUE": line(usd(12_600_000_000)), "COGS": line(usd(8_250_000_000)),
    "OPERATING_EXPENSES": line(usd(2_150_000_000)), "DEPRECIATION": line(usd(520_000_000)),
    "INTEREST_EXPENSE": line(usd(455_000_000)), "TAX": line(usd(250_000_000)),
    "TOTAL_ASSETS": line(usd(15_400_000_000)), "CURRENT_ASSETS": line(usd(6_300_000_000)),
    "CASH": line(usd(980_000_000)), "CURRENT_LIABILITIES": line(usd(3_950_000_000)),
    "SHORT_TERM_DEBT": line(usd(1_750_000_000)), "LONG_TERM_DEBT": line(usd(3_050_000_000)),
    "CURRENT_PORTION_LTD": line(usd(640_000_000)), "NET_WORTH": line(usd(6_600_000_000)),
    "CFO": line(usd(1_540_000_000))}}
st, sp = call("POST", f"/origination/api/applications/{ref}/spread",
              {"presentationCurrency": "INR", "periods": [fy24, fy23]}, actor="analyst.ravi")
sp = ok(st, sp, "spread")
print(f"  presentation ccy : {sp['presentationCurrency']}   currencyConsistent={sp['currencyConsistent']}")
for p in sp["periods"]:
    src = f" · {p['fxRateSource']}" if p.get("fxRateSource") else ""
    fxr = f" @ {p['fxToPresentation']}" if p.get("fxToPresentation") and p["currency"] != "INR" else ""
    print(f"  period {p['label']:7s} native={p['currency']}{fxr}{src}")
    r = p["ratios"]
    print(f"     engine ->  ratios: NET_LEV {r.get('NET_LEVERAGE')}x · INT_COV {r.get('INTEREST_COVERAGE')}x "
          f"· DSCR {r.get('DSCR')}x · CURRENT {r.get('CURRENT_RATIO')}x · EBITDA_MARGIN {pct(r.get('EBITDA_MARGIN'))}")
print(f"  engine ->  trends (computed in {sp['presentationCurrency']}): "
      f"REV {pct(sp['trends'].get('REVENUE_GROWTH'),1)} · "
      f"EBITDA {pct(sp['trends'].get('EBITDA_GROWTH'),1)} · "
      f"DEBT {pct(sp['trends'].get('DEBT_GROWTH'),1)}")
if sp.get("benchmarkFlags"):
    print(f"  engine ->  flags: {' · '.join(sp['benchmarkFlags'])}")

st, _ = call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.ravi")
ok(st, _, "confirm spread")
print("  human ->   spread CONFIRMED by analyst.ravi (gates rating)")


# ------------------------------------------------------------------ 4. RATING
step(4, "Scorecard rating  (deterministic · PD/LGD/grade)")
st, rt = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.ravi")
rt = ok(st, rt, "rate")
print(f"  engine ->  model score    : {rt.get('modelScore')}")
print(f"  engine ->  model grade    : {rt.get('modelGrade')}   final grade: {rt.get('finalGrade')}")
print(f"  engine ->  PD             : {pct(rt.get('pd'),4)}")
print(f"  engine ->  LGD            : {pct(rt.get('lgd'),2)}")
print(f"  engine ->  EAD            : {inr(rt.get('ead'))}")
st, _ = call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
ok(st, _, "confirm rating")
print("  human ->   rating CONFIRMED by credit.officer")


# ------------------------------------------------------------------ 5. CAPITAL
step(5, "Regulatory capital projection  (deterministic · RWA)")
st, cap = call("POST", f"/risk/api/risk/{ref}/capital", actor="risk.officer")
cap = ok(st, cap, "capital")
print(f"  engine ->  exposure class : {cap.get('exposureClass')}")
print(f"  engine ->  RWA            : {inr(cap.get('rwa'))}")
print(f"  engine ->  capital req'd  : {inr(cap.get('capitalRequired'))}")


# ------------------------------------------------------------------ 6. PRICING
step(6, "Risk-adjusted pricing  (deterministic · RAROC)")
st, pr = call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.anita")
pr = ok(st, pr, "pricing")
print(f"  engine ->  recommended rate : {pct(pr.get('recommendedRate'),3)}")
print(f"  engine ->  RAROC            : {pct(pr.get('raroc'),2)}  (hurdle {pct(pr.get('hurdleRaroc'),2)})")
print(f"  engine ->  below hurdle?    : {pr.get('belowHurdle')}")


# ------------------------------------------------------------------ 7. DECISION (DoA)
step(7, "Decision · DoA routing + named-human approval")
st, route = call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops")
route = ok(st, route, "route")
required = route.get("requiredAuthority")
print(f"  engine ->  routed authority : {required}  (amount × grade per the DoA matrix)")
# Pick a named human who actually holds the routed authority (from the ACTOR_ROLE master).
decider = {"BOARD_COMMITTEE": "cro", "CREDIT_COMMITTEE": "credit.committee",
           "CREDIT_OFFICER": "credit.officer", "RM_HEAD": "rm.head"}.get(required, "cro")
st, dec = call("POST", f"/decision/api/decisions/{ref}/decide", {
    "outcome": "APPROVE", "role": required,
    "rationale": "Investment-grade forger; coverage strong; secured 1.25x.",
    "conditions": ["Maintain DSCR >= 1.5x", "First charge over the new forging line"],
}, actor=decider)
dec = ok(st, dec, "decide")
print(f"  human ->   decision: {dec.get('outcome')} ({dec.get('status')}) by {decider} [{required}]")


# ------------------------------------------------------------------ 8. BOOK + ECL
step(8, "Book exposure · ECL / IRAC  (deterministic)")
call("PATCH", f"/origination/api/applications/{ref}/status", {"status": "APPROVED"}, actor="credit.ops")
st, exp = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": 0}, actor="credit.ops")
exp = ok(st, exp, "book")
print(f"  engine ->  exposure booked : EAD {inr(exp.get('ead'))} · grade {exp.get('finalGrade')} · status {exp.get('status')}")
st, ecl = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ecl", actor="credit.ops")
ecl = ok(st, ecl, "ecl")
print(f"  engine ->  IFRS9 stage     : {ecl.get('stage')}")
print(f"  engine ->  ECL             : {inr(ecl.get('ecl'))}")
print(f"  engine ->  reported prov.  : {inr(ecl.get('reportedProvision'))}  ({ecl.get('reportedProvisionPolicy')})")


# ------------------------------------------------------------------ 9. LIMITS
step(9, "Limit tree  (system-currency · INR base)")
st, tree = call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")
if st == 409:   # already built — read the view instead
    st, tree = call("GET", f"/limits/api/limits/view?cif={cref}")
tree = ok(st, tree, "limit tree")
print(f"  engine ->  base currency    : {tree.get('baseCurrency')}")
print(f"  engine ->  sanctioned (base): {inr(tree.get('totalSanctionedBase'))}")
print(f"  engine ->  outstanding(base): {inr(tree.get('totalOutstandingBase'))}")
print(f"  engine ->  available (base) : {inr(tree.get('totalAvailableBase'))}")
print(f"  engine ->  limit nodes      : {len(tree.get('nodes') or [])}")


# ------------------------------------------------------------------ 10. WORKFLOW
step(10, "Workflow lifecycle  (from seeded WORKFLOW_DEFINITION pack)")
st, view = call("GET", f"/workflow/api/workflow/instances/{ref}")
if st == 404:
    call("POST", "/workflow/api/workflow/instances",
         {"applicationReference": ref, "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE"}, actor="rm.anita")
    st, view = call("GET", f"/workflow/api/workflow/instances/{ref}")
view = ok(st, view, "workflow view")
inst = view["instance"]
print(f"  engine ->  definition      : {inst.get('definitionCode')} v{inst.get('definitionVersion')}")
print(f"  engine ->  current stage   : {inst.get('currentStageKey')}  ({inst.get('status')})")
done = [s for s in view["stages"] if s["status"] in ("COMPLETE", "SKIPPED")]
print(f"  engine ->  stages          : {len(view['stages'])} total, {len(done)} closed, "
      f"{sum(1 for s in view['stages'] if s['humanGate'])} human-gated")


# ------------------------------------------------------------------ 11. AD-HOC REPORT
step(11, "Ad-hoc report · this obligor on the book")
st, rep = call("POST", "/portfolio/api/reports/run", {
    "title": "Bharat Forgings — booked exposure",
    "dataset": "EXPOSURE_BOOK",
    "dimensions": ["counterpartyName", "finalGrade", "segment"],
    "measures": [{"field": "ead", "agg": "SUM", "as": "ead"},
                 {"field": "rwa", "agg": "SUM", "as": "rwa"},
                 {"field": "capitalRequired", "agg": "SUM", "as": "capital"}],
    "filters": [{"field": "counterpartyName", "op": "EQ", "value": "Bharat Forgings & Castings Ltd"}],
}, actor="credit.officer")
rep = ok(st, rep, "report")
if rep["rows"]:
    cols = [c["key"] for c in rep["columns"]]
    for row in rep["rows"]:
        print("  engine ->  " + " · ".join(f"{c}={row[i]:,.0f}" if isinstance(row[i], (int, float)) else f"{c}={row[i]}"
                                            for i, c in enumerate(cols)))
else:
    print("  (no rows — exposure not yet visible to the report dataset)")


# ------------------------------------------------------------------ 12. AUDIT
step(12, "Audit trail for this obligor  (who/what/actorType)")
for svc in ("counterparty", "risk", "decision", "portfolio"):
    st, evs = call("GET", f"/{svc}/api/audit")
    if not isinstance(evs, list):
        continue
    mine = [e for e in evs if ref in (e.get("subjectId") or "") or cref in (e.get("subjectId") or "")
            or (e.get("summary") and (ref in e["summary"] or "Bharat Forgings" in e["summary"]))]
    by_type = {}
    for e in mine:
        by_type[e.get("actorType")] = by_type.get(e.get("actorType"), 0) + 1
    if mine:
        sample = mine[0]
        print(f"  {svc:12s}: {len(mine)} events  actorMix={by_type}  e.g. {sample.get('eventType')} by {sample.get('actor')}")

print(f"\n{'='*72}\nDONE — {cp['legalName']} ({ref}) walked the full lifecycle.\n{'='*72}")
