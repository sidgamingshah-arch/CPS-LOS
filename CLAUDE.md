# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Helix is **governed AI for wholesale credit** — an AI-first wholesale loan origination & lifecycle
platform. Java 21 / Spring Boot microservices (9 of them), SQLite-per-service, React + Vite + TS
frontend behind a Spring Cloud Gateway. The product thesis is the spine of every design decision:

> **AI where it helps. Humans where regulation demands. Deterministic figures throughout.**

That triad shows up in the chrome itself (governance chips top-right on every screen, the promise
strip beneath the topbar, the AI-ADVISORY ↔ AUTHORITATIVE-UNCHANGED split on Risk Lab, the
PRICING-OF-RECORD-PRESERVED tag on Pricing Lab, AI→Human flow badges on the suggest-confirm
screens). See `README.md`, `docs/ARCHITECTURE.md`, `docs/FEATURE-COVERAGE.md`,
`docs/INTEGRATIONS.md`. Pitch artefacts live in `docs/` (see "Pitch & demo assets" below).

## Build · run · test

```bash
mvn -DskipTests package                 # build all jars (~10 modules under one reactor)
bash scripts/run-all.sh                 # start every service; health-gated on 8080-8088
python3 scripts/e2e_smoke.py            # full lifecycle, 239 assertions through the gateway
python3 scripts/e2e_100_obligors.py     # 100-obligor distributed book stress test (~60s)
bash scripts/stop-all.sh                # stop
cd frontend && npm install && npm run dev   # UI on :5173, talks to gateway :8080
docker compose up --build               # full stack (UI :5173, gateway :8080)
```

Build a single service: `mvn -q -pl <service> -am package -DskipTests` (e.g. `-pl limit-service`).
Run one in isolation: `SERVER_PORT=8088 java -jar limit-service/target/limit-service.jar`.

E2E tests are a single Python driver — there's no JUnit test runner today. To narrow it,
edit `scripts/e2e_smoke.py` and comment out the sections you don't need (each block is
prefixed with `print("== N. ..."`); the script bails out on the first hard error from `call()`,
so individual asserts are reached by removing earlier blocks. **The e2e is the safety
contract** — it asserts the governance invariants directly (advisory overlays never
move the authoritative rating / pricing; confirmed AI suggestions lock; SoD blocks on
maker-checker; etc.). When you add a new module, add a section to keep this contract.

## Services and routes (gateway :8080 strips the first segment)

| Service | Port | Gateway prefix | Owns |
|---|---|---|---|
| config-service | 8081 | `/config` | Jurisdiction profiles + versioned rule packs; **generic Master-Data engine** (`/api/masters/{type}`) with maker-checker + bulk |
| counterparty-service | 8082 | `/counterparty` | KYC/CDD/UBO/screening + **credit-initiation lifecycle** `/api/initiation` (prospect→obligor, dedup, negative check, source-system check façade, RM ownership, groups) |
| origination-service | 8083 | `/origination` | Applications, document classification, financial spreading with provenance, **proposed facilities + sublimits + interchangeability**, collaterals, **specialised deal structures** (`/api/applications/{ref}/structure` — group / joint / dual-obligor / syndication / FI ICR / copy-from), **GenAI document intelligence** (`/api/doc-intel` — extract / confirm / normalise / translate / checks) |
| risk-service | 8084 | `/risk` | Scorecard rating (PD/LGD/EAD, notch-limited overrides), capital projection for RAROC, RAROC pricing, **advisory overlays** (`/api/risk/{ref}/rag`, `/macro-impact`), **pricing optimiser + concession-approval sub-workflow** (`/pricing/optimise`, `/pricing/exception`). All AI / overlay paths are advisory; the authoritative `PricingResult` is never mutated |
| decision-service | 8085 | `/decision` | DoA approval, covenants + **tracking workflow** (`/api/covenants/tracking`), credit proposal, **CAD** `/api/cad` (checklist from master, 2-level waiver/deviation, limit-release trigger), **MER** `/api/mer` (deferred docs, renewals, reminders, escalation sweep, DMS feed), **document generation** `/api/docs` (DOC_TEMPLATE / TNC clauses + clause surgery + human-confirm lock), **AI narrative commentary** `/api/commentary` (grounded section drafters, advisory) |
| portfolio-service | 8086 | `/portfolio` | ECL/IRAC, EWS, concentration, stress, **RAROC actual/projected tracking**, MIS incl. Customer-360 + Portfolio-360, **CAP** `/api/cap`, **downstream canonical export feeds** `/api/exports` (ERM / Finance-GL / CPR — idempotent ExportBatch per as-of day) |
| copilot-service | 8087 | `/copilot` | Persona-scoped, grounded, non-binding conversational copilot (read-only fan-out) |
| limit-service | 8088 | `/limits` | Multi-level limit tree (built from deal facilities/sublimits), fungibility roll-up, exposure norms, product-processor View/Validation/Utilisation APIs (UTILISE/RELEASE/RESERVE/REVERSAL, override, freeze), country + department limits, FI transaction workflow, **EOD batch** `/api/limits/eod` (FX refresh + sanctioned-amount revaluation + utilisation reconciliation, idempotent run history) |
| gateway-service | 8080 | — | Spring Cloud Gateway; strips first path segment |

`helix-common/` is a shared library (not a service): canonical `Enums`, append-only `AuditEvent`,
JSON attribute converters, web cross-cutting, **canonical connector ingestion** contracts in
`com.helix.common.ingest` (Envelope / Provenance / Connector / IngestionGuard), and the
**symmetric outbound export contract** in `com.helix.common.export` (DownstreamSystem enum +
typed `Export.Envelope` / `ErmRiskRecord` / `FinanceProvisionEntry` / `CprPortfolioLine`).
Every service that includes helix-common automatically exposes `/api/audit` and `/api/audit/subject`.

## Architecture in one paragraph

A new regime is **overlay data, never a code branch**. `config-service` owns two things —
**versioned, dual-signed rule packs** (capital, ECRA, PD/LGD, provisioning, DoA, limits,
pricing, workflow definitions) and the **generic Master-Data engine** behind every "X Master"
in the platform (dedup rules, negative list, covenant library, facility/collateral masters,
RAROC masters, EWS triggers, email templates, checklists, doc templates, T&C, …) with
maker-checker + bulk upsert + SoD. All downstream engines fetch packs/masters at runtime; the
rating, capital, ECL, and pricing paths are **deterministic** (no GenAI in the figure path).
AI sits at the boundaries — document classification + extraction, screening rationale, narrative
drafting, language normalisation, translation, RAG scoring, macro overlays, pricing optimisation,
document generation, copilot — with explicit autonomy levels (`[A] / [C] / [D]`) and **never
produces credit-consequential figures or decisions**; the e2e asserts this directly (the
authoritative grade/pricing/PD are byte-identical before/after running the overlays). Inter-service
reads use Spring `RestClient` with graceful fallback to conservative built-in packs when
config-service is unreachable. Every write action takes an `X-Actor` header that is persisted in
each service's append-only `audit_events` table; the trail records actorType ∈ {HUMAN, AI, SYSTEM}.

The credit lifecycle is one spine: `counterparty-service` initiates → `origination-service`
captures facilities/sublimits/collateral + spreads with cell-level provenance + sets specialised
deal structure + runs doc-intel → `risk-service` rates + projects capital + recommends RAROC
pricing (+ advisory RAG/macro overlays + goal-seek optimiser + concession approvals) →
`decision-service` routes per DoA, captures the named-human decision, generates the credit
proposal + AI commentary, runs the CAD checklist + limit-release trigger, the covenant tracking
workflow, the MER register, and document generation with clause surgery + confirm-lock →
`limit-service` builds the limit tree from the deal, exposes the product-processor APIs, and
runs the EOD revaluation + ledger reconciliation → `portfolio-service` books exposure, runs
ECL/IRAC, tracks projected-vs-actual RAROC, the EWS agent, the Customer-360 / Portfolio-360
aggregations, the CAP workflow, and emits the canonical outbound feeds to ERM / Finance-GL / CPR.

## Conventions and footguns

- **SQLite per service** in `$HELIX_DATA_DIR` (default `./data`), Hibernate `ddl-auto=update`,
  dialect `org.hibernate.community.dialect.SQLiteDialect`, **Hikari pool size 1** (SQLite is
  single-writer). The audit `AuditService` therefore **joins the caller's transaction** —
  do NOT use `@Transactional(REQUIRES_NEW)`, it deadlocks the one-connection pool.
- **SQL reserved words bite**: column names matching SQLite keywords (`primary`, `limit`)
  fail at DDL time. Use `@Column(name = "is_primary")` / `dept_limit` — the codebase already
  has both fixes; copy the pattern when adding new columns.
- **Entities** use Lombok `@Getter`/`@Setter`; **DTOs are Java records**. Every
  `@SpringBootApplication` scans `com.helix` so shared audit/web beans (in helix-common) are
  picked up automatically — keep that scan base.
- **`X-Actor` header** on every write; persisted into the audit trail. The frontend's actor
  selector drives this; the e2e scripts pass per-step actors so SoD checks trigger.
- **Maker-checker / segregation of duties** is enforced server-side in: master records
  (config), rule-pack sign-off, rating overrides, CAD deviations (raiser ≠ approver, L1 ≠ L2),
  covenant action approvals, FI transaction approvals, ownership claims (receiving RM must
  accept), MER verify (verifier ≠ submitter) and MER waive (waiver ≠ owner), pricing-exception
  decisions (proposer ≠ approver, L1 ≠ L2), document-generation confirm-lock, commentary review,
  and CAP close (closer ≠ owner). Match this pattern when adding new approval flows — and use
  `ApiException.forbiddenAutonomy(...)` (403) for SoD violations, not a generic 400.
- **The AI / advisory invariant** (most important): no advisory engine, optimiser, drafter, or
  extractor may mutate an authoritative figure. The pattern is:
  1. Persist a separate, advisory entity (e.g. `RagAssessment`, `MacroImpactAssessment`,
     `PricingException`, `DocExtraction`, `ProposalCommentary`, `GeneratedDocument`).
  2. Mark it `advisory = true` and stamp `audit.ai("<capability>", "<EVENT>", ...)`.
  3. Provide a human-gate transition (CONFIRMED / APPROVED / REJECTED) that stamps `audit.human`.
  4. Add an e2e assertion proving the authoritative figure is unchanged after the advisory
     run (see sections 37, 40, 41 in `scripts/e2e_smoke.py` for the template).
- **Regime-specific values live in rule packs** (config-service), never in code branches.
  When adding a new computation parameter, add it to the relevant pack type's payload and
  read it via the consumer's `ConfigClient` (or `ConfigMasterClient` for masters).
- **Cross-service calls** use `RestClient` (not Feign). For new clients, follow the existing
  pattern: try the call, on failure log a warning and return a conservative fallback or
  `BAD_GATEWAY` — never silently swallow.
- **Canonical connector ingestion** (`com.helix.common.ingest`) is the contract for any new
  source-system feed: `Envelope<RAW>` → `Connector.validate/map` → `IngestionGuard` (idempotent
  on `(source, idempotencyKey)`) → domain persistence → `Ingestion.Result` (with warnings).
  Reference adapters: screening-vendor → counterparty, core-banking → exposure.
- **Canonical outbound exports** (`com.helix.common.export`) is the symmetric contract for any
  new downstream feed: assemble typed records into `Export.Envelope.of(destination, feedType,
  idempotencyKey, version, records)`, persist as an `ExportBatch` keyed by idempotencyKey
  (per as-of day), and stamp `audit.engine("EXPORT_GENERATED", ...)`. Re-running the same
  as-of day must return the existing batch.
- **JSON Map<String, Object> persistence** uses `@Convert(converter =
  JsonAttributeConverters.MapConverter.class)` + a sized `@Column(length = ...)`. Use
  `StringListConverter` for `List<String>`. Anything more complex needs a converter or a
  child entity.

## Frontend: governance design system

The "AI / Human / Deterministic" thesis is built into the UI, not just narrated over it.
When adding a new screen, reuse these primitives from `frontend/src/ui.tsx`:

- `AiBadge` · `HumanBadge` · `DeterministicBadge` — colour-coded chips that appear in the
  topbar on every screen (purple / green / blue), and inline wherever an output is AI-drafted,
  human-gated, or a deterministic figure.
- `Unchanged` — green-dashed `● UNCHANGED` / `PRESERVED` tag for an authoritative figure an
  AI overlay left untouched (the visual proof that the e2e's "unchanged" assertion is true).
- `GovernanceStrip` — the slim three-part promise under the topbar. App-shell only; don't
  duplicate.
- `GovSplit` — the signature left/right frame: **AI · ADVISORY** on the left, an arrow, and
  **AUTHORITATIVE · UNCHANGED** on the right. Risk Lab uses it (advisory RAG band beside the
  authoritative grade); reuse it whenever an AI output should sit next to an unchanged
  source-of-record value.
- `GovFlow({ai, human, note})` — an inline "AI EXTRACTS → HUMAN CONFIRMS" flow badge for
  suggest/confirm screens (Doc Intelligence / Doc Generation / AI Commentary).
- `gov-banner` (CSS class) — a dark hero banner with the one-line governance message
  for a screen (used at the top of Risk Lab and Pricing Lab). Use sparingly — one per page.

The CSS variables are scoped under `:root` in `frontend/src/styles.css`: `--ai`, `--ai-soft`,
`--human`, `--human-soft`, `--det`, `--det-soft`. Stick to those tokens so the language stays
coherent.

## Pitch & demo assets

- `docs/helix-pitch-demo.html` — auto-advancing slide viewer (loads from `docs/demo-assets/`).
  Two cuts toggled in the top bar: **Executive · 2:30** (10 beats, CRO/Head-of-Credit) and
  **Product Deep-Dive · 5:30** (20 beats, lifecycle). Two motion centerpieces are themselves
  the pitch: the **Risk Lab signature split** (full-bleed AI-ADVISORY ↔ AUTHORITATIVE-UNCHANGED
  with a pulsing `● UNCHANGED` ring) and the **Pricing governance chain** (animated
  Target→Optimiser→Concession→L1✓→L2✓→`● PRESERVED`). Plus a **Copilot** beat. Every shot beat
  has Ken-Burns + spotlight motion.
- `docs/helix-pitch-demo-standalone.html` — same viewer, all screenshots base64-inlined
  (~17 MB single file, no server, no internet).
- `docs/demo-assets/` — 19 PNG screenshots captured against the live UI (incl. `19-copilot.png`).
  To refresh after a UI change: start services + Vite dev, then re-shoot via Playwright (see
  prior runs of `/tmp/shot_demo_refresh.mjs` for the pattern) and re-bundle the standalone with
  `python3 /tmp/bundle_demo.py` (the bundler reads the source HTML, base64-encodes every
  `demo-assets/*.png`, and emits the standalone).
- `docs/demo-script.md` — the read-aloud narration script for both cuts, with timings,
  voiceover register, and beat-by-beat screen direction.

The live UI WebMs (`/tmp/helix-live-demo-governed.webm`) are real Playwright screen captures of
the running React app driven through the lifecycle, not animated slides.

## Adding a new service (quick recipe)

1. Add `<module>foo-service</module>` to the root `pom.xml`.
2. Create the module with the same shape as an existing data-service (pom inherits the parent;
   `application.yml` sets `server.port`, the SQLite URL `jdbc:sqlite:${HELIX_DATA_DIR:./data}/foo.db`,
   Hikari `maximum-pool-size: 1`, the community SQLite dialect, and any `helix.<svc>.base-url`
   it needs).
3. Add an `@SpringBootApplication(scanBasePackages = "com.helix")` class.
4. Wire a route in `gateway-service/src/main/resources/application.yml` (`Path=/foo/**`, `StripPrefix=1`).
5. Add the port to `scripts/run-all.sh` (SERVICES map + start loop + health-check ports) and
   `scripts/stop-all.sh`.
6. Add a service + volume entry to `docker-compose.yml`, and the `*_SERVICE_URL` env on the
   gateway service.

## Adding a new AI / advisory feature (recipe)

1. Define a separate entity with `advisory: boolean` (default true) and a JSON `factors` /
   `breakdown` / `contributions` map via `JsonAttributeConverters.MapConverter`.
2. Persist via its own repository; expose a `POST /…` to produce the advisory output and a
   `GET /…` to read history.
3. Stamp `audit.ai("<capability-name>", "<EVENT_NAME>", ...)` on every output.
4. Add a human-gate transition (`confirm` / `approve` / `reject`) with SoD if appropriate;
   stamp `audit.human(...)` on the gate.
5. In `frontend/src/ui.tsx` reuse `AiBadge` + `GovFlow` (suggest→confirm) or `GovSplit` (if
   the screen pairs the advisory output with an authoritative figure).
6. **Add an e2e assertion** proving the authoritative figure is unchanged after the advisory
   run. This is non-negotiable; it is the safety contract.
