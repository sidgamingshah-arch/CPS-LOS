/**
 * Financial projections — a multi-year proforma (P&L / cashflow / debt-service /
 * projected DSCR) built from the deal's base-year actuals × analyst driver
 * assumptions × the resolved PROJECTION_TEMPLATE. Deterministic + advisory: the
 * grid never moves the authoritative rating/capital/pricing.
 */
import { useEffect, useState } from "react";
import { origination, projections } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, DeterministicBadge, EmptyState, Field, GovFlow, useAsync } from "../ui";

const fmtN = (v: any) =>
  typeof v === "number" ? v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : "—";

export default function Projections() {
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState("");
  const [view, setView] = useState<any>(null);
  const [draft, setDraft] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState(false);
  const [sens, setSens] = useState<any>(null);
  const [sensDriver, setSensDriver] = useState("");
  const [sensDelta, setSensDelta] = useState("-0.05");

  const load = async (r: string) => {
    if (!r) { setView(null); return; }
    try {
      const v = await projections.view(r);
      setView(v); setSens(null);
      setDraft(Object.fromEntries(v.drivers.map((d: any) => [d.key, d.value])));
      if (v.drivers[0]) setSensDriver(v.drivers[0].key);
    } catch (e: any) { notify(e.message, true); setView(null); }
  };
  useEffect(() => { load(ref); /* eslint-disable-next-line */ }, [ref]);

  const applyDrivers = async () => {
    try { setBusy(true); const v = await projections.setDrivers(ref, draft, actor); setView(v); setSens(null); notify("Projection recomputed"); }
    catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  };
  const runSensitivity = async () => {
    try { setBusy(true); setSens(await projections.sensitivity(ref, sensDriver, Number(sensDelta), actor)); }
    catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  };
  const confirm = async () => {
    try { setBusy(true); const v = await projections.confirm(ref, actor); setView(v); notify("Projection confirmed"); }
    catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  };

  return (
    <div className="grid">
      <div className="gov-banner">
        <strong>FINANCIAL PROJECTIONS</strong>
        <span style={{ marginLeft: 12 }}>
          Multi-year proforma from base-year actuals × driver assumptions × a configurable
          PROJECTION_TEMPLATE. Deterministic and <em>advisory</em> — it never moves the
          authoritative rating, capital or pricing.
        </span>
        <span style={{ marginLeft: 16 }}>
          <GovFlow ai="USER · ASSUMPTIONS" human="HUMAN · CONFIRMS" note="PROFORMA DETERMINISTIC" />
        </span>
      </div>

      <Card title="Deal">
        <Field label="Application">
          <select value={ref} onChange={(e) => setRef(e.target.value)}>
            <option value="">— select a deal —</option>
            {(apps.data || []).map((a: any) => (
              <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName}</option>
            ))}
          </select>
        </Field>
      </Card>

      {!ref && <EmptyState glyph="📈" title="Select a deal" sub="Projections seed from the deal's spread (base-year actuals)." />}

      {view && (
        <>
          <Card title={`Projection · ${view.templateKey} v${view.templateVersion}`}
            sub={`${view.horizonYears}-year proforma; base year from the spread.`}
            right={<span className="btnrow" style={{ gap: 6 }}>
              <Badge kind={view.status === "CONFIRMED" ? "ok" : "warn"}>{view.status}</Badge>
              <Badge kind="ok">grade {view.authoritativeGrade ?? "—"} · UNCHANGED</Badge>
              <DeterministicBadge label="DETERMINISTIC" />
            </span>}>
            <div className="grid cols-3">
              {view.drivers.map((d: any) => (
                <Field key={d.key} label={`${d.label} (default ${d.defaultValue})`}>
                  <input type="number" step="0.01" value={draft[d.key] ?? d.value}
                    onChange={(e) => setDraft({ ...draft, [d.key]: Number(e.target.value) })} />
                </Field>
              ))}
            </div>
            <div className="btnrow" style={{ marginTop: 8 }}>
              <Button kind="primary" busy={busy} onClick={applyDrivers}>Recompute</Button>
              <Button kind="subtle" busy={busy} disabled={view.status === "CONFIRMED"} onClick={confirm}>Confirm projection</Button>
            </div>
          </Card>

          <Card title="Proforma" sub="Base year (actuals) then projected years.">
            <div style={{ overflowX: "auto" }}>
              <table>
                <thead><tr><th>Line</th><th className="num">Base</th>
                  {view.years.map((y: any) => <th key={y.year} className="num">Y{y.year}</th>)}</tr></thead>
                <tbody>
                  {view.lineKeys.map((k: string, i: number) => (
                    <tr key={k}>
                      <td>{view.lineLabels[i]} <small className="prov mono">{k}</small></td>
                      <td className="num">{fmtN(view.baseYear[k])}</td>
                      {view.years.map((y: any) => (
                        <td key={y.year} className="num"
                          style={k === "DSCR" ? { fontWeight: 600 } : undefined}>{fmtN(y.values[k])}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Card title="Sensitivity" sub="Flex one driver and see the projected-DSCR move (advisory).">
            <div className="btnrow" style={{ gap: 6, alignItems: "end" }}>
              <Field label="Driver">
                <select value={sensDriver} onChange={(e) => setSensDriver(e.target.value)}>
                  {view.drivers.map((d: any) => <option key={d.key} value={d.key}>{d.label}</option>)}
                </select>
              </Field>
              <Field label="Delta (absolute)"><input type="number" step="0.01" value={sensDelta} onChange={(e) => setSensDelta(e.target.value)} /></Field>
              <Button kind="subtle" busy={busy} onClick={runSensitivity}>Run</Button>
            </div>
            {sens && (
              <div className="prov" style={{ marginTop: 8 }}>
                {sens.driver} {sens.baseValue} → {sens.flexedValue}: final-year DSCR
                <b> {fmtN(sens.baseFinalDscr)} → {fmtN(sens.flexedFinalDscr)}</b>
                {" "}<Badge kind={sens.flexedFinalDscr < sens.baseFinalDscr ? "warn" : "ok"}>
                  {(sens.flexedFinalDscr - sens.baseFinalDscr >= 0 ? "+" : "")}{fmtN(sens.flexedFinalDscr - sens.baseFinalDscr)}</Badge>
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
