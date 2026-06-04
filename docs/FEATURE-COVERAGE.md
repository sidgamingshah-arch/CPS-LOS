# Helix — Feature Coverage Matrix

This maps the requested wholesale-lending feature set onto Helix's **generic**
platform capabilities. Many requested items are entity- or bank-specific
restatements of the same primitive; they are deliberately implemented once,
generically, and configured via masters / rule packs / workflow definitions.

**Legend** — ✅ built & tested · ◑ partial (core built, depth is config/impl) · ○ roadmap (seam exists)

The platform is exercised by two end-to-end suites: `scripts/e2e_smoke.py`
(**105 assertions**, single-deal + every module below) and
`scripts/e2e_100_obligors.py` (distributed 100-obligor book).

---

## 0. Cross-cutting generic engines (the "convert to generic" core)

| Requested (examples) | Generic capability | Status |
|---|---|---|
| Deduplication Logic Master, Inactivity Threshold Master, Negative List Master, Covenant Library, Facility Master, Collateral Master, Valuation/Charge/Rating-Agency masters, RAROC masters (PD term structure, CCF, opex, liquidity premium, FTP, benchmarks), EWS Trigger maintenance, Industry Benchmark config, Checklist/Document-Template/TnC masters | **One generic Master-Data engine** (`config-service` `MasterRecord` + `/api/masters/{type}`) — any `masterType`, JSON payload, **maker-checker with SoD**, versioning, bulk upsert, pending queue, history. 30+ masters seeded. | ✅ |
| "…front end add/edit/delete… 1-level maker/checker… bulk upload" (repeated ~15×) | Same engine: `POST /masters/{type}`, `…/bulk`, `records/{id}/approve|reject` (checker ≠ maker enforced) | ✅ |
| Configurable Approval Workflows, Standard/Default workflows (CP, rating, spreading, RAROC, CAD), segment/borrower-type-specific | **`WORKFLOW_DEFINITION` rule pack** — ordered stages with autonomy level, AI flag, human-gate, SLA per (jurisdiction × segment). Mid-corp + SME(STP) + CBUAE seeded. | ◑ (definitions + DoA routing built; full multi-actor state machine is impl) |
| Email Notifications through master (ownership claim/change, obligor approved, covenant due, CAD alerts) | **`EMAIL_TEMPLATE` master** + template-driven notification events emitted to the immutable audit trail (no SMTP binding in this build) | ◑ |
| Audit Trail & History Tracking (every module) | **Append-only `AuditEvent`** in every service; HUMAN/AI/SYSTEM actor type; `/api/audit` + `/audit/subject` | ✅ |
| External Data Connectivity / source-system integration, logged for compliance | **Canonical `Connector` ingestion** (`helix-common.ingest`) + **source-system check façade** (below); all interactions audited | ✅ / ◑ |
| Ready API / Standard APIs to push downstream (rating, CP, limits, RAROC) | REST APIs per domain via the gateway; downstream push modelled as audited events | ◑ |

---

## 1. Credit Initiation (Stage 1) — `counterparty-service`

| Requested | Generic capability | Status |
|---|---|---|
| Dynamic Obligor Data Capture, Basic Details (industry/sub-industry/segment/sub-segment), Identifier capture | `POST /api/initiation/prospects` — prospect with full meta; borrower type NTB/ETB/DUAL | ✅ |
| Deduplication Check + Logic Master + Comparison View | `GET /prospects/{id}/dedup` — name (Jaccard) + identifier match driven by `DEDUP_RULES` master; returns matches with **RM, classification, KYC status, last-updated, match type, score** | ✅ |
| Negative Check + Negative List Master (entities + sanctioned countries) | `GET /prospects/{id}/negative-check` against `NEGATIVE_LIST` master; **blocks obligor creation** on hit | ✅ |
| Screening (internal/external), Credit Bureau, KYC/AML, External Rating — "performed in source systems; fetch status; request refresh" | **One generic `ExternalCheck` façade** — `checks/fetch`, `checks/{id}/refresh`, unified view; covers all 5 integrations for any entity type (obligor/co-obligor/parent/guarantor/third party) | ✅ |
| Unified Screening Result View (all entities, RM/classification/last-updated) | `GET /prospects/{id}/checks` | ✅ |
| Configurable RM management + Automated Ownership Resolution + override + audit | Default RM = creator; `ownership/request` + `ownership/{id}/decision` (receiving-RM acceptance gate); reassignment audited | ✅ |
| Automatic Group Fetching, Group Exposure Summary, Group Risk Flags, Multi-country group creation, Real-time Group Syncing | `groups` create/tag + `groups/{id}/exposure` (members, obligor count, risk flags, multi-country flag) | ◑ (sync to CRM modelled as event) |
| Obligor Creation Summary (screening/dedup/group/peer/industry insights) | `GET /prospects/{id}/summary` — aggregates dedup + negative + external checks + group exposure + industry insight + blockers | ✅ |
| RM Decision Point (proceed/drop with reason) | `POST /prospects/{id}/decision` | ✅ |
| Obligor Creation (prospect → obligor), data validations, CRM/core ID mapping | `POST /prospects/{id}/approve` → `recordType=OBLIGOR`, `externalId` mapping, notification | ✅ |
| Automated data cleanup (drafts > configurable months), Inactivity Threshold | `POST /auto-cleanup` driven by `DRAFT_CLEANUP` + `INACTIVITY_THRESHOLD` masters | ✅ |
| Relending to closed obligors, Segment reclassification | Lifecycle status (DRAFT/ACTIVE/DROPPED/DISCARDED/CLOSED) supports reactivation; segment field reclassifiable | ◑ |
| External Rating management (multiple issuer ratings, fetch from providers) | `EXTERNAL_RATING` check + `EXTERNAL_RATING_AGENCY` master | ◑ |
| Auto-populate from internal/external systems, DMS integration, news feed | Connector + check façade seams; DMS/news are integration points | ○ |

---

## 2. Financial Spreading (Stage 4) — `origination-service`

| Requested | Status |
|---|---|
| Canonical spreading, multi-period, provenance to source doc/page/coords, analyst override with material-change gate, ratios, benchmark flags, downstream consumption | ✅ |
| Template selection (auto/override), template maintenance master, financial rule validations | ◑ (`FINANCIAL_TEMPLATE`/rules via master engine; SpreadJS UI is impl) |
| GenAI extraction (PDF/scanned/Arabic/Chinese), side-by-side view, multilingual | ○ (document-intelligence seam; `DocumentClassifier` stub today) |
| Projections (borrower / analyst / 3-scenario best-worst-likely), peer analysis (≤5), benchmarking, rolling/TTM, multi-company consolidation, multicurrency, version control | ◑ (trends + benchmark flags built; scenario/rolling/peer engines roadmap) |
| Auto financial analysis (cash flow, ratios), downloadable, currency rate, annualization | ◑ |

---

## 3. Credit Risk Rating (Stage 5) — `risk-service`

| Requested | Status |
|---|---|
| Scorecard PD/LGD/EAD, per-factor contributions, final score & score sheet | ✅ |
| Notch-limited override (per-role max notch), reason codes, override-rate model-fit signal | ✅ |
| Proposed vs accepted rating, confirmation gate, history | ✅ (proposed/confirmed; query-based discrepancy is impl) |
| Model repository / components / model-level PD & scale / standardized scale / external-agency scale | ◑ (`RATING_PD_MAP`, `LGD_MAP`, `EXTERNAL_RATING_AGENCY` masters; front-end model builder roadmap) |
| Guided dynamic questionnaire, AI auto-fill (ETB), AI rationale & explainability, risk-entity manual reference | ○ |
| Modifiers (parent/government/guarantee support), credit-policy notch up/down, validity period, auto review trigger on expiry, exception handling | ◑ |
| Reports: Company Rating, Rating Trail, Transition Matrix, Comparison, Benchmarks | ○ (data present; report renderers roadmap) |

---

## 4. Credit Proposal (Stage 8) — `decision-service`

| Requested | Status |
|---|---|
| Automated CP generation (grounded, cited, versioned, PDF/DOCX, partial), ETB copy-forward base, role-based section access | ✅ (markdown+HTML, citations, versioning; DOCX/PDF render is impl) |
| CP configuration master (screens/sections by type × counterparty, mandatory/editable, pre-populate toggle) | ◑ (master engine; screen-config depth is impl) |
| Facility & sub-limit capture, interchangeability, security capture & mapping, charge config | ✅ (multi-facility, **sublimits + interchangeability groups**, collateral, perfection) |
| Covenant capture (master-linked + case-level modify + custom), covenant testing pre-sanction with auto-flag & exception workflow | ✅ (`COVENANT_LIBRARY` master, covenant entities, `covenants/test` history) |
| DoA routing on amount × rating × deviations; named-human decision; committee note | ✅ |
| Group CP, multi-party/joint-obligor, joint-utiliser, dual-obligor (Islamic), ICR for FI, renewal/amendment CP, copy proposal | ○ (CP type config seam; specialised CP variants roadmap) |
| Business profile, management assessment, news feed, risks & mitigation, ways-out, relationship strategy, peer/industry benchmarking sections | ◑ (sections generated from data; narrative depth roadmap) |
| Credit decisioning template + decision support (historical rationale) | ◑ |

---

## 5. Covenant Monitoring — `decision-service` `/api/covenants/tracking`

| Requested | Status |
|---|---|
| Covenant library master (Affirmative/Negative/Financial/Non-financial), by industry/segment/facility, bulk upload, maker-checker | ✅ (`COVENANT_LIBRARY` via master engine) |
| Covenant schedule (frequency, period, thresholds, grace), tracking workflow (Compliant/Breached/Waived/Overdue/Extended), RM/credit actions, request-and-approve with SoD | ✅ (`init`, `run-due`, schedule state machine; `request/{extension,waiver}` + `actions/{id}/decision`; raiser≠approver) |
| Auto status from financial spreading | ✅ (driven by latest spread ratios from origination) |
| Alerts to RMs (configurable horizon) | ✅ (`alerts/send?days=`) |
| Freeze accounts / freeze disbursement triggers to limit mgmt | ✅ (`LIMIT_FREEZE_TRIGGER` audit event) |
| GenAI extraction from compliance certs / CP free-text | ○ |

---

## 6. RAROC / Pricing — `risk-service` + `portfolio-service`

| Requested | Status |
|---|---|
| RAROC masters (PD term structure, CCF, opex, liquidity premium, FTP, benchmarks) — front-end + bulk + maker-checker | ✅ (seeded via master engine) |
| Projected RAROC (RAROC = (Rev − Cost − EL + Income on capital) / Economic capital); EL = PD·LGD·EAD | ✅ |
| Initiate independently or from CP; capture facility/collateral/limit/guarantor/group/rating/repayment/price | ◑ (computed from deal; standalone RAROC workflow roadmap) |
| Roll-ups (facility → transaction → CIF → group), output detailed/summary, projected vs existing | ◑ |
| Periodic actual RAROC from source data, variance analysis, re-run on source change, approval workflow | ✅ (projected-vs-actual tracking + variance + material-miss governance; source-fed actuals via connector) |
| Price recommendation engine (ML), scenario optimiser (goal-seek), pricing-approval sub-workflow, downstream ERM/Finance/CPR interface | ○ |

---

## 7. CAD / Documentation — `decision-service` `/api/cad`

| Requested | Status |
|---|---|
| Checklist master, Document-template master, TnC master (front-end, maker-checker) | ✅ (`CHECKLIST_MASTER`, `DOC_TEMPLATE_MASTER`, `TNC_MASTER`) |
| CAD inbox post-CP, checklist suggestion from master config, per-item status update (complied/non-complied/waived) | ✅ (`POST /cad/cases`, `GET /cad/cases`, `POST /cad/items/{id}`) |
| Waiver/Deviation workflow (sequential 2-level, maker-checker with SoD) | ✅ (`items/{id}/deviation`, `deviations/{id}/decision`; raiser≠approver, L1≠L2) |
| Completion gate + limit-release checklist + feed to limit management | ✅ (`cases/{id}/complete`, `cases/{id}/limit-release` → LIMIT_RELEASE_TRIGGER) |
| MER tracking workflow (deferred docs / conditions subsequent / recurring renewals — insurance · valuation · annual review), reminders + escalation sweep, maker-checker clearance, DMS feed | ✅ (`/api/mer`: `generate/from-cad`, `submit`→DMS_FEED, `verify` [verifier≠submitter], `waive` [≠owner], `sweep`→OVERDUE/ESCALATED, `reminders/send`, recurring roll-forward; React **Monitoring · MER**) |
| Pre-populated doc/TnC generation, DMS versioning, email templates per stage | ◑ / ○ (EMAIL_TEMPLATE master + audit events; doc-gen is a further build) |
| GenAI (template selection, casual→legal language, clause add/remove, translation, signature verification, doc checks) | ○ |

---

## 8. Collateral Management — `origination-service` (core) + roadmap

| Requested | Status |
|---|---|
| Collateral first-class (type, value, haircut, perfection status/date, owner), facility–collateral linking, charge %/type, effective coverage | ✅ |
| Collateral hierarchy master (Group/Sub-group/Type/Sub-type), charge-nature, charge/valuation agencies | ✅ (`COLLATERAL_MASTER` hierarchy + agency masters) |
| Independent collateral journey, multi-borrower allocation, GenAI/email auto-capture, climate risk, marketable-securities valuation feeds, pre/post-disbursement monitoring, cash-margin for derivatives, 360 reports & triggers | ○ |

---

## 9. Limit Management — `limit-service` (:8088)

| Requested | Status |
|---|---|
| Facility + multi-level sub-limits (up to 5 levels, 50/parent), interchangeability/fungibility (combined cap, move within), hard caps, cap-overflow validation | ✅ |
| Build limit tree from approved deal (obligor → facilities → sub-limits) | ✅ (`POST /limits/build/{ref}`) |
| **View API** (CIF / line / product → tree or node, base-currency roll-up) | ✅ (`GET /limits/view`) |
| **Validation API** (status · expiry · tenor · line & obligor available · single-name exposure) | ✅ (`POST /limits/validate`) |
| **Utilisation API** (UTILISE/RELEASE/RESERVE/REVERSAL, multi-action, **override force**, ancestor roll-up) | ✅ (`POST /limits/utilise`) |
| Freeze/unfreeze, expiry extension; currency conversion to base for cross-currency roll-up | ✅ |
| Exposure norms (single-name, sector/geography) from config rule pack; single-name enforced per transaction, sector/geo at portfolio/sanction review | ✅ |
| Facility hierarchy master, fungibility defined at each level | ✅ (`FACILITY_MASTER` + per-node `fungible`) |
| Country & department limits with non-fungible departments rolling up to country cap; cash-margin captured per FI tx | ✅ (`/limits/country`, `/limits/department`, `/limits/country/{country}` view) |
| FI standalone transaction workflow (submit → approve/exception-approved → utilises obligor line; rejection captured) | ✅ (`/limits/fi/transactions`, `/limits/fi/transactions/{id}/decision`, pending inbox) |
| ICR country review (multi-FI tagged, simultaneous renewal), EOD batch utilisation/reconciliation, currency revaluation EOD | ◑ / ○ |

---

## 10. Portfolio Monitoring / EWS / Customer-360 — `portfolio-service`

| Requested | Status |
|---|---|
| Ingest from CP/limit/spreading/rating/core/covenant/CAD/bureau; borrower & relationship mapping | ◑ (cross-service reads + connector ingestion; some feeds roadmap) |
| Trigger maintenance master (enable/disable, threshold, criticality), EWS signals, close triggers, corrective-action planning | ◑ (`EWS_TRIGGER` master + EWS signal flagging; CAP module roadmap) |
| Customer-360 dashboard (borrower profile · limits & utilisation · triggers/breaches · financials · ratios · covenants · RAROC · provisioning · industry outlook) | ✅ (`GET /mis/customer360/{ref}` + React page) |
| Portfolio-360 dashboard (exposure count · total EAD/RWA · by internal rating · by segment · by jurisdiction · by status · by vintage year · open signals) | ✅ (`GET /mis/portfolio360`) |
| MIS reports (composition, RAROC variance, pipeline ageing, ECL by stage, watchlist) | ✅ |
| Corrective Action Plan (CAP) — raise · respond · close (SoD) · escalate · auto-overdue sweep | ✅ (`/api/cap/actions`, `/api/cap/sweep`) |
| RAG/ML borrower scoring, statistical thresholds, macro directional impact, AI commentary | ○ |
| ECL/IRAC provisioning, concentration vs limits, stress testing | ✅ |

---

## Honest scope statement

The **architecture, generic engines, and the credit-decision spine are built and
tested** (105 assertions). The items marked ○ are genuine module build-outs
(full CAD documentation, full limit-tree + transaction APIs, GenAI document
intelligence, ML/statistical scoring, SpreadJS UI, specialised CP variants). They
are intentionally **not stubbed as if complete** — each sits on an existing seam
(master engine, workflow definitions, connector ingestion, audit) and is additive
without core change. This is a reference platform demonstrating the patterns, not
a production deployment of every bank-specific feature.
