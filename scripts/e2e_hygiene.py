#!/usr/bin/env python3
"""
India statutory identifiers + VALIDATION_PARAMETER engine + hygiene RAG — e2e.

Proves:
 1. The VALIDATION_PARAMETER master is seeded (COUNTERPARTY_IDENTIFIERS domain, 4 rules).
 2. Malformed identifiers are blocked at create with ONE 400 aggregating every failing
    field — on both the counterparty and the prospect path. Identifiers stay optional:
    flows that never send them are untouched.
 3. Valid PAN/GSTIN/LEI/CIN are accepted, normalised to uppercase, and echoed back.
 4. Dedup matches on the new identifier fields (same GSTIN, dissimilar names ->
    IDENTIFIER match) — and legacy registrationNo dedup still works (protects the
    existing e2e_smoke UDEDUP123 contract).
 5. The source-system verify facade: PAN_VERIFY / GSTIN_VERIFY -> VERIFIED for a
    format-valid identifier; LEI_VERIFY -> NOT_AVAILABLE when the LEI is not captured.
 6. Hygiene RAG (deterministic read-only aggregation): GREEN when identifiers are
    present+valid AND screening is clear AND KYC verified; AMBER on missing
    identifiers / unverified KYC; RED on an unresolved HIGH screening hit.
    The read stamps a SYSTEM HYGIENE_ASSESSED audit event and leaves the counterparty
    record byte-identical (no figures touched).

GSTIN check digits are computed with the standard base-36 alternating 1/2-weight
algorithm; LEIs with ISO 17442 mod-97 — so every generated identifier is genuinely
checksum-valid, per run, without fixture collisions.
"""
import json
import random
import string
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0
B36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"


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


# ---- checksum-valid identifier generators (run-unique, no fixture collisions) ----

def gstin_check_digit(g14):
    s = 0
    for i, ch in enumerate(g14):
        p = B36.index(ch) * (1 if i % 2 == 0 else 2)
        s += p // 36 + p % 36
    return B36[(36 - s % 36) % 36]


def make_gstin(pan, state="27", entity="1"):
    base = state + pan + entity + "Z"
    return base + gstin_check_digit(base)


def make_lei():
    base18 = "".join(random.choices(string.ascii_uppercase + string.digits, k=18))
    rem = int("".join(str(int(c, 36)) for c in base18 + "00")) % 97
    return base18 + str(98 - rem).zfill(2)


def make_pan():
    return "".join(random.choices(string.ascii_uppercase, k=5)) \
        + f"{random.randint(0, 9999):04d}" \
        + random.choice(string.ascii_uppercase)


def make_cin():
    return "U" + f"{random.randint(0, 99999):05d}" + "MH2019PTC" + f"{random.randint(0, 999999):06d}"


def cp_body(name, **extra):
    b = {"legalName": name, "legalForm": "PRIVATE_LTD", "jurisdiction": "IN-RBI",
         "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
         "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
         "highRiskJurisdiction": False, "complexOwnership": False}
    b.update(extra)
    return b


RUN = "".join(random.choices(string.ascii_uppercase, k=5))

print("== 1. VALIDATION_PARAMETER master seeded ==")
st, vp = call("GET", "/config/api/masters/VALIDATION_PARAMETER")
rec = next((r for r in (vp or []) if r.get("recordKey") == "COUNTERPARTY_IDENTIFIERS"), None)
check("COUNTERPARTY_IDENTIFIERS domain seeded", st == 200 and rec is not None, f"{st} {vp}")
check("4 identifier rules (pan/gstin/lei/cin)",
      rec is not None and sorted(r["field"] for r in rec["payload"]["rules"]) == ["cin", "gstin", "lei", "pan"],
      str(rec and rec.get("payload")))

print("== 2. malformed identifiers blocked at create (400, aggregated) ==")
st, err = call("POST", "/counterparty/api/counterparties",
               cp_body(f"Hygiene BadPan {RUN} Ltd", pan="ABCDE12345"))  # 5 digits, no trailing letter
check("malformed PAN -> 400", st == 400, f"{st} {err}")
good_gstin = make_gstin(make_pan())
bad_gstin = good_gstin[:14] + B36[(B36.index(good_gstin[14]) + 1) % 36]  # flip the check digit
st, err = call("POST", "/counterparty/api/counterparties",
               cp_body(f"Hygiene BadGstin {RUN} Ltd", gstin=bad_gstin))
check("GSTIN with a wrong check digit -> 400 (CHECKSUM rule)", st == 400, f"{st} {err}")
st, err = call("POST", "/counterparty/api/counterparties",
               cp_body(f"Hygiene BadBoth {RUN} Ltd", pan="ABCDE12345", gstin=bad_gstin))
msg = (err or {}).get("message", "") if isinstance(err, dict) else str(err)
check("one 400 aggregates BOTH failing fields", st == 400 and "pan" in msg and "gstin" in msg, f"{st} {msg}")
st, err = call("POST", "/counterparty/api/initiation/prospects",
               cp_body(f"Hygiene BadCin {RUN} Ltd", cin="X12345MH2019PTC000001", borrowerType="NTB"))
check("malformed CIN blocked on the prospect path too -> 400", st == 400, f"{st} {err}")
st, badlei = call("POST", "/counterparty/api/counterparties",
                  cp_body(f"Hygiene BadLei {RUN} Ltd", lei="HELIX00TESTLEI000141"))  # mod-97 != 1
check("LEI failing ISO 17442 mod-97 -> 400", st == 400, f"{st} {badlei}")

print("== 3. valid identifiers accepted + normalised + echoed ==")
PAN, CIN, LEI = make_pan(), make_cin(), make_lei()
GSTIN = make_gstin(PAN)
st, cp_full = call("POST", "/counterparty/api/counterparties",
                   cp_body(f"Hygiene Green Metals {RUN} Ltd",
                           pan=PAN.lower(), gstin=GSTIN, lei=LEI, cin=CIN))
must(st, cp_full, "create with valid identifiers")
check("counterparty created with valid PAN+GSTIN+LEI+CIN", st == 200 and cp_full.get("id") is not None, f"{st}")
check("PAN normalised to uppercase and echoed", cp_full.get("pan") == PAN, str(cp_full.get("pan")))
check("GSTIN echoed", cp_full.get("gstin") == GSTIN, str(cp_full.get("gstin")))
check("LEI echoed", cp_full.get("lei") == LEI, str(cp_full.get("lei")))
check("CIN echoed", cp_full.get("cin") == CIN, str(cp_full.get("cin")))
st, plain = call("POST", "/counterparty/api/counterparties", cp_body(f"Hygiene Plain Foods {RUN} Ltd"))
check("identifiers stay optional — create without them is unchanged (200, all null)",
      st == 200 and plain.get("pan") is None and plain.get("gstin") is None
      and plain.get("lei") is None and plain.get("cin") is None, f"{st} {plain}")

print("== 4. dedup on the new identifier fields (same GSTIN) ==")
DUP_GSTIN = make_gstin(make_pan())
st, d1 = call("POST", "/counterparty/api/initiation/prospects",
              cp_body(f"Alpha Forge Industries {RUN}", gstin=DUP_GSTIN, borrowerType="NTB"), actor="rm.alice")
must(st, d1, "prospect 1 with GSTIN")
st, d2 = call("POST", "/counterparty/api/initiation/prospects",
              cp_body(f"Zenith Marine Traders {RUN}", gstin=DUP_GSTIN, borrowerType="NTB"), actor="rm.bob")
must(st, d2, "prospect 2 with same GSTIN")
st, dd = call("GET", f"/counterparty/api/initiation/prospects/{d2['id']}/dedup")
gstin_match = next((m for m in (dd or {}).get("matches", []) if m["reference"] == d1["reference"]), None)
check("dedup reports the GSTIN twin", st == 200 and gstin_match is not None, f"{st} {dd}")
check("match type is IDENTIFIER (names are dissimilar)",
      gstin_match is not None and "IDENTIFIER" in gstin_match["matchType"], str(gstin_match))
check("gstin listed in the configured identifierFields",
      st == 200 and "gstin" in dd.get("identifierFields", []), str(dd.get("identifierFields")))

print("== 5. legacy registrationNo dedup preserved ==")
REG = f"UHYGLEG{RUN}1"
st, l1 = call("POST", "/counterparty/api/initiation/prospects",
              cp_body(f"Borealis Textile Mills {RUN}", registrationNo=REG, borrowerType="NTB"), actor="rm.alice")
must(st, l1, "legacy prospect 1")
st, l2 = call("POST", "/counterparty/api/initiation/prospects",
              cp_body(f"Meridian Cargo Lines {RUN}", registrationNo=REG, borrowerType="NTB"), actor="rm.bob")
must(st, l2, "legacy prospect 2")
st, dd = call("GET", f"/counterparty/api/initiation/prospects/{l2['id']}/dedup")
reg_match = next((m for m in (dd or {}).get("matches", []) if m["reference"] == l1["reference"]), None)
check("registrationNo twin still dedups (legacy contract)",
      st == 200 and reg_match is not None and "IDENTIFIER" in reg_match["matchType"], f"{st} {dd}")

print("== 6. identifier-verify facade (simulated registry checks) ==")
st, pv = call("POST", f"/counterparty/api/initiation/prospects/{cp_full['id']}/checks/fetch",
              {"entityType": "OBLIGOR", "checkType": "PAN_VERIFY"}, actor="compliance.officer")
check("PAN_VERIFY -> VERIFIED for a format-valid PAN",
      st == 200 and pv["status"] == "VERIFIED" and pv["sourceSystem"] == "NSDL-PAN", f"{st} {pv}")
st, gv = call("POST", f"/counterparty/api/initiation/prospects/{cp_full['id']}/checks/fetch",
              {"entityType": "OBLIGOR", "checkType": "GSTIN_VERIFY"}, actor="compliance.officer")
check("GSTIN_VERIFY -> VERIFIED (GSTN)", st == 200 and gv["status"] == "VERIFIED"
      and gv["sourceSystem"] == "GSTN", f"{st} {gv}")
st, lv = call("POST", f"/counterparty/api/initiation/prospects/{plain['id']}/checks/fetch",
              {"entityType": "OBLIGOR", "checkType": "LEI_VERIFY"}, actor="compliance.officer")
check("LEI_VERIFY -> NOT_AVAILABLE when the LEI is not captured",
      st == 200 and lv["status"] == "NOT_AVAILABLE" and lv["sourceSystem"] == "GLEIF", f"{st} {lv}")

print("== 7. hygiene GREEN — valid ids + screening cleared + KYC verified ==")
st, hits = call("POST", f"/counterparty/api/counterparties/{cp_full['id']}/screening/run",
                actor="compliance.officer")
must(st, hits, "screening run (green obligor)")
for h in hits:
    call("POST", f"/counterparty/api/counterparties/screening/{h['id']}/disposition",
         {"disposition": "FALSE_POSITIVE", "note": "weak partial match, no secondary identifier"},
         actor="compliance.officer")
st, _ = call("POST", f"/counterparty/api/counterparties/{cp_full['id']}/kyc/verify", actor="compliance.officer")
check("KYC verified", st == 200, f"{st}")
st, before = call("GET", f"/counterparty/api/counterparties/{cp_full['id']}")
st, hyg = call("GET", f"/counterparty/api/counterparties/{cp_full['id']}/hygiene")
check("hygiene status GREEN", st == 200 and hyg.get("status") == "GREEN", f"{st} {hyg}")
states = {c["key"]: c["state"] for c in (hyg or {}).get("checks", [])}
check("all four identifiers VALID",
      all(states.get(f"identifier.{k}") == "VALID" for k in ("pan", "gstin", "lei", "cin")), str(states))
check("screening CLEAR + kyc VERIFIED in the checks",
      states.get("screening") == "CLEAR" and states.get("kyc") == "VERIFIED", str(states))
st, after = call("GET", f"/counterparty/api/counterparties/{cp_full['id']}")
check("hygiene read leaves the counterparty record byte-identical (no figures touched)",
      before == after, f"{before} != {after}")
st, trail = call("GET", f"/counterparty/api/audit/subject?type=Counterparty&id={cp_full['reference']}")
check("HYGIENE_ASSESSED stamped as a SYSTEM audit event",
      st == 200 and any(e["eventType"] == "HYGIENE_ASSESSED" and e["actorType"] == "SYSTEM" for e in trail),
      f"{st}")

print("== 8. hygiene AMBER — identifiers missing / KYC not verified ==")
st, hyg = call("GET", f"/counterparty/api/counterparties/{plain['id']}/hygiene")
check("obligor without identifiers -> AMBER", st == 200 and hyg.get("status") == "AMBER", f"{st} {hyg}")
states = {c["key"]: c["state"] for c in (hyg or {}).get("checks", [])}
check("identifier checks report MISSING", states.get("identifier.pan") == "MISSING", str(states))
check("kyc reported NOT_VERIFIED", states.get("kyc") == "NOT_VERIFIED", str(states))

print("== 9. hygiene RED — unresolved HIGH screening hit ==")
RPAN = make_pan()
st, cp_red = call("POST", "/counterparty/api/counterparties",
                  cp_body(f"Hygiene Red Chemicals {RUN} Ltd", pan=RPAN, gstin=make_gstin(RPAN),
                          lei=make_lei(), cin=make_cin(), pep=True))
must(st, cp_red, "create pep obligor")
st, hits = call("POST", f"/counterparty/api/counterparties/{cp_red['id']}/screening/run",
                actor="compliance.officer")
check("PEP screening produced a HIGH hit", st == 200 and any(h["severity"] == "HIGH" for h in hits), f"{st}")
st, hyg = call("GET", f"/counterparty/api/counterparties/{cp_red['id']}/hygiene")
check("unresolved HIGH hit -> RED (identifiers all valid)",
      st == 200 and hyg.get("status") == "RED", f"{st} {hyg}")
states = {c["key"]: c["state"] for c in (hyg or {}).get("checks", [])}
check("screening flagged SEVERE_OPEN while identifiers stay VALID",
      states.get("screening") == "SEVERE_OPEN" and states.get("identifier.pan") == "VALID", str(states))

print(f"\n== India identifiers + hygiene RAG e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
