import { useMemo, useState } from "react";
import { tasks, fmt } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, Col, DataTable, DeterministicBadge, EmptyState,
  Field, HumanBadge, Stat, useAsync,
} from "../ui";

/**
 * Delegation / Casework — the click-through surface over the workflow-service WorkItem
 * case layer (workflow-service /api/tasks). A WorkItem is a case-management MIRROR over
 * the authoritative domain status machines: it records who must act and the append-only
 * TAT timeline of who did it, but it approves nothing and holds no credit figures.
 *
 * The screen has two lenses onto the same rows:
 *   • My inbox   — the acting identity's open tasks, with a "My team" roll-up that folds
 *                  in subordinates resolved from the USER_HIERARCHY master (scope=team).
 *   • Queue view — the OPEN (unclaimed) tasks in a named queue, with a Claim (pull) action.
 *
 * Selecting a task opens its detail: metadata, the event timeline (the TAT / history
 * record — round-robin auto-assign, OOO-delegate routing, claim, reassign, send-back and
 * complete all show here), any fan-out / join siblings, and the case actions
 * (Claim · Reassign · Complete · Send back · Withdraw). Every gate — pool membership,
 * supervisor SoD, mandatory reassign reason — is enforced server-side; a 403 is toasted.
 */

const TERMINAL = ["COMPLETED", "WITHDRAWN", "CANCELLED", "SENT_BACK"];

function taskTone(s?: string): string {
  switch ((s || "").toUpperCase()) {
    case "COMPLETED": return "ok";
    case "ASSIGNED": return "info";
    case "OPEN":
    case "SENT_BACK": return "warn";
    case "WITHDRAWN":
    case "CANCELLED": return "bad";
    default: return "";
  }
}

function eventTone(e?: string): string {
  switch ((e || "").toUpperCase()) {
    case "COMPLETED": return "ok";
    case "SENT_BACK":
    case "REASSIGNED": return "warn";
    case "WITHDRAWN": return "bad";
    default: return "info"; // CREATED · ASSIGNED · CLAIMED
  }
}

function actorTypeTone(t?: string): string {
  switch ((t || "").toUpperCase()) {
    case "HUMAN": return "info";
    case "AI": return "warn";
    default: return ""; // SYSTEM — round-robin / auto-assign / OOO delegation
  }
}

const isOpenState = (r: any) => !TERMINAL.includes((r.status || "").toUpperCase());
const isOverdue = (r: any) =>
  isOpenState(r) && !!r.dueAt && new Date(r.dueAt).getTime() < Date.now();

function slaCell(r: any) {
  if (!r.dueAt) return <span className="muted">—</span>;
  const breached = r.slaBreached || isOverdue(r);
  return (
    <span className="inline" style={{ gap: 6, justifyContent: "flex-end" }}>
      <span className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(r.dueAt)}</span>
      {breached && <Badge kind="bad">BREACHED</Badge>}
    </span>
  );
}

const columns: Col<any>[] = [
  { key: "taskRef", header: "Task", render: (r) => <span className="mono">{r.taskRef}</span> },
  { key: "taskType", header: "Type", render: (r) => <span className="mono">{r.taskType}</span> },
  {
    key: "subjectRef", header: "Subject",
    render: (r) => <span className="mono">{r.subjectRef}</span>,
    value: (r) => `${r.subjectType || ""} ${r.subjectRef || ""}`,
  },
  {
    key: "queueKey", header: "Queue",
    render: (r) => (r.queueKey ? <span className="mono">{r.queueKey}</span> : <span className="muted">—</span>),
  },
  {
    key: "assignee", header: "Assignee",
    render: (r) => (r.assignee ? <span className="mono">{r.assignee}</span> : <Badge kind="warn">unclaimed</Badge>),
    value: (r) => r.assignee || "",
  },
  { key: "status", header: "Status", render: (r) => <Badge kind={taskTone(r.status)}>{r.status}</Badge> },
  { key: "priority", header: "Prio", align: "right", value: (r) => r.priority ?? 5 },
  { key: "dueAt", header: "SLA / due", align: "right", render: slaCell, value: (r) => r.dueAt || "" },
  {
    key: "reworkCycle", header: "Rework", align: "right",
    render: (r) => (r.reworkCycle ? <Badge kind="warn">×{r.reworkCycle}</Badge> : <span className="muted">0</span>),
    value: (r) => r.reworkCycle ?? 0,
  },
];

export default function Casework() {
  const { actor, notify } = useApp();
  const [mode, setMode] = useState<"inbox" | "queue">("inbox");
  const [teamMode, setTeamMode] = useState(false);
  const [queueDraft, setQueueDraft] = useState("");
  const [appliedQueue, setAppliedQueue] = useState("");
  const [selected, setSelected] = useState<string | null>(null);

  const inbox = useAsync(
    () => tasks.inbox(actor, teamMode ? "team" : undefined, actor),
    [actor, teamMode],
  );
  const queue = useAsync(
    () => (appliedQueue ? tasks.queue(appliedQueue, actor) : Promise.resolve([] as any[])),
    [appliedQueue, actor],
  );

  const active = mode === "inbox" ? inbox : queue;
  const rows = active.data || [];

  // Quick-pick queue chips derived from the queues seen in the inbox — lets the screen
  // work without hard-coding the demo seed's queue keys.
  const queueKeys = useMemo(() => {
    const s = new Set<string>();
    for (const t of inbox.data || []) if (t.queueKey) s.add(t.queueKey);
    return [...s].sort();
  }, [inbox.data]);

  const reloadList = () => { inbox.reload(); if (appliedQueue) queue.reload(); };
  const loadQueue = (key: string) => {
    const k = key.trim();
    setQueueDraft(k);
    setAppliedQueue(k);
    setMode("queue");
    setSelected(null);
  };

  return (
    <div className="grid">
      <Card
        title="Delegation & casework"
        sub="A case-management view over the WorkItem layer — inbox, queues, claim, reassign, send-back, complete and the per-task TAT timeline. The case layer mirrors the authoritative status machines; it never approves anything and holds no credit figures."
        right={<div className="gov-chips"><HumanBadge label="HUMAN-GATED · SoD" /><DeterministicBadge label="TASK MIRROR" /></div>}
      >
        <div className="cw-seg">
          <button
            className={`btn ${mode === "inbox" ? "primary" : "subtle"}`}
            aria-pressed={mode === "inbox"}
            onClick={() => { setMode("inbox"); setSelected(null); }}
          >My inbox</button>
          <button
            className={`btn ${mode === "queue" ? "primary" : "subtle"}`}
            aria-pressed={mode === "queue"}
            onClick={() => { setMode("queue"); setSelected(null); }}
          >Queue view</button>
          <Button kind="subtle" onClick={reloadList}>Refresh</Button>
        </div>

        {mode === "inbox" ? (
          <div style={{ marginTop: 12 }}>
            <label className="inline" style={{ gap: 8, alignItems: "center" }}>
              <input type="checkbox" checked={teamMode} onChange={(e) => { setTeamMode(e.target.checked); setSelected(null); }} />
              <span>My team <span className="muted">(roll up subordinates from USER_HIERARCHY · fail-open to self-only)</span></span>
            </label>
            <div className="cw-note">
              Showing open tasks assigned to <b className="mono">{actor}</b>{teamMode ? " and reports" : ""}. A supervisor
              may read a subordinate's inbox; anyone else is refused server-side (403).
            </div>
          </div>
        ) : (
          <div style={{ marginTop: 12 }}>
            <div className="inline" style={{ gap: 10, alignItems: "flex-end", flexWrap: "wrap" }}>
              <Field label="Queue key" hint="the ASSIGNMENT_POOL / queue key; you must be a pool member or supervisor to read it">
                <input
                  value={queueDraft}
                  onChange={(e) => setQueueDraft(e.target.value)}
                  placeholder="e.g. CREDIT_OPS"
                  onKeyDown={(e) => { if (e.key === "Enter" && queueDraft.trim()) loadQueue(queueDraft); }}
                />
              </Field>
              <Button kind="primary" disabled={!queueDraft.trim()} onClick={() => loadQueue(queueDraft)}>Load queue</Button>
            </div>
            {queueKeys.length > 0 && (
              <div className="cw-chips">
                <span className="muted" style={{ fontSize: 12, alignSelf: "center" }}>Queues in your inbox:</span>
                {queueKeys.map((k) => (
                  <button key={k} className="cmdk-btn" onClick={() => loadQueue(k)}>{k}</button>
                ))}
              </div>
            )}
          </div>
        )}
      </Card>

      <div className="grid cols-2">
        <Card
          title={mode === "inbox" ? (teamMode ? "Team inbox" : "My inbox") : (appliedQueue ? `Queue · ${appliedQueue}` : "Queue")}
          sub="Click a task to open its timeline and case actions."
          right={<span className="muted" style={{ fontSize: 12 }}>{rows.length} task{rows.length === 1 ? "" : "s"}</span>}
        >
          {active.loading && <div className="loading">Loading tasks…</div>}
          {active.error && <div className="alert err">{active.error}</div>}
          {!active.loading && !active.error && (
            mode === "queue" && !appliedQueue ? (
              <EmptyState glyph="◴" title="Pick a queue" sub="Enter a queue key (or use a chip above) to list its open, unclaimed tasks." />
            ) : rows.length === 0 ? (
              <EmptyState
                glyph="◴"
                title={mode === "inbox" ? "Inbox is empty" : "No open tasks in this queue"}
                sub={mode === "inbox"
                  ? "No open work-items are assigned to you right now."
                  : "Every task here has been claimed or closed."}
              />
            ) : (
              <DataTable
                id={mode === "inbox" ? "cw.inbox" : "cw.queue"}
                columns={columns}
                rows={rows}
                rowKey={(r) => r.taskRef}
                onRowClick={(r) => setSelected(r.taskRef)}
                rowClassName={(r) => (r.taskRef === selected ? "dt-row-selected" : undefined)}
                initialPageSize={10}
              />
            )
          )}
        </Card>

        <div>
          {selected ? (
            <TaskDetail
              key={selected}
              taskRef={selected}
              actor={actor}
              notify={notify}
              onChange={reloadList}
            />
          ) : (
            <Card>
              <EmptyState
                glyph="◳"
                title="Select a task"
                sub="Open a work-item to see its TAT timeline and act on it — claim from the queue, reassign (with a reason), send back for rework, complete, or withdraw."
              />
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

function TaskDetail({ taskRef, actor, notify, onChange }: {
  taskRef: string;
  actor: string;
  notify: (t: string, e?: boolean) => void;
  onChange: () => void;
}) {
  const view = useAsync(() => tasks.get(taskRef), [taskRef]);
  const tl = useAsync(() => tasks.timeline(taskRef), [taskRef]);
  const t = view.data;

  // Subject-level TAT rollup + fan-out/join siblings — both fail-soft (a null / [] result
  // simply hides the block; neither is required to act on the task).
  const tat = useAsync(
    () => (t?.subjectRef ? tasks.tat(t.subjectRef).catch(() => null) : Promise.resolve(null)),
    [t?.subjectRef],
  );
  const siblings = useAsync(
    () => (t?.joinGroupId && t?.subjectRef
      ? tasks.subject(t.subjectRef)
          .then((list) => list.filter((x: any) => x.joinGroupId === t.joinGroupId))
          .catch(() => [] as any[])
      : Promise.resolve([] as any[])),
    [t?.joinGroupId, t?.subjectRef],
  );

  const [assignTo, setAssignTo] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<any>, ok: string | ((res: any) => string)) => {
    setBusy(true);
    try {
      const res = await fn();
      notify(typeof ok === "function" ? ok(res) : ok);
      setNote("");
      setAssignTo("");
      view.reload();
      tl.reload();
      tat.reload();
      onChange();
    } catch (e: any) {
      notify(e?.message || "Action failed", true);
    } finally {
      setBusy(false);
    }
  };

  if (view.loading) return <Card title="Loading…"><div className="loading">Loading task…</div></Card>;
  if (view.error || !t) return <Card title="Error"><div className="alert err">{view.error || "Task not found"}</div></Card>;

  const st = (t.status || "").toUpperCase();
  const terminal = TERMINAL.includes(st);
  const payload = (t.payload || {}) as Record<string, any>;
  const tatData = tat.data as any;
  const sibs = siblings.data || [];
  const sibsDone = sibs.filter((s: any) => (s.status || "").toUpperCase() === "COMPLETED").length;

  return (
    <div className="grid">
      <Card
        title={t.taskRef}
        sub={`${t.taskType} · subject ${t.subjectType} ${t.subjectRef}`}
        right={<Badge kind={taskTone(st)}>{st}</Badge>}
      >
        <div className="kv">
          <span className="k">Queue</span>
          <span className="v">{t.queueKey ? <span className="mono">{t.queueKey}</span> : <span className="muted">— (unqueued)</span>}</span>
          <span className="k">Assignee</span>
          <span className="v">{t.assignee ? <span className="mono">{t.assignee}</span> : <Badge kind="warn">unclaimed</Badge>}</span>
          <span className="k">Priority</span>
          <span className="v mono">{t.priority ?? 5}</span>
          <span className="k">SLA</span>
          <span className="v">{t.slaHours ? `${t.slaHours}h` : "—"}{t.dueAt ? <> · due <span className="mono">{fmt.dateTime(t.dueAt)}</span></> : null}
            {(t.slaBreached || isOverdue(t)) && <> <Badge kind="bad">BREACHED</Badge></>}</span>
          <span className="k">Rework cycle</span>
          <span className="v">{t.reworkCycle ? <Badge kind="warn">×{t.reworkCycle}</Badge> : <span className="muted">0 · first pass</span>}</span>
          {t.joinGroupId && <>
            <span className="k">Fan-out / join</span>
            <span className="v"><span className="mono">{t.joinGroupId}</span> · policy <b>{t.joinPolicy}</b></span>
          </>}
          <span className="k">Created by</span>
          <span className="v mono">{t.createdBy || "—"}</span>
          <span className="k">Created</span>
          <span className="v mono">{fmt.dateTime(t.createdAt)}</span>
          {payload.rework && <>
            <span className="k">Rework reason</span>
            <span className="v">{payload.reworkReason || "—"}{payload.originTaskRef ? <> · from <span className="mono">{payload.originTaskRef}</span></> : null}</span>
          </>}
        </div>
      </Card>

      {t.joinGroupId && sibs.length > 0 && (
        <Card title="Fan-out / join group"
          sub={`Parallel siblings under ${t.joinGroupId} — join policy ${t.joinPolicy}. ${sibsDone}/${sibs.length} complete.`}>
          <table className="cw-tl">
            <thead>
              <tr><th>Task</th><th>Assignee</th><th>Status</th></tr>
            </thead>
            <tbody>
              {sibs.map((s: any) => (
                <tr key={s.taskRef} className={s.taskRef === t.taskRef ? "dt-row-selected" : undefined}>
                  <td className="mono">{s.taskRef}</td>
                  <td className="mono">{s.assignee || <span className="muted">unclaimed</span>}</td>
                  <td><Badge kind={taskTone(s.status)}>{s.status}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      <Card title="Case actions" right={<HumanBadge label="SERVER-ENFORCED SoD" />}>
        <div className="gate">
          <b>Acting as {actor}.</b> Claim needs pool membership; reassign needs a pool supervisor and a
          mandatory reason; complete / send-back need the assignee or a supervisor. All enforced server-side —
          a refusal surfaces as a 403 toast.
        </div>

        {terminal ? (
          <div className="muted" style={{ marginTop: 8 }}>
            This task is <b>{st}</b> — a terminal state. No further case actions are available.
          </div>
        ) : (
          <>
            <Field label="Reassign to" hint="another actor; needed only for Reassign">
              <input value={assignTo} onChange={(e) => setAssignTo(e.target.value)} placeholder="e.g. analyst.user" />
            </Field>
            <Field label="Reason / note" hint="mandatory to reassign or send back; optional for complete / withdraw">
              <textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)}
                placeholder="Why is this action being taken?" />
            </Field>
            <div className="btnrow" style={{ flexWrap: "wrap", gap: 8 }}>
              {st === "OPEN" && (
                <Button kind="primary" disabled={busy}
                  onClick={() => run(() => tasks.claim(taskRef, actor), `Claimed ${taskRef}`)}>
                  Claim
                </Button>
              )}
              <Button disabled={busy || !assignTo.trim() || !note.trim()}
                onClick={() => run(
                  () => tasks.assign(taskRef, assignTo.trim(), note.trim(), actor),
                  `Reassigned to ${assignTo.trim()}`)}>
                Reassign
              </Button>
              <Button disabled={busy}
                onClick={() => run(
                  () => tasks.complete(taskRef, note.trim() || undefined, actor),
                  (res) => res?.joinGroupId
                    ? `Completed · join ${res.joinPolicy} ${res.completedCount}/${res.totalCount}${res.joinGroupSatisfied ? " — group satisfied" : ""}`
                    : `Completed ${taskRef}`)}>
                Complete
              </Button>
              <button className="btn" disabled={busy || !note.trim()}
                onClick={() => run(
                  () => tasks.sendBack(taskRef, note.trim(), actor),
                  (res) => `Sent back — rework ${res?.rework?.taskRef || ""} opened`)}>
                Send back
              </button>
              <button className="btn danger" disabled={busy}
                onClick={() => {
                  if (window.confirm(`Withdraw task ${taskRef}? This closes the case (it changes no figure).`)) {
                    run(() => tasks.withdraw(taskRef, note.trim() || undefined, actor), `Withdrawn ${taskRef}`);
                  }
                }}>
                Withdraw
              </button>
            </div>
          </>
        )}
      </Card>

      {tatData && (tatData.taskCount ?? 0) > 0 && (
        <div className="grid cols-2">
          <Stat label="Tasks on subject" value={String(tatData.taskCount ?? 0)} />
          <Stat label="Completed" value={String(tatData.completedCount ?? 0)} />
          <Stat
            label="SLA breached" value={String(tatData.breachedCount ?? 0)}
            delta={(tatData.breachedCount ?? 0) > 0 ? "over SLA" : "within SLA"}
            tone={(tatData.breachedCount ?? 0) > 0 ? "var(--down)" : "var(--up)"}
          />
          <Stat label="Avg TAT (min)" value={tatData.avgTatMinutes == null ? "—" : String(tatData.avgTatMinutes)} />
        </div>
      )}

      <Card title="Timeline · TAT record"
        sub="Append-only event log for this task. SYSTEM rows are the round-robin auto-assign / OOO-delegate routing; every human action is named and audited.">
        {tl.loading ? <div className="loading">Loading timeline…</div>
          : tl.error ? <div className="alert err">{tl.error}</div>
          : (tl.data || []).length === 0 ? <div className="muted">No events yet.</div>
          : (
            <table className="cw-tl">
              <thead>
                <tr><th>When</th><th>Event</th><th>Actor</th><th>Type</th><th>Note</th></tr>
              </thead>
              <tbody>
                {(tl.data || []).map((e: any) => (
                  <tr key={e.id}>
                    <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(e.at)}</td>
                    <td><Badge kind={eventTone(e.event)}>{e.event}</Badge></td>
                    <td className="mono">{e.actor || "—"}</td>
                    <td><Badge kind={actorTypeTone(e.actorType)}>{e.actorType || "—"}</Badge></td>
                    <td>{e.note || <span className="muted">—</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </Card>
    </div>
  );
}
