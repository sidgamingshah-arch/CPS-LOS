import { useState } from "react";
import { fmt, origination } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, useAsync } from "../ui";

/**
 * SpreadJS-style financial spreading grid (PRD spreading UI). Rows are taxonomy line
 * items, columns are reporting periods. Each non-derived cell is editable inline;
 * editing a cell beyond the material threshold triggers the override-with-reason gate
 * (server-enforced) and resets the spread to unconfirmed. Cell provenance
 * (source document · page · confidence) is surfaced per cell, derived rows are
 * read-only, and the ratios / trends / benchmark flags render alongside.
 */
const line = (value: number) => ({
  value, sourceDocument: "AuditedFS.pdf", sourcePage: "P&L", coordinates: "auto", confidence: 0.93,
});
const SAMPLE_PERIODS = {
  periods: [
    { label: "FY2024", gaap: "IND_AS", currency: "INR", lines: {
      REVENUE: line(5e9), COGS: line(3.2e9), OPERATING_EXPENSES: line(0.9e9), DEPRECIATION: line(0.2e9),
      INTEREST_EXPENSE: line(0.15e9), TAX: line(0.12e9), TOTAL_ASSETS: line(6e9), CURRENT_ASSETS: line(2.5e9),
      CASH: line(0.6e9), CURRENT_LIABILITIES: line(1.5e9), SHORT_TERM_DEBT: line(0.5e9), LONG_TERM_DEBT: line(1.2e9),
      CURRENT_PORTION_LTD: line(0.2e9), NET_WORTH: line(2.8e9), CFO: line(0.7e9) } },
    { label: "FY2023", gaap: "IND_AS", currency: "INR", lines: {
      REVENUE: line(4.5e9), COGS: line(2.95e9), OPERATING_EXPENSES: line(0.85e9), DEPRECIATION: line(0.18e9),
      INTEREST_EXPENSE: line(0.16e9), TAX: line(0.1e9), TOTAL_ASSETS: line(5.6e9), CURRENT_ASSETS: line(2.3e9),
      CASH: line(0.5e9), CURRENT_LIABILITIES: line(1.45e9), SHORT_TERM_DEBT: line(0.55e9), LONG_TERM_DEBT: line(1.25e9),
      CURRENT_PORTION_LTD: line(0.2e9), NET_WORTH: line(2.5e9), CFO: line(0.6e9) } },
  ],
};

function confTone(c: number) {
  return c >= 0.85 ? "var(--ok)" : c >= 0.6 ? "var(--warn)" : "var(--bad)";
}

export default function Spreading() {
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>("");
  // Level-1 currency view: figures shown in each period's native currency, or
  // restated into the borrower's presentation currency (analyst edits stay native).
  const [ccyView, setCcyView] = useState<"native" | "presentation">("native");
  const analysis = useAsync(() => (ref ? origination.analysis(ref) : Promise.resolve(null)), [ref]);

  const reload = () => analysis.reload();
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); reload(); } catch (e: any) { notify(e.message, true); }
  };

  const a = analysis.data;
  const periods = a?.periods || [];
  const presCcy: string | null = a?.presentationCurrency || null;
  const showPres = ccyView === "presentation";
  // Value shown for a (period, taxonomy key): native cell value, or the
  // presentation-normalised value the server computed (value * fxToPresentation).
  const shownValue = (p: any, key: string, c: any) =>
    showPres && p.presentationValues && p.presentationValues[key] != null
      ? p.presentationValues[key]
      : c.value;
  // Row order from the first period; union of any extra keys from later periods.
  const rowKeys: string[] = [];
  periods.forEach((p: any) => (p.lines || []).forEach((c: any) => {
    if (!rowKeys.includes(c.taxonomyKey)) rowKeys.push(c.taxonomyKey);
  }));
  const cellOf = (p: any, key: string) => (p.lines || []).find((c: any) => c.taxonomyKey === key);
  const labelOf = (key: string) => {
    for (const p of periods) { const c = cellOf(p, key); if (c) return { label: c.label, derived: c.derived }; }
    return { label: key, derived: false };
  };

  const onEdit = async (cell: any, rawValue: string) => {
    const v = parseFloat(rawValue.replace(/,/g, ""));
    if (isNaN(v) || Math.abs(v - cell.value) < 1e-9) return;
    try {
      await origination.override(cell.id, { value: v, reason: undefined }, actor);
      notify("Override applied"); reload();
    } catch (e: any) {
      if (String(e.message).toLowerCase().includes("reason")) {
        const reason = window.prompt("Material override — reason required:");
        if (!reason) { reload(); return; }
        try {
          await origination.override(cell.id, { value: v, reason }, actor);
          notify("Material override applied — re-confirmation required"); reload();
        } catch (e2: any) { notify(e2.message, true); }
      } else { notify(e.message, true); }
    }
  };

  return (
    <div className="grid">
      <Card title="Financial spreading"
        sub="Multi-period grid with cell-level provenance and an analyst override-with-reason gate. Derived lines (grey) are computed; editing an extracted cell beyond the material threshold resets confirmation."
        right={a && <span className="btnrow" style={{ gap: 6 }}>
          {a.financialTemplate && a.financialTemplate !== "(none)" &&
            <Badge kind="info">chart: {a.financialTemplate}</Badge>}
          <Badge kind={a.spreadConfirmed ? "ok" : "warn"}>{a.spreadConfirmed ? "CONFIRMED" : "DRAFT"}</Badge>
        </span>}>
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Deal">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">— select —</option>
              {(apps.data || []).map((d: any) => <option key={d.reference} value={d.reference}>{d.reference} · {d.counterpartyName}</option>)}
            </select>
          </Field>
          <div className="btnrow">
            {ref && periods.length === 0 && !analysis.loading && (
              <Button kind="ghost" onClick={() => run(() => origination.spread(ref, SAMPLE_PERIODS, actor), "Spread generated")}>
                Auto-spread sample financials
              </Button>
            )}
            {ref && periods.length > 0 && (
              <Button disabled={a?.spreadConfirmed}
                onClick={() => run(() => origination.confirmSpread(ref, actor), "Spread confirmed")}>
                {a?.spreadConfirmed ? "Confirmed" : "Confirm spread"}
              </Button>
            )}
          </div>
        </div>
        {ref && (
          <div className="gate" style={{ marginTop: 8 }}>
            Provenance dot colour = extraction confidence (green ≥ 0.85, amber ≥ 0.6, red below). Hover a cell for source
            document · page. Overridden cells carry a badge; material overrides flip the deal back to DRAFT and re-gate confirmation.
          </div>
        )}
      </Card>

      {analysis.loading && <div className="loading">Loading spread…</div>}

      {ref && periods.length > 0 && (
        <Card title="Spread grid" sub={`${rowKeys.length} line items × ${periods.length} period(s)`}
          right={
            presCcy ? (
              <div className="btnrow" style={{ gap: 4 }}>
                <Badge kind={a?.currencyConsistent ? "ok" : "warn"}>
                  {a?.currencyConsistent ? `single currency` : `multi-currency → ${presCcy}`}
                </Badge>
                <button className={`btn ${showPres ? "subtle" : ""}`} style={{ fontSize: 11 }}
                  onClick={() => setCcyView("native")}>Native</button>
                <button className={`btn ${showPres ? "" : "subtle"}`} style={{ fontSize: 11 }}
                  onClick={() => setCcyView("presentation")}>Presentation · {presCcy}</button>
              </div>
            ) : undefined
          }>
          {!a?.currencyConsistent && (
            <div className="gate" style={{ marginTop: 0, marginBottom: 8 }}>
              Periods span multiple currencies — figures are normalised to <strong>{presCcy}</strong> for
              cross-period trends. Toggle the view above; analyst edits always apply in the cell's native currency.
            </div>
          )}
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead>
                <tr>
                  <th>Line item</th>
                  {periods.map((p: any) => (
                    <th key={p.periodId} className="num">{p.label}<br />
                      <small className="prov">
                        {p.gaap} · {showPres && presCcy ? presCcy : p.currency}
                        {showPres && presCcy && p.currency !== presCcy && p.fxToPresentation != null
                          ? ` @ ${p.fxToPresentation}${p.fxRateSource ? " · " + p.fxRateSource.replace("_", " ").toLowerCase() : ""}` : ""}
                      </small>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rowKeys.map((key) => {
                  const meta = labelOf(key);
                  return (
                    <tr key={key}>
                      <td>
                        {meta.label}
                        {meta.derived && <small className="prov"> · derived</small>}
                        <br /><small className="prov mono">{key}</small>
                      </td>
                      {periods.map((p: any) => {
                        const c = cellOf(p, key);
                        if (!c) return <td key={p.periodId} className="num muted">—</td>;
                        if (c.derived) {
                          return <td key={p.periodId} className="num" style={{ color: "var(--muted)" }}>{fmt.money(shownValue(p, key, c), "")}</td>;
                        }
                        // In presentation view, cells are read-only (edits are always native-currency).
                        if (showPres && presCcy && p.currency !== presCcy) {
                          return (
                            <td key={p.periodId} className="num" title={`native ${p.currency} ${Math.round(c.value).toLocaleString()}`}>
                              {fmt.money(shownValue(p, key, c), "")}
                              {c.overridden && <div style={{ textAlign: "right", marginTop: 2 }}>
                                <Badge kind={c.materialOverride ? "bad" : "info"}>{c.materialOverride ? "material override" : "override"}</Badge></div>}
                            </td>
                          );
                        }
                        return (
                          <td key={p.periodId} className="num">
                            <div style={{ display: "flex", alignItems: "center", gap: 6, justifyContent: "flex-end" }}>
                              <span title={`confidence ${(c.confidence * 100).toFixed(0)}%`}
                                style={{ width: 7, height: 7, borderRadius: 999, background: confTone(c.confidence), flex: "0 0 auto" }} />
                              <input
                                defaultValue={Math.round(c.value).toLocaleString()}
                                title={`${c.sourceDocument || "—"} · ${c.sourcePage || ""}`}
                                onBlur={(e) => onEdit(c, e.target.value)}
                                style={{ width: 130, textAlign: "right", padding: "3px 6px" }} />
                            </div>
                            {c.overridden && (
                              <div style={{ textAlign: "right", marginTop: 2 }}>
                                <Badge kind={c.materialOverride ? "bad" : "info"}>
                                  {c.materialOverride ? "material override" : "override"}
                                </Badge>
                              </div>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {ref && periods.length > 0 && (
        <div className="grid cols-2">
          <Card title="Ratios" sub="Computed per period from the confirmed/working spread.">
            <RatiosTable periods={periods} />
          </Card>
          <Card title="Trends & benchmark flags"
            sub={presCcy ? `Cross-period trends computed in ${presCcy} (ratios are currency-agnostic).` : undefined}>
            {a?.trends && Object.keys(a.trends).length > 0 ? (
              <div className="kv">
                {Object.entries(a.trends).map(([k, v]: [string, any]) => (
                  <div key={k} style={{ display: "contents" }}>
                    <div className="k">{k}</div>
                    <div className="v">{fmt.pct(v)}</div>
                  </div>
                ))}
              </div>
            ) : <div className="muted">No trends.</div>}
            {(a?.benchmarkFlags || []).length > 0 && (
              <div className="alert err" style={{ marginTop: 10 }}>{a.benchmarkFlags.join(" · ")}</div>
            )}
          </Card>
        </div>
      )}

      {!ref && (
        <Card>
          <EmptyState
            glyph="▦"
            title="Select a deal to spread its financials"
            sub="Pick an application above. The multi-period grid loads with cell-level provenance; derived lines compute automatically, and overriding an extracted cell resets confirmation."
          />
        </Card>
      )}
      {ref && periods.length === 0 && !analysis.loading && (
        <Card>
          <EmptyState
            glyph="▦"
            title="No spread on this deal yet"
            sub="Auto-spread the sample financials above to populate the grid. You can also upload statements on Doc Intelligence first."
          />
        </Card>
      )}
    </div>
  );
}

function RatiosTable({ periods }: { periods: any[] }) {
  const ratioKeys: string[] = [];
  periods.forEach((p) => Object.keys(p.ratios || {}).forEach((k) => { if (!ratioKeys.includes(k)) ratioKeys.push(k); }));
  if (ratioKeys.length === 0) return <div className="muted">No ratios.</div>;
  return (
    <table>
      <thead>
        <tr><th>Ratio</th>{periods.map((p) => <th key={p.periodId} className="num">{p.label}</th>)}</tr>
      </thead>
      <tbody>
        {ratioKeys.map((k) => (
          <tr key={k}>
            <td className="mono">{k}</td>
            {periods.map((p) => {
              const v = (p.ratios || {})[k];
              return <td key={p.periodId} className="num">{v == null ? "—" : fmt.num(v)}</td>;
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
