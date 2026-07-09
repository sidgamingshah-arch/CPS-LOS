#!/usr/bin/env python3
"""
Model configuration engine — e2e.

Replaces the old QUAL_SCORECARD with a configurable scoring model: selected by
jurisdiction/sector/segment, holding SECTIONS (qualitative + quantitative) of
typed questions (DROPDOWN / INPUT / NUMBER / ITERATIVE) with visibility rules,
min/max-answered constraints, master-driven options, and a deterministic weighted
composite -> band. Advisory throughout — the authoritative grade never moves.

Proves: resolution by sector/segment; section structure; visibility/conditional
rules; master-driven dropdown options; iterative groups; min/max + mandatory
constraints; AI suggest (governed off-switch); weighted composite; human confirm;
maker-checker on the definition; and the grade-unchanged invariant.
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
    return {"value": v, "sourceDocument": "mc.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def rated_deal(suffix, segment="MID_CORPORATE", sector="SERVICES"):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"ModelCfg {suffix} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"MC{suffix}", "jurisdiction": "IN-RBI",
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

    def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
        return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
            "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
            "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
            "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
            "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
            "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}

    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.2e9, 0.9e9, 0.15e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 2.8e9, 0.7e9),
        per("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.16e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 2.5e9, 0.6e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    return ref


print("== 1. Definition seeds + resolution by sector/segment (config) ==")
st, defs = call("GET", "/config/api/masters/MODEL_DEFINITION")
defs = must(st, defs, "model definitions")
keys = sorted(d["recordKey"] for d in defs if d.get("status") == "ACTIVE")
check("model definitions seeded (corporate + mfg + sme)",
      "corporate-rating-v1" in keys and "corporate-rating-mfg-v1" in keys and "sme-rating-v1" in keys, str(keys))

st, r_default = call("GET", "/config/api/models/resolve?jurisdiction=IN-RBI&segment=MID_CORPORATE")
r_default = must(st, r_default, "resolve default")
check("MID_CORPORATE (no sector) -> default corporate model",
      r_default["payload"]["modelKey"] == "corporate-rating-v1", str(r_default["payload"].get("modelKey")))

st, r_mfg = call("GET", "/config/api/models/resolve?jurisdiction=IN-RBI&sector=MANUFACTURING&segment=MID_CORPORATE")
r_mfg = must(st, r_mfg, "resolve mfg")
check("sector=MANUFACTURING -> sector-specific model wins (most specific)",
      r_mfg["payload"]["modelKey"] == "corporate-rating-mfg-v1", str(r_mfg["payload"].get("modelKey")))

st, r_sme = call("GET", "/config/api/models/resolve?jurisdiction=IN-RBI&segment=SME")
r_sme = must(st, r_sme, "resolve sme")
check("segment=SME -> SME model wins", r_sme["payload"]["modelKey"] == "sme-rating-v1",
      str(r_sme["payload"].get("modelKey")))


print("== 2. Render auto-resolves the model for a deal (sections present) ==")
ref = rated_deal("A")
st, view = call("GET", f"/risk/api/risk/{ref}/model")
view = must(st, view, "render")
check("non-sector deal resolves the default corporate model",
      view["modelKey"] == "corporate-rating-v1", str(view.get("modelKey")))
section_kinds = sorted(s["kind"] for s in view["sections"])
check("model has QUALITATIVE + QUANTITATIVE sections",
      section_kinds == ["QUALITATIVE", "QUANTITATIVE"], str(section_kinds))
check("advisory + grade carried (unchanged)", view["advisory"] and view["gradeUnchanged"])
grade0 = view.get("authoritativeGrade")

# Sector now flows from the counterparty through origination, so a MANUFACTURING
# borrower auto-resolves the sector-specific model with NO explicit override.
ref_mfg_auto = rated_deal("MFGAUTO", sector="MANUFACTURING")
st, vauto = call("GET", f"/risk/api/risk/{ref_mfg_auto}/model")
vauto = must(st, vauto, "mfg auto render")
check("MANUFACTURING borrower auto-resolves the sector model through origination",
      vauto["modelKey"] == "corporate-rating-mfg-v1", str(vauto.get("modelKey")))


def q(view, key):
    for s in view["sections"]:
        for qq in s["questions"]:
            if qq["key"] == key:
                return qq
    return None


print("== 3. Question types present (dropdown / number / iterative / master-driven) ==")
check("DROPDOWN question present", q(view, "mgmt_quality") and q(view, "mgmt_quality")["type"] == "DROPDOWN")
check("NUMBER question present", q(view, "leverage") and q(view, "leverage")["type"] == "NUMBER")
check("ITERATIVE question present", q(view, "related_parties") and q(view, "related_parties")["type"] == "ITERATIVE")
check("conditional question present (succession, visibleWhen)",
      q(view, "succession") and q(view, "succession").get("visibleWhen"))


print("== 4. Visibility / conditional rule ==")
# succession is visibleWhen mgmt_quality != 'Weak'. Answer mgmt_quality=Weak -> hidden.
st, v2 = call("POST", f"/risk/api/risk/{ref}/model/answer",
              {"answers": [{"questionKey": "mgmt_quality", "value": "Weak"}]}, actor="credit.officer")
v2 = must(st, v2, "answer weak")
check("succession hidden when management is Weak", q(v2, "succession")["visible"] is False,
      str(q(v2, "succession").get("visible")))
# Flip to Strong -> succession visible again.
st, v3 = call("POST", f"/risk/api/risk/{ref}/model/answer",
              {"answers": [{"questionKey": "mgmt_quality", "value": "Strong"}]}, actor="credit.officer")
v3 = must(st, v3, "answer strong")
check("succession visible when management is not Weak", q(v3, "succession")["visible"] is True)
check("mgmt_quality dropdown scored (Strong -> 90)", q(v3, "mgmt_quality")["score"] == 90,
      str(q(v3, "mgmt_quality").get("score")))


print("== 5. Master-driven options (ESG_BAND) on the manufacturing model ==")
st, vm = call("POST", f"/risk/api/risk/{ref}/model/resolve?sector=MANUFACTURING", actor="credit.officer")
vm = must(st, vm, "resolve mfg for deal")
esg = q(vm, "esg_band")
check("mfg model has the master-driven esg_band question", esg is not None and esg.get("optionsFromMaster") == "ESG_BAND",
      str(esg.get("optionsFromMaster") if esg else None))
opt_labels = sorted(o["label"] for o in (esg.get("options") or [])) if esg else []
check("esg_band options resolved from the ESG_BAND master",
      opt_labels == ["Adequate", "Lagging", "Leading"], str(opt_labels))
# switch back to the default model for the rest
call("POST", f"/risk/api/risk/{ref}/model/resolve", actor="credit.officer")


print("== 6. Iterative (repeating group) capture ==")
st, vit = call("POST", f"/risk/api/risk/{ref}/model/answer", {"answers": [
    {"questionKey": "related_parties", "itemIndex": 0, "itemFieldKey": "name", "value": "Parent Co"},
    {"questionKey": "related_parties", "itemIndex": 0, "itemFieldKey": "amount", "value": "120"},
    {"questionKey": "related_parties", "itemIndex": 1, "itemFieldKey": "name", "value": "Sister Co"},
    {"questionKey": "related_parties", "itemIndex": 1, "itemFieldKey": "amount", "value": "45"},
]}, actor="credit.officer")
vit = must(st, vit, "answer iterative")
rp = q(vit, "related_parties")
check("iterative group captured 2 items", len(rp.get("items") or []) == 2, str(rp.get("items")))


print("== 7. Parameter sources: module-sourced vs standalone (model-scored) ==")
# Definition declares quantitative params as MODULE (RATIO:*) and qualitative as STANDALONE.
st, fresh = call("GET", f"/risk/api/risk/{ref}/model")
lev_q = q(fresh, "leverage")
mgmt_q = q(fresh, "mgmt_quality")
check("leverage is MODULE-sourced from the spreading ratio",
      lev_q.get("source") == "RATIO:NET_LEVERAGE", str(lev_q.get("source")))
check("mgmt_quality is STANDALONE (not fed by another module)",
      mgmt_q.get("source") == "STANDALONE", str(mgmt_q.get("source")))

print("== 7b. Auto-score: pull module params + score standalone params ==")
st, sg = call("POST", f"/risk/api/risk/{ref}/model/suggest", actor="risk.analyst")
sg = must(st, sg, "auto-score")
check("auto-score produced a composite + band",
      sg["compositeScore"] > 0 and sg["compositeBand"] in ("STRONG", "ADEQUATE", "WEAK"),
      f"{sg.get('compositeScore')} {sg.get('compositeBand')}")
# Module-sourced leverage pulled from the deal's NET_LEVERAGE ratio (SYSTEM source).
lev = q(sg, "leverage")
check("module param (leverage) auto-pulled from the spreading ratio + scored",
      lev.get("answer") is not None and lev.get("score") is not None and lev.get("answerSource") == "SYSTEM",
      f"answer={lev.get('answer')} score={lev.get('score')} src={lev.get('answerSource')}")
# Standalone param (mgmt_quality) scored by the model's recommender with a rationale (AI source).
mgmt = q(sg, "mgmt_quality")
check("standalone param (mgmt_quality) model-scored with an AI rationale",
      mgmt.get("answer") is not None and mgmt.get("score") is not None
      and mgmt.get("answerSource") == "AI" and bool(mgmt.get("rationale")),
      f"answer={mgmt.get('answer')} src={mgmt.get('answerSource')} rationale={str(mgmt.get('rationale'))[:60]}")
check("standalone rationale is grounded (cites grade/ratios)",
      mgmt.get("rationale") and ("grade" in mgmt["rationale"].lower() or "ratio" in mgmt["rationale"].lower()),
      str(mgmt.get("rationale")))


print("== 8. Constraints: mandatory + min-answered gate confirmation ==")
# Fresh deal, answer nothing -> confirm must fail (mandatory unmet).
ref2 = rated_deal("B")
call("GET", f"/risk/api/risk/{ref2}/model")  # auto-resolve
st, blocked = call("POST", f"/risk/api/risk/{ref2}/model/confirm", actor="credit.officer")
check("confirm blocked when mandatory questions unanswered", blocked is not None and st == 400, f"{st}")
# Suggest fills the scored mandatory questions; confirm should now pass (or report remaining).
call("POST", f"/risk/api/risk/{ref2}/model/suggest", actor="risk.analyst")
# business_profile is mandatory + dropdown; ensure answered.
call("POST", f"/risk/api/risk/{ref2}/model/answer",
     {"answers": [{"questionKey": "mgmt_quality", "value": "Strong"},
                  {"questionKey": "business_profile", "value": "Diversified / resilient"}]}, actor="credit.officer")
st, conf = call("POST", f"/risk/api/risk/{ref2}/model/confirm", actor="credit.officer")
check("confirm succeeds once constraints satisfied", st == 200 and conf["status"] == "CONFIRMED",
      f"{st} {conf.get('status') if isinstance(conf, dict) else conf} {conf.get('errors') if isinstance(conf, dict) else ''}")
check("confirmed by a named human", conf.get("confirmedBy") == "credit.officer", str(conf.get("confirmedBy")))


print("== 9. Governance off-switch on the AI suggest capability ==")
# Disable model-scoring (default jurisdiction) and prove suggest -> 403, then re-enable.
st, gov = call("POST", "/config/api/masters/AI_GOVERNANCE",
               {"recordKey": "model-scoring", "payload": {"enabled": False}}, actor="master.maker")
gov = must(st, gov, "disable submit")
call("POST", f"/config/api/masters/records/{gov['id']}/approve", actor="master.checker")
call("POST", "/risk/api/governance/ai/cache/invalidate")
st, off = call("POST", f"/risk/api/risk/{ref}/model/suggest", actor="risk.analyst")
check("suggest blocked when model-scoring disabled (403)", st == 403, f"{st}")
st, gov2 = call("POST", "/config/api/masters/AI_GOVERNANCE",
                {"recordKey": "model-scoring", "payload": {"enabled": True}}, actor="master.maker")
call("POST", f"/config/api/masters/records/{gov2['id']}/approve", actor="master.checker")
call("POST", "/risk/api/governance/ai/cache/invalidate")
st, on = call("POST", f"/risk/api/risk/{ref}/model/suggest", actor="risk.analyst")
check("suggest works again once re-enabled", st == 200, f"{st}")


print("== 10. The advisory invariant: authoritative grade unchanged ==")
st, rs = call("GET", f"/risk/api/risk/{ref}")
rs = must(st, rs, "risk summary")
check("authoritative final grade byte-identical after all model activity",
      grade0 is None or rs["rating"]["finalGrade"] == grade0,
      f"{grade0} -> {rs['rating']['finalGrade']}")


print("== 11. MODEL_DEFINITION is governed (maker-checker / SoD) ==")
new_model = {"modelKey": "e2e-custom-v1", "displayName": "E2E Custom",
             "selector": {"segment": "TRADE_FINANCE"},
             "constraints": {"minAnswered": 1, "mandatory": ["q1"]},
             "scoring": {"bands": [{"band": "STRONG", "min": 67}, {"band": "WEAK", "min": 0}]},
             "sections": [{"key": "QUALITATIVE", "kind": "QUALITATIVE", "label": "Q", "weight": 1.0,
                           "questions": [{"key": "q1", "type": "DROPDOWN", "label": "Lead", "weight": 1.0,
                                          "required": True, "options": [{"label": "Yes", "score": 90},
                                                                        {"label": "No", "score": 20}]}]}]}
st, sub = call("POST", "/config/api/masters/MODEL_DEFINITION",
               {"recordKey": "e2e-custom-v1", "payload": new_model}, actor="master.maker")
sub = must(st, sub, "submit model")
check("new model submitted PENDING_APPROVAL", sub["status"] == "PENDING_APPROVAL", str(sub.get("status")))
st, self_ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.maker")
check("self-approval blocked (SoD) -> 403", st == 403, f"{st}")
st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.checker")
check("different checker approves -> ACTIVE", st == 200 and ap["status"] == "ACTIVE", f"{st}")
st, r_tf = call("GET", "/config/api/models/resolve?jurisdiction=IN-RBI&segment=TRADE_FINANCE")
check("the newly-approved model now resolves for its segment",
      st == 200 and r_tf["payload"]["modelKey"] == "e2e-custom-v1", f"{st}")


print(f"\n== model config engine e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
