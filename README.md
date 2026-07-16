# Helix — AI-First Wholesale Loan Origination & Lifecycle Platform

Helix is a reference implementation of the *Helix* PRD: an **AI-executed, human-gated**
credit lifecycle for wholesale (non-retail) lending. It inverts the traditional workflow —
document intelligence and agents spread, rate, capitalise, price and draft, while named
human authorities review, override and sign at every credit-consequential gate.

This repository delivers the **P1 MVP spine** end-to-end (mid-corporate, RBI), built as
**Java 25 / Spring Boot microservices** with **SQLite-per-service** and a **React + TypeScript**
front end, behind a single **API gateway**.

> Scope note: this is a working, runnable demonstration of the architecture and the credit
> lifecycle, not a production core-banking integration. The "AI" capabilities are deterministic
> stand-ins that model the *governance contract* (confidence, provenance, autonomy bounds,
> human gates) the PRD requires — wiring a real model gateway behind these seams is additive.

---

## What it demonstrates (the PRD's structural bets)

| PRD bet | Where it lives |
|---|---|
| **Regulatory abstraction layer first** — a new regime is an overlay, never a code branch | `config-service`: jurisdiction profiles + versioned, **dual-signed** rule packs (RBI & CBUAE seeded). Every downstream engine *consumes* packs; none branch on regime. |
| **Canonical model** — one chart, provenance on every figure | `origination-service` spreads to a canonical taxonomy; each `SpreadCell` carries source doc/page/coords, confidence, and retains both extracted value and override. |
| **Governed AI, gated by human accountability** | Autonomy levels (`[A]/[C]/[D]`), confidence-routed document classification, notch-limited rating overrides, RAROC as *advisory*, EWS that **flags but never reclassifies**, and named-human approval — AI never approves, prices, or stages autonomously. |
| **Immutable audit / figure→source→version trace** | Append-only `AuditEvent` in every service (`/api/audit`); capital & ECL emit a full trace citing rule-pack versions. |

---

## Architecture

```
                         ┌──────────────────────────┐
  React + Vite (UI) ───▶ │   gateway-service :8080   │  Spring Cloud Gateway
                         └─────────────┬─────────────┘
        ┌───────────────┬─────────────┼───────────────┬───────────────┬───────────────┐
        ▼               ▼             ▼               ▼               ▼               ▼
  config-service  counterparty-  origination-     risk-service    decision-       portfolio-
     :8081          service        service          :8084          service          service
  abstraction      :8082          :8083          rating /         :8085           :8086
  layer / rule     KYC·UBO·       intake·docs·    capital /       DoA approval·   ECL/IRAC·
  packs            screening      spreading       RAROC pricing   covenants       EWS·concentration
        │                                            │  │  │           │  │            │ │ │ │
        └────────────── rule packs ──────────────────┘  │  └─ credit   │  └─ rating/   │ │ │ └ covenants
                                                         └─ inputs ─────┘     pricing ──┘ │ └ rating/capital
                                                                                          └ credit-inputs

  Each service owns a private SQLite database (sqlite-jdbc + Hibernate community dialect).
  Inter-service calls are REST (Spring RestClient) with graceful fallback when config is down.

  copilot-service :8087 — a persona-scoped, grounded, non-binding conversational copilot
  (PRD §6.6) that reads (GET-only) across the services and cites its sources.
```

**Tech:** Java 25, Spring Boot 3.5, Spring Cloud Gateway 2025.0, SQLite (xerial JDBC +
Hibernate community dialect), React 18, Vite 5, TypeScript 5.

---

## Run it

### Prerequisites
- JDK 25 (Maven is bundled as `./mvnw` — no separate install)
- Node 20+ (for the UI)
- Optionally Docker (for the containerised stack)

### Option A — local (fastest)

```bash
# 1. build all service jars (mvnw downloads Maven on first use)
./mvnw -DskipTests package

# 2. start all services (config, counterparty, origination, risk, decision, portfolio, gateway)
bash scripts/run-all.sh          # waits for health; gateway on :8080

# 3. run the UI dev server
cd frontend && npm install && npm run dev    # http://localhost:5173

# stop everything
bash scripts/stop-all.sh
```

On **Windows / PowerShell**, use the `.ps1` ports of the launch scripts (you may
need `Set-ExecutionPolicy -Scope Process Bypass` once per session):

```powershell
.\mvnw.cmd -DskipTests package
.\scripts\run-all.ps1            # waits for health; gateway on :8080
cd frontend; npm install; npm run dev
.\scripts\stop-all.ps1
```

### Option B — Docker Compose (full stack)

```bash
mvn -DskipTests package          # build jars first (compose copies them in)
docker compose up --build
# UI:      http://localhost:5173
# Gateway: http://localhost:8080
```

### Option C — Prebuilt jars (no Maven, no Docker)

If you can't run Maven locally (no admin / locked-down JDK / wrong major
version), GitHub Actions publishes the 9 service jars to a rolling
`prebuilt-latest` release on every backend change. Fetch them into your
checkout and run the launch scripts directly:

```bash
bash scripts/fetch-prebuilt.sh        # Linux / Mac
bash scripts/run-all.sh
```

```powershell
.\scripts\fetch-prebuilt.ps1          # Windows / PowerShell
.\scripts\run-all.ps1
```

Both helpers download into each `<service>/target/` slot (no git involved).
The jars target Java 25 bytecode (`--release 25`) and boot on Java 25+
(Spring Boot 3.5.16 formally supports Java 25). The release is refreshed automatically by
the `Refresh prebuilt jars` GitHub Actions workflow on every backend
change. Direct URL for a single jar:
`https://github.com/<owner>/<repo>/releases/download/prebuilt-latest/<svc>-service.jar`.

### End-to-end tests
With the services running locally, exercise the entire lifecycle through the gateway:

```bash
python3 scripts/e2e_smoke.py            # single-deal · ~143 assertions across all stages + modules
python3 scripts/e2e_100_obligors.py     # 100-obligor distributed book · ~60s · MIS / variance / concentration
```

The 100-obligor run synthesises a realistic book — mix of segments (mid-corp,
SME, large-corp, project, trade, FI), jurisdictions (IN-RBI / AE-CBUAE),
existing-vs-new borrowers, and risk profiles — drives each obligor through
intake → spread → rate → capital → price → covenants → approve → book → ECL →
RAROC snapshot, then asserts the population distribution and exercises the
MIS endpoints. Typical run: ~190B EAD, ~30B RWA, 100+ booked exposures, several
single-name / sector limit breaches, and dozens of RAROC variance observations
feeding model-fit governance.

---

## Configuration

Helix is **secure-by-config**: every external integration ships a real, production-grade
implementation that is **inert by default** — with no configuration the platform runs exactly as the
self-contained demo (in-memory identity, outbox-only notifications, local document storage, simulated
downstream feeds, deterministic AI). Point the keys below at real infrastructure to switch each
integration "live" at deployment. All keys bind from `application.yml`, JVM `-D` properties, or
environment variables (Spring relaxed binding — `helix.security.mode` ⇄ `HELIX_SECURITY_MODE`).

### Core runtime
| Key / env | Default | Purpose |
|---|---|---|
| `HELIX_DATA_DIR` | `./data` | SQLite database + document store root (per service) |
| `SERVER_PORT` | per service (8081–8089) | Service HTTP port; gateway is `8080` |
| `helix.<svc>.base-url` | `http://localhost:<port>` | Inter-service `RestClient` targets (config/counterparty/origination/risk/decision/portfolio/limit/workflow) |

### Authentication / SSO — `helix.security.mode` = `none` (default) · `oidc` · `ldap`
Default `none` preserves the `X-Actor`-header identity model byte-identical (permit-all). Set a mode for real auth.
| Key | Applies to | Notes |
|---|---|---|
| `helix.security.mode` | all | `none` \| `oidc` \| `ldap` |
| `helix.security.issuer-uri` / `jwk-set-uri` / `jwt-secret` | `oidc` | JWT resource-server validation (IdP discovery, JWKS, or HS256 secret) |
| `helix.security.username-claim` (`preferred_username`) / `roles-claim` | `oidc` | which token claims map to actor + roles (roles drive SoD) |
| `helix.security.ldap.url` / `base-dn` / `user-dn-patterns` / `group-search-*` | `ldap` | directory bind + group→role mapping |
| `management.health.ldap.enabled` | `ldap` | leave `false` (default) unless you want the LDAP health probe |

The SPA reads `/api/security/mode` (unauthenticated) and, in `oidc`, runs an Authorization-Code + PKCE
login; in `none` it uses the persona/actor selector. The legacy demo token shim
(`helix.auth.mode` / `helix.auth.secret` / `helix.auth.token-ttl-seconds`) is unchanged and independent.

### Field-level encryption at rest — `HELIX_FIELD_KEY`
| Key | Default | Purpose |
|---|---|---|
| `HELIX_FIELD_KEY` | *(dev key)* | base64 AES-256 key encrypting sensitive PII columns |
| `HELIX_ALLOW_DEV_KEY` | `false` | opt-in to run with the built-in dev key |

**Production must set `HELIX_FIELD_KEY`** — under a `prod`/`production` Spring profile the service
**fails closed** if it is unset (unless `HELIX_ALLOW_DEV_KEY=true`); otherwise it uses the built-in dev
key and logs a loud warning (dev/CI convenience only — provides no real confidentiality).

### Notifications transport — `helix.notify.transport` = `outbox` (default) · `smtp` · `sms` · `all`
| Key | Notes |
|---|---|
| `helix.notify.transport` | `outbox` renders + persists only (no send); others transmit |
| `spring.mail.host` / `port` / `username` / `password`, `helix.notify.smtp.from` | SMTP (JavaMail); the sender autoconfigures only when `spring.mail.host` is set |
| `helix.notify.sms.gateway-url` / `api-key` / `api-key-header` | SMS HTTP gateway |
| `helix.notify.sweep-interval-ms` / `sweep-initial-delay-ms` | schedule-later + reminder sweep cadence |

Delivery is fail-soft (a transport error records `FAILED` on the outbox row and never breaks the business op).

### Document store (DMS) — `helix.dms.store` = `filesystem` (default) · `s3`
`/api/documents` upload/download/list. Filesystem stores under `${HELIX_DATA_DIR}/documents`.
| Key (S3) | Purpose |
|---|---|
| `helix.dms.s3.bucket` / `endpoint` / `region` / `access-key` / `secret-key` / `path-style` | S3 / S3-compatible object store (SigV4 over `RestClient`, no SDK dep) |

### CRM write-back — `helix.crm.mode` = `simulated` (default) · `live`
| Key | Purpose |
|---|---|
| `helix.crm.base-url` / `path` / `auth-header` / `auth-token` | live CRM REST endpoint for case/decision back-updation (idempotent per case + as-of day) |

### SharePoint / Excel-Online co-edit — `helix.coedit.provider` = `none` (default) · `graph`
| Key / env | Purpose |
|---|---|
| `helix.coedit.tenant-id` / `client-id` / `client-secret` / `drive-id` (`HELIX_COEDIT_*`) | Microsoft Graph app registration + target SharePoint drive |
| `helix.coedit.graph-base-url` / `token-url` / `scope` / `upload-folder` | Graph endpoints (overridable for private clouds) |

> ⚠️ **Data egress:** with `provider=graph`, generated credit documents are uploaded to the configured
> O365/SharePoint tenant. Default `none` returns the local artifact and makes **no external call**.

### LLM endpoint — `helix.llm.provider` = `none` (default, deterministic) · `openai` · `anthropic` · `azure-openai`
**Every** AI capability across the platform routes through this one governed client — copilot,
document classification / extraction / language-normalisation / translation / consistency-check
narratives, collateral extraction + revaluation narratives, financial-spreading extraction,
screening rationale, group identification, UBO resolution narrative, RAG & macro-impact &
pricing-optimiser & EWS rationales, client-planning-template & credit-proposal & group-insights
drafts, covenant extraction, compliance-certificate assessment, and document-generation clause
polishing. All of them are **deterministic/grounded by default** (no model call). Point them at a
model to make the drafting generative, **without changing the governance contract**: LLM output
stays **advisory + human-gated** and **never writes an authoritative figure/grade/price/decision**
(deterministic engines — rating, capital, ECL, RAROC/pricing math, LTV, covenant recompute,
match/ownership scores — are untouched; extractions remain human-confirm). This config is
maintained separately from every other integration.
| Key / env | Default | Purpose |
|---|---|---|
| `helix.llm.provider` (`HELIX_LLM_PROVIDER`) | `none` | `none` \| `openai` \| `anthropic` \| `azure-openai` |
| `helix.llm.base-url` (`HELIX_LLM_BASE_URL`) | — | model API base URL (public API or a private gateway) |
| `helix.llm.api-key` (`HELIX_LLM_API_KEY`) | — | credential (never logged) |
| `helix.llm.model` (`HELIX_LLM_MODEL`) | — | default model id / Azure deployment name |
| `helix.llm.timeout-ms` / `max-tokens` / `temperature` | 20000 / 1024 / 0.2 | request bounds (connect+read timeout, token cap, sampling) |
| `helix.llm.anthropic-version` | `2023-06-01` | `anthropic-version` header (API version, not a model) — `anthropic` provider only |
| `helix.llm.azure-api-version` | `2024-02-15-preview` | `api-version` query param (API version, not a model) — `azure-openai` provider only |
| `helix.llm.capability-models.<capability>` | — | per-capability model override — one key per AI capability (`copilot`, `doc-extract`, `doc-classify`, `spreading-extract`, `language-normalise`, `translation`, `doc-checks`, `collateral-extract`, `collateral-monitor`, `screening-rationale`, `group-identification`, `ubo-narrative`, `rag-narrative`, `macro-narrative`, `pricing-narrative`, `ews-narrative`, `commentary`, `cpt-draft`, `proposal-draft`, `group-insights`, `covenant-extract`, `covenant-certificate`, `docgen-clause`); a missing key uses `helix.llm.model` |

A model outage or timeout **falls back to the deterministic path** — a configured LLM is an enhancement, never a hard dependency.

### Dev / test toggles
`HELIX_NOTIFY_TEST_ENQUEUE_ENABLED`, `HELIX_RBAC_SIMULATE_OUTAGE_ENABLED` — off by default; used by the e2e suites.

---

## The demo flow (UI)

1. **Counterparties** → onboard a borrower. Risk flags drive the **CDD tier**; resolve the
   **UBO graph** (effective ownership via path multiplication, ≥10% flagged, low-confidence
   nodes routed to review); run **screening** and disposition hits (no auto-clear ≥ SEVERE);
   **verify KYC** (blocked while hits ≥ MEDIUM are open).
2. **Deals** → create an application, then open the **Deal Workspace**, which drives:
   - **Documents** — AI classification with confidence; low-confidence → review queue.
   - **Spreading** — auto-spread to the canonical chart with figure-level provenance;
     override a cell (a material change re-opens the **confirm gate**).
   - **Rating** — scorecard PD/LGD/EAD with per-factor contributions; **notch-limited
     overrides** (analyst 1, officer 2, committee unlimited); approver **confirms**.
   - **Capital** — deterministic SA RWA via the **RBI rule pack**, with CRM, due-diligence
     uplift, a full computation **trace**, and a grounded AI **explanation**.
   - **Pricing** — RAROC (advisory; below-hurdle flagged).
   - **Approval** — **DoA routing** on amount × rating × deviations; add AI-suggested
     covenants; a **named human** records APPROVE/CONDITIONAL/DECLINE — AI cannot approve.
   - **Book & monitor** — book the exposure, compute **ECL with parallel IRAC**
     (reported = jurisdiction policy), run the **EWS** scan.
   - **Ask the copilot** — a deal-scoped, grounded, non-binding Q&A panel.
3. **Portfolio Dashboard** → staging split, override-rate model-fit signal, concentration vs
   limits, EWS watchlist, stress scenarios.
4. **Copilot** → persona-scoped Q&A; it grounds-and-cites in-scope answers and **refuses**
   credit-consequential actions, routing them to the gated workflow.
5. **Jurisdictions & Rule Packs** → inspect the abstraction layer (switch IN-RBI / AE-CBUAE).
6. **Audit Trail** → every action attributed to a named **HUMAN**, governed **AI**, or
   deterministic **SYSTEM** actor.

Counterparties also expose an **ingest vendor feed** action and exposures accept a
**core-banking conduct feed** — the canonical connector ingestion path (idempotent,
provenance-stamped); see [`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md).

---

## API tour (through the gateway, `:8080`)

| Prefix | Service | Sample endpoints |
|---|---|---|
| `/config` | config-service | `GET /api/jurisdictions`, `GET /api/rulepacks?jurisdiction=IN-RBI&type=CAPITAL_SA` |
| `/counterparty` | counterparty-service | `POST /api/counterparties`, `POST /api/counterparties/{id}/ubo`, `POST /api/counterparties/{id}/kyc/verify` |
| `/origination` | origination-service | `POST /api/applications`, `POST /api/applications/{ref}/spread`, `GET /api/applications/{ref}/analysis` |
| `/risk` | risk-service | `POST /api/risk/{ref}/rate`, `POST /api/risk/{ref}/capital`, `GET /api/risk/{ref}/capital/explain` |
| `/decision` | decision-service | `POST /api/decisions/{ref}/route`, `POST /api/decisions/{ref}/decide` |
| `/portfolio` | portfolio-service | `POST /api/portfolio/exposures/{ref}/ecl`, `GET /api/portfolio/concentration`, `GET /api/portfolio/stress` |
| `/copilot` | copilot-service | `POST /api/copilot/ask`, `GET /api/copilot/scope?persona=…` |
| `/{svc}` | any | `GET /api/audit` — immutable trail; `POST …/ingest/{connector}` — vendor feeds |

All write calls accept an `X-Actor` header so the audit trail names the responsible party.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the canonical model, per-service
responsibilities, design decisions, and the full PRD-stage → service mapping, and
[`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md) for the connector pattern and canonical
ingestion schemas.
