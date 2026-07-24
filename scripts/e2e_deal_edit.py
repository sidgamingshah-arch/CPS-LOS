#!/usr/bin/env python3
"""
Deal-CRUD editing + interchangeable-group cap — e2e (batch4/deal-crud).

Proves three things about editing a deal's STRUCTURE (facilities / collaterals / sublimits):

  1. EDIT AFTER INPUT — facilities, collaterals and sublimits can be edited in place (not only
     added), and a collateral can be deleted, while the deal is still pre-sanction. The
     auto-created PRIMARY facility is NOT editable. A collateral's MARKET VALUE is NOT editable
     via the descriptive PATCH (it must route through the collateral-intel revalue -> review
     apply gate), so a marketValue in the PATCH body is ignored and left unchanged.

  2. INTERCHANGEABLE-GROUP CAP — a fungible group's combined cap is its SHARED cap (max member),
     NOT the sum of members; and the facility-cap check counts a fungible group ONCE (its shared
     cap) rather than summing members, so two same-group members each near the facility cap both
     fit. Hard (non-fungible) sublimits still sum on top. (Runtime pooled-headroom enforcement in
     limit-service is unchanged — this suite only exercises the origination cap/display view.)

  3. POST-SANCTION FREEZE — once the deal is past structuring (APPROVED), the structure of record
     is frozen: facility / collateral / sublimit edits and deletes are rejected 409 and must go
     through the amendment path.

Standalone against the gateway on :8080; NOT registered in run_regression; binds no port.
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


def msg_of(b):
    return (b.get("message") if isinstance(b, dict) else str(b)) or ""


def near(a, b, tol=1.0):
    return a is not None and abs(a - b) < tol


# ---------------------------------------------------------------- setup: a pre-sanction deal
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Deal-CRUD Widgets Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": "DCRUD1",
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp = must(st, cp, "counterparty")
st, app = call("POST", "/origination/api/applications", {
    "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
    "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
    "requestedAmount": 500_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
    "collateralType": "PROPERTY", "collateralValue": 750_000_000, "secured": True}, actor="rm.user")
app = must(st, app, "application")
ref = app["reference"]
check("new application starts in a pre-sanction structuring status (INTAKE)", app["status"] == "INTAKE", app["status"])

# ============================================================ 1. facilities: add (multi) + edit
print("\n== 1. Facilities — add (multi) + edit in place; PRIMARY not editable ==")
st, f1 = call("POST", f"/origination/api/applications/{ref}/facilities",
              {"facilityType": "WORKING_CAPITAL", "amount": 120_000_000, "currency": "INR",
               "tenorMonths": 12, "purpose": "WC line"}, actor="rm.user")
f1 = must(st, f1, "add facility F1")
st, f2 = call("POST", f"/origination/api/applications/{ref}/facilities",
              {"facilityType": "GUARANTEE", "amount": 60_000_000, "currency": "INR",
               "tenorMonths": 24, "purpose": "BG line"}, actor="rm.user")
f2 = must(st, f2, "add facility F2")
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
facs = must(st, facs, "list facilities")
primary = next((f for f in facs if f["primary"]), None)
check("2 facilities added on top of the auto-created primary (3 total)", len(facs) == 3 and primary is not None, str(len(facs)))

st, edited = call("PATCH", f"/origination/api/applications/facilities/{f1['id']}",
                  {"amount": 150_000_000, "purpose": "Revised WC line"}, actor="rm.user")
edited = must(st, edited, "edit facility F1")
check("facility edited in place (amount 120m -> 150m, purpose updated)",
      near(edited["amount"], 150_000_000) and edited["purpose"] == "Revised WC line", str(edited.get("amount")))

st, r = call("PATCH", f"/origination/api/applications/facilities/{primary['id']}",
             {"amount": 999_000_000}, actor="rm.user")
check("editing the auto-created PRIMARY facility is rejected (400)", st == 400, f"{st} {r}")
check("...rejection cites the primary facility", "primary" in msg_of(r).lower(), msg_of(r))
st, facs = call("GET", f"/origination/api/applications/{ref}/facilities")
check("primary facility amount unchanged after the rejected edit",
      near(next(f["amount"] for f in facs if f["primary"]), 500_000_000), str(facs))

# audit trail: the edit stamped a HUMAN FACILITY_UPDATED event
st, aud = call("GET", f"/origination/api/audit/subject?type=ProposedFacility&id={f1['id']}")
aud = must(st, aud, "facility audit")
check("facility edit stamped a HUMAN FACILITY_UPDATED audit event",
      any(e.get("eventType") == "FACILITY_UPDATED" and e.get("actorType") == "HUMAN" for e in aud), str(aud[:1]))

# ============================================================ 2. collateral: edit + delete; MV protected
print("\n== 2. Collateral — edit descriptive fields + delete; market value stays on the revalue gate ==")
st, c2 = call("POST", f"/origination/api/applications/{ref}/collaterals",
              {"collateralType": "EQUITY_LISTED", "description": "Pledged shares",
               "marketValue": 50_000_000, "haircut": 0.25, "perfectionStatus": "IN_PROGRESS"}, actor="analyst.user")
c2 = must(st, c2, "add collateral C2")
st, cols = call("GET", f"/origination/api/applications/{ref}/collaterals")
check("collateral added on top of the auto-created primary (2 total)", len(cols) == 2, str(len(cols)))

# PATCH descriptive fields AND (deliberately) a marketValue that must be ignored.
st, ec = call("PATCH", f"/origination/api/applications/collaterals/{c2['id']}",
              {"description": "Updated pledge", "haircut": 0.30, "owner": "Promoter",
               "perfectionStatus": "PERFECTED", "marketValue": 999_000_000}, actor="analyst.user")
ec = must(st, ec, "edit collateral C2")
check("collateral descriptive fields edited (description/haircut/owner/perfection)",
      ec["description"] == "Updated pledge" and near(ec["haircut"], 0.30)
      and ec["owner"] == "Promoter" and ec["perfectionStatus"] == "PERFECTED", str(ec))
check("market value is NOT overwritten by the descriptive PATCH (stays 50m — revalue gate owns it)",
      near(ec["marketValue"], 50_000_000), str(ec.get("marketValue")))

st, aud = call("GET", f"/origination/api/audit/subject?type=Collateral&id={c2['id']}")
aud = must(st, aud, "collateral audit")
check("collateral edit stamped a HUMAN COLLATERAL_UPDATED audit event",
      any(e.get("eventType") == "COLLATERAL_UPDATED" and e.get("actorType") == "HUMAN" for e in aud), str(aud[:1]))

st, _ = call("DELETE", f"/origination/api/applications/collaterals/{c2['id']}", actor="analyst.user")
check("collateral deleted (parity with facility/sublimit delete)", st == 200, f"{st}")
st, cols = call("GET", f"/origination/api/applications/{ref}/collaterals")
check("deleted collateral is gone (back to 1)",
      len(cols) == 1 and all(c["id"] != c2["id"] for c in cols), str(len(cols)))

# ============================================================ 3. interchangeable-group cap (F1 = 150m)
print("\n== 3. Interchangeable group — shared cap (max), no double-count of fungible members ==")


def add_sub(code, ptype, amount, group=None):
    return call("POST", f"/origination/api/applications/facilities/{f1['id']}/sublimits",
                {"code": code, "productType": ptype, "amount": amount, "currency": "INR",
                 "tenorMonths": 12, "purpose": code, "interchangeableGroup": group}, actor="analyst.user")


st, cc = add_sub("CC", "CASH_CREDIT", 120_000_000, "WC_FUNDED")
cc = must(st, cc, "add CC")
st, od = add_sub("OD", "OVERDRAFT", 120_000_000, "WC_FUNDED")
check("2nd fungible member (OD 120m) fits a 150m facility next to CC 120m — group counted ONCE, not summed (240m would breach)",
      st == 200, f"{st} {od}")
od = must(st, od, "add OD")


def f1_view():
    st, fv = call("GET", f"/origination/api/applications/{ref}/facilities/view")
    fv = must(st, fv, "facility view")
    return next((f for f in fv if f["id"] == f1["id"]), {})


v = f1_view()
grp = next((g for g in v.get("interchangeabilityGroups", []) if g["groupKey"] == "WC_FUNDED"), None)
check("WC_FUNDED group has 2 members", grp is not None and grp["memberCount"] == 2, str(v.get("interchangeabilityGroups")))
check("group combined cap is the SHARED cap 120m (max member), NOT the 240m sum",
      grp is not None and near(grp["combinedCap"], 120_000_000), str(grp))
check("facility effective allocation counts the pool once (120m), headroom 30m",
      near(v.get("sublimitTotal"), 120_000_000) and near(v.get("sublimitHeadroom"), 30_000_000),
      f"total={v.get('sublimitTotal')} headroom={v.get('sublimitHeadroom')}")

st, r = add_sub("BG_BIG", "BANK_GUARANTEE", 40_000_000, None)   # hard, 120(pool)+40 = 160 > 150
check("a hard sublimit that overflows the pool+hard cap is rejected (400)", st == 400, f"{st} {r}")
st, bg = add_sub("BG", "BANK_GUARANTEE", 20_000_000, None)      # hard, 120(pool)+20 = 140 <= 150
bg = must(st, bg, "add BG hard")
v = f1_view()
check("hard sublimit sums on top of the pooled cap (effective 140m, headroom 10m)",
      near(v.get("sublimitTotal"), 140_000_000) and near(v.get("sublimitHeadroom"), 10_000_000),
      f"total={v.get('sublimitTotal')} headroom={v.get('sublimitHeadroom')}")

# ---- edit a sublimit in place: cap re-checked with the group counted once ----
st, r = call("PATCH", f"/origination/api/applications/sublimits/{od['id']}",
             {"amount": 135_000_000}, actor="analyst.user")   # pool max(120,135)=135 + hard 20 = 155 > 150
check("sublimit edit that would breach the facility cap is rejected (400)", st == 400, f"{st} {r}")
st, r = call("PATCH", f"/origination/api/applications/sublimits/{od['id']}",
             {"amount": 125_000_000}, actor="analyst.user")   # pool max(120,125)=125 + hard 20 = 145 <= 150
r = must(st, r, "edit sublimit OD")
check("sublimit edited in place (OD 120m -> 125m)", near(r["amount"], 125_000_000), str(r.get("amount")))
v = f1_view()
grp = next((g for g in v.get("interchangeabilityGroups", []) if g["groupKey"] == "WC_FUNDED"), None)
check("group shared cap tracks the new max member (125m), still not the sum",
      grp is not None and near(grp["combinedCap"], 125_000_000), str(grp))
check("effective allocation after edit is 145m (pool 125 + hard 20), headroom 5m",
      near(v.get("sublimitTotal"), 145_000_000) and near(v.get("sublimitHeadroom"), 5_000_000),
      f"total={v.get('sublimitTotal')} headroom={v.get('sublimitHeadroom')}")

st, aud = call("GET", f"/origination/api/audit/subject?type=Sublimit&id={od['id']}")
aud = must(st, aud, "sublimit audit")
check("sublimit edit stamped a HUMAN SUBLIMIT_UPDATED audit event",
      any(e.get("eventType") == "SUBLIMIT_UPDATED" and e.get("actorType") == "HUMAN" for e in aud), str(aud[:1]))

# ============================================================ 4. post-sanction freeze -> 409
print("\n== 4. Post-sanction — structure frozen; edits/deletes rejected 409 (use amendment path) ==")
st, moved = call("PATCH", f"/origination/api/applications/{ref}/status", {"status": "APPROVED"}, actor="system")
moved = must(st, moved, "move to APPROVED")
check("deal moved to a post-sanction status (APPROVED)", moved["status"] == "APPROVED", moved["status"])

st, r = call("PATCH", f"/origination/api/applications/facilities/{f1['id']}", {"amount": 130_000_000}, actor="rm.user")
check("facility edit rejected post-sanction (409)", st == 409, f"{st} {r}")
check("...409 points to the amendment path", "amendment" in msg_of(r).lower(), msg_of(r))
st, r = call("PATCH", f"/origination/api/applications/sublimits/{cc['id']}", {"amount": 100_000_000}, actor="analyst.user")
check("sublimit edit rejected post-sanction (409)", st == 409, f"{st} {r}")
st, cols = call("GET", f"/origination/api/applications/{ref}/collaterals")
prim_col = cols[0]
st, r = call("PATCH", f"/origination/api/applications/collaterals/{prim_col['id']}", {"description": "x"}, actor="analyst.user")
check("collateral edit rejected post-sanction (409)", st == 409, f"{st} {r}")
st, r = call("DELETE", f"/origination/api/applications/collaterals/{prim_col['id']}", actor="analyst.user")
check("collateral delete rejected post-sanction (409)", st == 409, f"{st} {r}")
st, r = call("DELETE", f"/origination/api/applications/sublimits/{cc['id']}", actor="analyst.user")
check("sublimit delete rejected post-sanction (409)", st == 409, f"{st} {r}")
st, r = call("DELETE", f"/origination/api/applications/facilities/{f2['id']}", actor="rm.user")
check("facility delete rejected post-sanction (409)", st == 409, f"{st} {r}")

print(f"\n== Deal-CRUD edit e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
