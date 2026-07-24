import { useState } from "react";
import { copilot } from "./api";
import { useApp } from "./app-context";
import { Badge, Button, Field } from "./ui";

const EXAMPLES = [
  "Summarise this deal",
  "What's the rating and why?",
  "Explain the capital / RWA",
  "Is it below the RAROC hurdle?",
  "What covenants are set?",
  "Any early-warning signals?",
  "Approve this deal",          // demonstrates the refusal guardrail
];

export default function CopilotPanel({ reference, compact }: { reference?: string; compact?: boolean }) {
  const { actor, notify } = useApp();
  const [q, setQ] = useState(reference ? "Summarise this deal" : "");
  const [ref, setRef] = useState(reference || "");
  const [ans, setAns] = useState<any>(null);
  const [busy, setBusy] = useState(false);

  const ask = async (question?: string) => {
    const text = question ?? q;
    if (!text.trim()) return;
    setBusy(true);
    try {
      const a = await copilot.ask({ question: text, reference: ref || undefined }, actor);
      setAns(a);
    } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  };

  return (
    <div>
      <div className="sub">
        Acting as <b>{actor}</b> — answers are scoped to your role, grounded in platform data, and never bind credit.
      </div>
      <div className="grid cols-2" style={{ gap: 10 }}>
        <Field label="Question">
          <input value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === "Enter" && ask()}
            placeholder="Ask about a deal…" />
        </Field>
        <Field label="Deal reference (optional)">
          <input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="HLX-…" disabled={!!reference} />
        </Field>
      </div>
      <div className="btnrow">
        <Button onClick={() => ask()} busy={busy}>Ask Credit Intel</Button>
        {!compact && EXAMPLES.map((ex) => (
          <button key={ex} className="btn subtle" style={{ fontSize: 11 }} onClick={() => { setQ(ex); ask(ex); }}>{ex}</button>
        ))}
      </div>

      {ans && (
        <div className="card" style={{ marginTop: 12, background: ans.refused ? "#fff7ed" : "#f8fafc" }}>
          <div className="btnrow" style={{ marginBottom: 8 }}>
            <Badge kind="info">{ans.intent}</Badge>
            <Badge kind="ai">role: {ans.role}</Badge>
            {ans.refused ? <Badge kind="bad">refused</Badge>
              : ans.grounded ? <Badge kind="ok">grounded</Badge> : <Badge kind="warn">no data</Badge>}
          </div>
          <div style={{ fontSize: 14, lineHeight: 1.5 }}>{ans.answer}</div>
          {ans.refusalReason && <div className="gate" style={{ marginTop: 8 }}>{ans.refusalReason}</div>}
          {ans.suggestedAction && <div className="alert" style={{ background: "#eef2ff", color: "#3730a3", marginTop: 8 }}>→ {ans.suggestedAction}</div>}
          {ans.citations?.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <small className="prov">Sources:</small>
              <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
                {ans.citations.map((c: any, i: number) => (
                  <li key={i}><small className="prov"><span className="mono">{c.source}</span> {c.endpoint} — {c.field}</small></li>
                ))}
              </ul>
            </div>
          )}
          <small className="prov" style={{ display: "block", marginTop: 8 }}>In scope for {ans.role}: {ans.scope.join(", ")}</small>
        </div>
      )}
    </div>
  );
}
