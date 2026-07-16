import { useState } from "react";
import { tatMis, fmt } from "../api";
import {
  Badge, Button, Card, Col, DataTable, DeterministicBadge, EmptyState, Field, Stat, useAsync,
} from "../ui";

/**
 * TAT / MIS reporting over the case-management (WorkItem) and query (RFI) operational data.
 *
 * Every figure here is a DETERMINISTIC aggregation computed server-side from the WorkItem rows
 * and their append-only WorkItemEvent timeline — cycle time, SLA breach, rework and throughput.
 * There is no AI in this surface and it never mutates an authoritative domain figure; it is a
 * pure read/report view (workflow-service /api/tasks/mis + the per-service query SLA rollup).
 */

const QUERY_SERVICES = [
  "workflow", "decision", "counterparty", "origination", "risk", "portfolio", "limits",
];

/** hours (double) → 3-dp string, blank when null. */
const hrs = (v: number | null | undefined) => (v == null ? "—" : fmt.num(v, 3));
/** integer-ish → string, blank when null. */
const n = (v: number | null | undefined) => (v == null ? "—" : String(v));
/** 0..1 rate → percentage, blank when null. */
const rate = (v: number | null | undefined) => (v == null ? "—" : fmt.pct(v, 1));

function breachBadge(v: number | null | undefined) {
  if (v == null) return <span className="muted">—</span>;
  if (v > 0) return <Badge kind="bad">{rate(v)}</Badge>;
  return <Badge kind="ok">{rate(v)}</Badge>;
}

export default function TatReports() {
  // Draft filter inputs vs. the applied filter that actually drives the fetch (Apply gates it).
  const [queueKey, setQueueKey] = useState("");
  const [taskType, setTaskType] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [applied, setApplied] = useState<{ queueKey?: string; taskType?: string; from?: string; to?: string }>({});
  const [svc, setSvc] = useState("workflow");

  const report = useAsync(() => tatMis.mis(applied), [JSON.stringify(applied)]);
  const querySla = useAsync(() => tatMis.querySla(svc), [svc]);

  function apply() {
    setApplied({
      queueKey: queueKey.trim() || undefined,
      taskType: taskType.trim() || undefined,
      // Turn a bare date into a day-bounded ISO instant window.
      from: from ? new Date(from + "T00:00:00").toISOString() : undefined,
      to: to ? new Date(to + "T23:59:59").toISOString() : undefined,
    });
  }
  function clear() {
    setQueueKey(""); setTaskType(""); setFrom(""); setTo("");
    setApplied({});
  }

  const data = report.data as any;
  // Degrade gracefully if a malformed/edge response omits `totals` — the field helpers
  // (n / hrs / rate) already render "—" for null/undefined, so an empty object is safe.
  const t = (data?.totals ?? {}) as any;

  const groupCols: Col<any>[] = [
    { key: "count", header: "Tasks", align: "right", value: (r) => r.count },
    { key: "completedCount", header: "Completed", align: "right", value: (r) => r.completedCount },
    { key: "openCount", header: "Open", align: "right", value: (r) => r.openCount },
    { key: "sentBackCount", header: "Sent back", align: "right", value: (r) => r.sentBackCount },
    { key: "reworkTaskCount", header: "Rework", align: "right", value: (r) => r.reworkTaskCount },
    {
      key: "avgCycleHours", header: "Avg TAT (h)", align: "right",
      render: (r) => hrs(r.avgCycleHours), value: (r) => r.avgCycleHours ?? -1,
    },
    {
      key: "medianCycleHours", header: "Median (h)", align: "right",
      render: (r) => hrs(r.medianCycleHours), value: (r) => r.medianCycleHours ?? -1,
    },
    {
      key: "maxCycleHours", header: "Max (h)", align: "right",
      render: (r) => hrs(r.maxCycleHours), value: (r) => r.maxCycleHours ?? -1,
    },
    { key: "breachedCount", header: "Breached", align: "right", value: (r) => r.breachedCount },
    {
      key: "breachRate", header: "Breach %", align: "right",
      render: (r) => breachBadge(r.breachRate), value: (r) => r.breachRate ?? -1,
    },
    { key: "openOverdueCount", header: "Overdue", align: "right", value: (r) => r.openOverdueCount },
  ];

  const queueCols: Col<any>[] = [
    { key: "queueKey", header: "Queue", render: (r) => <span className="mono">{r.queueKey}</span> },
    ...groupCols,
  ];
  const typeCols: Col<any>[] = [
    { key: "taskType", header: "Task type", render: (r) => <span className="mono">{r.taskType}</span> },
    ...groupCols,
  ];

  const q = querySla.data as any;

  return (
    <div className="grid">
      <Card
        title="TAT / MIS — case & query operational reporting"
        sub="Deterministic aggregations over the WorkItem case layer and its append-only event timeline: cycle time, SLA breach, rework and throughput. No AI; no authoritative figure is moved. Pure read surface."
        right={<div className="gov-chips"><DeterministicBadge label="DETERMINISTIC · REPORT" /></div>}
      >
        <div className="tat-filters">
          <Field label="Queue key" hint="exact match; blank = all">
            <input value={queueKey} onChange={(e) => setQueueKey(e.target.value)} placeholder="e.g. CREDIT_OPS" />
          </Field>
          <Field label="Task type" hint="exact match; blank = all">
            <input value={taskType} onChange={(e) => setTaskType(e.target.value)} placeholder="e.g. REVIEW" />
          </Field>
          <Field label="Created from"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="Created to"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <div className="btnrow" style={{ gap: 8 }}>
            <Button kind="primary" onClick={apply}>Apply</Button>
            <Button kind="subtle" onClick={clear}>Clear</Button>
            <Button kind="subtle" onClick={() => report.reload()}>Refresh</Button>
          </div>
        </div>
      </Card>

      {report.loading && <div className="loading">Loading TAT / MIS report…</div>}
      {report.error && <div className="alert err">{report.error}</div>}

      {data && (
        <>
          <div className="tat-statgrid">
            <Stat label="Tasks in scope" value={n(t.count)} />
            <Stat label="Completed" value={n(t.completedCount)} />
            <Stat label="Open backlog" value={n(t.openCount)} />
            <Stat label="Avg TAT (hours)" value={hrs(t.avgCycleHours)} />
            <Stat label="Median TAT (hours)" value={hrs(t.medianCycleHours)} />
            <Stat
              label="SLA breach rate" value={rate(t.breachRate)}
              delta={`${n(t.breachedCount)} breached · ${n(t.openOverdueCount)} overdue`}
              tone={t.breachedCount > 0 ? "var(--bad)" : "var(--ok)"}
            />
            <Stat
              label="Send-back rate" value={rate(t.sendBackRate)}
              delta={`${n(t.sentBackCount)} sent back · ${n(t.reworkTaskCount)} rework`}
              tone={t.sentBackCount > 0 ? "var(--warn)" : "var(--ok)"}
            />
            <Stat
              label="Throughput (window)"
              value={`${n(data.throughput?.createdInWindow)} → ${n(data.throughput?.completedInWindow)}`}
              delta="created → completed"
            />
          </div>

          <Card title="Cycle time & SLA by queue"
            sub="From CREATED → COMPLETED on the event timeline. Averages/medians are over completed tasks only.">
            {(data.byQueue ?? []).length === 0
              ? <EmptyState glyph="◴" title="No tasks in scope" sub="Adjust the filters or seed some work-items." />
              : <DataTable id="tat.byQueue" columns={queueCols} rows={data.byQueue} rowKey={(r) => r.queueKey}
                  initialPageSize={25} />}
          </Card>

          <Card title="Cycle time & SLA by task type">
            {(data.byTaskType ?? []).length === 0
              ? <EmptyState glyph="◴" title="No tasks in scope" />
              : <DataTable id="tat.byType" columns={typeCols} rows={data.byTaskType} rowKey={(r) => r.taskType}
                  initialPageSize={25} />}
          </Card>

          <div className="grid cols-2">
            <Card title="Rework distribution" sub="How many tasks are on each rework cycle (0 = first pass).">
              {(data.reworkDistribution ?? []).length === 0 ? <div className="muted">—</div> : (
                <table>
                  <thead><tr><th>Rework cycle</th><th className="num">Tasks</th></tr></thead>
                  <tbody>
                    {data.reworkDistribution.map((r: any) => (
                      <tr key={r.reworkCycle}>
                        <td>{r.reworkCycle === 0 ? <Badge kind="ok">first pass</Badge> : <Badge kind="warn">cycle {r.reworkCycle}</Badge>}</td>
                        <td className="num">{r.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>

            <Card title="Assignee load" sub="Open (OPEN / ASSIGNED) tasks per assignee.">
              {(data.assigneeLoad ?? []).length === 0 ? <div className="muted">No open tasks assigned.</div> : (
                <table>
                  <thead><tr><th>Assignee</th><th className="num">Open tasks</th></tr></thead>
                  <tbody>
                    {data.assigneeLoad.map((r: any) => (
                      <tr key={r.assignee}>
                        <td className="mono">{r.assignee}</td>
                        <td className="num">{r.openCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>
          </div>
        </>
      )}

      <Card title="Query / RFI SLA rollup"
        sub="Per-service deterministic rollup of the query (RFI) collaboration lane — open / scheduled / responded / resolved + overdue, by channel. Each service reports its own threads (no cross-service fan-out)."
        right={<DeterministicBadge label="DETERMINISTIC" />}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, marginBottom: 10 }}>
          <Field label="Service">
            <select value={svc} onChange={(e) => setSvc(e.target.value)}>
              {QUERY_SERVICES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </Field>
          <Button kind="subtle" onClick={() => querySla.reload()}>Refresh</Button>
        </div>
        {querySla.loading ? <div className="loading">Loading…</div>
          : querySla.error ? <div className="alert err">{querySla.error}</div>
          : !q ? <div className="muted">—</div> : (
          <>
            <div className="tat-statgrid" style={{ marginBottom: 10 }}>
              <Stat label="Threads" value={n(q.total)} />
              <Stat label="Open" value={n(q.open)} />
              <Stat label="Responded" value={n(q.responded)} />
              <Stat label="Resolved" value={n(q.resolved)} />
              <Stat label="Overdue" value={n(q.overdue)} tone={q.overdue > 0 ? "var(--bad)" : "var(--ok)"} />
            </div>
            {(q.byChannel ?? []).length === 0 ? <div className="muted">No query threads on {svc}.</div> : (
              <table>
                <thead>
                  <tr><th>Channel</th><th className="num">Total</th><th className="num">Open</th><th className="num">Scheduled</th>
                    <th className="num">Responded</th><th className="num">Resolved</th><th className="num">Cancelled</th><th className="num">Overdue</th></tr>
                </thead>
                <tbody>
                  {q.byChannel.map((c: any) => (
                    <tr key={c.channel}>
                      <td className="mono">{c.channel}</td>
                      <td className="num">{c.total}</td>
                      <td className="num">{c.open}</td>
                      <td className="num">{c.scheduled}</td>
                      <td className="num">{c.responded}</td>
                      <td className="num">{c.resolved}</td>
                      <td className="num">{c.cancelled}</td>
                      <td className="num">{c.overdue > 0 ? <Badge kind="bad">{c.overdue}</Badge> : c.overdue}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </Card>

      <div className="tat-note">
        Figures are deterministic aggregations over the WorkItem case layer and query threads.
        This surface is read-only — it never changes a task's state or an authoritative domain figure.
      </div>
    </div>
  );
}
