# Design — Ad-hoc / Self-Service Reporting Layer

> Status: **proposed / build-ready**. Author hand-off spec for a user-defined
> report builder over the portfolio book. Companion to `docs/ARCHITECTURE.md`.

## 1. Objective & non-goals

**Objective.** Let a credit/portfolio user *define a report as data* — pick a
dataset, dimensions to group by, measures to aggregate, and filters — save it
under maker-checker, and execute it to a deterministic table, **without writing
code or SQL**. Today reporting is eight fixed MIS endpoints plus three canonical
export feeds; everything else needs a code change.

**Stance: reuse the master engine + add a guarded aggregation executor.** Report
*definitions* become a new `REPORT_DEFINITION` master type — inheriting
maker-checker, versioning, SoD, jurisdiction scoping, the master-admin UI, and
audit *for free* from the generic Master-Data engine (`MasterRecord`,
`config-service`). A new deterministic `ReportEngine` in portfolio-service runs a
definition over a **whitelisted dataset registry**. The certified fixed MIS
views stay exactly as-is; ad-hoc sits beside them for exploratory/management use.

**Non-goals (v1):**
- **No raw SQL / arbitrary field access.** The dataset registry is a whitelist;
  this is the injection and data-exposure boundary.
- **No cross-service joins.** v1 datasets are single-entity (portfolio book,
  RAROC, EWS). Cross-service analytics stays with the canonical export feeds.
- **No new figures.** The engine only *aggregates* system-of-record values
  (deterministic, no AI in the path) — same governance posture as `MisService`.
- The canonical exports (`ErmRiskRecord`/`FinanceProvisionEntry`/`CprPortfolioLine`)
  are downstream **contracts** and are out of scope — they are not "reports".

## 2. Current state (verified)

| Surface | Today | Evidence |
|---|---|---|
| MIS / dashboards | 8 **fixed** endpoints, zero filter params (one no-op `rm` stub) | `portfolio-service/.../api/MisController.java:26-64`; groupings hardcoded in `MisService.bookComposition():42-61` |
| Export feeds | 3 **rigid** canonical schemas | `helix-common/.../export/Export.java` (15/7/4 fixed fields) |
| Report builder / saved reports / field picker | **None** | no `ReportDefinition`/`QueryBuilder` entity or endpoint anywhere; no builder UI |

The queryable book today (`ExposureRecord`, `portfolio-service/.../entity/ExposureRecord.java`):
`counterpartyName, jurisdiction, segment, sector, facilityType, tenorMonths,
groupRef, finalGrade, pd, lgd, ead, rwa, capitalRequired, currency,
daysPastDue, status`. These are the v1 dimensions/measures.

## 3. Report-definition model (zero new persistence)

A saved report is a `MasterRecord` with `masterType = "REPORT_DEFINITION"`,
`recordKey = <report id>`, and this `payload` (stored via the existing
`MapConverter`, well within the 16 000-char column):

```jsonc
{
  "title": "Sub-investment-grade EAD by segment",
  "dataset": "EXPOSURE_BOOK",
  "dimensions": ["segment", "finalGrade"],          // group-by columns
  "measures": [
    { "field": "ead", "agg": "SUM",   "as": "totalEad" },
    { "field": "*",   "agg": "COUNT", "as": "deals" }
  ],
  "filters": [
    { "field": "finalGrade", "op": "IN",  "value": ["BB","B","CCC","CC","C","D"] },
    { "field": "jurisdiction","op": "EQ", "value": "IN-RBI" }
  ],
  "sort": [ { "by": "totalEad", "dir": "DESC" } ],
  "limit": 100
}
```

Because it's a `MasterRecord`, it automatically gets: maker→checker approval
with SoD, version history, `ACTIVE/PENDING_APPROVAL` lifecycle, optional
`jurisdiction` scoping, and the existing master-admin screen — **no new CRUD
endpoints or entity required.** Definitions are created/edited via the existing
`POST /config/api/masters/REPORT_DEFINITION` + approve flow.

## 4. Dataset registry (the security boundary)

A code-declared whitelist mapping `dataset` → repository + allowed fields +
types + allowed aggregations. Nothing outside the registry is queryable.

```java
enum FieldType { STRING, NUMBER, INT, ENUM }

record FieldSpec(String name, FieldType type, boolean dimension, boolean measure) {}

record DatasetSpec(String key, String label,
                   Supplier<List<Map<String,Object>>> rows,   // projection of the entity
                   Map<String, FieldSpec> fields) {}
```

v1 registry:

| dataset | source | dimensions | measures |
|---|---|---|---|
| `EXPOSURE_BOOK` | `ExposureRecordRepository.findAll()` | segment, finalGrade, jurisdiction, sector, facilityType, status, currency, tenorMonths(bucketed) | ead, rwa, capitalRequired, pd, lgd, daysPastDue, count |
| `RAROC_TRACKING` | `RarocTrackingRepository` | origination?, segment | variance, absVariancePct, count |
| `EWS_SIGNALS` | `EwsSignalRepository` | signalType, severity | count |

The executor rejects any `dimension`/`measure`/`filter.field` not in the
spec, and any `agg`/`op` not allowed for that field's type, with a 400. This is
what makes "self-service" safe without exposing arbitrary querying.

## 5. Aggregation executor (deterministic, read-only)

```java
@Service
class ReportEngine {
  ReportResult run(ReportDefinition def, Map<String,Object> runtimeFilters) {
    DatasetSpec ds = registry.require(def.dataset());          // 400 if unknown
    validate(def, ds);                                         // whitelist + type checks
    List<Map<String,Object>> rows = ds.rows().stream()
        .filter(r -> matches(r, mergeFilters(def.filters(), runtimeFilters), ds))
        .toList();
    Map<List<Object>, Accumulators> grouped = groupBy(rows, def.dimensions());
    List<Object[]> out = reduce(grouped, def.measures());      // SUM/COUNT/AVG/MIN/MAX
    sortAndLimit(out, def.sort(), def.limit());
    return new ReportResult(columns(def), out, grandTotals(out, def));
  }
}
```

- **Filter ops by type:** STRING/ENUM → `EQ, NE, IN`; NUMBER/INT → `EQ, NE, GT,
  GTE, LT, LTE, BETWEEN`. `tenorMonths` exposes a derived bucket dimension
  (`0-12, 13-36, 37-60, 60+`) mirroring the existing concentration buckets.
- **Measures:** `SUM, AVG, MIN, MAX` on NUMBER/INT fields; `COUNT` on `*`.
- **Result shape:** `{ columns:[{key,label,type}], rows:[[…]], totals:{…} }` —
  directly renderable as a table; `totals` lets the UI show a footer row.
- **No AI, no mutation.** Pure read aggregation, exactly like `MisService`.

## 6. API (portfolio-service)

| Verb | Path | Purpose |
|---|---|---|
| `GET` | `/api/reports/datasets` | Registry metadata for the builder (datasets, fields, types, allowed aggs/ops). |
| `POST` | `/api/reports/run` | Run an **inline** definition (builder live preview). Body = the definition JSON. |
| `GET` | `/api/reports/{key}/run?<runtime filters>` | Run a **saved** `REPORT_DEFINITION` (fetched from config-service via a `ReportDefinitionClient` mirroring `UpstreamClient.masters`). Runtime filters layer on top of saved ones. |
| `POST` | `/api/reports/{key}/export` | Snapshot a run as a CSV `ExportBatch` (reuse the export contract; idempotent per as-of day). |

Definitions themselves are written through the existing master endpoints — the
reporting service only *reads* and *executes* them.

## 7. Governance & RBAC

- **Deterministic figures:** every value is summed from the system-of-record
  book; no GenAI in the path. Stamp `audit.engine("REPORT_EXECUTED", …)` on each
  run with the definition key + row count + filter digest.
- **Definitions are human-authored + approved:** maker-checker via the master
  engine; a report can't go `ACTIVE` without a second approver (SoD inherited).
- **RBAC:** add `ProtectedAction.REPORT_RUN` (execute) and `REPORT_DEFINE`
  (author drafts) to `helix-common/.../rbac/ProtectedAction.java`; gate the run
  endpoints with `roles.require(actor, ProtectedAction.REPORT_RUN)` exactly as
  `DisbursementService` does.
- **Field whitelist + row cap** (default 5 000 scanned, configurable) are the
  data-exposure and performance guards.

## 8. Performance

SQLite, single writer, current book in the hundreds–low-thousands of exposures:
in-memory aggregation over `findAll()` is comfortably fast and keeps v1 simple.
Path to scale (documented, not built in v1): push filters/group-by into a JPA
`Criteria`/native `GROUP BY` per dataset when row counts grow, behind the same
`DatasetSpec.rows()` seam — the API and UI don't change.

## 9. Frontend — Report Builder

New page `frontend/src/pages/ReportBuilder.tsx`:
1. **Pick dataset** → fields load from `/reports/datasets`.
2. **Dimensions** and **measures** as add/remove chips; **filters** as
   `field · op · value` rows with type-aware inputs.
3. **Live preview** table via `POST /reports/run` (debounced).
4. **Save** → submits a `REPORT_DEFINITION` master *draft* (PENDING_APPROVAL);
   a second user approves it on the existing master-admin screen.
5. **Saved Reports** list → run / export each.

Reuse `frontend/src/ui.tsx`: `DeterministicBadge` on the result (figures are
deterministic), `HumanBadge` on the definition (human-authored + approved). No
new tokens.

## 10. Phasing & effort

| Phase | Scope | Risk | Est. |
|---|---|---|---|
| **1 — Engine + builder preview** | Dataset registry (`EXPOSURE_BOOK`), `ReportEngine`, `/datasets` + `/run`, Report Builder page with live preview (no saving yet). | Low | ~4–5 dev-days |
| **2 — Saved defs + governance** | `REPORT_DEFINITION` master wiring, `/{key}/run`, RBAC actions, maker-checker, `REPORT_EXECUTED` audit. | Low–Med | ~3 dev-days |
| **3 — Export + more datasets** | CSV `ExportBatch` snapshot, `RAROC_TRACKING`/`EWS_SIGNALS` datasets, tenor/duration buckets, optional scheduled runs. | Med | ~3–4 dev-days |

## 11. e2e contract (`scripts/e2e_reporting.py`)

The headline assertion is a **deterministic cross-check** that proves the ad-hoc
engine returns the same authoritative numbers as the certified fixed views:

- Define & run `{dataset:EXPOSURE_BOOK, dimensions:[segment], measures:[SUM ead]}`
  → assert the per-segment totals **equal** `GET /api/mis/composition.bySegment`
  (byte-for-byte on the deterministic figures).
- Filter `finalGrade IN (BB,B,CCC,…)` → assert the row set matches a manual
  count over the seeded book.
- Submit a definition with an **unknown field** → expect **400** (whitelist).
- Submit `AVG` on a STRING field → **400** (type guard).
- Run with a **blank `X-Actor`** once RBAC lands → **403**.
- Save a `REPORT_DEFINITION` and self-approve → **403** (SoD via master engine).

## 12. Risks & mitigations

- *Becomes a SQL-injection surface* → no SQL; whitelist registry + typed ops are
  the boundary; never interpolate field names into a query string.
- *Heavy scans on a growing book* → row cap + the documented Criteria-pushdown
  path behind the `DatasetSpec` seam.
- *Drift from certified MIS numbers* → the e2e cross-check (§11) pins ad-hoc
  output to the fixed `composition` view; both read the same `ExposureRecord`.
- *Users mistake ad-hoc for regulatory reports* → UI labels ad-hoc results
  "management view"; the canonical export feeds remain the regulatory record.
