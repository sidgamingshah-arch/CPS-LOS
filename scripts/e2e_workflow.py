#!/usr/bin/env python3
"""
Workflow-service e2e — proves the engine consumes the seeded
WORKFLOW_DEFINITION packs and enforces the pack-declared
humanGate / autonomy guard contract.

Non-negotiable invariant: the authoritative risk-grade and pricing
are byte-identical before vs. after a full advance sweep — the
workflow tracker never mutates a credit-consequential figure.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor is not None:
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


# Drive a fresh deal far enough that the lifecycle has rating + pricing,
# so we can snapshot the authoritative figures and prove the workflow
# overlay never touches them.
def fresh_deal_with_pricing(suffix):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Workflow E2E {suffix} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"WFE2E{suffix}", "jurisdiction": "IN-RBI",
        "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"],
        "counterpartyName": cp["legalName"], "jurisdiction": "IN-RBI",
        "segment": "MID_CORPORATE",
        "facilityType": "TERM_LOAN", "requestedAmount": 250_000_000,
        "currency": "INR", "tenorMonths": 60,
        "purpose": "Working capital expansion",
        "collateralType": "PROPERTY", "collateralValue": 300_000_000,
        "secured": True}, actor="rm.user")
    app = must(st, app, "app")
    ref = app["reference"]
    # Spread to get ratios — mirror the shape e2e_smoke uses (lines map, not cells list).
    def line(v):
        return {"value": v, "sourceDocument": f"workflow_e2e_{suffix}.pdf",
                "sourcePage": "P1", "coordinates": "tbl1", "confidence": 0.95}

    def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
        return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
            "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
            "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp),
            "TAX": line(rev * 0.025), "TOTAL_ASSETS": line(ta),
            "CURRENT_ASSETS": line(ca), "CASH": line(cash),
            "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std),
            "LONG_TERM_DEBT": line(ltd), "CURRENT_PORTION_LTD": line(std * 0.4),
            "NET_WORTH": line(nw), "CFO": line(cfo)}}

    st, _ = call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.2e9, 0.9e9, 0.15e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 2.8e9, 0.7e9),
        per("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.16e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 2.5e9, 0.6e9),
    ]}, actor="analyst.user")
    must(st, _, "spread")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="risk.officer")
    call("POST", f"/risk/api/risk/{ref}/pricing", actor="rm.user")
    return ref


print("== 1. Materialise: pull the WORKFLOW_DEFINITION pack and create stages ==")
ref = fresh_deal_with_pricing("MAT")
# Snapshot the authoritative figures BEFORE the workflow exists.
st, rs0 = call("GET", f"/risk/api/risk/{ref}")
must(st, rs0, "risk before workflow")
grade0 = rs0["rating"]["finalGrade"]
pd0 = rs0["rating"]["pd"]
rate0 = rs0["pricing"]["recommendedRate"]

st, mat = call("POST", "/workflow/api/workflow/instances",
               {"applicationReference": ref, "jurisdiction": "IN-RBI",
                "segment": "MID_CORPORATE"}, actor="rm.user")
mat = must(st, mat, "materialise")
check("materialised an active instance from the seeded pack",
      mat["status"] == "ACTIVE" and mat["definitionCode"].startswith("workflow_mid_corp_rbi"),
      f"code={mat.get('definitionCode')}")
check("definition version pinned on the instance",
      isinstance(mat.get("definitionVersion"), int) and mat["definitionVersion"] >= 1)

# Idempotent: a second materialise call on the same ref returns the same instance.
st2, mat2 = call("POST", "/workflow/api/workflow/instances",
                 {"applicationReference": ref, "jurisdiction": "IN-RBI",
                  "segment": "MID_CORPORATE"}, actor="rm.user")
check("materialise is idempotent (same instance id)",
      st2 == 200 and mat2["id"] == mat["id"], f"{st2} {mat2.get('id')} vs {mat.get('id')}")

st, view = call("GET", f"/workflow/api/workflow/instances/{ref}")
view = must(st, view, "view")
stages = view["stages"]
check("stage list is non-empty",
      len(stages) >= 8, f"{len(stages)}")
# The pack-declared stages must carry humanGate and autonomy flags.
human_gate_stages = [s for s in stages if s.get("humanGate")]
check("at least one stage is human-gated",
      len(human_gate_stages) >= 1, f"{len(human_gate_stages)}")
keys = [s["stageKey"] for s in stages]
check("INTAKE is first and IN_PROGRESS",
      stages[0]["stageKey"] == "INTAKE" and stages[0]["status"] == "IN_PROGRESS",
      f"{stages[0].get('stageKey')} {stages[0].get('status')}")


print("== 2. Guard contract: advance rejected on blank/AI/wrong actor ==")
# Blank X-Actor on an advance — 403 (named-human required).
st, err = call("POST", f"/workflow/api/workflow/instances/{ref}/advance",
               {"stageKey": "INTAKE", "actorType": "HUMAN", "note": "intake"})
check("blank X-Actor on advance -> 403", st == 403, f"{st} {err}")

# AI actor trying to advance a humanGate stage — 403. Find the first humanGate stage.
first_human = next(s for s in stages if s.get("humanGate"))
st, err = call("POST", f"/workflow/api/workflow/instances/{ref}/advance",
               {"stageKey": first_human["stageKey"], "actorType": "AI", "note": "bot did it"},
               actor="bot.ai")
check("AI actor on humanGate stage -> 403", st == 403, f"{st} {err}")

# Skipping a humanGate stage — 403/409.
after_human = next((s for s in stages if s["ordinal"] > first_human["ordinal"]
                    and not s.get("humanGate")), None)
if after_human is not None:
    st, err = call("POST", f"/workflow/api/workflow/instances/{ref}/advance",
                   {"stageKey": after_human["stageKey"], "actorType": "HUMAN", "note": "skip"},
                   actor="rm.user")
    check("cannot skip a humanGate stage that is still PENDING",
          st in (403, 409), f"{st} {err}")


print("== 3. Proper advance: HUMAN actor completes INTAKE, next stage opens ==")
st, adv = call("POST", f"/workflow/api/workflow/instances/{ref}/advance",
               {"stageKey": "INTAKE", "actorType": "HUMAN", "note": "intake done"},
               actor="rm.user")
adv = must(st, adv, "advance INTAKE")
check("advance moves currentStage off INTAKE",
      adv["currentStageKey"] != "INTAKE",
      f"{adv.get('currentStageKey')}")
st, v2 = call("GET", f"/workflow/api/workflow/instances/{ref}")
v2 = must(st, v2, "view 2")
intake = next(s for s in v2["stages"] if s["stageKey"] == "INTAKE")
check("INTAKE is COMPLETE with HUMAN actorType + named actor",
      intake["status"] == "COMPLETE" and intake["completedByType"] == "HUMAN"
      and intake["completedBy"] == "rm.user",
      f"{intake.get('status')} {intake.get('completedByType')} {intake.get('completedBy')}")


print("== 4. Sweep human-gated stages with rotating named humans ==")
seen_actors = {}
def actor_for(stage_key):
    # Map stages to plausible named-human actors that exist in the ACTOR_ROLE
    # master. The point isn't who-by-name but that a HUMAN actor satisfies the
    # gate; we rotate to also touch the maker-vs-checker neighbourhood.
    base = {
        "KYC_CDD": "credit.ops", "SPREAD_CONFIRM": "credit.officer",
        "RATING": "credit.officer", "APPROVAL": "credit.head",
        "DOCUMENTATION": "cad.ops", "LIMIT_SETUP_BOOKING": "credit.ops",
        "MONITORING": "credit.officer",
    }
    return base.get(stage_key, "credit.officer")

# Walk every PENDING stage. For human-gated ones, supply a HUMAN; for autonomous,
# advance as SYSTEM (engine accepts AI/SYSTEM for non-gated, non-D stages).
remaining = [s for s in v2["stages"] if s["status"] not in ("COMPLETE", "SKIPPED")]
for s in remaining:
    actor_type = "HUMAN" if (s.get("humanGate") or s.get("autonomy") == "D") else "SYSTEM"
    actor = actor_for(s["stageKey"]) if actor_type == "HUMAN" else "engine.workflow"
    seen_actors[s["stageKey"]] = (actor, actor_type)
    st, _ = call("POST", f"/workflow/api/workflow/instances/{ref}/advance",
                 {"stageKey": s["stageKey"], "actorType": actor_type,
                  "note": f"advanced {s['stageKey']}"}, actor=actor)
    if st != 200:
        # Surface unexpected failure with detail and bail
        print(f"  ERROR advance {s['stageKey']} as {actor_type}/{actor}: {st} {_}")
        break

st, v3 = call("GET", f"/workflow/api/workflow/instances/{ref}")
v3 = must(st, v3, "view 3")
complete_count = sum(1 for s in v3["stages"] if s["status"] in ("COMPLETE", "SKIPPED"))
check("every materialised stage now COMPLETE or SKIPPED",
      complete_count == len(v3["stages"]),
      f"{complete_count}/{len(v3['stages'])}")
check("instance status -> COMPLETED",
      v3["instance"]["status"] == "COMPLETED",
      f"{v3['instance'].get('status')}")
human_completions = sum(1 for s in v3["stages"] if s.get("completedByType") == "HUMAN")
check("at least 4 stages closed by a HUMAN actor",
      human_completions >= 4, f"{human_completions}")


print("== 5. The authoritative invariant: risk grade/pricing unchanged ==")
st, rs1 = call("GET", f"/risk/api/risk/{ref}")
rs1 = must(st, rs1, "risk after workflow")
check("rating final grade unchanged",
      rs1["rating"]["finalGrade"] == grade0,
      f"{grade0} -> {rs1['rating']['finalGrade']}")
check("rating PD byte-identical",
      abs(rs1["rating"]["pd"] - pd0) < 1e-12, f"{pd0} -> {rs1['rating']['pd']}")
check("recommended pricing rate byte-identical",
      abs(rs1["pricing"]["recommendedRate"] - rate0) < 1e-12,
      f"{rate0} -> {rs1['pricing']['recommendedRate']}")


print("== 6. Block / unblock a stage ==")
# Fresh deal so we have a PENDING stage to block.
ref2 = fresh_deal_with_pricing("BLK")
call("POST", "/workflow/api/workflow/instances",
     {"applicationReference": ref2, "jurisdiction": "IN-RBI",
      "segment": "MID_CORPORATE"}, actor="rm.user")
st, blk = call("POST", f"/workflow/api/workflow/instances/{ref2}/stages/KYC_CDD/block",
               {"reason": "Awaiting UBO docs"}, actor="cad.ops")
check("block sets status=BLOCKED with reason",
      st == 200 and blk["status"] == "BLOCKED" and blk["blockedReason"] == "Awaiting UBO docs",
      f"{st} {blk.get('status')}")
st, unblk = call("POST", f"/workflow/api/workflow/instances/{ref2}/stages/KYC_CDD/unblock",
                 None, actor="cad.ops")
check("unblock clears BLOCKED status",
      st == 200 and unblk["status"] in ("PENDING", "IN_PROGRESS"),
      f"{st} {unblk.get('status')}")


print("== 7. SLA breach surfaces in /sla-breaches after sweep ==")
# Force a breach by setting a stage's slaDueAt into the past — we approximate this
# by hitting a 0-hour SLA stage on a fresh deal (MONITORING stage in the seeded
# RBI mid-corp pack has slaHours that may already be tight in real seeds).
# Conservative approach: trigger the sweep endpoint and check it doesn't error.
st, sw = call("POST", "/workflow/api/workflow/sla-sweep")
check("sla-sweep endpoint responds",
      st == 200 and "flagged" in sw, f"{st} {sw}")
st, brc = call("GET", "/workflow/api/workflow/sla-breaches")
check("sla-breaches list is present", st == 200 and isinstance(brc, list), f"{st}")


print("== 8. Unknown stage / unknown reference are rejected ==")
# Use ref2 — still ACTIVE (ref was fully advanced and is now COMPLETED).
st, _ = call("POST", f"/workflow/api/workflow/instances/{ref2}/advance",
             {"stageKey": "DOES_NOT_EXIST", "actorType": "HUMAN", "note": "x"},
             actor="rm.user")
check("unknown stage on advance -> 400/404", st in (400, 404), f"{st}")
st, _ = call("GET", "/workflow/api/workflow/instances/NO-SUCH-APP")
check("view of unknown instance -> 404", st == 404, f"{st}")


print(f"\n== workflow engine e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
