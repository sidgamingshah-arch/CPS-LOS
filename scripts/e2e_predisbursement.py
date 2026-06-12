#!/usr/bin/env python3
"""
Pre-disbursement workflow + CP gate — e2e.

Exercises the new ConditionPrecedent register and Disbursement workflow:
  1. Drive a deal through to sanction (counterparty -> application -> spread ->
     rate -> capital -> price -> proposal -> decide -> CAD -> limit-tree built).
  2. Seed the CP register from CP_MASTER (TERM_LOAN template).
  3. Attempt drawdown authorise with CPs OPEN -> 403 with blocker list.
  4. Clear / waive every mandatory CP (SoD: clearer != waiver != requester —
     a drawdown requester cannot waive their own CPs).
  5. Authorise again -> succeeds.
  6. Release -> calls limit-service /utilise, exposure goes up.
  7. SoD assertions: requester==authoriser blocked, authoriser==releaser blocked,
     requester==releaser blocked (three distinct humans per funded draw),
     requester==rejecter blocked, missing/blank X-Actor blocked.
  8. A second tranche on the same facility doesn't need to re-clear the CPs
     (CPs were "pre-disbursement", not "every-disbursement").
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="rm.user"):
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


def must(st, body, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {body}")
        sys.exit(1)
    return body


# ============================================================ setup
print("== 0. Setup: drive a deal to sanction + limit-tree ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Surya Steel Industries", "legalForm": "PUBLIC_LTD", "registrationNo": "U27109MH1998PLC123456",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "create counterparty")

st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 800_000_000, "currency": "INR", "tenorMonths": 84, "purpose": "Plant expansion",
    "collateralType": "PROPERTY", "collateralValue": 700_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "create application")
ref = app["reference"]

# Application auto-creates a primary facility — find its reference.
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
facs = must(st, facs, "list facilities")
assert facs and len(facs) >= 1, f"expected primary facility auto-seeded, got {facs}"
facility_ref = facs[0]["reference"]
print(f"    primary facility {facility_ref} (auto-created on application)")

# Spread + rate + capital + price (deterministic path; CPs don't care about this).
call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": {"value": 5e9, "sourceDocument": "manual", "confidence": 1.0},
        "COGS": {"value": 3.3e9, "sourceDocument": "manual", "confidence": 1.0},
        "OPERATING_EXPENSES": {"value": 0.7e9, "sourceDocument": "manual", "confidence": 1.0},
        "DEPRECIATION": {"value": 0.18e9, "sourceDocument": "manual", "confidence": 1.0},
        "INTEREST_EXPENSE": {"value": 0.13e9, "sourceDocument": "manual", "confidence": 1.0},
        "TAX": {"value": 0.12e9, "sourceDocument": "manual", "confidence": 1.0},
        "TOTAL_ASSETS": {"value": 6e9, "sourceDocument": "manual", "confidence": 1.0},
        "CURRENT_ASSETS": {"value": 2.4e9, "sourceDocument": "manual", "confidence": 1.0},
        "CASH": {"value": 0.6e9, "sourceDocument": "manual", "confidence": 1.0},
        "CURRENT_LIABILITIES": {"value": 1.6e9, "sourceDocument": "manual", "confidence": 1.0},
        "SHORT_TERM_DEBT": {"value": 0.5e9, "sourceDocument": "manual", "confidence": 1.0},
        "LONG_TERM_DEBT": {"value": 1.2e9, "sourceDocument": "manual", "confidence": 1.0},
        "CURRENT_PORTION_LTD": {"value": 0.18e9, "sourceDocument": "manual", "confidence": 1.0},
        "NET_WORTH": {"value": 2.7e9, "sourceDocument": "manual", "confidence": 1.0},
        "CFO": {"value": 0.65e9, "sourceDocument": "manual", "confidence": 1.0}}}]},
     actor="analyst.user")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/capital", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/price", actor="analyst.user")

# Build the limit tree from the deal (so /utilise has a node to hit).
st, tree = call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")
must(st, tree, "build limit tree")
cif = tree.get("cif")
print(f"    limit tree built · cif {cif}")

# ============================================================ 1. seed CPs
print("\n== 1. Seed CP register from CP_MASTER ==")
st, seeded = call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
check("seed call returns 200", st == 200, f"{st}")
check("CPs seeded from TERM_LOAN master", len(seeded) >= 5, f"got {len(seeded)} items")

st, reg = call("GET", f"/decision/api/cps/{ref}")
check("register lists all seeded items", st == 200 and len(reg) == len(seeded), f"reg={len(reg) if reg else 0}")
mandatory_codes = [r["code"] for r in reg if r["mandatory"] and r["status"] == "OPEN"]
print(f"    mandatory open CPs: {len(mandatory_codes)} ({', '.join(mandatory_codes)})")

# Re-seeding should be idempotent (no duplicates).
st, again = call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
check("re-seed is idempotent (no new rows)", st == 200 and len(again) == 0, f"got {len(again) if again else 0} new")

# ============================================================ 2. gate while CPs open
print("\n== 2. Pre-disbursement gate with mandatory CPs OPEN ==")
st, gate = call("GET", f"/decision/api/cps/gate/{ref}/{facility_ref}")
check("gate readable", st == 200, f"{st}")
check("gate says canDrawdown=false", gate["canDrawdown"] is False)
check("gate mandatoryOpen matches register", gate["mandatoryOpen"] == len(mandatory_codes),
      f"{gate['mandatoryOpen']} vs {len(mandatory_codes)}")
check("gate exposes blockers list", len(gate["blockers"]) >= 1)

# ============================================================ 3. attempt authorise with CPs open
print("\n== 3. Request drawdown + attempt authorise (CPs OPEN → must 403) ==")
st, d1 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": facility_ref, "amount": 300_000_000, "currency": "INR",
               "purpose": "first tranche", "narrative": "Civil works tranche"},
              actor="credit.ops")
d1 = must(st, d1, "request drawdown")
check("drawdown drafted", d1["status"] == "DRAFT" and d1["drawdownNo"] == 1)

st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize",
                {"note": "first authorise"}, actor="credit.officer")
check("authorise blocked with 403 (CPs open)", st == 403, f"{st} {body}")
check("403 message names blockers", isinstance(body, dict)
      and "mandatory CP" in str(body.get("message", "")), str(body))

# ============================================================ 4. clear / waive mandatory CPs
print("\n== 4. Clear / waive every mandatory CP ==")
cleared_count, waived_count = 0, 0
for c in reg:
    if not c["mandatory"]:
        continue
    if c["status"] != "OPEN":
        continue
    # Waive one to exercise that lane, clear the rest. Use a different actor than
    # the drawdown requester so SoD on authorise still works.
    if c["code"] == "CP-MAC":
        # SoD: the requester of the in-flight drawdown (credit.ops drafted d1 in
        # section 3) cannot waive their own conditions precedent.
        st, body = call("POST", f"/decision/api/cps/{c['id']}/waive",
                        {"reason": "self-serve waiver"}, actor="credit.ops")
        check("waive by in-flight drawdown requester blocked (403 SoD)", st == 403, f"{st} {body}")
        st, _ = call("POST", f"/decision/api/cps/{c['id']}/waive",
                      {"reason": "RM letter dated " + "2026-05-01 confirms no MAC"},
                      actor="credit.committee")
        check(f"waive {c['code']}", st == 200, f"{st}")
        waived_count += 1
    else:
        st, _ = call("POST", f"/decision/api/cps/{c['id']}/clear",
                      {"evidenceRef": "DOC-" + c["code"], "note": "evidence on file"},
                      actor="cad.maker")
        check(f"clear {c['code']}", st == 200, f"{st}")
        cleared_count += 1

st, gate2 = call("GET", f"/decision/api/cps/gate/{ref}/{facility_ref}")
check("gate now says canDrawdown=true", gate2["canDrawdown"] is True, str(gate2))
check("gate shows zero open mandatory CPs", gate2["mandatoryOpen"] == 0)
print(f"    cleared {cleared_count}, waived {waived_count}")

# ============================================================ 5. authorise + SoD
print("\n== 5. Authorise (SoD enforced) + release (SoD enforced) ==")
# SoD: requester (credit.ops) cannot authorise.
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize",
                {"note": "self-auth"}, actor="credit.ops")
check("self-authorise blocked by SoD", st == 403, f"{st} {body}")

# Authorise with a different actor — succeeds.
st, d1a = call("POST", f"/decision/api/disbursement/{d1['id']}/authorize",
               {"note": "first tranche authorised"}, actor="credit.officer")
check("authorise succeeds with distinct actor", st == 200 and d1a["status"] == "AUTHORIZED", f"{st}")

# SoD: authoriser (credit.officer) cannot release.
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="credit.officer")
check("self-release blocked by SoD", st == 403, f"{st} {body}")

# SoD: the original requester (credit.ops) cannot release either — request,
# authorise and release must be three distinct humans.
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="credit.ops")
check("release by original requester blocked by SoD", st == 403, f"{st} {body}")

# Capture exposure BEFORE release.
st, exp_before = call("GET", f"/limits/api/limits/{cif}/exposure")
out_before = exp_before["norms"].get("totalOutstanding", 0) if exp_before.get("norms") else 0
# Fallback: read from tree.
st, tv_before = call("GET", f"/limits/api/limits/view?cif={cif}")
tree_out_before = tv_before["totalOutstandingBase"]

# Release with a third actor — books limit utilisation.
st, d1r = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="treasury.ops")
check("release with distinct third actor succeeds", st == 200 and d1r["status"] == "RELEASED", f"{st} {d1r}")
check("release stamps utilisationRef", d1r.get("utilisationRef", "").startswith("DISB-"), str(d1r.get("utilisationRef")))

st, tv_after = call("GET", f"/limits/api/limits/view?cif={cif}")
tree_out_after = tv_after["totalOutstandingBase"]
check("limit-service outstanding increased by drawdown amount",
      tree_out_after - tree_out_before >= 300_000_000 - 1,
      f"before={tree_out_before} after={tree_out_after}")

# ============================================================ 6. second tranche
print("\n== 6. Second tranche on the same facility — CPs are pre-DISBURSEMENT, not per-draw ==")
st, d2 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": facility_ref, "amount": 200_000_000, "currency": "INR",
               "purpose": "second tranche", "narrative": "Machinery"},
              actor="credit.ops")
d2 = must(st, d2, "request second tranche")
check("second tranche numbered drawdownNo=2", d2["drawdownNo"] == 2, f"got {d2['drawdownNo']}")

# Authorise directly (no CPs to re-clear); succeeds.
st, d2a = call("POST", f"/decision/api/disbursement/{d2['id']}/authorize",
               {"note": "milestone 2 reached"}, actor="credit.officer")
check("second tranche authorises (no re-CP needed)", st == 200 and d2a["status"] == "AUTHORIZED", f"{st}")

# ============================================================ 7. over-limit guard
print("\n== 7. Negative: over-limit drawdown rejected ==")
st, body = call("POST", f"/decision/api/disbursement/{ref}/request",
                {"facilityRef": facility_ref, "amount": 500_000_000, "currency": "INR",
                 "purpose": "over-limit", "narrative": "should fail"},
                actor="credit.ops")
check("over-limit drawdown rejected", st == 400, f"{st} {body}")

# ============================================================ 8. CP gate visible from history
print("\n== 8. Audit & history surfaces ==")
st, history = call("GET", f"/decision/api/disbursement/{ref}")
check("history returns rows", st == 200 and len(history) >= 2, f"got {len(history) if history else 0}")
st, audit = call("GET", "/decision/api/audit")
check("audit trail has disbursement events",
      st == 200 and any("DISBURSEMENT" in (a.get("eventType") or "") for a in audit), str(audit)[:200])
check("audit trail has CP events",
      st == 200 and any("CP_" in (a.get("eventType") or "") for a in audit), str(audit)[:200])
check("CP_CLEARED is stamped HUMAN",
      any(a.get("eventType") == "CP_CLEARED" and a.get("actorType") == "HUMAN" for a in audit), "")
check("DISBURSEMENT_RELEASED is stamped HUMAN",
      any(a.get("eventType") == "DISBURSEMENT_RELEASED" and a.get("actorType") == "HUMAN" for a in audit), "")

print("\n== 9. Amend: requester edits a fresh DRAFT ==")
st, da = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": facility_ref, "amount": 50_000_000, "currency": "INR",
               "purpose": "tranche A", "narrative": "initial"},
              actor="credit.ops")
da = must(st, da, "request amendable")
check("amendable draw drafted with requester stamped",
      da["status"] == "DRAFT" and da["requestedBy"] == "credit.ops", str(da))
st, amended = call("POST", f"/decision/api/disbursement/{da['id']}/amend",
                   {"amount": 75_000_000, "purpose": "tranche A (revised up)",
                    "narrative": "updated per RM request"}, actor="credit.ops")
check("amend by requester returns 200", st == 200, f"{st} {amended}")
check("amount reflects amendment", amended["amount"] == 75_000_000, str(amended["amount"]))
check("purpose reflects amendment", amended["purpose"] == "tranche A (revised up)", str(amended["purpose"]))
st, body = call("POST", f"/decision/api/disbursement/{da['id']}/amend",
                 {"amount": 80_000_000}, actor="credit.officer")
check("amend by non-requester blocked (403 SoD)", st == 403, f"{st} {body}")
# Amend only on DRAFT
call("POST", f"/decision/api/disbursement/{da['id']}/authorize", {}, actor="credit.officer")
st, body = call("POST", f"/decision/api/disbursement/{da['id']}/amend",
                 {"amount": 60_000_000}, actor="credit.ops")
check("amend after AUTHORIZED rejected (409)", st == 409, f"{st} {body}")

print("\n== 10. Cancel: DRAFT is the requester's, AUTHORIZED needs the authoriser ==")
# da is AUTHORIZED (by credit.officer at the end of section 9). The requester can
# no longer unilaterally void the approval — only the authoriser may cancel.
st, body = call("POST", f"/decision/api/disbursement/{da['id']}/cancel",
                 {"reason": "withdraw"}, actor="credit.ops")
check("cancel of AUTHORIZED by requester blocked (403 SoD)", st == 403, f"{st} {body}")
st, body = call("POST", f"/decision/api/disbursement/{da['id']}/cancel",
                 {"reason": "withdraw"}, actor="rm.user")
check("cancel of AUTHORIZED by third party blocked (403 SoD)", st == 403, f"{st} {body}")
st, cancelled = call("POST", f"/decision/api/disbursement/{da['id']}/cancel",
                      {"reason": "borrower changed mind"}, actor="credit.officer")
check("cancel of AUTHORIZED by authoriser succeeds",
      st == 200 and cancelled["status"] == "CANCELLED", f"{st} {cancelled}")
check("cancelledBy/Reason captured", cancelled["cancelledBy"] == "credit.officer"
      and "changed mind" in (cancelled.get("cancelledReason") or ""), str(cancelled))
# A CANCELLED draw can't be re-cancelled / authorised / rejected
st, body = call("POST", f"/decision/api/disbursement/{da['id']}/authorize", {}, actor="credit.officer")
check("CANCELLED can't be re-authorised", st in (409, 400), f"{st}")
# DRAFT lane: only the requester may cancel their own draft.
st, draft = call("POST", f"/decision/api/disbursement/{ref}/request",
                 {"facilityRef": facility_ref, "amount": 50_000_000, "currency": "INR",
                  "purpose": "draft-cancel lane"}, actor="credit.ops")
draft = must(st, draft, "request draft for cancel lane")
st, body = call("POST", f"/decision/api/disbursement/{draft['id']}/cancel",
                 {"reason": "not mine"}, actor="credit.officer")
check("cancel of DRAFT by non-requester blocked (403 SoD)", st == 403, f"{st} {body}")
st, dcan = call("POST", f"/decision/api/disbursement/{draft['id']}/cancel",
                 {"reason": "requester withdraws"}, actor="credit.ops")
check("cancel of DRAFT by requester succeeds", st == 200 and dcan["status"] == "CANCELLED", f"{st} {dcan}")
# Cancellation freed the headroom (no longer counts toward used)
st, fresh = call("POST", f"/decision/api/disbursement/{ref}/request",
                 {"facilityRef": facility_ref, "amount": 75_000_000, "currency": "INR",
                  "purpose": "post-cancel"}, actor="credit.ops")
check("cancelled drawdown freed facility headroom",
      st == 200 and fresh["status"] == "DRAFT", f"{st} {fresh}")
call("POST", f"/decision/api/disbursement/{fresh['id']}/cancel",
     {"reason": "headroom probe only"}, actor="credit.ops")

print("\n== 11. Cross-currency: USD drawdown on an INR facility (FX converted) ==")
st, fx = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": facility_ref, "amount": 1_000_000, "currency": "USD",
               "purpose": "USD draw", "narrative": "cross-ccy"},
              actor="credit.ops")
fx_d = must(st, fx, "cross-ccy request")
check("status DRAFT", fx_d["status"] == "DRAFT")
check("requestedCurrency = USD", fx_d.get("requestedCurrency") == "USD", str(fx_d.get("requestedCurrency")))
check("requestedAmount = 1,000,000", fx_d.get("requestedAmount") == 1_000_000, str(fx_d.get("requestedAmount")))
check("facility currency stays INR", fx_d["currency"] == "INR", str(fx_d["currency"]))
check("amount > requestedAmount (USD → INR at ~83)",
      fx_d["amount"] > fx_d["requestedAmount"] * 50, f"amount={fx_d['amount']} req={fx_d['requestedAmount']}")
check("fxRate stamped on the row", fx_d.get("fxRate") and fx_d["fxRate"] > 0, str(fx_d.get("fxRate")))
print(f"    {fx_d['requestedAmount']:,.0f} USD @ {fx_d['fxRate']:.4f} = {fx_d['amount']:,.2f} INR")

print("\n== 12. Per-jurisdiction CP_MASTER override: IN-RBI uses Indian-specific CPs ==")
# The seed has both a default TERM_LOAN pack and an IN-RBI override. The picker
# should choose the override for an IN-RBI deal (our Surya Steel app).
in_codes = {c["code"] for c in reg}
check("IN-RBI override picked (CERSAI present)", "CP-CERSAI" in in_codes, str(in_codes))
check("IN-RBI override picked (ROC charge filing)", "CP-ROC" in in_codes, str(in_codes))
check("IN-RBI override picked (revenue stamping)", "CP-STAMP" in in_codes, str(in_codes))
check("default-only codes not present (CP-VAL still here, CP-BR still here)",
      "CP-BR" in in_codes and "CP-VAL" in in_codes, str(in_codes))

# Now seed an AE-CBUAE deal and confirm a different pack is applied.
st, cp_ae = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Falcon Trading LLC", "legalForm": "LLC", "registrationNo": "AE-CR-CP-1",
    "jurisdiction": "AE-CBUAE", "segment": "MID_CORPORATE", "sector": "TRADE", "country": "AE",
    "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp_ae = must(st, cp_ae, "cp ae")
st, app_ae = call("POST", "/origination/api/applications", {
    "counterpartyId": cp_ae["id"], "counterpartyRef": cp_ae["reference"], "counterpartyName": cp_ae["legalName"],
    "jurisdiction": "AE-CBUAE", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 100_000_000, "currency": "AED", "tenorMonths": 60, "purpose": "Plant",
    "collateralType": "PROPERTY", "collateralValue": 130_000_000, "secured": True}, actor="rm.user")
app_ae = must(st, app_ae, "app ae")
ref_ae = app_ae["reference"]
call("POST", f"/decision/api/cps/{ref_ae}/seed", actor="credit.ops")
st, reg_ae = call("GET", f"/decision/api/cps/{ref_ae}")
ae_codes = {c["code"] for c in reg_ae}
check("AE-CBUAE override picked (Mortgage with Land Dept)", "CP-MOR-LD" in ae_codes, str(ae_codes))
check("AE-CBUAE override picked (Emirates ID)", "CP-EID" in ae_codes, str(ae_codes))
check("AE-CBUAE override picked (Al Etihad bureau)", "CP-AECB" in ae_codes, str(ae_codes))
check("AE-CBUAE override picked (Economic Substance)", "CP-ESR" in ae_codes, str(ae_codes))
check("AE-CBUAE does NOT carry India-specific CPs",
      "CP-CERSAI" not in ae_codes and "CP-ROC" not in ae_codes and "CP-STAMP" not in ae_codes, str(ae_codes))

print("\n== 13. Actor hygiene: anonymous writes are blocked, never defaulted ==")
# No X-Actor header at all -> 400 (required header). The old behaviour silently
# defaulted to per-endpoint actors that happened to satisfy SoD between them.
def call_raw(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
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

st, body = call_raw("POST", f"/decision/api/disbursement/{ref}/request",
                    {"facilityRef": facility_ref, "amount": 1_000_000, "currency": "INR",
                     "purpose": "anonymous"})
check("request without X-Actor header rejected (400)", st == 400, f"{st} {body}")
st, body = call_raw("POST", f"/decision/api/disbursement/{ref}/request",
                    {"facilityRef": facility_ref, "amount": 1_000_000, "currency": "INR",
                     "purpose": "blank actor"}, headers={"X-Actor": ""})
check("request with blank X-Actor rejected (400/403)", st in (400, 403), f"{st} {body}")

print("\n== 14. Reject is the checker's lane — the requester withdraws via cancel ==")
st, dr = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": facility_ref, "amount": 10_000_000, "currency": "INR",
               "purpose": "reject lane"}, actor="credit.ops")
dr = must(st, dr, "request for reject lane")
st, body = call("POST", f"/decision/api/disbursement/{dr['id']}/reject",
                 {"reason": "self-reject"}, actor="credit.ops")
check("reject by own requester blocked (403 SoD)", st == 403, f"{st} {body}")
st, drj = call("POST", f"/decision/api/disbursement/{dr['id']}/reject",
                {"reason": "duplicate of tranche 2"}, actor="credit.officer")
check("reject by a different actor succeeds", st == 200 and drj["status"] == "REJECTED", f"{st} {drj}")

print(f"\n== Pre-disbursement e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
