#!/usr/bin/env python3
"""
Configurable financial templates (FINANCIAL_TEMPLATE) — e2e.

A template AUGMENTS the canonical chart of accounts with sector/segment-specific
extra input lines, derived lines, and ratios (formula-driven). The canonical 15
input / 8 derived lines + standard ratios are always computed and unchanged
(zero regression); a default template matches everything and adds nothing.

Proves: resolution by sector/segment; the default leaves the standard chart
untouched; an SME template adds an extra input line + formula-computed ratios
that flow through the spread; a manufacturing (sector) template resolves; and
the template is governed by maker-checker.
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
    return {"value": v, "sourceDocument": "ft.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def base_lines(extra=None):
    d = {"REVENUE": line(5e9), "COGS": line(3.2e9), "OPERATING_EXPENSES": line(0.9e9),
         "DEPRECIATION": line(0.2e9), "INTEREST_EXPENSE": line(0.15e9), "TAX": line(0.12e9),
         "TOTAL_ASSETS": line(6e9), "CURRENT_ASSETS": line(2.5e9), "CASH": line(0.6e9),
         "CURRENT_LIABILITIES": line(1.5e9), "SHORT_TERM_DEBT": line(0.5e9),
         "LONG_TERM_DEBT": line(1.2e9), "CURRENT_PORTION_LTD": line(0.2e9),
         "NET_WORTH": line(2.8e9), "CFO": line(0.7e9)}
    if extra:
        d.update({k: line(v) for k, v in extra.items()})
    return d


def deal(suffix, segment, sector="MANUFACTURING"):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"FinTmpl {suffix} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"FT{suffix}", "jurisdiction": "IN-RBI", "segment": segment,
        "sector": sector, "country": "IN", "listedEntity": True, "regulatedFi": False,
        "pep": False, "adverseMedia": False, "highRiskJurisdiction": False, "complexOwnership": False},
        actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": segment, "facilityType": "TERM_LOAN",
        "requestedAmount": 200_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "WC",
        "collateralType": "PROPERTY", "collateralValue": 250_000_000, "secured": True}, actor="rm.user")
    return must(st, app, "app")["reference"]


print("== 1. Templates seeded + resolution by sector/segment ==")
st, all_t = call("GET", "/config/api/masters/FINANCIAL_TEMPLATE")
all_t = must(st, all_t, "templates")
keys = sorted(t["recordKey"] for t in all_t if t.get("status") == "ACTIVE")
check("default + SME + manufacturing templates seeded",
      "fin-default" in keys and "fin-sme" in keys and "fin-mfg" in keys, str(keys))

st, r_def = call("GET", "/config/api/financial-templates/resolve?jurisdiction=IN-RBI&segment=MID_CORPORATE")
check("MID_CORPORATE resolves the default chart",
      st == 200 and r_def["payload"]["templateKey"] == "fin-default", str(r_def.get("payload", {}).get("templateKey")))
st, r_sme = call("GET", "/config/api/financial-templates/resolve?jurisdiction=IN-RBI&segment=SME")
check("SME segment resolves the SME chart",
      st == 200 and r_sme["payload"]["templateKey"] == "fin-sme", str(r_sme.get("payload", {}).get("templateKey")))
st, r_mfg = call("GET", "/config/api/financial-templates/resolve?jurisdiction=IN-RBI&sector=MANUFACTURING&segment=MID_CORPORATE")
check("MANUFACTURING sector resolves the manufacturing chart (most specific)",
      st == 200 and r_mfg["payload"]["templateKey"] == "fin-mfg", str(r_mfg.get("payload", {}).get("templateKey")))


print("== 2. Default template leaves the standard chart untouched (zero regression) ==")
ref = deal("DEF", "MID_CORPORATE", "SERVICES")
st, a = call("POST", f"/origination/api/applications/{ref}/spread",
             {"periods": [{"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": base_lines()}]},
             actor="analyst.user")
a = must(st, a, "default spread")
check("default chart in force", a.get("financialTemplate") == "fin-default", str(a.get("financialTemplate")))
ratios = a["periods"][0]["ratios"]
check("standard ratios present (NET_LEVERAGE, DSCR, CURRENT_RATIO)",
      all(k in ratios for k in ("NET_LEVERAGE", "DSCR", "CURRENT_RATIO")), str(sorted(ratios.keys())))
check("no template-specific ratio leaks into the default chart",
      "ASSET_TURNOVER" not in ratios and "PROMOTER_COVERAGE" not in ratios, str(sorted(ratios.keys())))
# Standard NET_LEVERAGE = (TOTAL_DEBT - CASH)/EBITDA = (1.7 - 0.6)/0.9 = 1.222
check("standard NET_LEVERAGE computed exactly as before",
      abs(ratios.get("NET_LEVERAGE", 0) - 1.222) < 0.01, str(ratios.get("NET_LEVERAGE")))


print("== 3. SME template adds an input line + formula-computed ratios (through the spread) ==")
ref2 = deal("SME", "SME", "SERVICES")
st, a2 = call("POST", f"/origination/api/applications/{ref2}/spread",
              {"periods": [{"label": "FY2024", "gaap": "IND_AS", "currency": "INR",
                            "lines": base_lines({"PROMOTER_NET_WORTH": 3.0e9})}]}, actor="analyst.user")
a2 = must(st, a2, "sme spread")
check("SME chart in force", a2.get("financialTemplate") == "fin-sme", str(a2.get("financialTemplate")))
r2 = a2["periods"][0]["ratios"]
# ASSET_TURNOVER = REVENUE / TOTAL_ASSETS = 5e9 / 6e9 = 0.833
check("extra ratio ASSET_TURNOVER computed by formula",
      abs(r2.get("ASSET_TURNOVER", 0) - 0.833) < 0.01, str(r2.get("ASSET_TURNOVER")))
# PROMOTER_COVERAGE = PROMOTER_NET_WORTH / TOTAL_DEBT = 3.0e9 / 1.7e9 = 1.765
check("extra ratio PROMOTER_COVERAGE uses the extra input line via formula",
      abs(r2.get("PROMOTER_COVERAGE", 0) - 1.765) < 0.01, str(r2.get("PROMOTER_COVERAGE")))
# Standard ratios still present & unchanged alongside the extras.
check("standard ratios still present alongside the extras",
      all(k in r2 for k in ("NET_LEVERAGE", "DSCR")) and abs(r2.get("NET_LEVERAGE", 0) - 1.222) < 0.01,
      str(sorted(r2.keys())))
# The extra input line shows in the period's line items.
line_keys = [c["taxonomyKey"] for c in a2["periods"][0]["lines"]]
check("extra input line PROMOTER_NET_WORTH appears in the spread grid",
      "PROMOTER_NET_WORTH" in line_keys, str(line_keys))


print("== 4. Manufacturing template: extra input + derived line via formula ==")
# Resolve the mfg template directly (sector isn't passed through origination today) and
# verify its derived-line formula is well-formed by checking the definition payload.
mfg = r_mfg["payload"]
derived_keys = [d["key"] for d in mfg.get("extraDerivedLines", [])]
check("manufacturing template declares an INVENTORY_DAYS derived line",
      "INVENTORY_DAYS" in derived_keys, str(derived_keys))
inv = next((d for d in mfg["extraDerivedLines"] if d["key"] == "INVENTORY_DAYS"), {})
check("its formula references INVENTORY and COGS",
      "INVENTORY" in inv.get("formula", "") and "COGS" in inv.get("formula", ""), str(inv.get("formula")))


print("== 4b. MANUFACTURING borrower auto-resolves fin-mfg THROUGH origination (sector wired) ==")
ref3 = deal("MFG", "MID_CORPORATE", "MANUFACTURING")
st, a3 = call("POST", f"/origination/api/applications/{ref3}/spread",
              {"periods": [{"label": "FY2024", "gaap": "IND_AS", "currency": "INR",
                            "lines": base_lines({"INVENTORY": 0.8e9, "CAPACITY_UTIL_PCT": 82})}]}, actor="analyst.user")
a3 = must(st, a3, "mfg spread")
check("manufacturing borrower's spread auto-uses fin-mfg (sector from counterparty)",
      a3.get("financialTemplate") == "fin-mfg", str(a3.get("financialTemplate")))
r3 = a3["periods"][0]["ratios"]
check("manufacturing extra ratio ASSET_TURNOVER present", "ASSET_TURNOVER" in r3, str(sorted(r3.keys())))
l3 = {c["taxonomyKey"]: c["value"] for c in a3["periods"][0]["lines"]}
# INVENTORY_DAYS = INVENTORY / COGS * 365 = 0.8e9 / 3.2e9 * 365 = 91.25
check("INVENTORY_DAYS derived line computed by formula (0.8/3.2*365 = 91.25)",
      "INVENTORY_DAYS" in l3 and abs(l3["INVENTORY_DAYS"] - 91.25) < 0.5, str(l3.get("INVENTORY_DAYS")))


print("== 5. FINANCIAL_TEMPLATE is governed (maker-checker / SoD) ==")
new_t = {"templateKey": "fin-e2e", "displayName": "E2E template", "selector": {"segment": "TRADE_FINANCE"},
         "extraInputLines": [], "extraDerivedLines": [],
         "extraRatios": [{"key": "DEBT_TO_REVENUE", "label": "Debt / revenue", "formula": "TOTAL_DEBT / REVENUE"}]}
st, sub = call("POST", "/config/api/masters/FINANCIAL_TEMPLATE",
               {"recordKey": "fin-e2e", "payload": new_t}, actor="master.maker")
sub = must(st, sub, "submit template")
check("new template submitted PENDING_APPROVAL", sub["status"] == "PENDING_APPROVAL", str(sub.get("status")))
st, self_ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.maker")
check("self-approval blocked (SoD) -> 403", st == 403, f"{st}")
st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.checker")
check("different checker approves -> ACTIVE", st == 200 and ap["status"] == "ACTIVE", f"{st}")
st, r_tf = call("GET", "/config/api/financial-templates/resolve?jurisdiction=IN-RBI&segment=TRADE_FINANCE")
check("newly-approved template resolves for its segment",
      st == 200 and r_tf["payload"]["templateKey"] == "fin-e2e", f"{st}")


print(f"\n== financial templates e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
