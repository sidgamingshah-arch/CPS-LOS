#!/usr/bin/env python3
"""
Stage-4 committee / DoP ladder + escalation matrix + lines of defence — e2e (round-3 Wave 2A).

The delegated-authority sanctioning ladder is config-as-data in the DOA_MATRIX pack. This suite
proves the governed VIEW of "who sanctions what":

  1. Each active quantum×grade tier now carries a NAMED committee + composition (the Stage-4 ladder),
     WITHOUT changing which authority/quorum it routes to (behaviour-preserving — asserted elsewhere
     by e2e_smoke / e2e_decisioning).
  2. The full PSB committee ladder (Regional/Zonal → Circle/FGM → HOCAC-I/II/III → MCB → Board) is
     exposed for the governance view.
  3. The cross-cutting escalation matrix (trigger → escalates-to) is exposed.
  4. The three lines of defence (role → line) are exposed: coverage (FIRST) originates,
     credit/risk (SECOND) concurs, the board (OVERSIGHT) reviews.

Read-only against the gateway on :8080; binds no port. Registered by the coordinator.
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://localhost:8080"
PASS, FAIL = 0, 0


def call(method, path, body=None, actor="credit.ops"):
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


for jur in ("IN-RBI", "AE-CBUAE"):
    print(f"== DoA / committee ladder — {jur} ==")
    st, lad = call("GET", f"/decision/api/decisions/doa/ladder?jurisdiction={jur}")
    check(f"[{jur}] ladder endpoint 200", st == 200 and isinstance(lad, dict), f"{st}")
    if st != 200 or not isinstance(lad, dict):
        continue

    tiers = lad.get("tiers") or []
    check(f"[{jur}] active tiers carry a named committee + composition",
          len(tiers) >= 4 and all(t.get("committee_label") and t.get("composition") for t in tiers),
          str([t.get("committee_label") for t in tiers]))
    check(f"[{jur}] tiers still carry the routing keys (authority/max_amount/min_grade)",
          all(t.get("authority") and ("max_amount" in t) and t.get("min_grade") for t in tiers), "")

    ladder = lad.get("committeeLadder") or []
    names = [c.get("tier") for c in ladder]
    check(f"[{jur}] full PSB committee ladder exposed (Zonal → … → Board)",
          len(ladder) >= 7 and any("HOCAC-III" in (n or "") for n in names)
          and any("Board" in (n or "") for n in names), str(names))

    matrix = lad.get("escalationMatrix") or []
    triggers = " ".join((m.get("trigger") or "") for m in matrix)
    check(f"[{jur}] escalation matrix exposed (quantum / rating-hurdle / deviation triggers)",
          len(matrix) >= 4 and "hurdle" in triggers.lower() and "deviation" in triggers.lower(),
          str([m.get("trigger") for m in matrix]))

    lod = lad.get("linesOfDefence") or {}
    check(f"[{jur}] three lines of defence exposed (RM=FIRST, credit=SECOND, board=OVERSIGHT)",
          lod.get("RM") == "FIRST" and lod.get("CREDIT_OFFICER") == "SECOND"
          and lod.get("BOARD_COMMITTEE") == "OVERSIGHT", str(lod))
    check(f"[{jur}] compliance is a SECOND-line control (not first-line coverage)",
          lod.get("COMPLIANCE") == "SECOND", str(lod.get("COMPLIANCE")))
    check(f"[{jur}] hurdle grade + escalation flags surfaced",
          lad.get("hurdleGrade") is not None and lad.get("deviationEscalatesOneLevel") is True, str(lad.get("hurdleGrade")))

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
