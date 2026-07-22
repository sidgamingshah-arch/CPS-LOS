#!/usr/bin/env python3
"""
Document comparison / incremental-change diff (CLoM F57) — e2e.

A deterministic, read-only engine compares two versioned artifacts already held in
decision-service and emits a structured change table (ADDED / REMOVED / CHANGED /
UNCHANGED). Two artifact kinds are covered:

  A. PROPOSAL_VERSIONS — two credit-proposal versions of one application. We build a
     KNOWN difference: generate v1, add a second covenant, generate v2. The ONLY section
     that differs between the two renders is "Covenants" (its table gains a row); every
     other section is quoted from the same unchanged upstream figures. The returned diff
     must therefore be exactly: the Covenants section CHANGED, everything else UNCHANGED,
     nothing ADDED/REMOVED (same STANDARD format both times).

  B. DOCUMENT_VERSIONS — two generated documents. Generate doc A + doc B from the same
     template (identical clauses), then add one TNC clause to B. Comparing A -> B yields
     exactly one ADDED clause, the rest UNCHANGED.

Also asserted:
  * DETERMINISM — re-running the SAME comparison returns a byte-identical diff.
  * READ-ONLY INVARIANT — the source proposals AND the source documents are byte-identical
    (markdown/html/clauses) after the comparison runs.
  * A SYSTEM (engine) DOC_COMPARISON_COMPUTED audit event is stamped.
  * GET /{comparisonRef} and GET ?subjectRef= read the persisted comparison back.

Runs through the gateway (HELIX_GATEWAY, default :8080). Additive.
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GATEWAY", "http://localhost:8080")
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="analyst.user"):
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
    return {"value": v, "sourceDocument": "cmp.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount):
    """cp -> app -> spread -> confirm -> rate -> rating/confirm; returns the app ref."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"DocCmp {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"DCMP{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "Capex",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.5, "secured": True}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        per("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
        per("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    return ref


def add_covenant(ref, metric, threshold):
    st, c = call("POST", f"/decision/api/decisions/{ref}/covenants", {
        "covenantType": "FINANCIAL_MAINTENANCE", "metric": metric, "operator": ">=", "threshold": threshold,
        "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
        "breachSeverity": "MAJOR", "onBreach": ["notify_RM"]}, actor="analyst.user")
    return must(st, c, f"add covenant {metric}")


def version_markdown(ref, version):
    st, versions = call("GET", f"/decision/api/decisions/{ref}/credit-proposal/versions")
    versions = must(st, versions, "proposal versions")
    for v in versions:
        if v["version"] == version:
            return v.get("markdown"), v.get("html")
    return None, None


# ================================================================ setup
print("== doc-compare 0. Setup: a rated deal with two proposal versions (known delta) ==")
ref = rated_deal("PV", 60_000_000)

add_covenant(ref, "DSCR", 1.25)
st, v1 = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", {"format": "STANDARD"}, actor="analyst.user")
v1 = must(st, v1, "generate proposal v1")

# The KNOWN difference: a second covenant materially changes ONLY the Covenants section.
add_covenant(ref, "INTEREST_COVERAGE", 2.0)
st, v2 = call("POST", f"/decision/api/decisions/{ref}/credit-proposal/generate", {"format": "STANDARD"}, actor="analyst.user")
v2 = must(st, v2, "generate proposal v2")

check("two STANDARD proposal versions generated (v1, v2)",
      v1["version"] != v2["version"] and v1.get("format") == "STANDARD" and v2.get("format") == "STANDARD",
      f"{v1.get('version')}/{v2.get('version')} {v1.get('format')}/{v2.get('format')}")

# Snapshot the source proposals BEFORE any comparison (read-only invariant baseline).
md1_before, html1_before = version_markdown(ref, v1["version"])
md2_before, html2_before = version_markdown(ref, v2["version"])

# ================================================================ A. proposal-version diff
print("\n== A. PROPOSAL_VERSIONS diff — exactly the Covenants section CHANGED ==")
st, cmp1 = call("POST", "/decision/api/doc-compare", {
    "kind": "PROPOSAL_VERSIONS", "subjectRef": ref,
    "leftRef": str(v1["version"]), "rightRef": str(v2["version"])}, actor="credit.officer")
cmp1 = must(st, cmp1, "compare proposal versions")

check("comparison created (comparisonRef CMP-*, advisory, kind PROPOSAL_VERSIONS)",
      cmp1["comparisonRef"].startswith("CMP-") and cmp1["advisory"] is True
      and cmp1["kind"] == "PROPOSAL_VERSIONS", str(cmp1))
check("comparison records the acting human as createdBy (X-Actor)",
      cmp1.get("createdBy") == "credit.officer", str(cmp1.get("createdBy")))

rows = cmp1.get("diff") or []
check("diff has rows (proposal parsed into sections)", len(rows) > 0, str(len(rows)))

changed = [r for r in rows if r["changeType"] == "CHANGED"]
added = [r for r in rows if r["changeType"] == "ADDED"]
removed = [r for r in rows if r["changeType"] == "REMOVED"]
unchanged = [r for r in rows if r["changeType"] == "UNCHANGED"]

check("exactly ONE section CHANGED (same STANDARD format, only the covenant delta)",
      len(changed) == 1, f"changed={[r['section'] for r in changed]}")
check("the CHANGED section is Covenants",
      len(changed) == 1 and "Covenants" in changed[0]["section"],
      str([r["section"] for r in changed]))
check("no section ADDED (identical section set across the two versions)",
      len(added) == 0, str([r["section"] for r in added]))
check("no section REMOVED (identical section set across the two versions)",
      len(removed) == 0, str([r["section"] for r in removed]))
check("every OTHER section is UNCHANGED (figures quoted from the same unchanged upstream)",
      len(unchanged) == len(rows) - 1 and len(unchanged) > 0,
      f"unchanged={len(unchanged)} of {len(rows)}")
check("summary counts match the rows (changed=1, added=0, removed=0)",
      cmp1["changed"] == 1 and cmp1["added"] == 0 and cmp1["removed"] == 0
      and cmp1["unchanged"] == len(unchanged), str(cmp1))
# The CHANGED covenants row carries the OLD (1-covenant) and NEW (2-covenant) bodies.
check("the CHANGED row carries distinct old vs new values (the covenant table grew a row)",
      changed and changed[0]["oldValue"] != changed[0]["newValue"]
      and "INTEREST_COVERAGE" in (changed[0]["newValue"] or "")
      and "INTEREST_COVERAGE" not in (changed[0]["oldValue"] or ""),
      "old/new bodies not as expected")

# ================================================================ B. determinism
print("\n== B. Determinism — re-running the same comparison is byte-identical ==")
st, cmp1b = call("POST", "/decision/api/doc-compare", {
    "kind": "PROPOSAL_VERSIONS", "subjectRef": ref,
    "leftRef": str(v1["version"]), "rightRef": str(v2["version"])}, actor="credit.officer")
cmp1b = must(st, cmp1b, "re-compare proposal versions")
check("re-run diff is byte-identical to the first run (deterministic engine)",
      json.dumps(cmp1b["diff"], sort_keys=True) == json.dumps(cmp1["diff"], sort_keys=True),
      "diff differs across identical runs")
check("re-run is a distinct persisted record (new comparisonRef) with identical counts",
      cmp1b["comparisonRef"] != cmp1["comparisonRef"]
      and (cmp1b["changed"], cmp1b["added"], cmp1b["removed"], cmp1b["unchanged"])
      == (cmp1["changed"], cmp1["added"], cmp1["removed"], cmp1["unchanged"]),
      str(cmp1b.get("comparisonRef")))

# ================================================================ C. read-only invariant (proposals)
print("\n== C. Read-only invariant — the source proposals are unchanged ==")
md1_after, html1_after = version_markdown(ref, v1["version"])
md2_after, html2_after = version_markdown(ref, v2["version"])
check("source proposal v1 markdown+html byte-identical after comparison",
      md1_after == md1_before and html1_after == html1_before, "v1 mutated by comparison")
check("source proposal v2 markdown+html byte-identical after comparison",
      md2_after == md2_before and html2_after == html2_before, "v2 mutated by comparison")

# ================================================================ D. read-back (get + list)
print("\n== D. Read-back — GET /{ref} and GET ?subjectRef= ==")
st, got = call("GET", f"/decision/api/doc-compare/{cmp1['comparisonRef']}")
got = must(st, got, "get by comparisonRef")
check("GET /{comparisonRef} returns the same diff",
      got["comparisonRef"] == cmp1["comparisonRef"]
      and json.dumps(got["diff"], sort_keys=True) == json.dumps(cmp1["diff"], sort_keys=True),
      "persisted diff differs from the returned diff")
st, lst = call("GET", f"/decision/api/doc-compare?subjectRef={ref}")
lst = must(st, lst, "list by subject")
check("comparison is listed for the subject",
      any(c["comparisonRef"] == cmp1["comparisonRef"] for c in lst), str([c["comparisonRef"] for c in lst]))

# ================================================================ E. engine audit
print("\n== E. Audit — a SYSTEM (engine) DOC_COMPARISON_COMPUTED event is stamped ==")
st, aud = call("GET", f"/decision/api/audit/subject?type=Application&id={ref}")
aud = must(st, aud, "audit subject")
cmp_events = [e for e in aud if e.get("eventType") == "DOC_COMPARISON_COMPUTED"]
check("comparison stamped an audit SYSTEM event",
      any(e.get("actorType") == "SYSTEM" for e in cmp_events), str(cmp_events[:1]))

# ================================================================ F. document-version diff
print("\n== F. DOCUMENT_VERSIONS diff — exactly one ADDED clause ==")
st, docA = call("POST", f"/decision/api/docs/applications/{ref}/generate",
                {"templateKey": "FACILITY_AGREEMENT"}, actor="cad.officer")
docA = must(st, docA, "generate document A")
st, docB = call("POST", f"/decision/api/docs/applications/{ref}/generate",
                {"templateKey": "FACILITY_AGREEMENT"}, actor="cad.officer")
docB = must(st, docB, "generate document B")
# Add one TNC clause to B only — B is DRAFT and editable. A and B are otherwise identical.
st, docB2 = call("POST", f"/decision/api/docs/{docB['id']}/clauses",
                 {"tncRecordKey": "REGISTERED_MORTGAGE"}, actor="cad.officer")
docB2 = must(st, docB2, "add clause to document B")
clausesA_before = json.dumps(docA["clauses"], sort_keys=True)

st, cmpD = call("POST", "/decision/api/doc-compare", {
    "kind": "DOCUMENT_VERSIONS", "subjectRef": ref,
    "leftRef": str(docA["id"]), "rightRef": str(docB["id"])}, actor="credit.officer")
cmpD = must(st, cmpD, "compare document versions")
drows = cmpD.get("diff") or []
dadded = [r for r in drows if r["changeType"] == "ADDED"]
dchanged = [r for r in drows if r["changeType"] == "CHANGED"]
dremoved = [r for r in drows if r["changeType"] == "REMOVED"]
dunchanged = [r for r in drows if r["changeType"] == "UNCHANGED"]
check("document diff: exactly ONE clause ADDED (the TNC clause added to B)",
      len(dadded) == 1, f"added={[r['section'] for r in dadded]}")
check("the ADDED clause is the registered-mortgage TNC clause (present only on the right)",
      len(dadded) == 1 and dadded[0]["oldValue"] is None and (dadded[0]["newValue"] or "") != "",
      str(dadded[:1]))
check("no clause CHANGED or REMOVED (A and B share identical template clauses)",
      len(dchanged) == 0 and len(dremoved) == 0,
      f"changed={len(dchanged)} removed={len(dremoved)}")
check("the shared template clauses are all UNCHANGED",
      len(dunchanged) > 0 and cmpD["unchanged"] == len(dunchanged), str(cmpD))

# Read-only invariant on documents: doc A is untouched by the comparison.
st, docA_after = call("GET", f"/decision/api/docs/{docA['id']}")
docA_after = must(st, docA_after, "re-read document A")
check("source document A clauses byte-identical after comparison (read-only)",
      json.dumps(docA_after["clauses"], sort_keys=True) == clausesA_before, "document A mutated by comparison")

# ================================================================ G. validation
print("\n== G. Validation — unknown kind / missing artifact ==")
st, r = call("POST", "/decision/api/doc-compare", {
    "kind": "BOGUS", "subjectRef": ref, "leftRef": "1", "rightRef": "2"}, actor="credit.officer")
check("unknown kind -> 400", st == 400, f"{st} {r}")
st, r = call("POST", "/decision/api/doc-compare", {
    "kind": "PROPOSAL_VERSIONS", "subjectRef": ref, "leftRef": "1", "rightRef": "999"}, actor="credit.officer")
check("missing proposal version -> 404", st == 404, f"{st} {r}")

print(f"\n== doc-compare e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
