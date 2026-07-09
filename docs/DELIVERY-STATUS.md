# Helix — Remediation Delivery Status (hand-off)

Branch: **`claude/amazing-mayer-kaCZy`** · all work below is committed **and pushed** ·
every phase was proven by a dedicated e2e assertion and a clean full regression
(`scripts/run_regression.py` → **33 suites, 1228 assertions (incl. 421 smoke), 0 failures**)
on a freshly reseeded 10-service stack before commit.

This branch implements the remediation of the code-evidenced functional review in
`docs/FUNCTIONAL-REVIEW.md` (see its §0 for the finding-by-finding map). **P0 complete (A–E),
the agreed P1 programme (F, G, H, I, J) delivered, and P2 underway (K — notification transport;
L — fail-closed governance posture; M — real counterparty sector; N — group grade ladder; O — lifecycle terminal states + re-KYC sweep; P — CDD tiering from the CDD_TIERS pack — delivered).
The frontend surfaces (Q — audit-UI coverage; R — operational UIs) are delivered too. Every gap
identified in `FUNCTIONAL-REVIEW.md` is now closed.**

## Delivered

| Phase | Area | What it fixed | Commit |
|---|---|---|---|
| A | Limit-ledger + covenant cadence | D1 reversal decrements cumulative-drawn · D2 releasable reservations · D3 FX-converted mixed-currency root · D4 `SEMI_ANNUAL`→`HALF_YEARLY` | `08d2451` |
| B | Approver authority | G1 roles resolved server-side from `ACTOR_ROLE` (decide / override / confirm / write-off) · G3 pricing-exception tiers enforced | `82b4918` |
| C | Maker≠checker (SoD) | G2 across rating-confirm, doc-gen, commentary, pack dual-signer, FI submit-vs-approve | `29ac3e7` |
| D | Real limit-service integrations | G5 CAD limit-release + covenant freeze now make real (non-throwing) limit-service calls | `0ee35cd` |
| E | Consume dead config | §5 EWS_TRIGGER, IRAC day-counts, COVENANT_LIBRARY, dedup identifierFields, exception bps now read from config | `2e9c3ed` |
| F *(P1)* | Limit-service enforcement | D5 interchangeability pooled cap + intermediate-parent headroom · D8 country limits bind · G4 governed override + review queue | `a26d69c` |
| G *(P1)* | Rule-pack authoring | G6 maker-checker authoring API · author≠policy≠model-risk (3 humans) · `effectiveFrom` evaluated · lifecycle UI | `47bd148` |
| H *(P1)* | Model-of-record grade + SICR notch | `ratingModelOfRecord` opt-in: CONFIRMED model instance → authoritative grade via the shared `MasterScale` ladder (default-off; `gradeSource` provenance) · `ExposureRecord.originationGrade` snapshot (immutable across re-register) · ECL STAGE_2 when downgraded ≥ `sicr_notch_downgrade_stage2` notches (default 3) below origination | `a7da245` |
| I *(P1)* | Decisioning loop closure | CONDITIONAL_APPROVE/APPROVE `conditionsPrecedent` → CP register (`source=SANCTION`, fan-out, gate-enforced; inert unless supplied) · sanction letter via DocGen (`SANCTION_LETTER` template, DRAFT + advisory, maker≠checker confirm, no figure mutated) · committee/quorum voting (`CommitteeVote`, DoA `committee`/`quorum` flag; router can't vote, no double vote, quorum finalises; single-approver tiers unchanged) | `cb0f220` |
| J *(P1)* | India (RBI) regulatory pack | SMA-0/1/2→NPA sub-classification + CRILC large-credit feed (`DownstreamSystem.CRILC`, idempotent, threshold-filtered) · doubtful age-bands D1/D2/D3 with secured/unsecured split · working-capital drawing-power monitoring (advisory, ledger untouched) · restructure classification floor (held ≥ SUB_STANDARD/STAGE_2 for the hold period). All IN-RBI-only + absent-key-default, so CBUAE/IFRS-9 is byte-identical | `276a1df` |
| K *(P2)* | Notification transport (G5-notify) | Shared `com.helix.common.notify` outbox lane (auto-present per service like `/api/audit`): idempotent `Notification` + pluggable `NotificationTransport` (default `OutboxTransport` records only; real SMTP/webhook is a drop-in) + TTL-cached `EMAIL_TEMPLATE`/`NOTIFICATION_ROUTE` resolver + deterministic `{{token}}` render (SYSTEM audit). Wired additively (audit stays, try/catch-isolated) into covenant due/breach, MER reminder/overdue/escalated, committee-quorum-pending, CP nudge, CRILC report-due, EWS breach | `4f38c7e` |
| L *(P2)* | Fail-closed governance posture (G7) | Governed `GOVERNANCE_POSTURE` master (`failClosed`) flips the directory-outage authority behaviour from fail-open (default, unchanged) to fail-closed (deny). Centralised in `ActorDirectory.rolesFor` (throws `forbiddenPosture` on the outage branch under fail-closed) so consumers need zero edits; TTL-cached/stale-served; observable at `/api/governance/rbac/posture`; deny audit-stamped `RBAC_POSTURE_DENY` (SYSTEM, post-rollback) | `f457d1b` |
| M *(P2)* | Real counterparty sector (D6) | portfolio books the real counterparty `sector` from origination credit-inputs instead of the `segment` proxy (fallback: segment); the real sector flows into the concentration SECTOR dimension + ERM feed. A brittle concentration-stress assertion (cross-sector avg PD) was corrected to the true intent (stress multiple below the full shock) | `e1e3905` |
| N *(P2)* | Group grade ladder (D10) | deterministic GROUP grade on the AAA..D master ladder from member grades+exposures (`EXPOSURE_WEIGHTED_NOTCH` default; `WORST_OF`/`PARENT_ANCHORED` via the config-driven `GROUP_GRADE` pack) + per-member contribution breakdown; advisory + `GROUP_GRADE_DERIVED` (SYSTEM), member ratings unchanged | `38e0167` |
| O *(P2)* | Lifecycle terminals + re-KYC (D9) | reachable `CLOSED` via a governed close transition (audited, re-close 409) · deterministic re-KYC sweep flips `VERIFIED`→`RE_KYC_DUE` past the CDD-tier interval read from the `CDD_TIERS` pack (`asOf`-driven, idempotent, SYSTEM-audited + advisory `REKYC_DUE` notification); `verifyKyc` re-anchors the due date. Also a light consumption of `CDD_TIERS.rekyc_months` (ahead of E3) | `ad4b2c8` |
| P *(P2)* | CDD tiering from the pack (E3) | `deriveCddTier` now reads `enhanced_triggers`/`simplified_eligible`/`default_tier` from the `CDD_TIERS` rule pack (built-in fallback) instead of a hardcoded branch — behaviour-preserving vs the old logic, but the tiering moves when the pack is re-authored. Completes the CDD_TIERS consumption begun in O | `1f6a178` |
| Q *(P2)* | Audit-trail UI coverage (G8) | audit view extended from 6 to all 9 services + actorType/free-text filters + subject lookup; behaviour-preserving; `tsc`+`vite build` clean | `8e2ac89` |
| R *(P2)* | Operational UIs | new Committee Room (voting + sanction letter), Drawing Power, Notifications pages; RBAC-posture topbar chip; group-grade Stat; CRILC feed button; close + re-KYC-sweep actions — all reusing the governance design system; additive, no backend change | `ef64ec4` |

Design + regression-risk analysis for each phase was run up front (fan-out workflows)
so the first full regression came back green; every new check is scoped **inert unless
its config/structure is present**, so no existing assertion needed weakening.

## Test evidence

- `python3 scripts/run_regression.py` → **PASS**, 33/33 suites, 1228 assertions, 0 failures.
  Latest report under `regression-reports/` (newest timestamp).
- Phase P added `scripts/e2e_cdd_tiers.py` (10 assertions — the seeded CDD_TIERS pack reproduces
  the historical tiers; re-authoring to drop the PEP trigger moves the tier; finally-restored).
- Phase O added `scripts/e2e_lifecycle_rekyc.py` (17 assertions — CLOSED reachable/audited/
  idempotent; re-KYC not-due before / due after the tier interval; tier-differentiated from
  CDD_TIERS; SYSTEM audit + idempotent advisory notification; fresh obligor untouched).
- Phase N added `scripts/e2e_group_grade.py` (13 assertions — deterministic exposure-weighted
  ladder maths (AA/BBB/B → BBB), band coherence, SYSTEM audit, member grades unchanged, and a
  governed method-flip to WORST_OF with a finally-restore).
- Phase M added `scripts/e2e_sector_provenance.py` (7 assertions — a booked exposure records the
  real counterparty sector, not the segment proxy; it flows into the concentration SECTOR
  dimension; no segment value leaks in; segment-fallback preserved).
- Phase K added `scripts/e2e_notifications.py` (20 assertions — EMAIL_TEMPLATE rendering,
  idempotent outbox, additive/inert business responses, SYSTEM-actor audit, per-service
  `/api/notifications` across the decision + portfolio surfaces).
- Phase L added `scripts/e2e_governance_posture.py` (15 assertions — posture observable;
  fail-open allows the unknown actor during a simulated directory outage; governed maker-checker
  flip to fail-closed 403s the same call with an `RBAC_POSTURE_DENY` audit; clean restore to the
  e2e_rbac baseline). e2e_rbac itself is unchanged (default posture = fail-open).
- Phase J added `scripts/e2e_india_rbi.py` (27 assertions — SMA-0/1/2→NPA boundaries; CRILC
  batch + idempotency + threshold exclusion; doubtful secured/unsecured split numbers; drawing-
  power capping (advisory, ledger untouched); restructure floor at dpd 0; and a CBUAE control
  proving the overlay is IN-RBI-only).
- Phase H added two dedicated suites: `scripts/e2e_ratingmodel.py` (19 assertions — inert
  until confirmed; confirmed model-of-record flips the grade via the shared ladder; advisory
  model stays advisory) and `scripts/e2e_sicr_notch.py` (17 assertions — origination-grade
  snapshot immutability + notch-driven STAGE_2 at dpd 0 as the sole trigger).
- Phase I added `scripts/e2e_decisioning.py` (28 assertions — conditions → CP register with
  fan-out + gate enforcement; sanction letter advisory + maker≠checker confirm + grade/outcome
  unchanged; committee quorum voting with router-can't-vote / no-double-vote / quorum-finalises
  SoD). It authors and restores a transient committee DOA_MATRIX so the shared DB stays seeded.
- `scripts/e2e_smoke.py` (the single safety contract) grew from ~342 → **421** assertions;
  each phase added negative + positive proofs (e.g. self-attested role still 403; edit the
  master → behaviour moves; over-parent draw rejected; author cannot self-sign).
- Frontend `npx tsc --noEmit` **and** `npm run build` (`tsc -b && vite build`) clean after the
  Phase Q/R UI work (3 new pages + api additions + nav wiring + in-page additions). The frontend
  has no e2e; its gate is the typecheck + build, and no backend file was touched (the backend
  regression is unaffected).
- The AI/advisory invariant is untouched throughout — every fix is in a deterministic
  path or adds a human/RBAC gate; no authoritative figure is read or mutated by an
  advisory engine (the "unchanged" e2e assertions still hold).

## How to verify

```bash
mvn -q -DskipTests package                 # or -pl <touched-services> -am
bash scripts/stop-all.sh; rm -rf ./data    # clean reseed (seed/entity changes need it)
bash scripts/run-all.sh                    # health-gated 8080–8089
python3 scripts/e2e_smoke.py               # primary contract
python3 scripts/run_regression.py          # full 33-suite sweep
cd frontend && npm install && npx tsc --noEmit
```

## Remaining backlog (in the agreed order)

**P1 — complete.** The agreed P1 programme (F, G, H, I, J) is delivered:
- H — MODEL_DEFINITION → authoritative grade (opt-in model-of-record) + origination-grade
  snapshot + SICR notch-downgrade.
- I — decisioning loop closure: conditions → CP register + sanction letter + committee/quorum.
- J — India (RBI) pack: SMA/CRILC + doubtful age-bands (secured/unsecured) + drawing power +
  restructure classification floor.

**P2 — complete.**
- K — **notification transport (G5-notify)**: governed `EMAIL_TEMPLATE`-rendered outbox lane
  (default OUTBOX sink; real SMTP/webhook a drop-in), wired additively into the covenant / MER /
  CP / committee / CRILC / EWS trigger points. **Delivered.**
- L — **fail-closed governance posture (G7)**: governed `GOVERNANCE_POSTURE` toggle centralised
  in `ActorDirectory`; default fail-open unchanged, fail-closed denies on a directory outage with
  a `RBAC_POSTURE_DENY` audit; observable posture endpoint. **Delivered.**
- M — **real counterparty sector (D6)**: portfolio books the real sector, not the segment proxy;
  flows into concentration + ERM. **Delivered.**
- N — **group grade ladder (D10)**: deterministic, config-driven group grade on the AAA..D
  ladder; advisory, member ratings unchanged. **Delivered.**
- O — **lifecycle terminals + re-KYC (D9)**: reachable CLOSED transition; deterministic re-KYC
  sweep (RE_KYC_DUE) driven by the CDD_TIERS interval. **Delivered.**
- P — **CDD tiering from the pack (E3)**: tier derivation reads the CDD_TIERS pack; re-authoring
  moves the tier. **Delivered.**
- Q — **audit-UI coverage (G8)**: audit view across all 9 services + filters + subject lookup.
  **Delivered.**
- R — **operational UIs**: Committee Room, Drawing Power, Notifications pages; posture chip;
  group-grade Stat; CRILC feed; close + re-KYC-sweep actions. **Delivered.**

**Backlog complete.** Every gap identified in `docs/FUNCTIONAL-REVIEW.md` (P0 A–E, P1 F–J,
P2 K–R) is delivered — each behaviour-preserving, on a clean full regression (33 backend suites /
1228 assertions / 0 failures) with the frontend typecheck + build clean. Anything further is
net-new enhancement, not a review finding.

No PR has been opened (none requested). The branch is ready for review or a PR whenever
you want one.
