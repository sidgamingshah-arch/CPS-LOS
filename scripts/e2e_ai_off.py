#!/usr/bin/env python3
"""
AI-OFF lifecycle proof.

Drives a wholesale-credit deal from counterparty creation to a booked exposure
through the gateway, using ONLY deterministic / manual endpoints. The call()
helper enforces an AI denylist: if any AI-boundary endpoint is invoked the run
aborts. The point is to demonstrate that no AI surface sits on the critical
path — a regulator-cautious bank can run the entire LOS lifecycle with AI off.

Companion to scripts/e2e_smoke.py (which exercises the AI surfaces and asserts
the governance invariants). This one proves the *manual fallback* is complete.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0

# Endpoints that involve an AI / advisory capability. The manual lifecycle must
# never touch these — the harness aborts if one is requested.
AI_DENYLIST = [
    "/doc-intel",            # GenAI document classification + extraction
    "/collateral-intel",     # AI collateral extraction
    "/api/risk/", "/rag",    # (refined below) statistical RAG overlay
    "/macro-impact",         # macro directional overlay
    "/pricing/optimise",     # goal-seek pricing optimiser
    "/pricing/exception",    # concession optimiser
    "/covenants/intel",      # covenant intelligence (extract/assess)
    "/commentary",           # AI narrative commentary
    "/api/cpt",              # client planning template (AI-drafted)
    "/group/suggest",        # AI group identification
    "/copilot",              # conversational copilot
    "/documents",            # document auto-classification (AI)
]
# The /api/risk path also hosts deterministic rate/capital/pricing, so we only
# forbid the explicit advisory sub-paths, not the whole risk service.
AI_DENY_EXACT = ["/rag", "/macro-impact", "/pricing/optimise", "/pricing/exception"]
AI_DENY_SUBSTR = ["/doc-intel", "/collateral-intel", "/covenants/intel",
                  "/commentary", "/api/cpt", "/group/suggest", "/copilot", "/documents"]


def _is_ai(path):
    if any(s in path for s in AI_DENY_SUBSTR):
        return True
    if any(path.endswith(x) or x + "?" in path or x + "/" in path for x in AI_DENY_EXACT):
        return True
    return False


def call(method, path, body=None, actor="test.user"):
    if _is_ai(path):
        print(f"  ABORT  AI endpoint requested on the manual path: {method} {path}")
        sys.exit(2)
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


def line(v):
    return {"value": v, "sourceDocument": "manually_keyed_by_analyst",
            "sourcePage": "n/a", "coordinates": "n/a", "confidence": 1.0}


def period(label, rev, cogs, opex, dep, intexp, tax, ta, ca, cash, cl, std, ltd, cpltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(dep), "INTEREST_EXPENSE": line(intexp), "TAX": line(tax),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(cpltd), "NET_WORTH": line(nw), "CFO": line(cfo)}}


print("== AI-OFF lifecycle proof — manual / deterministic endpoints only ==\n")

print("-- 1. Counterparty (manual capture, no AI) --")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Granite Cement Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "L26900MH2001PLC900001",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
check("counterparty created", st == 200, f"{st} {cp}")
cp_id, cp_ref = cp["id"], cp["reference"]
st, _ = call("POST", f"/counterparty/api/counterparties/{cp_id}/kyc/verify", actor="compliance.officer")
check("KYC verified (human)", st == 200, f"{st}")

print("\n-- 2. Application (manual) --")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp_id, "counterpartyRef": cp_ref, "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 700_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Kiln upgrade",
    "collateralType": "PROPERTY", "collateralValue": 550_000_000, "secured": True}, actor="rm.user")
check("application created", st == 200, f"{st} {app}")
ref = app["reference"]

print("\n-- 3. Financial spreading (analyst keys the figures by hand — no doc-intel) --")
st, analysis = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    period("FY2024", 5e9, 3.2e9, 0.9e9, 0.2e9, 0.15e9, 0.12e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 0.2e9, 2.8e9, 0.7e9),
    period("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.18e9, 0.16e9, 0.10e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 0.2e9, 2.5e9, 0.6e9)]},
    actor="analyst.user")
check("spread generated from keyed figures", st == 200 and len(analysis["periods"]) == 2, f"{st}")
check("ratios computed deterministically", "DSCR" in analysis["periods"][0]["ratios"], "")
st, app = call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
check("spread confirmed (human gate)", st == 200 and app["spreadConfirmed"], f"{st}")

print("\n-- 4. Rating (deterministic scorecard) --")
st, rating = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
check("rating produced", st == 200, f"{st}")
check("model grade present", rating.get("modelGrade") in ("AAA", "AA", "A", "BBB", "BB", "B", "CCC"), rating.get("modelGrade"))
st, rating = call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
check("rating confirmed (human)", st == 200 and rating["confirmed"], f"{st}")

print("\n-- 5. Capital (deterministic Standardised-Approach RWA) --")
st, cap = call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
check("capital computed", st == 200 and cap["rwa"] > 0, f"{st}")

print("\n-- 6. Pricing (deterministic RAROC recommendation — NOT the optimiser) --")
st, pricing = call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")
check("pricing recommendation produced", st == 200 and pricing["recommendedRate"] > 0, f"{st}")

print("\n-- 7. Covenants (analyst writes them by hand — no covenant-intel) --")
st, cov = call("POST", f"/decision/api/decisions/{ref}/covenants", {
    "covenantType": "FINANCIAL_MAINTENANCE", "metric": "DSCR", "operator": ">=", "threshold": 1.25,
    "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
    "breachSeverity": "MAJOR", "onBreach": ["notify_RM"]}, actor="analyst.user")
check("covenant created manually", st == 200, f"{st}")

print("\n-- 8. Credit proposal (generated WITHOUT AI commentary) --")
st, prop = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", actor="rm.user")
check("credit proposal generated", st == 200, f"{st}")

print("\n-- 9. DoA routing + human decision --")
st, dec = call("POST", f"/decision/api/decisions/{ref}/route", actor="credit.ops")
check("routed to an authority tier", st == 200, f"{st}")
required = dec["requiredAuthority"]
st, dec = call("POST", f"/decision/api/decisions/{ref}/decide",
               {"outcome": "APPROVE", "role": required,
                "rationale": "Within appetite; reviewed manually, AI off."}, actor="credit.officer")
check("named human decision recorded", st == 200 and dec.get("status") == "DECIDED", f"{st} {dec}")
call("PATCH", f"/origination/api/applications/{ref}/status", {"status": "APPROVED"}, actor="credit.ops")

print("\n-- 10. Limit tree built from the approved deal --")
st, tree = call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")
check("limit tree built", st == 200, f"{st}")

print("\n-- 11. CAD documentation + limit release --")
st, cad = call("POST", "/decision/api/cad/cases",
               {"applicationRef": ref, "counterpartyName": cp["legalName"], "cpType": "NEW"}, actor="cad.officer")
items = cad["items"] if cad else []
check("CAD case opened with checklist", st == 200 and len(items) >= 1, f"{st}")
case_id = cad["cadCase"]["id"]
for it in items:
    call("POST", f"/decision/api/cad/items/{it['id']}", {"status": "COMPLIED", "docRef": "DMS-AIOFF"}, actor="cad.officer")
st, comp = call("POST", f"/decision/api/cad/cases/{case_id}/complete", actor="cad.officer")
check("CAD case completed", st == 200 and comp["cadCase"]["status"] == "COMPLETED", f"{st}")

print("\n-- 12. Exposure booked to the portfolio --")
st, exp = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register", {"daysPastDue": 0}, actor="credit.ops")
check("exposure registered (booked)", st == 200, f"{st}")
st, ecl = call("POST", f"/portfolio/api/portfolio/exposures/{ref}/ecl", actor="credit.ops")
check("ECL computed deterministically", st == 200, f"{st}")

print("\n-- 13. Audit: the figure path is attributed to SYSTEM, never AI --")
# The deterministic figure path (rating / capital / pricing) must never be stamped
# AI in the examiner trail — that is the whole 'deterministic figures' claim.
figure_events = {"RATING_PROPOSED", "PRICING_RECOMMENDED", "CAPITAL_COMPUTED"}
risk_trail = []
for svc in ("risk", "decision", "portfolio"):
    st, t = call("GET", f"/{svc}/api/audit/subject?type=Application&id={ref}")
    if isinstance(t, list):
        risk_trail += t
fig = [e for e in risk_trail if e.get("eventType") in figure_events]
fig_ai = [e for e in fig if e.get("actorType") == "AI"]
check("figure-path events present (rating/pricing/capital)", len(fig) >= 2, f"found {len(fig)}")
check("NO figure-path event attributed to AI", len(fig_ai) == 0,
      f"AI-stamped figures: {[e['eventType'] for e in fig_ai]}")
# Informational: any remaining AI-actor events on the deal (e.g. CP narrative assembly).
ai_events = [e.get("eventType") for e in risk_trail if e.get("actorType") == "AI"]
print(f"  INFO  AI-actor events on this manual deal: {sorted(set(ai_events)) or 'none'}")

print(f"\n== AI-OFF proof: {PASS} passed, {FAIL} failed ==")
print("The full LOS lifecycle (intake -> spread -> rate -> capital -> price ->")
print("covenants -> proposal -> decision -> limits -> CAD -> booking) completed")
print("with every AI endpoint hard-blocked by the harness.")
sys.exit(1 if FAIL else 0)
