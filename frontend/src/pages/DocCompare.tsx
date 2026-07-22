import { useEffect, useState } from "react";
import { docCompare, docs, origination, decision, fmt, type Comparison, type DiffRow } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, DeterministicBadge, EmptyState, Field, Toast, useAsync } from "../ui";

/**
 * Document comparison / incremental-change diff (CLoM F57). A deterministic, read-only engine
 * compares two versioned artifacts already held in decision-service — two credit-proposal
 * versions or two generated-document versions — and renders a structured change table
 * (ADDED / REMOVED / CHANGED / UNCHANGED) beside a side-by-side view. The engine never mutates
 * either source, so the two artifacts are DETERMINISTIC records of themselves: the same pair
 * always yields a byte-identical diff.
 */

type Kind = "PROPOSAL_VERSIONS" | "DOCUMENT_VERSIONS";

function changeTone(t: string): string {
  if (t === "ADDED") return "ok";
  if (t === "REMOVED") return "err";
  if (t === "CHANGED") return "warn";
  return "";
}

export default function DocCompare() {
  const { ref: ctxRef, actor } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState(ctxRef ?? "");
  const [kind, setKind] = useState<Kind>("PROPOSAL_VERSIONS");
  const [leftRef, setLeftRef] = useState("");
  const [rightRef, setRightRef] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Comparison | null>(null);
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);

  // Load the two selectable artifact lists for the chosen deal + kind.
  const artifacts = useAsync(async () => {
    if (!ref) return [] as { value: string; label: string }[];
    if (kind === "PROPOSAL_VERSIONS") {
      const versions = await decision.proposalVersions(ref);
      return (versions ?? []).map((p: any) => ({
        value: String(p.version),
        label: `v${p.version} · ${p.format ?? "STANDARD"} · ${fmt.dateTime(p.generatedAt)}`,
      }));
    }
    const list = await docs.list(ref);
    return (list ?? []).map((d: any) => ({
      value: String(d.id),
      label: `#${d.id} · ${d.title} · ${d.status}`,
    }));
  }, [ref, kind]);

  // Reset the artifact picks whenever the deal or kind changes.
  useEffect(() => {
    setLeftRef("");
    setRightRef("");
    setResult(null);
  }, [ref, kind]);

  const history = useAsync(async () => {
    if (!ref) return [] as Comparison[];
    return docCompare.list(ref);
  }, [ref, result]);

  const opts = artifacts.data ?? [];

  async function runCompare() {
    if (!ref || !leftRef || !rightRef) return;
    setBusy(true);
    try {
      const c = await docCompare.compare({ kind, subjectRef: ref, leftRef, rightRef }, actor);
      setResult(c);
      setToast({ text: `Comparison ${c.comparisonRef} computed — ${c.changed} changed, ${c.added} added, ${c.removed} removed` });
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  async function openComparison(comparisonRef: string) {
    try {
      const c = await docCompare.get(comparisonRef);
      setResult(c);
      setKind(c.kind as Kind);
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    }
  }

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />

      <Card
        title="Document Comparison"
        sub="Deterministic incremental-change diff between two versioned artifacts. Read-only over the sources — neither the proposals nor the documents are mutated. The same pair always yields a byte-identical change table."
        right={<DeterministicBadge label="DETERMINISTIC · READ-ONLY" />}
      >
        <div className="cmp-controls">
          <Field label="Deal (subject)">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">— select deal —</option>
              {(apps.data ?? []).map((a: any) => (
                <option key={a.reference} value={a.reference}>
                  {a.reference} · {a.counterpartyName} · {a.status}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Artifact kind">
            <select value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
              <option value="PROPOSAL_VERSIONS">Credit-proposal versions</option>
              <option value="DOCUMENT_VERSIONS">Generated-document versions</option>
            </select>
          </Field>
        </div>
      </Card>

      {!ref && (
        <Card>
          <EmptyState glyph="⇄" title="Select a deal" sub="Pick the deal whose artifact versions you want to compare." />
        </Card>
      )}

      {ref && (
        <Card title="Pick two versions to compare" right={<DeterministicBadge label="LEFT (BASELINE) → RIGHT (REVISED)" />}>
          {artifacts.loading && <div className="loading">Loading versions…</div>}
          {!artifacts.loading && opts.length < 2 && (
            <div className="muted">
              At least two {kind === "PROPOSAL_VERSIONS" ? "proposal versions" : "generated documents"} are needed to compare.
              {kind === "PROPOSAL_VERSIONS"
                ? " Generate the credit proposal more than once (Credit Proposal screen)."
                : " Generate more than one document (Doc Generation screen)."}
            </div>
          )}
          {!artifacts.loading && opts.length >= 2 && (
            <div className="cmp-controls">
              <Field label="Left (baseline)">
                <select value={leftRef} onChange={(e) => setLeftRef(e.target.value)}>
                  <option value="">— select —</option>
                  {opts.map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </Field>
              <Field label="Right (revised)">
                <select value={rightRef} onChange={(e) => setRightRef(e.target.value)}>
                  <option value="">— select —</option>
                  {opts.map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </Field>
              <Button onClick={runCompare} busy={busy} disabled={!leftRef || !rightRef || leftRef === rightRef}>
                Compare
              </Button>
            </div>
          )}
          {leftRef && rightRef && leftRef === rightRef && (
            <div className="muted">Pick two different versions.</div>
          )}
        </Card>
      )}

      {result && <ComparisonResult c={result} />}

      {ref && (history.data ?? []).length > 0 && (
        <Card title="Comparison history" sub={`${(history.data ?? []).length} comparison(s) for this deal`}>
          <table className="cmp-history">
            <thead>
              <tr>
                <th>Ref</th><th>Kind</th><th>Left → Right</th>
                <th>Changed</th><th>Added</th><th>Removed</th><th>Unchanged</th><th>When</th><th></th>
              </tr>
            </thead>
            <tbody>
              {(history.data ?? []).map((c) => (
                <tr key={c.comparisonRef}>
                  <td className="mono">{c.comparisonRef}</td>
                  <td>{c.kind === "PROPOSAL_VERSIONS" ? "Proposal" : "Document"}</td>
                  <td>{c.leftRef} → {c.rightRef}</td>
                  <td>{c.changed}</td>
                  <td>{c.added}</td>
                  <td>{c.removed}</td>
                  <td>{c.unchanged}</td>
                  <td className="mono">{fmt.dateTime(c.createdAt)}</td>
                  <td><a className="cmp-open" onClick={() => openComparison(c.comparisonRef)}>open</a></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

function ComparisonResult({ c }: { c: Comparison }) {
  const rows = c.diff ?? [];
  return (
    <>
      <Card
        title={`Change table · ${c.comparisonRef}`}
        sub={`${c.leftLabel} → ${c.rightLabel}`}
        right={
          <div className="cmp-summary">
            <Badge kind="warn">{c.changed} changed</Badge>
            <Badge kind="ok">{c.added} added</Badge>
            <Badge kind="err">{c.removed} removed</Badge>
            <Badge>{c.unchanged} unchanged</Badge>
          </div>
        }
      >
        <table className="cmp-diff">
          <thead>
            <tr><th>Section</th><th>Change</th></tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className={`cmp-row cmp-${r.changeType.toLowerCase()}`}>
                <td className="cmp-section">{r.section}</td>
                <td><Badge kind={changeTone(r.changeType)}>{r.changeType}</Badge></td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Card title="Side-by-side" sub="Left (baseline) vs right (revised) — added/removed/changed highlighted. Source artifacts unchanged.">
        <div className="cmp-sxs">
          <div className="cmp-col">
            <div className="cmp-col-head">{c.leftLabel}</div>
            {rows.filter((r) => r.changeType !== "ADDED").map((r, i) => (
              <SidePane key={i} row={r} side="old" />
            ))}
          </div>
          <div className="cmp-col">
            <div className="cmp-col-head">{c.rightLabel}</div>
            {rows.filter((r) => r.changeType !== "REMOVED").map((r, i) => (
              <SidePane key={i} row={r} side="new" />
            ))}
          </div>
        </div>
      </Card>
    </>
  );
}

function SidePane({ row, side }: { row: DiffRow; side: "old" | "new" }) {
  const value = side === "old" ? row.oldValue : row.newValue;
  return (
    <div className={`cmp-pane cmp-${row.changeType.toLowerCase()}`}>
      <div className="cmp-pane-head">
        <span className="cmp-pane-title">{row.section}</span>
        <Badge kind={changeTone(row.changeType)}>{row.changeType}</Badge>
      </div>
      <pre className="cmp-body">{value || <span className="muted">— (absent)</span>}</pre>
    </div>
  );
}
