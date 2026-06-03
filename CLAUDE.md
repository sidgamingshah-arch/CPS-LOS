# Helix — notes for Claude Code

AI-first wholesale loan origination platform. Java 21 / Spring Boot microservices,
SQLite-per-service, React + Vite + TS front end, Spring Cloud Gateway.

## Layout
- `helix-common/` — shared lib: canonical `Enums`, append-only audit, JSON converters, web config.
- `config-service` (8081) — abstraction layer: jurisdiction profiles + versioned rule packs.
- `counterparty-service` (8082) — KYC/CDD, UBO graph, screening.
- `origination-service` (8083) — applications, document classification, financial spreading.
- `risk-service` (8084) — rating, capital (RWA), pricing (RAROC). Calls config + origination.
- `decision-service` (8085) — DoA approval workflow, covenants. Calls config/origination/risk.
- `portfolio-service` (8086) — ECL/IRAC, EWS, concentration, stress. Calls all upstreams.
- `gateway-service` (8080) — routes `/{service}/**` to each service.
- `frontend/` — React app (calls the gateway; `VITE_GATEWAY_URL`, default `http://localhost:8080`).

## Build / run / test
```bash
mvn -DskipTests package          # build all jars
bash scripts/run-all.sh          # start every service (health-gated)
python3 scripts/e2e_smoke.py     # full-lifecycle integration test (expects 45 passed)
bash scripts/stop-all.sh         # stop
cd frontend && npm install && npm run dev   # UI on :5173
docker compose up --build        # full stack (UI :8088, gateway :8080)
```

## Conventions
- One SQLite DB per service in `$HELIX_DATA_DIR` (default `./data`), Hibernate `ddl-auto=update`.
- Hikari pool size is **1** (SQLite single-writer); audit writes **join** the caller's tx
  (do not use `REQUIRES_NEW` — it deadlocks the one-connection pool).
- Entities use Lombok `@Getter/@Setter`; DTOs are Java records. Each `@SpringBootApplication`
  scans `com.helix` so shared audit/web beans are picked up.
- Write actions take an `X-Actor` header → recorded in the immutable audit trail.
- Regime-specific values live in rule packs (config-service), never in code branches.
