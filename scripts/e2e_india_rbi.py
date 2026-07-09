#!/usr/bin/env python3
"""
India (RBI) regulatory pack — e2e (P1 track 3).

The RBI supervisory overlay on the deterministic provisioning path, all pack-driven
(IN-RBI PROVISIONING) and IN-RBI-only, so the CBUAE / IFRS-9 flat path is unchanged:

  1. SMA sub-classification of standard accounts by DPD (SMA-0/1/2 -> NPA) + a CRILC
     large-credit export feed (canonical outbound contract, idempotent per as-of day).
  2. Working-capital drawing-power monitoring (advisory, deterministic; ledger untouched).
  3. Doubtful-asset age-bands (D1/D2/D3) with a secured/unsecured provisioning split.
  4. Restructure classification consequence — a RESTRUCTURED account is held at (at least)
     SUB_STANDARD / STAGE_2 for a hold period.

Pack values are read at runtime (like the smoke IRAC-cutpoint checks) so the suite tracks
config rather than hard-coded absolutes. Requires a freshly reseeded stack (the RBI pack
keys are seed-time additions).
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
    return {"value": v, "sourceDocument": "rbi.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def deal(suffix, amount, collateral, secured, jurisdiction="IN-RBI"):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"RBI {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"RBI{suffix}",
        "jurisdiction": jurisdiction, "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": jurisdiction, "segment": "MID_CORPORATE", "facilityType": "WORKING_CAPITAL",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 36, "purpose": "WC",
        "collateralType": "PROPERTY", "collateralValue": collateral, "secured": secured}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
        per("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
    st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
    facs = must(st, facs, "facilities")
    return ref, facs[0]["reference"]


def ecl_at(ref, dpd):
    call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": dpd}, actor="credit.ops")
    st, ecl = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ecl", actor="credit.ops")
    return must(st, ecl, f"ecl@{dpd}")


print("== 0. IN-RBI PROVISIONING pack carries the RBI overlay keys ==")
st, pk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=PROVISIONING")
pp = must(st, pk, "provisioning pack")["payload"]
substandard_dpd = int(pp["irac_dpd_substandard"])
doubtful_dpd = int(pp["irac_dpd_doubtful"])
d1_max = int(pp["irac_doubtful_d1_max_dpd"])
d2_max = int(pp["irac_doubtful_d2_max_dpd"])
age_bands = pp["irac_doubtful_age_bands"]
unsecured_rate = float(pp["irac_doubtful_unsecured_rate"])
stock_margin = float(pp["dp_stock_margin_pct"])
debtor_margin = float(pp["dp_debtor_margin_pct"])
threshold = float(pp["crilc_exposure_threshold"])
check("SMA + CRILC + doubtful age-bands + restructure + DP keys all seeded",
      int(pp.get("sma_enabled", 0)) == 1 and threshold > 0 and bool(age_bands)
      and int(pp.get("restructure_npa_hold_months", 0)) > 0 and int(pp.get("drawing_power_enabled", 0)) == 1,
      str({k: pp.get(k) for k in ("sma_enabled", "crilc_exposure_threshold", "restructure_npa_hold_months",
                                  "drawing_power_enabled")}))

# Deals: a large partially-secured borrower (SMA/CRILC/doubtful/DP/restructure), a small
# sub-threshold borrower, and an unsecured borrower for the D3 100% case.
ref, fac = deal("BIG", 800_000_000, 400_000_000, True)
small_ref, _ = deal("SMALL", 5_000_000, 3_000_000, True)
uns_ref, _ = deal("UNSEC", 200_000_000, 0, False)


print("\n== 1. SMA sub-classification boundaries (SMA-0/1/2 -> NPA) ==")
e0 = ecl_at(ref, 0)
check("dpd 0 -> no SMA, STANDARD", e0["smaClass"] == "NONE" and e0["iracClass"] == "STANDARD",
      f"{e0.get('smaClass')} / {e0.get('iracClass')}")
check("dpd 1 -> SMA_0", ecl_at(ref, 1)["smaClass"] == "SMA_0")
check("dpd 30 -> SMA_0", ecl_at(ref, 30)["smaClass"] == "SMA_0")
check("dpd 31 -> SMA_1", ecl_at(ref, 31)["smaClass"] == "SMA_1")
check("dpd 60 -> SMA_1", ecl_at(ref, 60)["smaClass"] == "SMA_1")
check("dpd 61 -> SMA_2", ecl_at(ref, 61)["smaClass"] == "SMA_2")
e89 = ecl_at(ref, substandard_dpd - 1)
check("dpd just below NPA cut -> SMA_2, still STANDARD IRAC",
      e89["smaClass"] == "SMA_2" and e89["iracClass"] == "STANDARD", f"{e89.get('smaClass')}/{e89.get('iracClass')}")
enpa = ecl_at(ref, substandard_dpd)
check("dpd at NPA cut -> SUB_STANDARD, SMA cleared (now NPA)",
      enpa["iracClass"] == "SUB_STANDARD" and enpa["smaClass"] == "NONE",
      f"{enpa.get('iracClass')}/{enpa.get('smaClass')}")


print("\n== 2. CRILC large-credit feed (idempotent, threshold-filtered) ==")
ecl_at(small_ref, 61)          # small borrower into SMA but below threshold
ecl_at(ref, 61)                # large borrower into SMA above threshold
st, b1 = call("POST", "/portfolio/api/exports/crilc", actor="reg.reporter")
b1 = must(st, b1, "crilc feed")
check("CRILC batch generated to the CRILC destination", b1["destination"] == "CRILC" and b1["recordCount"] >= 1,
      f"{b1.get('destination')} n={b1.get('recordCount')}")
st, full = call("GET", f"/portfolio/api/exports/batches/{b1['id']}")
recs = must(st, full, "crilc batch")["envelope"]["records"]
big_line = next((r for r in recs if r["borrowerRef"] == ref or r.get("name") == "RBI BIG Ltd"), None)
check("large borrower reported with classification + SMA bucket",
      big_line is not None and big_line["smaClass"] == "SMA_2" and big_line["assetClassification"] == "STANDARD",
      str(big_line))
small_present = any(r.get("borrowerRef") == small_ref or r.get("name") == "RBI SMALL Ltd" for r in recs)
check("sub-threshold borrower excluded from CRILC", not small_present, f"small in feed={small_present}")
st, b2 = call("POST", "/portfolio/api/exports/crilc", actor="reg.reporter")
check("CRILC idempotent per as-of day (same batch id)", b2 and b2["id"] == b1["id"], f"{b1['id']} vs {b2.get('id')}")


print("\n== 3. Doubtful-asset age-bands with secured/unsecured split ==")
ed1 = ecl_at(ref, doubtful_dpd + 10)     # D1 doubtful, partially secured (collateral 400M < ead)
E = ed1["ead"]
secured_exp = min(E, 400_000_000)
unsecured_exp = max(0.0, E - secured_exp)
d1_rate = float(age_bands["D1"])
sec_prov = round(secured_exp * d1_rate, 2)
uns_prov = round(unsecured_exp * unsecured_rate, 2)
check("doubtful account banded D1", ed1["iracClass"] == "DOUBTFUL" and ed1["doubtfulAgeBand"] == "D1",
      f"{ed1.get('iracClass')}/{ed1.get('doubtfulAgeBand')}")
check("secured portion provisioned at the D1 secured rate",
      abs(ed1["securedProvision"] - sec_prov) < 1.0, f"{ed1.get('securedProvision')} vs {sec_prov}")
check("unsecured portion provisioned at 100%",
      abs(ed1["unsecuredProvision"] - uns_prov) < 1.0, f"{ed1.get('unsecuredProvision')} vs {uns_prov}")
check("total IRAC provision is the split sum",
      abs(ed1["iracProvision"] - (sec_prov + uns_prov)) < 1.0,
      f"{ed1.get('iracProvision')} vs {sec_prov + uns_prov}")
# D3 fully-unsecured account -> 100% provision.
ed3 = ecl_at(uns_ref, d2_max + 30)
check("unsecured account in D3 provisioned at 100% of EAD",
      ed3["doubtfulAgeBand"] == "D3" and abs(ed3["iracProvision"] - round(ed3["ead"] * unsecured_rate, 2)) < 1.0,
      f"band={ed3.get('doubtfulAgeBand')} prov={ed3.get('iracProvision')} ead={ed3.get('ead')}")


print("\n== 4. Working-capital drawing-power monitoring (advisory) ==")
st, exp_before = call("GET", f"/portfolio/api/portfolio/exposures/{ref}")
ead_before = must(st, exp_before, "exposure before")["ead"]
stock, debtors, creditors = 100_000_000, 50_000_000, 20_000_000
dp_exp = round(stock * (1 - stock_margin) + debtors * (1 - debtor_margin) - creditors, 2)
st, dp = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/drawing-power", {
    "facilityRef": fac, "stock": stock, "debtors": debtors, "creditors": creditors,
    "sanctionedLimit": 200_000_000, "outstanding": 120_000_000, "currency": "INR"}, actor="credit.ops")
dp = must(st, dp, "drawing power")
check("drawing power computed deterministically from the borrowing base",
      abs(dp["drawingPower"] - dp_exp) < 1.0, f"{dp.get('drawingPower')} vs {dp_exp}")
check("shortfall flagged when outstanding exceeds drawing power",
      dp["capped"] is True and abs(dp["shortfall"] - (120_000_000 - dp_exp)) < 1.0,
      f"capped={dp.get('capped')} shortfall={dp.get('shortfall')}")
check("assessment is advisory", dp["advisory"] is True, str(dp.get("advisory")))
st, exp_after = call("GET", f"/portfolio/api/portfolio/exposures/{ref}")
check("advisory invariant: exposure EAD untouched by the DP assessment",
      abs(must(st, exp_after, "exposure after")["ead"] - ead_before) < 1.0, "ead moved")


print("\n== 5. Restructure classification consequence (held at >= SUB_STANDARD) ==")
st, case = call("POST", f"/decision/api/collections/{ref}/open",
                {"facilityRef": fac, "daysPastDue": 100, "overdueAmount": 10_000_000}, actor="collections.ops")
case = must(st, case, "open collections case")
cid = case["id"]
st, am = call("POST", f"/decision/api/collections/{cid}/restructure/propose",
              {"newTenorMonths": 60, "reason": "stretch tenor to cure stress"}, actor="collections.ops")
am = must(st, am, "propose restructure")
call("POST", f"/decision/api/amendments/{am['id']}/approve", {"comment": "approved"}, actor="cro")
st, applied = call("POST", f"/decision/api/collections/{cid}/restructure/applied", {}, actor="collections.ops")
check("collections case flipped to RESTRUCTURED", must(st, applied, "applied")["status"] == "RESTRUCTURED",
      str(applied.get("status")))

# Re-book at dpd 0: absent the floor this is STANDARD/STAGE_1 — the restructure floor is the sole lift.
efloor = ecl_at(ref, 0)
check("restructured account floored to >= SUB_STANDARD at dpd 0",
      efloor["restructureFloorApplied"] is True and efloor["iracClass"] in ("SUB_STANDARD", "DOUBTFUL", "LOSS"),
      f"applied={efloor.get('restructureFloorApplied')} irac={efloor.get('iracClass')}")
check("restructured account floored to >= STAGE_2 at dpd 0 (below the dpd gate)",
      efloor["stage"] in ("STAGE_2", "STAGE_3"), str(efloor.get("stage")))
check("floor trace records the hold + restructure date",
      (efloor.get("trace") or {}).get("restructureFloorApplied") is True
      and (efloor.get("trace") or {}).get("restructuredAt"),
      str((efloor.get("trace") or {}).get("restructuredAt")))

# Non-RBI control: a CBUAE deal is never floored (pack has no restructure keys).
cae_ref, _ = deal("AE", 200_000_000, 100_000_000, True, jurisdiction="AE-CBUAE")
eae = ecl_at(cae_ref, 0)
check("CBUAE deal unaffected: no SMA, no restructure floor",
      eae["smaClass"] in (None, "NONE") and eae["restructureFloorApplied"] is False
      and eae["iracClass"] == "STANDARD",
      f"sma={eae.get('smaClass')} floor={eae.get('restructureFloorApplied')} irac={eae.get('iracClass')}")


print(f"\n== India (RBI) regulatory pack e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
