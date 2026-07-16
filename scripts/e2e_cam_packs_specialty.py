#!/usr/bin/env python3
"""
CAM-format config packs — SPECIALTY / FI cluster — e2e (gateway :8080).

Seeds the 8 SPECIALTY/FI CAM formats (via scripts/seed_cam_packs_specialty.py,
pure config-as-data) and proves the EXISTING config-driven engines resolve a
segment-appropriate CAM for each, with NO service/frontend code:

  1. MODEL_DEFINITION resolves per segment (most-specific match) with the
     segment-appropriate QUALITATIVE + QUANTITATIVE sections and mandatory params.
  2. FINANCIAL_TEMPLATE resolves per segment and its AUGMENTATION round-trips —
     the segment-appropriate prudential input lines + formula ratios (CRAR/Tier-1/
     GNPA/LCR for banks, solvency/combined for insurers, net-capital for brokers,
     hedge-ratio/structured-DSCR for ECB, project-DSCR for infra, anchor-dependence
     for SCF) are all present and well-formed.
  3. CHECKLIST_MASTER + CP_MASTER resolve per format (governed keyed masters).
  4. Every new SEGMENT is selectable (CODE_VALUE) and the canonical 6 are preserved.
  5. maker != checker (SoD) is enforced on the governed approval path the seeder uses.
  6. The Bank/FI prudential chart augmentation is proven to actually COMPUTE through
     the real spreading engine (CRAR%, GNPA%, LCR% flow out of a live spread).
  7. Seeding is idempotent (a second run creates nothing).

Everything here is exercised through the gateway; the seeder itself only uses the
generic master API + checker approval. The authoritative figure path is untouched
(these are advisory CAM-scaffolding config records).
"""
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import seed_cam_packs_specialty as packs  # noqa: E402  (sibling module in scripts/)

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
packs.GW = GW  # keep the seeder and this suite pointed at the same gateway
PASS, FAIL = 0, 0

# The canonical six wholesale segments that must survive our SEGMENT extension.
CANONICAL_SEGMENTS = ["MID_CORPORATE", "LARGE_CORPORATE", "SME",
                      "PROJECT_FINANCE", "TRADE_FINANCE", "FINANCIAL_INSTITUTION"]


def call(method, path, body=None, actor="rm.user"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Actor", actor)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, (json.loads(txt) if txt else None)
        except Exception:
            return e.code, txt
    except urllib.error.URLError as e:
        return 0, {"message": str(e.reason)}


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
    return {"value": v, "sourceDocument": "cam.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


# ============================================================ 0. seed the 8 packs
print("== 0. Seed the 8 SPECIALTY/FI CAM packs (config-as-data, maker!=checker) ==")
try:
    summary = packs.seed_all(verbose=False)
except packs.SeedError as e:
    print(f"  ERROR seed_all: {e}")
    sys.exit(1)
check("seed_all reports 8 CAM formats", summary["formats"] == 8, str(summary))
check("seed_all completed without leaving records unresolved (created+skipped covers 4 masters x 8)",
      (summary["created"] + summary["skipped"]) >= 32, str(summary))


# ============================================================ 1. MODEL_DEFINITION resolution
print("\n== 1. Each format resolves its MODEL_DEFINITION by segment (most-specific) ==")
for fmt in packs.FORMATS:
    seg = fmt["segment"]
    st, r = call("GET", f"/config/api/models/resolve?jurisdiction=IN-RBI&segment={seg}")
    if st != 200 or not isinstance(r, dict):
        check(f"[{seg}] MODEL_DEFINITION resolves", False, f"HTTP {st} {r}")
        continue
    payload = r.get("payload") or {}
    check(f"[{seg}] resolves modelKey={fmt['modelKey']}",
          payload.get("modelKey") == fmt["modelKey"], str(payload.get("modelKey")))
    kinds = sorted(s.get("kind") for s in payload.get("sections", []))
    check(f"[{seg}] model has QUALITATIVE + QUANTITATIVE sections",
          kinds == ["QUALITATIVE", "QUANTITATIVE"], str(kinds))
    mandatory = (payload.get("constraints") or {}).get("mandatory") or []
    check(f"[{seg}] mandatory params carried ({len(mandatory)})",
          mandatory == fmt["model"]["constraints"]["mandatory"], str(mandatory))

# The specialty models must NOT shadow the corporate default for the standard
# corporate segment (they pin a different, more-specific segment).
st, r_corp = call("GET", "/config/api/models/resolve?jurisdiction=IN-RBI&segment=MID_CORPORATE")
r_corp = must(st, r_corp, "resolve MID_CORPORATE")
check("MID_CORPORATE still resolves the corporate default (specialty models don't shadow it)",
      r_corp["payload"]["modelKey"] == "corporate-rating-v1", str(r_corp["payload"].get("modelKey")))


# ============================================================ 2. FINANCIAL_TEMPLATE augmentation
print("\n== 2. Each format resolves its FINANCIAL_TEMPLATE augmentation (prudential chart) ==")
for fmt in packs.FORMATS:
    seg = fmt["segment"]
    st, r = call("GET", f"/config/api/financial-templates/resolve?jurisdiction=IN-RBI&segment={seg}")
    if st != 200 or not isinstance(r, dict):
        check(f"[{seg}] FINANCIAL_TEMPLATE resolves", False, f"HTTP {st} {r}")
        continue
    payload = r.get("payload") or {}
    check(f"[{seg}] resolves templateKey={fmt['templateKey']}",
          payload.get("templateKey") == fmt["templateKey"], str(payload.get("templateKey")))
    declared_ratios = [x["key"] for x in fmt["template"]["extraRatios"]]
    got_ratios = [x.get("key") for x in payload.get("extraRatios", [])]
    check(f"[{seg}] prudential ratios round-trip: {declared_ratios}",
          got_ratios == declared_ratios, str(got_ratios))
    declared_inputs = [x["key"] for x in fmt["template"]["extraInputLines"]]
    got_inputs = [x.get("key") for x in payload.get("extraInputLines", [])]
    check(f"[{seg}] prudential input lines round-trip ({len(declared_inputs)})",
          got_inputs == declared_inputs, str(got_inputs))
    # Every extra ratio references at least one declared line (canonical or template extra).
    all_keys = set(declared_inputs) | {"REVENUE", "COGS", "NET_WORTH", "CFO", "TOTAL_DEBT",
                                       "EBITDA", "TOTAL_ASSETS", "CURRENT_ASSETS", "CURRENT_LIABILITIES"}
    formulas_ok = all(any(k in x.get("formula", "") for k in all_keys)
                      for x in payload.get("extraRatios", []))
    check(f"[{seg}] every prudential ratio formula references a declared line", formulas_ok)

# Named FI/insurer/broker signatures — proves the charts differ from a corporate chart.
SIGNATURE = {"BANK_FI": "CRAR_PCT", "INSURANCE": "SOLVENCY_RATIO",
             "BROKER_CAPITAL_MARKET": "NET_CAPITAL_RATIO", "IBPC": "ISSUER_CRAR_PCT",
             "ECB_STRUCTURED": "HEDGE_RATIO", "INFRA_PRIORITY": "PROJECT_DSCR",
             "SCF_VENDOR": "ANCHOR_DEPENDENCE", "SCF_DEALER": "INVENTORY_TURNOVER"}
for seg, sig in SIGNATURE.items():
    st, r = call("GET", f"/config/api/financial-templates/resolve?jurisdiction=IN-RBI&segment={seg}")
    keys = [x.get("key") for x in (r.get("payload", {}).get("extraRatios", []) if isinstance(r, dict) else [])]
    check(f"[{seg}] carries its signature prudential ratio {sig}", sig in keys, str(keys))


# ============================================================ 3. CHECKLIST_MASTER resolution
print("\n== 3. Each format resolves its CHECKLIST_MASTER (governed keyed master) ==")
for fmt in packs.FORMATS:
    key = fmt["checklistKey"]
    st, r = call("GET", f"/config/api/masters/CHECKLIST_MASTER/{key}")
    if st != 200 or not isinstance(r, dict):
        check(f"[{fmt['segment']}] CHECKLIST_MASTER/{key} resolves ACTIVE", False, f"HTTP {st} {r}")
        continue
    items = (r.get("payload") or {}).get("items") or []
    check(f"[{fmt['segment']}] CHECKLIST_MASTER/{key} ACTIVE with {len(items)} items",
          r.get("status") == "ACTIVE" and items == fmt["checklist"], f"status={r.get('status')} items={items}")
    check(f"[{fmt['segment']}] checklist key has no 'SECURED' substring (CadService picker unperturbed)",
          "SECURED" not in key, key)

# The existing corporate CAD checklist is still the one CadService's global picker
# selects (first "SECURED" key) — our CAM checklists never displaced it.
st, all_ck = call("GET", "/config/api/masters/CHECKLIST_MASTER")
all_ck = must(st, all_ck, "list CHECKLIST_MASTER")
secured = sorted(m["recordKey"] for m in all_ck if "SECURED" in m["recordKey"] and m.get("status") == "ACTIVE")
check("CORP_TERM_LOAN_SECURED remains the sole/first 'SECURED' checklist (picker unchanged)",
      secured and secured[0] == "CORP_TERM_LOAN_SECURED", str(secured))


# ============================================================ 4. CP_MASTER resolution
print("\n== 4. Each format resolves its CP_MASTER (governed keyed master) ==")
for fmt in packs.FORMATS:
    key = fmt["cpKey"]
    st, r = call("GET", f"/config/api/masters/CP_MASTER/{key}")
    if st != 200 or not isinstance(r, dict):
        check(f"[{fmt['segment']}] CP_MASTER/{key} resolves ACTIVE", False, f"HTTP {st} {r}")
        continue
    items = (r.get("payload") or {}).get("items") or []
    codes = [it.get("code") for it in items]
    check(f"[{fmt['segment']}] CP_MASTER/{key} ACTIVE with {len(items)} CP items",
          r.get("status") == "ACTIVE" and len(items) == len(fmt["cp"]), f"status={r.get('status')} codes={codes}")
    mandatory = [it for it in items if it.get("mandatory")]
    check(f"[{fmt['segment']}] CP list has mandatory items + a MAC condition",
          len(mandatory) >= 1 and any(it.get("code") == "CP-MAC" for it in items), str(codes))


# ============================================================ 5. SEGMENT selectability
print("\n== 5. Every new SEGMENT is selectable; the canonical six are preserved ==")
st, seg_set = call("GET", "/config/api/code-values/SEGMENT")
seg_set = must(st, seg_set, "code-values SEGMENT")
codes = [v["code"] for v in seg_set["values"]]
for fmt in packs.FORMATS:
    check(f"SEGMENT dropdown carries {fmt['segment']}", fmt["segment"] in codes, str(codes))
check("all six canonical wholesale segments preserved",
      all(s in codes for s in CANONICAL_SEGMENTS), str(codes))
labels_ok = all(v.get("label") for v in seg_set["values"])
check("every SEGMENT value still carries a label (well-formed dropdown)", labels_ok)


# ============================================================ 6. maker != checker (SoD)
print("\n== 6. Maker != checker (SoD) enforced on the governed approval path ==")
probe = {"modelKey": "cam-sod-probe-v1", "displayName": "SoD probe",
         "selector": {"segment": "CAM_SOD_PROBE"},
         "constraints": {"minAnswered": 1, "mandatory": ["q1"]},
         "scoring": {"bands": [{"band": "STRONG", "min": 67}, {"band": "WEAK", "min": 0}]},
         "sections": [{"key": "QUALITATIVE", "kind": "QUALITATIVE", "label": "Q", "weight": 1.0,
                       "questions": [{"key": "q1", "type": "DROPDOWN", "label": "View", "weight": 1.0,
                                      "required": True, "options": [{"label": "Yes", "score": 90},
                                                                    {"label": "No", "score": 20}]}]}]}
st, sub = call("POST", "/config/api/masters/MODEL_DEFINITION",
               {"recordKey": "cam-sod-probe-v1", "payload": probe}, actor=packs.MAKER)
sub = must(st, sub, "submit SoD probe")
check("probe submitted PENDING_APPROVAL", sub.get("status") == "PENDING_APPROVAL", str(sub.get("status")))
st, self_ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor=packs.MAKER)
check("self-approval blocked (maker==checker) -> 403", st == 403, f"HTTP {st}")
st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor=packs.CHECKER)
check("different checker approves -> ACTIVE", st == 200 and isinstance(ap, dict) and ap.get("status") == "ACTIVE",
      f"HTTP {st} {ap if not isinstance(ap, dict) else ap.get('status')}")


# ============================================================ 7. live augmentation compute (Bank/FI)
print("\n== 7. Bank/FI prudential augmentation actually COMPUTES through the spreading engine ==")
# Drive a real counterparty -> application -> spread on segment=BANK_FI and prove the
# CRAR% / GNPA% / LCR% formula ratios flow out of the live spread (not just declared).
st, cp = call("POST", "/counterparty/api/counterparties", {
    "legalName": "CAM Bank/FI Counterparty Ltd", "legalForm": "PUBLIC_LTD",
    "registrationNo": "CAMBANKFI001", "jurisdiction": "IN-RBI",
    "segment": "BANK_FI", "sector": "BANKING", "country": "IN",
    "listedEntity": True, "regulatedFi": True, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
if st != 200 or not isinstance(cp, dict) or "id" not in cp:
    check("Bank/FI counterparty created (live augmentation setup)", False, f"HTTP {st} {cp}")
else:
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "BANK_FI", "sector": "BANKING", "facilityType": "TERM_LOAN",
        "requestedAmount": 2_000_000_000, "currency": "INR", "tenorMonths": 36, "purpose": "FI line",
        "collateralType": "GOVT_SECURITIES", "collateralValue": 2_500_000_000, "secured": True}, actor="rm.user")
    if st != 200 or not isinstance(app, dict) or "reference" not in app:
        check("Bank/FI application created (live augmentation setup)", False, f"HTTP {st} {app}")
    else:
        ref = app["reference"]
        lines = {
            # canonical inputs (present so standard ratios compute; not asserted here)
            "REVENUE": line(120e9), "COGS": line(70e9), "OPERATING_EXPENSES": line(20e9),
            "DEPRECIATION": line(2e9), "INTEREST_EXPENSE": line(60e9), "TAX": line(5e9),
            "TOTAL_ASSETS": line(1500e9), "CURRENT_ASSETS": line(400e9), "CASH": line(80e9),
            "CURRENT_LIABILITIES": line(300e9), "SHORT_TERM_DEBT": line(200e9),
            "LONG_TERM_DEBT": line(300e9), "CURRENT_PORTION_LTD": line(50e9),
            "NET_WORTH": line(150e9), "CFO": line(40e9),
            # fin-bank-fi prudential inputs -> CRAR% = 180/1000*100 = 18.0, GNPA% = 40/1000*100 = 4.0,
            # LCR% = 120/100*100 = 120.0
            "CAPITAL_FUNDS": line(180e9), "TIER1_CAPITAL": line(140e9), "RWA_TOTAL": line(1000e9),
            "GROSS_NPA": line(40e9), "NET_NPA": line(10e9), "GROSS_ADVANCES": line(1000e9),
            "HQLA": line(120e9), "NET_CASH_OUTFLOWS_30D": line(100e9),
        }
        st, a = call("POST", f"/origination/api/applications/{ref}/spread",
                     {"periods": [{"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "lines": lines}]},
                     actor="analyst.user")
        if st != 200 or not isinstance(a, dict):
            check("Bank/FI spread accepted (live augmentation)", False, f"HTTP {st} {a}")
        else:
            check("Bank/FI deal auto-resolves the fin-bank-fi chart (segment-driven)",
                  a.get("financialTemplate") == "fin-bank-fi", str(a.get("financialTemplate")))
            ratios = a["periods"][0]["ratios"]
            check("CRAR_PCT computed by formula (180/1000*100 = 18.0)",
                  abs(ratios.get("CRAR_PCT", 0) - 18.0) < 0.05, str(ratios.get("CRAR_PCT")))
            check("GNPA_RATIO computed by formula (40/1000*100 = 4.0)",
                  abs(ratios.get("GNPA_RATIO", 0) - 4.0) < 0.05, str(ratios.get("GNPA_RATIO")))
            check("LCR computed by formula (120/100*100 = 120.0)",
                  abs(ratios.get("LCR", 0) - 120.0) < 0.1, str(ratios.get("LCR")))
            check("standard corporate ratios still present alongside the prudential extras",
                  all(k in ratios for k in ("NET_LEVERAGE", "CURRENT_RATIO")), str(sorted(ratios.keys())))
            grid = [c["taxonomyKey"] for c in a["periods"][0]["lines"]]
            check("prudential input line CAPITAL_FUNDS appears in the spread grid",
                  "CAPITAL_FUNDS" in grid, str(grid))


# ============================================================ 8. idempotency
print("\n== 8. Re-seeding is idempotent (a second run creates nothing) ==")
try:
    again = packs.seed_all(verbose=False)
    check("second seed_all creates 0 new master records", again["created"] == 0, str(again))
    check("second seed_all reports the same 8 formats", again["formats"] == 8, str(again))
except packs.SeedError as e:
    check("second seed_all runs cleanly", False, str(e))


print(f"\n== CAM specialty packs e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
