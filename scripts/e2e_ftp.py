#!/usr/bin/env python3
"""
FTP (funds-transfer-pricing) master — e2e.

Proves the pricing path now derives cost-of-funds from a term-structured,
behaviourally-adjusted FTP curve (FTP_CURVE master), not a flat number:
  1. Price a long-tenor TERM_LOAN -> FTP from curve, behavioural life < contractual.
  2. Price a short revolving WORKING_CAPITAL -> different behavioural maturity.
  3. The two get materially different funding rates off the same currency curve.
  4. The pricing breakdown carries a full FTP derivation (source=FTP_CURVE).
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


def price_deal(facility_type, tenor_months, currency="INR"):
    """Drive a fresh deal of the given facility type through to a PricingResult."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"FTP Test {facility_type} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"FTP{facility_type}{tenor_months}", "jurisdiction": "IN-RBI",
        "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": facility_type,
        "requestedAmount": 500_000_000, "currency": currency, "tenorMonths": tenor_months,
        "purpose": "test", "collateralType": "PROPERTY", "collateralValue": 600_000_000,
        "secured": True}, actor="rm.user")
    app = must(st, app, "app")
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
    st, pricing = call("POST", f"/risk/api/risk/{ref}/pricing", actor="analyst.user")
    return must(st, pricing, "price")


print("== 1. Long-tenor TERM_LOAN (84m, amortising → WAL ≈ 0.6 × 84 = ~50m) ==")
tl = price_deal("TERM_LOAN", 84)
tl_ftp = tl["breakdown"]["ftp"]
check("pricing breakdown carries FTP block", isinstance(tl_ftp, dict), str(tl_ftp)[:120])
check("TERM_LOAN FTP sourced from curve", tl_ftp.get("source") == "FTP_CURVE", str(tl_ftp.get("source")))
check("TERM_LOAN behaviour is AMORTISING", tl_ftp.get("behaviourType") == "AMORTISING", str(tl_ftp.get("behaviourType")))
check("TERM_LOAN behavioural life < contractual",
      tl_ftp.get("behaviouralMonths", 999) < tl_ftp.get("contractualMonths", 0),
      f"beh={tl_ftp.get('behaviouralMonths')} contract={tl_ftp.get('contractualMonths')}")
print(f"    TL: contractual {tl_ftp['contractualMonths']}m → behavioural {tl_ftp['behaviouralMonths']}m, "
      f"base {tl_ftp['baseCurveRate']:.4f} + LP {tl_ftp['liquidityPremium']:.4f} = FTP {tl_ftp['ftp']:.4f}")

print("\n== 2. Short revolving WORKING_CAPITAL (12m contractual, behavioural 12m) ==")
wc = price_deal("WORKING_CAPITAL", 12)
wc_ftp = wc["breakdown"]["ftp"]
check("WC FTP sourced from curve", wc_ftp.get("source") == "FTP_CURVE", str(wc_ftp.get("source")))
check("WC behaviour is REVOLVING", wc_ftp.get("behaviourType") == "REVOLVING", str(wc_ftp.get("behaviourType")))
print(f"    WC: contractual {wc_ftp['contractualMonths']}m → behavioural {wc_ftp['behaviouralMonths']}m, "
      f"base {wc_ftp['baseCurveRate']:.4f} + LP {wc_ftp['liquidityPremium']:.4f} = FTP {wc_ftp['ftp']:.4f}")

print("\n== 3. The term structure produces different funding for different maturities ==")
check("TL (longer behavioural life) funds higher than WC (short revolving)",
      tl_ftp["ftp"] > wc_ftp["ftp"],
      f"TL {tl_ftp['ftp']:.4f} vs WC {wc_ftp['ftp']:.4f}")
check("the two FTPs differ materially (>10 bps)",
      abs(tl_ftp["ftp"] - wc_ftp["ftp"]) > 0.0010,
      f"delta {(tl_ftp['ftp']-wc_ftp['ftp'])*10000:.0f} bps")

print("\n== 4. cost_of_funds in the breakdown equals the derived FTP (not the flat 0.075) ==")
check("breakdown costOfFunds == FTP", abs(tl["breakdown"]["costOfFunds"] - tl_ftp["ftp"]) < 1e-9,
      f"cof={tl['breakdown']['costOfFunds']} ftp={tl_ftp['ftp']}")
check("derived FTP differs from the old flat 0.075", abs(tl_ftp["ftp"] - 0.075) > 1e-6,
      f"ftp={tl_ftp['ftp']}")

print("\n== 5. Negative: overdraft behavioural maturity is the shortest ==")
od = price_deal("OVERDRAFT", 12)
od_ftp = od["breakdown"]["ftp"]
check("OD behavioural maturity (6m) < WC behavioural (12m)",
      od_ftp["behaviouralMonths"] < wc_ftp["behaviouralMonths"],
      f"OD {od_ftp['behaviouralMonths']} vs WC {wc_ftp['behaviouralMonths']}")

print(f"\n== FTP e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
