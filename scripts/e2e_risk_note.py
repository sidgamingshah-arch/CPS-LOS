#!/usr/bin/env python3
"""
Independent Risk Note (CLoM R1-13) — e2e through the gateway.

An Independent Risk Note is the risk function's OWN narrative opinion on a credit,
with a governed lifecycle: DRAFT -> SUBMITTED -> REVIEWED -> APPROVED, plus REJECTED /
REVERSED terminals and a reassign action for the work-item owner. It is DISTINCT from
the advisory statistical RAG overlay: a RAG band is a computed score; this is a
qualitative opinion RECORD. Like every advisory artefact it never mutates the
authoritative rating — it forms an opinion ABOUT the rating and quotes a snapshot.

Asserts:
  1. full lifecycle create -> author sections -> submit -> review -> approve.
  2. SoD: review / approve by the AUTHOR -> 403 (reviewer != author).
  3. reassign moves assignedTo to the target actor.
  4. reject needs a mandatory reason (blank -> 400; author -> 403; reviewer -> REJECTED).
  5. reverse is APPROVED-only (from SUBMITTED -> 409; blank reason -> 400; APPROVED -> REVERSED).
  6. AI-draft path is fail-soft (default provider none -> blank sections stay blank).
  7. CRUCIAL invariant — the deal's authoritative Rating (finalGrade / PD) is
     BYTE-IDENTICAL before and after the full risk-note lifecycle.
  8. every RISK_NOTE_* audit event is stamped HUMAN (no AI/SYSTEM figure path).

Registered in run_regression before e2e_lifecycle_rekyc.
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


def canon(o):
    return json.dumps(o, sort_keys=True)


def line(v):
    return {"value": v, "sourceDocument": "rn.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def per(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def rated_deal(suffix, amount):
    """cp -> app -> spread -> confirm -> rate; returns the app ref (rating exists)."""
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"RiskNote {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"RN{suffix}",
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
    must(*call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user"), "rate")
    return ref


def rating_fig(ref):
    """Snapshot the authoritative Rating's decision-consequential figures (finalGrade/PD)."""
    st, r = call("GET", f"/risk/api/risk/{ref}/rating")
    r = must(st, r, "rating")
    return canon({"finalGrade": r.get("finalGrade"), "modelGrade": r.get("modelGrade"), "pd": r.get("pd")})


AUTHOR = "risk.analyst"
REVIEWER = "risk.reviewer"
APPROVER = "risk.head"

SECTIONS = {
    "RISK_OPINION": "Mid-corporate manufacturer with adequate coverage; leverage is manageable.",
    "KEY_RISKS": "Cyclical demand and input-cost volatility; refinancing concentration in FY26.",
    "MITIGANTS": "Property security at 1.5x; committed offtake; conservative amortisation.",
    "RECOMMENDATION": "Support with standard financial covenants and a security perfection CP.",
}

print("== 1. Setup: a rated deal — snapshot the authoritative rating BEFORE any risk note ==")
ref = rated_deal("A", 400_000_000)
FIG_BEFORE = rating_fig(ref)
print(f"    deal {ref} rated; {FIG_BEFORE}")

print("\n== 2. Full lifecycle: create -> author sections -> submit -> review -> approve ==")
st, n = call("POST", "/risk/api/risk-notes",
             {"subjectRef": ref, "recommendedAction": "SUPPORT_WITH_CONDITIONS"}, actor=AUTHOR)
n = must(st, n, "create risk note")
rn = n["riskNoteRef"]
check("created DRAFT with RN- ref, advisory=true, author + assignedTo = X-Actor",
      n["status"] == "DRAFT" and rn.startswith("RN-") and n["advisory"] is True
      and n["author"] == AUTHOR and n["assignedTo"] == AUTHOR, str(n))
check("rating snapshot captured (grade at authoring, read-only)", n.get("gradeSnapshot") is not None, str(n.get("gradeSnapshot")))

st, sec = call("PUT", f"/risk/api/risk-notes/{rn}/sections",
               {"sections": SECTIONS, "recommendedAction": "SUPPORT_WITH_CONDITIONS"}, actor=AUTHOR)
sec = must(st, sec, "author sections")
check("all four narrative sections stored",
      all(sec["sections"].get(k) == v for k, v in SECTIONS.items()), str(sec.get("sections")))

st, sub = call("POST", f"/risk/api/risk-notes/{rn}/submit", actor=AUTHOR)
sub = must(st, sub, "submit")
check("submit -> SUBMITTED", sub["status"] == "SUBMITTED", str(sub))

# SoD: review by the AUTHOR is blocked before the real review below.
st, b = call("POST", f"/risk/api/risk-notes/{rn}/review", actor=AUTHOR)
check("review by the AUTHOR -> 403 (SoD reviewer != author)", st == 403, f"{st} {b}")

st, rev = call("POST", f"/risk/api/risk-notes/{rn}/review", actor=REVIEWER)
rev = must(st, rev, "review")
check("review by a different human -> REVIEWED, reviewer recorded",
      rev["status"] == "REVIEWED" and rev["reviewer"] == REVIEWER, str(rev))

# SoD: approve by the AUTHOR is blocked.
st, b = call("POST", f"/risk/api/risk-notes/{rn}/approve", {"note": "self-approve attempt"}, actor=AUTHOR)
check("approve by the AUTHOR -> 403 (SoD approver != author)", st == 403, f"{st} {b}")

st, ap = call("POST", f"/risk/api/risk-notes/{rn}/approve", {"note": "endorsed by risk head"}, actor=APPROVER)
ap = must(st, ap, "approve")
check("approve by a different human -> APPROVED", ap["status"] == "APPROVED", str(ap))

print("\n== 2b. Enriched CAM critique: WAYS_OUT/SWOT + grounded exposure/collateral/RAROC ==")
# Price the deal so the RAROC detail is real (not the pricing-pending fallback).
call("POST", f"/risk/api/risk/{ref}/pricing", actor="pricing.analyst")
grade0 = call("GET", f"/risk/api/risk/{ref}")[1]["rating"]["finalGrade"]
st, en = call("POST", "/risk/api/risk-notes", {"subjectRef": ref, "recommendedAction": "SUPPORT"}, actor=AUTHOR)
enr = must(st, en, "create enriched note")["riskNoteRef"]
st, ws = call("PUT", f"/risk/api/risk-notes/{enr}/sections", {"sections": {"WAYS_OUT":
    "Primary exit: refinance on improved DSCR. Secondary: enforce the property charge (cover >100%). "
    "SWOT — strengths: market position; weaknesses: working-capital cycle; opportunities: capacity upside; "
    "threats: commodity-price volatility."}}, actor=AUTHOR)
ws = must(st, ws, "author WAYS_OUT")
check("WAYS_OUT (ways-out / SWOT) is an accepted narrative section", "WAYS_OUT" in ws.get("sections", {}),
      str(list(ws.get("sections", {}).keys())))
st, g = call("POST", f"/risk/api/risk-notes/{enr}/ground", actor=AUTHOR)
g = must(st, g, "ground detail sections")
gs = g.get("sections", {})
check("EXPOSURE_DETAILS grounded from the deal envelope (quotes the exposure)",
      "400" in str(gs.get("EXPOSURE_DETAILS", "")).replace(",", ""), str(gs.get("EXPOSURE_DETAILS")))
check("COLLATERAL_DETAILS grounded (security + indicative cover)",
      "cover" in str(gs.get("COLLATERAL_DETAILS", "")).lower(), str(gs.get("COLLATERAL_DETAILS")))
check("RAROC_DETAILS grounded (RAROC vs hurdle)",
      "RAROC" in str(gs.get("RAROC_DETAILS", "")), str(gs.get("RAROC_DETAILS")))
check("grounding did NOT overwrite the human WAYS_OUT critique",
      "Primary exit" in str(gs.get("WAYS_OUT", "")), str(gs.get("WAYS_OUT")))
# The authoritative rating is unchanged by authoring/grounding the enriched note.
grade1 = call("GET", f"/risk/api/risk/{ref}")[1]["rating"]["finalGrade"]
check("authoritative grade UNCHANGED after the enriched risk-note critique", grade1 == grade0,
      f"{grade0} -> {grade1}")

print("\n== 3. reassign moves the work-item owner ==")
st, r2 = call("POST", "/risk/api/risk-notes", {"subjectRef": ref}, actor=AUTHOR)
r2 = must(st, r2, "create for reassign")
rr = r2["riskNoteRef"]
check("blank toActor -> 400", call("POST", f"/risk/api/risk-notes/{rr}/reassign", {"toActor": "  "}, actor=AUTHOR)[0] == 400)
st, rz = call("POST", f"/risk/api/risk-notes/{rr}/reassign", {"toActor": REVIEWER}, actor=AUTHOR)
rz = must(st, rz, "reassign")
check("reassign moved assignedTo to the target actor", rz["assignedTo"] == REVIEWER, str(rz))

print("\n== 4. reject requires a mandatory reason + SoD ==")
st, r3 = call("POST", "/risk/api/risk-notes", {"subjectRef": ref}, actor=AUTHOR)
r3 = must(st, r3, "create for reject")
rj = r3["riskNoteRef"]
call("POST", f"/risk/api/risk-notes/{rj}/submit", actor=AUTHOR)
st, b = call("POST", f"/risk/api/risk-notes/{rj}/reject", {"reason": "  "}, actor=REVIEWER)
check("reject with blank reason -> 400", st == 400, f"{st} {b}")
st, b = call("POST", f"/risk/api/risk-notes/{rj}/reject", {"reason": "insufficient coverage"}, actor=AUTHOR)
check("reject by the AUTHOR -> 403 (SoD)", st == 403, f"{st} {b}")
st, rjd = call("POST", f"/risk/api/risk-notes/{rj}/reject", {"reason": "insufficient coverage evidence"}, actor=REVIEWER)
check("reject with a reason by a reviewer -> REJECTED", st == 200 and rjd["status"] == "REJECTED", f"{st} {rjd}")

print("\n== 5. reverse is APPROVED-only, reason mandatory ==")
st, r4 = call("POST", "/risk/api/risk-notes", {"subjectRef": ref}, actor=AUTHOR)
r4 = must(st, r4, "create for reverse")
rv = r4["riskNoteRef"]
call("POST", f"/risk/api/risk-notes/{rv}/submit", actor=AUTHOR)
st, b = call("POST", f"/risk/api/risk-notes/{rv}/reverse", {"reason": "too early"}, actor=APPROVER)
check("reverse from SUBMITTED -> 409 (only APPROVED can be reversed)", st == 409, f"{st} {b}")
# drive it to APPROVED, then reverse
call("POST", f"/risk/api/risk-notes/{rv}/review", actor=REVIEWER)
call("POST", f"/risk/api/risk-notes/{rv}/approve", {"note": "ok"}, actor=APPROVER)
st, b = call("POST", f"/risk/api/risk-notes/{rv}/reverse", {"reason": ""}, actor=APPROVER)
check("reverse with blank reason -> 400", st == 400, f"{st} {b}")
st, rvd = call("POST", f"/risk/api/risk-notes/{rv}/reverse", {"reason": "superseded by revised assessment"}, actor=APPROVER)
check("reverse an APPROVED note -> REVERSED", st == 200 and rvd["status"] == "REVERSED", f"{st} {rvd}")

print("\n== 6. AI-draft is fail-soft (default provider none -> blank sections stay blank) ==")
st, r5 = call("POST", "/risk/api/risk-notes", {"subjectRef": ref}, actor=AUTHOR)
r5 = must(st, r5, "create for ai-draft")
ra = r5["riskNoteRef"]
st, drafted = call("PUT", f"/risk/api/risk-notes/{ra}/sections",
                   {"sections": {"KEY_RISKS": "human-authored risk line"}, "aiDraft": True}, actor=AUTHOR)
drafted = must(st, drafted, "ai-draft attempt")
secs = drafted.get("sections") or {}
check("human-authored section preserved verbatim under aiDraft", secs.get("KEY_RISKS") == "human-authored risk line", str(secs))
check("no external model configured -> blank sections remain blank (fail-soft, deterministic)",
      not secs.get("RISK_OPINION") and not secs.get("MITIGANTS"), str(secs))

print("\n== 7. list + get filters ==")
st, mine = call("GET", f"/risk/api/risk-notes?subjectRef={ref}")
mine = must(st, mine, "list by subject")
check("all risk notes for the subject are listed (>=5)", len(mine) >= 5, str(len(mine)))
st, one = call("GET", f"/risk/api/risk-notes/{rn}")
check("get by ref returns the record", st == 200 and one["riskNoteRef"] == rn, str(one))

print("\n== 8. INVARIANT: the authoritative rating is BYTE-IDENTICAL after the full lifecycle ==")
FIG_AFTER = rating_fig(ref)
check("rating finalGrade / PD unchanged (opinion record, not a figure mutation)",
      FIG_AFTER == FIG_BEFORE, f"before={FIG_BEFORE} after={FIG_AFTER}")

st, audit_rows = call("GET", "/risk/api/audit")
rn_events = [a for a in (audit_rows or []) if str(a.get("eventType", "")).startswith("RISK_NOTE_")]
gate_events = [a for a in rn_events if a.get("eventType") != "RISK_NOTE_AI_DRAFTED"]
check("every RISK_NOTE_* gate audit event is stamped HUMAN",
      len(gate_events) >= 1 and all(a.get("actorType") == "HUMAN" for a in gate_events),
      str([(a.get("eventType"), a.get("actorType")) for a in gate_events[:3]]))

print(f"\n== Independent Risk Note e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
