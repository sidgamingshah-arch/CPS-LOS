#!/usr/bin/env python3
"""
CDD tiering from the CDD_TIERS pack (E3, P2) — e2e.

CDD intensity tiering was a hardcoded code branch (pep/high-risk/... -> ENHANCED;
listed/regulated -> SIMPLIFIED; else STANDARD). It now reads the jurisdiction's CDD_TIERS
rule pack (`enhanced_triggers` / `simplified_eligible` / `default_tier`). The seeded pack's
lists match the old logic, so this is behaviour-preserving — but the tiering now MOVES when
the pack is re-authored (maker-checker), instead of being frozen in code.

Proves: the seeded pack reproduces the historical tiers (ENHANCED/SIMPLIFIED/STANDARD);
re-authoring the pack to drop PEP from enhanced_triggers moves a PEP counterparty off
ENHANCED; and a finally-restore returns the seeded tiering.
"""
import copy
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


def tier_of(suffix, **flags):
    body = {"legalName": f"CDD {suffix} Ltd", "legalForm": "PUBLIC_LTD", "registrationNo": f"E3{suffix}",
            "jurisdiction": "IN-RBI", "segment": "MID_CORPORATE", "sector": "MANUFACTURING", "country": "IN",
            "listedEntity": False, "regulatedFi": False, "pep": False, "adverseMedia": False,
            "highRiskJurisdiction": False, "complexOwnership": False}
    body.update(flags)
    st, cp = call("POST", "/counterparty/api/counterparties", body, actor="rm.user")
    return must(st, cp, f"create {suffix}")["cddTier"]


def author_cdd(payload):
    st, draft = call("POST", "/config/api/rulepacks",
                     {"jurisdiction": "IN-RBI", "type": "CDD_TIERS", "code": "rbi_kyc_md_tiers", "payload": payload},
                     actor="pack.author.alice")
    draft = must(st, draft, "author CDD_TIERS")
    did = draft["id"]
    call("POST", f"/config/api/rulepacks/{did}/signoff?control=policy", actor="policy.betty")
    must(*call("POST", f"/config/api/rulepacks/{did}/signoff?control=model-risk", actor="modelrisk.carol"),
         "sign CDD_TIERS")


st, cur = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=CDD_TIERS")
original = must(st, cur, "current CDD_TIERS")["payload"]

try:
    print("== 1. Seeded pack reproduces the historical CDD tiers (behaviour-preserving) ==")
    check("PEP -> ENHANCED", tier_of("PEP", pep=True) == "ENHANCED")
    check("high-risk jurisdiction -> ENHANCED", tier_of("HRJ", highRiskJurisdiction=True) == "ENHANCED")
    check("listed entity (no triggers) -> SIMPLIFIED", tier_of("LISTED", listedEntity=True) == "SIMPLIFIED")
    check("regulated FI -> SIMPLIFIED", tier_of("FI", regulatedFi=True) == "SIMPLIFIED")
    check("no flags -> default STANDARD", tier_of("PLAIN") == "STANDARD")
    check("CDD_TIERS pack carries the enhanced_triggers list",
          "PEP" in (original.get("enhanced_triggers") or []), str(original.get("enhanced_triggers")))

    print("\n== 2. Re-authoring the pack MOVES the tiering (config-driven, not code) ==")
    variant = copy.deepcopy(original)
    variant["enhanced_triggers"] = [t for t in variant.get("enhanced_triggers", []) if t != "PEP"]
    author_cdd(variant)
    check("with PEP removed from enhanced_triggers, a PEP-only counterparty is no longer ENHANCED",
          tier_of("PEP2", pep=True) == "STANDARD", "still ENHANCED — derivation not reading the pack")
    check("a listed counterparty is still SIMPLIFIED (simplified_eligible unchanged)",
          tier_of("LISTED2", listedEntity=True) == "SIMPLIFIED")

finally:
    author_cdd(original)
    st, chk = call("GET", "/config/api/rulepacks?jurisdiction=IN-RBI&type=CDD_TIERS")
    restored = st == 200 and "PEP" in (chk["payload"].get("enhanced_triggers") or [])
    check("CDD_TIERS pack restored (PEP back in enhanced_triggers)", restored, str(chk.get("payload")))
    if restored:
        check("PEP -> ENHANCED again after restore", tier_of("PEP3", pep=True) == "ENHANCED")


print(f"\n== CDD tiering from pack (E3) e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(0 if FAIL == 0 else 1)
