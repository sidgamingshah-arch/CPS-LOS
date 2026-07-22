#!/usr/bin/env python3
"""
Global / combined cash-flow (relationship consolidated debt-service) — e2e.

portfolio-service gained a DETERMINISTIC relationship consolidation: for a borrower group it
pulls each member's latest CONFIRMED spread figures (revenue, an EBITDA proxy, CFO, and total
debt service = interest expense + current-portion LTD) via best-effort upstream reads and
consolidates them into a combined coverage view with a per-member contribution breakdown:

    combinedRevenue     = Σ member REVENUE
    combinedEbitda      = Σ member (REVENUE − COGS − OPERATING_EXPENSES)
    combinedCfo         = Σ member CFO
    combinedDebtService = Σ member (INTEREST_EXPENSE + CURRENT_PORTION_LTD)
    combinedDscr        = combinedCfo ÷ combinedDebtService

It is a read-side consolidation: it NEVER writes to any member's spread / rating / exposure.

Fixture — a group of 2 members with KNOWN latest-period spreads:
  member A  REVENUE 5.0e9  COGS 3.0e9  OPEX 0.8e9  INT 0.12e9  CPLTD 0.18e9  CFO 0.9e9
            -> EBITDA 1.2e9 · debt service 0.30e9 · DSCR 3.00
  member B  REVENUE 3.0e9  COGS 1.8e9  OPEX 0.5e9  INT 0.08e9  CPLTD 0.12e9  CFO 0.5e9
            -> EBITDA 0.7e9 · debt service 0.20e9 · DSCR 2.50
  combined  REVENUE 8.0e9 · EBITDA 1.9e9 · CFO 1.4e9 · debt service 0.50e9 · DSCR 2.80

Proves: combined DSCR = combined CFO ÷ combined debt service (hand-computed 2.80); combined
revenue = sum of members; per-member contributions are the exact confirmed-spread figures;
GLOBAL_CASHFLOW_CONSOLIDATED stamped SYSTEM (deterministic, never AI); GET-by-ref + list; and
the INVARIANT — each member's authoritative spread is BYTE-IDENTICAL after the consolidation.
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


def approx(a, b, tol=1.0):
    return a is not None and b is not None and abs(a - b) < tol


def line(v):
    return {"value": v, "sourceDocument": "gcf.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def period(label, rev, cogs, opex, intexp, cpltd, cfo):
    """Latest-period spread with explicit lines so the hand-computation is exact."""
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.02),
        "TOTAL_ASSETS": line(rev * 1.2), "CURRENT_ASSETS": line(rev * 0.5), "CASH": line(rev * 0.1),
        "CURRENT_LIABILITIES": line(rev * 0.25), "SHORT_TERM_DEBT": line(cpltd * 2.0),
        "LONG_TERM_DEBT": line(rev * 0.2), "CURRENT_PORTION_LTD": line(cpltd),
        "NET_WORTH": line(rev * 0.5), "CFO": line(cfo)}}


def member(suffix, rev, cogs, opex, intexp, cpltd, cfo):
    """cp -> app -> spread (latest = the KNOWN period) -> confirm. Returns (id, ref)."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"GCF {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"GCF{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.alice")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 1_000_000_000, "currency": "INR", "tenorMonths": 60, "purpose": "WC",
        "collateralType": "PROPERTY", "collateralValue": 1_500_000_000, "secured": True}, actor="rm.alice")
    ref = must(st, app, "app")["reference"]
    # FY2024 (ordinal 0) is the latest, KNOWN period; FY2023 is filler and does not affect `latest`.
    must(*call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        period("FY2024", rev, cogs, opex, intexp, cpltd, cfo),
        period("FY2023", rev * 0.9, cogs * 0.9, opex * 0.9, intexp * 1.1, cpltd * 1.1, cfo * 0.9),
    ]}, actor="analyst.user")[:2], "spread")
    must(*call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")[:2], "confirm")
    return cp["id"], ref


def latest_fin(ref):
    st, ci = call("GET", f"/origination/api/applications/{ref}/credit-inputs")
    ci = must(st, ci, "credit-inputs")
    return ci.get("latestFinancials", {})


# ============================================================ 0. build a 2-member group with KNOWN spreads
print("== 0. Build a 2-member borrower group with KNOWN confirmed spreads ==")
a_id, a_ref = member("A", 5.0e9, 3.0e9, 0.8e9, 0.12e9, 0.18e9, 0.9e9)   # EBITDA 1.2e9 · DS 0.30e9 · CFO 0.9e9
b_id, b_ref = member("B", 3.0e9, 1.8e9, 0.5e9, 0.08e9, 0.12e9, 0.5e9)   # EBITDA 0.7e9 · DS 0.20e9 · CFO 0.5e9

st, grp = call("POST", "/counterparty/api/initiation/groups",
               {"name": "GlobalCashflow Test Group", "groupRmId": "rm.group", "country": "IN",
                "multiCountry": False}, actor="rm.alice")
grp = must(st, grp, "group")
gref = grp["reference"]
for cid in (a_id, b_id):
    must(*call("POST", f"/counterparty/api/initiation/counterparties/{cid}/group/{grp['id']}", actor="rm.alice")[:2], "tag")

# INVARIANT baseline: each member's authoritative spread BEFORE the consolidation.
fin_before = {a_ref: json.dumps(latest_fin(a_ref), sort_keys=True),
              b_ref: json.dumps(latest_fin(b_ref), sort_keys=True)}

# ============================================================ 1. consolidate
print("\n== 1. Consolidate group cash-flow (deterministic) ==")
st, gcf = call("POST", "/portfolio/api/portfolio/global-cashflow", {"groupReference": gref}, actor="portfolio.manager")
gcf = must(st, gcf, "consolidate")
check("gcfRef generated GCF-XXXXXX + advisory",
      gcf["gcfRef"].startswith("GCF-") and len(gcf["gcfRef"]) == 10 and gcf["advisory"] is True, str(gcf.get("gcfRef")))
check("both members considered and included",
      gcf["membersConsidered"] == 2 and gcf["membersIncluded"] == 2, str(gcf)[:160])
check("no fail-soft skips (both members readable + confirmed)",
      (gcf.get("warnings") or []) == [], str(gcf.get("warnings")))

# ============================================================ 2. deterministic combined figures
print("\n== 2. Combined figures = deterministic sums; combined DSCR = combined CFO / combined debt service ==")
check("combinedRevenue = Σ members = 8.0e9", approx(gcf["combinedRevenue"], 8.0e9), gcf["combinedRevenue"])
check("combinedEbitda = Σ EBITDA proxy = 1.9e9", approx(gcf["combinedEbitda"], 1.9e9), gcf["combinedEbitda"])
check("combinedCfo = Σ CFO = 1.4e9", approx(gcf["combinedCfo"], 1.4e9), gcf["combinedCfo"])
check("combinedDebtService = Σ (INT + CPLTD) = 0.5e9", approx(gcf["combinedDebtService"], 0.5e9), gcf["combinedDebtService"])
# combined DSCR: equals the returned CFO / debt service AND the hand-computed 2.80.
expected_dscr = gcf["combinedCfo"] / gcf["combinedDebtService"]
check("combinedDscr = combinedCfo ÷ combinedDebtService (self-consistent)",
      approx(gcf["combinedDscr"], expected_dscr, 1e-6), f"{gcf['combinedDscr']} vs {expected_dscr}")
check("combinedDscr = hand-computed 2.80", approx(gcf["combinedDscr"], 2.80, 1e-6), gcf["combinedDscr"])

# per-member contributions carry the exact confirmed-spread figures.
by_ref = {m["ref"]: m for m in gcf["members"]}
ma, mb = by_ref.get(a_ref), by_ref.get(b_ref)
check("member A contribution = its exact spread (rev 5.0e9, EBITDA 1.2e9, CFO 0.9e9, DS 0.30e9, DSCR 3.00)",
      ma and approx(ma["revenue"], 5.0e9) and approx(ma["ebitda"], 1.2e9) and approx(ma["cfo"], 0.9e9)
      and approx(ma["debtService"], 0.30e9) and approx(ma["dscr"], 3.00, 1e-6), str(ma))
check("member B contribution = its exact spread (rev 3.0e9, EBITDA 0.7e9, CFO 0.5e9, DS 0.20e9, DSCR 2.50)",
      mb and approx(mb["revenue"], 3.0e9) and approx(mb["ebitda"], 0.7e9) and approx(mb["cfo"], 0.5e9)
      and approx(mb["debtService"], 0.20e9) and approx(mb["dscr"], 2.50, 1e-6), str(mb))
check("per-member revenue sums to combinedRevenue",
      approx(ma["revenue"] + mb["revenue"], gcf["combinedRevenue"]), "member revenues do not sum")

# ============================================================ 3. GET by ref + list
print("\n== 3. GET by ref + list ==")
st, got = call("GET", f"/portfolio/api/portfolio/global-cashflow/{gcf['gcfRef']}")
got = must(st, got, "get by ref")
check("fetchable by gcfRef with identical combined figures",
      got["gcfRef"] == gcf["gcfRef"] and approx(got["combinedDscr"], gcf["combinedDscr"], 1e-9), str(got.get("gcfRef")))
st, lst = call("GET", f"/portfolio/api/portfolio/global-cashflow?groupReference={gref}")
lst = must(st, lst, "list by group")
check("listed for the group", any(a["gcfRef"] == gcf["gcfRef"] for a in lst), str([a["gcfRef"] for a in lst]))

# ============================================================ 4. audit — SYSTEM (deterministic, never AI)
print("\n== 4. GLOBAL_CASHFLOW_CONSOLIDATED stamped SYSTEM (deterministic) ==")
st, trail = call("GET", f"/portfolio/api/audit/subject?type=GlobalCashflow&id={gcf['gcfRef']}")
trail = trail if isinstance(trail, list) else []
cons = [e for e in trail if e.get("eventType") == "GLOBAL_CASHFLOW_CONSOLIDATED"]
check("GLOBAL_CASHFLOW_CONSOLIDATED present + SYSTEM (engine), never AI",
      any(e.get("actorType") == "SYSTEM" for e in cons) and not any(e.get("actorType") == "AI" for e in cons),
      str([e.get("actorType") for e in cons]))

# ============================================================ 5. INVARIANT: member spreads byte-identical
print("\n== 5. INVARIANT: each member's authoritative spread is byte-identical after the consolidation ==")
check("member A spread unchanged", json.dumps(latest_fin(a_ref), sort_keys=True) == fin_before[a_ref], "member A spread moved")
check("member B spread unchanged", json.dumps(latest_fin(b_ref), sort_keys=True) == fin_before[b_ref], "member B spread moved")

# ============================================================ 6. guardrails
print("\n== 6. Guardrails ==")
st, _ = call("POST", "/portfolio/api/portfolio/global-cashflow", {"groupReference": "GRP-NOPE-999"}, actor="portfolio.manager")
check("unknown group -> 404", st == 404, st)
st, _ = call("POST", "/portfolio/api/portfolio/global-cashflow", {"groupReference": ""}, actor="portfolio.manager")
check("blank groupReference -> 400", st == 400, st)
st, _ = call("GET", "/portfolio/api/portfolio/global-cashflow/GCF-NOPE99")
check("unknown gcfRef -> 404", st == 404, st)

print(f"\nglobal cash-flow e2e: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
