#!/usr/bin/env python3
"""
User hierarchy + field-level access control (U9 / CLoM F13, F47, F77) — e2e.

Three additive, DEFAULT-PERMISSIVE surfaces are exercised end-to-end through the gateway:

  A. FIELD_ACCESS master (config-service) + helix-common FieldAccessService/Controller,
     auto-exposed on every service (read via workflow-service, which has a live
     FieldAccessService bean). recordKey = a form key; payload.roles {role -> {field ->
     READ|WRITE|HIDDEN}}. GET /api/field-access/{form}?role= returns the {field: access} map.

  B. Server-side enforce() — the authoritative gate the client cannot bypass:
       * an explicit WRITE to a HIDDEN field -> 403 (forbiddenAutonomy);
       * a READ field is stripped from the allowed (writable) subset (no error);
       * a WRITE field is kept.

  C. USER_HIERARCHY master (config-service) + the workflow-service "view my team" inbox
     scope. GET /api/tasks/inbox?assignee=<sup>&scope=team folds in the open work-items of
     the caller's subordinates (USER_HIERARCHY.supervisor == caller); scope=self (the default)
     is byte-identical to the pre-U9 inbox and does NOT include a subordinate's task.

  D. DEFAULT-PERMISSIVE / no-regression proof: an UNMAPPED role -> full access (enforce is a
     no-op); an UNMAPPED form -> empty specs; an UNMAPPED supervisor -> team scope degrades to
     self-only. This is the guarantee that keeps e2e_casework / e2e_tat_mis / e2e_rbac /
     e2e_field_policy byte-identical.

All masters are authored under maker-checker (maker != checker for SoD). Runs against the
gateway (:8080). Registered in run_regression before e2e_lifecycle_rekyc.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
SUF = str(int(time.time() * 1000))[-7:]


def call(method, path, body=None, actor=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor is not None:
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


def make_master(mtype, key, payload, maker, checker):
    """Author a master record under maker-checker (maker != checker for SoD)."""
    st, rec = call("POST", f"/config/api/masters/{mtype}",
                   {"recordKey": key, "payload": payload}, actor=maker)
    rec = must(st, rec, f"submit {mtype}/{key}")
    st, appr = call("POST", f"/config/api/masters/records/{rec['id']}/approve", actor=checker)
    must(st, appr, f"approve {mtype}/{key}")
    return rec


# ---- run-unique identities so nothing collides across runs ----
FORM = f"U9_FORM_{SUF}"
sub = f"u9.sub.{SUF}"
sup = f"u9.sup.{SUF}"
nobody = f"u9.nobody.{SUF}"

print("== 0. Seed FIELD_ACCESS + USER_HIERARCHY masters (maker-checker, SoD) ==")
make_master("FIELD_ACCESS", FORM, {"roles": {
    "RM": {"amount": "WRITE", "salary": "HIDDEN", "notes": "READ"},
}}, maker="fa.maker", checker="fa.checker")
make_master("USER_HIERARCHY", sub,
            {"supervisor": sup, "department": "WHOLESALE_CREDIT", "role": "RM"},
            maker="uh.maker", checker="uh.checker")
check("masters authored", True)


print("== A. GET /api/field-access/{form}?role= returns the {field: access} map ==")


def get_access_until(form, role, want_field, tries=16, delay=0.5):
    """Poll the (TTL-cached) FieldAccessService until the seeded field appears."""
    for _ in range(tries):
        st, body = call("GET", f"/workflow/api/field-access/{form}?role={role}", actor=role)
        fields = body.get("fields") if isinstance(body, dict) else None
        if st == 200 and isinstance(fields, dict) and want_field in fields:
            return body
        time.sleep(delay)
    return body


body = get_access_until(FORM, "RM", "salary")
fields = body.get("fields") if isinstance(body, dict) else {}
check("map shape formKey + role + fields", isinstance(body, dict)
      and body.get("formKey") == FORM and isinstance(fields, dict), str(body))
check("RM sees amount=WRITE, salary=HIDDEN, notes=READ",
      fields.get("amount") == "WRITE" and fields.get("salary") == "HIDDEN"
      and fields.get("notes") == "READ", str(fields))


print("== B. enforce() is the authoritative server-side gate ==")
# B1. explicit WRITE to a HIDDEN field -> 403 forbiddenAutonomy.
st, r = call("POST", f"/workflow/api/field-access/{FORM}/enforce?role=RM",
             {"amount": 100, "salary": 50000, "notes": "x"}, actor="rm.user")
check("explicit write to a HIDDEN field -> 403", st == 403, f"{st} {r}")
msg = (r.get("message") if isinstance(r, dict) else str(r)) or ""
check("...403 names the hidden field", "salary" in msg and "hidden" in msg.lower(), msg)

# B2. READ field stripped, WRITE field kept (no HIDDEN write present -> 200).
st, r = call("POST", f"/workflow/api/field-access/{FORM}/enforce?role=RM",
             {"amount": 100, "notes": "read-only"}, actor="rm.user")
r = must(st, r, "enforce strip READ")
allowed = r.get("allowed") if isinstance(r, dict) else {}
check("READ field 'notes' stripped from the allowed subset", "notes" not in allowed, str(allowed))
check("WRITE field 'amount' kept in the allowed subset", allowed.get("amount") == 100, str(allowed))

# B3. a HIDDEN field carrying a null value is stripped silently (not a real write) -> 200.
st, r = call("POST", f"/workflow/api/field-access/{FORM}/enforce?role=RM",
             {"amount": 100, "salary": None}, actor="rm.user")
r = must(st, r, "enforce strip null HIDDEN")
allowed = r.get("allowed") if isinstance(r, dict) else {}
check("null-valued HIDDEN field stripped (no 403)",
      "salary" not in allowed and allowed.get("amount") == 100, str(allowed))


print("== C. 'view my team' inbox scope (USER_HIERARCHY) ==")
# A task assigned to the subordinate (explicit assignee -> ASSIGNED, in the sub's open inbox).
st, t = call("POST", "/workflow/api/tasks",
             {"subjectType": "Deal", "subjectRef": f"U9SUBJ-{SUF}",
              "taskType": "U9_TEAM_REVIEW", "assignee": sub}, actor="rm.user")
t = must(st, t, "create subordinate task")
tref = t["taskRef"]
check("subordinate task is ASSIGNED to the sub", t["status"] == "ASSIGNED" and t["assignee"] == sub,
      f"{t.get('status')} {t.get('assignee')}")

# Sanity: the task IS in the subordinate's own inbox.
st, subibx = call("GET", f"/workflow/api/tasks/inbox?assignee={sub}&scope=self", actor=sub)
subibx = must(st, subibx, "sub self inbox")
check("subordinate's own inbox contains the task", any(x["taskRef"] == tref for x in subibx), "")

# scope=team for the SUPERVISOR includes the subordinate's task.
st, team = call("GET", f"/workflow/api/tasks/inbox?assignee={sup}&scope=team", actor=sup)
team = must(st, team, "supervisor team inbox")
check("supervisor scope=team includes the subordinate's task",
      any(x["taskRef"] == tref for x in team), f"{[x['taskRef'] for x in team]}")

# scope=self for the SUPERVISOR does NOT include it (byte-identical to pre-U9).
st, selfibx = call("GET", f"/workflow/api/tasks/inbox?assignee={sup}&scope=self", actor=sup)
selfibx = must(st, selfibx, "supervisor self inbox")
check("supervisor scope=self does NOT include the subordinate's task",
      all(x["taskRef"] != tref for x in selfibx), f"{[x['taskRef'] for x in selfibx]}")

# No scope param at all -> identical to scope=self (the default is unchanged).
st, noscope = call("GET", f"/workflow/api/tasks/inbox?assignee={sup}", actor=sup)
noscope = must(st, noscope, "supervisor no-scope inbox")
check("no scope param == self (default unchanged)",
      [x["taskRef"] for x in noscope] == [x["taskRef"] for x in selfibx],
      f"{[x['taskRef'] for x in noscope]}")


print("== D. DEFAULT-PERMISSIVE / no-regression guarantees ==")
# D1. an UNMAPPED role -> full access (enforce is a no-op; nothing stripped, no 403).
st, r = call("POST", f"/workflow/api/field-access/{FORM}/enforce?role=UNKNOWN_ROLE",
             {"amount": 100, "salary": 50000, "notes": "x"}, actor="rm.user")
r = must(st, r, "enforce unmapped role")
allowed = r.get("allowed") if isinstance(r, dict) else {}
check("unmapped role -> full access (all fields kept, no 403)",
      allowed.get("amount") == 100 and allowed.get("salary") == 50000 and allowed.get("notes") == "x",
      str(allowed))

# D2. an UNMAPPED form -> empty specs (fail-open).
st, body = call("GET", f"/workflow/api/field-access/U9_UNKNOWN_{SUF}?role=RM", actor="rm.user")
body = must(st, body, "unknown form")
check("unmapped form -> empty fields (fail-open)", body.get("fields") == {}, str(body))

# D3. an UNMAPPED supervisor -> team scope degrades to self-only (== self inbox).
st, teamN = call("GET", f"/workflow/api/tasks/inbox?assignee={nobody}&scope=team", actor=nobody)
teamN = must(st, teamN, "unmapped-supervisor team inbox")
st, selfN = call("GET", f"/workflow/api/tasks/inbox?assignee={nobody}&scope=self", actor=nobody)
selfN = must(st, selfN, "unmapped-supervisor self inbox")
check("unmapped supervisor: team scope == self-only (no regression)",
      [x["taskRef"] for x in teamN] == [x["taskRef"] for x in selfN], f"{[x['taskRef'] for x in teamN]}")


print("== E. Audit — the master authoring stamped HUMAN maker-checker events ==")
enc = urllib.parse.quote("Master:FIELD_ACCESS", safe="")
st, aud = call("GET", f"/config/api/audit/subject?type={enc}&id={FORM}")
aud = must(st, aud, "audit subject")
appr = [e for e in aud if e.get("eventType") == "MASTER_APPROVED"] if isinstance(aud, list) else []
check("FIELD_ACCESS authoring stamped a HUMAN MASTER_APPROVED audit event",
      any(e.get("actorType") == "HUMAN" for e in appr), str(appr[:1]))


print(f"\n== Field-access + user-hierarchy e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
