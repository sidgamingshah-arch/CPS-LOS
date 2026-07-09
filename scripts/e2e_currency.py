#!/usr/bin/env python3
"""
Two-level currency conversion e2e.

Level 1 — FINANCIAL-ANALYSIS currency (origination):
  A borrower's multi-currency financials are normalised into one presentation
  currency so cross-period trends are meaningful. Per-period ratios are
  unit-free (quotients of monetary lines) and so are identical in either
  currency; only cross-period TRENDS need the normalisation.

Level 2 — SYSTEM currency (limit-service):
  Every limit/exposure amount is converted to the INR base for aggregation,
  from the same FxService rate table that Level 1 reads. This suite proves both
  levels draw from ONE source of truth.

Headline assertions:
  * A spread with a USD prior-year period (restated via FX) yields the SAME
    REVENUE/EBITDA/DEBT growth trends as the economically-identical all-INR
    spread — proving currency normalisation works.
  * Per-period NET_LEVERAGE is identical regardless of currency (unit-free).
  * A mixed-currency spread with NO available FX rate is REJECTED (the
    currency-consistency guard) rather than silently summed.
  * Level 1 and Level 2 share the same FX table (origination's fetched cross
    rate == limit-service's published rate).
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


def line(v):
    return {"value": v, "sourceDocument": "ccy_e2e.pdf", "sourcePage": "P1",
            "coordinates": "x", "confidence": 0.95}


def period(label, ccy, scale=1.0, fx=None):
    """A period whose monetary lines are the base INR figures * scale. When ccy
    is foreign and scale = 1/rate, the economic values match the INR baseline."""
    base = dict(REVENUE=5e9, COGS=3.2e9, OPERATING_EXPENSES=0.9e9, DEPRECIATION=0.2e9,
                INTEREST_EXPENSE=0.15e9, TAX=0.12e9, TOTAL_ASSETS=6e9, CURRENT_ASSETS=2.5e9,
                CASH=0.6e9, CURRENT_LIABILITIES=1.5e9, SHORT_TERM_DEBT=0.5e9,
                LONG_TERM_DEBT=1.2e9, CURRENT_PORTION_LTD=0.2e9, NET_WORTH=2.8e9, CFO=0.7e9)
    p = {"label": label, "gaap": "IND_AS", "currency": ccy,
         "lines": {k: line(v * scale) for k, v in base.items()}}
    if fx is not None:
        p["fxToPresentation"] = fx
    return p


def prior(label, ccy, scale=1.0, fx=None):
    """Prior year ~10% smaller — to exercise growth trends."""
    base = dict(REVENUE=4.5e9, COGS=2.95e9, OPERATING_EXPENSES=0.85e9, DEPRECIATION=0.18e9,
                INTEREST_EXPENSE=0.16e9, TAX=0.1e9, TOTAL_ASSETS=5.6e9, CURRENT_ASSETS=2.3e9,
                CASH=0.5e9, CURRENT_LIABILITIES=1.45e9, SHORT_TERM_DEBT=0.55e9,
                LONG_TERM_DEBT=1.25e9, CURRENT_PORTION_LTD=0.2e9, NET_WORTH=2.5e9, CFO=0.6e9)
    p = {"label": label, "gaap": "IND_AS", "currency": ccy,
         "lines": {k: line(v * scale) for k, v in base.items()}}
    if fx is not None:
        p["fxToPresentation"] = fx
    return p


def new_deal(suffix, jurisdiction="IN-RBI"):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Currency E2E {suffix} Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": f"CCY{suffix}", "jurisdiction": jurisdiction,
        "segment": "MID_CORPORATE", "sector": "MANUFACTURING",
        "country": "IN" if jurisdiction == "IN-RBI" else "AE",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"],
        "counterpartyName": cp["legalName"], "jurisdiction": jurisdiction,
        "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 250_000_000, "currency": "INR" if jurisdiction == "IN-RBI" else "AED",
        "tenorMonths": 60, "purpose": "WC", "collateralType": "PROPERTY",
        "collateralValue": 300_000_000, "secured": True}, actor="rm.user")
    return must(st, app, "app")["reference"], cp


print("== 0. The shared FX source of truth (limit-service) ==")
st, fx = call("GET", "/limits/api/limits/eod/fx")
fx = must(st, fx, "fx view")
rates = fx.get("rates", {})
check("FX base is INR", fx.get("base") == "INR", str(fx.get("base")))
check("USD rate present in the platform FX table", "USD" in rates, str(list(rates.keys())))
usd_rate = rates.get("USD", 0)   # INR per 1 USD
check("USD rate is plausible (> 50 INR)", usd_rate > 50, str(usd_rate))


print("== 1. Baseline: all-INR two-period spread ==")
ref_inr, _ = new_deal("INR")
st, a_inr = call("POST", f"/origination/api/applications/{ref_inr}/spread", {
    "periods": [period("FY2024", "INR"), prior("FY2023", "INR")]}, actor="analyst.user")
a_inr = must(st, a_inr, "inr spread")
check("presentation currency resolves to INR",
      a_inr["presentationCurrency"] == "INR", str(a_inr.get("presentationCurrency")))
check("spread is flagged single-currency",
      a_inr["currencyConsistent"] is True, str(a_inr.get("currencyConsistent")))
inr_trends = a_inr["trends"]
check("baseline trends present (REVENUE_GROWTH)",
      "REVENUE_GROWTH" in inr_trends and inr_trends["REVENUE_GROWTH"] != 0,
      str(inr_trends))
inr_latest_ratios = a_inr["periods"][0]["ratios"]
base_net_lev = inr_latest_ratios.get("NET_LEVERAGE")
check("baseline NET_LEVERAGE computed", base_net_lev is not None, str(base_net_lev))


print("== 2. Cross-currency: prior year in USD, restated to INR ==")
# FY2023 expressed in USD: divide INR figures by the USD rate, and supply the
# inverse rate (INR per USD) so restatement recovers the original INR magnitudes.
ref_mix, _ = new_deal("MIX")
st, a_mix = call("POST", f"/origination/api/applications/{ref_mix}/spread", {
    "presentationCurrency": "INR",
    "periods": [
        period("FY2024", "INR"),
        prior("FY2023", "USD", scale=1.0 / usd_rate, fx=usd_rate),
    ]}, actor="analyst.user")
a_mix = must(st, a_mix, "mixed spread")
check("mixed spread normalised to INR",
      a_mix["presentationCurrency"] == "INR", str(a_mix.get("presentationCurrency")))
check("mixed spread flagged NOT single-currency",
      a_mix["currencyConsistent"] is False, str(a_mix.get("currencyConsistent")))

# The prior (USD) period carries its native currency + the FX rate used.
usd_period = next(p for p in a_mix["periods"] if p["label"] == "FY2023")
check("USD period keeps native currency USD",
      usd_period["currency"] == "USD", str(usd_period.get("currency")))
check("USD period carries fxToPresentation == USD rate",
      abs(usd_period["fxToPresentation"] - usd_rate) < 1e-6,
      f"{usd_period.get('fxToPresentation')} vs {usd_rate}")
check("USD period presentationValues restated to INR magnitude",
      abs(usd_period["presentationValues"]["REVENUE"] - 4.5e9) < 5.0,
      str(usd_period["presentationValues"].get("REVENUE")))

# THE HEADLINE: trends on the restated spread match the all-INR baseline.
mix_trends = a_mix["trends"]
for k in ("REVENUE_GROWTH", "EBITDA_GROWTH", "DEBT_GROWTH"):
    check(f"trend {k} matches the all-INR baseline after USD restatement  "
          f"(INR={inr_trends.get(k)} · mixed={mix_trends.get(k)})",
          abs(inr_trends.get(k, 0) - mix_trends.get(k, 0)) < 0.005)


print("== 3. Per-period ratios are currency-agnostic (unit-free) ==")
# The USD period's NET_LEVERAGE must equal the INR baseline prior-year ratio —
# ratios are quotients so currency cancels.
inr_prior_ratios = a_inr["periods"][1]["ratios"]
usd_ratios = usd_period["ratios"]
check("NET_LEVERAGE identical across currencies (unit-free)",
      abs(inr_prior_ratios.get("NET_LEVERAGE", -1) - usd_ratios.get("NET_LEVERAGE", -2)) < 1e-6,
      f"INR={inr_prior_ratios.get('NET_LEVERAGE')} USD={usd_ratios.get('NET_LEVERAGE')}")
check("INTEREST_COVERAGE identical across currencies",
      abs(inr_prior_ratios.get("INTEREST_COVERAGE", -1) - usd_ratios.get("INTEREST_COVERAGE", -2)) < 1e-6)


print("== 4. Currency-consistency guard: mixed currency, no rate -> 400 ==")
# JPY is not in the seeded FX table and no fxToPresentation is supplied, so the
# rate cannot be resolved — the spread must be REJECTED, not silently summed.
ref_bad, _ = new_deal("BAD")
st, err = call("POST", f"/origination/api/applications/{ref_bad}/spread", {
    "presentationCurrency": "INR",
    "periods": [period("FY2024", "INR"), prior("FY2023", "JPY")]}, actor="analyst.user")
check("mixed-currency spread with no resolvable FX rate -> 400",
      st == 400, f"{st} {err}")
check("error explains the currency-consistency refusal",
      st == 400 and "FX rate" in (err.get("message", "") if isinstance(err, dict) else str(err)),
      str(err))


print("== 5. Analyst-supplied per-period rate overrides the fetched rate ==")
ref_sup, _ = new_deal("SUP")
supplied = 80.0   # deliberately different from the live USD table rate
st, a_sup = call("POST", f"/origination/api/applications/{ref_sup}/spread", {
    "presentationCurrency": "INR",
    "periods": [period("FY2024", "INR"),
                prior("FY2023", "USD", scale=1.0 / usd_rate, fx=supplied)]}, actor="analyst.user")
a_sup = must(st, a_sup, "supplied-rate spread")
sup_period = next(p for p in a_sup["periods"] if p["label"] == "FY2023")
check("supplied fxToPresentation is honoured verbatim",
      abs(sup_period["fxToPresentation"] - supplied) < 1e-6,
      str(sup_period.get("fxToPresentation")))


print("== 6. Presentation currency defaults to the latest period's currency ==")
ref_def, _ = new_deal("DEF", jurisdiction="AE-CBUAE")
# No presentationCurrency supplied; latest period is AED -> default AED, prior USD restated.
st, a_def = call("POST", f"/origination/api/applications/{ref_def}/spread", {
    "periods": [
        period("FY2024", "AED"),
        prior("FY2023", "USD", scale=1.0, fx=(usd_rate / rates.get("AED", 22.8))),
    ]}, actor="analyst.user")
a_def = must(st, a_def, "default-presentation spread")
check("presentation currency defaults to latest period (AED)",
      a_def["presentationCurrency"] == "AED", str(a_def.get("presentationCurrency")))


print("== 7. Borrower-level presentation currency on the counterparty ==")
st, cp_in = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Currency E2E Borrower-Level Ltd", "legalForm": "PUBLIC_LTD",
    "registrationNo": "CCYBL", "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
    "sector": "MANUFACTURING", "country": "IN",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp_in = must(st, cp_in, "cp default ccy")
check("IN-RBI borrower defaults presentationCurrency to INR",
      cp_in.get("presentationCurrency") == "INR", str(cp_in.get("presentationCurrency")))

st, cp_ex = call("POST", "/counterparty/api/counterparties", {
    "legalName": "Currency E2E Explicit Ccy Ltd", "legalForm": "PUBLIC_LTD",
    "registrationNo": "CCYEX", "jurisdiction": "AE-CBUAE", "segment": "LARGE_CORPORATE",
    "sector": "TRADING", "country": "AE", "presentationCurrency": "USD",
    "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
    "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
cp_ex = must(st, cp_ex, "cp explicit ccy")
check("explicit presentationCurrency is honoured (USD)",
      cp_ex.get("presentationCurrency") == "USD", str(cp_ex.get("presentationCurrency")))


print("== 8. Level 2 — system currency: limit ledger reconciles to base ==")
# Refresh a rate and read it back — proves the system-currency FX engine is the
# live, refreshable source of truth that Level 1 also consumed above.
st, before = call("GET", "/limits/api/limits/eod/fx")
prev_aed = before["rates"].get("AED")
st, ref_fx = call("POST", "/limits/api/limits/eod/fx/refresh",
                  {"currency": "AED", "rate": prev_aed}, actor="eod.batch")
check("FX refresh accepted (idempotent re-post of the same rate)",
      st == 200, f"{st} {ref_fx}")
st, after = call("GET", "/limits/api/limits/eod/fx")
check("AED rate readable after refresh (system-currency table live)",
      after["rates"].get("AED") == prev_aed, f"{after['rates'].get('AED')} vs {prev_aed}")
check("base currency is fixed at INR and rate 1.0",
      after["base"] == "INR" and after["rates"].get("INR") == 1.0,
      str(after["rates"].get("INR")))


print("== 9. Dated FX_RATE master is the historical authority ==")
st, fxm = call("GET", "/config/api/masters/FX_RATE")
fxm = must(st, fxm, "FX_RATE master")
active = [m for m in fxm if m.get("status") == "ACTIVE"]
check("FX_RATE master seeded with dated points", len(active) >= 6, f"{len(active)}")
usd_2403 = next((m for m in active if m["recordKey"] == "USD@2024-03-31"), None)
usd_2303 = next((m for m in active if m["recordKey"] == "USD@2023-03-31"), None)
check("USD@2024-03-31 dated point present", usd_2403 is not None)
check("USD@2023-03-31 dated point present", usd_2303 is not None)
dated_2303 = usd_2303["payload"]["rateToInr"] if usd_2303 else None   # 82.0, distinct from spot 83.0
check("dated 2023 rate differs from current spot (so period-dating is observable)",
      dated_2303 is not None and abs(dated_2303 - usd_rate) > 0.1,
      f"dated={dated_2303} spot={usd_rate}")


print("== 10. Period-end date drives the DATED rate, not today's spot ==")
ref_dated, _ = new_deal("DATED")
# FY2023 in USD with a period-end of 2023-03-31 and NO supplied rate -> the engine
# must restate at the dated FX_RATE point (82.0), not the current spot (83.0).
st, a_dated = call("POST", f"/origination/api/applications/{ref_dated}/spread", {
    "presentationCurrency": "INR",
    "periods": [
        {"label": "FY2024", "gaap": "IND_AS", "currency": "INR", "periodEnd": "2024-03-31",
         "lines": period("FY2024", "INR")["lines"]},
        {"label": "FY2023", "gaap": "IND_AS", "currency": "USD", "periodEnd": "2023-03-31",
         "lines": prior("FY2023", "USD", scale=1.0 / dated_2303)["lines"]},
    ]}, actor="analyst.user")
a_dated = must(st, a_dated, "dated spread")
usd_dated = next(p for p in a_dated["periods"] if p["label"] == "FY2023")
check("FX source is the DATED master (not spot)",
      usd_dated["fxRateSource"] == "DATED_MASTER", str(usd_dated.get("fxRateSource")))
check("dated rate used == the 2023-03-31 FX_RATE point",
      abs(usd_dated["fxToPresentation"] - dated_2303) < 1e-6,
      f"{usd_dated.get('fxToPresentation')} vs {dated_2303}")
check("period-end date carried through",
      usd_dated["periodEnd"] == "2023-03-31", str(usd_dated.get("periodEnd")))
# Restated at the DATED rate, the USD revenue recovers the original INR magnitude.
check("USD revenue restated to INR at the dated rate",
      abs(usd_dated["presentationValues"]["REVENUE"] - 4.5e9) < 5.0,
      str(usd_dated["presentationValues"].get("REVENUE")))


print("== 11. No period-end -> falls back to current spot (distinct source) ==")
ref_spot, _ = new_deal("SPOT")
st, a_spot = call("POST", f"/origination/api/applications/{ref_spot}/spread", {
    "presentationCurrency": "INR",
    "periods": [
        period("FY2024", "INR"),
        prior("FY2023", "USD", scale=1.0 / usd_rate),   # no periodEnd, no supplied rate
    ]}, actor="analyst.user")
a_spot = must(st, a_spot, "spot spread")
usd_spot = next(p for p in a_spot["periods"] if p["label"] == "FY2023")
check("FX source falls back to CURRENT_SPOT when no period-end given",
      usd_spot["fxRateSource"] == "CURRENT_SPOT", str(usd_spot.get("fxRateSource")))
check("spot rate used == limit-service current USD rate",
      abs(usd_spot["fxToPresentation"] - usd_rate) < 1e-6,
      f"{usd_spot.get('fxToPresentation')} vs {usd_rate}")


print("== 12. FX_RATE master is governed (maker-checker / SoD) ==")
st, sub = call("POST", "/config/api/masters/FX_RATE",
               {"recordKey": "USD@2025-03-31", "payload": {
                   "currency": "USD", "asOf": "2025-03-31", "base": "INR", "rateToInr": 84.1}},
               actor="master.maker")
sub = must(st, sub, "fx submit")
check("new dated point submitted PENDING_APPROVAL",
      sub["status"] == "PENDING_APPROVAL", str(sub.get("status")))
st, self_ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.maker")
check("self-approval blocked (SoD) -> 403", st == 403, f"{st}")
st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.checker")
check("different checker approves -> ACTIVE", st == 200 and ap["status"] == "ACTIVE", f"{st}")
# A 2025 period-end now resolves to the newly approved point.
ref_new, _ = new_deal("NEW")
st, a_new = call("POST", f"/origination/api/applications/{ref_new}/spread", {
    "presentationCurrency": "INR",
    "periods": [
        {"label": "FY2025", "gaap": "IND_AS", "currency": "INR", "periodEnd": "2025-03-31",
         "lines": period("FY2025", "INR")["lines"]},
        {"label": "FY2024", "gaap": "IND_AS", "currency": "USD", "periodEnd": "2025-03-31",
         "lines": prior("FY2024", "USD", scale=1.0 / 84.1)["lines"]},
    ]}, actor="analyst.user")
a_new = must(st, a_new, "new-point spread")
usd_new = next(p for p in a_new["periods"] if p["label"] == "FY2024")
check("newly-approved dated point is used for matching period-end",
      usd_new["fxRateSource"] == "DATED_MASTER" and abs(usd_new["fxToPresentation"] - 84.1) < 1e-6,
      f"{usd_new.get('fxRateSource')} {usd_new.get('fxToPresentation')}")


print(f"\n== currency (two-level) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
