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

print("== 16. Facilities · collaterals · envelope ==")
st, fac = call("POST", f"/origination/api/applications/{ref}/facilities",
               {"facilityType": "WORKING_CAPITAL", "amount": 150_000_000, "currency": "INR",
                "tenorMonths": 12, "purpose": "Working capital line", "indicativeRate": 0.105}, actor="rm.user")
check("additional facility added", st == 200 and not fac["primary"], f"{st}")
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
check("primary + additional facility listed", st == 200 and len(facs) >= 2, f"{st} {len(facs) if facs else 0}")
st, col = call("POST", f"/origination/api/applications/{ref}/collaterals",
               {"collateralType": "RECEIVABLES", "description": "Trade receivables pool",
                "marketValue": 200_000_000, "haircut": 0.5, "perfectionStatus": "IN_PROGRESS"}, actor="analyst.user")
check("additional collateral added", st == 200, f"{st}")
st, env = call("GET", f"/origination/api/applications/{ref}/envelope")
check("deal envelope returns multi-facility/multi-collateral", st == 200 and len(env["facilities"]) >= 2 and len(env["collaterals"]) >= 2, f"{st}")

print("== 16b. Sublimits and interchangeability ==")
# Use the WC facility we added to build a CC/LC/BG sublimit set, with CC+LC interchangeable.
wc = next((f for f in facs if f["facilityType"] == "WORKING_CAPITAL"), facs[-1])
def add_sublimit(code, ptype, amount, group=None):
    return call("POST", f"/origination/api/applications/facilities/{wc['id']}/sublimits",
                {"code": code, "productType": ptype, "amount": amount, "currency": wc["currency"],
                 "tenorMonths": 12, "purpose": code, "interchangeableGroup": group}, actor="analyst.user")
st, s1 = add_sublimit("CC", "CASH_CREDIT", 60_000_000, "WC_FUNDED")
st2, s2 = add_sublimit("BD", "BILL_DISCOUNTING", 40_000_000, "WC_FUNDED")
st3, s3 = add_sublimit("BG", "BANK_GUARANTEE", 50_000_000, None)   # hard cap, not fungible
check("CC + BD + BG sublimits added under WC", st == 200 and st2 == 200 and st3 == 200, f"{st}/{st2}/{st3}")
# Negative: a sublimit that would overflow the facility cap must be rejected.
st_over, _ = add_sublimit("EXTRA", "OVERDRAFT", 999_000_000, None)
check("sublimit exceeding facility cap rejected (400)", st_over == 400, f"{st_over}")
# Verify enriched facility view exposes sublimits + the WC_FUNDED interchangeability pool.
st, fv = call("GET", f"/origination/api/applications/{ref}/facilities/view")
check("facility view returned", st == 200, f"{st}")
wc_view = next((f for f in fv if f["id"] == wc["id"]), {})
check("WC facility view has 3 sublimits", len(wc_view.get("sublimits", [])) == 3,
      str(len(wc_view.get("sublimits", []))))
check("WC_FUNDED group has 2 members and combined cap 100m",
      any(g["groupKey"] == "WC_FUNDED" and g["memberCount"] == 2 and abs(g["combinedCap"] - 100_000_000) < 1
          for g in wc_view.get("interchangeabilityGroups", [])),
      str(wc_view.get("interchangeabilityGroups")))
check("BG sublimit is non-fungible (hard cap)",
      any(s["code"] == "BG" and not s["fungible"] for s in wc_view.get("sublimits", [])))

print("== 17. Covenant testing history ==")
st, tests = call("POST", f"/decision/api/decisions/{ref}/covenants/test", actor="analyst.user")
check("covenants tested", st == 200 and len(tests) >= 1, f"{st}")
check("DSCR covenant passed (clean deal)", any(t["metric"] == "DSCR" and t["passed"] for t in tests), str(tests))

print("== 18. Credit proposal generation (grounded, cited) ==")
st, prop = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", actor="analyst.user")
check("credit proposal v1 generated", st == 200 and prop["version"] == 1, f"{st}")
check("proposal contains facility section in markdown", st == 200 and "Facilities proposed" in prop["markdown"])
check("proposal HTML includes citations to source endpoints",
      "envelope" in prop["citations"] and "rating" in prop["citations"], str(prop.get("citations")))
st, prop2 = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", actor="analyst.user")
check("regeneration bumps version", prop2["version"] == 2, f"v={prop2.get('version')}")

print("== 19. Projected vs actual RAROC tracking ==")
st, snap = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/raroc/snapshot", actor="credit.ops")
check("origination RAROC snapshot present", st == 200 and snap["origination"], f"{st}")
st, actual = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/raroc/compute?period=2026Q2&realisedProvisionDelta=0", actor="portfolio.manager")
check("actual RAROC computed", st == 200 and not actual["origination"] and "actualRaroc" in actual, f"{st}")
check("RAROC variance recorded", "variance" in actual, str(actual))
st, hist = call("GET", f"/portfolio/api/portfolio/exposures/{ref}/raroc")
check("RAROC history >= 2 rows", st == 200 and len(hist) >= 2, f"{st}")

print("== 20. Configurable workflow definitions ==")
st, wf = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=WORKFLOW_DEFINITION")
check("workflow pack returned", st == 200 and wf["payload"]["segment"] == "MID_CORPORATE", f"{st}")
check("workflow has 10+ stages", len(wf["payload"]["stages"]) >= 10)

print("== 21. MIS / dashboards ==")
st, mis = call("GET", "/portfolio/api/mis/dashboard")
check("MIS dashboard returned", st == 200 and "composition" in mis and "rarocVariance" in mis, f"{st}")
st, comp = call("GET", "/portfolio/api/mis/composition")
check("book composition by segment/grade present", st == 200 and "bySegment" in comp and "byGrade" in comp)
st, var = call("GET", "/portfolio/api/mis/raroc-variance")
check("RAROC variance MIS endpoint", st == 200 and var["trackedDeals"] >= 1, f"{st}")
st, ageing = call("GET", "/portfolio/api/mis/pipeline-ageing")
check("pipeline ageing endpoint", st == 200 and "avgAgeDays" in ageing)

print("== 22. Generic master-data + maker-checker ==")
st, dedupM = call("GET", "/config/api/masters/DEDUP_RULES")
check("DEDUP_RULES master seeded (active)", st == 200 and len(dedupM) >= 1, f"{st}")
st, neg = call("GET", "/config/api/masters/NEGATIVE_LIST")
check("NEGATIVE_LIST master seeded", st == 200 and len(neg) >= 3, f"{st}")
st, cov = call("GET", "/config/api/masters/COVENANT_LIBRARY")
check("COVENANT_LIBRARY master seeded", st == 200 and len(cov) >= 3, f"{st}")
# maker-checker lifecycle
st, sub = call("POST", "/config/api/masters/FACILITY_MASTER",
               {"recordKey": "OVERDRAFT", "payload": {"classification": "FUND_BASED", "type": "REVOLVING", "category": "SHORT_TERM"}},
               actor="master.maker")
check("maker submits master record (pending)", st == 200 and sub["status"] == "PENDING_APPROVAL", f"{st} {sub.get('status')}")
# SoD: same actor cannot approve own record
st, sod = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.maker")
check("maker cannot approve own record (SoD, 403)", st == 403, f"{st}")
st, appr = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.checker")
check("checker approves -> ACTIVE", st == 200 and appr["status"] == "ACTIVE", f"{st} {appr.get('status')}")
st, bulk = call("POST", "/config/api/masters/EWS_TRIGGER/bulk",
                [{"recordKey": "CHEQUE_RETURNS", "payload": {"enabled": True, "criticality": "HIGH"}}], actor="master.maker")
check("bulk submit master rows", st == 200 and len(bulk) == 1, f"{st}")

print("== 23. Credit initiation: prospect · dedup · negative · summary ==")
st, p1 = call("POST", "/counterparty/api/initiation/prospects",
              {"legalName": "Helix Demo Steel Pvt Ltd", "legalForm": "PRIVATE_LTD", "registrationNo": "UDEDUP123",
               "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
               "borrowerType": "NTB"}, actor="rm.alice")
check("prospect created (default RM = creator)", st == 200 and p1["recordType"] == "PROSPECT" and p1["rmId"] == "rm.alice", f"{st}")
# Create a near-duplicate to trigger dedup (same registration no + similar name)
st, p2 = call("POST", "/counterparty/api/initiation/prospects",
              {"legalName": "Helix Demo Steel Private Limited", "registrationNo": "UDEDUP123",
               "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN"}, actor="rm.bob")
st, dd = call("GET", f"/counterparty/api/initiation/prospects/{p2['id']}/dedup")
check("dedup finds the duplicate (identifier + name)", st == 200 and dd["matchCount"] >= 1, f"{st} {dd.get('matchCount')}")
check("dedup match exposes RM + classification + lastUpdated",
      st == 200 and dd["matches"][0]["rmId"] is not None and dd["matches"][0]["matchType"] in ("IDENTIFIER", "NAME", "NAME_AND_IDENTIFIER"))
# Negative check: sanctioned country
st, pn = call("POST", "/counterparty/api/initiation/prospects",
              {"legalName": "Pyongyang Trading Co", "jurisdiction": "IN-RBI", "segment": "TRADE_FINANCE",
               "sector": "TRADING", "country": "KP", "borrowerType": "NTB"}, actor="rm.alice")
st, nc = call("GET", f"/counterparty/api/initiation/prospects/{pn['id']}/negative-check")
check("negative check hits sanctioned country", st == 200 and nc["hit"], f"{st}")
st, blocked = call("POST", f"/counterparty/api/initiation/prospects/{pn['id']}/approve", actor="rm.alice")
check("obligor creation blocked while on negative list (409)", blocked is not None and st == 409, f"{st}")

print("== 24. External/source-system checks (fetch + refresh + unified) ==")
for ct in ["SCREENING_INTERNAL", "CREDIT_BUREAU", "KYC_AML", "EXTERNAL_RATING"]:
    call("POST", f"/counterparty/api/initiation/prospects/{p1['id']}/checks/fetch",
         {"entityType": "OBLIGOR", "checkType": ct}, actor="compliance.officer")
st, uni = call("GET", f"/counterparty/api/initiation/prospects/{p1['id']}/checks")
check("unified screening view returns all check types", st == 200 and len(uni) == 4, f"{st} {len(uni) if uni else 0}")
check("bureau/rating checks CLEAR", any(c["checkType"] == "CREDIT_BUREAU" and c["status"] == "CLEAR" for c in uni))
st, refreshed = call("POST", f"/counterparty/api/initiation/checks/{uni[0]['id']}/refresh", actor="compliance.officer")
check("check refresh re-fetches from source", st == 200 and refreshed["status"] in ("CLEAR", "HIT"), f"{st}")

print("== 25. Obligor creation summary + decision + approve ==")
st, summ = call("GET", f"/counterparty/api/initiation/prospects/{p1['id']}/summary")
check("creation summary aggregates dedup+negative+checks", st == 200 and "dedup" in summ and "negative" in summ and "externalChecks" in summ, f"{st}")
call("POST", f"/counterparty/api/initiation/prospects/{p1['id']}/decision", {"proceed": True, "reason": ""}, actor="rm.alice")
st, ob = call("POST", f"/counterparty/api/initiation/prospects/{p1['id']}/approve", actor="rm.alice")
check("prospect approved into obligor", st == 200 and ob["recordType"] == "OBLIGOR" and ob["externalId"], f"{st}")

print("== 26. RM ownership + groups ==")
st, oa = call("POST", f"/counterparty/api/initiation/counterparties/{p1['id']}/ownership/request",
              {"toRm": "rm.carol", "mode": "ASSIGN", "note": "coverage change"}, actor="rm.alice")
check("ownership request pending", st == 200 and oa["status"] == "PENDING", f"{st}")
st, wrong = call("POST", f"/counterparty/api/initiation/ownership/{oa['id']}/decision?accept=true", actor="rm.eve")
check("only receiving RM can accept (403)", st == 403, f"{st}")
st, acc = call("POST", f"/counterparty/api/initiation/ownership/{oa['id']}/decision?accept=true", actor="rm.carol")
check("receiving RM accepts -> reassigned", st == 200 and acc["status"] == "ACCEPTED", f"{st}")
st, ob2 = call("GET", f"/counterparty/api/counterparties/{p1['id']}")
check("counterparty RM reassigned to rm.carol", st == 200 and ob2["rmId"] == "rm.carol", f"{st} {ob2.get('rmId')}")
st, grp = call("POST", "/counterparty/api/initiation/groups",
               {"name": "Helix Demo Group", "groupRmId": "rm.group", "country": "IN", "multiCountry": False}, actor="rm.alice")
check("group created", st == 200, f"{st}")
call("POST", f"/counterparty/api/initiation/counterparties/{p1['id']}/group/{grp['id']}", actor="rm.alice")
st, gx = call("GET", f"/counterparty/api/initiation/groups/{grp['id']}/exposure")
check("group exposure summary lists members", st == 200 and gx["memberCount"] >= 1, f"{st}")
st, cu = call("POST", "/counterparty/api/initiation/auto-cleanup", actor="system")
check("auto-cleanup endpoint runs (config-driven months)", st == 200 and "cleanupMonths" in cu, f"{st}")

print("== 27. Limit management: tree · fungibility · transaction APIs ==")
cp_ref = cp["reference"]
st, tree = call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")
check("limit tree built from deal facilities", st == 200 and len(tree["nodes"]) >= 3, f"{st} {len(tree.get('nodes', [])) if tree else 0}")
check("tree exposes interchangeability group (WC_FUNDED)",
      any(g["groupKey"] == "WC_FUNDED" for g in tree.get("interchangeabilityGroups", [])), str(tree.get("interchangeabilityGroups")))
# View API: full tree by CIF
st, view = call("GET", f"/limits/api/limits/view?cif={cp_ref}")
check("View API returns tree for CIF", st == 200 and view["cif"] == cp_ref and view["totalSanctionedBase"] > 0, f"{st}")
# pick the obligor root and a leaf sublimit line
root_node = next((n for n in view["nodes"] if n["level"] == 0), None)
leaf = next((n for n in view["nodes"] if n["level"] == 2), None) or next((n for n in view["nodes"] if n["level"] == 1), None)
check("obligor root + leaf present", root_node is not None and leaf is not None)
line_id = leaf["reference"]
avail = leaf["available"]
# Validation API: a within-limit amount passes
st, val = call("POST", f"/limits/api/limits/validate?cif={cp_ref}&line={line_id}&amount={int(avail*0.4)}&currency=INR")
check("Validation API passes within available", st == 200 and val["success"], f"{st} {val}")
# Utilisation API: utilise then check balances rolled up
st, ur = call("POST", "/limits/api/limits/utilise",
              {"cif": cp_ref, "productProcessor": "FINACLE",
               "actions": [{"lineId": line_id, "action": "UTILISE", "amount": int(avail*0.4), "currency": "INR", "transactionRef": "TXN-1"}]},
              actor="product.processor")
check("Utilisation API confirms utilise", st == 200 and ur["success"] and ur["results"][0]["newOutstanding"] > 0, f"{st} {ur}")
st, view2 = call("GET", f"/limits/api/limits/view?cif={cp_ref}")
root2 = next((n for n in view2["nodes"] if n["level"] == 0), {})
check("utilisation rolled up to obligor root", root2.get("outstanding", 0) > 0, str(root2.get("outstanding")))
# Over-limit utilise is rejected
st, over = call("POST", "/limits/api/limits/utilise",
                {"cif": cp_ref, "actions": [{"lineId": line_id, "action": "UTILISE", "amount": 999_000_000_000, "currency": "INR"}]},
                actor="product.processor")
check("over-limit utilise rejected", st == 200 and not over["results"][0]["success"], f"{st}")
# Override forces it through
st, forced = call("POST", "/limits/api/limits/utilise",
                  {"cif": cp_ref, "overrideFlag": True,
                   "actions": [{"lineId": line_id, "action": "UTILISE", "amount": 5_000_000, "currency": "INR"}]},
                  actor="product.processor")
check("override forces utilisation", st == 200 and forced["results"][0]["success"], f"{st}")
# Freeze blocks utilisation even with override
st, frozen = call("POST", f"/limits/api/limits/{leaf['id']}/freeze", {"reason": "investigation"}, actor="credit.ops")
st, blk = call("POST", "/limits/api/limits/utilise",
               {"cif": cp_ref, "overrideFlag": True,
                "actions": [{"lineId": line_id, "action": "UTILISE", "amount": 1000, "currency": "INR"}]},
               actor="product.processor")
check("frozen line blocks utilisation (even override)", st == 200 and not blk["results"][0]["success"], f"{st}")
call("POST", f"/limits/api/limits/{leaf['id']}/unfreeze", actor="credit.ops")
# Cap validation on manual child add
st, badchild = call("POST", f"/limits/api/limits/{root_node['id']}/child",
                    {"code": "OVERSIZED", "productType": "TERM_LOAN", "classification": "FUND_BASED",
                     "revolving": False, "sanctionedAmount": 999_000_000_000, "currency": "INR"}, actor="credit.ops")
check("child exceeding parent cap rejected (400)", badchild is not None and st == 400, f"{st}")
# Exposure norms
st, exp = call("GET", f"/limits/api/limits/{cp_ref}/exposure")
check("exposure-norm check returns single-name + sector", st == 200 and len(exp["checks"]) >= 2, f"{st}")

print("== 28. CAD / documentation: checklist · deviation (2-level) · limit release ==")
st, cad = call("POST", "/decision/api/cad/cases",
               {"applicationRef": ref, "counterpartyName": cp["legalName"], "cpType": "NEW"}, actor="cad.officer")
items = cad["items"] if cad else []
check("CAD case opened with checklist from master", st == 200 and len(items) >= 3, f"{st} {len(items)}")
case_id = cad["cadCase"]["id"]
# comply all but the last item; the last goes to deviation/waiver
for it in items[:-1]:
    call("POST", f"/decision/api/cad/items/{it['id']}", {"status": "COMPLIED", "docRef": "DMS-1"}, actor="cad.officer")
last = items[-1]
st, dev = call("POST", f"/decision/api/cad/items/{last['id']}/deviation",
               {"type": "WAIVER", "reason": "Insurance assignment pending; 30-day cure"}, actor="cad.officer")
check("deviation raised (pending L1)", st == 200 and dev["status"] == "PENDING_L1", f"{st}")
# SoD: raiser cannot approve
st, sod = call("POST", f"/decision/api/cad/deviations/{dev['id']}/decision", {"approve": True}, actor="cad.officer")
check("raiser cannot approve own deviation (403)", st == 403, f"{st}")
st, l1 = call("POST", f"/decision/api/cad/deviations/{dev['id']}/decision", {"approve": True, "comment": "ok L1"}, actor="cad.l1")
check("L1 approves -> pending L2", st == 200 and l1["status"] == "PENDING_L2", f"{st}")
# SoD: L2 must differ from L1
st, samel = call("POST", f"/decision/api/cad/deviations/{dev['id']}/decision", {"approve": True}, actor="cad.l1")
check("L2 must differ from L1 (403)", st == 403, f"{st}")
st, l2 = call("POST", f"/decision/api/cad/deviations/{dev['id']}/decision", {"approve": True, "comment": "ok L2"}, actor="cad.l2")
check("L2 approves -> deviation approved (item waived)", st == 200 and l2["status"] == "APPROVED", f"{st}")
# complete + limit release
st, comp = call("POST", f"/decision/api/cad/cases/{case_id}/complete", actor="cad.officer")
check("CAD checklist completed (all complied/waived)", st == 200 and comp["cadCase"]["status"] == "COMPLETED", f"{st}")
st, badrel = call("POST", f"/decision/api/cad/cases/{case_id}/limit-release",
                  {"processingFeeAmortised": False, "lienMarked": False, "cashMarginCaptured": False}, actor="cad.officer")
check("incomplete limit-release checklist rejected (400)", badrel is not None and st == 400, f"{st}")
st, rel = call("POST", f"/decision/api/cad/cases/{case_id}/limit-release",
               {"processingFeeAmortised": True, "lienMarked": True, "cashMarginCaptured": True, "comment": "ok"}, actor="cad.officer")
check("limit release triggers feed to limit mgmt", st == 200 and rel["cadCase"]["status"] == "LIMIT_RELEASED", f"{st}")

print("== 29. Covenant tracking workflow (schedule · state machine · waiver) ==")
st, scheds = call("POST", "/decision/api/covenants/tracking/init",
                  {"applicationRef": ref, "startDate": "2026-01-01", "endDate": "2029-01-01"}, actor="analyst.user")
check("schedules initialised for active covenants", st == 200 and len(scheds) >= 1, f"{st}")
st, run1 = call("POST", f"/decision/api/covenants/tracking/{ref}/run-due", actor="portfolio.manager")
check("run-due executes the state machine", st == 200 and all(s["status"] in ("COMPLIANT", "BREACHED", "OVERDUE") for s in run1), f"{st}")
# Manufacture a breach so we can exercise the waiver flow: add a tight covenant we know will breach.
call("POST", f"/decision/api/decisions/{ref}/covenants",
     {"covenantType": "FINANCIAL_MAINTENANCE", "metric": "NET_LEVERAGE", "operator": "<=", "threshold": 0.01,
      "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
      "breachSeverity": "MAJOR", "onBreach": ["raise_EWS"]}, actor="analyst.user")
call("POST", "/decision/api/covenants/tracking/init",
     {"applicationRef": ref, "startDate": "2026-01-01", "endDate": "2029-01-01"}, actor="analyst.user")
st, run2 = call("POST", f"/decision/api/covenants/tracking/{ref}/run-due", actor="portfolio.manager")
breached = [s for s in run2 if s["status"] in ("BREACHED", "OVERDUE")]
check("at least one schedule breached after tight covenant", len(breached) >= 1, f"{[s.get('status') for s in run2]}")
sched_id = breached[0]["id"]
# RM requests a waiver
st, req = call("POST", f"/decision/api/covenants/tracking/schedules/{sched_id}/request/waiver",
               {"reason": "Temporary breach due to one-off acquisition cost"}, actor="rm.user")
check("waiver request raised (pending)", st == 200 and req["status"] == "PENDING", f"{st}")
# SoD: requester cannot self-approve
st, sod = call("POST", f"/decision/api/covenants/tracking/actions/{req['id']}/decision",
               {"approve": True, "comment": "self"}, actor="rm.user")
check("requester cannot approve own action (403)", st == 403, f"{st}")
st, dec = call("POST", f"/decision/api/covenants/tracking/actions/{req['id']}/decision",
               {"approve": True, "comment": "1-quarter waiver"}, actor="credit.officer")
check("credit officer approves waiver", st == 200 and dec["status"] == "APPROVED", f"{st}")
st, after = call("GET", f"/decision/api/covenants/tracking/{ref}")
check("schedule status updated to WAIVED", any(s["id"] == sched_id and s["status"] == "WAIVED" for s in after), str([s.get("status") for s in after]))
# Extension flow
st, breached_now = call("GET", f"/decision/api/covenants/tracking/{ref}")
ext_sched = next((s for s in breached_now if s["status"] in ("BREACHED", "OVERDUE")), None)
if ext_sched:
    fut = "2030-01-01"
    st, ereq = call("POST", f"/decision/api/covenants/tracking/schedules/{ext_sched['id']}/request/extension",
                    {"newDueDate": fut, "reason": "Need more time"}, actor="rm.user")
    check("extension request raised", st == 200 and ereq["status"] == "PENDING", f"{st}")
    st, edec = call("POST", f"/decision/api/covenants/tracking/actions/{ereq['id']}/decision",
                    {"approve": True, "comment": "ok"}, actor="credit.officer")
    check("extension approved -> EXTENDED + due date advanced", st == 200 and edec["status"] == "APPROVED", f"{st}")
st, alerts = call("POST", "/decision/api/covenants/tracking/alerts/send?days=120", actor="system")
check("upcoming alerts emitted", st == 200 and alerts["alertsSent"] >= 0, f"{st}")

print("== 30. Customer-360 / Portfolio-360 dashboards ==")
st, c360 = call("GET", f"/portfolio/api/mis/customer360/{ref}")
check("Customer-360 aggregates borrower view", st == 200 and "borrowerProfile" in c360 and "ratios" in c360 and "raroc" in c360, f"{st}")
check("Customer-360 surfaces triggers + provisioning", "triggersAndBreaches" in c360 and "provisioning" in c360)
st, p360 = call("GET", "/portfolio/api/mis/portfolio360")
check("Portfolio-360 returns book-level cuts", st == 200 and p360["exposureCount"] >= 1 and "byInternalRating" in p360 and "byVintageYear" in p360, f"{st}")

print("== 31. Country & department limits · FI transaction workflow ==")
st, cy = call("POST", "/limits/api/limits/country",
              {"country": "IN", "overallLimit": 50_000_000_000, "currency": "INR", "externalRating": "BBB"}, actor="credit.ops")
check("country limit upserted", st == 200, f"{st}")
st, dept = call("POST", "/limits/api/limits/department",
                {"country": "IN", "department": "FI", "limit": 10_000_000_000, "currency": "INR", "cashCollateral": 0}, actor="credit.ops")
check("FI department limit upserted", st == 200, f"{st}")
st, bad = call("POST", "/limits/api/limits/department",
               {"country": "IN", "department": "CORPORATE", "limit": 99_000_000_000_000, "currency": "INR", "cashCollateral": 0}, actor="credit.ops")
check("department overflowing country cap rejected (400)", bad is not None and st == 400, f"{st}")
st, cv = call("GET", "/limits/api/limits/country/IN")
check("country view aggregates departments", st == 200 and cv["country"] == "IN" and len(cv["departments"]) >= 1, f"{st}")
# Submit an FI transaction against the existing obligor's working-capital leaf line
st, fv = call("GET", f"/limits/api/limits/view?cif={cp['reference']}")
fi_line = next((n for n in fv["nodes"] if n["level"] >= 1), None)
st, fitx = call("POST", "/limits/api/limits/fi/transactions",
                {"cif": cp["reference"], "country": "IN", "department": "FI", "lineId": fi_line["reference"],
                 "facilityType": fi_line["productType"] or fi_line["code"], "amount": 5_000_000, "currency": "INR",
                 "productProcessor": "FI-PP", "bookingUnit": "MUM01", "transactionRef": "FX-01", "cashMargin": 0},
                actor="product.processor")
check("FI transaction submitted (pending approval)", st == 200 and fitx["status"] == "PENDING_APPROVAL", f"{st}")
st, fdec = call("POST", f"/limits/api/limits/fi/transactions/{fitx['id']}/decision",
                {"approve": True, "approvedRate": 0.085, "comment": "ok"}, actor="credit.officer")
check("FI transaction approved -> utilisation applied", st == 200 and fdec["status"] in ("APPROVED", "EXCEPTION_APPROVED"), f"{st}")

print("== 32. Corrective Action Plan (CAP) ==")
fut = "2030-06-01"
st, capr = call("POST", "/portfolio/api/cap/actions",
                {"applicationReference": ref, "description": "Submit revised cash-flow projections",
                 "criticality": "HIGH", "targetDate": fut, "owner": "rm.alice",
                 "reminderDays": 3, "escalationDays": 5}, actor="credit.officer")
check("CAP raised by credit team", st == 200 and capr["status"] == "OPEN", f"{st}")
# Only owner may respond
st, wrong = call("POST", f"/portfolio/api/cap/actions/{capr['id']}/respond",
                 {"response": "x", "docRef": "DMS-CAP-1"}, actor="rm.bob")
check("non-owner cannot respond (403)", st == 403, f"{st}")
st, resp = call("POST", f"/portfolio/api/cap/actions/{capr['id']}/respond",
                {"response": "Projections attached", "docRef": "DMS-CAP-1"}, actor="rm.alice")
check("owner submits response -> IN_PROGRESS", st == 200 and resp["status"] == "IN_PROGRESS", f"{st}")
# SoD: owner cannot close their own CAP
st, sod = call("POST", f"/portfolio/api/cap/actions/{capr['id']}/close", {"comment": "self"}, actor="rm.alice")
check("owner cannot close own CAP (403)", st == 403, f"{st}")
st, closed = call("POST", f"/portfolio/api/cap/actions/{capr['id']}/close",
                  {"comment": "Approved"}, actor="credit.officer")
check("credit team closes CAP", st == 200 and closed["status"] == "COMPLETED", f"{st}")
st, sweep = call("POST", "/portfolio/api/cap/sweep", actor="system")
check("CAP sweep endpoint runs", st == 200 and "overdue" in sweep, f"{st}")
st, byref = call("GET", f"/portfolio/api/cap/{ref}")
check("CAPs queryable by application", st == 200 and len(byref) >= 1, f"{st}")

print("== 33. MER tracking: deferred docs · renewals · DMS feed · maker-checker · escalation ==")
st, mers = call("POST", f"/decision/api/mer/generate/from-cad/{case_id}?owner=rm.alice", actor="cad.officer")
check("MER register built from CAD case", st == 200 and len(mers) >= 2, f"{st} {len(mers) if mers else 0}")
deferred = next((m for m in mers if m["itemType"] == "DEFERRED_DOCUMENT"), None)
check("deferred document obligation captured from waived item", deferred is not None, str([m["itemType"] for m in mers]))
review = next((m for m in mers if m["recurring"] and m["itemType"] == "RENEWAL_REVIEW"), None)
check("annual review seeded as recurring renewal", review is not None, str([m["itemType"] for m in mers]))
check("collateral renewals seeded (insurance/valuation)",
      any(m["itemType"] in ("INSURANCE", "VALUATION") for m in mers), str([m["itemType"] for m in mers]))

# Submit evidence on the deferred document -> SUBMITTED + DMS feed
st, sub = call("POST", f"/decision/api/mer/{deferred['id']}/submit",
               {"docRef": "DMS-MER-1", "comment": "Insurance assignment executed"}, actor="rm.alice")
check("evidence submitted -> SUBMITTED (DMS-fed)", st == 200 and sub["status"] == "SUBMITTED" and sub["docRef"] == "DMS-MER-1", f"{st}")
# SoD: verifier cannot be the submitter
st, sodv = call("POST", f"/decision/api/mer/{deferred['id']}/verify", {"approve": True, "comment": "self"}, actor="rm.alice")
check("verifier cannot be submitter (403)", st == 403, f"{st}")
st, ver = call("POST", f"/decision/api/mer/{deferred['id']}/verify", {"approve": True, "comment": "verified"}, actor="cad.officer")
check("verified by a different actor -> CLEARED", st == 200 and ver["status"] == "CLEARED", f"{st}")

# Recurring renewal: submit + verify rolls the due date forward and re-opens for the next cycle
call("POST", f"/decision/api/mer/{review['id']}/submit", {"docRef": "DMS-REVIEW-1", "comment": "Annual review note"}, actor="rm.alice")
st, rver = call("POST", f"/decision/api/mer/{review['id']}/verify", {"approve": True}, actor="credit.officer")
check("recurring renewal rolls forward (OPEN, cycle 1)", st == 200 and rver["status"] == "OPEN" and rver["cycleCount"] == 1,
      f"{st} {rver.get('status') if rver else None} {rver.get('cycleCount') if rver else None}")
check("renewal due date advanced", rver["dueDate"] > review["dueDate"], f"{rver.get('dueDate')} vs {review.get('dueDate')}")

# Waiver with maker-checker SoD: the owner cannot waive their own item
st, allm = call("GET", f"/decision/api/mer/{ref}")
waivable = next((m for m in allm if m["status"] in ("OPEN", "SUBMITTED", "OVERDUE", "ESCALATED")), None)
st, sodw = call("POST", f"/decision/api/mer/{waivable['id']}/waive", {"reason": "self"}, actor="rm.alice")
check("owner cannot waive own item (403)", st == 403, f"{st}")
st, wv = call("POST", f"/decision/api/mer/{waivable['id']}/waive",
              {"reason": "Covered by group master insurance policy"}, actor="credit.officer")
check("waiver by a non-owner -> WAIVED", st == 200 and wv["status"] == "WAIVED", f"{st}")

# Sweep (escalation ageing), reminders and the dashboard summary
st, mswp = call("POST", "/decision/api/mer/sweep", actor="system")
check("MER sweep runs", st == 200 and "markedOverdue" in mswp and "markedEscalated" in mswp, f"{st}")
st, mrem = call("POST", "/decision/api/mer/reminders/send?days=400", actor="system")
check("MER reminders emitted for near-due items", st == 200 and mrem["remindersSent"] >= 1, f"{st}")
st, msum = call("GET", f"/decision/api/mer/summary?reference={ref}")
check("MER summary returns status breakdown", st == 200 and msum["total"] >= 2 and "byStatus" in msum, f"{st}")

print("== 34. EOD batch: FX refresh · currency revaluation · utilisation reconciliation ==")
# A USD-denominated root so revaluation has a non-base-currency node to move.
st, usd_root = call("POST", "/limits/api/limits/root",
                    {"cif": "EOD-USD-1", "applicationRef": "EOD-USD-APP", "code": "USD-ROOT",
                     "sanctionedAmount": 5_000_000, "currency": "USD", "tenorMonths": 36,
                     "segment": "WHOLESALE", "sector": "TRADE", "country": "AE", "fungible": False},
                    actor="credit.ops")
check("USD root created for revaluation test", st == 200 and usd_root["currency"] == "USD", f"{st}")

# Initial FX view
st, fxv = call("GET", "/limits/api/limits/eod/fx")
check("FX view returns rates + base", st == 200 and fxv["base"] == "INR" and "USD" in fxv["rates"], f"{st}")

# A clean EOD run before any rate change should produce no revaluations.
st, run0 = call("POST", "/limits/api/limits/eod/run", actor="eod.batch")
check("first EOD run completes", st == 200 and "id" in run0, f"{st}")
check("clean run produces no revaluations", run0["revaluedCount"] == 0, f"{run0.get('revaluedCount')}")
check("clean run produces no variances on freshly built book", run0["varianceCount"] == 0, f"{run0.get('varianceCount')}")

# Refresh the USD rate +5% to simulate today's market move.
st, fxr = call("POST", "/limits/api/limits/eod/fx/refresh", {"currency": "USD", "rate": 87.15}, actor="market.data")
check("USD rate refreshed", st == 200 and abs(fxr["current"] - 87.15) < 0.01, f"{st}")

# Negative: a non-positive rate must be rejected.
st, badr = call("POST", "/limits/api/limits/eod/fx/refresh", {"currency": "USD", "rate": -1}, actor="market.data")
check("non-positive FX rate rejected (400)", badr is not None and st == 400, f"{st}")

# Re-run EOD; USD-priced nodes must revalue and produce a positive delta.
st, run1 = call("POST", "/limits/api/limits/eod/run", actor="eod.batch")
check("EOD revalues USD nodes", st == 200 and run1["revaluedCount"] >= 1, f"{run1.get('revaluedCount')}")
check("net revaluation delta is positive (USD up)", run1["revaluationDeltaBase"] > 0, f"{run1.get('revaluationDeltaBase')}")
st, detail = call("GET", f"/limits/api/limits/eod/runs/{run1['id']}")
check("per-run detail returns the revaluation entries",
      len(detail["revaluations"]) >= 1 and detail["revaluations"][0]["currency"] == "USD", str(detail.get("revaluations")))

# Idempotency: an immediate re-run at the same rates should produce no further moves.
st, run2 = call("POST", "/limits/api/limits/eod/run", actor="eod.batch")
check("re-running EOD with no rate change is idempotent (no new revaluation)",
      run2["revaluedCount"] == 0, f"{run2.get('revaluedCount')}")

# Variance detection: utilise on a leaf line on the INR book and prove the ledger reconciles.
fv = call("GET", f"/limits/api/limits/view?cif={cp['reference']}")[1]
leaf = next((n for n in fv["nodes"] if n["level"] >= 1), None)
call("POST", "/limits/api/limits/utilise",
     {"cif": cp["reference"], "productProcessor": "EOD-T",
      "actions": [{"lineId": leaf["reference"], "action": "UTILISE", "amount": 100_000, "currency": "INR"}]},
     actor="product.processor")
st, run3 = call("POST", "/limits/api/limits/eod/run", actor="eod.batch")
check("ledger-consistent utilisation reconciles cleanly", run3["varianceCount"] == 0, f"{run3.get('varianceCount')}")

st, runs = call("GET", "/limits/api/limits/eod/runs")
check("EOD run history queryable (most recent first)",
      st == 200 and len(runs) >= 4 and runs[0]["id"] > runs[-1]["id"], f"{st} {len(runs) if runs else 0}")

def fresh_app(purpose):
    _, a = call("POST", "/origination/api/applications",
                {"counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
                 "jurisdiction": "IN", "segment": "WHOLESALE", "facilityType": "TERM_LOAN",
                 "requestedAmount": 100_000_000, "currency": "INR", "tenorMonths": 36, "purpose": purpose,
                 "collateralType": "NONE", "collateralValue": 0, "secured": False}, actor="rm.user")
    return a["reference"]

print("== 35. Specialised deal structures (CP variants) ==")
# Syndication on the main deal: lead + two participants, our 25% share.
st, syn = call("POST", f"/origination/api/applications/{ref}/structure",
               {"structureType": "SYNDICATION", "leadArranger": "Helix Bank",
                "totalDealAmount": 1_000_000_000, "ourShareAmount": 250_000_000}, actor="rm.user")
check("syndication structure set", st == 200 and syn["structure"]["structureType"] == "SYNDICATION", f"{st}")
check("our share % derived from total", abs(syn["structure"]["ourSharePct"] - 25.0) < 0.1, str(syn["structure"].get("ourSharePct")))
for (role, name, committed) in [("LEAD_BANK", "Helix Bank", 400_000_000),
                                ("PARTICIPANT_LENDER", "Bank B", 300_000_000),
                                ("PARTICIPANT_LENDER", "Bank C", 300_000_000)]:
    call("POST", f"/origination/api/applications/{ref}/structure/participants",
         {"role": role, "name": name, "committedAmount": committed}, actor="rm.user")
st, synv = call("GET", f"/origination/api/applications/{ref}/structure")
check("syndication validates (>=2 lenders, commitments tie to total)",
      synv["valid"] and abs(synv["lenderCommittedSum"] - 1_000_000_000) < 1, f"valid={synv['valid']} sum={synv['lenderCommittedSum']}")

# Dual-obligor (Islamic): requires exactly two obligors.
ref_dual = fresh_app("Dual-obligor murabaha")
call("POST", f"/origination/api/applications/{ref_dual}/structure",
     {"structureType": "DUAL_OBLIGOR", "islamic": True}, actor="rm.user")
call("POST", f"/origination/api/applications/{ref_dual}/structure/participants",
     {"role": "PRIMARY_OBLIGOR", "name": "Obligor One", "sharePct": 50, "liabilityType": "JOINT_AND_SEVERAL"}, actor="rm.user")
st, d1 = call("GET", f"/origination/api/applications/{ref_dual}/structure")
check("dual-obligor with one obligor is invalid (ERROR finding)",
      not d1["valid"] and any(f["level"] == "ERROR" for f in d1["findings"]), str(d1["findings"]))
call("POST", f"/origination/api/applications/{ref_dual}/structure/participants",
     {"role": "CO_OBLIGOR", "name": "Obligor Two", "sharePct": 50, "liabilityType": "JOINT_AND_SEVERAL"}, actor="rm.user")
st, d2 = call("GET", f"/origination/api/applications/{ref_dual}/structure")
check("dual-obligor with two obligors validates", d2["valid"], str(d2["findings"]))

# Renewal/amendment: copy the structure into a fresh proposal.
ref_renew = fresh_app("Renewal of syndicated TL")
st, cpc = call("POST", f"/origination/api/applications/{ref_renew}/structure/copy-from/{ref}", actor="rm.user")
check("copy-from clones structure for renewal", st == 200 and cpc["structure"]["copiedFromReference"] == ref, f"{st}")
check("copied participants carried over", len(cpc["participants"]) == 3, str(len(cpc.get("participants", []))))

print("== 36. GenAI document intelligence (extract → confirm gate · language · translate · checks) ==")
st, fdoc = call("POST", f"/origination/api/applications/{ref}/documents",
                {"fileName": "ACME_FY2025_financials.pdf", "declaredType": "FINANCIAL_STATEMENT"}, actor="analyst.user")
doc_id = fdoc["id"]
st, ext = call("POST", f"/origination/api/doc-intel/documents/{doc_id}/extract", actor="doc.intel")
check("AI extraction produced suggested fields", st == 200 and ext["status"] == "SUGGESTED" and len(ext["fields"]) >= 3, f"{st}")
check("extraction carries confidence (advisory, not auto-applied to figures)", ext["overallConfidence"] > 0, str(ext.get("overallConfidence")))
st, conf = call("POST", f"/origination/api/doc-intel/extractions/{ext['id']}/confirm",
                {"note": "tie-out vs source ok"}, actor="analyst.user")
check("human confirms AI extraction", st == 200 and conf["status"] == "CONFIRMED" and conf["reviewedBy"] == "analyst.user", f"{st}")
st, reconf = call("POST", f"/origination/api/doc-intel/extractions/{ext['id']}/confirm", {}, actor="analyst.user")
check("re-confirm rejected (already decided, 409)", reconf is not None and st == 409, f"{st}")
st, norm = call("POST", "/origination/api/doc-intel/normalise-language",
                {"text": "we'll fund the deal asap and you'll repay", "target": "LEGAL"}, actor="analyst.user")
check("casual→legal rewrite changes the text", st == 200 and norm["rewritten"] != norm["original"] and norm["advisory"], f"{st}")
st, tr = call("POST", "/origination/api/doc-intel/translate",
              {"text": "هذا مستند تجريبي", "targetLanguage": "en"}, actor="analyst.user")
check("translation detects Arabic source", st == 200 and tr["sourceLanguage"] == "ar" and tr["targetLanguage"] == "en", f"{st} {tr.get('sourceLanguage')}")
st, chk = call("GET", f"/origination/api/doc-intel/documents/{doc_id}/checks")
check("document checks return advisory findings", st == 200 and chk["advisory"] and len(chk["findings"]) >= 1, f"{st}")

print("== 37. Advisory RAG scoring + macro directional impact (non-binding overlays) ==")
st, grade_before = call("GET", f"/risk/api/risk/{ref}/rating")
g0 = grade_before["finalGrade"]
st, rag = call("POST", f"/risk/api/risk/{ref}/rag", actor="risk.analyst")
check("statistical RAG assessed", st == 200 and rag["band"] in ("RED", "AMBER", "GREEN") and 0 <= rag["score"] <= 100, f"{st} {rag.get('band')}")
check("RAG is advisory and explains its factors", rag["advisory"] and "breakdown" in rag["factors"], str(list(rag.get("factors", {}).keys())))
st, ragh = call("GET", f"/risk/api/risk/{ref}/rag")
check("RAG history queryable", st == 200 and len(ragh) >= 1, f"{st}")
st, down = call("POST", f"/risk/api/risk/{ref}/macro-impact",
                {"scenarioName": "Stagflation", "interestRateBps": 200, "gdpGrowthDeltaPct": -2,
                 "sectorOutlook": "DETERIORATING"}, actor="risk.analyst")
check("adverse macro scenario raises PD (downgrade pressure)",
      st == 200 and down["stressedPd"] > down["baselinePd"] and down["notchEstimate"] < 0, f"{st} {down.get('stressedPd')} vs {down.get('baselinePd')}")
st, up = call("POST", f"/risk/api/risk/{ref}/macro-impact",
              {"scenarioName": "Soft landing", "interestRateBps": -100, "gdpGrowthDeltaPct": 2,
               "sectorOutlook": "IMPROVING"}, actor="risk.analyst")
check("benign macro scenario lowers PD (upgrade headroom)",
      st == 200 and up["stressedPd"] < up["baselinePd"] and up["notchEstimate"] > 0, f"{st} {up.get('stressedPd')} vs {up.get('baselinePd')}")
check("macro overlays are advisory", down["advisory"] and up["advisory"])
st, grade_after = call("GET", f"/risk/api/risk/{ref}/rating")
check("authoritative rating UNCHANGED by advisory overlays", grade_after["finalGrade"] == g0, f"{g0} -> {grade_after.get('finalGrade')}")

print("== 38. Document generation (template-driven · clause surgery · confirm gate) ==")
st, tpls = call("GET", "/decision/api/docs/templates")
check("templates listed from DOC_TEMPLATE_MASTER", st == 200 and any(t["recordKey"] == "FACILITY_AGREEMENT" for t in tpls), f"{st}")
st, tncs = call("GET", "/decision/api/docs/tnc-clauses")
check("TNC clauses listed from TNC_MASTER", st == 200 and len(tncs) >= 1, f"{st}")
st, gendoc = call("POST", f"/decision/api/docs/applications/{ref}/generate",
                  {"templateKey": "FACILITY_AGREEMENT", "variables": {}}, actor="cad.officer")
check("document generated in DRAFT (AI suggestion)",
      st == 200 and gendoc["status"] == "DRAFT" and gendoc["advisory"] and len(gendoc["clauseOrder"]) >= 5, f"{st}")
check("generated HTML grounded with the borrower",
      "Meridian Steel" in gendoc["html"] or cp["legalName"].split()[0] in gendoc["html"], "")
doc_id = gendoc["id"]
# Add a clause from the TNC master (REGISTERED_MORTGAGE is seeded)
st, added = call("POST", f"/decision/api/docs/{doc_id}/clauses",
                 {"clauseRef": "insurance", "tncRecordKey": "REGISTERED_MORTGAGE", "position": 4}, actor="cad.officer")
check("clause added from TNC_MASTER", st == 200 and "insurance" in added["clauseOrder"], str(added.get("clauseOrder")))
# Edit a clause
st, edited = call("POST", f"/decision/api/docs/{doc_id}/clauses/covenants/edit",
                  {"text": "Custom covenant text — Helix-edited."}, actor="cad.officer")
check("clause edited (custom text)", st == 200 and "Helix-edited" in edited["html"], "")
# Remove a clause
st, removed = call("DELETE", f"/decision/api/docs/{doc_id}/clauses/governing_law", actor="cad.officer")
# governing_law isn't in the seeded template; expect 404 from a clean clause removal
st, removed2 = call("DELETE", f"/decision/api/docs/{doc_id}/clauses/insurance", actor="cad.officer")
check("clause removed", st == 200 and "insurance" not in removed2["clauseOrder"], str(removed2.get("clauseOrder")))
# Confirm (human gate)
st, confd = call("POST", f"/decision/api/docs/{doc_id}/confirm", {"comment": "wording ok"}, actor="credit.officer")
check("human-confirm gate transitions DRAFT -> CONFIRMED",
      st == 200 and confd["status"] == "CONFIRMED" and confd["confirmedBy"] == "credit.officer", f"{st}")
# Re-confirm conflicts
st, reconf = call("POST", f"/decision/api/docs/{doc_id}/confirm", {}, actor="credit.officer")
check("re-confirm rejected (already confirmed, 409)", reconf is not None and st == 409, f"{st}")
# Edit-after-confirm rejected
st, lock = call("POST", f"/decision/api/docs/{doc_id}/clauses/covenants/edit",
                {"text": "should be blocked"}, actor="cad.officer")
check("confirmed doc is locked from clause edits (409)", lock is not None and st == 409, f"{st}")

print("== 39. AI narrative commentary (grounded · advisory · human-confirm) ==")
sections = ["industry_outlook", "management_quality", "financial_commentary",
            "structure_commentary", "risk_commentary"]
drafted = []
for s in sections:
    st, d = call("POST", f"/decision/api/commentary/applications/{ref}/draft",
                 {"section": s}, actor="analyst.user")
    if st == 200:
        drafted.append(d)
check("all five commentary sections drafted",
      len(drafted) == len(sections) and all(d["status"] == "DRAFT" and d["advisory"] for d in drafted),
      str([d.get("section") for d in drafted]))
fin = next(d for d in drafted if d["section"] == "financial_commentary")
check("financial commentary cites real ratios", "DSCR" in fin["narrative"] or "leverage" in fin["narrative"].lower(),
      fin["narrative"][:100])
check("financial commentary carries source provenance", "ratios" in fin["sources"] or "grade" in fin["sources"],
      str(list(fin["sources"].keys())))
# Bad section name -> 400
st, bad = call("POST", f"/decision/api/commentary/applications/{ref}/draft",
               {"section": "made_up_section"}, actor="analyst.user")
check("unknown section rejected (400)", bad is not None and st == 400, f"{st}")
# Review (human gate)
st, conf = call("POST", f"/decision/api/commentary/{fin['id']}/review",
                {"approve": True, "note": "ok"}, actor="credit.officer")
check("commentary confirmed -> CONFIRMED", st == 200 and conf["status"] == "CONFIRMED", f"{st}")
# Edit-after-confirm rejected
st, le = call("POST", f"/decision/api/commentary/{fin['id']}/edit", {"narrative": "x"}, actor="analyst.user")
check("confirmed commentary locked from edits (409)", le is not None and st == 409, f"{st}")
# Reject path on a different draft
mgmt = next(d for d in drafted if d["section"] == "management_quality")
st, rej = call("POST", f"/decision/api/commentary/{mgmt['id']}/review",
               {"approve": False, "note": "rewrite"}, actor="credit.officer")
check("commentary reject path -> REJECTED", st == 200 and rej["status"] == "REJECTED", f"{st}")
st, listall = call("GET", f"/decision/api/commentary/applications/{ref}")
check("commentary list queryable", st == 200 and len(listall) >= 5, f"{st}")

print("== 40. Pricing scenario optimiser (goal-seek · advisory · authoritative pricing unchanged) ==")
st, before_pricing = call("POST", f"/risk/api/risk/{ref}/pricing", actor="pricing.analyst")
baseline_rate = before_pricing["recommendedRate"]
# Achievable target (modestly above hurdle)
st, opt_hi = call("POST", f"/risk/api/risk/{ref}/pricing/optimise",
                  {"targetRaroc": 0.20, "rateCap": 0.15, "feeBpsCap": 200, "maxCollateralCover": 0.5},
                  actor="pricing.analyst")
check("optimiser returns 4 scenarios with breakdowns",
      st == 200 and len(opt_hi["scenarios"]) == 4 and all("breakdown" in s for s in opt_hi["scenarios"]),
      str(len(opt_hi.get("scenarios", []))))
check("achievable target → recommended scenario meets it",
      opt_hi["achievable"] and opt_hi["recommended"]["meetsTarget"]
      and opt_hi["recommended"]["raroc"] >= opt_hi["targetRaroc"] - 1e-6, str(opt_hi.get("recommended")))
check("baseline scenario reflects current pricing", abs(opt_hi["baselineRate"] - opt_hi["scenarios"][0]["rate"]) < 1e-4, "")
# Unreachable target with tight caps
st, opt_lo = call("POST", f"/risk/api/risk/{ref}/pricing/optimise",
                  {"targetRaroc": 5.0, "rateCap": 0.10, "feeBpsCap": 25, "maxCollateralCover": 0.2},
                  actor="pricing.analyst")
check("infeasible target → not achievable, no scenario meets target",
      not opt_lo["achievable"] and not opt_lo["recommended"]["meetsTarget"], str(opt_lo.get("achievable")))
check("infeasible run still surfaces a recommended scenario (best-effort)",
      opt_lo["recommended"] is not None, "")
# Authoritative pricing must be untouched by the optimiser
st, after_pricing = call("GET", f"/risk/api/risk/{ref}")
check("authoritative recommended rate UNCHANGED by optimiser",
      abs(after_pricing["pricing"]["recommendedRate"] - baseline_rate) < 1e-9,
      f"{baseline_rate} -> {after_pricing.get('pricing', {}).get('recommendedRate')}")

print("== 41. Pricing-exception (concession) approval sub-workflow ==")
st, rs = call("GET", f"/risk/api/risk/{ref}")
rec_rate = rs["pricing"]["recommendedRate"]
# Modest concession -> single level
small = round(rec_rate - 0.003, 6)
st, ex1 = call("POST", f"/risk/api/risk/{ref}/pricing/exception",
               {"proposedRate": small, "reason": "Relationship pricing — strategic client"}, actor="rm.user")
check("modest concession routed single-level (PENDING_L1)",
      st == 200 and ex1["status"] == "PENDING_L1" and ex1["requiredLevels"] == 1 and ex1["concessionBps"] > 0,
      f"{st} levels={ex1.get('requiredLevels')} bps={ex1.get('concessionBps')}")
# SoD: proposer cannot approve own
st, sod = call("POST", f"/risk/api/risk/pricing/exception/{ex1['id']}/decision",
               {"approve": True, "comment": "self"}, actor="rm.user")
check("proposer cannot approve own exception (403)", st == 403, f"{st}")
st, ap1 = call("POST", f"/risk/api/risk/pricing/exception/{ex1['id']}/decision",
               {"approve": True, "comment": "approved"}, actor="credit.officer")
check("single-level concession approved", st == 200 and ap1["status"] == "APPROVED", f"{st}")
# Deep concession -> two-level + below hurdle
deep = round(rec_rate * 0.4, 6)
st, ex2 = call("POST", f"/risk/api/risk/{ref}/pricing/exception",
               {"proposedRate": deep, "reason": "Competitive must-win"}, actor="rm.user")
check("deep concession routes two-level + below hurdle",
      st == 200 and ex2["requiredLevels"] == 2 and ex2["belowHurdle"] and ex2["status"] == "PENDING_L1",
      f"{st} levels={ex2.get('requiredLevels')} below={ex2.get('belowHurdle')}")
st, l1 = call("POST", f"/risk/api/risk/pricing/exception/{ex2['id']}/decision",
              {"approve": True, "comment": "L1 ok"}, actor="credit.head")
check("L1 approves -> PENDING_L2", st == 200 and l1["status"] == "PENDING_L2", f"{st}")
# SoD: L2 must differ from L1
st, sod2 = call("POST", f"/risk/api/risk/pricing/exception/{ex2['id']}/decision",
                {"approve": True}, actor="credit.head")
check("L2 must differ from L1 (403)", st == 403, f"{st}")
st, l2 = call("POST", f"/risk/api/risk/pricing/exception/{ex2['id']}/decision",
              {"approve": True, "comment": "committee ok"}, actor="credit.committee")
check("L2 approves -> APPROVED", st == 200 and l2["status"] == "APPROVED", f"{st}")
# Premium (no concession) auto-approves
st, ex3 = call("POST", f"/risk/api/risk/{ref}/pricing/exception",
               {"proposedRate": round(rec_rate + 0.01, 6), "reason": "Premium pricing"}, actor="rm.user")
check("premium (no concession) auto-approved, zero levels",
      st == 200 and ex3["status"] == "APPROVED" and ex3["requiredLevels"] == 0, f"{st} {ex3.get('requiredLevels')}")
# Authoritative pricing unchanged
st, rs2 = call("GET", f"/risk/api/risk/{ref}")
check("authoritative recommended rate UNCHANGED by exception workflow",
      abs(rs2["pricing"]["recommendedRate"] - rec_rate) < 1e-9, f"{rec_rate} -> {rs2['pricing']['recommendedRate']}")
st, lst = call("GET", f"/risk/api/risk/{ref}/pricing/exception")
check("exceptions queryable by deal", st == 200 and len(lst) >= 3, f"{st}")

print("== 42. Downstream canonical export feeds (ERM · Finance/GL · CPR) ==")
st, erm = call("POST", "/portfolio/api/exports/erm", actor="export.batch")
check("ERM feed generated with obligor risk records",
      st == 200 and erm["destination"] == "ERM" and erm["recordCount"] >= 1, f"{st} {erm.get('recordCount')}")
check("ERM envelope carries typed records + payload version",
      "records" in erm["envelope"] and erm["envelope"]["payloadVersion"] == "1.0", str(list(erm.get("envelope", {}).keys())))
# Idempotent within the as-of day
st, erm2 = call("POST", "/portfolio/api/exports/erm", actor="export.batch")
check("ERM feed idempotent per as-of day (same batch id)", erm2["id"] == erm["id"], f"{erm['id']} vs {erm2.get('id')}")
st, gl = call("POST", "/portfolio/api/exports/finance-gl", actor="export.batch")
check("Finance/GL provisioning feed generated", st == 200 and gl["destination"] == "FINANCE_GL", f"{st}")
st, cprf = call("POST", "/portfolio/api/exports/cpr", actor="export.batch")
check("CPR portfolio-composition feed generated with lines",
      st == 200 and cprf["destination"] == "CPR" and cprf["recordCount"] >= 1, f"{st} {cprf.get('recordCount')}")
st, batches = call("GET", "/portfolio/api/exports/batches")
check("export batches queryable", st == 200 and len(batches) >= 3, f"{st}")
st, detail = call("GET", f"/portfolio/api/exports/batches/{erm['id']}")
check("batch detail returns the full canonical envelope",
      st == 200 and detail["envelope"]["destination"] == "ERM"
      and len(detail["envelope"]["records"]) == erm["recordCount"], f"{st}")

print("== 13. Audit trail ==")
st, audit = call("GET", f"/risk/api/audit/subject?type=Application&id={ref}")
check("risk-service audit trail present", st == 200 and len(audit) >= 2, f"{st}")

print(f"\nRESULT: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
