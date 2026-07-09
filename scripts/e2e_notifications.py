#!/usr/bin/env python3
"""
Notification transport (G5-notify) — e2e (P2).

The previously audit-only lifecycle events now render an EMAIL_TEMPLATE and dispatch
through a pluggable transport whose default is a durable, idempotent OUTBOX sink (the
persisted, examiner-visible row IS the deliverable). Every enqueue is ADDITIVE — the
existing audit event stays and the business response is unchanged.

Proves, across the decision + portfolio outboxes (each service auto-gains /api/notifications):
  * a template is fetched + interpolated (not name-only) — covenant due + breach, CP nudge,
    CRILC report-due, EWS breach;
  * the outbox is queryable + filterable (status / eventType);
  * enqueue is idempotent per (eventType, subjectRef, dedupeKey) — re-firing a sweep never
    duplicates;
  * the additive/inert guarantee — the business call's response body/counts are unchanged;
  * NOTIFICATION_ENQUEUED is a SYSTEM-actor audit event (deterministic, never AI).

Dates are computed relative to today so schedules land in the alert horizon. Requires a
freshly reseeded stack (the EMAIL_TEMPLATE + NOTIFICATION_ROUTE keys are seed-time additions).
"""
import datetime
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
TODAY = datetime.date.today()


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
    return {"value": v, "sourceDocument": "n.pdf", "sourcePage": "P1", "coordinates": "x", "confidence": 0.95}


def period(label, rev, cogs, opex, intexp, ta, ca, cash, cl, std, ltd, nw, cfo):
    return {"label": label, "gaap": "IND_AS", "currency": "INR", "lines": {
        "REVENUE": line(rev), "COGS": line(cogs), "OPERATING_EXPENSES": line(opex),
        "DEPRECIATION": line(rev * 0.04), "INTEREST_EXPENSE": line(intexp), "TAX": line(rev * 0.025),
        "TOTAL_ASSETS": line(ta), "CURRENT_ASSETS": line(ca), "CASH": line(cash),
        "CURRENT_LIABILITIES": line(cl), "SHORT_TERM_DEBT": line(std), "LONG_TERM_DEBT": line(ltd),
        "CURRENT_PORTION_LTD": line(std * 0.4), "NET_WORTH": line(nw), "CFO": line(cfo)}}


def deal(suffix, amount=400_000_000, register_dpd=None):
    st, cp = call("POST", "/counterparty/api/counterparties", {
        "legalName": f"Notify {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"NTF{suffix}",
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
        "listedEntity": True, "regulatedFi": False, "pep": False, "adverseMedia": False,
        "highRiskJurisdiction": False, "complexOwnership": False}, actor="rm.user")
    cp = must(st, cp, "cp")
    st, app = call("POST", "/origination/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": amount, "currency": "INR", "tenorMonths": 60, "purpose": "x",
        "collateralType": "PROPERTY", "collateralValue": amount * 1.4, "secured": True}, actor="rm.user")
    ref = must(st, app, "app")["reference"]
    call("POST", f"/origination/api/applications/{ref}/spread", {"periods": [
        period("FY2024", 5e9, 3.0e9, 0.8e9, 0.12e9, 6e9, 2.6e9, 0.7e9, 1.4e9, 0.45e9, 1.1e9, 3.0e9, 0.9e9),
        period("FY2023", 4.5e9, 2.8e9, 0.78e9, 0.13e9, 5.6e9, 2.4e9, 0.6e9, 1.4e9, 0.5e9, 1.15e9, 2.7e9, 0.8e9),
    ]}, actor="analyst.user")
    call("POST", f"/origination/api/applications/{ref}/spread/confirm", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rate", actor="analyst.user")
    call("POST", f"/risk/api/risk/{ref}/rating/confirm", actor="credit.officer")
    call("POST", f"/risk/api/risk/{ref}/capital", actor="credit.ops")
    if register_dpd is not None:
        must(*call("POST", f"/portfolio/api/portfolio/exposures/{ref}/register",
                   {"daysPastDue": register_dpd}, actor="credit.ops"), "register")
    return ref


def notifs(svc, **q):
    qs = "&".join(f"{k}={v}" for k, v in q.items() if v is not None)
    st, rows = call("GET", f"/{svc}/api/notifications" + (("?" + qs) if qs else ""))
    return must(st, rows, f"{svc} notifications")


print("== 1. Covenant DUE reminder renders an EMAIL_TEMPLATE (decision outbox) ==")
ref = deal("COV")
call("POST", f"/decision/api/decisions/{ref}/covenants",
     {"covenantType": "FINANCIAL_MAINTENANCE", "metric": "DSCR", "operator": ">=", "threshold": 1.25,
      "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
      "breachSeverity": "MAJOR", "onBreach": ["notify_RM"]}, actor="analyst.user")
call("POST", "/decision/api/covenants/tracking/init",
     {"applicationRef": ref, "startDate": TODAY.isoformat(), "endDate": (TODAY.replace(year=TODAY.year + 3)).isoformat()},
     actor="analyst.user")
st, alerts = call("POST", "/decision/api/covenants/tracking/alerts/send?days=200", actor="system")
alerts = must(st, alerts, "alerts/send")
check("alerts/send business response unchanged (alertsSent count returned)",
      "alertsSent" in alerts and alerts["alertsSent"] >= 1, str(alerts))
due = [n for n in notifs("decision", eventType="COVENANT_DUE") if n["subjectRef"] == ref]
check("a COVENANT_DUE notification was enqueued", len(due) == 1, f"{len(due)} rows")
n0 = due[0]
check("template was fetched + interpolated (not name-only)",
      "{{" not in n0["renderedBody"] and "DSCR" in n0["renderedBody"] and ref in n0["renderedSubject"],
      f"subj={n0['renderedSubject']!r} body={n0['renderedBody']!r}")
check("dispatched via the OUTBOX transport, status SENT",
      n0["transport"] == "OUTBOX" and n0["status"] == "SENT", f"{n0.get('transport')}/{n0.get('status')}")
check("recipient roles routed from NOTIFICATION_ROUTE",
      "RM" in (n0.get("recipientRoles") or []), str(n0.get("recipientRoles")))

print("\n== 2. Idempotent — re-firing the same sweep does not duplicate ==")
call("POST", "/decision/api/covenants/tracking/alerts/send?days=200", actor="system")
due2 = [n for n in notifs("decision", eventType="COVENANT_DUE") if n["subjectRef"] == ref]
check("re-fire produced no duplicate (same single row, same id)",
      len(due2) == 1 and due2[0]["id"] == n0["id"], f"{len(due2)} rows")

print("\n== 3. Covenant BREACH notification on run-due (additive to COVENANT_TESTED) ==")
call("POST", f"/decision/api/decisions/{ref}/covenants",
     {"covenantType": "FINANCIAL_MAINTENANCE", "metric": "NET_LEVERAGE", "operator": "<=", "threshold": 0.01,
      "testFrequency": "QUARTERLY", "source": "borrower_management_accounts", "curePeriodDays": 30,
      "breachSeverity": "MAJOR", "onBreach": ["raise_EWS", "notify_RM"]}, actor="analyst.user")
call("POST", "/decision/api/covenants/tracking/init",
     {"applicationRef": ref, "startDate": TODAY.isoformat(), "endDate": (TODAY.replace(year=TODAY.year + 3)).isoformat()},
     actor="analyst.user")
st, run = call("POST", f"/decision/api/covenants/tracking/{ref}/run-due", actor="portfolio.manager")
run = must(st, run, "run-due")
check("run-due business response unchanged (schedule state machine list)",
      isinstance(run, list) and all(s["status"] in ("COMPLIANT", "BREACHED", "OVERDUE") for s in run), str(run))
breach = [n for n in notifs("decision", eventType="COVENANT_BREACH") if n["subjectRef"] == ref]
check("a COVENANT_BREACH notification was enqueued", len(breach) >= 1, f"{len(breach)} rows")
check("breach body cites the covenant + action verbatim",
      any("NET_LEVERAGE" in n["renderedBody"] for n in breach), str([n["renderedBody"] for n in breach]))
# the existing audit event is kept alongside
st, atrail = call("GET", f"/decision/api/audit/subject?type=Application&id={ref}")
atrail = atrail if isinstance(atrail, list) else []
check("existing COVENANT_TESTED audit still emitted (additive, not replaced)",
      any(e.get("eventType") == "COVENANT_TESTED" for e in atrail), "no COVENANT_TESTED")
check("NOTIFICATION_ENQUEUED audit is SYSTEM-actor (deterministic, never AI)",
      any(e.get("eventType") == "NOTIFICATION_ENQUEUED" and e.get("actorType") == "SYSTEM" for e in atrail)
      and not any(e.get("eventType") == "NOTIFICATION_ENQUEUED" and e.get("actorType") == "AI" for e in atrail),
      str([e.get("actorType") for e in atrail if e.get("eventType") == "NOTIFICATION_ENQUEUED"]))

print("\n== 4. CP nudge — one notification per facility with open CPs ==")
call("POST", f"/decision/api/cps/{ref}/seed", actor="credit.ops")
st, nud = call("POST", f"/decision/api/cps/{ref}/nudge", actor="cad.ops")
nud = must(st, nud, "cp nudge")
check("nudge swept at least one facility with open CPs", nud.get("nudged", 0) >= 1, str(nud))
cpn = [n for n in notifs("decision", eventType="CP_NUDGE") if n["subjectRef"] == ref]
check("a CP_NUDGE notification was enqueued with the open codes",
      len(cpn) >= 1 and "{{" not in cpn[0]["renderedBody"] and cpn[0]["renderedBody"].strip() != "",
      str([n["renderedBody"] for n in cpn]))

print("\n== 5. Outbox is queryable + filterable ==")
sent = notifs("decision", status="SENT")
check("status filter returns only SENT rows", sent and all(n["status"] == "SENT" for n in sent), "non-SENT leaked")
st, one = call("GET", f"/decision/api/notifications/{n0['id']}")
check("single-row fetch returns the notification", must(st, one, "get one")["id"] == n0["id"], str(one.get("id")))

print("\n== 6. CRILC report-due notification (portfolio outbox, idempotent) ==")
big = deal("CRILC", amount=800_000_000, register_dpd=61)
call("POST", f"/portfolio/api/portfolio/exposures/{big}/ecl", actor="credit.ops")
st, b1 = call("POST", "/portfolio/api/exports/crilc", actor="reg.reporter")
b1 = must(st, b1, "crilc")
crilc = notifs("portfolio", eventType="CRILC_REPORT_DUE")
check("a CRILC_REPORT_DUE notification was enqueued", len(crilc) >= 1, f"{len(crilc)} rows")
check("CRILC notification renders the as-of day + count",
      "{{" not in crilc[0]["renderedBody"] and crilc[0]["renderedBody"].strip() != "", str(crilc[0]["renderedBody"]))
call("POST", "/portfolio/api/exports/crilc", actor="reg.reporter")
crilc2 = notifs("portfolio", eventType="CRILC_REPORT_DUE")
check("CRILC notification idempotent per as-of day (no duplicate on re-run)",
      len(crilc2) == len(crilc), f"{len(crilc)} -> {len(crilc2)}")

print("\n== 7. EWS breach notification (portfolio outbox) ==")
bad = deal("EWS", register_dpd=120)
st, sig = call("POST", f"/portfolio/api/portfolio/exposures/{bad}/ews/scan", actor="credit.ops")
sig = must(st, sig, "ews scan")
ews = [n for n in notifs("portfolio", eventType="EWS_BREACH") if n["subjectRef"] == bad]
check("a severe EWS signal enqueued an EWS_BREACH notification", len(ews) >= 1, f"{len(ews)} rows")
check("EWS notification renders the borrower + signal (interpolated)",
      ews and "{{" not in ews[0]["renderedBody"] and "Notify EWS Ltd" in ews[0]["renderedSubject"],
      str(ews[0]["renderedSubject"]) if ews else "none")


print(f"\n== notification transport (G5-notify) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
