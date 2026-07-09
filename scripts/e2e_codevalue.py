#!/usr/bin/env python3
"""
Generic CODE_VALUE master — closes the dropdown configurability gap.

The CODE_VALUE master is the single source of truth for every UI dropdown:
one record per DOMAIN (GRADE_SCALE, FACILITY_TYPE, COLLATERAL_TYPE, SEGMENT,
STRUCTURE_TYPE, PARTICIPANT_ROLE, LIABILITY_TYPE, DECISION_OUTCOME,
OVERRIDE_REASON, OVERRIDE_ROLE, DOC_KIND, TRANSLATION_LANGUAGE,
SECTOR_OUTLOOK, SORT_DIRECTION), payload carries an ordered list of
{code, label, score?, sortOrder?}.

Proves: every key dropdown's options are master-driven; the resolver
returns the right shape; ordering is preserved; an admin can extend a
domain through maker-checker and the resolver picks it up.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0

EXPECTED_DOMAINS = [
    "GRADE_SCALE", "OVERRIDE_REASON", "OVERRIDE_ROLE",
    "FACILITY_TYPE", "COLLATERAL_TYPE", "SEGMENT",
    "STRUCTURE_TYPE", "PARTICIPANT_ROLE", "LIABILITY_TYPE",
    "DECISION_OUTCOME", "DOC_KIND", "TRANSLATION_LANGUAGE",
    "SECTOR_OUTLOOK", "SORT_DIRECTION",
]


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
        PASS += 1; print(f"  PASS  {name}")
    else:
        FAIL += 1; print(f"  FAIL  {name}  {detail}")


def must(st, b, label, status=200):
    if st != status:
        print(f"  ERROR {label}: HTTP {st} {b}"); sys.exit(1)
    return b


print("== 1. All expected domains seeded as CODE_VALUE records ==")
st, all_records = call("GET", "/config/api/masters/CODE_VALUE")
all_records = must(st, all_records, "code-value masters")
seeded = sorted(r["recordKey"] for r in all_records if r.get("status") == "ACTIVE")
missing = [d for d in EXPECTED_DOMAINS if d not in seeded]
check(f"every expected domain seeded ({len(EXPECTED_DOMAINS)} total)",
      not missing, f"missing={missing}")


print("== 2. Resolver returns the right shape + preserved order ==")
st, gs = call("GET", "/config/api/code-values/GRADE_SCALE")
gs = must(st, gs, "GRADE_SCALE")
codes = [v["code"] for v in gs["values"]]
expected_ladder = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]
# Assert the canonical 10 are present and in ladder order (the section 5 extension
# below may have appended further values — that's fine; we only care about the order
# of the canonical ladder).
positions = [codes.index(g) if g in codes else -1 for g in expected_ladder]
check("GRADE_SCALE canonical ladder present in correct order (AAA -> D)",
      all(p >= 0 for p in positions) and positions == sorted(positions), str(codes))
check("each value carries code + label", all("code" in v and "label" in v for v in gs["values"]))

st, oroles = call("GET", "/config/api/code-values/OVERRIDE_ROLE")
oroles = must(st, oroles, "OVERRIDE_ROLE")
analyst = next((v for v in oroles["values"] if v["code"] == "ANALYST"), {})
check("OVERRIDE_ROLE.ANALYST carries score=1 (notch limit) — proves score field round-trips",
      abs((analyst.get("score") or 0) - 1) < 1e-6, str(analyst))
cro = next((v for v in oroles["values"] if v["code"] == "CRO"), {})
check("OVERRIDE_ROLE.CRO score >= 99 (unlimited)",
      (cro.get("score") or 0) >= 99, str(cro))


print("== 3. Multi-domain endpoint returns every CODE_VALUE in one call ==")
st, all_sets = call("GET", "/config/api/code-values")
all_sets = must(st, all_sets, "all code-values")
returned = sorted(s["domain"] for s in all_sets)
check("every expected domain returned by the bulk endpoint",
      all(d in returned for d in EXPECTED_DOMAINS), str(set(EXPECTED_DOMAINS) - set(returned)))


print("== 4. Unknown domain -> 404 ==")
st, err = call("GET", "/config/api/code-values/NOT_A_DOMAIN")
check("unknown domain -> 404", st == 404, f"{st}")


print("== 5. CODE_VALUE is governed (maker-checker / SoD); adding a new value works ==")
# Extend the GRADE_SCALE master with an extra band (admin scenario).
existing = next(r for r in all_records if r["recordKey"] == "GRADE_SCALE" and r.get("status") == "ACTIVE")
new_values = list(existing["payload"]["values"]) + [
    {"code": "NR", "label": "Not rated", "sortOrder": 10},
]
st, sub = call("POST", "/config/api/masters/CODE_VALUE",
               {"recordKey": "GRADE_SCALE",
                "payload": {"domain": "GRADE_SCALE", "label": "Credit-rating master scale",
                            "values": new_values}}, actor="master.maker")
sub = must(st, sub, "submit extension")
check("extension submitted PENDING_APPROVAL", sub["status"] == "PENDING_APPROVAL", str(sub.get("status")))
st, self_ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.maker")
check("self-approval blocked (SoD) -> 403", st == 403, f"{st}")
st, ap = call("POST", f"/config/api/masters/records/{sub['id']}/approve", actor="master.checker")
check("different checker approves -> ACTIVE", st == 200 and ap["status"] == "ACTIVE", f"{st}")
st, gs2 = call("GET", "/config/api/code-values/GRADE_SCALE")
codes2 = [v["code"] for v in gs2["values"]]
check("the new value (NR) shows up at the resolver immediately",
      "NR" in codes2, str(codes2))


print("== 6. New brand-new domain can be added without code change ==")
new_domain = {
    "domain": "GAAP_FRAMEWORK",
    "label": "Accounting framework",
    "values": [
        {"code": "IND_AS", "label": "Ind AS", "sortOrder": 0},
        {"code": "IFRS", "label": "IFRS", "sortOrder": 1},
        {"code": "LOCAL_GAAP", "label": "Local GAAP", "sortOrder": 2},
    ],
}
st, nd = call("POST", "/config/api/masters/CODE_VALUE",
              {"recordKey": "GAAP_FRAMEWORK", "payload": new_domain}, actor="master.maker")
nd = must(st, nd, "submit GAAP")
call("POST", f"/config/api/masters/records/{nd['id']}/approve", actor="master.checker")
st, gaap = call("GET", "/config/api/code-values/GAAP_FRAMEWORK")
check("brand-new domain resolves with declared order",
      st == 200 and [v["code"] for v in gaap["values"]] == ["IND_AS", "IFRS", "LOCAL_GAAP"],
      str(gaap.get("values") if isinstance(gaap, dict) else gaap))


print(f"\n== code-value master e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
