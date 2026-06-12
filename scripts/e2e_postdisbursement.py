#!/usr/bin/env python3
"""
Post-disbursement money movement — e2e.

Closes the lifecycle loop the disbursement workflow opened:
  1. Deterministic repayment schedule (EMI / EQUAL_PRINCIPAL / BULLET) computed
     from the released-draw ledger + pricing of record — never persisted.
  2. Manual repayment lane: record (maker) -> confirm (checker, SoD) books a
     limit RELEASE for the principal component; outstanding drops.
  3. Core-banking connector lane: idempotent envelope ingest books the RELEASE
     as SYSTEM; replays are recognised as duplicates (no double-release).
  4. Reversal: a RELEASED drawdown is reversed (SoD: reverser != releaser,
     reason mandatory); the limit ledger is restored and headroom freed;
     replaying the reversal's limit booking is a no-op.
  5. Guards: over-repayment blocked; reversal blocked while confirmed
     repayments exist on the facility.
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
        PASS += 1; print(f"  PASS  {name}")
    else:
        FAIL += 1; print(f"  FAIL  {name}  {detail}")


def must(st, b, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {b}"); sys.exit(1)
    return b


print("== 0. Setup: deal to sanction, CPs cleared, 300M drawdown released ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Meridian Polymers Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "PD-MP-1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 600_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 550_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
ref = app["reference"]
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
fref = must(st, facs, "facilities")[0]["reference"]

call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
    {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": {"value": 4e9, "sourceDocument": "m", "confidence": 1.0},
        "COGS": {"value": 2.6e9, "sourceDocument": "m", "confidence": 1.0},
        "OPERATING_EXPENSES": {"value": 0.6e9, "sourceDocument": "m", "confidence": 1.0},
        "DEPRECIATION": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
        "INTEREST_EXPENSE": {"value": 0.12e9, "sourceDocument": "m", "confidence": 1.0},
        "TAX": {"value": 0.1e9, "sourceDocument": "m", "confidence": 1.0},
        "TOTAL_ASSETS": {"value": 5e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_ASSETS": {"value": 2e9, "sourceDocument": "m", "confidence": 1.0},
        "CASH": {"value": 0.5e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_LIABILITIES": {"value": 1.3e9, "sourceDocument": "m", "confidence": 1.0},
        "SHORT_TERM_DEBT": {"value": 0.4e9, "sourceDocument": "m", "confidence": 1.0},
        "LONG_TERM_DEBT": {"value": 1.0e9, "sourceDocument": "m", "confidence": 1.0},
        "CURRENT_PORTION_LTD": {"value": 0.15e9, "sourceDocument": "m", "confidence": 1.0},
        "NET_WORTH": {"value": 2.3e9, "sourceDocument": "m", "confidence": 1.0},
        "CFO": {"value": 0.55e9, "sourceDocument": "m", "confidence": 1.0}}}]},
     actor="analyst.user")
call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/capital", actor="analyst.user")
call("POST", f"/risk/api/risk/{ref}/pricing", actor="analyst.user")
st, tree = call("POST", f"/limits/api/limits/build/{ref}", actor="credit.ops")
cif = must(st, tree, "limit tree")["cif"]

call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
st, reg = call("GET", f"/decision/api/cps/{ref}")
for c in reg:
    if c["mandatory"] and c["status"] == "OPEN":
        call("POST", f"/decision/api/cps/{c['id']}/clear", {"evidenceRef": "DOC-" + c["code"]}, actor="cad.maker")

st, d1 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 300_000_000, "currency": "INR",
               "purpose": "tranche 1"}, actor="credit.ops")
d1 = must(st, d1, "request d1")
call("POST", f"/decision/api/disbursement/{d1['id']}/authorize", {}, actor="credit.officer")
st, d1r = call("POST", f"/decision/api/disbursement/{d1['id']}/release", actor="treasury.ops")
d1r = must(st, d1r, "release d1")
print(f"    deal {ref}, facility {fref}, 300M released")

# ============================================================ 1. schedule
print("\n== 1. Deterministic repayment schedule (computed, never persisted) ==")
st, emi = call("GET", f"/decision/api/repayments/{ref}/schedule?facilityRef={fref}&method=EMI&frequency=MONTHLY")
emi = must(st, emi, "EMI schedule")
check("schedule principal = released 300M", abs(emi["principal"] - 300_000_000) < 1, str(emi["principal"]))
check("schedule periods = facility tenor (60)", emi["periods"] == 60, str(emi["periods"]))
check("rate sourced from pricing of record", emi["rateSource"] == "PRICING_OF_RECORD", str(emi["rateSource"]))
rows = emi["rows"]
check("principal column sums to the principal",
      abs(sum(r["principal"] for r in rows) - emi["principal"]) < 1,
      str(sum(r["principal"] for r in rows)))
check("EMI payment constant across periods (±1)",
      max(r["payment"] for r in rows[:-1]) - min(r["payment"] for r in rows[:-1]) < 1, "")
check("closing balance reaches zero", abs(rows[-1]["closingBalance"]) < 1, str(rows[-1]["closingBalance"]))

st, ep = call("GET", f"/decision/api/repayments/{ref}/schedule?facilityRef={fref}&method=EQUAL_PRINCIPAL")
ep = must(st, ep, "EP schedule")
check("EQUAL_PRINCIPAL has constant principal (±1)",
      max(r["principal"] for r in ep["rows"][:-1]) - min(r["principal"] for r in ep["rows"][:-1]) < 1, "")
st, bl = call("GET", f"/decision/api/repayments/{ref}/schedule?facilityRef={fref}&method=BULLET&frequency=QUARTERLY")
bl = must(st, bl, "bullet schedule")
check("BULLET: principal only in final period",
      all(r["principal"] == 0 for r in bl["rows"][:-1]) and abs(bl["rows"][-1]["principal"] - 300_000_000) < 1, "")
check("BULLET quarterly periods = 20", bl["periods"] == 20, str(bl["periods"]))

# ============================================================ 2. manual lane
print("\n== 2. Manual repayment: record (maker) -> confirm (checker) books limit RELEASE ==")
st, view0 = call("GET", f"/limits/api/limits/view?cif={cif}")
out0 = view0["totalOutstandingBase"]

st, rp = call("POST", f"/decision/api/repayments/{ref}/record",
              {"facilityRef": fref, "amount": 50_000_000, "principalComponent": 45_000_000,
               "interestComponent": 5_000_000, "narrative": "Q1 instalment"}, actor="loan.ops")
rp = must(st, rp, "record repayment")
check("repayment RECORDED with split captured",
      rp["status"] == "RECORDED" and rp["principalComponent"] == 45_000_000, str(rp))

st, view1 = call("GET", f"/limits/api/limits/view?cif={cif}")
check("no ledger movement before confirmation", abs(view1["totalOutstandingBase"] - out0) < 1,
      f"{out0} -> {view1['totalOutstandingBase']}")

st, body = call("POST", f"/decision/api/repayments/{rp['id']}/confirm", actor="loan.ops")
check("self-confirm blocked (403 SoD)", st == 403, f"{st} {body}")
st, rpc = call("POST", f"/decision/api/repayments/{rp['id']}/confirm", actor="loan.checker")
check("confirm by checker succeeds", st == 200 and rpc["status"] == "CONFIRMED", f"{st} {rpc}")
check("releaseRef stamped", (rpc.get("releaseRef") or "").startswith("RPMT-"), str(rpc.get("releaseRef")))

st, view2 = call("GET", f"/limits/api/limits/view?cif={cif}")
check("outstanding dropped by the principal (45M), not the full amount",
      abs((out0 - view2["totalOutstandingBase"]) - 45_000_000) < 1,
      f"{out0} -> {view2['totalOutstandingBase']}")
st, outd = call("GET", f"/decision/api/repayments/{ref}/outstanding?facilityRef={fref}")
check("decision-ledger outstanding = 255M", abs(outd["outstandingPrincipal"] - 255_000_000) < 1,
      str(outd["outstandingPrincipal"]))

st, body = call("POST", f"/decision/api/repayments/{rp['id']}/confirm", actor="loan.checker")
check("re-confirm rejected (409 — already CONFIRMED)", st == 409, f"{st}")

# Over-repayment guard: principal beyond outstanding is blocked at record time.
st, body = call("POST", f"/decision/api/repayments/{ref}/record",
                {"facilityRef": fref, "amount": 400_000_000}, actor="loan.ops")
check("over-repayment blocked (400)", st == 400, f"{st} {body}")

# Reject lane: maker can't kill their own entry; checker can.
st, rj = call("POST", f"/decision/api/repayments/{ref}/record",
              {"facilityRef": fref, "amount": 1_000_000}, actor="loan.ops")
rj = must(st, rj, "record for reject")
st, body = call("POST", f"/decision/api/repayments/{rj['id']}/reject",
                {"reason": "self"}, actor="loan.ops")
check("self-reject blocked (403 SoD)", st == 403, f"{st} {body}")
st, _ = call("POST", f"/decision/api/repayments/{rj['id']}/reject",
             {"reason": "duplicate entry"}, actor="loan.checker")
check("reject by checker succeeds", st == 200, f"{st}")

# ============================================================ 3. connector lane
print("\n== 3. Core-banking connector: idempotent envelope ingest, RELEASE as SYSTEM ==")
env = {"vendor": "FlexLMS", "idempotencyKey": f"LMS-{ref}-RPMT-0001", "payloadVersion": "v1",
       "payload": {"facilityRef": fref, "amount": 30_000_000, "principalComponent": 28_000_000,
                   "interestComponent": 2_000_000, "currency": "INR",
                   "valueDate": "2026-06-10", "externalRef": "LMS-TXN-77001"}}
st, res = call("POST", f"/decision/api/repayments/{ref}/ingest", env, actor="core-banking")
res = must(st, res, "ingest")
check("event accepted (not duplicate)", res["accepted"] is True and res["duplicate"] is False, str(res))
st, view3 = call("GET", f"/limits/api/limits/view?cif={cif}")
check("outstanding dropped by ingested principal (28M)",
      abs((view2["totalOutstandingBase"] - view3["totalOutstandingBase"]) - 28_000_000) < 1,
      f"{view2['totalOutstandingBase']} -> {view3['totalOutstandingBase']}")

st, res2 = call("POST", f"/decision/api/repayments/{ref}/ingest", env, actor="core-banking")
check("replay recognised as duplicate", st == 200 and res2["duplicate"] is True, f"{st} {res2}")
st, view4 = call("GET", f"/limits/api/limits/view?cif={cif}")
check("replay did NOT move the ledger", abs(view4["totalOutstandingBase"] - view3["totalOutstandingBase"]) < 1,
      f"{view3['totalOutstandingBase']} -> {view4['totalOutstandingBase']}")

st, hist = call("GET", f"/decision/api/repayments/{ref}?facilityRef={fref}")
cb = [r for r in hist if r["source"] == "CORE_BANKING"]
check("connector repayment CONFIRMED by SYSTEM",
      len(cb) == 1 and cb[0]["status"] == "CONFIRMED" and "SYSTEM" in (cb[0].get("confirmedBy") or ""), str(cb))

# ============================================================ 4. reversal
print("\n== 4. Reversal of a RELEASED drawdown — blocked while repayments exist ==")
st, body = call("POST", f"/decision/api/disbursement/{d1['id']}/reverse",
                {"reason": "test"}, actor="ops.checker")
check("reversal blocked while facility has confirmed repayments (409)", st == 409, f"{st} {body}")

# Use a clean second drawdown for the reversal lane.
st, d2 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 100_000_000, "currency": "INR",
               "purpose": "tranche 2 (to be reversed)"}, actor="credit.ops")
d2 = must(st, d2, "request d2")
call("POST", f"/decision/api/disbursement/{d2['id']}/authorize", {}, actor="credit.officer")
st, d2r = call("POST", f"/decision/api/disbursement/{d2['id']}/release", actor="treasury.ops")
d2r = must(st, d2r, "release d2")
st, view5 = call("GET", f"/limits/api/limits/view?cif={cif}")

st, body = call("POST", f"/decision/api/disbursement/{d2['id']}/reverse", {"reason": ""}, actor="ops.checker")
check("reversal without a reason rejected (400)", st == 400, f"{st} {body}")
st, body = call("POST", f"/decision/api/disbursement/{d2['id']}/reverse",
                {"reason": "wrong account"}, actor="treasury.ops")
check("reversal by the releaser blocked (403 SoD)", st == 403, f"{st} {body}")

print("\n== 5. Reversal succeeds: ledger restored, headroom freed, terminal state ==")
st, d2v = call("POST", f"/decision/api/disbursement/{d2['id']}/reverse",
               {"reason": "disbursed to wrong account"}, actor="ops.checker")
check("reversal by a different actor succeeds", st == 200 and d2v["status"] == "REVERSED", f"{st} {d2v}")
check("reversalRef = REV-<utilisationRef>", d2v.get("reversalRef") == "REV-" + d2r["utilisationRef"],
      str(d2v.get("reversalRef")))

st, view6 = call("GET", f"/limits/api/limits/view?cif={cif}")
check("limit outstanding restored by the reversed amount (100M)",
      abs((view5["totalOutstandingBase"] - view6["totalOutstandingBase"]) - 100_000_000) < 1,
      f"{view5['totalOutstandingBase']} -> {view6['totalOutstandingBase']}")

# Headroom freed: 600M sanctioned, 300M live (d1) -> a fresh 300M draft fits again.
st, d3 = call("POST", f"/decision/api/disbursement/{ref}/request",
              {"facilityRef": fref, "amount": 300_000_000, "currency": "INR",
               "purpose": "post-reversal redraw"}, actor="credit.ops")
check("reversed drawdown freed facility headroom", st == 200 and d3["status"] == "DRAFT", f"{st} {d3}")
call("POST", f"/decision/api/disbursement/{d3['id']}/cancel", {"reason": "probe"}, actor="credit.ops")

st, body = call("POST", f"/decision/api/disbursement/{d2['id']}/reverse",
                {"reason": "again"}, actor="ops.checker")
check("REVERSED is terminal — second reversal rejected (409)", st == 409, f"{st} {body}")

# The audit trail carries the reversal as a HUMAN action.
st, audit_rows = call("GET", "/decision/api/audit")
check("DISBURSEMENT_REVERSED stamped HUMAN",
      any(a.get("eventType") == "DISBURSEMENT_REVERSED" and a.get("actorType") == "HUMAN"
          for a in audit_rows), "")
check("REPAYMENT_INGESTED stamped as engine/system event",
      any(a.get("eventType") == "REPAYMENT_INGESTED" for a in audit_rows), "")

print("\n== 6. Floating-rate schedule (EBLR + 200bps, reset quarterly) ==")
# Flip d1's facility to FLOATING and re-pull the schedule. Same outstanding (the
# 222M left after repayments + reversal), but the rate now comes from the
# BENCHMARK master, not the pricing of record.
st, ft = call("POST", f"/origination/api/applications/{ref}/facilities/{fref}/rate-type",
              {"rateType": "FLOATING", "benchmarkCode": "EBLR",
               "spreadBps": 200, "resetFrequencyMonths": 3}, actor="rm.user")
ft = must(st, ft, "set FLOATING")
check("facility now FLOATING with EBLR + 200bps",
      ft["rateType"] == "FLOATING" and ft["benchmarkCode"] == "EBLR" and ft["spreadBps"] == 200, str(ft))
st, fs = call("GET", f"/decision/api/repayments/{ref}/schedule?facilityRef={fref}&method=EMI&frequency=MONTHLY")
fs = must(st, fs, "FLOATING schedule")
check("rateSource references the benchmark", fs["rateSource"].startswith("BENCHMARK:EBLR"), str(fs["rateSource"]))
check("annualRate = EBLR (8.7%) + 200bps = 10.7%", abs(fs["annualRate"] - 0.107) < 1e-6, str(fs["annualRate"]))
check("rows carry periodRate", all(r.get("periodRate") is not None for r in fs["rows"]), str(fs["rows"][0]))

# Toggling the benchmark feeds through immediately on the next schedule call.
rec_id = None
st, rec_arr = call("GET", "/config/api/masters/BENCHMARK")
for r in rec_arr or []:
    if r["recordKey"] == "EBLR":
        rec_id = r["id"]; break
must(200, rec_arr, "benchmark master")
st, prop = call("POST", "/config/api/masters/BENCHMARK",
                {"recordKey": "EBLR", "jurisdiction": None,
                 "payload": {"currency": "INR", "currentRate": 0.0920,
                             "displayName": "EBLR (hiked)"}},
                actor="config.admin")
prop = must(st, prop, "propose new EBLR")
call("POST", f"/config/api/masters/records/{prop['id']}/approve", actor="config.checker")
st, fs2 = call("GET", f"/decision/api/repayments/{ref}/schedule?facilityRef={fref}&method=EMI&frequency=MONTHLY")
fs2 = must(st, fs2, "FLOATING schedule after EBLR hike")
check("schedule reflects the new EBLR (9.2 + 2.0 = 11.2%)",
      abs(fs2["annualRate"] - 0.112) < 1e-6, str(fs2["annualRate"]))
check("payment rose with the higher rate", fs2["rows"][0]["payment"] > fs["rows"][0]["payment"],
      f"old {fs['rows'][0]['payment']} vs new {fs2['rows'][0]['payment']}")

print(f"\n== Post-disbursement e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
