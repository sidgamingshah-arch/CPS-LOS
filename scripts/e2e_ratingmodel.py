#!/usr/bin/env python3
"""
Rating model of record — e2e (P1 item 10).

By default the configurable scoring model is purely ADVISORY: its weighted
composite never moves the authoritative grade (proved by e2e_modelconfig.py).
This test exercises the OPT-IN path: when a MODEL_DEFINITION is flagged
`ratingModelOfRecord: true` AND a named human has CONFIRMED a model instance
for the deal, the model composite becomes the authoritative grade — mapped
through the SAME MasterScale score->grade ladder the deterministic scorecard
uses (0-100 composite -> AAA..D). The figure path stays deterministic and
reproducible; only its SOURCE moves from SCORECARD to MODEL_OF_RECORD.

Proves:
  1. A model-of-record deal with NO confirmed instance falls back to the
     deterministic scorecard (gradeSource=SCORECARD) — the feature is inert
     until a human confirms.
  2. Confirming the model instance flips the authoritative grade to the
     composite-derived grade (composite 40 -> MasterScale CCC),
     gradeSource=MODEL_OF_RECORD, modelScore=composite, with model provenance
     in the breakdown.
  3. CONTROL: an identical model NOT flagged model-of-record stays advisory —
     confirming its instance leaves the scorecard grade byte-identical
     (gradeSource=SCORECARD). The flag alone is the gate.
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
    return {"value": v, "sourceDocument": "rm.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def deal(suffix, segment, sector):
    """Create a rated-ready deal (cp -> app -> spread -> confirm) but do NOT rate it."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"RatingModel {suffix} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"RM{suffix}", "jurisdiction": "IN-RBI",
        "segment": segment, "sector": sector, "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": segment, "facilityType": "TERM_LOAN",
        "requestedAmount": 250_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "WC",
        "collateralType": "PROPERTY", "collateralValue": 300_000_000, "secured": True}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.2e9, 0.9e9, 0.15e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 2.8e9, 0.7e9),
        per("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.16e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 2.5e9, 0.6e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    return ref


def publish_model(record_key, segment, sector, model_of_record):
    """Publish a single-dropdown MODEL_DEFINITION via maker-checker and activate it.

    Both test models pin (segment, sector) so they resolve uniquely for THIS suite's
    deals and never collide with another suite's segment-only model definitions
    (e.g. e2e_modelconfig publishes an e2e-custom-v1 for TRADE_FINANCE)."""
    payload = {
        "modelKey": record_key,
        "displayName": f"{sector} Rating ({'of record' if model_of_record else 'advisory'})",
        "selector": {"segment": segment, "sector": sector},
        "constraints": {"minAnswered": 1, "mandatory": ["risk_view"]},
        "scoring": {"bands": [{"band": "STRONG", "min": 67}, {"band": "ADEQUATE", "min": 46},
                              {"band": "WEAK", "min": 0}]},
        "sections": [{"key": "OVERALL", "kind": "QUALITATIVE", "label": "Overall", "weight": 1.0,
                      "questions": [{"key": "risk_view", "type": "DROPDOWN", "label": "Credit view",
                                     "weight": 1.0, "required": True,
                                     "options": [{"label": "Strong", "score": 80},
                                                 {"label": "Weak", "score": 40}]}]}],
    }
    if model_of_record:
        payload["ratingModelOfRecord"] = True
    st, sub = call("POST", "/config/api/masters/MODEL_DEFINITION",
                   {"recordKey": record_key, "payload": payload}, actor="master.maker")
    sub = must(st, sub, f"submit {record_key}")
    st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.checker")
    must(st, ap, f"approve {record_key}")
    return record_key


def source_of(rating):
    return (rating.get("scoreBreakdown") or {}).get("gradeSource")


print("== 1. Publish two single-dropdown models: one model-of-record, one advisory ==")
publish_model("pf-rating-mor-v1", "PROJECT_FINANCE", "INFRASTRUCTURE", model_of_record=True)
publish_model("pf-rating-adv-v1", "PROJECT_FINANCE", "RENEWABLES", model_of_record=False)

st, r_pf = call("GET", "/config/api/models/resolve?jurisdiction=IN-RBI&sector=INFRASTRUCTURE&segment=PROJECT_FINANCE")
r_pf = must(st, r_pf, "resolve PF/INFRASTRUCTURE")
check("PF/INFRASTRUCTURE resolves the model-of-record definition",
      r_pf["payload"]["modelKey"] == "pf-rating-mor-v1", str(r_pf["payload"].get("modelKey")))
check("its payload carries ratingModelOfRecord=true",
      r_pf["payload"].get("ratingModelOfRecord") is True, str(r_pf["payload"].get("ratingModelOfRecord")))
st, r_adv = call("GET", "/config/api/models/resolve?jurisdiction=IN-RBI&sector=RENEWABLES&segment=PROJECT_FINANCE")
r_adv = must(st, r_adv, "resolve PF/RENEWABLES")
check("PF/RENEWABLES resolves the advisory (non-of-record) definition",
      r_adv["payload"]["modelKey"] == "pf-rating-adv-v1"
      and not r_adv["payload"].get("ratingModelOfRecord"), str(r_adv["payload"].get("modelKey")))


print("\n== 2. Model-of-record deal: NO confirmed instance -> scorecard fallback ==")
ref = deal("PF", "PROJECT_FINANCE", "INFRASTRUCTURE")
st, r1 = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
r1 = must(st, r1, "rate #1 (no instance)")
scorecard_grade = r1["finalGrade"]
check("no confirmed model instance -> gradeSource=SCORECARD (feature inert)",
      source_of(r1) == "SCORECARD", str(source_of(r1)))
check("scorecard produced a healthy grade (not the model's CCC)",
      scorecard_grade not in (None, "CCC", "CC", "C", "D"), str(scorecard_grade))


print("\n== 3. Confirm the model instance (composite 40) -> flips grade to MODEL_OF_RECORD ==")
st, view = call("POST", f"/risk/api/risk/{ref}/model/resolve", actor="risk.analyst")
view = must(st, view, "resolve model on deal")
check("deal resolves its model-of-record definition",
      view["modelKey"] == "pf-rating-mor-v1", str(view.get("modelKey")))
st, ans = call("POST", f"/risk/api/risk/{ref}/model/answer",
               {"answers": [{"questionKey": "risk_view", "value": "Weak"}]}, actor="risk.analyst")
ans = must(st, ans, "answer risk_view=Weak")
check("single dropdown answer -> advisory composite 40",
      ans["compositeScore"] == 40, str(ans.get("compositeScore")))
st, conf = call("POST", f"/risk/api/risk/{ref}/model/confirm", actor="credit.officer")
conf = must(st, conf, "confirm model instance")
check("model instance CONFIRMED by a named human", conf["status"] == "CONFIRMED", str(conf.get("status")))

st, r2 = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
r2 = must(st, r2, "rate #2 (confirmed instance)")
check("confirmed model-of-record -> gradeSource=MODEL_OF_RECORD",
      source_of(r2) == "MODEL_OF_RECORD", str(source_of(r2)))
check("authoritative grade now the composite ladder result (40 -> CCC)",
      r2["finalGrade"] == "CCC", str(r2.get("finalGrade")))
check("modelScore carries the composite (40.0)", r2["modelScore"] == 40.0, str(r2.get("modelScore")))
check("breakdown records the model provenance",
      isinstance((r2.get("scoreBreakdown") or {}).get("modelOfRecord"), dict),
      str((r2.get("scoreBreakdown") or {}).get("modelOfRecord")))
mor = (r2.get("scoreBreakdown") or {}).get("modelOfRecord") or {}
check("provenance names the confirming human + model key",
      mor.get("confirmedBy") == "credit.officer" and mor.get("modelKey") == "pf-rating-mor-v1",
      str(mor))

st, summary = call("GET", f"/risk/api/risk/{ref}")
summary = must(st, summary, "risk summary")
check("latest authoritative rating reads CCC (model of record)",
      summary["rating"]["finalGrade"] == "CCC", str(summary["rating"].get("finalGrade")))


print("\n== 4. CONTROL: identical model NOT flagged of-record stays advisory ==")
cref = deal("PFADV", "PROJECT_FINANCE", "RENEWABLES")
st, c1 = call("POST", f"/risk/api/risk/{cref}/rate", actor="analyst.user")
c1 = must(st, c1, "control rate #1")
control_grade = c1["finalGrade"]
check("control deal rated by scorecard", source_of(c1) == "SCORECARD", str(source_of(c1)))

call("POST", f"/risk/api/risk/{cref}/model/resolve", actor="risk.analyst")
call("POST", f"/risk/api/risk/{cref}/model/answer",
     {"answers": [{"questionKey": "risk_view", "value": "Weak"}]}, actor="risk.analyst")
st, cconf = call("POST", f"/risk/api/risk/{cref}/model/confirm", actor="credit.officer")
cconf = must(st, cconf, "control confirm")
check("control model instance CONFIRMED (composite 40, advisory)",
      cconf["status"] == "CONFIRMED" and cconf["compositeScore"] == 40,
      f"{cconf.get('status')} {cconf.get('compositeScore')}")

st, c2 = call("POST", f"/risk/api/risk/{cref}/rate", actor="analyst.user")
c2 = must(st, c2, "control rate #2")
check("confirmed advisory instance does NOT move the grade (still SCORECARD)",
      source_of(c2) == "SCORECARD", str(source_of(c2)))
check("control authoritative grade byte-identical after confirm",
      c2["finalGrade"] == control_grade, f"{control_grade} -> {c2.get('finalGrade')}")
check("control grade is NOT the model composite CCC (flag alone is the gate)",
      c2["finalGrade"] != "CCC", str(c2.get("finalGrade")))


print(f"\n== rating model of record e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
