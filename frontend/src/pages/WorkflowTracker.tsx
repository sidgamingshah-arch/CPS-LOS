/**
 * Workflow tracker — visualises the WORKFLOW_DEFINITION pack that has been
 * seeded since day one but never had a runtime engine. Each stage chip is
 * tagged with its governance posture (Ai-allowed vs Human-gated, autonomy
 * level), and the topbar carries the GovFlow chip so users see this is the
 * place where AI suggestions meet named-human decisions.
 */
import { useEffect, useMemo, useState } from "react";
import { fmt, workflow, WorkflowInstance, WorkflowStage, WorkflowView } from "../api";
import { useApp } from "../app-context";
import { AiBadge, Badge, Button, Card, EmptyState, Field, GovFlow, HumanBadge, useAsync } from "../ui";

function StatusBadge({ s }: { s: string }) {
  const map: Record<string, string> = {
    COMPLETE: "ok",
    IN_PROGRESS: "info",
    PENDING: "",
    BLOCKED: "warn",
    SKIPPED: "bad",
  };
  return <Badge kind={map[s] ?? ""}>{s}</Badge>;
}

function StageChip({ s }: { s: WorkflowStage }) {
  const tone =
    s.status === "COMPLETE"     ? "ok"
    : s.status === "IN_PROGRESS" ? "info"
    : s.status === "BLOCKED"     ? "warn"
    : s.status === "SKIPPED"     ? "bad"
    : "";
  return (
    <div className="card" style={{ padding: 10, minWidth: 220 }}>
      <div className="flexbetween">
        <strong className="mono" style={{ fontSize: 12 }}>{s.stageKey}</strong>
        <StatusBadge s={s.status} />
      </div>
      <div className="sub" style={{ marginTop: 4 }}>{s.label}</div>
      <div className="btnrow" style={{ marginTop: 6, gap: 4 }}>
        {s.humanGate
          ? <HumanBadge label="HUMAN-GATED" />
          : s.aiAllowed
            ? <AiBadge label={`AI · ${s.autonomy || "—"}`} />
            : <Badge>SYSTEM</Badge>}
        <Badge kind={tone}>SLA {s.slaHours}h</Badge>
        {s.slaBreached && <Badge kind="bad">SLA BREACH</Badge>}
      </div>
      {s.completedBy && (
        <small className="prov" style={{ display: "block", marginTop: 6 }}>
          ✓ {s.completedByType} · {s.completedBy}
        </small>
      )}
      {s.blockedReason && <small className="prov">⚠ {s.blockedReason}</small>}
    </div>
  );
}

export default function WorkflowTracker() {
  const { actor, notify } = useApp();
  const { data: active, reload: reloadActive } = useAsync(() => workflow.active(), []);
  const [selected, setSelected] = useState<string | null>(null);
  const [view, setView] = useState<WorkflowView | null>(null);
  const [breaches, setBreaches] = useState<any[] | null>(null);
  const [busy, setBusy] = useState(false);

  // Pick the first active instance by default.
  useEffect(() => {
    if (!selected && (active || []).length > 0) setSelected(active![0].applicationReference);
  }, [active, selected]);

  // Pull the view whenever selection changes.
  useEffect(() => {
    if (!selected) { setView(null); return; }
    let alive = true;
    workflow.view(selected).then((v) => { if (alive) setView(v); }).catch(() => alive && setView(null));
    return () => { alive = false; };
  }, [selected]);

  useEffect(() => {
    workflow.slaBreaches().then(setBreaches).catch(() => setBreaches([]));
  }, [view]);

  async function advance(stage: WorkflowStage) {
    const actorType = stage.humanGate || stage.autonomy === "D" ? "HUMAN"
                    : stage.aiAllowed ? "AI" : "SYSTEM";
    try {
      setBusy(true);
      await workflow.advance(selected!, stage.stageKey, actorType,
                              `Advanced via UI by ${actor}`, actor);
      const v = await workflow.view(selected!);
      setView(v);
      notify(`Stage ${stage.stageKey} advanced`);
      reloadActive();
    } catch (e: any) {
      notify(e.message || "Advance failed", true);
    } finally { setBusy(false); }
  }

  async function block(stage: WorkflowStage) {
    const reason = prompt("Why are you blocking " + stage.stageKey + "?");
    if (!reason) return;
    try {
      setBusy(true);
      await workflow.block(selected!, stage.stageKey, reason, actor);
      setView(await workflow.view(selected!));
      notify(`Stage ${stage.stageKey} blocked`, true);
    } catch (e: any) { notify(e.message || "Block failed", true); }
    finally { setBusy(false); }
  }

  async function unblock(stage: WorkflowStage) {
    try {
      setBusy(true);
      await workflow.unblock(selected!, stage.stageKey, actor);
      setView(await workflow.view(selected!));
      notify(`Stage ${stage.stageKey} unblocked`);
    } catch (e: any) { notify(e.message || "Unblock failed", true); }
    finally { setBusy(false); }
  }

  const current = useMemo(
    () => (view?.stages || []).find((s) => s.stageKey === view?.instance.currentStageKey),
    [view],
  );

  return (
    <div className="grid">
      <div className="gov-banner">
        <strong>WORKFLOW TRACKER</strong>
        <span style={{ marginLeft: 12 }}>
          The lifecycle visualised from the seeded <code>WORKFLOW_DEFINITION</code> pack.
          Stage advances respect the pack's <code>humanGate</code> + <code>autonomy</code> contract;
          authoritative figures (rating, capital, pricing) are <em>never</em> touched by this surface.
        </span>
        <span style={{ marginLeft: 16 }}>
          <GovFlow ai="AI · STAGES ADVISORY" human="HUMAN · GATES BINDING" />
        </span>
      </div>

      <div className="grid cols-2">
        <Card title="Active workflows" sub={`${(active || []).length} in progress`}>
          {(active || []).length === 0
            ? <EmptyState title="No active workflows" sub="Create a new application — workflow instances materialise on /origination POST." />
            : (
              <div className="grid" style={{ gap: 6 }}>
                {(active || []).map((wf: WorkflowInstance) => (
                  <button key={wf.id}
                          className={`btn ${selected === wf.applicationReference ? "" : "subtle"}`}
                          onClick={() => setSelected(wf.applicationReference)}
                          style={{ textAlign: "left", justifyContent: "space-between", display: "flex" }}>
                    <span className="mono">{wf.applicationReference}</span>
                    <span className="sub">{wf.currentStageKey}</span>
                    {wf.slaBreached && <Badge kind="bad">SLA</Badge>}
                  </button>
                ))}
              </div>
            )}
        </Card>

        <Card title="SLA breaches" sub="Stages past their slaDueAt; sweep runs every 5 min and on-demand.">
          {(breaches || []).length === 0
            ? <EmptyState glyph="◔" title="No SLA breaches" sub="The book is on time." />
            : (
              <table className="table">
                <thead><tr><th>Deal</th><th>Stage</th><th>Status</th><th>Due</th></tr></thead>
                <tbody>
                  {(breaches || []).map((b: any, i: number) => (
                    <tr key={i}>
                      <td className="mono">{b.applicationReference}</td>
                      <td><strong className="mono">{b.stageKey}</strong> {b.humanGate && <HumanBadge />}</td>
                      <td><StatusBadge s={b.status} /></td>
                      <td className="prov">{fmt.dateTime(b.slaDueAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </Card>
      </div>

      {view ? (
        <Card title={`Lifecycle · ${view.instance.applicationReference}`}
              sub={`Definition ${view.instance.definitionCode} v${view.instance.definitionVersion} · ${view.instance.jurisdiction} · ${view.instance.segment}`}
              right={
                <span>
                  <Badge kind={view.instance.status === "COMPLETED" ? "ok" : "info"}>{view.instance.status}</Badge>
                  {view.instance.slaBreached && <Badge kind="bad">SLA BREACH</Badge>}
                </span>
              }>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginBottom: 12 }}>
            {view.stages.map((s) => (
              <div key={s.id}>
                <StageChip s={s} />
                {s.status === "IN_PROGRESS" && (
                  <div className="btnrow" style={{ marginTop: 4, gap: 4 }}>
                    <Button kind="primary" busy={busy} onClick={() => advance(s)}>Advance</Button>
                    <Button kind="subtle" onClick={() => block(s)}>Block</Button>
                  </div>
                )}
                {s.status === "BLOCKED" && (
                  <Button kind="subtle" onClick={() => unblock(s)}>Unblock</Button>
                )}
              </div>
            ))}
          </div>
          {current && (
            <div className="prov" style={{ marginTop: 12 }}>
              Currently at <strong className="mono">{current.stageKey}</strong> — {current.label}
              {" · "} entered {fmt.dateTime(current.enteredAt)}
              {current.slaDueAt && ` · SLA due ${fmt.dateTime(current.slaDueAt)}`}
            </div>
          )}
        </Card>
      ) : (
        <EmptyState title="Select an instance" sub="Pick a deal on the left to inspect its lifecycle."/>
      )}
    </div>
  );
}
