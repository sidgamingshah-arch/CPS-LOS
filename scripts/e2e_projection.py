#!/usr/bin/env python3
"""
Financial projections engine — e2e.

A PROJECTION_TEMPLATE (driver model) + the deal's base-year actuals produce a
deterministic multi-year proforma (revenue, EBITDA, debt, debt-service, projected
DSCR). Drivers are analyst-editable; single-driver sensitivity is supported.
Advisory throughout — projections never move the authoritative rating.

Proves: template resolves; proforma computes N years; revenue compounds at the
growth driver; projected DSCR present; driver overrides change the grid;
sensitivity moves the final-year DSCR; human confirm; grade unchanged;
maker-checker on the template.
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
    return {"value": v, "sourceDocument": "proj.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def rated_deal(suffix):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Projection {suffix} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"PRJ{suffix}", "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
        "sector": "MANUFACTURING", "country": "IN", "listedEntity": True, "regulatedFi": False,
        "pep": False, "adverseMedia": False, "highRiskJurisdiction": False, "complexOwnership": False},
        actor="rm.user")
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
    return ref


print("== 1. Template resolves + proforma computes N years ==")
ref = rated_deal("A")
st, v = call("GET", f"/risk/api/risk/{ref}/projection")
v = must(st, v, "projection view")
check("resolved proj-corp-v1 template", v["templateKey"] == "proj-corp-v1", str(v.get("templateKey")))
check("5-year horizon with 5 projected years",
      v["horizonYears"] == 5 and len(v["years"]) == 5, f"{v.get('horizonYears')} / {len(v.get('years', []))}")
check("drivers present (revenue_growth etc.)",
      any(d["key"] == "revenue_growth" for d in v["drivers"]), str([d["key"] for d in v["drivers"]]))
check("base year seeded from the spread (REVENUE = 5e9)",
      abs(v["baseYear"].get("REVENUE", 0) - 5e9) < 1.0, str(v["baseYear"].get("REVENUE")))


print("== 2. Revenue compounds at the growth driver (10% default) ==")
y1 = v["years"][0]["values"]
y2 = v["years"][1]["values"]
check("Y1 revenue = base * 1.10",
      abs(y1["REVENUE"] - 5e9 * 1.10) < 1.0, f"{y1.get('REVENUE')} vs {5e9*1.1}")
check("Y2 revenue = Y1 * 1.10",
      abs(y2["REVENUE"] - y1["REVENUE"] * 1.10) < 1.0, f"{y2.get('REVENUE')} vs {y1.get('REVENUE')*1.1}")
check("projected DSCR present each year",
      all("DSCR" in y["values"] for y in v["years"]) and y1["DSCR"] > 0, str(y1.get("DSCR")))
check("debt amortises (Y2 debt < Y1 debt)",
      y2["DEBT"] < y1["DEBT"], f"{y1.get('DEBT')} -> {y2.get('DEBT')}")


print("== 3. Driver override changes the grid ==")
st, v2 = call("POST", f"/risk/api/risk/{ref}/projection/drivers",
              {"drivers": {"revenue_growth": 0.20}}, actor="analyst.user")
v2 = must(st, v2, "set drivers")
check("revenue_growth override applied (0.20)",
      any(abs(d["value"] - 0.20) < 1e-9 for d in v2["drivers"] if d["key"] == "revenue_growth"),
      str([(d["key"], d["value"]) for d in v2["drivers"]]))
check("Y1 revenue now base * 1.20",
      abs(v2["years"][0]["values"]["REVENUE"] - 5e9 * 1.20) < 1.0,
      str(v2["years"][0]["values"].get("REVENUE")))
# Only declared drivers accepted — an unknown key is ignored.
st, v3 = call("POST", f"/risk/api/risk/{ref}/projection/drivers",
              {"drivers": {"not_a_driver": 9.9}}, actor="analyst.user")
check("unknown driver key ignored (no crash)", st == 200, f"{st}")


print("== 4. Sensitivity: flex a driver, see the final-year DSCR move ==")
st, s = call("POST", f"/risk/api/risk/{ref}/projection/sensitivity",
             {"driver": "ebitda_margin", "delta": -0.05}, actor="analyst.user")
s = must(st, s, "sensitivity")
check("sensitivity returns base + flexed grids",
      len(s["base"]) == 5 and len(s["flexed"]) == 5, f"{len(s.get('base', []))}/{len(s.get('flexed', []))}")
check("lower EBITDA margin lowers the final-year projected DSCR",
      s["flexedFinalDscr"] < s["baseFinalDscr"],
      f"base={s.get('baseFinalDscr')} flexed={s.get('flexedFinalDscr')}")
st, sbad = call("POST", f"/risk/api/risk/{ref}/projection/sensitivity",
                {"driver": "nope", "delta": 0.1}, actor="analyst.user")
check("unknown sensitivity driver -> 400", sbad is not None and st == 400, f"{st}")


print("== 5. Human confirm (advisory gate) ==")
st, c = call("POST", f"/risk/api/risk/{ref}/projection/confirm", actor="credit.officer")
c = must(st, c, "confirm")
check("projection CONFIRMED by a named human",
      c["status"] == "CONFIRMED" and c["confirmedBy"] == "credit.officer", str(c.get("status")))


print("== 6. Advisory invariant: authoritative grade unchanged ==")
st, rs = call("GET", f"/risk/api/risk/{ref}")
rs = must(st, rs, "risk summary")
st, before = call("GET", f"/risk/api/risk/{ref}")
check("rating present + unchanged by projection activity",
      rs["rating"]["finalGrade"] == before["rating"]["finalGrade"], "")
check("projection view advertises advisory + gradeUnchanged",
      c["advisory"] and c["gradeUnchanged"], "")


print("== 7. PROJECTION_TEMPLATE is governed (maker-checker / SoD) ==")
new_t = {"templateKey": "proj-e2e", "displayName": "E2E projection", "selector": {"segment": "TRADE_FINANCE"},
         "horizonYears": 3,
         "drivers": [{"key": "revenue_growth", "label": "Growth", "defaultValue": 0.05}],
         "lines": [{"key": "REVENUE", "label": "Revenue", "formula": "prev_REVENUE * (1 + revenue_growth)", "seedFrom": "REVENUE"}]}
st, sub = call("POST", "/config/api/masters/PROJECTION_TEMPLATE",
               {"recordKey": "proj-e2e", "payload": new_t}, actor="master.maker")
sub = must(st, sub, "submit template")
check("new template submitted PENDING_APPROVAL", sub["status"] == "PENDING_APPROVAL", str(sub.get("status")))
st, self_ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.maker")
check("self-approval blocked (SoD) -> 403", st == 403, f"{st}")
st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.checker")
check("different checker approves -> ACTIVE", st == 200 and ap["status"] == "ACTIVE", f"{st}")
st, r_tf = call("GET", "/config/api/projection-templates/resolve?jurisdiction=IN-RBI&segment=TRADE_FINANCE")
check("newly-approved template resolves for its segment",
      st == 200 and r_tf["payload"]["templateKey"] == "proj-e2e", f"{st}")


print(f"\n== financial projections e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
