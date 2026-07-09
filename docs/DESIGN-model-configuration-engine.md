# Design / Plan — Model Configuration Engine

> Status: **proposed, awaiting approval**. Replaces the `QUAL_SCORECARD` master with
> a generic, configurable scoring-model engine. Built from research of the existing
> QUAL_SCORECARD footprint and the config-service master/engine conventions.

## 1. What the user asked for

- **Not** qualitative parameters as a flat master.
- A **full-fledged model configuration engine** with: visibility rules, min/max
  questions answered, conditional questions, dropdowns, free inputs, iterative
  (repeating) questions, master-driven parameters, scoring.
- **Sector- and segment-specific models** are configurable.
- **Qualitative vs quantitative are sections within the same model** (not separate things).
- Engine **scores + captures** (deterministic weighted composite → band, advisory).
- **Remove & migrate** the old `QUAL_SCORECARD`; re-seed the rating model as a model definition.

## 2. Design

### 2.1 Model = sections of typed questions + rules + scoring

A **model definition** is a `MODEL_DEFINITION` master (reuses the generic master
engine → maker-checker, versioning, SoD, audit for free). Payload shape:

```jsonc
{
  "modelKey": "corporate-rating-v1",
  "displayName": "Corporate Credit Rating Model",
  "selector": { "jurisdiction": "IN-RBI", "sector": "MANUFACTURING", "segment": "MID_CORPORATE" },
  "constraints": { "minAnswered": 6, "maxAnswered": 40, "mandatory": ["mgmt_quality","leverage"] },
  "scoring": { "bands": [ {"band":"STRONG","min":67},{"band":"ADEQUATE","min":45},{"band":"WEAK","min":0} ] },
  "sections": [
    { "key": "QUALITATIVE", "kind": "QUALITATIVE", "weight": 0.4, "questions": [
        { "key":"mgmt_quality","type":"DROPDOWN","label":"Management quality","weight":0.25,
          "options":[{"label":"Strong","score":90},{"label":"Adequate","score":60},{"label":"Weak","score":30}],
          "required":true },
        { "key":"esg","type":"DROPDOWN","label":"ESG posture","weight":0.15,
          "optionsFromMaster":"ESG_BAND",                         // master-driven options
          "visibleWhen":"mgmt_quality != 'Weak'" },               // conditional/visibility
        { "key":"related_parties","type":"ITERATIVE","label":"Related-party exposures",
          "itemFields":[{"key":"name","type":"INPUT"},{"key":"amount","type":"NUMBER"}],
          "min":0,"max":10 }                                      // iterative/repeating
    ]},
    { "key": "QUANTITATIVE", "kind": "QUANTITATIVE", "weight": 0.6, "questions": [
        { "key":"leverage","type":"NUMBER","label":"Net leverage (x)","weight":0.3,
          "scoreBands":[{"max":2,"score":90},{"max":3.5,"score":60},{"max":99,"score":30}] },
        { "key":"dscr","type":"NUMBER","label":"DSCR (x)","weight":0.3,
          "scoreBands":[{"min":1.5,"score":90},{"min":1.25,"score":60},{"min":0,"score":30}] }
    ]}
  ]
}
```

**Resolution by sector/segment**: the generic master resolves by `jurisdiction`
only, so a thin **`GET /config/api/models/resolve?jurisdiction=&sector=&segment=`**
endpoint scans active `MODEL_DEFINITION` records and picks the most-specific
selector match (exact > sector-only > segment-only > default), falling back to a
default model.

### 2.2 Question types
`INPUT` (text) · `NUMBER` · `DROPDOWN` (static options or `optionsFromMaster`) ·
`ITERATIVE` (repeating group of `itemFields`) · (boolean/date trivially as INPUT variants).

### 2.3 Rules
- **Visibility / conditional**: each question may carry `visibleWhen` — a small
  safe expression over other answers (`q == 'X'`, `q != 'X'`, `q in [..]`,
  `answered(q)`, numeric `q >= n`). A hidden question is excluded from
  constraints and scoring.
- **Constraints**: `minAnswered`/`maxAnswered` across visible questions;
  `mandatory[]` must be answered; iterative `min`/`max` cardinality.
- **Master-driven**: `optionsFromMaster: "<TYPE>"` resolves dropdown options
  from any master at render time (reuses the config master client).

### 2.4 Scoring (deterministic, advisory)
Per question → a 0–100 score (dropdown option score, or numeric `scoreBands`).
Per section → weighted mean of its visible answered questions → section band.
Overall → weighted mean of sections → overall band. **Advisory only** — stamped
`audit.ai(...)`, human-confirmed (`audit.human`), and **never mutates the
authoritative grade** (the e2e asserts grade byte-identical, per the platform invariant).

### 2.5 Where it lives (migration of QUAL_SCORECARD)
- **config-service**: `MODEL_DEFINITION` master + resolve endpoint + seeds; **remove** the
  `QUAL_SCORECARD` seed and the `ModelDocumentService` extraction that targets it
  (or retarget it to emit a model). Frontend keeps a builder.
- **risk-service**: replace `QualitativeAssessmentService/Controller/entity/DTO`
  with a `ModelEngine` (resolve → render → answer → evaluate rules → score →
  confirm). New entities `ModelInstance` + `ModelAnswer`. `AiCapability` enum
  value `QUALITATIVE_SCORECARD` → `MODEL_SCORING` (or kept as alias).
- **frontend**: a **Model Builder** (define sections/questions/rules) under
  Configure&Govern, and a **runtime** in RiskLab (answer the resolved model,
  replacing `QualitativeCard`).
- **e2e/docs**: new `scripts/e2e_modelconfig.py`, `run_regression.py` wiring,
  `CLAUDE.md`/docs updates, retire `e2e_qualitative.py` (or rewrite it).

## 3. Honest shape note (important)

This is a **deeply layered greenfield vertical**, not a set of independently
mergeable units. The API can't compile before the entities exist; the frontend
can't compile before the API contract exists; risk-service scoring can't run
before the definition schema exists. Genuine parallel worktrees would only be
independent **after a near-complete foundation has landed** — i.e. most of the
engine has to exist before the "parallel" leaves have anything to attach to.

Given that, and that you chose **"coordinator runs the consolidated e2e + full
regression at the end"** (not per-unit isolated e2e), the cleanest path is the
one that produced the last three clean builds this session (workflow engine,
reporting, currency): **a coherent, dependency-ordered build on the branch**,
verified by one consolidated `e2e_modelconfig.py` + the full 20-suite regression.

## 4. Work units (build sequence — also the review checklist)

| # | Unit | Files | Depends on |
|---|------|-------|-----------|
| 1 | **Definition schema + resolve** | config-service: `MODEL_DEFINITION` seed scaffold, `ModelResolveController`/service; remove `QUAL_SCORECARD` seed | — |
| 2 | **risk engine core** | risk-service: `ModelInstance`/`ModelAnswer` entities+repos, `ModelDefinitionClient`, `ModelEngine` + `ModelController` + DTOs (resolve/render/answer/score/confirm) | 1 |
| 3 | **Rule evaluators** | risk-service `model/rules/*`: visibility/conditional + constraints (min/max/mandatory/iterative cardinality) | 2 |
| 4 | **Option resolver (master-driven + static) + iterative capture** | risk-service `model/resolve/*` | 2 |
| 5 | **Scorer** | risk-service `model/score/WeightedCompositeScorer` (question→section→overall band) | 2 |
| 6 | **Cutover** | remove `QualitativeAssessment*`; repoint `AiCapability`, governance, RiskLab references | 2–5 |
| 7 | **Model seeds** | config-service: corporate-rating-v1 (qual+quant) + a MANUFACTURING-sector + an SME-segment variant | 1 |
| 8 | **Frontend builder** | `frontend/src/pages/ModelBuilder.tsx` + `api.ts` `models` block + `App.tsx` nav | 1,2 |
| 9 | **Frontend runtime** | `RiskLab.tsx` (replace `QualitativeCard` with the model runtime) | 2 |
| 10 | **e2e + docs + regression** | `scripts/e2e_modelconfig.py`, `run_regression.py`, retire `e2e_qualitative.py`, `CLAUDE.md`/docs | all |

## 5. Verification (per your choice: consolidated, not per-unit)

- Each unit: `./mvnw -q -pl <svc> -am package` (backend) or `npx tsc --noEmit` (frontend) must pass.
- **Consolidated e2e** (`scripts/e2e_modelconfig.py`, run by me at the end): boot
  the stack, then assert — model resolves by sector/segment; maker-checker on the
  definition; visibility rule hides a question and excludes it from constraints;
  min/max-answered enforced; master-driven dropdown options resolve; iterative
  group captures N items; weighted composite → band computed; advisory + human
  confirm; **authoritative grade byte-identical before/after** (the invariant).
- **Full regression**: `scripts/run_regression.py` must stay green (now with
  `e2e_modelconfig` and without `e2e_qualitative`), plus the consolidated demo.
