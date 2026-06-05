# Helix — AI-First Wholesale Loan Origination & Lifecycle Platform

Helix is a reference implementation of the *Helix* PRD: an **AI-executed, human-gated**
credit lifecycle for wholesale (non-retail) lending. It inverts the traditional workflow —
document intelligence and agents spread, rate, capitalise, price and draft, while named
human authorities review, override and sign at every credit-consequential gate.

This repository delivers the **P1 MVP spine** end-to-end (mid-corporate, RBI), built as
**Java 21 / Spring Boot microservices** with **SQLite-per-service** and a **React + TypeScript**
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

**Tech:** Java 21, Spring Boot 3.3, Spring Cloud Gateway 2023.0, SQLite (xerial JDBC +
Hibernate community dialect), React 18, Vite 5, TypeScript 5.

---

## Run it

### Prerequisites
- JDK 21, Maven 3.9+
- Node 20+ (for the UI)
- Optionally Docker (for the containerised stack)

### Option A — local (fastest)

```bash
# 1. build all service jars
mvn -DskipTests package

# 2. start all services (config, counterparty, origination, risk, decision, portfolio, gateway)
bash scripts/run-all.sh          # waits for health; gateway on :8080

# 3. run the UI dev server
cd frontend && npm install && npm run dev    # http://localhost:5173

# stop everything
bash scripts/stop-all.sh
```

### Option B — Docker Compose (full stack)

```bash
mvn -DskipTests package          # build jars first (compose copies them in)
docker compose up --build
# UI:      http://localhost:5173
# Gateway: http://localhost:8080
```

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
