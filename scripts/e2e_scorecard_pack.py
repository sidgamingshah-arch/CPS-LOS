#!/usr/bin/env python3
"""
SCORECARD rule pack — e2e.

The RatingEngine's scorecard factors (keys/weights/band bounds/inverse/source) and the
score->grade cut-points now come from a versioned, DUAL-SIGNED SCORECARD rule pack in
config-service instead of hardcoded constants. The seeded pack is a verbatim copy of
the constants the engine shipped with, so the move from code to config never moves a
grade (behaviour-preserving — the hard constraint).

Proves:
  (a) a fresh obligor rates off the seeded pack: the breakdown's per-factor weights
      equal the pack's weights and totalScore == sum(bandScore x weight) exactly —
      the figure path stays deterministic and pack-traceable;
  (b) the rating breakdown stamps `scorecardSource: "<pack> vN"` (never BUILT_IN when
      config-service is up) — additive provenance, no existing key touched;
  (c) a NEW pack version with one weight visibly moved (DSCR 0.18 -> 0.30) can be
      authored + dual-signed by two distinct humans (maker-checker preserved) and the
      resolver serves it immediately;
  (d) a SECOND obligor with IDENTICAL financials then scores differently from what the
      old weights would give — and lands exactly where the new weights put it (the
      weights are config, not code);
  (e) the FIRST obligor's STORED rating is byte-identical after the re-authoring —
      re-weighting a pack never rewrites a booked figure;
  finally: the seeded weights are restored (new version, verbatim payload) and a control
      obligor scores identically to (a) — grade parity for every later suite.
"""
import copy
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
RUN = str(int(time.time()))[-6:]


def call(method, path, body=None, actor="test.user"):
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
    return {"value": v, "sourceDocument": "sc.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def deal(suffix):
    """cp -> app -> spread -> confirm. Every deal posts IDENTICAL financials so any
    score difference between two obligors can only come from the scorecard pack."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"ScorecardPack {suffix}{RUN} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"SCP{suffix}{RUN}", "jurisdiction": "IN-RBI",
        "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, f"cp {suffix}")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 250_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "WC",
        "collateralType": "PROPERTY", "collateralValue": 300_000_000, "secured": True}, actor="rm.user")
    ref = must(st, app, f"app {suffix}")["reference"]
    must(*call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.2e9, 0.9e9, 0.15e9, 6e9, 2.5e9, 0.6e9, 1.5e9, 0.5e9, 1.2e9, 2.8e9, 0.7e9),
        per("FY2023", 4.5e9, 2.95e9, 0.85e9, 0.16e9, 5.6e9, 2.3e9, 0.5e9, 1.45e9, 0.55e9, 1.25e9, 2.5e9, 0.6e9),
    ]}, actor="analyst.user"), f"spread {suffix}")
    must(*call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user"),
         f"confirm {suffix}")
    return ref


def rate(ref):
    st, r = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    return must(st, r, f"rate {ref}")


def author_scorecard(payload):
    """Author a new SCORECARD version and dual-sign it with two distinct actors
    (neither the author) — same 3-person governance as e2e_smoke section 1c."""
    st, draft = call("POST", "/config/api/rulepacks",
                     {"jurisdiction": "IN-RBI", "type": "SCORECARD", "code": "rbi_scorecard_v1",
                      "payload": payload}, actor="pack.author.alice")
    draft = must(st, draft, "author SCORECARD")
    did = draft["id"]
    must(*call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty"),
         "policy sign SCORECARD")
    return must(*call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol"),
                "model-risk sign SCORECARD")


def weights_of(payload):
    return {f["key"]: f["weight"] for f in payload["factors"]}


def expected_score(breakdown, weights):
    """Recompute the total from the per-factor band scores (weight-independent) x the
    given weight set, mirroring the engine's rounding — the deterministic cross-check."""
    return round(sum(breakdown["factors"][k]["score"] * w for k, w in weights.items()), 4)


print("== 0. Seeded SCORECARD pack (dual-signed, weights verbatim from the old constants) ==")
st, seeded = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=SCORECARD")
seeded = must(st, seeded, "current SCORECARD")
original = seeded["payload"]
check("SCORECARD pack resolvable for IN-RBI and dual-signed", seeded.get("fullySignedOff") is True,
      str(seeded.get("fullySignedOff")))
check("pack carries 7 factors + 10 grade cut-points",
      len(original.get("factors") or []) == 7 and len(original.get("gradeCutoffs") or []) == 10,
      f"{len(original.get('factors') or [])} factors, {len(original.get('gradeCutoffs') or [])} cutoffs")
st, ae = call("GET", "/config/api/rulepacks?jurisdiction=AE-CBUAE&type=SCORECARD")
check("AE-CBUAE SCORECARD pack seeded too (same numbers, overlay not release)",
      st == 200 and (ae.get("payload") or {}).get("factors") == original.get("factors"), f"{st}")

old_weights = weights_of(original)
check("seeded DSCR weight is the historical constant 0.18",
      abs(old_weights.get("DSCR", 0) - 0.18) < 1e-12, str(old_weights.get("DSCR")))

score_a, grade_a = None, None   # set in (a); guards the finally-block control check

try:
    print("\n== a/b. Obligor A rates off the pack; breakdown stamps scorecardSource ==")
    ref_a = deal("A")
    r_a = rate(ref_a)
    bd_a = r_a["scoreBreakdown"]
    grade_a, score_a = r_a["finalGrade"], r_a["modelScore"]
    src_a = bd_a.get("scorecardSource")
    check("(b) breakdown carries scorecardSource", isinstance(src_a, str) and src_a != "", str(src_a))
    check("(b) scorecardSource cites the governed pack, not BUILT_IN",
          src_a != "BUILT_IN" and " v" in (src_a or ""), str(src_a))
    check("(a) every factor weight in the breakdown == the pack's weight",
          all(abs(bd_a["factors"][k]["weight"] - w) < 1e-12 for k, w in old_weights.items()),
          json.dumps({k: bd_a["factors"].get(k, {}).get("weight") for k in old_weights}))
    check("(a) totalScore == sum(bandScore x packWeight) — deterministic, pack-traceable",
          abs(bd_a["totalScore"] - expected_score(bd_a, old_weights)) < 1e-6,
          f"total {bd_a['totalScore']} vs recomputed {expected_score(bd_a, old_weights)}")

    print("\n== c. Author + dual-sign a NEW version with one weight visibly moved (DSCR 0.18 -> 0.30) ==")
    moved = copy.deepcopy(original)
    for f in moved["factors"]:
        if f["key"] == "DSCR":
            f["weight"] = 0.30
    v2 = author_scorecard(moved)
    check("(c) new version fully signed by two distinct humans and active",
          v2["fullySignedOff"] and v2["active"] and v2["version"] > seeded["version"],
          f"v{v2.get('version')} signed={v2.get('fullySignedOff')} active={v2.get('active')}")
    st, eff = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=SCORECARD")
    check("(c) resolver now serves the moved-weight version",
          st == 200 and eff["version"] == v2["version"]
          and any(f["key"] == "DSCR" and abs(f["weight"] - 0.30) < 1e-12 for f in eff["payload"]["factors"]),
          f"{st} v{eff.get('version') if st == 200 else '?'}")

    print("\n== d. Obligor B (identical financials) scores off the NEW weights ==")
    ref_b = deal("B")
    r_b = rate(ref_b)
    bd_b = r_b["scoreBreakdown"]
    score_b = r_b["modelScore"]
    new_weights = weights_of(moved)
    check("(d) B's breakdown carries the NEW DSCR weight 0.30",
          abs(bd_b["factors"]["DSCR"]["weight"] - 0.30) < 1e-12, str(bd_b["factors"]["DSCR"]["weight"]))
    check("(d) B's scorecardSource cites the new pack version",
          f"v{v2['version']}" in (bd_b.get("scorecardSource") or ""), str(bd_b.get("scorecardSource")))
    would_be_old = expected_score(bd_b, old_weights)
    check("(d) the score MOVED vs what the old weights would give",
          abs(score_b - would_be_old) > 1e-6, f"score {score_b} == old-weight recompute {would_be_old}")
    check("(d) ...and lands exactly where the new weights put it (config, not code)",
          abs(score_b - expected_score(bd_b, new_weights)) < 1e-6,
          f"score {score_b} vs new-weight recompute {expected_score(bd_b, new_weights)}")
    check("(d) identical financials, different pack version -> different score than A",
          abs(score_b - score_a) > 1e-6, f"A {score_a} vs B {score_b}")

    print("\n== e. Obligor A's STORED rating is untouched by the re-authoring ==")
    st, r_a2 = call("GET", f"/risk/api/risk/{ref_a}/rating")
    r_a2 = must(st, r_a2, "re-fetch A rating")
    check("(e) A's stored grade unchanged",
          r_a2["finalGrade"] == grade_a and r_a2["modelGrade"] == r_a["modelGrade"],
          f"{grade_a} -> {r_a2.get('finalGrade')}")
    check("(e) A's stored score byte-identical", r_a2["modelScore"] == score_a,
          f"{score_a} -> {r_a2.get('modelScore')}")
    check("(e) A's stored breakdown still cites the pack version it was rated under",
          r_a2["scoreBreakdown"].get("scorecardSource") == src_a,
          str(r_a2["scoreBreakdown"].get("scorecardSource")))

finally:
    print("\n== restore. Re-author the seeded weights verbatim (grade parity for later suites) ==")
    author_scorecard(original)
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=SCORECARD")
    restored = (st == 200 and any(f["key"] == "DSCR" and abs(f["weight"] - old_weights["DSCR"]) < 1e-12
                                  for f in chk["payload"]["factors"]))
    check("SCORECARD pack restored to the seeded weights", restored,
          str(chk.get("payload", {}).get("factors")) if st == 200 else str(st))
    if restored and FAIL == 0 and score_a is not None:
        ref_c = deal("C")
        r_c = rate(ref_c)
        check("post-restore control obligor scores byte-identically to A (parity restored)",
              r_c["modelScore"] == score_a and r_c["finalGrade"] == grade_a,
              f"A {score_a}/{grade_a} vs C {r_c.get('modelScore')}/{r_c.get('finalGrade')}")

print(f"\n== SCORECARD rule pack e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
