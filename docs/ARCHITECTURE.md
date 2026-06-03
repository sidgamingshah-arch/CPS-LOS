# Helix — Architecture

This document explains how the implementation realises the *Helix* PRD: the service
topology, the canonical model, the regulatory abstraction layer, the AI-governance
contract, and the key design decisions.

## 1. Service topology

Helix is a set of independently deployable Spring Boot services, each owning a private
SQLite database (no shared schema), behind a Spring Cloud Gateway. Services are organised
by **bounded context**, mirroring the PRD lifecycle stages.

| Service | Port | PRD stages | Owns |
|---|---|---|---|
| `gateway-service` | 8080 | §8 (API-first) | Routing, CORS, single entry point |
| `config-service` | 8081 | §10 | Jurisdiction profiles, versioned rule packs, dual sign-off |
| `counterparty-service` | 8082 | §1 | Counterparties, CDD tiering, UBO graph, screening |
| `origination-service` | 8083 | §3, §4 | Applications, document classification, financial spreading |
| `risk-service` | 8084 | §5, §6, §7 | Rating (PD/LGD/EAD), capital (RWA), RAROC pricing |
| `decision-service` | 8085 | §8, §9 | DoA routing, approval decisions, covenants |
| `portfolio-service` | 8086 | §11, §12 | Exposures, ECL/IRAC, EWS, concentration, stress |
| `copilot-service` | 8087 | §6.6 | Persona-scoped, grounded, non-binding conversational copilot |

`helix-common` is a shared library (not a service): canonical enums, the append-only audit
subsystem, JSON attribute converters (SQLite has no JSON type), and web cross-cutting
concerns (CORS, error handling).

### Inter-service communication
REST over `RestClient`. Runtime dependencies are deliberately minimal and **degrade
gracefully** (PRD §9 resilience):

- `risk-service` → `config-service` for rule packs, → `origination-service` for credit inputs.
- `decision-service` → `config` (DoA), `origination` (terms), `risk` (rating/pricing).
- `portfolio-service` → `config` (provisioning/limits), `origination`, `risk`, `decision`.

If `config-service` is unreachable, consumers fall back to conservative built-in packs
(version `0`, visible in the trace) so the deterministic capital/ECL path still runs.

## 2. Canonical data model (PRD §7)

Core objects are jurisdiction-agnostic; regime specifics live only in rule-pack data.

- **Counterparty** — identity, legal form, CDD tier, KYC state, risk flags.
- **UBO graph** — `UboNode` + `UboEdge`; effective ownership = Σ over all paths of the
  product of edge percentages; cycles detected and flagged.
- **Application** — terms, collateral, status, `spreadConfirmed` gate.
- **Spread** — `FinancialPeriod` + `SpreadCell`; every cell carries provenance
  (source document/page/coordinates), confidence, extracted value, and any override.
- **Rating** — model vs final grade, PD/LGD/EAD, override metadata, score breakdown.
- **CapitalResult / EclResult** — figures plus a full `trace` citing rule-pack versions.
- **Decision** — outcome, required authority, deviations, conditions, named decider.
- **Covenant** — structured rule object (metric, operator, threshold, frequency, breach actions).
- **AuditEvent** — append-only; service, actor, actorType (HUMAN/AI/SYSTEM), event, detail.

The canonical chart of accounts and derived-line rules live in
`origination-service/.../CanonicalTaxonomy.java`; ratios in `Ratios.java`.

## 3. The regulatory abstraction layer (PRD §10)

`config-service` is built first because everything depends on it. A **jurisdiction profile**
selects the capital approach, CVA approach, provisioning frameworks, reported-provision
policy, limits, CDD rules and reporting pack. The actual numbers live in **rule packs**,
each `(code, type, jurisdiction, version)` with a JSON payload and **dual sign-off**
(policy + model risk) before activation. Pack types consumed downstream:

`CAPITAL_SA`, `ECRA_MAPPING`, `RATING_PD_MAP`, `LGD_MAP`, `PROVISIONING`, `DOA_MATRIX`,
`CDD_TIERS`, `EXPOSURE_LIMITS`, `PRICING`.

Two regimes are seeded — **IN-RBI** (SA Directions 2026 / IRAC, `max(ECL, IRAC)`) and
**AE-CBUAE** (Basel III / IFRS-9 only). Switching regimes changes *data*, not code: the same
`CapitalEngine` / `EclEngine` produce different results purely from the active pack. This is
the PRD's central claim — *a new regime is an overlay, not a release* — made concrete.

## 4. AI governance contract (PRD §6, §11)

Generative AI is **forbidden in the deterministic computation path** (capital, ECL, RWA). It
may *explain* a number — grounded in and quoting engine values — but never produce one. The
implementation encodes the contract structurally:

- **Autonomy levels** — `AUTONOMOUS [A]`, `COPILOT [C]`, `DECISION_SUPPORT [D]` (`Enums`).
- **Confidence routing** — document classification auto-routes only above a threshold;
  below, it sets `needsReview`.
- **Human gates, hard-coded** — spread must be analyst-confirmed before rating; rating must be
  approver-confirmed; only a named human at sufficient DoA authority can decide; EWS *flags*
  but never reclassifies/re-stages; screening hits ≥ SEVERE cannot be auto-cleared.
- **Notch-limited overrides** — analyst 1, officer 2, committee unlimited; every override is
  audited with `model_grade/final_grade/notches/reason_code/approver` and feeds the
  **override-rate** model-fit signal (alert > 25%).
- **Immutable audit** — `AuditService` is insert-only; the `actorType` distinguishes HUMAN
  vs AI vs SYSTEM, so the trail shows exactly where AI acted and where a human signed.

## 5. Key design decisions

- **SQLite-per-service.** Honours the brief and keeps services independent with zero infra.
  Hikari pool size is 1 (SQLite is single-writer); audit writes **join the caller's
  transaction** rather than opening a new one (which would deadlock on a one-connection pool).
- **JSON-as-TEXT.** SQLite lacks a JSON type, so maps/lists (traces, breakdowns, reasons) are
  persisted via `AttributeConverter`s in `helix-common`.
- **Rule packs as data, fetched at runtime.** Engines read packs through a client with a
  built-in fallback, demonstrating both the abstraction layer and graceful degradation.
- **Frontend orchestrates the lifecycle.** The React workspace calls each service in sequence
  through the gateway, making the staged, human-gated pipeline visible; server-to-server calls
  are limited to authoritative reads (e.g. risk → origination credit inputs).

## 6. Capital computation (worked logic, PRD §6)

`CapitalEngine` is fully deterministic:

1. Map segment/facility → **exposure class** (CORPORATE, SME_CORPORATE, BANK,
   SPECIALISED_LENDING, …).
2. Apply **CCF** for off-balance items (undrawn commitment, trade contingent, direct credit
   substitute) → exposure.
3. **Risk weight** from the ECRA mapping (internal grade → bucket → table weight), or
   supervisory **slotting** for specialised lending.
4. **Due-diligence uplift** where the regime requires DD and evidence is absent.
5. **CRM** — the collateralised portion (after the supervisory haircut) is mitigated; the
   residual carries the full weight.
6. `RWA = unsecured × appliedRW`; `capital = RWA × capital_ratio_min`.

Every intermediate value and the rule-pack versions are written to `trace`, satisfying the
"figure → source → version" examiner requirement (US-6.1/6.2).

## 7. Conversational copilot (PRD §6.6)

`copilot-service` is a stateless (bar its audit DB) retrieval layer. `ask(persona, question)`:

1. **Action guardrail** — credit-consequential imperatives (approve, override, book, stage,
   disburse) are refused and routed to the gated workflow; the copilot never mutates state
   (all its upstream reads are GETs).
2. **Intent + scope** — the question is classified to an intent; `PersonaScope` maps the actor
   to a role and rejects out-of-scope topics (the scope-leak guardrail).
3. **Grounding** — facts are retrieved from the owning services and the answer **cites** each
   source endpoint; nothing is fabricated. Every ask is logged to the audit trail as an AI action.

The reasoning is deterministic today; a generative model drops in behind this same envelope
(scope → retrieve → ground → cite → refuse) without changing the governance behaviour.

## 8. Connector ingestion (PRD §8)

Shared in `helix-common` (`com.helix.common.ingest`): canonical schemas (bureau, registry/GST,
screening, core-banking, market data), a `Connector<RAW,CANON>` interface, a `Provenance`
stamp, and an `IngestionGuard` (idempotency via the `ingestion_records` table). Two reference
adapters are wired — screening-vendor → counterparty, core-banking → exposure — each idempotent,
provenance-stamped, and surfacing reconciliation/validation issues as warnings. Full schema set
and example payloads in [`INTEGRATIONS.md`](INTEGRATIONS.md).

## 9. What is MVP vs. additive

Implemented end-to-end: the credit lifecycle spine for mid-corporate under RBI, plus the
CBUAE overlay, ECL/IRAC, concentration, EWS and stress.

Additive (seams exist, not built here): SA-CCR / CVA for FI counterparties, real document
OCR + a model gateway behind the AI seams, SICR notch-migration staging, a borrower portal,
event streaming, and RBAC/ABAC/SSO. None require core changes — they extend existing
contracts.
