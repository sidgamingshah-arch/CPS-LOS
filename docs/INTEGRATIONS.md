# Helix — Integration & Ingestion Contracts

This describes the canonical data structures Helix expects from external source
systems (PRD §8) and the connector pattern that ingests them. Every connector is
**idempotent**, stamps **provenance** on each figure, and **surfaces failures** as
warnings rather than dropping them.

## The connector pattern (`com.helix.common.ingest`)

```
Envelope<RAW>            inbound wrapper: { source, vendor, idempotencyKey, payloadVersion, payload }
   │
   ▼
Connector<RAW, CANON>    validate(raw) -> warnings ;  map(raw, provenance) -> canonical object
   │
   ▼
IngestionGuard           idempotency: (source, idempotencyKey) seen before? -> replay is a no-op
   │
   ▼
domain persistence       canonical object -> service entities, audited
   │
   ▼
Ingestion.Result         { accepted, duplicate, source, idempotencyKey, canonicalRef, message, warnings }
```

- **Provenance** `{ sourceSystem, vendor, sourceReference, payloadVersion, retrievedAt }` is attached
  to every canonical record, feeding the figure→source→version trace (PRD §7/§13).
- **Idempotency** is enforced by a shared `ingestion_records` table (unique on
  `source + idempotencyKey`); a replayed payload returns `duplicate=true` and is not re-applied.
- **Failure surfacing**: validation issues and reconciliation mismatches come back in
  `warnings`; they are never silently dropped.

## Canonical schemas (`com.helix.common.ingest.Canonical`)

| Source system | Canonical type | Key fields |
|---|---|---|
| `CREDIT_BUREAU` | `BureauReport` | creditScore, scoreModel, inquiriesLast6m, delinquenciesLast24m, openTradelines, totalOutstanding |
| `CORPORATE_REGISTRY` | `RegistryRecord` | legalName, registrationNo, legalForm, incorporationDate, status, directors[], ownership[] (edges) |
| `GST_TAX` | `GstRecord` | gstin, annualTurnover, filingStatus, lastReturnPeriod |
| `SANCTIONS_SCREENING` | `ScreeningResult` | hits[] { listSource, matchedName, matchScore, severity, matchedAttributes } |
| `CORE_BANKING` | `CoreBankingPosition` | facilityRef, limit, drawn, undrawn, daysPastDue, conductScore, status |
| `MARKET_DATA` | `MarketDataPoint` | instrument, asOf, value, unit |

Each carries a `Provenance`. These are the **expected data structures** source data is
mapped onto; raw vendor payloads are mapped to them by a `Connector`.

## Wired reference adapters

### 1. Sanctions/screening vendor → counterparty (`counterparty-service`)

`POST /counterparty/api/counterparties/{id}/ingest/screening`

```jsonc
{
  "source": "SANCTIONS_SCREENING",
  "vendor": "WorldCheck",
  "idempotencyKey": "WC-42-001",
  "payloadVersion": "2024-06",
  "payload": {
    "entityName": "Meridian Steel Pvt Ltd",
    "matches": [
      { "list": "OFAC", "name": "Meridian Steel Pvt Ltd", "score": 0.71, "risk": "HIGH", "fields": ["name", "country:IN"] },
      { "list": "PEP",  "name": "Meridian Steel Pvt Ltd", "score": 0.55, "risk": "MEDIUM", "fields": ["name"] }
    ]
  }
}
```

`ScreeningConnector` maps vendor risk → canonical severity (`CRITICAL→SEVERE`, …) and produces
`ScreeningHit` rows (disposition `OPEN`, provenance-stamped). Replaying the same `idempotencyKey`
returns `duplicate=true` and creates no new hits. This is the real ingestion path the simulated
`/screening/run` stands in for.

### 2. Core-banking conduct/booking → exposure (`portfolio-service`)

`POST /portfolio/api/portfolio/exposures/{reference}/ingest/core-banking`

```jsonc
{
  "source": "CORE_BANKING",
  "vendor": "Finacle",
  "idempotencyKey": "FIN-HLX-2026-XXXX-2026Q2",
  "payloadVersion": "v1",
  "payload": {
    "facilityRef": "FAC-…", "sanctionedLimit": 800000000, "outstanding": 800000000,
    "currency": "INR", "overdueDays": 0, "conductRating": 0.9, "accountStatus": "ACTIVE"
  }
}
```

`CoreBankingConnector` maps to `CoreBankingPosition` and the service updates the exposure's
`daysPastDue`/`status`, performing **round-trip reconciliation** against the booked EAD — a
drawn-vs-EAD divergence is surfaced as a warning. Idempotent on the key.

## Contract-only sources (schemas defined, adapters not yet wired)

`CREDIT_BUREAU`, `CORPORATE_REGISTRY`, `GST_TAX` and `MARKET_DATA` have canonical schemas and
the `Connector` contract in `helix-common`, ready to wire. A bureau adapter would enrich the
counterparty; a registry adapter would populate identity + the declared UBO structure; market
data would feed SA-CCR/CVA. None require core changes — each is a new `Connector` + a thin
ingestion service following the two reference adapters above.
