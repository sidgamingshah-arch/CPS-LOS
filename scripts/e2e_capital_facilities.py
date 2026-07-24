#!/usr/bin/env python3
"""
Capital-factors-facilities + dual-approach pricing — e2e (batch4/capital-pricing).

Proves the capital & pricing unit's new, governed behaviour end-to-end through the gateway:

  A. PARITY (single facility) — a deal with ONE facility (the primary) capitalises on the
     historical single-figure path (trace method is NOT the per-facility aggregate; EAD ==
     requested amount) and prices exactly as before. Per-facility pricing still returns a
     single entry, and the dual (RAROC + peer) price is presented.

  B. MULTI-FACILITY CAPITAL (documented NEW behaviour) — a deal with 2+ facilities capitalises
     PER FACILITY (each facility's CCF + its linked / apportioned collateral) and AGGREGATES:
       * the trace method is PER_FACILITY_AGGREGATE with facilityCount == 2,
       * a REVOLVING_CREDIT facility applies CCF 0.4 (exposure = 0.4 x amount) — proving CCF is
         factored per facility,
       * the aggregate EAD == the sum of per-facility post-CCF exposures (so ADDITIONAL facilities
         now MOVE the authoritative capital — they did not before),
       * the aggregate RWA == the sum of per-facility RWA,
       * collateral is recognised (secured portion > 0).

  C. PER-FACILITY PRICING + DUAL PRICE — POST /pricing returns per-facility RAROC prices AND a
     deal aggregate; the recommended price presents BOTH the RAROC price and the PEER_PRICING
     benchmark price (shown, never silently blended).

  D. HURDLE FROM CONFIG — the RAROC hurdle is read from the PRICING rule pack, never hardcoded:
     bumping the pack's flat hurdle changes the priced hurdle (and lifts the recommended rate),
     and a per-segment override in the pack wins for that segment. The transient pack is restored
     (original payload re-authored + dual-signed) in a finally block.

This suite is NOT registered in run_regression — run it standalone against the gateway.
"""
import copy
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
    return {"value": v, "sourceDocument": "cap.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.96}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


SPREAD = {"periods": [
    per("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
    per("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
]}


def new_deal(suffix, amount):
    """cp -> app (TERM_LOAN + PROPERTY collateral). Returns (ref, app)."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"CapFac {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"CAPFAC{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount, "secured": True}, actor="rm.user")
    app = must(st, app, "app")
    return app["reference"], app


def rate_confirm(ref):
    call("POST", f"/origination/api/applications/{ref}/spread", SPREAD, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    st, r = call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    r = must(st, r, "rate")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    return r["finalGrade"]


# ---------------------------------------------------------------- transient PRICING pack author/restore
st, cur = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=PRICING")
cur = must(st, cur, "current PRICING pack")
original_payload = copy.deepcopy(cur["payload"])
pack_code = cur["code"]


def author_pricing_pack(payload):
    st, draft = call("POST", "/config/api/rulepacks",
                     {"jurisdiction": "IN-RBI", "type": "PRICING", "code": pack_code, "payload": payload},
                     actor="pack.author.alice")
    draft = must(st, draft, "author PRICING pack")
    did = draft["id"]
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty")
    st, signed = call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol")
    must(st, signed, "sign PRICING pack")


def restore_original_pack():
    st, draft = call("POST", "/config/api/rulepacks",
                     {"jurisdiction": "IN-RBI", "type": "PRICING", "code": pack_code,
                      "payload": original_payload}, actor="pack.author.alice")
    if st != 200:
        print(f"  WARN  could not re-author original PRICING pack ({st})")
        return
    did = draft["id"]
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty")
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol")


try:
    # ============================================================ A. parity (single facility)
    print("\n== A. PARITY — single facility uses the historical single-figure capital path ==")
    sref, _ = new_deal("SINGLE", 40_000_000)
    rate_confirm(sref)
    st, scap = call("POST", f"/risk/api/risk/{sref}/capital", actor="credit.ops")
    scap = must(st, scap, "single capital")
    strace = scap.get("trace") or {}
    check("single-facility capital did NOT take the per-facility aggregate path",
          strace.get("method") != "PER_FACILITY_AGGREGATE" and "nominalExposure" in strace,
          str(strace.get("method")))
    check("single-facility EAD == requested amount (TERM_LOAN CCF 1.0)",
          abs(scap["ead"] - 40_000_000) < 1.0, str(scap.get("ead")))
    check("single-facility RWA positive and collateral recognised",
          scap["rwa"] > 0 and scap["securedPortion"] > 0, f"rwa={scap.get('rwa')} sec={scap.get('securedPortion')}")
    st, sprice = call("POST", f"/risk/api/risk/{sref}/pricing", actor="rm.user")
    sprice = must(st, sprice, "single pricing")
    sdetail = sprice.get("detail") or {}
    check("single-facility pricing returns exactly one per-facility price",
          len(sdetail.get("perFacility") or []) == 1, str(len(sdetail.get("perFacility") or [])))
    check("single-facility recommended rate above cost of funds",
          sprice["recommendedRate"] > 0.05, str(sprice.get("recommendedRate")))

    # ============================================================ B. multi-facility capital (NEW)
    print("\n== B. MULTI-FACILITY — capital aggregates per-facility RWA + factors collateral ==")
    mref, mapp = new_deal("MULTI", 40_000_000)
    # add a REVOLVING_CREDIT facility (CCF 0.4) alongside the primary TERM_LOAN (CCF 1.0)
    st, f2 = call("POST", f"/origination/api/applications/{mref}/facilities",
                  {"facilityType": "REVOLVING_CREDIT", "amount": 20_000_000, "currency": "INR",
                   "tenorMonths": 24, "purpose": "Working capital"}, actor="rm.user")
    f2 = must(st, f2, "add facility")
    # facility-specific collateral (linked to the revolving line) + a pooled collateral (no facilityId)
    st, _rc = call("POST", f"/origination/api/applications/{mref}/collaterals",
                   {"collateralType": "RECEIVABLES", "description": "Book debts", "marketValue": 10_000_000,
                    "haircut": 0, "perfectionStatus": "PERFECTED", "facilityId": f2["id"]}, actor="rm.user")
    must(st, _rc, "add linked collateral")
    st, _pool = call("POST", f"/origination/api/applications/{mref}/collaterals",
                     {"collateralType": "CASH", "description": "Margin deposit", "marketValue": 5_000_000,
                      "haircut": 0, "perfectionStatus": "PERFECTED"}, actor="rm.user")
    must(st, _pool, "add pooled collateral")

    rate_confirm(mref)
    st, mcap = call("POST", f"/risk/api/risk/{mref}/capital", actor="credit.ops")
    mcap = must(st, mcap, "multi capital")
    mtrace = mcap.get("trace") or {}
    pf = mtrace.get("perFacility") or []
    check("multi-facility capital took the PER_FACILITY_AGGREGATE path",
          mtrace.get("method") == "PER_FACILITY_AGGREGATE", str(mtrace.get("method")))
    check("per-facility breakdown has 2 facilities", len(pf) == 2, str(len(pf)))

    rc = next((x for x in pf if x["facilityType"] == "REVOLVING_CREDIT"), None)
    tl = next((x for x in pf if x["facilityType"] == "TERM_LOAN"), None)
    check("REVOLVING_CREDIT facility applies CCF 0.4 (per-facility CCF factored)",
          rc is not None and abs(rc["ccf"] - 0.4) < 1e-9, str(rc))
    check("REVOLVING_CREDIT post-CCF exposure == 0.4 x amount (8,000,000)",
          rc is not None and abs(rc["exposureAfterCcf"] - 8_000_000) < 1.0, str(rc.get("exposureAfterCcf") if rc else None))
    check("TERM_LOAN facility applies CCF 1.0 (exposure == amount)",
          tl is not None and abs(tl["ccf"] - 1.0) < 1e-9 and abs(tl["exposureAfterCcf"] - 40_000_000) < 1.0, str(tl))

    exp_sum = sum(x["exposureAfterCcf"] for x in pf)
    rwa_sum = sum(x["rwa"] for x in pf)
    check("aggregate EAD == sum of per-facility exposures (48,000,000) — additional facilities MOVE capital",
          abs(mcap["ead"] - exp_sum) < 1.0 and abs(mcap["ead"] - 48_000_000) < 1.0, f"ead={mcap.get('ead')} sum={exp_sum}")
    check("aggregate RWA == sum of per-facility RWA", abs(mcap["rwa"] - rwa_sum) < 1.0,
          f"rwa={mcap.get('rwa')} sum={rwa_sum}")
    check("aggregate EAD exceeds the primary requested amount (multi-facility exposure booked)",
          mcap["ead"] > 40_000_000, str(mcap.get("ead")))
    check("multi-facility collateral recognised (secured portion > 0)", mcap["securedPortion"] > 0,
          str(mcap.get("securedPortion")))

    # ============================================================ C. per-facility pricing + dual price
    print("\n== C. PER-FACILITY PRICING + DUAL (RAROC + PEER) PRICE ==")
    st, mprice = call("POST", f"/risk/api/risk/{mref}/pricing", actor="rm.user")
    mprice = must(st, mprice, "multi pricing")
    mdetail = mprice.get("detail") or {}
    mpf = mdetail.get("perFacility") or []
    check("pricing returns per-facility results (2) + a deal aggregate",
          len(mpf) == 2 and mdetail.get("aggregate") is not None, f"perFacility={len(mpf)}")
    check("each per-facility price carries a RAROC + hurdle",
          all("recommendedRate" in x and "raroc" in x and "hurdleRaroc" in x for x in mpf), str(mpf[:1]))
    check("authoritative aggregate recommendedRate matches the detail aggregate",
          abs(mprice["recommendedRate"] - mdetail["aggregate"]["recommendedRate"]) < 1e-9,
          f"{mprice.get('recommendedRate')} vs {mdetail['aggregate'].get('recommendedRate')}")

    peer = mdetail.get("peer") or {}
    check("dual price: PEER_PRICING benchmark resolved (available)", peer.get("available") is True, str(peer))
    check("dual price shows BOTH the RAROC rate and the peer rate",
          mprice["recommendedRate"] > 0 and isinstance(peer.get("peerRate"), (int, float)) and peer["peerRate"] > 0,
          f"raroc={mprice.get('recommendedRate')} peer={peer.get('peerRate')}")
    check("peer price carries a matched master key + source (advisory presentation)",
          bool(peer.get("matchedKey")) and "source" in peer, str(peer.get("matchedKey")))

    # ============================================================ D. hurdle from config
    print("\n== D. HURDLE FROM CONFIG — pack drives the hurdle (never hardcoded) ==")
    base_hurdle = mprice["hurdleRaroc"]
    base_rate = mprice["recommendedRate"]
    check("baseline priced hurdle == seeded flat hurdle 0.15", abs(base_hurdle - 0.15) < 1e-9, str(base_hurdle))

    # D1 — bump the flat hurdle to 0.30 and re-price.
    bumped = copy.deepcopy(original_payload)
    bumped["hurdle_raroc"] = 0.30
    author_pricing_pack(bumped)
    st, mprice2 = call("POST", f"/risk/api/risk/{mref}/pricing", actor="rm.user")
    mprice2 = must(st, mprice2, "re-price after hurdle bump")
    check("changing the pack's flat hurdle moved the priced hurdle to 0.30",
          abs(mprice2["hurdleRaroc"] - 0.30) < 1e-9, str(mprice2.get("hurdleRaroc")))
    check("detail.hurdle reports the new flat hurdle from config",
          abs(((mprice2.get("detail") or {}).get("hurdle") or {}).get("flatHurdle", 0) - 0.30) < 1e-9,
          str((mprice2.get("detail") or {}).get("hurdle")))
    check("a higher hurdle lifts (never lowers) the recommended rate",
          mprice2["recommendedRate"] >= base_rate - 1e-9,
          f"{base_rate} -> {mprice2.get('recommendedRate')}")

    # D2 — per-segment override wins for MID_CORPORATE (flat stays 0.15).
    override = copy.deepcopy(original_payload)
    override["hurdle_raroc_overrides"] = {"MID_CORPORATE": 0.35}
    author_pricing_pack(override)
    st, mprice3 = call("POST", f"/risk/api/risk/{mref}/pricing", actor="rm.user")
    mprice3 = must(st, mprice3, "re-price with per-segment override")
    check("per-segment hurdle override (MID_CORPORATE=0.35) wins over the flat hurdle",
          abs(mprice3["hurdleRaroc"] - 0.35) < 1e-9, str(mprice3.get("hurdleRaroc")))
    check("detail.hurdle flags the per-segment override",
          ((mprice3.get("detail") or {}).get("hurdle") or {}).get("perSegmentOverride") is True,
          str((mprice3.get("detail") or {}).get("hurdle")))

finally:
    restore_original_pack()
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=PRICING")
    if st == 200:
        restored = abs(chk["payload"].get("hurdle_raroc", 0) - original_payload.get("hurdle_raroc", 0)) < 1e-9 \
            and "hurdle_raroc_overrides" not in chk["payload"]
        check("PRICING pack restored to seeded semantics (flat hurdle, no per-segment override)",
              restored, str({k: chk["payload"].get(k) for k in ("hurdle_raroc", "hurdle_raroc_overrides")}))


print(f"\n== Capital-facilities + dual-pricing e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
