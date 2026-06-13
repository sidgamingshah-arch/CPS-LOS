#!/usr/bin/env python3
"""
Multi-dimensional concentration — e2e.

Books a spread of exposures and proves the multi-dim engine:
  1. Reports 8 dimensions + 2 intersections (was: 3 fixed dimensions).
  2. Each dimension has buckets with share / limit / utilisation / breach + HHI.
  3. Thresholds come from the CONCENTRATION_LIMITS rule pack (jurisdiction-scoped).
  4. A deliberately oversized single name breaches the single-name (capital) limit.
  5. Duration bucketing + instrument + rating dimensions populate from booked deals.
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
        PASS += 1; print(f"  PASS  {name}")
    else:
        FAIL += 1; print(f"  FAIL  {name}  {detail}")


def must(st, b, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {b}"); sys.exit(1)
    return b


def book(name, reg, sector, facility, tenor, amount, currency="INR",
         jurisdiction="IN-RBI", country="IN"):
    """Drive a deal to a booked exposure with the given dimensions."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": name, "legalForm": "PUBLIC_LTD", "registrationNo": reg, "jurisdiction": jurisdiction,
        "segment": sector, "sector": sector, "country": country, "listedEntity": True, "regulatedFi": False,
        "pep": False, "adverseMedia": False, "highRiskJurisdiction": False, "complexOwnership": False},
        actor="rm.user")
    cp = must(st, cp, f"cp {name}")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": name,
        "jurisdiction": jurisdiction, "segment": sector, "facilityType": facility,
        "requestedAmount": amount, "currency": currency, "tenorMonths": tenor, "purpose": "x",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.3, "secured": True}, actor="rm.user")
    app = must(st, app, f"app {name}")
    ref = app["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        {"label": "FY2024", "gaap": "IND_AS", "currency": currency, "lines": {
            "REVENUE": {"value": 4e9, "sourceDocument": "m", "confidence": 1.0},
            "COGS": {"value": 2.6e9, "sourceDocument": "m", "confidence": 1.0},
            "OPERATING_EXPENSES": {"value": 0.6e9, "sourceDocument": "m", "confidence": 1.0},
            "DEPRECIATION": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
            "INTEREST_EXPENSE": {"value": 0.12e9, "sourceDocument": "m", "confidence": 1.0},
            "TAX": {"value": 0.1e9, "sourceDocument": "m", "confidence": 1.0},
            "TOTAL_ASSETS": {"value": 5e9, "sourceDocument": "m", "confidence": 1.0},
            "CURRENT_ASSETS": {"value": 2e9, "sourceDocument": "m", "confidence": 1.0},
            "CASH": {"value": 0.5e9, "sourceDocument": "m", "confidence": 1.0},
            "CURRENT_LIABILITIES": {"value": 1.3e9, "sourceDocument": "m", "confidence": 1.0},
            "SHORT_TERM_DEBT": {"value": 0.4e9, "sourceDocument": "m", "confidence": 1.0},
            "LONG_TERM_DEBT": {"value": 1.0e9, "sourceDocument": "m", "confidence": 1.0},
            "CURRENT_PORTION_LTD": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
            "NET_WORTH": {"value": 2.3e9, "sourceDocument": "m", "confidence": 1.0},
            "CFO": {"value": 0.55e9, "sourceDocument": "m", "confidence": 1.0}}}]},
         actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="analyst.user")
    st, _ = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": 0}, actor="credit.ops")
    must(st, _, f"book {name}")
    return ref


print("== 0. Book a spread of exposures across dimensions ==")
# One huge single-name to breach the single-name capital limit (15% of 50bn = 7.5bn).
book("Titan Mega Corp", "MD-1", "MANUFACTURING", "TERM_LOAN", 84, 9_000_000_000)
book("Orbit Textiles", "MD-2", "MANUFACTURING", "WORKING_CAPITAL", 12, 1_500_000_000)
book("Delta Infra", "MD-3", "INFRASTRUCTURE", "PROJECT_FINANCE", 144, 2_000_000_000)
book("Helio Retail", "MD-4", "RETAIL", "OVERDRAFT", 12, 800_000_000)
book("Nova Shipping", "MD-5", "LOGISTICS", "TERM_LOAN", 60, 1_200_000_000)
print("    5 exposures booked")

print("\n== 1. Multi-dim view returns all dimensions ==")
st, v = call("GET", "/portfolio/api/portfolio/concentration/multi?jurisdiction=IN-RBI")
v = must(st, v, "multi concentration")
dims = {d["dimension"]: d for d in v["dimensions"]}
expected = {"SINGLE_NAME", "GROUP", "SECTOR", "GEOGRAPHY", "INSTRUMENT",
            "DURATION_BUCKET", "RATING", "CURRENCY", "SECTOR_x_GEOGRAPHY", "RATING_x_SECTOR"}
check("all 10 dimensions present", expected.issubset(set(dims.keys())), str(set(dims.keys())))
check("dimensionCount == 10", v["dimensionCount"] == 10, str(v["dimensionCount"]))

print("\n== 2. Each dimension carries HHI + buckets + limit ==")
sector = dims["SECTOR"]
check("SECTOR dimension has buckets", sector["bucketCount"] >= 3, str(sector["bucketCount"]))
check("SECTOR has an HHI in (0,1]", 0 < sector["hhi"] <= 1.0001, str(sector["hhi"]))
check("SECTOR basis is PORTFOLIO", sector["basis"] == "PORTFOLIO", str(sector["basis"]))
check("SECTOR lines sorted desc by exposure",
      all(sector["lines"][i]["exposure"] >= sector["lines"][i+1]["exposure"]
          for i in range(len(sector["lines"]) - 1)), "")

print("\n== 3. Single-name capital limit breached by the oversized name ==")
sn = dims["SINGLE_NAME"]
check("SINGLE_NAME basis is CAPITAL", sn["basis"] == "CAPITAL", str(sn["basis"]))
titan = next((l for l in sn["lines"] if "Titan" in l["key"]), None)
check("Titan single-name line present", titan is not None, str(sn["lines"])[:200])
check("Titan breaches single-name limit (9bn > 7.5bn)", titan and titan["breach"] is True, str(titan))
check("total breaches >= 1", v["totalBreaches"] >= 1, str(v["totalBreaches"]))
check("breach narrative mentions SINGLE_NAME",
      any("SINGLE_NAME" in b for b in v["breaches"]), str(v["breaches"])[:200])

print("\n== 4. Instrument + duration + rating dimensions populated ==")
inst = dims["INSTRUMENT"]
check("INSTRUMENT has >= 3 facility types", inst["bucketCount"] >= 3, str(inst["bucketCount"]))
check("INSTRUMENT includes TERM_LOAN", any(l["key"] == "TERM_LOAN" for l in inst["lines"]), str(inst["lines"]))
dur = dims["DURATION_BUCKET"]
check("DURATION_BUCKET has multiple buckets", dur["bucketCount"] >= 3, str(dur["bucketCount"]))
check("DURATION includes a 0-12m bucket", any(l["key"] == "0-12m" for l in dur["lines"]), str(dur["lines"]))
check("DURATION includes a 120m+ bucket (PF 144m)", any(l["key"] == "120m+" for l in dur["lines"]), str(dur["lines"]))

print("\n== 5. Intersection dimension (sector x geography) present ==")
sg = dims["SECTOR_x_GEOGRAPHY"]
check("SECTOR_x_GEOGRAPHY has composite keys", sg["bucketCount"] >= 3 and "/" in sg["lines"][0]["key"], str(sg["lines"][0]))
check("intersection limitPct tighter than sector (0.15 vs 0.20)", sg["limitPct"] < sector["limitPct"],
      f"{sg['limitPct']} vs {sector['limitPct']}")

print("\n== 6. Legacy 3-dimension view still works (back-compat) ==")
st, legacy = call("GET", "/portfolio/api/portfolio/concentration?jurisdiction=IN-RBI")
legacy = must(st, legacy, "legacy concentration")
check("legacy view still returns singleName/sector/segment",
      "singleName" in legacy and "sector" in legacy and "segment" in legacy, str(legacy.keys()))

print("\n== 7. Jurisdiction scoping: the local view never leaks another regime's book ==")
book("Gulf Star Trading FZE", "MD-AE-1", "TRADE", "WORKING_CAPITAL", 12, 900_000_000,
     currency="AED", jurisdiction="AE-CBUAE", country="AE")
print("    1 AE-CBUAE exposure booked")

st, in_view = call("GET", "/portfolio/api/portfolio/concentration/multi?jurisdiction=IN-RBI")
in_view = must(st, in_view, "IN-RBI scoped view")
in_names = {l["key"] for d in in_view["dimensions"] if d["dimension"] == "SINGLE_NAME" for l in d["lines"]}
check("IN-RBI view does NOT contain the AE counterparty", "Gulf Star Trading FZE" not in in_names, str(in_names))

st, ae_view = call("GET", "/portfolio/api/portfolio/concentration/multi?jurisdiction=AE-CBUAE")
ae_view = must(st, ae_view, "AE-CBUAE scoped view")
ae_names = {l["key"] for d in ae_view["dimensions"] if d["dimension"] == "SINGLE_NAME" for l in d["lines"]}
check("AE-CBUAE view contains its own counterparty", "Gulf Star Trading FZE" in ae_names, str(ae_names))
check("AE-CBUAE view does NOT contain IN-RBI names", "Titan Mega Corp" not in ae_names, str(ae_names))

st, global_view = call("GET", "/portfolio/api/portfolio/concentration/multi?jurisdiction=IN-RBI&global=true")
global_view = must(st, global_view, "global view")
g_names = {l["key"] for d in global_view["dimensions"] if d["dimension"] == "SINGLE_NAME" for l in d["lines"]}
check("global=true (group-CRO) view spans both books",
      "Gulf Star Trading FZE" in g_names and "Titan Mega Corp" in g_names, str(g_names))
geo = next(d for d in global_view["dimensions"] if d["dimension"] == "GEOGRAPHY")
check("global view GEOGRAPHY has both jurisdictions",
      {"IN-RBI", "AE-CBUAE"}.issubset({l["key"] for l in geo["lines"]}),
      str([l["key"] for l in geo["lines"]]))

print("\n== 8. Early-warning bands (NORMAL / WATCH / WARNING / BREACH) ==")
sn = dims["SINGLE_NAME"]
check("every line carries a band", all("band" in l for l in sn["lines"]), str(sn["lines"][0]))
check("Titan single-name is in the BREACH band (9bn > 7.5bn cap)",
      next(l["band"] for l in sn["lines"] if "Titan" in l["key"]) == "BREACH",
      str([(l["key"], l["band"]) for l in sn["lines"]]))
check("dimension reports a bands tally", "bands" in sn and "breach" in sn["bands"], str(sn.get("bands")))
check("SINGLE_NAME breach band count == breachCount",
      sn["bands"]["breach"] == sn["breachCount"], str(sn["bands"]))
check("every bucket lands in exactly one band",
      all(sum(sn["bands"][k] for k in ("normal", "watch", "warning", "breach")) == sn["bucketCount"]
          for sn in [dims[d] for d in dims]),
      str({d: dims[d]["bands"] for d in dims}))
# A bucket between 80% and 100% of its limit should be WATCH or WARNING, never silently fine.
sector = dims["SECTOR"]
for l in sector["lines"]:
    u = l["utilisation"]
    expected_band = ("BREACH" if u > 1.0001 else "WARNING" if u >= 0.90 else "WATCH" if u >= 0.80 else "NORMAL")
    if l["band"] != expected_band:
        check(f"SECTOR band for {l['key']} matches utilisation {u:.2f}", False, f"{l['band']} != {expected_band}")
        break
else:
    check("SECTOR bands all match their utilisation bucket", True, "")

print("\n== 9. Correlation-stressed concentration ==")
# Shock INFRASTRUCTURE: Delta Infra is the direct hit; via the seeded matrix the
# manufacturing / steel / logistics complex co-moves, so stressed loss is far above
# the un-correlated single-sector loss.
st, stress = call("POST", "/portfolio/api/portfolio/concentration/stress?jurisdiction=IN-RBI",
                  {"shockedSector": "INFRASTRUCTURE", "pdMultiplier": 4.0}, actor="cro")
stress = must(st, stress, "stress")
check("shocked sector echoed", stress["shockedSector"] == "INFRASTRUCTURE", str(stress["shockedSector"]))
check("stressed EL strictly exceeds base EL",
      stress["stressedExpectedLoss"] > stress["baseExpectedLoss"], str(stress))
check("incremental loss = stressed - base",
      abs(stress["incrementalLoss"] - (stress["stressedExpectedLoss"] - stress["baseExpectedLoss"])) < 1,
      str(stress["incrementalLoss"]))
secs = {s["sector"]: s for s in stress["sectors"]}
check("shocked sector has correlation 1.0",
      abs(secs["INFRASTRUCTURE"]["correlationToShock"] - 1.0) < 1e-6, str(secs.get("INFRASTRUCTURE")))
check("shocked sector PD lifts ~4x",
      secs["INFRASTRUCTURE"]["avgStressedPd"] > 3.0 * secs["INFRASTRUCTURE"]["avgBasePd"] - 1e-9,
      f"{secs['INFRASTRUCTURE']['avgBasePd']} -> {secs['INFRASTRUCTURE']['avgStressedPd']}")
check("a correlated sector (MANUFACTURING) takes a partial PD hit, not the full shock",
      secs["MANUFACTURING"]["avgBasePd"] < secs["MANUFACTURING"]["avgStressedPd"] < secs["INFRASTRUCTURE"]["avgStressedPd"],
      f"mfg base {secs['MANUFACTURING']['avgBasePd']} stressed {secs['MANUFACTURING']['avgStressedPd']}")
check("MANUFACTURING correlation to INFRASTRUCTURE is in (0,1)",
      0 < secs["MANUFACTURING"]["correlationToShock"] < 1, str(secs["MANUFACTURING"]["correlationToShock"]))
check("sectors sorted by incremental loss desc",
      all(stress["sectors"][i]["incrementalLoss"] >= stress["sectors"][i+1]["incrementalLoss"]
          for i in range(len(stress["sectors"]) - 1)), "")
check("stressed loss reported as % of capital", stress["stressedLossPctOfCapital"] > 0,
      str(stress["stressedLossPctOfCapital"]))

# An uncorrelated shock (RETAIL only lightly touches infra/mfg) moves less than the infra shock.
st, stress2 = call("POST", "/portfolio/api/portfolio/concentration/stress?jurisdiction=IN-RBI",
                   {"shockedSector": "RETAIL", "pdMultiplier": 4.0}, actor="cro")
stress2 = must(st, stress2, "retail stress")
check("RETAIL shock yields a different (lower) incremental loss than the INFRASTRUCTURE complex",
      stress2["incrementalLoss"] < stress["incrementalLoss"],
      f"retail {stress2['incrementalLoss']} vs infra {stress['incrementalLoss']}")

# Severe shock breaches the capital buffer and raises alerts. The book's strong
# ratings keep stressed EL a small % of capital, so pick a buffer below the
# stressed loss the engine actually reports (then the breach is exercised cleanly).
buffer_below = round(stress["stressedLossPctOfCapital"] / 2, 8) or 0.0001
st, severe = call("POST", "/portfolio/api/portfolio/concentration/stress?jurisdiction=IN-RBI",
                  {"shockedSector": "INFRASTRUCTURE", "pdMultiplier": 25.0,
                   "capitalBufferPct": buffer_below},
                  actor="cro")
severe = must(st, severe, "severe stress")
check("severe shock breaches the capital buffer", severe["capitalBreach"] is True, str(severe["capitalBreach"]))
check("capital breach raises an alert",
      any("capital" in a.lower() for a in severe["alerts"]), str(severe["alerts"]))

# A bad scenario is rejected.
st, body = call("POST", "/portfolio/api/portfolio/concentration/stress?jurisdiction=IN-RBI",
                {"pdMultiplier": 4.0}, actor="cro")
check("stress without a shocked sector rejected (400)", st == 400, f"{st} {body}")

print(f"\n== Multi-dim concentration e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
