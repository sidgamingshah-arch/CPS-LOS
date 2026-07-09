#!/usr/bin/env python3
"""
Sector provenance (D6, P2) — e2e.

portfolio-service used to book an exposure's sector as a PROXY for the segment
(`e.setSector(inputs.segment())`). It now books the REAL counterparty sector that
origination already captures (falling back to the segment only when a deal carries no
sector, preserving legacy behaviour). Proven with a deal whose sector and segment are
DISTINCT, so the fix is observable: the booked exposure — and the concentration
SECTOR dimension — reflect the counterparty's actual sector, not its segment.
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
    return {"value": v, "sourceDocument": "sp.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def period(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


# Distinct sector vs segment so the provenance fix is observable.
SECTOR = "LOGISTICS"
SEGMENT = "MID_CORPORATE"

print("== 0. Book a deal whose SECTOR and SEGMENT differ ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Sector Provenance Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "D6-1",
    "jurisdiction": "IN-RBI", "segment": SEGMENT, "sector": SECTOR, "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": SEGMENT, "facilityType": "TERM_LOAN",
    "requestedAmount": 300_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Fleet",
    "collateralType": "PROPERTY", "collateralValue": 350_000_000, "secured": True}, actor="rm.user")
ref = must(st, app, "app")["reference"]

print("\n== 1. Origination already captures the real counterparty sector ==")
st, ci = call("GET", f"/origination/api/applications/{ref}/credit-inputs")
ci = must(st, ci, "credit-inputs")
check("origination credit-inputs carries the counterparty sector",
      ci.get("sector") == SECTOR, f"{ci.get('sector')}")
check("segment is distinct from sector on this deal",
      ci.get("segment") == SEGMENT and ci.get("sector") != ci.get("segment"),
      f"segment={ci.get('segment')} sector={ci.get('sector')}")

call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    period("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
    period("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
]}, actor="analyst.user")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")

print("\n== 2. The booked exposure records the REAL sector, not the segment proxy ==")
st, exp = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": 0}, actor="credit.ops")
exp = must(st, exp, "register")
check("exposure sector = counterparty sector (LOGISTICS), not the segment", exp["sector"] == SECTOR,
      f"sector={exp.get('sector')} (segment={exp.get('segment')})")
check("exposure sector is NOT the segment proxy", exp["sector"] != SEGMENT, str(exp.get("sector")))
check("exposure segment unchanged", exp["segment"] == SEGMENT, str(exp.get("segment")))

print("\n== 3. The real sector flows into the concentration SECTOR dimension ==")
st, conc = call("GET", "/portfolio/api/portfolio/concentration/multi")
conc = must(st, conc, "concentration")
dims = {d["dimension"]: d for d in conc["dimensions"]}
buckets = [str(l.get("key")) for l in dims["SECTOR"]["lines"]]
check("concentration SECTOR dimension now buckets by the real sector (LOGISTICS present)",
      SECTOR in buckets, f"buckets={buckets}")
check("no SEGMENT value leaks into the SECTOR dimension (proxy retired)",
      SEGMENT not in buckets, f"buckets={buckets}")

print(f"\n== sector provenance (D6) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
