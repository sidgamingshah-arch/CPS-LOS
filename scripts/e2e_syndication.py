#!/usr/bin/env python3
"""
Syndication agency engine — e2e.

Builds a 3-lender syndicate on a deal and proves the agency mechanics on top of
participation capture:
  1. Syndicate book: shares from commitments, fee waterfall (arrangement/under-
     writing/agency to lead; participation to each lender on its share).
  2. Agency reconciliation: a funded drawdown allocated pro-rata; idempotent on
     drawdownRef; funded-to-date rolls up in the book.
  3. Participant feed: one canonical statement line per lender.
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


print("== 0. Setup: SYNDICATION deal with 3 lenders ==")
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Meridian Infra Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "SYND-001",
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "sector": "INFRASTRUCTURE", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "cp")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 10_000_000_000, "currency": "INR", "tenorMonths": 120, "purpose": "Highway BOT",
    "collateralType": "PROPERTY", "collateralValue": 12_000_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "app")
ref = app["reference"]

st, _ = call("POST", f"/origination/api/applications/{ref}/structure", {
    "structureType": "SYNDICATION", "leadArranger": "Helix Bank",
    "totalDealAmount": 10_000_000_000, "ourShareAmount": 5_000_000_000,
    "notes": "3-lender club"}, actor="rm.user")
# Lead 5bn, Participant A 3bn, Participant B 2bn => total 10bn; shares 50/30/20.
for role, name, ext, commit in [
        ("LEAD_BANK", "Helix Bank", "BANK-HELIX", 5_000_000_000),
        ("PARTICIPANT_LENDER", "Orient Commercial Bank", "BANK-ORIENT", 3_000_000_000),
        ("PARTICIPANT_LENDER", "Coastal Union Bank", "BANK-COASTAL", 2_000_000_000)]:
    st, _ = call("POST", f"/origination/api/applications/{ref}/structure/participants",
                 {"role": role, "name": name, "externalRef": ext, "committedAmount": commit},
                 actor="rm.user")
    must(st, _, f"add {name}")
print(f"    syndicate set on {ref}")

print("\n== 1. Syndicate book: shares + fee waterfall ==")
st, b = call("GET", f"/origination/api/syndication/{ref}/book")
b = must(st, b, "book")
check("3 lenders in book", len(b["lenders"]) == 3, str(len(b["lenders"])))
check("total commitment = 10bn", abs(b["totalCommitment"] - 10_000_000_000) < 1, str(b["totalCommitment"]))
lead = next(l for l in b["lenders"] if l["role"] == "LEAD_BANK")
pa = next(l for l in b["lenders"] if l["name"].startswith("Orient"))
pb = next(l for l in b["lenders"] if l["name"].startswith("Coastal"))
check("lead share = 0.50", abs(lead["sharePct"] - 0.50) < 1e-6, str(lead["sharePct"]))
check("participant A share = 0.30", abs(pa["sharePct"] - 0.30) < 1e-6, str(pa["sharePct"]))
check("participant B share = 0.20", abs(pb["sharePct"] - 0.20) < 1e-6, str(pb["sharePct"]))

# Fee waterfall: lead gets arrangement (75bps) + underwriting (25bps) + agency (10bps)
# on the 10bn total = 0.011 * 10bn = 110,000,000; plus participation 30bps on its 5bn.
exp_lead_arr = 10_000_000_000 * 75 / 10_000.0
exp_lead_uw = 10_000_000_000 * 25 / 10_000.0
exp_lead_ag = 10_000_000_000 * 10 / 10_000.0
exp_lead_part = 5_000_000_000 * 30 / 10_000.0
check("lead arrangement fee = 75bps on total", abs(lead["fees"]["arrangementFee"] - exp_lead_arr) < 1, str(lead["fees"]))
check("lead underwriting fee = 25bps on total", abs(lead["fees"]["underwritingFee"] - exp_lead_uw) < 1, str(lead["fees"]))
check("lead agency fee = 10bps on total", abs(lead["fees"]["agencyFee"] - exp_lead_ag) < 1, str(lead["fees"]))
check("lead participation fee = 30bps on its share", abs(lead["fees"]["participationFee"] - exp_lead_part) < 1, str(lead["fees"]))
# Participants get only participation fee (no arrangement/underwriting/agency).
check("participant A has no arrangement fee", pa["fees"]["arrangementFee"] == 0, str(pa["fees"]))
check("participant A participation = 30bps on 3bn",
      abs(pa["fees"]["participationFee"] - 3_000_000_000 * 30 / 10_000.0) < 1, str(pa["fees"]))
print(f"    lead total fee {lead['fees']['totalFee']:,.0f}; A {pa['fees']['totalFee']:,.0f}; B {pb['fees']['totalFee']:,.0f}")

print("\n== 2. Agency reconciliation: allocate a funded drawdown pro-rata ==")
st, alloc = call("POST", f"/origination/api/syndication/{ref}/allocate",
                 {"drawdownRef": "DRAW-1", "amount": 1_000_000_000, "currency": "INR"},
                 actor="agency.desk")
alloc = must(st, alloc, "allocate")
check("allocation has 3 lines", len(alloc["lines"]) == 3, str(len(alloc["lines"])))
check("allocated total = drawdown amount", abs(alloc["allocatedTotal"] - 1_000_000_000) < 1, str(alloc["allocatedTotal"]))
la = next(l for l in alloc["lines"] if l["role"] == "LEAD_BANK")
check("lead gets 50% of the draw", abs(la["allocatedAmount"] - 500_000_000) < 1, str(la["allocatedAmount"]))

print("\n== 3. Idempotency: same drawdownRef returns the existing allocation ==")
st, again = call("POST", f"/origination/api/syndication/{ref}/allocate",
                 {"drawdownRef": "DRAW-1", "amount": 1_000_000_000, "currency": "INR"}, actor="agency.desk")
again = must(st, again, "re-allocate")
check("re-allocation flagged reused=true", again["reused"] is True, str(again["reused"]))
st, ledger = call("GET", f"/origination/api/syndication/{ref}/allocations")
check("ledger not double-counted (3 rows, not 6)", len(ledger) == 3, str(len(ledger)))

print("\n== 4. Funded-to-date rolls up in the book ==")
st, b2 = call("GET", f"/origination/api/syndication/{ref}/book")
check("total funded = 1bn after one draw", abs(b2["totalFunded"] - 1_000_000_000) < 1, str(b2["totalFunded"]))
lead2 = next(l for l in b2["lenders"] if l["role"] == "LEAD_BANK")
check("lead funded-to-date = 500m", abs(lead2["fundedToDate"] - 500_000_000) < 1, str(lead2["fundedToDate"]))
check("lead undrawn = 4.5bn", abs(lead2["undrawn"] - 4_500_000_000) < 1, str(lead2["undrawn"]))

# Second drawdown accumulates.
call("POST", f"/origination/api/syndication/{ref}/allocate",
     {"drawdownRef": "DRAW-2", "amount": 2_000_000_000, "currency": "INR"}, actor="agency.desk")
st, b3 = call("GET", f"/origination/api/syndication/{ref}/book")
check("total funded = 3bn after two draws", abs(b3["totalFunded"] - 3_000_000_000) < 1, str(b3["totalFunded"]))

print("\n== 5. Participant feed (canonical downstream statement) ==")
st, feed = call("GET", f"/origination/api/syndication/{ref}/feed")
feed = must(st, feed, "feed")
check("feed destination = SYNDICATION", feed["destination"] == "SYNDICATION", str(feed.get("destination")))
check("feed has 3 participant lines", feed["recordCount"] == 3, str(feed.get("recordCount")))
check("feed line carries funded + fees",
      all("fundedToDate" in r and "totalFees" in r for r in feed["records"]), str(feed["records"])[:200])

# Feed is now persisted as an idempotent ExportBatch — same shape as every other
# downstream feed in the platform.
st, batches = call("GET", f"/origination/api/syndication/{ref}/feed/batches")
batches = must(st, batches, "feed batches")
check("feed call persisted as an ExportBatch", len(batches) >= 1, str(batches))
check("ExportBatch idempotency key follows SYND-<ref>-<asOf>",
      batches[0]["idempotencyKey"].startswith(f"SYND-{ref}-"), str(batches[0]["idempotencyKey"]))
check("ExportBatch record count matches envelope",
      batches[0]["recordCount"] == feed["recordCount"], str(batches[0]["recordCount"]))
check("ExportBatch destination = SYNDICATION",
      batches[0]["destination"] == "SYNDICATION", str(batches[0]["destination"]))

# Re-fetching the feed reuses the persisted batch (no duplicate).
st, feed2 = call("GET", f"/origination/api/syndication/{ref}/feed")
must(st, feed2, "feed reload")
st, batches2 = call("GET", f"/origination/api/syndication/{ref}/feed/batches")
batches2 = must(st, batches2, "feed batches reload")
check("re-fetching the feed on the same day reuses the ExportBatch",
      len(batches2) == len(batches), f"{len(batches)} -> {len(batches2)}")

print("\n== 6. Negative: non-syndication deal rejected ==")
st2, app2 = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 100_000_000, "currency": "INR", "tenorMonths": 36, "purpose": "x",
    "collateralType": "PROPERTY", "collateralValue": 120_000_000, "secured": True}, actor="rm.user")
st, body = call("GET", f"/origination/api/syndication/{app2['reference']}/book")
check("book on non-syndication deal returns 4xx", st in (400, 404), f"{st} {body}")

print(f"\n== Syndication e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
