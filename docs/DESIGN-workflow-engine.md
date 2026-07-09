# Design — Config-Driven Workflow Engine

> Status: **proposed / build-ready**. Author hand-off spec for wiring the
> already-seeded `WORKFLOW_DEFINITION` rule packs to a runtime engine.
> Companion to `docs/ARCHITECTURE.md`.

## 1. Objective & non-goals

**Objective.** Make the lifecycle workflow *observable and guarded from config*:
materialise each application's stage sequence from the `WORKFLOW_DEFINITION`
rule pack that config-service already seeds, track where every deal sits, drive
SLA timers off it, and enforce the pack-declared `humanGate` / `autonomy`
contract on each stage transition — without rewriting the domain logic that is
correctly regulation-shaped today.

**Stance: strangler overlay, not a BPM rewrite.** The platform's bespoke state
machines (disbursement `DRAFT→AUTHORIZED→RELEASED→REVERSED`, CAD, MER, covenant
tracking, collections DPD staging) encode real money-movement and SoD rules.
We do **not** genericise those into a Turing-complete process engine — that
would dilute the deterministic, auditable invariants that are the product
thesis. Instead the engine sits *alongside* them: it tracks lifecycle state and
guards stage entry/exit; domain services keep their logic and *report* into it.

**Non-goals (v1):**
- No replacement of money-movement state machines (disbursement/reversal/repayment).
- No arbitrary user-authored process graphs with loops/branches — stages are a
  declared ordered list with gate metadata (which is exactly the seeded shape).
- No cross-service distributed transaction/saga semantics; stage reporting is
  best-effort and idempotent (same pattern as `UpstreamClient.allocateSyndicationOrSkip`).

## 2. Current state (verified)

| Workflow area | Today | Evidence |
|---|---|---|
| `WORKFLOW_DEFINITION` packs | **Seeded, browsable, never consumed** | `config-service/.../seed/RulePackSeeder.java:74-77,114`; only other ref is the UI pack list `frontend/src/pages/RulePacks.tsx:8` |
| DoA approval routing | **Data-driven** (the one good case) | `decision-service/.../service/DoaRouter.java:21-46` reads `DOA_MATRIX` |
| Pricing-exception L1/L2 | Hardcoded bps bands + `switch(status)` | `risk-service/.../PricingExceptionService.java:100-199` |
| CAD 2-level deviation | Hardcoded `switch(status)` | `decision-service/.../CadService.java:145-166` |
| Covenant / MER / Collections / Disbursement / Amendment | Bespoke `switch`/`if` state machines | per-service |

The seeded stage schema we will consume (`RulePackSeeder.java:333-367`):

```jsonc
{
  "segment": "MID_CORPORATE",
  "stages": [
    { "key": "RATING", "label": "Scorecard rating…",
      "autonomy": "D", "ai": true, "humanGate": true, "slaHours": 8 },
    { "key": "APPROVAL", "label": "DoA routing & named-human decision",
      "autonomy": "—", "ai": false, "humanGate": true, "slaHours": 72 }
    // …14 stages total
  ]
}
```

## 3. Target architecture

A new **`workflow-service` (port 8089)** is the system-of-record for *workflow
instances*; config-service stays the owner of *definitions* (no change there).
Participating services gain a thin `WorkflowClient` that reports stage
transitions at their existing transition points. This follows the platform's
microservice shape (`CLAUDE.md` → "Adding a new service") and keeps the
lifecycle-spanning state in one queryable place rather than smeared across nine
SQLite files.

```
config-service ──(WORKFLOW_DEFINITION pack)──┐
                                             ▼
counterparty / origination / risk / decision / portfolio
        │  (WorkflowClient.recordStage, best-effort)
        ▼
   workflow-service  ── owns WorkflowInstance / StageState / Transition
        │
        ├─ GET  /api/workflow/instances/{appRef}     (lifecycle view)
        ├─ POST /api/workflow/instances/{appRef}/advance
        └─ GET  /api/workflow/sla-breaches           (sweep + dashboard feed)
```

**De-risking note.** Because stage reporting is best-effort and non-blocking,
Phase 1 can ship with the client calls wrapped exactly like the existing
syndication-allocate seam (try → on failure WARN + skip), so a workflow-service
outage can never break the credit lifecycle. The engine is an overlay.

## 4. Data model (`workflow-service`)

SQLite per service, Hikari pool 1, community dialect — same as every data
service. Watch the reserved-word footgun (`@Column(name=…)` for `key`/`order`).

```java
@Entity @Table(name = "workflow_instances",
   indexes = @Index(name="idx_wf_app", columnList="applicationReference", unique=true))
class WorkflowInstance {
  @Id @GeneratedValue Long id;
  @Column(nullable=false, unique=true, length=30) String applicationReference;
  String definitionCode;        // e.g. workflow_mid_corp_rbi_v1
  int    definitionVersion;     // pin the pack version at materialise time
  String jurisdiction;
  String segment;
  String currentStageKey;
  @Column(length=20) String status;   // ACTIVE | COMPLETED | ABANDONED
  Instant startedAt; Instant completedAt;
  boolean slaBreached;          // rolled up from any breached stage
}

@Entity @Table(name = "workflow_stage_states")
class WorkflowStageState {
  @Id @GeneratedValue Long id;
  Long instanceId; int ordinal;
  @Column(name="stage_key", length=40) String stageKey;   // 'key' is reserved
  String label; @Column(length=4) String autonomy;        // A|C|D|—
  boolean aiAllowed; boolean humanGate; int slaHours;
  @Column(length=20) String status;   // PENDING | IN_PROGRESS | COMPLETE | BLOCKED | SKIPPED
  Instant enteredAt; Instant completedAt;
  String completedBy; @Column(length=10) String completedByType; // HUMAN|AI|SYSTEM
  Instant slaDueAt; boolean slaBreached;
}

@Entity @Table(name = "workflow_transitions")   // append-only, like audit
class WorkflowTransition {
  @Id @GeneratedValue Long id;
  Long instanceId; String fromStageKey; String toStageKey;
  String actor; @Column(length=10) String actorType; String note;
  @CreationTimestamp Instant occurredAt;
}
```

## 5. Engine API

| Verb | Path | Purpose |
|---|---|---|
| `POST` | `/api/workflow/instances` | Materialise from the active `WORKFLOW_DEFINITION` for `(jurisdiction, segment)`. Idempotent on `applicationReference`. |
| `GET` | `/api/workflow/instances/{appRef}` | Full lifecycle view (instance + ordered stage states + transitions). |
| `POST` | `/api/workflow/instances/{appRef}/advance` | Complete the current/named stage and enter the next. Enforces the gate contract (§6). Body: `{stageKey, note}`; `X-Actor` header mandatory. |
| `POST` | `/api/workflow/instances/{appRef}/stages/{key}/block` · `/unblock` | Park a stage (e.g. pending CP) with a reason. |
| `POST` | `/api/workflow/instances/{appRef}/stages/{key}/record` | Best-effort report from a domain service that a stage *happened* (no strict ordering enforcement — used by the strangler wiring). |
| `GET` | `/api/workflow/sla-breaches?jurisdiction=&segment=` | All instances/stages past `slaDueAt`. Drives the dashboard + a scheduled sweep. |

Materialise pulls the pack via the established client idiom (mirror
`UpstreamClient.doaMatrix` → add `workflowDefinition(jurisdiction, segment)`),
with a conservative fallback to a built-in linear stage list if config-service
is unreachable, so onboarding never hard-fails.

## 6. Transition guard contract (the governed core)

`advance()` enforces, reading the pinned definition — **no hardcoded rules**:

1. **Ordering.** The target stage must be the current stage or the next
   `PENDING` one; you cannot skip a stage whose `humanGate=true`.
2. **Named human on gates.** If `humanGate=true`, a blank/`SYSTEM`/`AI` actor is
   rejected with `ApiException.forbiddenAutonomy(...)` (403) — reuse the exact
   `requireActor` idiom from `DisbursementService:203-208`. Records
   `completedByType=HUMAN`.
3. **Autonomy honoured.** Stages with `autonomy=A` may be completed by
   `actorType ∈ {AI, SYSTEM}` (an automated step); `autonomy=D` or any
   `humanGate` stage requires `HUMAN`. This makes the seeded `[A]/[C]/[D]`
   metadata executable rather than decorative.
4. **Audit on every transition.** `audit.human(...)` for human gates,
   `audit.engine(...)`/`audit.ai(...)` for automated stages — event
   `WORKFLOW_STAGE_ADVANCED`, plus a `WorkflowTransition` row.
5. **Authoritative-figure invariant.** The engine stores *no* credit figures —
   it tracks stage status only. The e2e asserts grade/pricing/PD are byte-identical
   before and after a full advance sweep (per `CLAUDE.md` non-negotiable).

## 7. Strangler wiring (where existing services call in)

Each service reports its existing transition into the engine — *additive*, no
behaviour change. Examples:

| Service | Existing event | New call |
|---|---|---|
| origination | `APPLICATION_CREATED` | `workflow.materialise(appRef, jurisdiction, segment)` |
| origination | `SPREAD_CONFIRMED` | `workflow.record(appRef, "SPREAD_CONFIRM", actor)` |
| risk | `RATING_CONFIRMED` | `workflow.record(appRef, "RATING", actor)` |
| decision | `DECISION_RECORDED` | `workflow.advance(appRef, "APPROVAL", actor)` |
| portfolio | `EXPOSURE_BOOKED` | `workflow.advance(appRef, "BOOKING", actor)` |

Calls are best-effort (try → WARN → continue), so workflow-service is never on
the critical path in Phase 1.

## 8. Genuinely config-isable slice: generalise approval routing

The data-amenable refactor that pays off immediately is to lift the L1/L2
threshold logic out of code, the way DoA already is. Introduce a shared
`ApprovalRouter` (generalise `DoaRouter`) that reads an `APPROVAL_POLICY` block:

```jsonc
// new APPROVAL_POLICY master / rule-pack block, per workflow + jurisdiction
{ "policyKey": "PRICING_EXCEPTION",
  "levels": [
    { "max_metric": 50,  "metric": "concessionBps", "authority": "RELATIONSHIP_HEAD", "level": 1 },
    { "max_metric": 150, "metric": "concessionBps", "authority": "CREDIT_OFFICER",    "level": 1 },
    { "max_metric": 300, "metric": "concessionBps", "authority": "CREDIT_HEAD",       "level": 2 }
  ],
  "below_hurdle_escalates": true }
```

`PricingExceptionService:100-120` and `CadService` then read levels/authorities
from config; the `switch(status)` *state machine stays in code* (it's correct),
but the **thresholds and authorities become config**, eliminating the last
hardcoded bands. This is the concrete answer to "make workflows configurable"
without destabilising the approval semantics.

## 9. Phasing & effort

| Phase | Scope | Risk | Est. |
|---|---|---|---|
| **1 — Tracker + SLA** | workflow-service, instance/stage model, materialise, best-effort `record`, SLA-breach read + sweep, lifecycle UI strip. Zero behaviour change. | Low | ~4–5 dev-days |
| **2 — Guarded advance + ApprovalRouter** | `advance()` gate contract; generalise DoA→`ApprovalRouter` + `APPROVAL_POLICY`; migrate PricingException & CAD thresholds to config. | Medium (touches approval paths — covered by e2e) | ~5–6 dev-days |
| **3 — Editable definitions** | Expose `WORKFLOW_DEFINITION` editing through the master-engine UI (maker-checker, versioning, SoD already free); pack-version pinning on in-flight instances. | Medium | ~3–4 dev-days |

Service-creation overhead (pom module, gateway route `Path=/workflow/**`
`StripPrefix=1`, run-all/stop-all ports, docker-compose, `*_SERVICE_URL`) per
the `CLAUDE.md` recipe: ~0.5 day, in Phase 1.

## 10. e2e contract (`scripts/e2e_workflow.py`, new section in the safety net)

- Materialise an instance for a mid-corp RBI deal → assert stage list equals the
  seeded pack (count + keys + `humanGate` flags).
- `advance` a `humanGate` stage with **blank** `X-Actor` → expect **403**
  `forbiddenAutonomy`.
- `advance` a `humanGate` stage with an AI actorType → **403**; with a named
  human → **200**, `completedByType=HUMAN`.
- Force an SLA breach (back-date `enteredAt`) → assert it surfaces in
  `/sla-breaches`.
- **Invariant:** snapshot `risk/{ref}` grade+pricing before and after a full
  advance sweep → assert byte-identical (the non-negotiable assertion).

## 11. Frontend

- Lifecycle **stage tracker** strip on the application screen: ordered chips,
  each tagged with `AiBadge`/`HumanBadge` from the stage's `autonomy`/`humanGate`,
  current stage highlighted, SLA countdown, `GovFlow` on suggest→confirm gates.
- **SLA dashboard** card fed by `/sla-breaches`.
- Reuse `frontend/src/ui.tsx` primitives only — no new design tokens.

## 12. Risks & mitigations

- *Scope creep into a BPM engine* → the non-goals (§1) are the guardrail;
  money-movement machines stay in code.
- *Instance/source drift* (engine says stage X, domain says Y) → engine is
  advisory-of-record for *lifecycle position only*; domain services remain
  authoritative for their own status; the e2e cross-checks key transitions.
- *Pack edited mid-flight* → instances pin `definitionVersion` at materialise;
  edits apply to new instances only.
