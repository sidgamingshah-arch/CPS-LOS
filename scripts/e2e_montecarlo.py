#!/usr/bin/env python3
"""
Monte-Carlo financial projections — e2e (round-3 Wave 3A).

The projection engine gains an advisory ML overlay: N stochastic runs of the SAME deterministic
per-line proforma, with each driver sampled from a Normal(mean, |mean|×volatility) distribution
whose volatility is calibrated (in the PROJECTION_TEMPLATE) from industry / peer / historical
inputs. Reports the final-year DSCR band (P10/P50/P90 + mean) and the covenant-breach probability
P(DSCR<1), plus the final-year distribution of every line.

Proves:
  1. The simulation returns a well-ordered DSCR band (P10 <= P50 <= P90) + a breach probability in
     [0,1], over the requested iteration count, with per-line distributions.
  2. Drivers carry their calibrated volatility + the three calibration sources.
  3. It is DETERMINISTIC — seeded from the deal reference, a re-run reproduces the same P50 (auditable).
  4. ADVISORY INVARIANT — the authoritative rating grade is byte-identical before vs after, and the
     run is stamped as an AI audit event (governed by the MONTE_CARLO capability).

Against the gateway on :8080; binds no port. Registered by the coordinator.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


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


def line(v):
    return {"value": v, "sourceDocument": "mc.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


# ---------------------------------------------------------------- rated deal
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Monte Carlo Metals Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "MCM1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 250_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "WC",
    "collateralType": "PROPERTY", "collateralValue": 300_000_000, "secured": True}, actor="rm.user")
ref = must(st, app, "app")["reference"]
st, _ = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(5e9), "COGS": line(3.2e9), "OPERATING_EXPENSES": line(0.9e9),
        "DEPRECIATION": line(0.2e9), "INTEREST_EXPENSE": line(0.15e9), "TAX": line(0.12e9),
        "TOTAL_ASSETS": line(6e9), "CURRENT_ASSETS": line(2.5e9), "CASH": line(0.6e9),
        "CURRENT_LIABILITIES": line(1.5e9), "SHORT_TERM_DEBT": line(0.5e9),
        "LONG_TERM_DEBT": line(1.2e9), "CURRENT_PORTION_LTD": line(0.2e9),
        "NET_WORTH": line(2.8e9), "CFO": line(0.7e9)}}]}, actor="analyst.user")
must(st, _, "spread")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")

st, before = call("GET", f"/risk/api/risk/{ref}")
grade_before = must(st, before, "risk summary")["rating"]["finalGrade"]

print("== 1. Monte-Carlo simulation returns a well-ordered DSCR band + breach probability ==")
st, mc = call("POST", f"/risk/api/risk/{ref}/projection/simulate?iterations=3000", actor="risk.analyst")
mc = must(st, mc, "simulate")
check("iterations echoed (clamped/accepted 3000)", mc.get("iterations") == 3000, str(mc.get("iterations")))
check("deterministic seed present", isinstance(mc.get("seed"), int) and mc.get("seed") != 0, str(mc.get("seed")))
check("DSCR band is well-ordered P10 <= P50 <= P90",
      mc["dscrP10"] <= mc["dscrP50"] <= mc["dscrP90"],
      f'{mc.get("dscrP10")}/{mc.get("dscrP50")}/{mc.get("dscrP90")}')
check("breach probability P(DSCR<1) in [0,1]", 0.0 <= mc["dscrBreachProbability"] <= 1.0,
      str(mc.get("dscrBreachProbability")))
check("run flagged advisory", mc.get("advisory") is True, str(mc.get("advisory")))

print("== 2. Drivers carry calibrated volatility + the three calibration sources ==")
drivers = mc.get("drivers") or []
check("drivers present with volatility", len(drivers) >= 5 and all("volatility" in d for d in drivers),
      str([d.get("key") for d in drivers]))
rg = next((d for d in drivers if d["key"] == "revenue_growth"), None)
check("revenue_growth volatility calibrated (>0)", rg is not None and rg.get("volatility", 0) > 0, str(rg))
check("driver distributions cite industry / peer / historical sources",
      rg is not None and set(rg.get("sources") or []) == {"INDUSTRY_FEED", "PEER_STATS", "HISTORICAL_TREND"},
      str(rg.get("sources") if rg else None))

print("== 3. Per-line final-year distributions are present and well-ordered ==")
lines = mc.get("finalYearLines") or []
dscr_line = next((l for l in lines if l["line"] == "DSCR"), None)
check("per-line distributions present incl. DSCR", len(lines) >= 5 and dscr_line is not None,
      str([l.get("line") for l in lines]))
check("each line band is well-ordered p10<=p50<=p90",
      all(l["p10"] <= l["p50"] <= l["p90"] for l in lines), "")

print("== 4. Deterministic (seeded) — a re-run reproduces the same P50 ==")
st, mc2 = call("POST", f"/risk/api/risk/{ref}/projection/simulate?iterations=3000", actor="risk.analyst")
mc2 = must(st, mc2, "simulate re-run")
check("re-run reproduces the same DSCR P50 (deterministic seed)", mc2["dscrP50"] == mc["dscrP50"],
      f'{mc.get("dscrP50")} vs {mc2.get("dscrP50")}')
check("re-run reproduces the same seed", mc2["seed"] == mc["seed"], "")

print("== 5. Advisory invariant + AI audit stamp ==")
st, after = call("GET", f"/risk/api/risk/{ref}")
grade_after = must(st, after, "risk summary after")["rating"]["finalGrade"]
check("authoritative grade UNCHANGED by the Monte-Carlo simulation", grade_after == grade_before,
      f"{grade_before} -> {grade_after}")
check("simulation reports the authoritative grade read-only", mc.get("authoritativeGrade") == grade_before,
      str(mc.get("authoritativeGrade")))
st, aud = call("GET", f"/risk/api/audit/subject?type=Application&id={ref}")
aud = aud or []
mcev = [e for e in aud if e.get("eventType") == "MONTE_CARLO_SIMULATED"]
check("Monte-Carlo run stamped as an AI audit event", len(mcev) >= 1 and mcev[0].get("actorType") == "AI",
      str([(e.get("eventType"), e.get("actorType")) for e in aud[:4]]))

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
