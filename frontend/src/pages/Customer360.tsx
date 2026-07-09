import { useState } from "react";
import { mis, origination, fmt } from "../api";
import { Badge, Card, EmptyState, Field, GradeBadge, Stat, useAsync } from "../ui";

/**
 * Customer-360 — single-borrower view aggregating profile, limits, triggers,
 * financials, ratios, covenants, RAROC, provisioning and industry outlook.
 * Pure read; nothing here is credit-binding.
 */
export default function Customer360() {
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>("");
  const data = useAsync(() => (ref ? mis.customer360(ref) : Promise.resolve(null)), [ref]);

  return (
    <div className="grid">
      <Card title="Customer-360"
        sub="Integrates data across services into a comprehensive borrower view. Select a deal to load.">
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Deal reference">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">— select —</option>
              {(apps.data || []).map((a: any) => (
                <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName}</option>
              ))}
            </select>
          </Field>
        </div>
      </Card>

      {!ref && (
        <Card>
          <EmptyState
            glyph="◍"
            title="Select a deal to load Customer-360"
            sub="Pick an application above. The view aggregates profile, limits, triggers, financials, ratios, RAROC and provisioning across services — read-only, never credit-binding."
          />
        </Card>
      )}
      {data.loading && <Card><div className="loading">Loading…</div></Card>}
      {data.data && <CustomerView c={data.data} />}
    </div>
  );
}

function CustomerView({ c }: { c: any }) {
  const profile = c.borrowerProfile || {};
  const limits = c.limitsAndUtilisation || {};
  const triggers = c.triggersAndBreaches || {};
  const raroc = c.raroc || {};
  const prov = c.provisioning || {};

  return (
    <div className="grid">
      <Card title={profile.counterpartyName || c.reference}
        sub={`${profile.counterpartyRef || "—"} · ${profile.segment || "—"} · ${profile.jurisdiction || "—"}`}>
        <div className="grid cols-4">
          <Stat label="Internal rating" value={<GradeBadge grade={profile.internalRating} />} />
          <Stat label="EAD" value={fmt.money(limits.ead || 0)} />
          <Stat label="RWA" value={fmt.money(limits.rwa || 0)} />
          <Stat label="DPD" value={limits.daysPastDue ?? 0}
            tone={(limits.daysPastDue ?? 0) > 0 ? "var(--bad)" : "var(--ok)"} />
        </div>
      </Card>

      <div className="grid cols-2">
        <Card title="Triggers & breaches" sub="Open EWS signals + recent flags.">
          <div className="inline" style={{ gap: 16 }}>
            <div><span className="muted">Open</span><br /><b>{triggers.openSignals ?? 0}</b></div>
            {Object.entries(triggers.bySeverity || {}).map(([k, v]: any) => (
              <div key={k}>
                <Badge kind={k === "SEVERE" ? "bad" : k === "HIGH" ? "warn" : "info"}>{k}</Badge> {v as number}
              </div>
            ))}
          </div>
          {(triggers.recent || []).length > 0 && (
            <table style={{ marginTop: 10 }}>
              <thead><tr><th>Type</th><th>Severity</th><th>Action</th></tr></thead>
              <tbody>
                {triggers.recent.map((s: any) => (
                  <tr key={s.id}>
                    <td className="mono">{s.signalType}</td>
                    <td><Badge kind={s.severity === "SEVERE" ? "bad" : s.severity === "HIGH" ? "warn" : "info"}>{s.severity}</Badge></td>
                    <td><small className="prov">{s.proposedAction}</small></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title="RAROC" sub="Projected vs actual at the latest period.">
          {raroc.tracked ? (
            <div className="kv">
              <div className="k">Projected</div><div className="v">{fmt.pct(raroc.projected || 0, 2)}</div>
              <div className="k">Latest actual</div><div className="v">{fmt.pct(raroc.latestActual || 0, 2)}</div>
              <div className="k">Variance</div>
              <div className="v" style={{ color: (raroc.latestVariance || 0) < 0 ? "var(--bad)" : "var(--ok)" }}>
                {fmt.pct(raroc.latestVariance || 0, 2)}
              </div>
              <div className="k">Status</div>
              <div className="v">{raroc.materialMiss ? <Badge kind="bad">material miss</Badge> : <Badge kind="ok">within</Badge>}</div>
            </div>
          ) : <div className="muted">Not tracked yet — book the exposure and compute actual RAROC.</div>}
        </Card>
      </div>

      <Card title="Latest financials" sub="From the canonical spread (origination-service).">
        <div className="grid cols-2">
          <div>
            <h4>Income statement</h4>
            <Kvs map={c.financials || {}} keys={["REVENUE", "COGS", "OPERATING_EXPENSES", "EBITDA", "PAT"]} formatter={fmt.money} />
          </div>
          <div>
            <h4>Balance sheet</h4>
            <Kvs map={c.financials || {}} keys={["TOTAL_ASSETS", "CASH", "TOTAL_DEBT", "NET_WORTH", "WORKING_CAPITAL"]} formatter={fmt.money} />
          </div>
        </div>
      </Card>

      <div className="grid cols-2">
        <Card title="Ratios">
          <Kvs map={c.ratios || {}}
            keys={["NET_LEVERAGE", "INTEREST_COVERAGE", "DSCR", "EBITDA_MARGIN", "CURRENT_RATIO", "RETURN_ON_EQUITY"]}
            formatter={(v) => v.toFixed(2)} />
        </Card>
        <Card title="Provisioning"
          sub={(prov as any).policy ? `Policy: ${(prov as any).policy}` : ""}>
          <div className="kv">
            <div className="k">Stage</div><div className="v"><Badge kind={prov.stage === "STAGE_3" ? "bad" : prov.stage === "STAGE_2" ? "warn" : "ok"}>{prov.stage}</Badge></div>
            {prov.reportedProvision != null && (<><div className="k">Reported provision</div><div className="v"><b>{fmt.money(prov.reportedProvision)}</b></div></>)}
          </div>
        </Card>
      </div>

      <Card title="Industry outlook" sub="Benchmark values for the segment from the INDUSTRY_BENCHMARK master.">
        {Object.keys(c.externalOutlook?.industryBenchmark || {}).length === 0
          ? <div className="muted">No benchmark for this segment.</div>
          : <Kvs map={c.externalOutlook.industryBenchmark} keys={Object.keys(c.externalOutlook.industryBenchmark)} formatter={(v) => v.toFixed(2)} />}
      </Card>
    </div>
  );
}

function Kvs({ map, keys, formatter }: { map: Record<string, number>; keys: string[]; formatter: (v: number) => string }) {
  return (
    <div className="kv">
      {keys.filter((k) => map[k] !== undefined).map((k) => (
        <>
          <div className="k" key={k + "k"}>{k}</div>
          <div className="v" key={k + "v"}>{formatter(map[k])}</div>
        </>
      ))}
    </div>
  );
}
