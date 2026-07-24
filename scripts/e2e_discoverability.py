#!/usr/bin/env python3
"""
Discoverability helper — syndicated-deal summary endpoint (batch4/discoverability).

The Syndication and Syndication-IM screens must offer ONLY syndicated deals (a
non-syndicated pick would just yield an empty book). This is backed by an additive,
read-only endpoint:

    GET /origination/api/syndication/deals
        -> [ { reference, borrower, currency, totalCommitment, lenderCount } ]

This suite proves the endpoint's shape and its filtering contract:

  A. A deal whose structure is SYNDICATION (with LEAD_BANK + PARTICIPANT_LENDER
     participants) appears, with a borrower, the summed lender commitment, and the
     lender count derived from the captured participants.
  B. A plain (non-syndicated) application does NOT appear — the picker only ever
     lists syndicated deals.

It only reads/writes origination + counterparty (no rule-pack mutation), and is NOT
registered in run_regression — run it standalone against a live gateway.
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


def make_app(suffix, amount):
    """cp -> app; returns (ref, borrower_name)."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Discov {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"DISC{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    app = must(st, app, "app")
    return app["reference"], cp["legalName"]


# ============================================================ A. a syndicated deal
print("\n== A. A SYNDICATION-structured deal appears in /syndication/deals ==")
sref, sborrower = make_app("SYND", 1_000_000_000)
must(*call("POST", f"/origination/api/applications/{sref}/structure",
           {"structureType": "SYNDICATION", "totalDealAmount": 1_000_000_000, "ourShareAmount": 600_000_000},
           actor="rm.user"), "set SYNDICATION structure")
must(*call("POST", f"/origination/api/applications/{sref}/structure/participants",
           {"role": "LEAD_BANK", "name": "Helix Bank", "committedAmount": 600_000_000}, actor="rm.user"),
     "add lead bank")
must(*call("POST", f"/origination/api/applications/{sref}/structure/participants",
           {"role": "PARTICIPANT_LENDER", "name": "Meridian Capital", "committedAmount": 400_000_000},
           actor="rm.user"), "add participant lender")

st, deals = call("GET", "/origination/api/syndication/deals")
deals = must(st, deals, "GET /syndication/deals")
check("endpoint returns a JSON array", isinstance(deals, list), str(type(deals)))
row = next((d for d in deals if d.get("reference") == sref), None)
check("the syndicated deal is listed", row is not None, str([d.get("reference") for d in deals]))
if row is not None:
    check("row has the expected shape (reference/borrower/currency/totalCommitment/lenderCount)",
          all(k in row for k in ("reference", "borrower", "currency", "totalCommitment", "lenderCount")),
          str(sorted(row.keys())))
    check("borrower is the counterparty name", row.get("borrower") == sborrower, str(row.get("borrower")))
    check("currency echoed from the application", row.get("currency") == "INR", str(row.get("currency")))
    check("lenderCount = the two captured lenders", row.get("lenderCount") == 2, str(row.get("lenderCount")))
    check("totalCommitment = sum of lender commitments (1,000,000,000)",
          abs(float(row.get("totalCommitment", 0)) - 1_000_000_000) < 1.0, str(row.get("totalCommitment")))

# ============================================================ B. non-syndicated excluded
print("\n== B. A non-syndicated application does NOT appear ==")
pref, _ = make_app("PLAIN", 50_000_000)   # left as SINGLE / no structure
must(*call("POST", f"/origination/api/applications/{pref}/structure",
           {"structureType": "SINGLE"}, actor="rm.user"), "set SINGLE structure")
st, deals2 = call("GET", "/origination/api/syndication/deals")
deals2 = must(st, deals2, "GET /syndication/deals (2)")
check("the non-syndicated deal is excluded from the picker feed",
      all(d.get("reference") != pref for d in deals2), str([d.get("reference") for d in deals2]))

print(f"\n== Discoverability e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
