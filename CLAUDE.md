# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Helix is an AI-first wholesale loan origination & lifecycle platform. Java 21 / Spring Boot
microservices (9 of them), SQLite-per-service, React + Vite + TS frontend behind a Spring
Cloud Gateway. See `README.md` for the product story and `docs/ARCHITECTURE.md` /
`docs/FEATURE-COVERAGE.md` / `docs/INTEGRATIONS.md` for depth.

## Build · run · test

```bash
mvn -DskipTests package                 # build all jars (~10 modules under one reactor)
bash scripts/run-all.sh                 # start every service; health-gated on 8080-8088
python3 scripts/e2e_smoke.py            # full lifecycle, ~143 assertions through the gateway
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
so individual asserts are reached by removing earlier blocks.

## Services and routes (gateway :8080 strips the first segment)

| Service | Port | Gateway prefix | Owns |
|---|---|---|---|
| config-service | 8081 | `/config` | Jurisdiction profiles + versioned rule packs; **generic Master-Data engine** (`/api/masters/{type}`) with maker-checker + bulk |
| counterparty-service | 8082 | `/counterparty` | KYC/CDD/UBO/screening + **credit-initiation lifecycle** `/api/initiation` (prospect→obligor, dedup, negative check, source-system check façade, RM ownership, groups) |
| origination-service | 8083 | `/origination` | Applications, document classification, financial spreading with provenance, **proposed facilities + sublimits + interchangeability**, collaterals |
| risk-service | 8084 | `/risk` | Scorecard rating (PD/LGD/EAD, notch-limited overrides), capital projection for RAROC, RAROC pricing. Calls config + origination |
| decision-service | 8085 | `/decision` | DoA approval, covenants + **tracking workflow** (`/api/covenants/tracking`), credit proposal, **CAD / documentation** `/api/cad` (checklist from master, 2-level waiver/deviation, limit-release trigger) |
| portfolio-service | 8086 | `/portfolio` | ECL/IRAC, EWS, concentration, stress, **RAROC actual/projected tracking**, MIS incl. Customer-360 + Portfolio-360 |
| copilot-service | 8087 | `/copilot` | Persona-scoped, grounded, non-binding conversational copilot (read-only fan-out) |
| limit-service | 8088 | `/limits` | Multi-level limit tree (built from deal facilities/sublimits), fungibility roll-up, exposure norms, product-processor View/Validation/Utilisation APIs (UTILISE/RELEASE/RESERVE/REVERSAL, override, freeze), country + department limits, FI transaction workflow |
| gateway-service | 8080 | — | Spring Cloud Gateway; strips first path segment |

`helix-common/` is a shared library (not a service): canonical `Enums`, append-only `AuditEvent`,
JSON attribute converters, web cross-cutting, and the **canonical connector ingestion** contracts
in `com.helix.common.ingest` (Envelope, Provenance, Connector interface, idempotency guard).
Every service that includes helix-common automatically exposes `/api/audit` and `/api/audit/subject`.

## Architecture in one paragraph

A new regime is **overlay data, never a code branch**. `config-service` owns two things —
**versioned, dual-signed rule packs** (capital, ECRA, PD/LGD, provisioning, DoA, limits,
pricing, workflow definitions) and the **generic Master-Data engine** behind every "X Master"
in the platform (dedup rules, negative list, covenant library, facility/collateral masters,
RAROC masters, EWS triggers, email templates, checklists, …) with maker-checker + bulk upsert
+ SoD. All downstream engines fetch packs/masters at runtime; the rating, capital and ECL paths
are **deterministic** (no GenAI in the figure path). AI sits at the boundaries (document
classification, screening rationale, narrative drafting, copilot) with explicit autonomy levels
(`[A] / [C] / [D]`) and never produces credit-consequential figures or decisions — those are
hard-coded to a named human. Inter-service reads use Spring `RestClient` with graceful fallback
to conservative built-in packs when config-service is unreachable. Every write action takes an
`X-Actor` header that is persisted in each service's append-only `audit_events` table; the
trail records actorType ∈ {HUMAN, AI, SYSTEM}.

The credit lifecycle is one spine: `counterparty-service` initiates → `origination-service`
captures facilities/sublimits/collateral + spreads with cell-level provenance →
`risk-service` rates + projects capital + recommends RAROC pricing → `decision-service`
routes per DoA, captures the named-human decision, generates the credit proposal, runs the
CAD checklist + limit-release trigger and the covenant tracking workflow → `limit-service`
builds the limit tree from the deal and exposes the product-processor APIs →
`portfolio-service` books exposure, runs ECL/IRAC, tracks projected-vs-actual RAROC, the
EWS agent and the Customer-360 / Portfolio-360 aggregations.

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
  accept). Match this pattern when adding new approval flows.
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
