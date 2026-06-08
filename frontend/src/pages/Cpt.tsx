/**
 * Client Planning Template (CPT) workspace.
 *
 * Per-counterparty advisory plan: client overview, exposure snapshot, cross-sell
 * whitespace, 3-scenario wallet sizing, industry insights, peer / whitespace,
 * completeness nudges. AI drafts; an RM confirms. Deterministic figures (rating,
 * pricing) are quoted verbatim — never mutated.
 */
import { useEffect, useMemo, useState } from "react";
import { counterparty, cpt as cptApi, fmt } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge,
  Badge,
  Button,
  Card,
  Field,
  GovFlow,
  Stat,
  Unchanged,
  useAsync,
} from "../ui";

export default function Cpt() {
  const { actor, notify } = useApp();
  const list = useAsync(() => counterparty.list(), []);
  const [ref, setRef] = useState<string>("");
  const [trend, setTrend] = useState<string>("");
  const [generating, setGenerating] = useState(false);

  // Auto-select first obligor when the list loads
  useEffect(() => {
    if (!ref && list.data && list.data.length > 0) {
      const first = list.data.find((c: any) => c.recordType === "OBLIGOR") || list.data[0];
      setRef(first.reference);
    }
  }, [list.data, ref]);

  const latest = useAsync<any>(() => (ref ? cptApi.latest(ref) : Promise.resolve(null)), [ref]);
  const versions = useAsync<any[]>(() => (ref ? cptApi.versions(ref) : Promise.resolve([])), [ref]);

  const generate = async () => {
    if (!ref) return;
    const body: any = {};
    if (trend.trim() !== "") {
      const parsed = Number(trend);
      if (!Number.isFinite(parsed)) {
        notify(`Trend must be a number (got '${trend}') — clear the field for default 10%`, true);
        return;
      }
      body.trendFactorOverride = parsed / 100;     // user enters % (e.g. 12 → 0.12)
    }
    setGenerating(true);
    try {
      const out = await cptApi.generate(ref, body, actor);
      notify(`CPT v${out.version} generated`);
      latest.reload();
      versions.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setGenerating(false);
    }
  };

  const review = async (id: number, approve: boolean) => {
    const note = window.prompt(approve ? "Approval note (optional):" : "Rejection reason:");
    if (approve === false && note === null) return;
    try {
      await cptApi.review(id, { approve, note: note ?? "" }, actor);
      notify(approve ? "CPT confirmed" : "CPT rejected");
      latest.reload();
      versions.reload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const cp = useMemo(
    () => (list.data ?? []).find((c: any) => c.reference === ref),
    [list.data, ref],
  );

  const t = latest.data;
  const ws = t?.walletSizing ?? {};
  const scenarios = (ws.scenarios ?? []) as any[];
  const indic = ws.indicativeWallet;
  const ind = t?.industryInsights ?? {};

  return (
    <div className="grid">
      <div className="gov-banner">
        <h3>Plan the relationship. Figures stay deterministic.</h3>
        <div className="gb-sub">
          Auto-drafted from the counterparty record, every live application's envelope, the
          risk-service rating/pricing/RAROC, and the immutable audit trail. The RM signs the plan;
          rating, capital and pricing are <b>not</b> mutated by this module.
        </div>
        <div className="gb-chips">
          <span className="gb-chip"><b>AI · ADVISORY</b> wallet sizing + nudges</span>
          <span className="gb-chip"><b>HUMAN-GATED</b> RM sign-off</span>
          <span className="gb-chip"><b>DETERMINISTIC FIGURES</b> grade · capital · pricing · unchanged</span>
        </div>
      </div>

      {/* ── Counterparty selector + generate ── */}
      <Card title="Counterparty"
        sub="Pick the obligor or prospect and generate the CPT. Optional trend override drives the MOST_LIKELY wallet scenario."
        right={<GovFlow ai="AI DRAFTS" human="RM CONFIRMS" />}>
        <div className="grid cols-2">
          <Field label="Counterparty">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">— select counterparty —</option>
              {(list.data ?? []).map((c: any) => (
                <option key={c.id} value={c.reference}>
                  {c.reference} · {c.legalName} · {c.recordType}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Trend override (MOST_LIKELY, %)">
            <input type="number" value={trend} onChange={(e) => setTrend(e.target.value)} placeholder="leave blank for default 10%" />
          </Field>
        </div>
        <div className="btnrow" style={{ marginTop: 8 }}>
          <Button onClick={generate} busy={generating} disabled={!ref}>
            {t ? "Re-generate (new version)" : "Generate CPT"}
          </Button>
          {t && (
            <>
              <Badge kind={t.status === "CONFIRMED" ? "ok" : t.status === "REJECTED" ? "bad" : "ai"}>
                v{t.version} · {t.status}
              </Badge>
              {t.status === "DRAFT" && (
                <>
                  <Button kind="ghost" onClick={() => review(t.id, true)}>Approve</Button>
                  <Button kind="subtle" onClick={() => review(t.id, false)}>Reject</Button>
                </>
              )}
            </>
          )}
        </div>
      </Card>

      {!t && ref && !latest.loading && (
        <Card title="No CPT on file">
          <div className="muted">No CPT for {ref} yet — click <b>Generate CPT</b> above.</div>
        </Card>
      )}

      {t && (
        <>
          {/* Snapshot */}
          <Card title="Client snapshot"
            right={<><AiBadge label="AI · ADVISORY rollup" /> <Unchanged label="MEMBER FIGURES UNCHANGED" /></>}>
            <div className="grid cols-3">
              <Stat label="Latest grade" value={t.latestGrade ?? "—"} />
              <Stat label="Weighted PD" value={t.weightedAveragePd == null ? "—" : fmt.pct(t.weightedAveragePd, 3)} />
              <Stat label="Weighted RAROC" value={t.weightedAverageRaroc == null ? "—" : fmt.pct(t.weightedAverageRaroc)} />
              <Stat label="Applications" value={t.applicationCount} />
              <Stat label="Facilities" value={t.facilityCount} />
              <Stat
                label="Exposure"
                value={
                  Object.keys(t.exposureByCurrency ?? {}).length === 0
                    ? "—"
                    : Object.entries<any>(t.exposureByCurrency).map(([c, v]) => `${fmt.money(v, "")} ${c}`).join(" + ")
                }
              />
              <Stat label="Sector" value={cp?.sector ?? t.sector ?? "—"} />
              <Stat label="Country" value={cp?.country ?? t.country ?? "—"} />
              <Stat label="RM" value={t.rmId ?? "—"} />
            </div>
          </Card>

          {/* Cross-sell */}
          <Card title="Relationship surface · cross-sell whitespace">
            <div className="muted" style={{ fontSize: 13, marginBottom: 4 }}>Currently used products</div>
            <div className="btnrow">
              {(t.currentFacilityTypes ?? []).length === 0
                ? <span className="muted">none yet</span>
                : (t.currentFacilityTypes ?? []).map((p: string) => <Badge key={p} kind="info">{p}</Badge>)}
            </div>
            <div className="muted" style={{ fontSize: 13, marginTop: 12, marginBottom: 4 }}>Catalogue whitespace (heuristic)</div>
            <div className="btnrow">
              {(t.potentialCrossSell ?? []).map((p: string) => <Badge key={p} kind="ai">{p}</Badge>)}
            </div>
          </Card>

          {/* Wallet sizing */}
          <Card title="Wallet sizing (3 scenarios, heuristic)"
            sub={`Base revenue ${ws.baseRevenue == null ? "—" : fmt.money(ws.baseRevenue, "")} · MOST_LIKELY trend ${ws.trendFactor == null ? "—" : fmt.pct(ws.trendFactor)} · 3-year horizon`}>
            {scenarios.length === 0 ? (
              <div className="muted">No spread on file — scenarios require a confirmed EBITDA margin to back out base revenue.</div>
            ) : (
              <table>
                <thead><tr><th>Scenario</th><th>Δ%</th><th>Y1</th><th>Y2</th><th>Y3</th><th>Commentary</th></tr></thead>
                <tbody>
                  {scenarios.map((s) => (
                    <tr key={s.label}>
                      <td><b>{s.label}</b></td>
                      <td className="mono">{fmt.pct(s.deltaPct)}</td>
                      {[0, 1, 2].map((i) => (
                        <td key={i} className="num">{s.projectedRevenue ? fmt.money(s.projectedRevenue[i], "") : "—"}</td>
                      ))}
                      <td>{s.commentary}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {indic && (
              <div className="alert info" style={{ marginTop: 10 }}>
                <b>Indicative wallet:</b> {fmt.money(indic.amount, "")} · {indic.basis}
              </div>
            )}
          </Card>

          {/* Industry insights */}
          <Card title={`Industry & region · ${ind.sector ?? "—"} · ${ind.country ?? "—"}`}>
            <div className="grid cols-2">
              <div>
                <div className="muted" style={{ fontSize: 13, marginBottom: 4 }}>Headwinds</div>
                <ul style={{ margin: 0, lineHeight: 1.7 }}>
                  {(ind.headwinds ?? []).map((h: string, i: number) => <li key={i}>{h}</li>)}
                </ul>
              </div>
              <div>
                <div className="muted" style={{ fontSize: 13, marginBottom: 4 }}>Tailwinds</div>
                <ul style={{ margin: 0, lineHeight: 1.7 }}>
                  {(ind.tailwinds ?? []).map((h: string, i: number) => <li key={i}>{h}</li>)}
                </ul>
              </div>
            </div>
            {ind.note && <div className="muted" style={{ marginTop: 8, fontSize: 12 }}>{ind.note}</div>}
          </Card>

          {/* Peer / whitespace */}
          <Card title="Peer & whitespace">
            <ul style={{ margin: 0, lineHeight: 1.7 }}>
              {(t.peerInsights ?? []).map((p: string, i: number) => <li key={i}>{p}</li>)}
            </ul>
          </Card>

          {/* Completeness nudges */}
          <Card title="Completeness nudges (RM action items)"
            right={<AiBadge label="AI · advisory" />}>
            {(t.completenessNudges ?? []).length === 0 ? (
              <div className="muted">All standard completeness checks passed.</div>
            ) : (
              <ul style={{ margin: 0, lineHeight: 1.75 }}>
                {(t.completenessNudges ?? []).map((n: string, i: number) => <li key={i}>{n}</li>)}
              </ul>
            )}
          </Card>

          {/* Rendered HTML */}
          <Card title="Full CPT" sub={`Generated ${new Date(t.generatedAt).toLocaleString()} · ${t.generatedBy}`}>
            <div
              className="proposal"
              style={{
                border: "1px solid var(--line)", borderRadius: 6, padding: 12,
                background: "#fff", maxHeight: 520, overflow: "auto",
              }}
              dangerouslySetInnerHTML={{ __html: t.html }}
            />
          </Card>

          {/* Versions */}
          {(versions.data ?? []).length > 1 && (
            <Card title="Version history">
              <table>
                <thead><tr><th>v</th><th>Status</th><th>Generated</th><th>Reviewed</th></tr></thead>
                <tbody>
                  {(versions.data ?? []).map((v: any) => (
                    <tr key={v.id}>
                      <td>{v.version}</td>
                      <td><Badge kind={v.status === "CONFIRMED" ? "ok" : v.status === "REJECTED" ? "bad" : "ai"}>{v.status}</Badge></td>
                      <td className="mono">{new Date(v.generatedAt).toLocaleString()}</td>
                      <td className="muted">{v.reviewedBy ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
