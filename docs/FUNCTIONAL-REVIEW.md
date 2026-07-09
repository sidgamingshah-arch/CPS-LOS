# Helix — Functional Review (Wholesale Credit)

**Reviewer lens:** wholesale-credit processing practice — the lifecycle as a bank runs it, the
controls a regulator expects, and the calibration a credit-risk function would sign off.
**Evidence base:** full code-level inventory of all 9 services + gateway + frontend + all 33 e2e
suites (each business rule verified against source with file:line citations; the sharpest
findings re-verified by hand). This reviews *function and domain correctness*, not code style.

---

## 0. Remediation status — P0 delivered, P1 underway

Every item below has been fixed on branch `claude/amazing-mayer-kaCZy`, each as a
coherent commit proven by a dedicated e2e assertion and a clean full regression
(33 suites, 1228 assertions — incl. 421 in the smoke contract — 0 failures) on a
clean-reseeded 10-service stack. Design and adversarial/regression analysis for each
phase was done up front so the first regression came back green. **P0 (A–E) complete;
P1 (F–J) delivered; P2 backend (K–P) delivered; the frontend surfaces (Q — audit coverage,
R — operational UIs) delivered. Every gap identified in this review is now closed.**

| Phase | Scope | Findings closed | Commit |
|---|---|---|---|
| **A** | Limit-ledger + covenant-cadence correctness | **D1** (REVERSAL now decrements cumulative drawn), **D2** (reservations releasable via `RELEASE_RESERVE`), **D3** (obligor root = FX-converted sum of legs), **D4** (`SEMI_ANNUAL`→`HALF_YEARLY` cadence) | `08d2451` |
| **B** | Server-side approver authority | **G1** (`decide`/rating-override/rating-confirm/write-off resolve the actor's real roles from `ACTOR_ROLE`, never a body role), **G3** (pricing-exception tiers enforced) | `82b4918` |
| **C** | Maker≠checker (SoD) | **G2** — rating-confirm≠overrider, doc-gen confirm≠generator, commentary review≠drafter, pack policy-signer≠model-risk-signer, FI approve≠submitter | `29ac3e7` |
| **D** | Real limit-service integrations | **G5** — CAD limit-release and covenant freeze now make real, non-throwing calls to limit-service (freeze/release the deal's nodes); the audit-event stubs became genuine | `0ee35cd` |
| **E** | Consume dead config | **§5** — IRAC day-counts, pricing-exception bps, `COVENANT_LIBRARY` suggestions, dedup `identifierFields`, and `EWS_TRIGGER` thresholds are now read from config (constant kept as fallback), each proven by a "edit the master → behavior moves" e2e | `2e9c3ed` |
| **F** *(P1)* | Limit-service enforcement | **D5** (fungible-group pooled cap + intermediate-parent headroom now enforced in utilisation), **D8** (country limits now bind on a confirmed draw, only-when-configured), **G4** (utilisation override flag now requires a `LIMIT_OVERRIDE` role + an append-only review queue) | `a26d69c` |
| **G** *(P1)* | Rule-pack authoring | **G6** — a maker-checker authoring API (`POST /api/rulepacks` drafts a new version; `GET /api/rulepacks/drafts` is the checker queue), `author ≠ policy-signer ≠ model-risk-signer` (3 distinct humans), `effectiveFrom` now evaluated by the resolver (a future-dated activated pack does not supersede until its date), plus a pack-lifecycle UI (propose + dual sign-off) | `47bd148` |
| **H** *(P1)* | Model-of-record grade + SICR notch | **MODEL_DEFINITION → authoritative grade** (opt-in): a `ratingModelOfRecord: true` definition **plus** a human-CONFIRMED model instance makes the model composite the authoritative grade, mapped through the *same* `MasterScale` score→grade ladder the scorecard uses; default-off, so the scorecard stays authoritative and the AI-advisory invariant is untouched (`gradeSource` provenance `SCORECARD`/`MODEL_OF_RECORD`). **SICR notch-downgrade** — `ExposureRecord` now snapshots the origination grade (immutable across re-register), and the ECL engine moves an exposure to STAGE_2 when the current grade is ≥ `sicr_notch_downgrade_stage2` notches (default 3) below origination — even at dpd 0 and a non-weak grade | `a7da245` |
| **I** *(P1)* | Decisioning loop closure | **Conditions register** — structured conditions of sanction on an APPROVE / CONDITIONAL_APPROVE now materialise into the pre-disbursement CP register (`source=SANCTION`, fan-out across facilities when unpinned), so the existing gate enforces them; inert unless `conditionsPrecedent` is supplied. **Sanction letter** — generated from the DECIDED approval through the existing DocGen machinery (new `SANCTION_LETTER` template), DRAFT + advisory, quoting the deterministic facilities/pricing + conditions, human-confirmed with maker≠checker; mutates no authoritative figure. **Committee / quorum** — a `committee`/`quorum`-flagged DoA tier requires N distinct approving votes (`CommitteeVote`), with SoD (the router cannot vote, no member votes twice, authority server-resolved from `ACTOR_ROLE`); single-authority tiers keep the one-approver path | `cb0f220` |
| **J** *(P1)* | India (RBI) regulatory pack | **SMA + CRILC** — `EclEngine` sets an orthogonal SMA bucket (SMA-0/1/2→NPA) by DPD when `sma_enabled`; `ExportService.generateCrilc` emits a canonical CRILC large-credit feed (new `DownstreamSystem.CRILC`) for borrowers at/above `crilc_exposure_threshold` in SMA/NPA, idempotent per as-of day. **Doubtful age-bands** — a `DOUBTFUL` account is provisioned secured-portion × D1/D2/D3 rate + unsecured × 100% when `irac_doubtful_age_bands` is present (else the flat rate). **Drawing power** — advisory `DrawingPowerService` (DP from the borrowing base, shortfall flag; never touches the ledger). **Restructure floor** — a RESTRUCTURED account is held at ≥ SUB_STANDARD / STAGE_2 while `restructure_npa_hold_months` has not elapsed. All keys are IN-RBI-only and default to current behaviour when absent, so CBUAE / IFRS-9 is byte-identical | `276a1df` |
| **K** *(P2)* | Notification transport (G5-notify) | A governed outbound notification lane behind `EMAIL_TEMPLATE`: a shared `com.helix.common.notify` package (auto-present in every service like `/api/audit`) with a `Notification` outbox (idempotent per `eventType\|subjectRef\|dedupeKey`), a pluggable `NotificationTransport` whose default `OutboxTransport` records only (the rendered row **is** the deliverable; real SMTP/webhook is a drop-in), a TTL-cached `EMAIL_TEMPLATE`/`NOTIFICATION_ROUTE` resolver, and deterministic `{{token}}` rendering stamped SYSTEM (`NOTIFICATION_ENQUEUED`). Wired **additively** (the audit event stays; enqueue is try/catch-isolated, never fails the business op) into covenant due/breach, MER reminder/overdue/escalated, committee-quorum-pending, CP nudge, CRILC report-due, and EWS breach | `4f38c7e` |
| **L** *(P2)* | Fail-closed governance posture (G7) | The authority layer fails OPEN on a cold-start `ACTOR_ROLE` directory outage (humans keep working; SoD still applies). A governed `GOVERNANCE_POSTURE` master (`failClosed`) now lets a bank flip to FAIL-CLOSED so the outage **denies** instead. Centralised in `ActorDirectory.rolesFor` (throws `forbiddenPosture` on the outage branch under fail-closed; returns null / unchanged under the default fail-open), so every consumer's null-fallback is simply never reached — **zero call-site edits**. Posture is TTL-cached/stale-served (mirrors `AiGovernanceClient`), observable at `GET /api/governance/rbac/posture`, and a deny is audit-stamped `RBAC_POSTURE_DENY` (SYSTEM, post-rollback). Default = fail-open = current behaviour | `f457d1b` |
| **M** *(P2)* | Real counterparty sector (D6) | portfolio-service booked an exposure's `sector` as a proxy for the `segment`. It now books the **real** counterparty sector that origination already captures (`counterparties.sectorFor(...)`), falling back to the segment only when a deal carries none — so the concentration SECTOR dimension and the ERM feed carry the actual sector, not a segment value masquerading as one. A brittle concentration-stress assertion (cross-sector average PD) was corrected to the true intent (a correlated sector's stress *multiple* stays below the full shock), which strengthens it | `e1e3905` |
| **N** *(P2)* | Group grade ladder (D10) | Group decisioning derived only a best→weakest grade **band**. It now derives a defensible GROUP grade on the same AAA..D master ladder from member grades + exposures, per a config-driven `GROUP_GRADE` pack (default `EXPOSURE_WEIGHTED_NOTCH`; also `WORST_OF` / `PARENT_ANCHORED`), with a per-member contribution breakdown. Deterministic + advisory: it reads authoritative member figures and mutates none of them (member grades byte-identical after the rollup, asserted), stamped `GROUP_GRADE_DERIVED` (SYSTEM). Reuses the 10-notch ladder, not the divergent band ladder | `38e0167` |
| **O** *(P2)* | Lifecycle terminals + re-KYC (D9) | Two states the counterparty state machine declared but never reached are now reachable via governed transitions. **CLOSED** — a `close` transition on an ACTIVE obligor (reason-required, re-close 409, `COUNTERPARTY_CLOSED` HUMAN audit). **RE_KYC_DUE** — a deterministic re-KYC sweep flags VERIFIED counterparties whose KYC is older than their CDD-tier interval (read from the `CDD_TIERS` pack, 12/24/36 fallback), evaluated at an `asOf` so due-ness is testable without waiting real months; idempotent, SYSTEM-audited (`REKYC_DUE`/`REKYC_SWEEP`), with an advisory `REKYC_DUE` notification via the Phase K lane. Consumes `CDD_TIERS.rekyc_months` (a head start on E3) | `ad4b2c8` |
| **P** *(P2)* | CDD tiering from the pack (E3) | `deriveCddTier` was a hardcoded branch. It now reads the jurisdiction's `CDD_TIERS` rule pack — `enhanced_triggers` / `simplified_eligible` / `default_tier` — matching the counterparty's risk flags against the pack's trigger keys (`ConfigMasterClient.cddTiers`, built-in fallback). The seeded pack's lists match the historical logic, so it's behaviour-preserving; but the tiering now **moves** when the pack is re-authored (maker-checker) instead of being frozen in code. Completes the `CDD_TIERS` consumption begun in Phase O | `1f6a178` |
| **Q** *(P2)* | Audit-trail UI coverage (G8) | The audit view covered 6 of 9 services; it now covers all nine (`config`/`counterparty`/`origination`/`risk`/`decision`/`portfolio`/`copilot`/`limits`/`workflow`), with an actorType (HUMAN/AI/SYSTEM) filter, a free-text filter, and a subject-lookup ("everything touching subject X"). Behaviour-preserving; `tsc --noEmit` + `vite build` clean | `8e2ac89` |
| **R** *(P2)* | Operational UIs | Screens for the capabilities that lacked a surface, reusing the governance design system: **Committee Room** (quorum voting + SoD + sanction letter via `GovFlow`), **Drawing Power** (deterministic advisory, ledger `Unchanged`), **Notifications** outbox; plus a topbar RBAC-posture chip (G7), a derived **group-grade** Stat (D10), a **CRILC** feed button/table (J), and governed **close** + **re-KYC sweep** actions (D9). Additive routes; no backend change; `tsc`/`vite build` clean | `ef64ec4` |

**Deferred:** none of the review's identified gaps remain — the P0/P1/P2 backlog and the
operational UIs are all delivered. Anything beyond this is net-new enhancement, not a finding
from this review.

The findings in sections 3–5 are preserved as the original review record; items marked
above are now fixed in code.

---

## 1. Verdict

Helix is a genuinely end-to-end wholesale LOS/LMS reference platform with an unusually strong
governance chassis. The credit spine — initiation → KYC/screening → application → spreading →
rating → capital → RAROC pricing → DoA decision → CP/CAD → limits → disbursement → repayment →
amendment → collections → covenants/EWS/MER → ECL/IRAC → exports — is *implemented and
regression-proven* (33 suites, ~1,228 assertions through the gateway), which is more than most
commercial LOS demos can claim. Its most distinctive idea — **AI is advisory, humans gate,
deterministic figures are byte-provably unchanged** — is not marketing: it is enforced in code
(separate advisory entities, human-gate transitions, per-capability kill switches) and asserted
byte-level in the e2e contract.

It is **not yet bank-grade** in four specific ways:

1. **Authority integrity leaks.** The single most important control in a credit system — *who
   may approve* — is partially self-attested: the DoA decide endpoint trusts a role supplied in
   the request body, and several human gates lack maker≠checker separation.
2. **A handful of real correctness defects** in the limit ledger and covenant cadence that would
   corrupt books in production (reversal accounting, unreleasable reservations, mixed-currency
   root totals, semi-annual covenant roll-forward).
3. **The config-over-code thesis is breached at the edges.** Several masters/pack keys are
   seeded, documented, demo-visible — and never read by the engines they claim to drive
   (EWS thresholds, IRAC day-counts, SICR notch trigger, covenant library, exception tiers).
   Rule packs themselves have **no authoring API** — they exist only via the seeder.
4. **Servicing/regulatory depth** is thinner than the origination side: no SMA buckets or
   drawing-power engine (India), interchangeability is captured but not enforced at utilisation,
   country limits are never checked, and several "integrations" are audit-log entries only.

None of these are architectural; every fix lands on an existing seam. Sections 3–5 are the
priority work list.

---

## 2. What is genuinely strong

- **The advisory invariant, proven, everywhere.** RAG/macro overlays, pricing optimiser,
  concession workflow, doc/collateral/covenant intelligence, commentary, CPT, group insights —
  each persists its own advisory entity, is stamped `audit.ai`, has a human gate stamped
  `audit.human`, can be killed per-jurisdiction via the `AI_GOVERNANCE` master (server-side 403,
  UI hides the nav item), and the e2e asserts the authoritative grade/PD/price is byte-identical
  before/after. `e2e_ai_off.py` even replays the whole lifecycle under an AI-endpoint denylist
  and asserts no figure-path audit event is AI-attributed. This is the product's moat and it holds.
- **A real post-sanction lane** — rare in reference builds: CP register seeded from a
  jurisdiction-aware `CP_MASTER`, a pre-disbursement gate that 403s while mandatory CPs are open,
  a **three-actor disbursement** flow (requester ≠ authoriser ≠ releaser, all enforced), release-time
  FX re-quote with headroom re-check, maker-checker repayments that book limit RELEASE, DoA-routed
  facility amendments applied transactionally to origination + the limit tree, DoA-routed
  write-offs, project-finance milestone/DSRA gates with an LIE certification role, and an
  EWS→collections auto-open loop (case shell only; workout stays human).
- **Two-jurisdiction regime overlay actually works.** IN-RBI vs AE-CBUAE differ purely in data:
  DoA thresholds (50m/250m/1bn vs 20m/100m/500m), hurdle RAROC (15% vs 13.5%), provisioning
  policy (`max(ecl,irac)` vs `ecl`), CDD flags, exposure norms (15% vs 25% single-name), CPs
  (CERSAI/ROC/stamp/CIBIL vs Emirates ID/Tabu/AECB/ESR). No code branches on regime.
- **FTP done properly for a demo tier:** currency-keyed tenor curves, behavioural maturity
  (WAL life-factors for amortising, fixed behavioural life for revolvers/demand), liquidity
  premium by behavioural life, jurisdiction override, flat-CoF fallback labelled as such.
- **Identity spine better than most demos:** HMAC bearer tokens, gateway-verified `X-Actor`
  injection, spoof e2e (token subject beats client header), RBAC via an `ACTOR_ROLE` master with
  cache invalidation, and dozens of enforced SoD comparisons (master maker-checker, CAD L1≠L2,
  MER verifier≠submitter, pricing-exception proposer≠L1≠L2, CAP closer≠owner, receiving-RM
  acceptance, reserve withdrawer≠funder…).
- **Canonical connector/export contracts** with enforced idempotency on `(source, key)` /
  as-of-day batches; replay-safe, warning-carrying, audited. The right integration shape.
- **Domain fluency in the details:** crore/lakh money parsing, Indian digit grouping, dated
  period-end FX for restatement vs spot for limits (two-level currency is conceptually correct),
  supervisory slotting for project finance, SME supporting factor, sublimit cap validation,
  syndication fee waterfall + pro-rata agency reconciliation + unfunded-only secondary transfers.

---

## 3. Correctness defects (fix before any external demo touches money paths)

| # | Defect | Where | Impact |
|---|--------|-------|--------|
| D1 | **REVERSAL never decrements `cumulativeDrawn`** — `adjust()` clamps the drawn delta to ≥0 (`Math.max(0, drawnDelta)`), while REVERSAL passes `-amt`. EOD reconciliation *replays* reversals as `-=`, and decision-service's client doc says reversal undoes drawn. | `limit-service/.../UtilisationService.java:151,196`; `EodService.java:184` | Non-revolving headroom never restored on reversal; **every reversal manufactures a permanent LEAF reconciliation variance** — the recon feature discredits itself on first use. |
| D2 | **Reservations are never releasable.** RESERVE increments `reserved`; no action or code path ever decrements it. Forced reserves also skip the `overrideApplied` flag. | `UtilisationService.java:150,145-146` | Earmarks permanently consume headroom; RESERVE→UTILISE conversion (the normal product-processor pattern) is impossible. |
| D3 | **Obligor root sums mixed-currency facility amounts without conversion** (`mapToDouble(amount).sum()` over raw native amounts), while node math is otherwise base-INR. | `limit-service/.../LimitService.java:153` | A USD 10m + INR 100m deal produces a meaningless root cap; single-name norm checks then run against it. |
| D4 | **Covenant frequency token mismatch:** intel extraction emits `SEMI_ANNUAL`; the schedule and MER roll-forwards only recognise `HALF_YEARLY`, so semi-annual falls to the default **+3 months**. | `CovenantIntelligenceService.java:436` vs `CovenantWorkflowService.java:252`, `MerService.java:391` | A confirmed semi-annual covenant is silently tested quarterly — wrong compliance calendar. |
| D5 | **Interchangeability is inert at runtime.** Fungible groups are captured in origination and rolled up in the limit view, but `validate()`/`applyOne()` never read the fungible flag — utilisation is capped at the node's own amount, so a member can never borrow a sibling's headroom. Intermediate (facility-level) caps are *also* unchecked (only own node + obligor root). | `limit-service/.../UtilisationService.java:53-74` | The flagship sublimit feature has no behavioural effect; conversely a draw on one sublimit never consumes facility-level headroom of its siblings' parent. |
| D6 | **`sector` = segment proxy at exposure booking** (`e.setSector(inputs.segment())`). | `portfolio-service/.../PortfolioService.java:73` | Sector concentration, sector-correlation stress and the RAG/EWS sector logic all run on segments — the 9-sector correlation matrix in the pack can never match. Counterparty.sector exists and is already pinned on the application; it just isn't carried through. |
| D7 | **`buildFromDeal` bypasses the sibling-allocation cap** enforced on manual child adds; also root "sector" is set from *segment* and country from jurisdiction string. | `LimitService.java:166-167,193-219` | Over-allocated sublimits (Σ children > parent) can enter via the normal path; the origination-side cap mostly saves this, but amendments/resync make divergence reachable. |
| D8 | **Country limits are decorative:** `CountryLimit.outstanding` is never accumulated and no utilisation path checks the country cap; department gross-OSUC updates only via FI approval. | `CountryAndFiService.java:100-107,179-182` | The country-limit screen shows full availability forever; only the *department* Σ ≤ country cap upsert check exists. |
| D9 | **Terminal states unreachable:** `CLOSED` (limit nodes, counterparties), `ISSUED` (documents), `DELIVERED` (export batches), `PENDING_REVIEW/REJECTED/RE_KYC_DUE` (KYC) are declared but no transition sets them; re-KYC due dates are stamped but nothing sweeps them. | multiple | Lifecycle can be opened but not properly ended; re-KYC is tracked but never triggers. |
| D10 | **Group insights uses a private 20-notch grade ladder** vs the platform's 10-grade master scale. | `GroupInsightsService.java:35-38` vs `MasterScale.java:12` | Band arithmetic in group rollups disagrees with the rating ladder used everywhere else. |

Also worth knowing (not defects, but surprising): RELEASE and REVERSAL skip the frozen/expired
hard-stops entirely (defensible for repayments, but means a frozen line's exposure can be
*reduced-then-redrawn* via idempotency-key variation only barred by the UTILISE stop); EOD "runs"
are per-invocation rows, not one-per-date (idempotency is "no new revaluation on unchanged
rates", not run-once).

---

## 4. Governance integrity gaps (the thesis's own standard, applied)

| # | Gap | Where |
|---|-----|-------|
| G1 | **DoA decide trusts a client-supplied role.** `role = req.role()` then rank-check — any caller can claim `BOARD_COMMITTEE`. The platform already has the fix in-house: facility amendments resolve the actor's real roles via `ActorDirectory` and rank-check those. The same must apply to `decide`, rating override (`role` also from body), and write-off approve. | `DecisionService.java:99-105`; `RiskService.java` override; contrast `FacilityAmendmentService.java:256-266` |
| G2 | **No maker≠checker on:** rating confirm (the overrider can confirm their own override — and confirm has no role requirement at all), spread confirm (overriding analyst confirms own override), doc-gen confirm (lock exists, separation doesn't), commentary review, covenant-intel confirm, certificate-assessment review, CPT review, FI transaction decide (submitter isn't even persisted), CAD item status updates. CLAUDE.md *claims* SoD on doc-gen confirm and commentary review — the code does not enforce it. | `RiskService.java:140-150`; `DocGenService.java:211-225`; `CommentaryService.java:94-110`; `CountryAndFiService.java:155-187` |
| G3 | **Pricing-exception authority tiers are recorded, not enforced** — any distinct actor can approve L1/L2 regardless of the computed `requiredAuthority`; the tier thresholds (`exception_single_level_bps`/`two_level_bps`) are absent from the seeded packs so code defaults always apply. | `PricingExceptionService.java:113-116,146-201` |
| G4 | **The limit `overrideFlag` is ungoverned** — any caller may force past available/norm breaches; it is only recorded. No role, no approval, no post-facto review queue. | `UtilisationService.java:31-32` + `Dtos.java:53-54` |
| G5 | **Integration events that don't integrate:** CAD limit-release, covenant freeze-accounts /
freeze-disbursement, all notifications (COVENANT_DUE, MER_DUE, ownership), and the DMS feed are
audit-log rows only — nothing calls limit-service, nothing freezes, nothing sends. A demo
narrative reading "feeds limit management" is, today, a log line. | `CadService.java:203-205`; `CovenantWorkflowService.java:228-231`; `MerService.java:221-223` |
| G6 | **Rule packs cannot be authored:** no create/edit endpoint exists (seeder-only); dual sign-off never checks the two signers differ; `effectiveFrom` is stored but ignored by the active-pack lookup. The centrepiece governance object is read-only and its one control is self-signable. | `ConfigService.signOff`; `RulePack.java` |
| G7 | Fail-open postures worth a deliberate decision: RBAC directory cold-start fails open; AI governance fails open on never-fetched for `pricing-optimiser`, `pricing-exception`, `model-scoring`, `covenant-intel`, `commentary`, `cpt`, `group-suggest`, `copilot` (only 4 capabilities fail closed). | `ActorDirectory.java:74-90`; `AiGovernanceClient.java:63-87` |
| G8 | Audit UI covers 6 of 9 services (limits, copilot, workflow missing from the picker) — precisely the services with the money movements and AI Q&A. | `AuditLog.tsx` |

---

## 5. Config-over-code breaches (seeded but never consumed)

The platform's promise is "a new regime is overlay data, never a code branch." These break it —
an administrator edits the master/pack, approval flows run, **and nothing changes**:

| Seeded artefact | Engine actually uses | Where |
|---|---|---|
| `EWS_TRIGGER` master (DPD 30/60, leverage 3.5/4.5, DSCR 1.0/1.25, criticality) | Hardcoded *different* thresholds (DPD 30/90, leverage 4.0, DSCR 1.1) | `EwsService.java:39-99` — zero references to the master |
| `COVENANT_LIBRARY` master (5 covenants incl. thresholds) | Hardcoded grade-tiered suggestions; extraction taxonomy compiled in | `CovenantService.java:124-137` |
| PROVISIONING pack keys `irac_dpd_substandard: 90`, `irac_dpd_doubtful: 365` | Hardcoded 90/365 constants | `EclEngine.java:101-110` |
| PROVISIONING pack key `sicr_notch_downgrade_stage2: 3` | Never read; no origination-grade snapshot exists to compare against | `EclEngine.java:87-95` |
| CAPITAL_SA key `below_investment_grade_corporate_rw: 1.5` | Never read (the ECRA `B_AND_BELOW` bucket delivers 1.5 anyway; the key is dead) | `CapitalEngine.java:114-116` |
| PRICING keys `exception_single_level_bps`/`two_level_bps` | Not in seeds → code defaults 100/200 always apply | `PricingExceptionService.java:77-78` |
| `DEDUP_RULES.identifierFields` `[registrationNo, pan, passport, gstin]` + `combineWith` | Only `registrationNo` exact-match; only `nameMatchThreshold` consumed | `InitiationService.java:96-124` |
| `INACTIVITY_THRESHOLD` master | Client method exists, never called | counterparty `ConfigMasterClient.java:61-65` |
| `EMAIL_TEMPLATE` masters | Referenced by *name* in audit rows; body never fetched/rendered (and `MER_DUE` isn't even seeded) | decision + counterparty services |
| CDD tiers / UBO 10% / re-KYC months (in `CDD_TIERS` pack) | Hardcoded in Java with comments *claiming* the pack | `CounterpartyService.java:75-91`; `UboService.java:29-31` |
| Rating scorecard weights/bands, RAG/macro coefficients, stress scenarios, RAROC-actual constants (CoF 7.5%, opex 1%, quarterly), material-miss 25%, benchmark spread flags | All hardcoded — no pack type exists for any of them | `RatingEngine.java:30-37`; `AdvisoryRiskService.java`; `PortfolioService.java:213-216`; `RarocTrackingService.java:30-31` |

The fix is mechanical (read the key where the constant sits, keep the constant as fallback), and
each one should carry an e2e assert that editing the master changes behaviour — the CODE_VALUE
suite already demonstrates the pattern.

---

## 6. Stage-by-stage functional assessment

**A. Onboarding & KYC** — *Solid shape, thin depth.* Prospect lifecycle with dedup (Jaccard +
registration exact), hard negative-list gate at conversion, ExternalCheck façade for the five
source-system checks, UBO graph with path-product effective ownership + cycle detection, CDD
tiering with re-KYC dates, screening with severity-gated KYC verification and SEVERE-can't-clear.
Gaps: dedup ignores its own configured identifier fields (PAN/GSTIN); screening/bureau/rating are
simulations keyed off the record's own flags; **no UI at all for the prospect lifecycle** (the
flagship initiation module is API/e2e-only — Counterparties.tsx creates obligors directly);
re-KYC is stamped, never swept; no periodic-review workflow; same RM can create and approve a
prospect (dedup/hits don't block conversion — only advisory "blockers" text).

**B. Application & structuring** — *Good.* Facilities/sublimits with cap + currency validation,
six deal structures with variant-aware findings, copy-from for renewals, syndication lifecycle
(invitations→allocations→transfers→feed) with real SoD. Gaps: **application status has no
transition graph** (any of the 12 statuses settable by PATCH, no actor rules) — the workflow
tracker exists but no domain service records into it; structure validation is advisory
(ERROR findings don't block rating/decisioning); no renewal/review *scheduling* ties an approved
deal back to a future review (MER's RENEWAL_REVIEW item stands in).

**C. Spreading & financial analysis** — *Strong core.* Fixed canonical chart + template-driven
extensions, cell provenance, material-override gate that re-opens confirmation, two-level FX with
a refuse-to-guess rate guard, sector-resolved templates and projections. Gaps: no
audited/provisional or consolidated/standalone flags (a credit decision needs to know); trends
use only two periods; benchmark flags hardcoded (INDUSTRY_BENCHMARK master exists — wire it);
overriding analyst confirms their own spread.

**D. Rating & models** — *Correct governance, hardcoded model.* The 7-factor scorecard's weights
and grade cut-points are Java constants — a bank cannot change its rating model without a
release. The new MODEL_DEFINITION engine solves exactly this (sections/weights/visibility/
constraints, module-sourced + standalone params, advisory suggest) but is **not connected to the
authoritative grade** — it produces an advisory composite alongside. That's the right sequencing
under the governance thesis, but the destination must be: model-engine output *becomes* the model
grade under a signed-off MODEL_DEFINITION, with the scorecard as fallback. Also: no rating
validity/expiry/annual-review trigger; no origination-grade snapshot (blocks SICR-by-downgrade
and transition matrices); override reason codes fine, notch limits fine — but role self-attested
(G1) and confirm un-separated (G2).

**E. Capital & pricing** — *Right shape, calibration notes.* SA with ECRA buckets, slotting by
DSCR, CCFs, SME factor, DD uplift; RAROC with FTP. Notes: collateral-covered portion gets **0% RW
regardless of collateral type** (property should attract RE/IPRE weight — this understates RWA on
secured lending); BBB corporate at 100% vs Basel III's 75% (conservative — fine if deliberate);
`ddDone = spreadConfirmed` proxy; **no fee income in pricing-of-record** while the optimiser
prices fees (asymmetry: optimiser scenarios aren't reproducible as record pricing); EAD =
requested amount at rating (CCF applies only in capital).

**F. Sanction & decisioning** — *Single-approver model.* DoA matrix routing with
deviation-escalation works and is pack-driven. Gaps: no committee/quorum (a
CREDIT_COMMITTEE decision is one person with dissent free-text); **conditions on
CONDITIONAL_APPROVE are stored strings** — nothing materialises them into the CP/CS register
that the platform *already has* (one line of wiring closes a real credit-ops loop);
no sanction-letter artifact (doc-gen exists — add the template + generation-on-approve).

**G. Documentation, CP & CAD** — *Good bones.* CP register with jurisdiction overlays and a
hard pre-disbursement gate is genuinely right. Gaps: CAD checklist selection is
`recordKey.contains("SECURED")`; CAD limit-release is an audit event (G5); doc-gen clause bodies
are boilerplate switches; no execution/e-sign/DMS integration (fine as seams — but say so).

**H. Limits & disbursement** — see D1–D8. The disbursement lane itself (gate → authorize →
release → reverse, FX re-quote, idempotent booking) is the platform's best-controlled money path.

**I. Servicing** — Repayments (schedule engine + maker-checker + core-banking ingest) and
amendments are solid. Collections: staging 30/90, legal gate, DoA write-off, cure discipline —
good skeleton; missing: restructured-asset classification consequences (a restructure today
just amends the facility — no downgrade/upgrade rules), no interest accrual/DPD feed (DPD is
set by API/ingest), no settlement/OTS lane.

**J. Monitoring** — Covenant tracking with grace/waiver/extension SoD is right; certificate
assessment with deterministic recompute + taxonomy-mismatch detection is a standout feature.
Gaps: D4 (cadence), tracking decisions/freeze have **no UI**; EWS ignores its master (§5) and
signal disposition has no UI; the RBI early-warning/red-flag indicator list (RFA) is absent;
MER reminder emission re-fires on every call (no dedup on `lastReminderAt`).

**K. Portfolio & regulatory** — ECL/IRAC dual-track with `max(ecl,irac)` policy is conceptually
right for IND-AS banks. Calibration gaps: lifetime PD fixed at 3 years regardless of tenor; ECL
EAD is the static booked EAD (no amortisation, no CCF); Stage-2 lacks notch-downgrade SICR; no
forward-looking multi-scenario weighting (single overlay multiplier); IRAC lacks **SMA-0/1/2**,
doubtful age-bands (25/40/100) and the secured/unsecured provisioning split; standard-asset rate
is flat 0.40% (no CRE 1.00%/CRE-RH 0.75%/teaser differential). Concentration/HHI/correlation
stress are pack-driven and better than typical; book stress scenarios are hardcoded; RAROC
actuals use hardcoded CoF/opex/quarter (should read the PRICING pack + FTP it already has).

**L. Reporting, exports, copilot, workflow** — Ad-hoc reporting with a whitelist boundary +
byte-equality against fixed MIS is well done. Exports are idempotent and typed; `DELIVERED`
never happens (no transport — fine, but the status is dead). Copilot scoping/refusal/citations
are exemplary *as a contract*; composition is templates (declared honestly). Workflow-service:
materialised on create, then **nothing reports into it** — either wire `record()` calls at each
domain transition or reposition the tracker as manual-ops; today the SLA sweep measures a clock
no process feeds. Portfolio-360's `rm` filter is a declared no-op.

---

## 7. India (RBI) lens — since IN-RBI is the primary seeded regime

**Already right:** IRAC classification track with provisioning-of-record `max(ecl,irac)`;
CERSAI / ROC CHG-1 (30 days) / state stamp duty / CIBIL-willful-defaulter CPs on TERM_LOAN:IN-RBI;
signed drawing-power CP + STOCK_STATEMENT MER type on working capital; EBLR/MCLR benchmarks with
floating-rate resets; LEF 15%/25% norms; INR system base; crore/lakh parsing.

**Missing for an Indian bank pilot (ordered by examiner-pain):**
1. **SMA-0/1/2 sub-classification** (1–30/31–60/61–90 DPD) + a CRILC-shaped export feed — the
   platform has `REGULATORY` in its `DownstreamSystem` enum, unused.
2. **Drawing-power engine**: stock/book-debt statement ingestion → margin application → DP →
   utilisation cap = min(sanctioned, DP) on CC/OD lines, with DP-stale freeze. The MER item and CP
   exist; the computation and the limit-service hook don't.
3. **Doubtful age-bands + secured/unsecured provisioning split** (15% substandard [25% unsecured],
   D1/D2/D3 25/40/100 on secured portion + 100% unsecured; the platform already computes
   secured/unsecured portions in the capital engine — reuse that split).
4. **Restructuring framework consequences**: downgrade on restructure, upgrade-after-performance
   rules, resolution-plan timelines (June 7 2019 framework shape).
5. Consortium/multiple-banking declarations & information exchange; end-use certification
   tracking; RFA/red-flag EWS indicator set; legal-audit item type.

---

## 8. UI and test coverage gaps

**API surfaces with no UI:** the entire prospect/initiation lifecycle (dedup, negative check,
checks façade, decision, approve, group create); CAP (whole module); country/department limits +
FI transaction maker-checker; covenant-tracking approvals + freeze; certificate-assessment
confirm/reject; EWS disposition; PF milestone/reserve definition + gate view; MER manual raise;
Portfolio-360 endpoint; pricing-exception cross-deal inbox; collections/NPA (no api.ts client at
all); rule-pack sign-off (view-only page); masters bulk upsert.

**E2E untested:** spread cell-override gate (UI-wired, never asserted); collateral perfect;
doc-gen withdraw; committee note; EWS disposition; covenant freeze-accounts; override-stats;
workflow `record`. **No frontend test automation exists at all** (no vitest/Playwright) — every
assertion is gateway-level; the governance *chrome* (nav gating, actor flow) is unverified.

**Doc drift:** CLAUDE.md claims SoD on doc-gen confirm and commentary review (not in code);
FEATURE-COVERAGE.md marks covenant-cert GenAI extraction "○ roadmap" (it's built);
run_regression's description of `e2e_monitoring` describes a different suite.

---

## 9. Priorities

**P0 — correctness & authority integrity (days, not weeks):**
1. Fix D1–D4 (reversal accounting + reservation release + recon alignment; mixed-currency root;
   covenant frequency canonicalisation — one enum, both sides).
2. Resolve approver roles server-side (ActorDirectory) on decide / rating override / rating
   confirm / write-off / pricing-exception decisions; enforce exception tiers; require a named
   role on rating confirm.
3. Add maker≠checker where the thesis promises it: rating confirm vs overrider, doc-gen
   confirm vs generator, commentary review vs drafter, covenant-intel/cert confirm vs extractor
   requester, FI decide vs submitter (persist submitter), pack policy-signer ≠ model-risk-signer.
4. Make the two audit-event integrations real: CAD limit-release → limit-service activation call;
   covenant freeze → `POST /limits/{id}/freeze`.
5. Consume the dead config (§5 table) — each with an e2e assert that editing the master moves
   the behaviour.

**P1 — functional gaps that block a credible bank pilot:**
6. Enforce interchangeability + intermediate-parent headroom in utilisation (D5) and country-limit
   checking (D8); govern the override flag (role + review queue).
7. Wire conditions → CP/CS register on CONDITIONAL_APPROVE; generate a sanction-letter document
   on approval; add a committee/quorum decision mode.
8. Rule-pack authoring API with maker-checker + distinct dual signers + `effectiveFrom`
   evaluation; UI for pack lifecycle (page is view-only).
9. India pack: SMA buckets + CRILC-shaped feed; drawing-power engine; doubtful age-bands with
   secured/unsecured split; restructure classification consequences.
10. Connect MODEL_DEFINITION composite to the model grade (signed-off models as the rating
    model of record; scorecard as fallback); snapshot origination grade (enables SICR-by-notch
    and transition matrices).
11. Build the missing operational UIs (initiation lifecycle, covenant approvals, EWS disposition,
    CAP, FI/country limits, cert-assessment review) — the back ends are done and tested.
12. Real notification transport behind EMAIL_TEMPLATE (SMTP/webhook), with the audit row kept.

**P2 — depth:**
13. ECL: tenor-linked lifetime PD, amortising EAD/CCF, multi-scenario weights; fee income into
    pricing-of-record; collateral-type-appropriate RW on covered portions; stress scenarios into
    a pack; RAROC actuals from the PRICING pack/FTP; peer analysis; rating transition matrix and
    validity/expiry sweep; workflow `record()` wiring from domain services; frontend test
    automation; terminal-state sweeps (re-KYC due, node closure, doc ISSUED, batch DELIVERED).

---

*Prepared as a code-evidenced functional review; every finding above was located to file:line
during review and the highest-impact items (D1, G1, G2-doc-gen, §5 EWS/covenant-library,
D4, capital sub-IG nuance) were re-verified by direct inspection before inclusion.*
