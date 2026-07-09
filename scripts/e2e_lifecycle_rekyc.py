#!/usr/bin/env python3
"""
Lifecycle terminal states + re-KYC sweep (D9, P2) — e2e.

Two dead states the counterparty state machine never reached are now reachable via governed
transitions:
  * CLOSED — a new close/exit transition on an ACTIVE obligor.
  * RE_KYC_DUE — a deterministic re-KYC sweep that flags VERIFIED counterparties whose KYC is
    older than their CDD-tier re-KYC interval (from the CDD_TIERS pack — ENHANCED 12 / STANDARD
    24 / SIMPLIFIED 36). The sweep takes an `asOf` so due-ness is evaluated at a point in time
    without waiting real months.

Proves: CLOSED reachable + audited + idempotent; re-KYC not-due before / due after the tier
interval; the interval is tier-differentiated (ENHANCED flips while SIMPLIFIED doesn't at the
same as-of — i.e. read from CDD_TIERS); SYSTEM audit + advisory REKYC_DUE notification
(idempotent); and a freshly-verified counterparty is untouched by a today sweep.
"""
import calendar
import datetime
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


def add_months(d, n):
    m = d.month - 1 + n
    y = d.year + m // 12
    m = m % 12 + 1
    return datetime.date(y, m, min(d.day, calendar.monthrange(y, m)[1]))


def make(suffix, **flags):
    body = {"legalName": f"Lifecycle {suffix} Ltd", "legalForm": "PUBLIC_LTD",
            "registrationNo": f"D9{suffix}", "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
            "sector": "MANUFACTURING", "country": "IN", "listedEntity": False, "regulatedFi": False,
            "pep": False, "adverseMedia": False, "highRiskJurisdiction": False, "complexOwnership": False}
    body.update(flags)
    st, cp = call("POST", "/counterparty/api/counterparties", body, actor="rm.user")
    return must(st, cp, f"create {suffix}")


def verify(cp_id):
    st, v = call("POST", f"/counterparty/api/counterparties/{cp_id}/kyc/verify", actor="compliance.officer")
    return must(st, v, "verify")


def get(cp_id):
    st, c = call("GET", f"/counterparty/api/counterparties/{cp_id}")
    return must(st, c, "get")


def sweep(as_of=None, actor="system"):
    q = f"?asOf={as_of}" if as_of else ""
    st, r = call("POST", f"/counterparty/api/counterparties/rekyc/sweep{q}", actor=actor)
    return must(st, r, "sweep")


print("== 1. CLOSED lifecycle terminal is now reachable (governed + audited) ==")
c = make("CLOSE", listedEntity=True)
verify(c["id"])
check("created obligor starts ACTIVE", get(c["id"])["lifecycleStatus"] == "ACTIVE", "")
st, closed = call("POST", f"/counterparty/api/counterparties/{c['id']}/close",
                  {"reason": "relationship exit — dormant book"}, actor="relationship.manager")
closed = must(st, closed, "close")
check("close transitions to the CLOSED terminal state", closed["lifecycleStatus"] == "CLOSED",
      str(closed.get("lifecycleStatus")))
st, again = call("POST", f"/counterparty/api/counterparties/{c['id']}/close",
                 {"reason": "again"}, actor="relationship.manager")
check("re-close is rejected (idempotent guard, 409)", st == 409, f"{st}")
st, noreason = call("POST", f"/counterparty/api/counterparties/{make('NOREASON')['id']}/close",
                    {"reason": ""}, actor="relationship.manager")
check("close requires a reason (400)", st == 400, f"{st}")
st, trail = call("GET", f"/counterparty/api/audit/subject?type=Counterparty&id={c['reference']}")
trail = trail if isinstance(trail, list) else []
check("COUNTERPARTY_CLOSED stamped by a named human",
      any(e.get("eventType") == "COUNTERPARTY_CLOSED" and e.get("actorType") == "HUMAN" for e in trail),
      str([e.get("eventType") for e in trail]))


print("\n== 2. Re-KYC sweep: due after the tier interval, not before; tier-differentiated ==")
e = make("ENH", pep=True)          # ENHANCED -> 12-month interval
s = make("SIMP", listedEntity=True)  # SIMPLIFIED -> 36-month interval
check("ENHANCED tier derived", get(e["id"])["cddTier"] == "ENHANCED", get(e["id"]).get("cddTier"))
check("SIMPLIFIED tier derived", get(s["id"])["cddTier"] == "SIMPLIFIED", get(s["id"]).get("cddTier"))
verify(e["id"])
verify(s["id"])
vdate = datetime.datetime.fromisoformat(get(e["id"])["verifiedAt"].replace("Z", "+00:00")).date()

sweep(add_months(vdate, 6).isoformat())     # 6mo < 12mo ENHANCED
check("not due before the interval (ENHANCED still VERIFIED at 6 months)",
      get(e["id"])["kycStatus"] == "VERIFIED", get(e["id"]).get("kycStatus"))

st, r = call("POST", f"/counterparty/api/counterparties/rekyc/sweep?asOf={add_months(vdate, 24).isoformat()}",
             actor="system")
r = must(st, r, "sweep 24mo")
check("ENHANCED flips to RE_KYC_DUE past its 12-month interval",
      get(e["id"])["kycStatus"] == "RE_KYC_DUE", get(e["id"]).get("kycStatus"))
check("SIMPLIFIED still VERIFIED at 24 months (interval is tier-driven from CDD_TIERS)",
      get(s["id"])["kycStatus"] == "VERIFIED", get(s["id"]).get("kycStatus"))
check("sweep response reports the flagged obligor",
      r["flagged"] >= 1 and e["reference"] in r["flaggedRefs"], str(r))
check("re-KYC due date recorded ~= verified + 12 months",
      get(e["id"]).get("reKycDueDate") == add_months(vdate, 12).isoformat(), get(e["id"]).get("reKycDueDate"))


print("\n== 3. Deterministic SYSTEM audit + advisory REKYC_DUE notification (idempotent) ==")
st, etrail = call("GET", f"/counterparty/api/audit/subject?type=Counterparty&id={e['reference']}")
etrail = etrail if isinstance(etrail, list) else []
check("REKYC_DUE stamped by SYSTEM (engine), never AI",
      any(ev.get("eventType") == "REKYC_DUE" and ev.get("actorType") == "SYSTEM" for ev in etrail)
      and not any(ev.get("eventType") == "REKYC_DUE" and ev.get("actorType") == "AI" for ev in etrail),
      str([ev.get("actorType") for ev in etrail if ev.get("eventType") == "REKYC_DUE"]))
st, notifs = call("GET", "/counterparty/api/notifications?eventType=REKYC_DUE")
notifs = notifs if isinstance(notifs, list) else []
mine = [n for n in notifs if n.get("subjectRef") == e["reference"]]
check("a REKYC_DUE notification was enqueued for the flagged obligor",
      len(mine) == 1 and "Lifecycle ENH Ltd" in (mine[0].get("renderedBody") or ""),
      str([n.get("renderedBody") for n in mine]))
# Re-fire the same sweep — E is already RE_KYC_DUE, so no duplicate.
call("POST", f"/counterparty/api/counterparties/rekyc/sweep?asOf={add_months(vdate, 24).isoformat()}", actor="system")
st, notifs2 = call("GET", "/counterparty/api/notifications?eventType=REKYC_DUE")
notifs2 = notifs2 if isinstance(notifs2, list) else []
check("re-firing the sweep does not duplicate the notification",
      len([n for n in notifs2 if n.get("subjectRef") == e["reference"]]) == 1, "duplicate enqueued")


print("\n== 4. A freshly-verified counterparty is untouched by a today sweep ==")
f = make("FRESH", listedEntity=True)
verify(f["id"])
r = sweep()   # asOf defaults to today
check("freshly-verified obligor stays VERIFIED under a today sweep",
      get(f["id"])["kycStatus"] == "VERIFIED", get(f["id"]).get("kycStatus"))
check("fresh obligor not in the flagged set", f["reference"] not in r["flaggedRefs"], str(r.get("flaggedRefs")))


print(f"\n== lifecycle + re-KYC (D9) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
