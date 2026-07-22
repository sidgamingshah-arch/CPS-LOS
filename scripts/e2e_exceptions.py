#!/usr/bin/env python3
"""
Unified exception / tickler register — e2e (U7).

The register is a portfolio-service cockpit with two halves:

  * a READ-ONLY rollup (`GET /api/exceptions/rollup`) that aggregates open exception items
    across the platform — covenant breaches/overdue, MER overdue/deferred docs, pending CAD
    deviations, expiring limits and open EWS signals — into ONE normalised shape
    {source, type, subjectRef, description, owner, dueAt, severity, status}. It is best-effort:
    a source that is unreachable degrades to a warning, not a failure, and surfacing an item
    here NEVER mutates its source of record.

  * a light manual Tickler (TKL-*) with a human-gated, maker-checker resolution
    (resolver != owner -> 403 forbiddenAutonomy).

This suite proves:

  1. A real open item (an MER item) is surfaced in the normalised shape by the rollup.
  2. The rollup is read-only — the source MER item is byte-identical after the rollup.
  3. The rollup response shape is tolerant (a book-wide rollup returns the shape even with
     zero items for a source; warnings is a list).
  4. Tickler create -> assign -> resolve, with SoD (owner resolving own -> 403; a different
     actor resolves) and an audit HUMAN event on resolution.

Registered in run_regression (not order-sensitive — it only reads shared state and writes its
own MER item + tickler).
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0

SHAPE_KEYS = {"source", "type", "subjectRef", "description", "owner", "dueAt", "severity", "status"}


def call(method, path, body=None, actor="portfolio.manager"):
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


def iso_in(days):
    return time.strftime("%Y-%m-%d", time.localtime(time.time() + days * 86400))


ref = "EXC-" + str(int(time.time()))

# ============================================================ 0. seed a real open item (MER)
print("== 0. Seed a real open exception item (MER deferred document) ==")
st, mer = call("POST", "/decision/api/mer/raise", {
    "applicationRef": ref, "counterpartyName": "Exceptions Test Ltd",
    "itemType": "DEFERRED_DOCUMENT", "category": "DOCUMENT",
    "description": "Board resolution pending", "criticality": "HIGH",
    "dueDate": iso_in(10), "recurring": False, "owner": "rm.alpha",
}, actor="cad.officer")
mer = must(st, mer, "raise MER item")
check("MER item raised OPEN", mer["status"] == "OPEN" and mer["applicationReference"] == ref, str(mer.get("status")))
mer_id = mer["id"]

# capture the source-of-record state BEFORE the rollup surfaces it
st, before = call("GET", f"/decision/api/mer/{ref}")
before = must(st, before, "MER before")
src_before = next((m for m in before if m["id"] == mer_id), None)
check("source MER item is fetchable before rollup", src_before is not None, str([m["id"] for m in before]))

# ============================================================ 1. rollup surfaces it (normalised)
print("\n== 1. Subject-scoped rollup surfaces the item in the normalised shape ==")
st, roll = call("GET", f"/portfolio/api/exceptions/rollup?subjectRef={ref}")
roll = must(st, roll, "rollup by subject")
check("rollup carries subjectRef / totalOpen / items / bySource / warnings",
      all(k in roll for k in ("subjectRef", "totalOpen", "items", "bySource", "warnings")), str(list(roll.keys())))
check("warnings is a list (best-effort degradation surface)", isinstance(roll["warnings"], list), str(roll.get("warnings")))
items = roll["items"]
check("every rollup item is in the normalised {source,type,...,status} shape",
      all(SHAPE_KEYS.issubset(set(i.keys())) for i in items),
      str(set(items[0].keys()) if items else "no items"))
mer_item = next((i for i in items if i["source"] == "MER" and i["subjectRef"] == ref), None)
check("the seeded MER item is surfaced (source=MER, open)",
      mer_item is not None and mer_item["status"] == "OPEN", str(mer_item))
check("surfaced item carries the normalised fields (owner + severity + dueAt)",
      mer_item and mer_item["owner"] == "rm.alpha" and mer_item["severity"] == "HIGH" and mer_item["dueAt"] == iso_in(10),
      str(mer_item))
check("totalOpen >= 1 and bySource counts MER", roll["totalOpen"] >= 1 and roll["bySource"].get("MER", 0) >= 1,
      str(roll["bySource"]))

# ============================================================ 2. read-only invariant
print("\n== 2. The rollup is read-only — the source item is unchanged ==")
st, after = call("GET", f"/decision/api/mer/{ref}")
after = must(st, after, "MER after")
src_after = next((m for m in after if m["id"] == mer_id), None)
check("source MER item still present after rollup", src_after is not None, "")
check("source MER item is byte-identical after being surfaced (read-only)",
      src_after == src_before, f"{src_before} != {src_after}")

# ============================================================ 3. tolerant book-wide shape
print("\n== 3. Book-wide rollup returns the shape (tolerant, no fixed count) ==")
st, roll2 = call("GET", "/portfolio/api/exceptions/rollup")
roll2 = must(st, roll2, "rollup book-wide")
check("book-wide rollup carries the same response shape",
      all(k in roll2 for k in ("totalOpen", "items", "bySource", "warnings")) and isinstance(roll2["items"], list),
      str(list(roll2.keys())))
check("book-wide rollup items (if any) all match the normalised shape",
      all(SHAPE_KEYS.issubset(set(i.keys())) for i in roll2["items"]), "")

# ============================================================ 4. tickler: create -> assign -> resolve (SoD)
print("\n== 4. Manual tickler: create -> assign -> resolve with SoD + audit HUMAN ==")
st, t = call("POST", "/portfolio/api/exceptions/ticklers", {
    "subjectRef": ref, "title": "Chase board resolution", "description": "follow up with RM",
    "priority": "HIGH", "dueAt": iso_in(7),
}, actor="portfolio.manager")
t = must(st, t, "create tickler")
tref = t["ticklerRef"]
check("tickler created (TKL-*, OPEN when unassigned, records createdBy)",
      tref.startswith("TKL-") and t["status"] == "OPEN" and t["createdBy"] == "portfolio.manager", str(t))

st, ta = call("POST", f"/portfolio/api/exceptions/ticklers/{tref}/assign", {"toActor": "rm.alpha"}, actor="portfolio.manager")
ta = must(st, ta, "assign tickler")
check("assign sets owner + IN_PROGRESS", ta["owner"] == "rm.alpha" and ta["status"] == "IN_PROGRESS", str(ta))

# SoD: the owner cannot resolve their own tickler
st, r = call("POST", f"/portfolio/api/exceptions/ticklers/{tref}/resolve", {"note": "self"}, actor="rm.alpha")
check("owner resolving own tickler -> 403 (segregation of duties)", st == 403, f"{st} {r}")
st, still = call("GET", f"/portfolio/api/exceptions/ticklers?subjectRef={ref}")
still = must(st, still, "list ticklers")
cur = next((x for x in still if x["ticklerRef"] == tref), None)
check("tickler remains IN_PROGRESS after the SoD block", cur and cur["status"] == "IN_PROGRESS", str(cur))

# a different actor resolves it
st, tr = call("POST", f"/portfolio/api/exceptions/ticklers/{tref}/resolve", {"note": "board resolution received"}, actor="credit.checker")
tr = must(st, tr, "resolve tickler")
check("a resolver != owner resolves -> RESOLVED, records resolvedBy",
      tr["status"] == "RESOLVED" and tr["resolvedBy"] == "credit.checker", str(tr))

# audit HUMAN on the resolution
st, aud = call("GET", f"/portfolio/api/audit/subject?type=Tickler&id={tref}")
aud = must(st, aud, "audit subject")
resolved_events = [e for e in aud if e.get("eventType") == "TICKLER_RESOLVED"]
check("tickler resolution stamped an audit HUMAN event",
      any(e.get("actorType") == "HUMAN" for e in resolved_events), str(resolved_events[:1]))
check("tickler create was also HUMAN-audited",
      any(e.get("eventType") == "TICKLER_CREATED" and e.get("actorType") == "HUMAN" for e in aud), "")

print(f"\n== Unified exception / tickler register e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
