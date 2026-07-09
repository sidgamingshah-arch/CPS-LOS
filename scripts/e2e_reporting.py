#!/usr/bin/env python3
"""
Ad-hoc reporting e2e — proves the deterministic aggregation executor returns
identical figures to the certified fixed MIS view, and that the whitelist /
RBAC / SoD guards behave correctly.

Headline assertion: an ad-hoc { SUM(ead) by segment } over EXPOSURE_BOOK must
equal /api/mis/composition.bySegment byte-for-byte — both read the same
ExposureRecord rows; the engine adds nothing on top of the deterministic figures.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor is not None:
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


print("== 1. Dataset registry is the whitelist boundary ==")
st, ds = call("GET", "/portfolio/api/reports/datasets")
ds = must(st, ds, "datasets")
keys = [d["key"] for d in ds]
check("EXPOSURE_BOOK dataset registered",  "EXPOSURE_BOOK" in keys, str(keys))
check("RAROC_TRACKING dataset registered", "RAROC_TRACKING" in keys, str(keys))
check("EWS_SIGNALS dataset registered",    "EWS_SIGNALS" in keys, str(keys))
exposure_ds = next(d for d in ds if d["key"] == "EXPOSURE_BOOK")
field_names = [f["name"] for f in exposure_ds["fields"]]
check("ead exposed as a measure field",
      any(f["name"] == "ead" and f["measure"] for f in exposure_ds["fields"]),
      f"fields={field_names[:8]}…")
check("segment exposed as a dimension",
      any(f["name"] == "segment" and f["dimension"] for f in exposure_ds["fields"]))
check("typed numeric op catalogue published",
      "GT" in exposure_ds["numberOps"] and "BETWEEN" in exposure_ds["numberOps"])
check("aggregations catalogue published",
      set(exposure_ds["aggregations"]) >= {"SUM", "COUNT", "AVG"})


print("== 2. RBAC and SoD guards ==")
# Blank actor: 403 (named-actor required).
st, _ = call("POST", "/portfolio/api/reports/run",
             {"dataset": "EXPOSURE_BOOK", "measures": [{"field": "*", "agg": "COUNT", "as": "n"}]})
check("blank X-Actor on /run -> 403", st == 403, f"{st}")

# Actor without REPORT_RUN role: 403.
st, _ = call("POST", "/portfolio/api/reports/run",
             {"dataset": "EXPOSURE_BOOK", "measures": [{"field": "*", "agg": "COUNT", "as": "n"}]},
             actor="treasury.user")
check("actor without REPORT_RUN role -> 403", st == 403, f"{st}")

# Allowed actor passes.
st, ok = call("POST", "/portfolio/api/reports/run",
              {"dataset": "EXPOSURE_BOOK",
               "measures": [{"field": "*", "agg": "COUNT", "as": "n"}]},
              actor="credit.officer")
check("allowed actor runs an inline report", st == 200 and "rows" in ok, f"{st}")


print("== 3. Whitelist rejects unknown fields, datasets, ops ==")
st, _ = call("POST", "/portfolio/api/reports/run",
             {"dataset": "EXPOSURE_BOOK", "dimensions": ["nonexistent_column"],
              "measures": [{"field": "ead", "agg": "SUM", "as": "total"}]},
             actor="credit.officer")
check("unknown dimension -> 400", st == 400, f"{st}")

st, _ = call("POST", "/portfolio/api/reports/run",
             {"dataset": "EXPOSURE_BOOK",
              "measures": [{"field": "segment", "agg": "AVG", "as": "avg"}]},
             actor="credit.officer")
check("AVG on a STRING field -> 400", st == 400, f"{st}")

st, _ = call("POST", "/portfolio/api/reports/run",
             {"dataset": "MADE_UP_DATASET",
              "measures": [{"field": "*", "agg": "COUNT", "as": "n"}]},
             actor="credit.officer")
check("unknown dataset -> 400", st == 400, f"{st}")

st, _ = call("POST", "/portfolio/api/reports/run",
             {"dataset": "EXPOSURE_BOOK",
              "filters": [{"field": "ead", "op": "STARTS_WITH", "value": 1}],
              "measures": [{"field": "*", "agg": "COUNT", "as": "n"}]},
             actor="credit.officer")
check("unsupported operator -> 400", st == 400, f"{st}")


print("== 4. Need a book to compare against — drive a couple of bookings ==")


def drive_book(cp_name, reg, jurisdiction, segment, amount, tenor, sector="MANUFACTURING"):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": cp_name, "legalForm": "PUBLIC_LTD",
        "registrationNo": reg, "jurisdiction": jurisdiction,
        "segment": segment, "sector": sector, "country": "IN" if jurisdiction == "IN-RBI" else "AE",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"],
        "counterpartyName": cp["legalName"], "jurisdiction": jurisdiction,
        "segment": segment,
        "facilityType": "TERM_LOAN", "requestedAmount": amount,
        "currency": "INR" if jurisdiction == "IN-RBI" else "AED",
        "tenorMonths": tenor, "purpose": "Working capital",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.2,
        "secured": True}, actor="rm.user")
    app = must(st, app, "app")
    ref = app["reference"]

    def line(v):
        return {"value": v, "sourceDocument": "rep.pdf", "sourcePage": "P1",
                "coordinates": "x", "confidence": 0.95}

    def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
        return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
            "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
            "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp),
            "TAX": line(rev * 0.025), "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca),
            "CASH": line(cash), "CURRENT_LIABILITIES": line(cl),
            "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
            "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}

    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.2e9, 0.9e9, 0.15e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 2.8e9, 0.7e9),
        per("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.16e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 2.5e9, 0.6e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="risk.officer")
    call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")
    # Route the decision through DoA first — the real endpoint is /route then /decide
    call("POST", f"/decision/api/decisions/{ref}/route", actor="rm.user")
    call("POST", f"/decision/api/decisions/{ref}/decide", {
        "outcome": "APPROVED",
        "rationale": "Strong cover; standard conditions",
        "conditions": ["Maintain DSCR >= 1.25x"]}, actor="credit.officer")
    call("PATCH", f"/origination/api/applications/{ref}/status",
         {"status": "APPROVED"}, actor="credit.ops")
    call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register",
         {"daysPastDue": 0}, actor="credit.ops")
    return ref


drive_book("Reporting E2E Alpha Ltd",  "REPTA",  "IN-RBI", "MID_CORPORATE", 150_000_000, 60)
drive_book("Reporting E2E Bravo Ltd",  "REPTB",  "IN-RBI", "SME",          80_000_000, 48)
drive_book("Reporting E2E Charlie Ltd","REPTC",  "IN-RBI", "MID_CORPORATE", 220_000_000, 72)


print("== 5. The headline cross-check: ad-hoc SUM(ead) by segment == MIS view ==")
# Authoritative figures from the certified fixed view.
st, mis = call("GET", "/portfolio/api/mis/composition")
mis = must(st, mis, "mis composition")
mis_by_segment = mis.get("bySegment", {})

# Same shape from the ad-hoc engine.
st, ad = call("POST", "/portfolio/api/reports/run", {
    "dataset": "EXPOSURE_BOOK",
    "dimensions": ["segment"],
    "measures": [{"field": "ead", "agg": "SUM", "as": "ead"}]},
    actor="credit.officer")
ad = must(st, ad, "ad-hoc by segment")
ad_by_segment = {row[0]: row[1] for row in ad["rows"]}

check("ad-hoc and MIS produced segments for the same set of keys",
      set(mis_by_segment.keys()) == set(ad_by_segment.keys()),
      f"MIS={set(mis_by_segment.keys())} adhoc={set(ad_by_segment.keys())}")
for seg, mis_v in mis_by_segment.items():
    ad_v = ad_by_segment.get(seg, -1)
    check(f"  segment {seg} totals match  (MIS={mis_v:,.0f} · ad-hoc={ad_v:,.0f})",
          abs(mis_v - ad_v) < 0.5)


print("== 6. Filter + sort + limit ==")
st, mid = call("POST", "/portfolio/api/reports/run", {
    "dataset": "EXPOSURE_BOOK",
    "dimensions": ["counterpartyName"],
    "measures": [{"field": "ead", "agg": "SUM", "as": "ead"}],
    "filters": [{"field": "segment", "op": "EQ", "value": "MID_CORPORATE"}],
    "sort": [{"by": "ead", "dir": "DESC"}],
    "limit": 5},
    actor="credit.officer")
mid = must(st, mid, "mid corp filter")
check("filter narrowed scannedRows",
      mid["scannedRows"] <= ad["scannedRows"], f"{mid['scannedRows']} <= {ad['scannedRows']}")
check("sort+limit honoured",
      mid["returnedRows"] <= 5, f"{mid['returnedRows']}")
if len(mid["rows"]) >= 2:
    check("rows sorted DESC by ead",
          mid["rows"][0][1] >= mid["rows"][1][1], str(mid["rows"]))


print("== 7. Numeric IN / BETWEEN / GT operators ==")
st, hi = call("POST", "/portfolio/api/reports/run", {
    "dataset": "EXPOSURE_BOOK",
    "dimensions": ["finalGrade"],
    "measures": [{"field": "*", "agg": "COUNT", "as": "n"},
                 {"field": "ead", "agg": "SUM", "as": "ead"}],
    "filters": [{"field": "ead", "op": "GT", "value": 100_000_000}]},
    actor="credit.officer")
check("GT filter executes", st == 200 and "rows" in hi, f"{st}")

st, bet = call("POST", "/portfolio/api/reports/run", {
    "dataset": "EXPOSURE_BOOK",
    "dimensions": ["finalGrade"],
    "measures": [{"field": "ead", "agg": "SUM", "as": "ead"}],
    "filters": [{"field": "ead", "op": "BETWEEN", "value": [50_000_000, 200_000_000]}]},
    actor="credit.officer")
check("BETWEEN filter executes", st == 200, f"{st}")


print("== 8. Saved REPORT_DEFINITION via master engine + SoD ==")
# Author a draft via the existing master engine. recordKey doubles as the report key.
payload = {
    "title": "Sub-investment-grade EAD by segment",
    "dataset": "EXPOSURE_BOOK",
    "dimensions": ["segment"],
    "measures": [{"field": "ead", "agg": "SUM", "as": "totalEad"},
                 {"field": "*", "agg": "COUNT", "as": "deals"}],
    "filters": [{"field": "finalGrade", "op": "IN",
                 "value": ["BB", "B", "CCC", "CC", "C", "D"]}],
    "sort": [{"by": "totalEad", "dir": "DESC"}]
}
st, m = call("POST", "/config/api/masters/REPORT_DEFINITION",
             {"recordKey": "sub_ig_by_segment", "payload": payload},
             actor="master.maker")
m = must(st, m, "master submitted")
check("master created in PENDING_APPROVAL", m["status"] == "PENDING_APPROVAL", m.get("status"))

# Self-approval blocked (SoD inherited from master engine).
st, sod = call("POST", f"/config/api/masters/records/{m['id']}/approve",
               actor="master.maker")
check("self-approval blocked -> 403 (SoD)", st == 403, f"{st}")

# Different checker approves.
st, ap = call("POST", f"/config/api/masters/records/{m['id']}/approve",
              actor="master.checker")
check("different checker approves -> ACTIVE",
      st == 200 and ap["status"] == "ACTIVE", f"{st} {ap.get('status')}")

# Run the saved report by key.
st, saved = call("GET", "/portfolio/api/reports/sub_ig_by_segment/run",
                 actor="credit.officer")
check("saved report executes", st == 200 and saved.get("datasetKey") == "EXPOSURE_BOOK",
      f"{st} {saved.get('datasetKey') if isinstance(saved, dict) else saved}")
check("saved report carries the title's intent (column shape)",
      any(c.get("key") == "totalEad" for c in saved.get("columns", [])),
      str([c.get("key") for c in saved.get("columns", [])]))


print("== 9. Engine stamps a deterministic audit event ==")
st, recent = call("GET", "/portfolio/api/audit")
recent = must(st, recent, "audit list")
report_events = [e for e in recent if e.get("eventType") == "REPORT_EXECUTED"]
check("REPORT_EXECUTED audit stamped by SYSTEM",
      len(report_events) >= 3 and all(e["actorType"] == "SYSTEM" for e in report_events[:3]),
      f"count={len(report_events)} actorTypes={set(e['actorType'] for e in report_events[:3])}")


print("== 10. The advisory invariant: reports never mutate authoritative figures ==")
# Compute the MIS total again and the ad-hoc total again — must be unchanged.
st, mis2 = call("GET", "/portfolio/api/mis/composition")
check("MIS totalEad unchanged after several ad-hoc runs",
      abs((mis2["totalEad"] - mis["totalEad"])) < 0.5,
      f"{mis['totalEad']} -> {mis2['totalEad']}")


print(f"\n== ad-hoc reporting e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
