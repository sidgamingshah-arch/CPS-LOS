#!/usr/bin/env python3
"""
Configurable RISK_FLAG catalogue + CDD/HITL governance — e2e.

Proves the tester-driven counterparty hardening:
 1. RISK_FLAG master is seeded (6 records) and drives CDD tiering + advisory screening.
 2. BYTE-IDENTICAL parity: the catalogue reproduces the historically-hardcoded flags —
    pep->ENHANCED, listedEntity->SIMPLIFIED, no-flags->STANDARD; screening emits
    PEP(0.92/HIGH) -> ADVERSE_MEDIA(0.74/MEDIUM) -> OFAC(0.68/HIGH) -> WORLDCHECK(0.41/LOW)
    in that order.
 3. Governed "no simulated AI text": with no LLM configured (the regression default),
    a generated hit's aiRationale is NOT fabricated (null, rationaleSource=NONE); a named
    human records the rationale instead (rationaleSource=HUMAN).
 4. HITL / SoD: CDD sign-off (verifyKyc) blocks the creator (maker!=checker); obligor
    approval blocks the prospect creator (maker!=checker); a distinct human succeeds.
 5. Forward-compatible: a config-defined flag beyond the six typed booleans is captured
    via extraRiskFlags without a schema change.
"""
import json
import os
import random
import string
import sys
import urllib.error
import urllib.request

GW = os.environ.get("HELIX_GW", "http://localhost:8080")
PASS, FAIL = 0, 0
RUN = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))


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


def cp_body(name, **extra):
    b = {"legalName": name, "legalForm": "PRIVATE_LTD", "jurisdiction": "IN-RBI",
         "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
         "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
         "highRiskJurisdiction": False, "complexOwnership": False}
    b.update(extra)
    return b


print(f"== RISK_FLAG catalogue + CDD/HITL governance (run {RUN}) ==")

# 1. RISK_FLAG master seeded ---------------------------------------------------
print("== 1. RISK_FLAG catalogue is a config master ==")
st, cat = call("GET", "/config/api/masters/RISK_FLAG")
must(st, cat, "list RISK_FLAG master")
by_key = {r["recordKey"]: r for r in cat if isinstance(r, dict) and r.get("status") == "ACTIVE"}
for k in ("PEP", "ADVERSE_MEDIA", "HIGH_RISK_JURISDICTION", "COMPLEX_OWNERSHIP", "LISTED_ENTITY", "REGULATED_FI"):
    check(f"RISK_FLAG record present: {k}", k in by_key, str(list(by_key.keys())))
pep = by_key.get("PEP", {}).get("payload", {})
check("PEP flag: HIGH/0.92 advisory screening (config-driven) + ENHANCED CDD descriptor",
      pep.get("cddImpact") == "ENHANCED" and pep.get("defaultSeverity") == "HIGH"
      and abs(float(pep.get("defaultScore", 0)) - 0.92) < 1e-9, str(pep))
check("LISTED_ENTITY flag carries the SIMPLIFIED-CDD descriptor",
      by_key.get("LISTED_ENTITY", {}).get("payload", {}).get("cddImpact") == "SIMPLIFIED",
      str(by_key.get("LISTED_ENTITY")))

# 2. CDD tiering parity --------------------------------------------------------
print("== 2. CDD tiering parity (catalogue reproduces the historical flags) ==")
st, e = call("POST", "/counterparty/api/counterparties", cp_body(f"RF Pep {RUN}", pep=True))
must(st, e, "create pep obligor")
check("pep -> ENHANCED CDD tier", e.get("cddTier") == "ENHANCED", str(e.get("cddTier")))
st, s = call("POST", "/counterparty/api/counterparties", cp_body(f"RF Listed {RUN}", listedEntity=True))
must(st, s, "create listed obligor")
check("listedEntity -> SIMPLIFIED CDD tier", s.get("cddTier") == "SIMPLIFIED", str(s.get("cddTier")))
st, n = call("POST", "/counterparty/api/counterparties", cp_body(f"RF Plain {RUN}"))
must(st, n, "create plain obligor")
check("no flags -> STANDARD CDD tier", n.get("cddTier") == "STANDARD", str(n.get("cddTier")))
st, co = call("POST", "/counterparty/api/counterparties", cp_body(f"RF Complex {RUN}", complexOwnership=True))
check("complexOwnership -> ENHANCED CDD tier", co.get("cddTier") == "ENHANCED", str(co.get("cddTier")))

# 3. Screening parity (byte-identical values + order) --------------------------
print("== 3. Screening driven by the RISK_FLAG catalogue (byte-identical) ==")
st, full = call("POST", "/counterparty/api/counterparties",
                cp_body(f"RF Screen {RUN}", pep=True, adverseMedia=True, highRiskJurisdiction=True))
must(st, full, "create risky obligor")
st, hits = call("POST", f"/counterparty/api/counterparties/{full['id']}/screening/run", actor="compliance.officer")
must(st, hits, "run screening")
by_src = {h["listSource"]: h for h in hits}
check("PEP hit 0.92/HIGH", by_src.get("PEP", {}).get("matchScore") == 0.92 and by_src["PEP"]["severity"] == "HIGH", str(by_src.get("PEP")))
check("ADVERSE_MEDIA hit 0.74/MEDIUM", by_src.get("ADVERSE_MEDIA", {}).get("matchScore") == 0.74 and by_src["ADVERSE_MEDIA"]["severity"] == "MEDIUM", str(by_src.get("ADVERSE_MEDIA")))
check("OFAC hit 0.68/HIGH (from HIGH_RISK_JURISDICTION)", by_src.get("OFAC", {}).get("matchScore") == 0.68 and by_src["OFAC"]["severity"] == "HIGH", str(by_src.get("OFAC")))
check("WORLDCHECK weak match 0.41/LOW always emitted", by_src.get("WORLDCHECK", {}).get("matchScore") == 0.41 and by_src["WORLDCHECK"]["severity"] == "LOW", str(by_src.get("WORLDCHECK")))
order = [h["listSource"] for h in hits]
check("emission order preserved (PEP, ADVERSE_MEDIA, OFAC, WORLDCHECK)",
      order == ["PEP", "ADVERSE_MEDIA", "OFAC", "WORLDCHECK"], str(order))

# 4. Governed no-simulated-AI-text + human rationale --------------------------
print("== 4. No fabricated AI rationale offline; named-human rationale instead ==")
pep_hit = by_src["PEP"]
check("aiRationale NOT fabricated when no model configured", not pep_hit.get("aiRationale"), str(pep_hit.get("aiRationale")))
check("rationaleSource is NONE (awaiting human input)", pep_hit.get("rationaleSource") == "NONE", str(pep_hit.get("rationaleSource")))
st, rr = call("POST", f"/counterparty/api/counterparties/screening/{pep_hit['id']}/rationale",
              {"rationale": f"Analyst review {RUN}: PEP match confirmed, escalating."}, actor="compliance.officer")
must(st, rr, "record human rationale")
check("rationaleSource flips to HUMAN after a named human records one", rr.get("rationaleSource") == "HUMAN", str(rr.get("rationaleSource")))
check("humanRationale round-trips as plaintext", RUN in (rr.get("humanRationale") or ""), str(rr.get("humanRationale")))
st, _bad = call("POST", f"/counterparty/api/counterparties/screening/{pep_hit['id']}/rationale", {"rationale": ""}, actor="compliance.officer")
check("blank rationale rejected (400)", st == 400, f"{st}")

# 5. HITL SoD on CDD sign-off (verifyKyc) -------------------------------------
print("== 5. CDD sign-off maker!=checker ==")
st, clean = call("POST", "/counterparty/api/counterparties", cp_body(f"RF Signoff {RUN}"), actor="rm.maker1")
must(st, clean, "create clean obligor by rm.maker1")
call("POST", f"/counterparty/api/counterparties/{clean['id']}/screening/run", actor="compliance.officer")
st, _self = call("POST", f"/counterparty/api/counterparties/{clean['id']}/kyc/verify", actor="rm.maker1")
check("creator cannot sign off their own CDD tier (403 SoD)", st == 403, f"{st}")
st, ok = call("POST", f"/counterparty/api/counterparties/{clean['id']}/kyc/verify", actor="compliance.checker1")
check("a distinct named human signs off CDD -> VERIFIED", st == 200 and ok.get("kycStatus") == "VERIFIED", f"{st} {ok.get('kycStatus') if isinstance(ok, dict) else ok}")

# 6. HITL SoD on obligor approval --------------------------------------------
print("== 6. Obligor approval maker!=checker ==")
st, prospect = call("POST", "/counterparty/api/initiation/prospects",
                    {"legalName": f"RF Prospect {RUN}", "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE",
                     "sector": "MANUFACTURING", "country": "IN", "borrowerType": "NTB"}, actor="rm.maker2")
must(st, prospect, "create prospect by rm.maker2")
st, _self = call("POST", f"/counterparty/api/initiation/prospects/{prospect['id']}/approve", actor="rm.maker2")
check("creator cannot approve their own prospect into an obligor (403 SoD)", st == 403, f"{st}")
st, ob = call("POST", f"/counterparty/api/initiation/prospects/{prospect['id']}/approve", actor="credit.checker2")
check("a distinct named human promotes prospect -> OBLIGOR", st == 200 and ob.get("recordType") == "OBLIGOR", f"{st} {ob.get('recordType') if isinstance(ob, dict) else ob}")

# 7. Forward-compatible extra flag -------------------------------------------
print("== 7. Config-defined flag beyond the six booleans (extraRiskFlags) ==")
st, x = call("POST", "/counterparty/api/counterparties",
             cp_body(f"RF Extra {RUN}", extraRiskFlags={"STATE_OWNED_ENTERPRISE": True}))
must(st, x, "create with an extra risk flag")
st, xg = call("GET", f"/counterparty/api/counterparties/{x['id']}")
check("extra config-defined flag persisted (no schema change)",
      isinstance(xg, dict) and (xg.get("extraRiskFlags") or {}).get("STATE_OWNED_ENTERPRISE") is True,
      str(xg.get("extraRiskFlags") if isinstance(xg, dict) else xg))

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
