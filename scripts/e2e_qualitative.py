#!/usr/bin/env python3
"""
Qualitative scorecard (prompt library + model-doc extraction) + quantitative provenance — e2e.

Proves:
  1. QUANTITATIVE numbers are fetched from the financial template — the rating's
     factor values + provenance block trace straight to the confirmed spread cells.
  2. The qualitative PROMPT LIBRARY is configured as the QUAL_SCORECARD master
     (per-parameter prompt + weight), front-end editable via the masters engine.
  3. A model DOCUMENT can be uploaded and its qualitative parameters + scoring
     prompts extracted into draft records under maker-checker.
  4. The qualitative engine RECOMMENDS scores (advisory, prompt-driven, grounded),
     tagged AI, traceable (prompt persisted), human-confirmed — and the
     authoritative grade is NEVER mutated.
  5. The qualitative capability sits under the AI governance off-switch.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="risk.analyst"):
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


REVENUE = 4_000_000_000
print("== 0. Deal → spread (known figures) → confirm → rate ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Veritas Chemicals Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "QL-VC-1",
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 1_500_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 1_800_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
ref = app["reference"]
call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": {"value": REVENUE, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "COGS": {"value": 2.6e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "OPERATING_EXPENSES": {"value": 0.6e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "DEPRECIATION": {"value": 0.15e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "INTEREST_EXPENSE": {"value": 0.12e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "TAX": {"value": 0.1e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "TOTAL_ASSETS": {"value": 5e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "CURRENT_ASSETS": {"value": 2.2e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "CASH": {"value": 0.6e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "CURRENT_LIABILITIES": {"value": 1.3e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "SHORT_TERM_DEBT": {"value": 0.4e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "LONG_TERM_DEBT": {"value": 1.0e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "CURRENT_PORTION_LTD": {"value": 0.15e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "NET_WORTH": {"value": 2.5e9, "sourceDocument": "AR-FY24", "confidence": 1.0},
        "CFO": {"value": 0.7e9, "sourceDocument": "AR-FY24", "confidence": 1.0}}}]},
     actor="analyst.user")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
st, rating = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
rating = must(st, rating, "rate")
grade0 = rating["finalGrade"]
print(f"    deal {ref}, authoritative grade {grade0}")

print("\n== 1. Quantitative factors are fetched from the financial template ==")
st, rget = call("GET", f"/risk/api/risk/{ref}/rating")
rget = must(st, rget, "get rating")
bd = rget["scoreBreakdown"]
qs = bd.get("quantitativeSource", {})
check("rating declares the financial-template source",
      qs.get("template") == "FINANCIAL_SPREAD", str(qs.get("template")))
check("source marked spread-confirmed", qs.get("spreadConfirmed") is True, str(qs.get("spreadConfirmed")))
fin = qs.get("financialsUsed", {})
check("financials used = the exact spread cells (REVENUE traces to template)",
      abs(fin.get("REVENUE", 0) - REVENUE) < 1, str(fin.get("REVENUE")))
check("leverage factor present and computed from the spread",
      "NET_LEVERAGE" in bd["factors"] and bd["factors"]["NET_LEVERAGE"]["value"] > 0,
      str(bd["factors"].get("NET_LEVERAGE")))

print("\n== 2. Qualitative prompt library is the QUAL_SCORECARD master (front-end editable) ==")
st, qmasters = call("GET", "/config/api/masters/QUAL_SCORECARD")
qmasters = must(st, qmasters, "qual masters")
check("seeded qualitative parameters present (>=5)", len(qmasters) >= 5, str(len(qmasters)))
check("each parameter carries a scoring prompt + weight",
      all(r["payload"].get("prompt") and r["payload"].get("weight") is not None for r in qmasters),
      str(qmasters[0]["payload"].keys()))

print("\n== 3. Upload a model document → extract qualitative parameters + prompts (maker-checker) ==")
model_doc = (
    "INTERNAL CREDIT RATING MODEL — QUALITATIVE MODULE\n"
    "1. Management Quality (25%): assess promoter pedigree, succession, related-party conduct.\n"
    "2. Industry & Market Outlook (20%): cyclicality, demand, regulatory headwinds.\n"
    "3. Business Profile (20%): scale, customer/supplier diversification, operating record.\n"
    "4. Financial Flexibility (20%): funding access, refinancing runway, liquidity buffers.\n"
    "5. Governance and ESG (15%): board independence, disclosure, audit standing, ESG risk.\n")
st, ext = call("POST", "/config/api/model-doc/extract",
               {"text": model_doc, "jurisdiction": None}, actor="config.admin")
ext = must(st, ext, "extract model doc")
check("extractor found the 5 qualitative parameters", ext["parametersFound"] == 5, str(ext["parametersFound"]))
check("stated weights parsed to sum ~1.0", abs(ext["totalWeight"] - 1.0) < 0.02, str(ext["totalWeight"]))
check("each extracted record is PENDING with a generated prompt",
      all(r["status"] == "PENDING_APPROVAL" or r["status"] == "PENDING" for r in ext["submitted"])
      if ext["submitted"] else False, str([r.get("status") for r in ext["submitted"]]))
mgmt = next((r for r in ext["submitted"] if r["recordKey"] == "management_quality"), None)
check("management prompt generated from the doc + tagged MODEL_DOC",
      mgmt and "MODEL_DOC" == mgmt["payload"].get("source") and "0-100" in mgmt["payload"].get("prompt", ""),
      str(mgmt["payload"].get("source") if mgmt else None))
# Approve one extracted parameter (maker-checker: checker != maker).
st, appr = call("POST", f"/config/api/masters/records/{mgmt['id']}/approve", actor="config.checker")
check("extracted parameter approved into the active scorecard", st == 200, f"{st} {appr}")

print("\n== 4. Qualitative engine recommends (advisory) — grade is NOT mutated ==")
st, qa = call("POST", f"/risk/api/risk/{ref}/qualitative/assess", actor="risk.analyst")
qa = must(st, qa, "qualitative assess")
check("composite score + band returned", qa["compositeScore"] > 0 and qa["compositeBand"] in
      ("STRONG", "ADEQUATE", "WEAK"), str(qa.get("compositeBand")))
check("one advisory line per parameter", qa["parameterCount"] == len(qa["parameters"]) >= 5, str(qa["parameterCount"]))
mq = next((p for p in qa["parameters"] if p["parameterKey"] == "management_quality"), None)
check("the approved model-doc prompt is the one used to score management_quality",
      mq and mq["promptSource"] == "MODEL_DOC", str(mq["promptSource"] if mq else None))
fin_flex = next((p for p in qa["parameters"] if p["parameterKey"] == "financial_flexibility"), None)
check("financial_flexibility rationale cites spread-derived ratios (DSCR/current ratio)",
      fin_flex and ("DSCR" in fin_flex["rationale"] or "current ratio" in fin_flex["rationale"]),
      str(fin_flex["rationale"] if fin_flex else None))
check("every line carries the prompt that produced it (traceability)",
      all(p.get("prompt") for p in qa["parameters"]), "")
check("every line is a SUGGESTED advisory score", all(p["status"] == "SUGGESTED" for p in qa["parameters"]), "")
check("view reports the authoritative grade and gradeUnchanged=true",
      qa["authoritativeGrade"] == grade0 and qa["gradeUnchanged"] is True, str(qa.get("authoritativeGrade")))
# The authoritative grade must be byte-identical after the advisory run.
st, rcheck = call("GET", f"/risk/api/risk/{ref}/rating")
check("authoritative grade unchanged after qualitative assess",
      rcheck["finalGrade"] == grade0, f"{grade0} -> {rcheck['finalGrade']}")
st, audit = call("GET", "/risk/api/audit")
check("QUAL_ASSESSED stamped as an AI action",
      any(a.get("eventType") == "QUAL_ASSESSED" and a.get("actorType") == "AI" for a in audit), "")

print("\n== 5. Human confirms (and adjusts) — still advisory, grade still unchanged ==")
target = qa["parameters"][0]
st, body = call("POST", f"/risk/api/risk/qualitative/{target['id']}/confirm",
                {"score": 72, "note": "RM diligence supports a higher mark"}, actor="credit.officer")
body = must(st, body, "confirm")
confirmed = next(p for p in body["parameters"] if p["id"] == target["id"])
check("parameter confirmed with the adjusted score", confirmed["status"] == "CONFIRMED"
      and abs(confirmed["score"] - 72) < 0.01, str(confirmed))
check("composite recomputed off the confirmed score", body["compositeScore"] > 0, str(body["compositeScore"]))
st, rcheck2 = call("GET", f"/risk/api/risk/{ref}/rating")
check("grade STILL unchanged after human confirm", rcheck2["finalGrade"] == grade0, str(rcheck2["finalGrade"]))
st, audit2 = call("GET", "/risk/api/audit")
check("QUAL_CONFIRMED stamped HUMAN", any(a.get("eventType") == "QUAL_CONFIRMED"
      and a.get("actorType") == "HUMAN" for a in audit2), "")

print("\n== 6. Qualitative engine is under the AI governance off-switch ==")
def set_cap(enabled):
    st, rec = call("POST", "/config/api/masters/AI_GOVERNANCE",
                   {"recordKey": "qualitative-scorecard", "jurisdiction": None,
                    "payload": {"enabled": enabled}}, actor="config.admin")
    must(st, rec, "toggle cap")
    call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor="config.checker")
    call("POST", "/risk/api/governance/ai/cache/invalidate", actor="ops")
set_cap(False)
st, body = call("POST", f"/risk/api/risk/{ref}/qualitative/assess", actor="risk.analyst")
check("qualitative assess blocked when capability disabled (403)", st == 403, f"{st} {body}")
set_cap(True)
st, body = call("POST", f"/risk/api/risk/{ref}/qualitative/assess", actor="risk.analyst")
check("qualitative assess works again after re-enable", st == 200, f"{st}")

print("\n== 7. Manual rating override — 100% human; qualitative composite is read-only context ==")
SCALE = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]
st, rnow = call("GET", f"/risk/api/risk/{ref}/rating")
rnow = must(st, rnow, "rating before override")
model_grade = rnow["modelGrade"]
# Read-only qualitative composite the override screen echoes as context.
st, qbefore = call("GET", f"/risk/api/risk/{ref}/qualitative")
qbefore = must(st, qbefore, "qualitative before override")
composite_before = qbefore["compositeScore"]
# The qualitative readout proposes NO notch — overrides are completely manual.
check("qualitative view carries no AI notch suggestion (override is 100% manual)",
      "suggestedNotch" not in qbefore and all("suggestedNotch" not in p for p in qbefore["parameters"]), "")

# A 3-notch override as ANALYST must be blocked (notch-limited by role).
mi = SCALE.index(model_grade)
three = SCALE[min(mi + 3, len(SCALE) - 1)]
st, blocked = call("POST", f"/risk/api/risk/{ref}/rating/override",
                   {"proposedGrade": three, "reasonCode": "OTHER", "note": "too far", "role": "ANALYST"},
                   actor="credit.officer")
check("3-notch ANALYST override blocked (403, notch-limited)", st == 403, f"{st} {blocked}")

# A 1-notch manual override (human-entered grade + reason) is accepted.
one = SCALE[min(mi + 1, len(SCALE) - 1)]
pd_before = rnow["pd"]
st, ov = call("POST", f"/risk/api/risk/{ref}/rating/override",
              {"proposedGrade": one, "reasonCode": "MANAGEMENT_QUALITY",
               "note": "RM diligence + qualitative context reviewed", "role": "CREDIT_OFFICER"},
              actor="credit.officer")
ov = must(st, ov, "manual override")
check("manual override changes the FINAL grade to the human-entered value", ov["finalGrade"] == one, str(ov["finalGrade"]))
check("MODEL grade preserved by override (only the final grade moves)", ov["modelGrade"] == model_grade, str(ov["modelGrade"]))
check("override re-derives the deterministic PD from the new grade",
      ov["overridden"] is True and abs(ov["pd"] - pd_before) > 1e-9, f"{pd_before} -> {ov['pd']}")
st, oaudit = call("GET", "/risk/api/audit")
check("RATING_OVERRIDDEN stamped HUMAN (manual, never AI)",
      any(a.get("eventType") == "RATING_OVERRIDDEN" and a.get("actorType") == "HUMAN" for a in oaudit), "")

# The qualitative composite is pure read-only context — the manual override neither
# reads it nor changes it.
st, qafter = call("GET", f"/risk/api/risk/{ref}/qualitative")
qafter = must(st, qafter, "qualitative after override")
check("qualitative composite is read-only context — unchanged by the manual override",
      abs(qafter["compositeScore"] - composite_before) < 1e-9, f"{composite_before} -> {qafter['compositeScore']}")

print(f"\n== Qualitative scorecard e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
