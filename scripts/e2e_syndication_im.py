#!/usr/bin/env python3
"""
Syndication Information Memorandum (IM) workspace — e2e (CLoM gap #80 / R3-07).

The IM is a *versioned document artifact* layered on an existing SYNDICATION deal in
origination-service. It seeds standard sections deterministically from the deal's own
data (application, deal structure, syndicate book), is edited by named humans through
DRAFT -> CIRCULATED -> FINAL (WITHDRAWN off-ramp), finalisation is SoD-gated (finaliser
!= drafter), and versions are append-only (a re-draft clones a pinned IM at version+1).

The governance invariant: an IM never mutates any authoritative figure. This suite
fetches the syndicate book AND the allocation ledger before vs after the full IM run and
asserts they are byte-identical — allocations, participant commitments and fees untouched.

SELF-CONTAINED: this script starts ONLY the services it needs — config-service,
counterparty-service, origination-service — on ALT ports (19181-19183, never 8080-8089),
against a throwaway HELIX_DATA_DIR, health-gates them, runs the assertions directly
against origination (no gateway), then stops everything it started. Jars are built with
the local-JDK release override if missing.

Mirrors scripts/e2e_coi.py for call()/check()/must() and the "N passed, M failed" tail.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CONFIG_PORT = 19181
CPTY_PORT = 19182
ORIG_PORT = 19183
CONFIG_URL = f"http://localhost:{CONFIG_PORT}"
CPTY_URL = f"http://localhost:{CPTY_PORT}"
ORIG_URL = f"http://localhost:{ORIG_PORT}"

DATA_DIR = tempfile.mkdtemp(prefix="helix-im-e2e-")
LOG_DIR = tempfile.mkdtemp(prefix="helix-im-log-")
PROCS = []

PASS, FAIL = 0, 0


def call(base, method, path, body=None, actor="rm.user"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
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
        stop_all()
        sys.exit(1)
    return b


# ---------------------------------------------------------------- service lifecycle

def ensure_jar(module):
    jar = os.path.join(ROOT, module, "target", f"{module}.jar")
    if os.path.exists(jar):
        return jar
    print(f"  building {module} (jar missing)...")
    rc = subprocess.call(
        ["mvn", "-q", "-pl", module, "-am", "package", "-DskipTests",
         "-Dmaven.compiler.release=21"], cwd=ROOT)
    if rc != 0 or not os.path.exists(jar):
        print(f"  ERROR could not build {module}")
        sys.exit(1)
    return jar


def start(module, port, extra_env):
    jar = ensure_jar(module)
    env = os.environ.copy()
    env["SERVER_PORT"] = str(port)
    env["HELIX_DATA_DIR"] = DATA_DIR
    env.update(extra_env)
    log = open(os.path.join(LOG_DIR, f"{module}.log"), "w")
    p = subprocess.Popen(["java", "-jar", jar], env=env, stdout=log, stderr=subprocess.STDOUT)
    PROCS.append((module, p, log))
    return p


def wait_health(module, port, timeout=150):
    url = f"http://localhost:{port}/actuator/health"
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                if r.status == 200 and json.loads(r.read().decode()).get("status") == "UP":
                    print(f"    :{port} {module} UP")
                    return True
        except Exception:
            pass
        # bail early if the process died
        for m, p, _ in PROCS:
            if m == module and p.poll() is not None:
                print(f"  ERROR {module} exited early (rc={p.returncode}); see {LOG_DIR}/{module}.log")
                return False
        time.sleep(1)
    print(f"  ERROR {module} did not become healthy on :{port}; see {LOG_DIR}/{module}.log")
    return False


def stop_all():
    for module, p, log in PROCS:
        try:
            p.terminate()
        except Exception:
            pass
    for module, p, log in PROCS:
        try:
            p.wait(timeout=20)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
        try:
            log.close()
        except Exception:
            pass
    shutil.rmtree(DATA_DIR, ignore_errors=True)
    shutil.rmtree(LOG_DIR, ignore_errors=True)


def boot():
    print(f"== boot: config/counterparty/origination on :{CONFIG_PORT}/{CPTY_PORT}/{ORIG_PORT} "
          f"(data={DATA_DIR}) ==")
    start("config-service", CONFIG_PORT, {})
    start("counterparty-service", CPTY_PORT, {"CONFIG_SERVICE_URL": CONFIG_URL})
    start("origination-service", ORIG_PORT,
          {"CONFIG_SERVICE_URL": CONFIG_URL, "COUNTERPARTY_SERVICE_URL": CPTY_URL})
    ok = (wait_health("config-service", CONFIG_PORT)
          and wait_health("counterparty-service", CPTY_PORT)
          and wait_health("origination-service", ORIG_PORT))
    if not ok:
        stop_all()
        sys.exit(1)


STD_KEYS = {"EXECUTIVE_SUMMARY", "TRANSACTION_OVERVIEW", "BORROWER_PROFILE",
            "FACILITY_AND_SECURITY", "FINANCIALS_SUMMARY", "RISK_FACTORS", "SYNDICATION_TERMS"}


def run():
    global PASS, FAIL

    # ------------------------------------------------------------ setup: SYNDICATION deal
    print("\n== 0. Setup: SYNDICATION deal with 3 lenders + one funded drawdown ==")
    st, cp = call(CPTY_URL, "POST", "/api/counterparties", {
        "legalName": "Aurora Ports & Logistics Ltd", "legalForm": "PUBLIC_LTD",
        "registrationNo": "SYNDIM-001", "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE",
        "sector": "INFRASTRUCTURE", "country": "IN", "listedEntity": True, "regulatedFi": False,
        "pep": False, "adverseMedia": False, "highRiskJurisdiction": False, "complexOwnership": False},
        actor="rm.user")
    cp = must(st, cp, "counterparty create")

    st, app = call(ORIG_URL, "POST", "/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 8_000_000_000, "currency": "INR", "tenorMonths": 96,
        "purpose": "Port terminal expansion", "collateralType": "PROPERTY",
        "collateralValue": 10_000_000_000, "secured": True}, actor="rm.user")
    ref = must(st, app, "application create")["reference"]

    st, _ = call(ORIG_URL, "POST", f"/api/applications/{ref}/structure", {
        "structureType": "SYNDICATION", "leadArranger": "Helix Bank",
        "totalDealAmount": 8_000_000_000, "ourShareAmount": 4_000_000_000,
        "notes": "3-lender club"}, actor="rm.user")
    must(st, _, "set structure")
    for role, name, ext, commit in [
            ("LEAD_BANK", "Helix Bank", "BANK-HELIX", 4_000_000_000),
            ("PARTICIPANT_LENDER", "Orient Commercial Bank", "BANK-ORIENT", 2_500_000_000),
            ("PARTICIPANT_LENDER", "Coastal Union Bank", "BANK-COASTAL", 1_500_000_000)]:
        st, _ = call(ORIG_URL, "POST", f"/api/applications/{ref}/structure/participants",
                     {"role": role, "name": name, "externalRef": ext, "committedAmount": commit},
                     actor="rm.user")
        must(st, _, f"add {name}")
    # fund a drawdown so the ledger + funded-to-date are non-trivial for the invariant
    st, _ = call(ORIG_URL, "POST", f"/api/syndication/{ref}/allocate",
                 {"drawdownRef": "DRAW-1", "amount": 800_000_000, "currency": "INR"}, actor="agency.desk")
    must(st, _, "allocate DRAW-1")
    print(f"    syndicate + DRAW-1 set on {ref}")

    # snapshot the AUTHORITATIVE state BEFORE any IM activity
    st, book_before = call(ORIG_URL, "GET", f"/api/syndication/{ref}/book")
    book_before = must(st, book_before, "book before")
    st, ledger_before = call(ORIG_URL, "GET", f"/api/syndication/{ref}/allocations")
    ledger_before = must(st, ledger_before, "ledger before")

    # ------------------------------------------------------------ 1. create IM
    print("\n== 1. Create IM (DRAFT, standard grounded sections) ==")
    st, im = call(ORIG_URL, "POST", f"/api/syndication/{ref}/im",
                  {"title": "Aurora Ports — Syndication IM"}, actor="arranger.alice")
    im = must(st, im, "create IM")
    im_id = im["id"]
    v1_ref = im["imRef"]
    check("IM created (imRef IM-*, DRAFT, version 1)",
          im["imRef"].startswith("IM-") and im["status"] == "DRAFT" and im["version"] == 1, str(im))
    check("IM stamps the creating human actor", im["createdBy"] == "arranger.alice", str(im.get("createdBy")))
    check("IM links the syndication/application deal",
          im["applicationReference"] == ref and im["syndicationRef"] == ref, str(im))
    secs = im.get("sections") or {}
    check("all 7 standard sections seeded", set(secs.keys()) == STD_KEYS, str(sorted(secs.keys())))
    check("every section carries title/content/order/source",
          all(isinstance(v, dict) and {"title", "content", "order", "source"} <= set(v.keys())
              for v in secs.values()), str(secs))
    check("seeded section content is non-empty (grounded, deterministic)",
          all((v.get("content") or "").strip() for v in secs.values()), "")
    check("Executive Summary grounded from the deal (borrower + lead arranger)",
          "Aurora Ports & Logistics Ltd" in secs["EXECUTIVE_SUMMARY"]["content"]
          and "Helix Bank" in secs["EXECUTIVE_SUMMARY"]["content"], secs["EXECUTIVE_SUMMARY"]["content"])
    check("Syndication Terms grounded from the syndicate book (3 lenders)",
          "3 lender" in secs["SYNDICATION_TERMS"]["content"], secs["SYNDICATION_TERMS"]["content"])
    check("Financials Summary states the spread-of-record is authoritative (not restated)",
          "spread of record" in secs["FINANCIALS_SUMMARY"]["content"], secs["FINANCIALS_SUMMARY"]["content"])

    # ------------------------------------------------------------ 2. edit + circulate + finalise
    print("\n== 2. Edit a section, circulate, finalise ==")
    st, im = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/section",
                  {"key": "RISK_FACTORS", "content": "Human-authored: concentration in a single terminal asset."},
                  actor="arranger.alice")
    im = must(st, im, "upsert RISK_FACTORS")
    check("existing section upserted (content replaced, key preserved)",
          im["sections"]["RISK_FACTORS"]["content"].startswith("Human-authored"), str(im["sections"]["RISK_FACTORS"]))
    check("upsert did not add or drop sections (still 7)", len(im["sections"]) == 7, str(len(im["sections"])))

    st, im = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/section",
                  {"key": "market conditions", "content": "Stable rate outlook."}, actor="arranger.alice")
    im = must(st, im, "upsert new section")
    check("brand-new section added + key normalised to MARKET_CONDITIONS",
          "MARKET_CONDITIONS" in im["sections"] and len(im["sections"]) == 8, str(sorted(im["sections"].keys())))
    check("new section given an order after the existing max",
          im["sections"]["MARKET_CONDITIONS"]["order"] == max(
              s["order"] for k, s in im["sections"].items() if k != "MARKET_CONDITIONS") + 1,
          str({k: v["order"] for k, v in im["sections"].items()}))

    st, im = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/circulate", actor="arranger.alice")
    im = must(st, im, "circulate")
    check("DRAFT -> CIRCULATED (circulatedBy stamped)",
          im["status"] == "CIRCULATED" and im["circulatedBy"] == "arranger.alice", str(im))

    st, im = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/section",
                  {"key": "RISK_FACTORS", "content": "Revised in circulation: covenant package tightened."},
                  actor="arranger.alice")
    im = must(st, im, "upsert while circulated")
    check("sections still editable while CIRCULATED",
          im["sections"]["RISK_FACTORS"]["content"].startswith("Revised in circulation"), "")

    # ------------------------------------------------------------ 3. SoD on finalise
    print("\n== 3. SoD: drafter cannot finalise; a distinct actor can ==")
    st, r = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/finalise", actor="arranger.alice")
    check("self-finalise by the drafter blocked -> 403", st == 403, f"{st} {r}")
    msg = (r.get("message") if isinstance(r, dict) else str(r)) or ""
    check("...403 is a segregation-of-duties denial (names the drafter)",
          "different actor" in msg and "arranger.alice" in msg, msg)
    st, still = call(ORIG_URL, "GET", f"/api/syndication/im/{im_id}")
    check("IM stays CIRCULATED after the SoD block", still["status"] == "CIRCULATED", str(still.get("status")))

    st, im = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/finalise", actor="credit.bob")
    im = must(st, im, "finalise by distinct actor")
    check("CIRCULATED -> FINAL by a distinct actor (finalisedBy stamped)",
          im["status"] == "FINAL" and im["finalisedBy"] == "credit.bob", str(im))
    # snapshot the pinned FINAL v1 for the append-only comparison
    st, v1_final = call(ORIG_URL, "GET", f"/api/syndication/im/{im_id}")
    v1_final = must(st, v1_final, "read v1 FINAL")

    # ------------------------------------------------------------ 4. versioning / append-only
    print("\n== 4. Versioning: list/get + append-only re-draft ==")
    st, lst = call(ORIG_URL, "GET", f"/api/syndication/{ref}/im")
    lst = must(st, lst, "list IMs")
    check("deal IM list returns the IM", any(x["imRef"] == v1_ref for x in lst), str([x["imRef"] for x in lst]))
    st, got = call(ORIG_URL, "GET", f"/api/syndication/im/{im_id}")
    check("IM fetchable by id (FINAL, v1)", got["imRef"] == v1_ref and got["status"] == "FINAL", str(got.get("status")))

    st, r = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/section",
                 {"key": "RISK_FACTORS", "content": "should be rejected"}, actor="credit.bob")
    check("editing a FINAL IM is rejected -> 409", st == 409, f"{st} {r}")
    st, r = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/circulate", actor="credit.bob")
    check("re-circulating a FINAL IM is rejected -> 409", st == 409, f"{st} {r}")

    st, v2 = call(ORIG_URL, "POST", f"/api/syndication/im/{im_id}/redraft", actor="arranger.alice")
    v2 = must(st, v2, "redraft FINAL -> v2")
    v2_id = v2["id"]
    check("re-draft creates a fresh DRAFT at version 2",
          v2["status"] == "DRAFT" and v2["version"] == 2 and v2["imRef"] != v1_ref, str(v2))
    check("re-draft records the superseded version (append-only lineage)",
          v2["supersedesImRef"] == v1_ref, str(v2.get("supersedesImRef")))
    check("re-draft copied the source sections", len(v2["sections"]) == len(v1_final["sections"]), "")

    st, v1_after = call(ORIG_URL, "GET", f"/api/syndication/im/{im_id}")
    v1_after = must(st, v1_after, "read v1 after redraft")
    check("re-draft is non-destructive — the pinned FINAL v1 is byte-identical",
          json.dumps(v1_final, sort_keys=True) == json.dumps(v1_after, sort_keys=True), "v1 mutated by redraft")

    st, lst2 = call(ORIG_URL, "GET", f"/api/syndication/{ref}/im")
    check("list now returns both versions, newest first",
          len(lst2) == 2 and lst2[0]["version"] == 2 and lst2[1]["version"] == 1, str([x["version"] for x in lst2]))

    # withdraw v2, then re-draft the WITHDRAWN version into v3 (still append-only)
    st, v2w = call(ORIG_URL, "POST", f"/api/syndication/im/{v2_id}/withdraw", actor="arranger.alice")
    v2w = must(st, v2w, "withdraw v2")
    check("v2 withdrawn (WITHDRAWN, withdrawnBy stamped)",
          v2w["status"] == "WITHDRAWN" and v2w["withdrawnBy"] == "arranger.alice", str(v2w))
    st, v3 = call(ORIG_URL, "POST", f"/api/syndication/im/{v2_id}/redraft", actor="arranger.alice")
    v3 = must(st, v3, "redraft WITHDRAWN -> v3")
    check("re-draft from a WITHDRAWN IM yields DRAFT v3", v3["version"] == 3 and v3["status"] == "DRAFT", str(v3))

    # ------------------------------------------------------------ 5. INVARIANT
    print("\n== 5. INVARIANT: syndicate book + allocation ledger byte-identical ==")
    st, book_after = call(ORIG_URL, "GET", f"/api/syndication/{ref}/book")
    book_after = must(st, book_after, "book after")
    st, ledger_after = call(ORIG_URL, "GET", f"/api/syndication/{ref}/allocations")
    ledger_after = must(st, ledger_after, "ledger after")
    check("syndicate book (commitments, shares, funded-to-date, fees) byte-identical after all IM ops",
          json.dumps(book_before, sort_keys=True) == json.dumps(book_after, sort_keys=True),
          "book mutated by IM workspace")
    check("allocation ledger (participant amounts) byte-identical after all IM ops",
          json.dumps(ledger_before, sort_keys=True) == json.dumps(ledger_after, sort_keys=True),
          "ledger mutated by IM workspace")
    check("total commitment unchanged (8bn)", abs(book_after["totalCommitment"] - 8_000_000_000) < 1,
          str(book_after["totalCommitment"]))
    check("total fee waterfall unchanged",
          book_before["feeTotals"]["totalFee"] == book_after["feeTotals"]["totalFee"],
          f"{book_before['feeTotals']['totalFee']} != {book_after['feeTotals']['totalFee']}")

    # ------------------------------------------------------------ 6. audit trail HUMAN
    print("\n== 6. Audit trail — IM transitions stamped HUMAN ==")
    st, aud = call(ORIG_URL, "GET", f"/api/audit/subject?type=InformationMemorandum&id={v1_ref}")
    aud = must(st, aud, "audit subject v1")
    types = {e.get("eventType") for e in aud}
    check("SYNDICATION_IM_CREATED recorded", "SYNDICATION_IM_CREATED" in types, str(types))
    check("SYNDICATION_IM_SECTION_UPSERTED recorded", "SYNDICATION_IM_SECTION_UPSERTED" in types, str(types))
    check("SYNDICATION_IM_CIRCULATED recorded", "SYNDICATION_IM_CIRCULATED" in types, str(types))
    check("SYNDICATION_IM_FINALISED recorded", "SYNDICATION_IM_FINALISED" in types, str(types))
    check("every IM audit event on v1 is a HUMAN actor",
          bool(aud) and all(e.get("actorType") == "HUMAN" for e in aud),
          str([(e.get("eventType"), e.get("actorType")) for e in aud[:4]]))
    st, aud2 = call(ORIG_URL, "GET", f"/api/audit/subject?type=InformationMemorandum&id={v2['imRef']}")
    aud2 = must(st, aud2, "audit subject v2")
    check("SYNDICATION_IM_REDRAFTED + WITHDRAWN stamped HUMAN on v2",
          {"SYNDICATION_IM_REDRAFTED", "SYNDICATION_IM_WITHDRAWN"} <= {e.get("eventType") for e in aud2}
          and all(e.get("actorType") == "HUMAN" for e in aud2), str([e.get("eventType") for e in aud2]))

    # ------------------------------------------------------------ 7. negative
    print("\n== 7. Negatives ==")
    st, app2 = call(ORIG_URL, "POST", "/api/applications", {
        "counterpartyId": cp["id"], "counterpartyRef": cp["reference"], "counterpartyName": cp["legalName"],
        "jurisdiction": "IN-RBI", "segment": "LARGE_CORPORATE", "facilityType": "TERM_LOAN",
        "requestedAmount": 100_000_000, "currency": "INR", "tenorMonths": 36, "purpose": "wc",
        "collateralType": "PROPERTY", "collateralValue": 120_000_000, "secured": True}, actor="rm.user")
    app2 = must(st, app2, "plain app")
    st, r = call(ORIG_URL, "POST", f"/api/syndication/{app2['reference']}/im", {}, actor="arranger.alice")
    check("creating an IM on a non-SYNDICATION deal is rejected -> 4xx", st in (400, 404), f"{st} {r}")
    st, r = call(ORIG_URL, "GET", "/api/syndication/im/99999")
    check("unknown IM id -> 404", st == 404, f"{st}")


try:
    boot()
    run()
finally:
    stop_all()

print(f"\n== Syndication IM e2e: {PASS} passed, {FAIL} failed ==")
sys.exit(1 if FAIL else 0)
