#!/usr/bin/env python3
"""E2E: financial-spread version timeline (append-only; figure path untouched).

Proves the SpreadVersion timeline in origination-service:
  - every POST /spread appends an immutable version (who / when / source / note),
  - resubmission versions keep the earlier snapshot byte-identical,
  - the live analysis equals the latest version's snapshot figures,
  - confirm stamps the LATEST version only,
  - rating still succeeds off the untouched live spread (grade parity with a
    non-versioned deal is proven by the untouched main regression).
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="test.user", expect=None):
    url = GW + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        return e.code, (json.loads(txt) if txt else None)


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def period(label, rev, cogs, opex, dep, intexp, tax, ta, ca, cash, cl, std, ltd, cpltd, nw, cfo):
    def line(v):
        return {"value": v, "sourceDocument": "Aurora_audited_financials_FY24.pdf",
                "sourcePage": "P9", "coordinates": "tbl2", "confidence": 0.96}
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex), "DEPRECIATION": line(dep),
        "INTEREST_EXPENSE": line(intexp), "TAX": line(tax), "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca),
        "CASH": line(cash), "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std),
        "LONG_TERM_DEBT": line(ltd), "CURRENT_PORTION_LTD": line(cpltd), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def revenue_of(analysis_like):
    """REVENUE value of the latest period from a SpreadAnalysis-shaped dict."""
    for c in (analysis_like or {}).get("periods", [{}])[0].get("lines", []):
        if c.get("taxonomyKey") == "REVENUE":
            return c.get("value")
    return None


print("== 1. Onboard counterparty (KYC-verified) ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Aurora Textiles Pvt Ltd", "legalForm": "PRIVATE_LTD",
    "registrationNo": "U17110MH2012PTC556677", "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
    "sector": "MANUFACTURING", "country": "IN", "listedEntity": False, "regulatedFi": False,
    "pep": False, "adverseMedia": False, "highRiskJurisdiction": False, "complexOwnership": False},
    actor="rm.user")
check("counterparty created", st == 200 and cp and "id" in cp, f"{st} {cp}")
cp_id = cp["id"]
st, hits = call("POST", f"/counterparty/api/counterparties/{cp_id}/screening/run", actor="compliance.officer")
check("screening ran", st == 200, f"{st}")
for h in (hits or []):
    call("POST", f"/counterparty/api/counterparties/screening/{h['id']}/disposition",
         {"disposition": "FALSE_POSITIVE", "note": "no secondary identifier match"}, actor="compliance.officer")
st, cp = call("POST", f"/counterparty/api/counterparties/{cp_id}/kyc/verify", actor="compliance.officer")
check("KYC verified", st == 200 and cp.get("kycStatus") == "VERIFIED", f"{st} {cp}")

print("== 2. Application ==")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp_id, "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 500_000_000, "currency": "INR", "tenorMonths": 48, "purpose": "Capacity expansion",
    "collateralType": "PROPERTY", "collateralValue": 400_000_000, "secured": True}, actor="rm.user")
check("application created", st == 200 and app and "reference" in app, f"{st} {app}")
ref = app["reference"]

print("== 3. Spread v1 (analyst.one) ==")
st, a1 = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    period("FY2024", 5e9, 3.2e9, 0.9e9, 0.2e9, 0.15e9, 0.12e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 0.2e9, 2.8e9, 0.7e9),
    period("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.18e9, 0.16e9, 0.10e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 0.2e9, 2.5e9, 0.6e9)]},
    actor="analyst.one")
check("spread v1 generated (2 periods)", st == 200 and len(a1["periods"]) == 2, f"{st}")

st, vs = call("GET", f"/origination/api/applications/{ref}/spread/versions")
check("timeline has exactly 1 version after v1", st == 200 and len(vs) == 1, f"{st} {vs}")
v1 = vs[0]
check("v1 versionNo == 1", v1["versionNo"] == 1, str(v1))
check("v1 createdBy analyst.one", v1["createdBy"] == "analyst.one", str(v1.get("createdBy")))
check("v1 source derived MANUAL (first spread)", v1["source"] == "MANUAL", str(v1.get("source")))
check("v1 unconfirmed and timestamped", v1["confirmed"] is False and v1.get("createdAt"), str(v1))
check("timeline row carries no snapshot payload", "snapshot" not in v1, str(v1.keys()))

print("== 4. Spread v2 — resubmission with changed numbers (analyst.two) ==")
st, a2 = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    period("FY2024", 6e9, 3.7e9, 1.0e9, 0.22e9, 0.15e9, 0.15e9, 6.5e9, 2.8e9, 0.7e9, 1.6e9, 0.5e9, 1.2e9, 0.2e9, 3.1e9, 0.9e9),
    period("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.18e9, 0.16e9, 0.10e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 0.2e9, 2.5e9, 0.6e9)],
    "note": "restated FY24 audited figures"}, actor="analyst.two")
check("spread v2 generated", st == 200 and len(a2["periods"]) == 2, f"{st}")

st, vs = call("GET", f"/origination/api/applications/{ref}/spread/versions")
check("timeline has exactly 2 versions after resubmission", st == 200 and len(vs) == 2, f"{st} {vs}")
check("timeline ordered v1 then v2", [v["versionNo"] for v in vs] == [1, 2], str([v["versionNo"] for v in vs]))
check("authors preserved per version",
      vs[0]["createdBy"] == "analyst.one" and vs[1]["createdBy"] == "analyst.two",
      str([v["createdBy"] for v in vs]))
check("v2 source derived RESUBMISSION", vs[1]["source"] == "RESUBMISSION", str(vs[1].get("source")))
check("v2 note recorded", vs[1].get("note") == "restated FY24 audited figures", str(vs[1].get("note")))

print("== 5. Snapshots: live analysis == v2, v1 history immutable ==")
st, d2 = call("GET", f"/origination/api/applications/{ref}/spread/versions/2")
check("v2 snapshot fetched", st == 200 and d2.get("snapshot"), f"{st}")
st, live = call("GET", f"/origination/api/applications/{ref}/analysis")
check("live analysis fetched", st == 200, f"{st}")
live_rev, snap2_rev = revenue_of(live), revenue_of(d2["snapshot"])
check("live REVENUE equals v2 snapshot REVENUE (= 6e9)",
      live_rev is not None and snap2_rev is not None
      and abs(live_rev - snap2_rev) < 1e-6 and abs(snap2_rev - 6e9) < 1,
      f"live={live_rev} snap={snap2_rev}")
st, d1 = call("GET", f"/origination/api/applications/{ref}/spread/versions/1")
check("v1 snapshot fetched", st == 200 and d1.get("snapshot"), f"{st}")
check("v1 snapshot REVENUE still 5e9 (append-only history unchanged by resubmission)",
      abs((revenue_of(d1["snapshot"]) or 0) - 5e9) < 1, str(revenue_of(d1["snapshot"])))
st, _ = call("GET", f"/origination/api/applications/{ref}/spread/versions/99")
check("missing version returns 404", st == 404, f"{st}")

print("== 6. Confirm stamps the LATEST version ==")
st, app = call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.one")
check("spread confirmed (live gate unchanged)", st == 200 and app["spreadConfirmed"], f"{st}")
st, vs = call("GET", f"/origination/api/applications/{ref}/spread/versions")
check("latest version (v2) stamped confirmed by analyst.one",
      st == 200 and vs[1]["confirmed"] is True and vs[1]["confirmedBy"] == "analyst.one"
      and vs[1].get("confirmedAt"), str(vs[1]))
check("earlier version (v1) remains unconfirmed", vs[0]["confirmed"] is False and not vs[0].get("confirmedBy"),
      str(vs[0]))

print("== 7. Rating still succeeds off the untouched live spread ==")
st, rating = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
check("rating produced (200)", st == 200, f"{st} {rating}")
check("finalGrade non-null", bool(rating and rating.get("finalGrade")), str((rating or {}).get("finalGrade")))

print(f"\nRESULT: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
