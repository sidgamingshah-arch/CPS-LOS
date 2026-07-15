#!/usr/bin/env python3
"""
CAM-format config packs — CORE LENDING cluster — e2e.

Seeds the 8 core-lending CAM formats (via scripts/seed_cam_packs_core.py, imported
and run in-process) and proves — for EACH format — that the EXISTING engines produce
a segment-appropriate CAM purely from the authored config-as-data, with no code branch:

  1. The format's SEGMENT is selectable (present in the CODE_VALUE/SEGMENT domain).
  2. The MODEL_DEFINITION resolves by segment (config resolver, most-specific wins)
     with QUALITATIVE + QUANTITATIVE sections.
  3. The FINANCIAL_TEMPLATE resolves by segment and declares the authored extra ratios.
  4. An application on that segment picks up the config THROUGH origination: the spread
     runs the resolved template, augments the chart with the authored extra input line,
     and computes the authored formula ratio to the expected value.
  5. The CHECKLIST_MASTER materialises with the authored items (governed master).
  6. The CP register materialises from the CP_MASTER template (the authored mandatory
     CP codes appear on the deal's facility).

Plus: maker != checker is enforced on the master approvals (self-approve -> 403), and
the seed is idempotent (a second run creates nothing).

Runs against the gateway (default :8080; override with HELIX_GATEWAY).
"""
import json
import os
import sys
import urllib.error
import urllib.request

# Import the seeder (same directory) regardless of the process CWD.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import seed_cam_packs_core as seed  # noqa: E402

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
PASS, FAIL = 0, 0
MAKER, CHECKER = seed.MAKER, seed.CHECKER


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
    return {"value": v, "sourceDocument": "cam.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def spread_lines(fmt):
    """Canonical base lines + this format's extra input values, each wrapped as a spread cell."""
    merged = dict(seed.BASE_LINES)
    merged.update(fmt["extraInputValues"])
    return {k: line(v) for k, v in merged.items()}


def q_in_model(payload, key):
    for s in payload.get("sections", []):
        for qq in s.get("questions", []):
            if qq.get("key") == key:
                return qq
    return None


# ============================================================ seed (idempotent)
print("== 0. Seed the 8 CAM core-lending formats (config-as-data) ==")
# Reachability.
st, _ = call("GET", "/config/api/masters/CODE_VALUE/SEGMENT")
must(st, _, "gateway reachable (SEGMENT master)")
stats = seed.run_seed(GW, verbose=True)
check("seed completed with no errors", not stats.errors, str(stats.errors[:3]))
check("seed created config records on the first run (or all already present)",
      (stats.created + stats.skipped) > 0, f"created={stats.created} skipped={stats.skipped}")


# ============================================================ 1. segments selectable
print("\n== 1. All 8 CAM segments are selectable (CODE_VALUE/SEGMENT) ==")
st, seg = call("GET", "/config/api/code-values/SEGMENT")
seg = must(st, seg, "resolve SEGMENT domain")
seg_codes = [v["code"] for v in seg["values"]]
for f in seed.FORMATS:
    check(f"segment {f['segment']} present in the SEGMENT dropdown", f["segment"] in seg_codes,
          str(seg_codes))
# The 6 canonical enum segments must still be there (extension is additive).
canonical = ["MID_CORPORATE", "LARGE_CORPORATE", "SME", "PROJECT_FINANCE",
             "TRADE_FINANCE", "FINANCIAL_INSTITUTION"]
check("canonical enum segments preserved (extension is additive)",
      all(c in seg_codes for c in canonical), str(seg_codes))


# ============================================================ 2..6 per-format proofs
for f in seed.FORMATS:
    print(f"\n== {f['name']} (segment {f['segment']}) ==")

    # ---- 2. MODEL_DEFINITION resolves by segment
    st, rm = call("GET", f"/config/api/models/resolve?jurisdiction=IN-RBI&segment={f['segment']}")
    rm = must(st, rm, f"resolve model {f['segment']}")
    check("MODEL_DEFINITION resolves by segment (most-specific wins)",
          rm["payload"]["modelKey"] == f["modelKey"], str(rm["payload"].get("modelKey")))
    kinds = sorted(s["kind"] for s in rm["payload"]["sections"])
    check("model carries QUALITATIVE + QUANTITATIVE sections",
          kinds == ["QUALITATIVE", "QUANTITATIVE"], str(kinds))
    # A distinctive authored qualitative question is present.
    first_qual_key = f["qual"][0]["key"]
    check(f"authored qualitative question '{first_qual_key}' present",
          q_in_model(rm["payload"], first_qual_key) is not None, first_qual_key)
    # A module-sourced quantitative question pulls from a spreading ratio.
    quant_q = q_in_model(rm["payload"], f["quant"][0]["key"])
    check("quantitative question is MODULE-sourced from a spreading ratio",
          quant_q and (quant_q.get("source") or {}).get("kind") == "MODULE"
          and str((quant_q.get("source") or {}).get("ref", "")).startswith("RATIO:"),
          str(quant_q.get("source") if quant_q else None))

    # ---- 3. FINANCIAL_TEMPLATE resolves by segment
    st, rt = call("GET", f"/config/api/financial-templates/resolve?jurisdiction=IN-RBI&segment={f['segment']}")
    rt = must(st, rt, f"resolve template {f['segment']}")
    check("FINANCIAL_TEMPLATE resolves by segment",
          rt["payload"]["templateKey"] == f["templateKey"], str(rt["payload"].get("templateKey")))
    tmpl_ratio_keys = [r["key"] for r in rt["payload"].get("extraRatios", [])]
    assert_key, assert_val = f["assertRatio"]
    check(f"template declares the authored extra ratio {assert_key}",
          assert_key in tmpl_ratio_keys, str(tmpl_ratio_keys))
    tmpl_input_keys = [l["key"] for l in rt["payload"].get("extraInputLines", [])]
    check("template declares the authored extra input line(s)",
          all(k in tmpl_input_keys for k in f["extraInputValues"].keys()), str(tmpl_input_keys))

    # ---- 4. An application on this segment picks up the config through origination
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"CAM {f['segment']} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"CAMCORE{f['segment']}", "jurisdiction": "IN-RBI",
        "segment": f["segment"], "sector": f["sector"], "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "create counterparty")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": f["segment"], "facilityType": f["cpFacilityType"],
        "requestedAmount": 500_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "CAM e2e",
        "collateralType": "PROPERTY", "collateralValue": 600_000_000, "secured": True}, actor="rm.user")
    app = must(st, app, "create application")
    ref = app["reference"]
    st, a = call("POST", f"/origination/api/applications/{ref}/spread",
                 {"periods": [{"label": "FY2024", "gaap": "IND_AS", "currency": "INR",
                               "lines": spread_lines(f)}]}, actor="analyst.user")
    a = must(st, a, "post spread")
    check("spread auto-resolved this segment's FINANCIAL_TEMPLATE",
          a.get("financialTemplate") == f["templateKey"], str(a.get("financialTemplate")))
    ratios = a["periods"][0]["ratios"]
    check(f"authored extra ratio {assert_key} computed by formula ({assert_val})",
          assert_key in ratios and abs(ratios[assert_key] - assert_val) < 0.01,
          f"{assert_key}={ratios.get(assert_key)}")
    # Standard ratios still present alongside the extras (zero regression on the canonical chart).
    check("standard canonical ratios still present alongside the extras",
          all(k in ratios for k in ("NET_LEVERAGE", "DSCR", "CURRENT_RATIO")), str(sorted(ratios.keys())))
    grid_keys = [c["taxonomyKey"] for c in a["periods"][0]["lines"]]
    check("authored extra input line appears in the spread grid",
          all(k in grid_keys for k in f["extraInputValues"].keys()), str(grid_keys))

    # ---- 5. CHECKLIST_MASTER materialises with the authored items
    st, cl = call("GET", f"/config/api/masters/CHECKLIST_MASTER/{f['checklistKey']}")
    cl = must(st, cl, "resolve checklist master")
    check("CHECKLIST_MASTER materialises with the authored items",
          cl["payload"].get("items") == f["checklistItems"], str(cl["payload"].get("items")))

    # ---- 6. CP register materialises from CP_MASTER (by the facility's facility-type)
    st, seeded = call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
    seeded = must(st, seeded, "seed CP register")
    st, reg = call("GET", f"/decision/api/cps/{ref}")
    reg = must(st, reg, "read CP register")
    reg_codes = {c["code"] for c in reg}
    want_codes = {c["code"] for c in f["cpItems"] if c["mandatory"]}
    check("CP register materialised the authored mandatory CP codes",
          want_codes.issubset(reg_codes), f"want={sorted(want_codes)} got={sorted(reg_codes)}")


# ============================================================ 7. maker != checker (SoD)
print("\n== 7. Maker != checker enforced on master approvals (SoD) ==")
st, sub = call("POST", "/config/api/masters/CAM_SOD_PROBE",
               {"recordKey": "probe", "payload": {"note": "sod probe"}}, actor=MAKER)
sub = must(st, sub, "submit SoD probe")
check("probe submitted PENDING_APPROVAL", sub.get("status") == "PENDING_APPROVAL", str(sub.get("status")))
st, self_ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor=MAKER)
check("self-approval blocked (maker == checker) -> 403", st == 403, f"HTTP {st}")
st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor=CHECKER)
check("different checker approves -> ACTIVE", st == 200 and ap.get("status") == "ACTIVE", f"HTTP {st}")


# ============================================================ 8. idempotency
print("\n== 8. Seed is idempotent (a second run creates nothing) ==")
stats2 = seed.run_seed(GW, verbose=False)
check("second seed run creates zero records (idempotent)", stats2.created == 0,
      f"created={stats2.created} skipped={stats2.skipped}")
check("second seed run has no errors", not stats2.errors, str(stats2.errors[:3]))


print(f"\n== CAM core-lending packs e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
