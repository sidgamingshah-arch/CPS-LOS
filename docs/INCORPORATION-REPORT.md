# Helix CPS-LOS — Incorporation Report (Functional + AI)

> Generated from the codebase + `docs/FEATURE-COVERAGE.md`, refreshed with every
> capability added this session. **Legend:** ✅ built & tested · ◑ partial (core
> built; depth is config/impl) · ○ roadmap (seam exists, not built).
>
> **Important honesty note:** the user referenced *two specific requirement lists
> (one functional, one AI)*. Those lists are **not present in the repo** and are
> not in the assistant's context, so this report is built from the platform's own
> `FEATURE-COVERAGE.md` backbone + the actual code. It is a **capability
> incorporation inventory**, not a line-by-line mapping against the user's exact
> lists. Paste the two lists and they can be mapped item-by-item to ✅/◑/○.
>
> Test surface: **22 e2e suites, 955 assertions, 0 failures** through the gateway
> on a clean DB (`scripts/run_regression.py`).

---

## A. Direct answers to the two questions asked

### A1. Are all front-end dropdowns backed by configurable code-value masters? — **NO (gap)**
- ~27 dropdowns are **hardcoded TypeScript enums/arrays** in `frontend/src/pages/*`:
  grade scale (AAA–D), override reasons + roles, facility types, collateral types,
  segments, structure/participant/liability types, decision outcomes, document
  types, translation languages, sector outlooks, the create-form jurisdiction list,
  the Masters type-picker, the RulePacks type list.
- Master-driven today: jurisdictions (`config.jurisdictions()`), AI capabilities
  (`governance.capabilities()`), and **model-question options** via
  `optionsFromMaster` (e.g. `ESG_BAND`).
- **No generic `CODE_VALUE` / `LOOKUP` master exists.** The generic `MasterRecord`
  engine could back one trivially; it just isn't wired to the form dropdowns.
- **Closeable**: a `CODE_VALUE` master (recordKey = `<DOMAIN>`, payload = ordered
  options) + a `/api/codes/{domain}` read + a `useCodes(domain)` hook would make
  every dropdown admin-editable under maker-checker. ~1 day. (Status: ○)

### A2. Is there a dedupe engine? — **YES (✅)**
- `counterparty-service` `InitiationService.dedupCheck()`: **Jaccard name
  similarity** (stop-word-stripped tokens) **+ exact identifier match**, driven by
  the `DEDUP_RULES` master (`NAME_AND_IDENTIFIER`, threshold `0.82`, `OR`).
- `GET /api/initiation/prospects/{id}/dedup` → ranked candidates (RM, classification,
  KYC, lifecycle, matchType, score). **Advisory**; **NEGATIVE_LIST** is the hard
  block (409 on obligor approval). e2e: `e2e_smoke.py` §23.

---

## B. Functional capability incorporation (by lifecycle stage)

### B0. Cross-cutting generic engines
| Capability | Status | Where |
|---|---|---|
| Generic Master-Data engine (any `masterType`, JSON payload, maker-checker + SoD, versioning, bulk, pending queue, history) — **35+ master types** | ✅ | config-service `MasterRecord` + `/api/masters/{type}`; React Master Data page |
| Append-only audit trail (HUMAN/AI/SYSTEM actor type) in every service | ✅ | `helix-common` `AuditEvent`; `/api/audit` |
| RBAC (`ACTOR_ROLE` master) + segregation-of-duties across all approvals | ✅ | `ProtectedAction` + `ActorDirectory` |
| AI governance off-switch (per-capability, per-jurisdiction) | ✅ | `AI_GOVERNANCE` master + `AiGovernanceClient` |
| Canonical connector ingestion + symmetric downstream export contracts | ✅ / ◑ | `helix-common.ingest` / `helix-common.export` |
| **Workflow runtime engine** (consumes `WORKFLOW_DEFINITION`; stage state, humanGate/autonomy guard, SLA sweep) | ✅ **(this session)** | new `workflow-service` :8089 |
| **Generic value/code master backing all UI dropdowns** | ○ | gap (see A1) |

### B1. Credit Initiation
| Capability | Status |
|---|---|
| Prospect capture, borrower type NTB/ETB/DUAL | ✅ |
| **Dedup engine** (name+identifier, master-driven) | ✅ |
| Negative check / sanctions (blocking) | ✅ |
| External-check façade (screening/bureau/KYC-AML/external-rating) | ✅ |
| RM ownership resolution + accept gate + audit | ✅ |
| Group fetch / exposure / risk flags / multi-country | ◑ |
| Obligor creation summary + RM decision + approve | ✅ |
| Draft cleanup / inactivity thresholds | ✅ |
| DMS / news-feed auto-populate | ○ |

### B2. Financial Spreading
| Capability | Status |
|---|---|
| Canonical multi-period spread, cell provenance, override-with-reason gate, ratios, benchmark flags | ✅ |
| **Configurable financial templates** (sector/segment chart-of-accounts augmentation: extra input/derived lines + formula ratios) | ✅ **(this session)** |
| **Two-level currency** (system-currency base + financial-analysis presentation currency, dated period-end FX) | ✅ **(this session)** |
| GenAI extraction (multi-language, suggest→confirm) | ✅ |
| **Financial projections** (multi-year proforma, drivers, sensitivity, projected DSCR — advisory) | ✅ **(this session)** |
| Peer analysis (≤5), rolling/TTM, multi-company consolidation | ◑ / ○ |

### B3. Credit Risk Rating
| Capability | Status |
|---|---|
| Deterministic scorecard PD/LGD/EAD + per-factor contributions | ✅ |
| Notch-limited override (per-role), reason codes, SoD | ✅ |
| Proposed vs confirmed gate + history | ✅ |
| **Model configuration engine** (sector/segment models; qualitative + quantitative **sections**; dropdown/input/number/iterative questions; visibility/conditional rules; min/max + mandatory; master-driven options; weighted composite → band) | ✅ **(this session)** |
| **Parameter sourcing** (module-sourced from CPS data vs standalone model-scored, with rationale) | ✅ **(this session)** |
| Rating reports (transition matrix, trail, comparison) | ○ |

### B4. Credit Proposal & Decisioning
| Capability | Status |
|---|---|
| Automated CP generation (grounded, cited, versioned) | ✅ (DOCX/PDF render ◑) |
| Facility + sublimits + interchangeability + collateral/charge | ✅ |
| Covenant capture (master-linked + custom) + pre-sanction testing | ✅ |
| DoA routing (amount × rating × deviation) + named-human decision | ✅ |
| Specialised structures (group/joint/dual-obligor/syndication/FI-ICR/renewal-copy) | ✅ |
| AI narrative commentary (grounded, advisory, human-confirm) | ✅ |

### B5–B10 (Covenant monitoring · RAROC/Pricing · CAD/Docs/MER · Collateral · Limits · Portfolio/EWS/360)
| Area | Status |
|---|---|
| Covenant tracking workflow + alerts + freeze triggers | ✅ |
| RAROC projected + actual + variance; goal-seek optimiser; concession-approval sub-workflow | ✅ |
| CAD checklist + 2-level waiver/deviation + limit-release; MER register + sweep + DMS feed | ✅ |
| Document generation (clause surgery + confirm-lock) | ✅ |
| Collateral first-class + hierarchy master + agencies; collateral-intel extraction | ✅ (independent collateral journey ○) |
| Limit tree + View/Validation/Utilisation APIs + freeze + country/dept limits + FI tx + EOD batch | ✅ |
| ECL/IRAC, EWS, concentration, stress, Customer-360, Portfolio-360, CAP, MIS | ✅ |
| **Ad-hoc reporting** (whitelisted-dataset query builder + saved REPORT_DEFINITION + maker-checker) | ✅ **(this session)** |
| Downstream ERM / Finance-GL / CPR export feeds | ✅ |

---

## C. AI / ML capability incorporation

All AI is **advisory, human-gated, governed by the `AI_GOVERNANCE` off-switch, and
provably never alters a credit-consequential figure** (asserted by e2e). The
catalogue is a compile-time enum (`AiCapability`):

| AI capability | Status | Notes |
|---|---|---|
| `DOC_INTEL` — document classification + structured extraction (multi-language) | ✅ | suggest → human-confirm; never auto-applied to figures |
| `COLLATERAL_INTEL` — type-aware collateral extraction | ✅ | |
| `RAG_OVERLAY` — statistical Red/Amber/Green over the deterministic rating | ✅ | transparent per-factor contributions; rating unchanged |
| `MACRO_IMPACT` — directional PD/notch projection from macro shifts | ✅ | advisory |
| `PRICING_OPTIMISER` — goal-seek pricing scenarios | ✅ | authoritative pricing unchanged |
| `PRICING_EXCEPTION` — concession-approval sub-workflow | ✅ | maker-checker SoD |
| `COMMENTARY` — grounded narrative section drafters | ✅ | human-confirm |
| `COVENANT_INTEL` — covenant extraction + compliance assessment | ◑ / ○ | |
| `CPT` — client planning template (wallet sizing + nudges) | ✅ | |
| `GROUP_SUGGEST` — group identification advisory match | ✅ | |
| `COPILOT` — read-only conversational copilot | ✅ | |
| `MODEL_SCORING` — model-engine AI pre-fill + standalone-parameter scoring | ✅ **(this session)** | governed off-switch; advisory |
| Rating questionnaire AI auto-fill (ETB) / explainability narrative | ◑ | model engine covers questionnaire + rationale; LLM auto-fill is a deterministic stand-in |
| Signature verification / climate-risk / news-sentiment models | ○ | |

**Governance posture (the product thesis):** every AI output persists a separate
advisory entity, stamps `audit.ai(...)`, has a human-confirm gate stamping
`audit.human(...)`, and the e2e asserts the authoritative grade/pricing/PD is
byte-identical before/after. The deterministic figure path (rating, capital, ECL,
RAROC, FTP) contains **no AI**.

---

## D. Notable gaps (honest)

| Gap | Effort | Note |
|---|---|---|
| Generic `CODE_VALUE` master behind all UI dropdowns | ~1 day | A1 — biggest "configurability" gap |
| Full SpreadJS-style formula authoring UI for financial templates | med | engine + JSON authoring exist; visual builder is UX |
| Visual builder for model / projection definitions (today: JSON) | med | engines complete |
| Rating/regulatory report renderers (transition matrix, etc.) | med | data present |
| Sector-keyed projection templates auto-applied (only a default seeded) | low | engine + sector wiring done; just seed variants |
| Covenant-intel GenAI extraction, DMS/news/climate feeds | med | seams exist |

---

## E. To get an exact mapping against YOUR two lists

Paste the **functional requirements list** and the **AI requirements list** (even
as raw bullet dumps). They will be mapped item-by-item to ✅/◑/○ with the specific
file/endpoint that implements each, and any not-incorporated items called out
explicitly with the build estimate — no item silently marked done.
