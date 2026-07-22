import { useState } from "react";
import { exceptions, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Col, DataTable, DeterministicBadge, EmptyState, Field, HumanBadge, Stat, Toast, useAsync } from "../ui";

/**
 * Unified exception / tickler cockpit (U7).
 *
 * The aggregated rollup is READ-ONLY — it surfaces open covenant / MER / CAD / limit / EWS
 * items from their owning services into one normalised shape and never mutates a source of
 * record; a source that is unreachable degrades to a warning, not a failure. Alongside it is
 * a light manual tickler with a human-gated, maker-checker resolution (resolver != owner).
 */
function sevKind(sev?: string): string {
  const s = (sev || "").toUpperCase();
  if (s === "SEVERE" || s === "HIGH") return "bad";
  if (s === "MEDIUM") return "warn";
  if (s === "LOW") return "ok";
  return "";
}

const SOURCE_LABEL: Record<string, string> = {
  COVENANT: "Covenant", MER: "MER", CAD: "CAD", LIMIT: "Limit", EWS: "EWS",
};

export default function Exceptions() {
  const { actor } = useApp();
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);
  const [subject, setSubject] = useState("");
  const [subjectApplied, setSubjectApplied] = useState("");

  const roll = useAsync(() => exceptions.rollup(subjectApplied || undefined), [subjectApplied]);
  const ticklers = useAsync(() => exceptions.ticklers(), []);

  // create-tickler form
  const [tSubject, setTSubject] = useState("");
  const [tTitle, setTTitle] = useState("");
  const [tDesc, setTDesc] = useState("");
  const [tOwner, setTOwner] = useState("");
  const [tDue, setTDue] = useState("");
  const [tPriority, setTPriority] = useState("MEDIUM");

  // resolve form (per-row)
  const [resolveRef, setResolveRef] = useState<string | null>(null);
  const [resolveNote, setResolveNote] = useState("");
  const [assignRef, setAssignRef] = useState<string | null>(null);
  const [assignTo, setAssignTo] = useState("");
  const [busy, setBusy] = useState(false);

  const data = roll.data as any;
  const items = (data?.items ?? []) as any[];
  const warnings = (data?.warnings ?? []) as string[];
  const bySource = (data?.bySource ?? {}) as Record<string, number>;

  async function createTickler() {
    setBusy(true);
    try {
      const t = await exceptions.create({
        subjectRef: tSubject.trim(), title: tTitle.trim(),
        description: tDesc.trim() || undefined, owner: tOwner.trim() || undefined,
        dueAt: tDue || undefined, priority: tPriority,
      }, actor);
      setToast({ text: `Tickler ${t.ticklerRef} raised` });
      setTTitle(""); setTDesc(""); setTOwner(""); setTDue("");
      ticklers.reload();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  async function doAssign(ref: string) {
    setBusy(true);
    try {
      await exceptions.assign(ref, assignTo.trim(), actor);
      setToast({ text: `Tickler ${ref} assigned to ${assignTo.trim()}` });
      setAssignRef(null); setAssignTo("");
      ticklers.reload();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  async function doResolve(ref: string) {
    setBusy(true);
    try {
      await exceptions.resolve(ref, resolveNote.trim(), actor);
      setToast({ text: `Tickler ${ref} resolved` });
      setResolveRef(null); setResolveNote("");
      ticklers.reload();
    } catch (e: any) {
      // SoD (resolver == owner) surfaces as a 403 forbiddenAutonomy
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  const cols: Col<any>[] = [
    { key: "source", header: "Source", value: (r) => r.source,
      render: (r) => <Badge>{SOURCE_LABEL[r.source] ?? r.source}</Badge> },
    { key: "type", header: "Type", value: (r) => r.type ?? "" },
    { key: "subjectRef", header: "Subject", value: (r) => r.subjectRef ?? "",
      render: (r) => <span className="mono">{r.subjectRef ?? "—"}</span> },
    { key: "description", header: "Description", value: (r) => r.description ?? "" },
    { key: "owner", header: "Owner", value: (r) => r.owner ?? "",
      render: (r) => r.owner ? <span className="mono">{r.owner}</span> : <span className="muted">—</span> },
    { key: "dueAt", header: "Due", value: (r) => r.dueAt ?? "",
      render: (r) => r.dueAt ? fmt.date(r.dueAt) : <span className="muted">—</span> },
    { key: "severity", header: "Severity", value: (r) => r.severity ?? "",
      render: (r) => <Badge kind={sevKind(r.severity)}>{r.severity ?? "—"}</Badge> },
    { key: "status", header: "Status", value: (r) => r.status ?? "" },
  ];

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />

      <Card title="Unified exception / tickler register"
        sub="A read-only cockpit: open covenant / MER / CAD / limit / EWS items are aggregated best-effort into one normalised shape. Surfacing an item never mutates its source of record; a source that is unreachable degrades to a warning below."
        right={<div className="gov-chips"><DeterministicBadge label="READ-ONLY · AGGREGATED" /></div>}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Subject filter (deal / obligor ref)" hint="Blank = book-wide open items">
            <input value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="e.g. APP-XXXX" />
          </Field>
          <Button onClick={() => setSubjectApplied(subject.trim())}>Load rollup</Button>
          {subjectApplied && <Button kind="subtle" onClick={() => { setSubject(""); setSubjectApplied(""); }}>Clear</Button>}
          <Button kind="subtle" onClick={() => roll.reload()}>Refresh</Button>
        </div>
      </Card>

      <Card title="Aggregated open exceptions"
        sub={data ? `${data.totalOpen} open item(s)${subjectApplied ? ` for ${subjectApplied}` : " (book-wide)"}` : "Loading…"}>
        <div className="statgrid" style={{ marginBottom: 10 }}>
          <Stat label="Total open" value={data?.totalOpen ?? "—"} />
          {["COVENANT", "MER", "CAD", "LIMIT", "EWS"].map((s) => (
            <Stat key={s} label={SOURCE_LABEL[s]} value={bySource[s] ?? 0} />
          ))}
        </div>
        {warnings.length > 0 && (
          <div className="exc-warnings">
            <strong>Degraded — {warnings.length} source(s) unavailable (partial view):</strong>
            <ul>{warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
          </div>
        )}
        <DataTable
          id="exceptions-rollup"
          columns={cols}
          rows={items}
          rowKey={(r: any) => `${r.source}:${r.type}:${r.subjectRef}:${r.dueAt}:${r.description}`}
          empty={<EmptyState glyph="◔" title="No open exceptions" sub="Nothing to action for this scope." />}
        />
      </Card>

      <Card title="Raise a tickler"
        sub="A light, human-owned follow-up tracked in the portfolio book. Assign an owner, then a different actor resolves it (segregation of duties)."
        right={<div className="gov-chips"><HumanBadge label="HUMAN-OWNED" /></div>}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Subject ref"><input value={tSubject} onChange={(e) => setTSubject(e.target.value)} placeholder="deal / obligor ref" /></Field>
          <Field label="Title"><input value={tTitle} onChange={(e) => setTTitle(e.target.value)} placeholder="e.g. Chase stock statement" /></Field>
          <Field label="Owner (optional)"><input value={tOwner} onChange={(e) => setTOwner(e.target.value)} placeholder="assignee" /></Field>
          <Field label="Due"><input type="date" value={tDue} onChange={(e) => setTDue(e.target.value)} /></Field>
          <Field label="Priority">
            <select value={tPriority} onChange={(e) => setTPriority(e.target.value)}>
              <option value="HIGH">HIGH</option>
              <option value="MEDIUM">MEDIUM</option>
              <option value="LOW">LOW</option>
            </select>
          </Field>
          <Button onClick={createTickler} busy={busy} disabled={!tSubject.trim() || !tTitle.trim()}>Raise tickler</Button>
        </div>
        <Field label="Description"><input value={tDesc} onChange={(e) => setTDesc(e.target.value)} placeholder="what needs doing" /></Field>
      </Card>

      <Card title="Ticklers" sub={`${(ticklers.data ?? []).length} tracked`}>
        {(ticklers.data ?? []).length === 0 ? (
          <EmptyState glyph="◔" title="No ticklers yet" sub="Raise one above." />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Ref</th><th>Subject</th><th>Title</th><th>Priority</th><th>Owner</th>
                  <th>Due</th><th>Status</th><th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {(ticklers.data ?? []).map((t: any) => (
                  <tr key={t.ticklerRef}>
                    <td className="mono">{t.ticklerRef}</td>
                    <td className="mono">{t.subjectRef}</td>
                    <td>{t.title}</td>
                    <td><Badge kind={sevKind(t.priority)}>{t.priority}</Badge></td>
                    <td className="mono">{t.owner ?? "—"}</td>
                    <td>{t.dueAt ? fmt.date(t.dueAt) : "—"}</td>
                    <td>
                      <Badge kind={t.status === "RESOLVED" ? "ok" : t.status === "IN_PROGRESS" ? "warn" : ""}>{t.status}</Badge>
                    </td>
                    <td>
                      {t.status !== "RESOLVED" && (
                        <div className="btnrow" style={{ gap: 6, flexWrap: "wrap" }}>
                          {assignRef === t.ticklerRef ? (
                            <>
                              <input value={assignTo} onChange={(e) => setAssignTo(e.target.value)} placeholder="assignee" style={{ width: 120 }} />
                              <Button kind="subtle" busy={busy} disabled={!assignTo.trim()} onClick={() => doAssign(t.ticklerRef)}>Save</Button>
                              <Button kind="subtle" onClick={() => { setAssignRef(null); setAssignTo(""); }}>Cancel</Button>
                            </>
                          ) : resolveRef === t.ticklerRef ? (
                            <>
                              <input value={resolveNote} onChange={(e) => setResolveNote(e.target.value)} placeholder="resolution note" style={{ width: 160 }} />
                              <Button busy={busy} onClick={() => doResolve(t.ticklerRef)}>Confirm resolve</Button>
                              <Button kind="subtle" onClick={() => { setResolveRef(null); setResolveNote(""); }}>Cancel</Button>
                            </>
                          ) : (
                            <>
                              <Button kind="subtle" onClick={() => { setAssignRef(t.ticklerRef); setAssignTo(t.owner ?? ""); }}>Assign</Button>
                              <Button kind="subtle" onClick={() => { setResolveRef(t.ticklerRef); setResolveNote(""); }}>Resolve</Button>
                            </>
                          )}
                        </div>
                      )}
                      {t.status === "RESOLVED" && (
                        <span className="muted mono">by {t.resolvedBy}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="exc-note">Resolution is maker-checker: the resolver must differ from the assigned owner (a 403 otherwise). Every tickler transition is HUMAN-audited.</div>
      </Card>
    </div>
  );
}
