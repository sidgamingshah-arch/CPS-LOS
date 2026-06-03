#!/usr/bin/env python3
"""End-to-end smoke test of the Helix credit lifecycle, driven through the gateway."""
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


print("== 1. Abstraction layer ==")
st, js = call("GET", "/config/api/jurisdictions")
check("two jurisdictions seeded", st == 200 and len(js) == 2, f"{st}")
st, pack = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=CAPITAL_SA")
check("RBI capital pack dual-signed", st == 200 and pack["fullySignedOff"], f"{st}")

print("== 2. Counterparty / KYC / UBO / screening ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Meridian Steel Pvt Ltd", "legalForm": "PRIVATE_LTD", "registrationNo": "U27100MH2009PTC123456",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": True}, actor="rm.user")
check("counterparty created", st == 200, f"{st} {cp}")
cp_id = cp["id"]
check("complex ownership -> ENHANCED CDD", cp["cddTier"] == "ENHANCED", cp["cddTier"])

st, hits = call("POST", f"/counterparty/api/counterparties/{cp_id}/screening/run", actor="compliance.officer")
check("screening produced hits", st == 200 and len(hits) >= 1, f"{st}")
for h in hits:
    call("POST", f"/counterparty/api/counterparties/screening/{h['id']}/disposition",
         {"disposition": "FALSE_POSITIVE", "note": "no secondary identifier match"}, actor="compliance.officer")

st, ubo = call("POST", f"/counterparty/api/counterparties/{cp_id}/ubo", {
    "nodes": [
        {"key": "ROOT", "name": "Meridian Steel Pvt Ltd", "type": "ROOT", "country": "IN", "confidence": 1.0},
        {"key": "HOLDCO", "name": "Meridian Holdings", "type": "ENTITY", "country": "IN", "confidence": 1.0},
        {"key": "P1", "name": "A. Mehta", "type": "PERSON", "country": "IN", "confidence": 0.95},
        {"key": "P2", "name": "S. Mehta", "type": "PERSON", "country": "IN", "confidence": 0.7}],
    "edges": [
        {"parent": "HOLDCO", "child": "ROOT", "ownershipPct": 0.8},
        {"parent": "P1", "child": "HOLDCO", "ownershipPct": 0.6},
        {"parent": "P1", "child": "ROOT", "ownershipPct": 0.1},
        {"parent": "P2", "child": "HOLDCO", "ownershipPct": 0.4}]}, actor="compliance.officer")
check("UBO resolved", st == 200, f"{st}")
p1 = next((n for n in ubo if n["nodeKey"] == "P1"), {})
# P1 effective = 0.6*0.8 + 0.1 = 0.58 ; P2 = 0.4*0.8 = 0.32 -> both UBOs (>=10%)
check("P1 effective ownership 0.58", abs(p1.get("effectiveOwnership", 0) - 0.58) < 1e-6, str(p1.get("effectiveOwnership")))
check("P1 flagged UBO", p1.get("ubo") is True)
p2 = next((n for n in ubo if n["nodeKey"] == "P2"), {})
check("low-confidence node needs review", p2.get("needsReview") is True)

st, cp = call("POST", f"/counterparty/api/counterparties/{cp_id}/kyc/verify", actor="compliance.officer")
check("KYC verified after clearing hits", st == 200 and cp["kycStatus"] == "VERIFIED", f"{st} {cp.get('kycStatus')}")

print("== 3-4. Application + documents + spreading ==")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp_id, "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 800_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capacity expansion",
    "collateralType": "PROPERTY", "collateralValue": 600_000_000, "secured": True}, actor="rm.user")
check("application created", st == 200, f"{st} {app}")
ref = app["reference"]

st, doc = call("POST", f"/origination/api/applications/{ref}/documents",
               {"fileName": "Meridian_audited_financials_FY24.pdf", "declaredType": None}, actor="analyst.user")
check("financials auto-classified high-confidence", st == 200 and doc["classifiedType"] == "FINANCIAL_STATEMENT"
      and not doc["needsReview"], f"{st} {doc.get('classifiedType')}")
st, doc2 = call("POST", f"/origination/api/applications/{ref}/documents",
                {"fileName": "misc_scan.pdf", "declaredType": None}, actor="analyst.user")
check("unrecognised doc routed to review", st == 200 and doc2["needsReview"], f"{st}")


def period(label, gaap, rev, cogs, opex, dep, intexp, tax, ta, ca, cash, cl, std, ltd, cpltd, nw, cfo):
    def line(v):
        return {"value": v, "sourceDocument": "Meridian_audited_financials_FY24.pdf",
                "sourcePage": "P12", "coordinates": "tbl1", "confidence": 0.97}
    return {"label": label, "gaap": gaap, "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex), "DEPRECIATION": line(dep),
        "INTEREST_EXPENSE": line(intexp), "TAX": line(tax), "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca),
        "CASH": line(cash), "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std),
        "LONG_TERM_DEBT": line(ltd), "CURRENT_PORTION_LTD": line(cpltd), "NET_WORTH": line(nw), "CFO": line(cfo)}}


st, analysis = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    period("FY2024", "IND_AS", 5e9, 3.2e9, 0.9e9, 0.2e9, 0.15e9, 0.12e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 0.2e9, 2.8e9, 0.7e9),
    period("FY2023", "IND_AS", 4.5e9, 2.95e9, 0.85e9, 0.18e9, 0.16e9, 0.10e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 0.2e9, 2.5e9, 0.6e9)]},
    actor="analyst.user")
check("spread generated with 2 periods", st == 200 and len(analysis["periods"]) == 2, f"{st}")
ratios = analysis["periods"][0]["ratios"]
check("DSCR computed = 2.0", abs(ratios.get("DSCR", 0) - 2.0) < 0.01, str(ratios.get("DSCR")))
check("EBITDA derived in latest period",
      any(c["taxonomyKey"] == "EBITDA" and abs(c["value"] - 0.9e9) < 1 for c in analysis["periods"][0]["lines"]))

# rating must be blocked before spread confirmation
st, _ = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
check("rating blocked before spread confirm", st == 409, f"{st}")

st, app = call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
check("spread confirmed", st == 200 and app["spreadConfirmed"], f"{st}")

print("== 5. Rating ==")
st, rating = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
check("rating produced", st == 200, f"{st} {rating}")
check("investment-grade model grade", rating["modelGrade"] in ("AAA", "AA", "A", "BBB"), rating.get("modelGrade"))
check("score breakdown has factors", "factors" in rating["scoreBreakdown"])

# negative: a multi-notch analyst override must be rejected (analyst limit = 1 notch)
st, _ = call("POST", f"/risk/api/risk/{ref}/rating/override",
             {"proposedGrade": "CCC", "reasonCode": "MANAGEMENT_QUALITY", "role": "ANALYST", "note": "x"},
             actor="analyst.user")
check("excessive analyst override rejected (403)", st == 403, f"{st}")

st, rating = call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
check("rating confirmed", st == 200 and rating["confirmed"], f"{st}")

print("== 6. Capital (RWA via rule pack) ==")
st, cap = call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
check("capital computed", st == 200, f"{st} {cap}")
check("exposure class CORPORATE", cap["exposureClass"] == "CORPORATE", cap.get("exposureClass"))
check("rule-pack provenance present", cap["capitalPackCode"] == "rbi_sa_directions_2026", cap.get("capitalPackCode"))
check("CRM recognised (secured portion > 0)", cap["securedPortion"] > 0, str(cap.get("securedPortion")))
check("RWA positive and < EAD (good grade)", 0 < cap["rwa"], str(cap.get("rwa")))
st, expl = call("GET", f"/risk/api/risk/{ref}/capital/explain")
check("grounded capital explanation", st == 200 and "rule pack" in expl["explanation"])

print("== 7. Pricing (RAROC) ==")
st, pricing = call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")
check("pricing produced", st == 200, f"{st} {pricing}")
check("recommended rate above cost of funds", pricing["recommendedRate"] > 0.075, str(pricing.get("recommendedRate")))

print("== 8. Approval workflow (DoA) ==")
st, cov = call("POST", f"/decision/api/decisions/{ref}/covenants", {
    "covenantType": "FINANCIAL_MAINTENANCE", "metric": "DSCR", "operator": ">=", "threshold": 1.25,
    "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
    "breachSeverity": "MAJOR", "onBreach": ["notify_RM", "raise_EWS", "trigger_review"]}, actor="analyst.user")
check("covenant added", st == 200, f"{st}")
st, dec = call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops")
check("routed for approval", st == 200, f"{st} {dec}")
required = dec["requiredAuthority"]
check("required authority resolved", required in ("RM_HEAD", "CREDIT_OFFICER", "CREDIT_COMMITTEE", "BOARD_COMMITTEE"), required)

# negative: insufficient authority
st, _ = call("POST", f"/decision/api/decisions/{ref}/decide",
             {"outcome": "APPROVE", "role": "ANALYST", "rationale": "x"}, actor="analyst.user")
check("insufficient authority rejected (403)", st == 403, f"{st}")

st, dec = call("POST", f"/decision/api/decisions/{ref}/decide",
               {"outcome": "CONDITIONAL_APPROVE", "role": required,
                "rationale": "Strong coverage; standard conditions.",
                "conditions": ["Maintain DSCR >= 1.25x", "Charge over plant & machinery"]}, actor="credit.officer")
check("decision recorded by authorised approver", st == 200 and dec["status"] == "DECIDED", f"{st} {dec}")

print("== 9-12. Portfolio: book, ECL, EWS, concentration, stress ==")
call("PATCH", f"/origination/api/applications/{ref}/status", {"status": "APPROVED"}, actor="credit.ops")
st, exp = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": 0}, actor="credit.ops")
check("exposure booked", st == 200, f"{st} {exp}")
st, ecl = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ecl", actor="credit.ops")
check("ECL computed", st == 200, f"{st} {ecl}")
check("Stage 1 for performing IG name", ecl["stage"] == "STAGE_1", ecl.get("stage"))
check("reported = max(ecl,irac) policy applied", ecl["reportedProvisionPolicy"].startswith("max"), ecl.get("reportedProvisionPolicy"))
st, sigs = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ews/scan", actor="portfolio.manager")
check("EWS scan ran (clean name -> few/no signals)", st == 200, f"{st}")
st, conc = call("GET", "/portfolio/api/portfolio/concentration?jurisdiction=IN-RBI")
check("concentration computed", st == 200 and conc["totalExposure"] > 0, f"{st}")
st, stress = call("GET", "/portfolio/api/portfolio/stress")
check("stress scenarios computed", st == 200 and len(stress["outcomes"]) == 3, f"{st}")
sev = next((o for o in stress["outcomes"] if o["scenario"] == "SEVERE"), {})
check("severe ECL >= baseline", sev.get("stressedEcl", 0) >= sev.get("baselineEcl", 0))

print("== 14. Conversational copilot (scoped, grounded, non-binding) ==")
st, a = call("POST", "/copilot/api/copilot/ask", {"question": "Summarise this deal", "reference": ref}, actor="rm.user")
check("copilot grounded summary", st == 200 and a["grounded"] and not a["refused"] and a["intent"] == "SUMMARY", f"{st} {a}")
st, a = call("POST", "/copilot/api/copilot/ask", {"question": "Approve this deal now", "reference": ref}, actor="credit.officer")
check("copilot refuses credit-consequential action", st == 200 and a["refused"] and a["intent"] == "ACTION_BLOCKED", f"{st}")
st, a = call("POST", "/copilot/api/copilot/ask", {"question": "What is the internal rating?", "reference": ref}, actor="rm.user")
check("copilot enforces persona scope (RM blocked from RATING)", st == 200 and a["refused"], f"{st} {a.get('intent')}")
st, a = call("POST", "/copilot/api/copilot/ask", {"question": "What's the rating and PD?", "reference": ref}, actor="analyst.user")
check("copilot answers in-scope w/ citations", st == 200 and a["grounded"] and len(a["citations"]) >= 1, f"{st}")

print("== 15. Connector ingestion (idempotent, provenance, failures surfaced) ==")
envelope = {"source": "SANCTIONS_SCREENING", "vendor": "WorldCheck", "idempotencyKey": f"WC-{cp_id}-e2e",
            "payloadVersion": "2024-06", "payload": {"entityName": cp["legalName"], "matches": [
                {"list": "OFAC", "name": cp["legalName"], "score": 0.71, "risk": "HIGH", "fields": ["name", "country:IN"]},
                {"list": "PEP", "name": cp["legalName"], "score": 1.4, "risk": "MEDIUM", "fields": ["name"]}]}}
st, r = call("POST", f"/counterparty/api/counterparties/{cp_id}/ingest/screening", envelope, actor="compliance.officer")
check("screening feed ingested", st == 200 and r["accepted"] and not r["duplicate"], f"{st} {r}")
check("out-of-range score surfaced as warning", any("outside" in w for w in r["warnings"]), str(r.get("warnings")))
st, r2 = call("POST", f"/counterparty/api/counterparties/{cp_id}/ingest/screening", envelope, actor="compliance.officer")
check("re-ingest is idempotent (duplicate)", st == 200 and r2["duplicate"], f"{st} {r2}")

cb = {"source": "CORE_BANKING", "vendor": "Finacle", "idempotencyKey": f"FIN-{ref}-q2", "payloadVersion": "v1",
      "payload": {"facilityRef": "FAC-X", "sanctionedLimit": 800000000, "outstanding": 800000000,
                  "currency": "INR", "overdueDays": 0, "conductRating": 0.9, "accountStatus": "ACTIVE"}}
st, r = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ingest/core-banking", cb, actor="credit.ops")
check("core-banking conduct ingested", st == 200 and r["accepted"], f"{st} {r}")
st, r2 = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ingest/core-banking", cb, actor="credit.ops")
check("core-banking re-ingest idempotent", st == 200 and r2["duplicate"], f"{st}")
st, ecl2 = call("GET", f"/portfolio/api/portfolio/exposures/{ref}/ecl/latest")
check("ECL latest readable (GET)", st == 200 and ecl2["stage"].startswith("STAGE"), f"{st}")

print("== 13. Audit trail ==")
st, audit = call("GET", f"/risk/api/audit/subject?type=Application&id={ref}")
check("risk-service audit trail present", st == 200 and len(audit) >= 2, f"{st}")

print(f"\nRESULT: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
