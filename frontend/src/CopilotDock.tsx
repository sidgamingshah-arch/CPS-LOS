import { useEffect, useRef, useState } from "react";
import { copilot } from "./api";
import { useApp } from "./app-context";
import { Badge, AiBadge, GovFlow } from "./ui";

/**
 * CopilotDock — the persistent, floating chatbot present on EVERY screen.
 *
 * The dedicated Copilot screen (pages/Copilot.tsx → CopilotPanel.tsx) still exists;
 * this is the always-available companion. It is a real chat: history lives entirely
 * client-side (the /copilot/api/copilot/ask endpoint is stateless), each turn is a
 * fresh grounded call scoped to the active deal + the signed-in actor's role.
 *
 * Governance: strictly advisory / non-binding. It never approves, prices, or books —
 * the answer surfaces the same intent / role / grounded|refused affordances the
 * dedicated panel does, including refusal rationale and a "suggested action" hand-off
 * to the human-gated workflow.
 */

type Citation = { source: string; endpoint: string; field: string };
type Meta = {
  intent?: string;
  role?: string;
  grounded?: boolean;
  refused?: boolean;
  refusalReason?: string;
  suggestedAction?: string;
  citations?: Citation[];
  scope?: string[];
  /** Transport/gateway failure — rendered inline so the chat never appears frozen
   *  (the notify() toast can be occluded by the open dock, which shares its corner). */
  error?: boolean;
};
type Msg = { role: "user" | "assistant"; text: string; meta?: Meta };

/** Quick-prompt chips — same spirit as CopilotPanel's EXAMPLES (incl. a refusal demo). */
const QUICK_PROMPTS = [
  "Summarise this deal",
  "What's the rating and why?",
  "Any early-warning signals?",
  "What covenants are set?",
  "Approve this deal", // demonstrates the non-binding refusal guardrail
];

/* tiny localStorage helpers (best-effort; never throw) */
const lsGet = (k: string, fallback: string) => {
  try { return localStorage.getItem(k) ?? fallback; } catch { return fallback; }
};
const lsSet = (k: string, v: string) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };

function MessageBubble({ msg }: { msg: Msg }) {
  if (msg.role === "user") {
    return <div className="cd-msg user"><div className="cd-bubble">{msg.text}</div></div>;
  }
  const meta = msg.meta || {};
  return (
    <div className="cd-msg assistant">
      <div className={`cd-bubble${meta.refused || meta.error ? " refused" : ""}`}>
        <div className="cd-badges">
          {meta.error ? <Badge kind="bad">error</Badge> : <>
            {meta.intent && <Badge kind="info">{meta.intent}</Badge>}
            {meta.role && <Badge kind="ai">role: {meta.role}</Badge>}
            {meta.refused ? <Badge kind="bad">refused</Badge>
              : meta.grounded ? <Badge kind="ok">grounded</Badge> : <Badge kind="warn">no data</Badge>}
          </>}
        </div>
        <div className="cd-answer">{msg.text}</div>
        {meta.refusalReason && <div className="gate cd-gate">{meta.refusalReason}</div>}
        {meta.suggestedAction && <div className="cd-suggest">→ {meta.suggestedAction}</div>}
        {meta.citations && meta.citations.length > 0 && (
          <div className="cd-cites">
            <small className="prov">Sources:</small>
            <ul>
              {meta.citations.map((c, i) => (
                <li key={i}>
                  <small className="prov"><span className="mono">{c.source}</span> {c.endpoint} — {c.field}</small>
                </li>
              ))}
            </ul>
          </div>
        )}
        {meta.scope && meta.scope.length > 0 && (
          <small className="prov cd-inscope">In scope for {meta.role}: {meta.scope.join(", ")}</small>
        )}
      </div>
    </div>
  );
}

export default function CopilotDock({ reference }: { reference?: string }) {
  const { actor, notify } = useApp();
  const [open, setOpen] = useState(() => lsGet("helix.copilot.dock", "closed") === "open");
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState(false);
  const [messages, setMessages] = useState<Msg[]>([]);
  const bodyRef = useRef<HTMLDivElement>(null);

  // Persist open/closed (best-effort) so the dock keeps its state across reloads.
  useEffect(() => { lsSet("helix.copilot.dock", open ? "open" : "closed"); }, [open]);

  // Auto-scroll to the newest turn (and while the typing indicator shows).
  useEffect(() => {
    if (open && bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
  }, [messages, busy, open]);

  const ask = async (question?: string) => {
    const text = (question ?? q).trim();
    if (!text || busy) return;
    setMessages((m) => [...m, { role: "user", text }]);
    setQ("");
    setBusy(true);
    try {
      // Grounded on the active deal when one is selected. The endpoint is stateless,
      // so conversation continuity is purely the client-side `messages` array.
      const a = await copilot.ask({ question: text, reference: reference || undefined }, actor);
      const meta: Meta = {
        intent: a?.intent, role: a?.role, grounded: a?.grounded, refused: a?.refused,
        refusalReason: a?.refusalReason, suggestedAction: a?.suggestedAction,
        citations: a?.citations, scope: a?.scope,
      };
      setMessages((m) => [...m, { role: "assistant", text: a?.answer ?? "", meta }]);
    } catch (e: any) {
      const emsg = e?.message || "Couldn't reach the copilot.";
      // Surface inline too — the toast shares the dock's corner and would be hidden.
      setMessages((m) => [...m, { role: "assistant", text: emsg, meta: { error: true } }]);
      notify(emsg, true);
    } finally {
      setBusy(false);
    }
  };

  // Collapsed → a single floating launcher (FAB) in the bottom-right corner.
  if (!open) {
    return (
      <button className="copilot-fab" aria-label="Open Copilot" title="Copilot — grounded, non-binding assistant"
        onClick={() => setOpen(true)}>
        <span className="copilot-fab-glyph" aria-hidden="true">✦</span>
        <span className="copilot-fab-label">Copilot</span>
      </button>
    );
  }

  return (
    <div className="copilot-dock" role="dialog" aria-label="Copilot chat">
      <div className="copilot-dock-head">
        <div className="cd-title">
          <AiBadge />
          <span className="cd-name">Copilot</span>
        </div>
        <button className="cd-close" aria-label="Minimise Copilot" title="Minimise" onClick={() => setOpen(false)}>×</button>
      </div>

      <div className="copilot-dock-sub">
        <GovFlow ai="AI · ADVISORY" human="HUMAN DECIDES" note="grounded · non-binding" />
        {reference
          ? <span className="cd-scope">scoped to <b className="mono">{reference}</b></span>
          : <span className="cd-scope muted">no active deal — ask a general question</span>}
      </div>

      <div className="copilot-dock-body" ref={bodyRef}>
        {messages.length === 0 && (
          <div className="cd-empty">
            Ask about a deal, rating, covenants, or the portfolio. Answers are scoped to <b>{actor}</b>,
            grounded in platform data, and never bind credit.
          </div>
        )}
        {messages.map((m, i) => <MessageBubble key={i} msg={m} />)}
        {busy && (
          <div className="cd-msg assistant">
            <div className="cd-bubble typing" aria-label="Copilot is thinking"><span /><span /><span /></div>
          </div>
        )}
      </div>

      <div className="copilot-dock-quick">
        {QUICK_PROMPTS.map((p) => (
          <button key={p} className="cd-chip" disabled={busy} onClick={() => ask(p)}>{p}</button>
        ))}
      </div>

      <div className="copilot-dock-input">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && ask()}
          placeholder={reference ? `Ask about ${reference}…` : "Ask the copilot…"}
          aria-label="Ask the copilot"
        />
        <button className="cd-send" aria-label="Send" disabled={busy || !q.trim()} onClick={() => ask()}>
          {busy ? "…" : "↵"}
        </button>
      </div>
    </div>
  );
}
