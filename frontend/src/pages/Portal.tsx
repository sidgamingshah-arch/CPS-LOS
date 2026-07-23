/**
 * Customer / vendor Portal (gap #23) — a standalone, external-facing surface.
 *
 * An external counterparty opens a one-time link (or pastes its token), reads the
 * Request-for-Information (topic, question, message timeline), types a response,
 * and uploads a supporting document. The token is the sole credential: an
 * invalid/expired token is surfaced as a clean message, never a crash.
 *
 * Deliberately visually distinct and minimal (`.portal-shell`) — this is what an
 * outside party sees, not an internal operator screen.
 */
import { useEffect, useState } from "react";
import { portal, type PortalContext, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, statusTone } from "../ui";

// Best-effort: seed the token from ?token= or #token= if the portal was opened
// from a real one-time link. Falls back to a paste box.
function tokenFromUrl(): string {
  try {
    const q = new URLSearchParams(window.location.search).get("token");
    if (q) return q;
    const h = window.location.hash.replace(/^#/, "");
    const hp = new URLSearchParams(h).get("token");
    if (hp) return hp;
  } catch { /* ignore */ }
  return "";
}

export default function Portal() {
  const { actor, notify } = useApp();
  const [tokenInput, setTokenInput] = useState<string>(tokenFromUrl());
  const [token, setToken] = useState<string | null>(null);
  const [ctx, setCtx] = useState<PortalContext | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [reply, setReply] = useState("");
  const [sending, setSending] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);

  const load = async (t: string) => {
    const tok = t.trim();
    if (!tok) return;
    setLoading(true);
    setError(null);
    try {
      const c = await portal.context(tok);
      setCtx(c);
      setToken(tok);
    } catch (e: any) {
      setCtx(null);
      setToken(null);
      setError(e?.message || "This link is invalid or has expired. Check the link in your invitation email or contact your relationship manager.");
    } finally {
      setLoading(false);
    }
  };

  // Auto-open if a token arrived via the URL.
  useEffect(() => {
    const t = tokenFromUrl();
    if (t) load(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sendReply = async () => {
    if (!token || !reply.trim()) return;
    setSending(true);
    try {
      const c = await portal.respond(token, reply.trim(), actor);
      setCtx(c);
      setReply("");
      notify("Response submitted");
    } catch (e: any) { notify(e.message, true); }
    finally { setSending(false); }
  };

  const doUpload = async () => {
    if (!token || !file) return;
    setUploading(true);
    try {
      const r = await portal.upload(token, file, actor);
      notify(`Uploaded ${r.fileName}`);
      setFile(null);
      // Refresh the timeline so the upload event is reflected if the server logs it.
      load(token);
    } catch (e: any) { notify(e.message, true); }
    finally { setUploading(false); }
  };

  const reset = () => { setToken(null); setCtx(null); setError(null); setReply(""); setFile(null); };

  return (
    <div className="portal-shell">
      <div className="portal-head">
        <div className="portal-brand">Helix <span>Secure Portal</span></div>
        <div className="portal-tag">External document &amp; information exchange · token-secured</div>
      </div>

      {/* Token entry */}
      {!ctx && (
        <div className="portal-card portal-gate">
          <h3>Open your request</h3>
          <p className="portal-lede">
            Paste the secure token from your invitation email to view the request and respond.
          </p>
          <div className="portal-token-row">
            <input
              className="portal-token-input"
              value={tokenInput}
              onChange={(e) => setTokenInput(e.target.value)}
              placeholder="Paste your access token"
              aria-label="Access token"
              onKeyDown={(e) => { if (e.key === "Enter") load(tokenInput); }}
            />
            <Button onClick={() => load(tokenInput)} busy={loading} disabled={!tokenInput.trim()}>Open</Button>
          </div>
          {error && <div className="alert err portal-error" role="alert">{error}</div>}
        </div>
      )}

      {/* Request context */}
      {ctx && (
        <>
          <div className="portal-card">
            <div className="portal-req-head">
              <div>
                <div className="portal-topic-label">Request topic</div>
                <h3 className="portal-topic">{ctx.topic}</h3>
              </div>
              <div className="portal-meta">
                <Badge kind={statusTone(ctx.status)}>{ctx.status}</Badge>
                {ctx.deadline && <span className="portal-deadline">Due {fmt.date(ctx.deadline)}</span>}
              </div>
            </div>
            <div className="portal-question">{ctx.question}</div>
            <button className="portal-link" onClick={reset}>← Open a different request</button>
          </div>

          {/* Message timeline */}
          <div className="portal-card">
            <h4 className="portal-section-title">Conversation</h4>
            {(ctx.messages ?? []).length === 0 ? (
              <div className="muted">No messages yet — your response will start the thread.</div>
            ) : (
              <ul className="portal-timeline">
                {ctx.messages.map((m, i) => (
                  <li key={i} className="portal-msg">
                    <div className="portal-msg-head">
                      <span className="portal-msg-author">{m.author}</span>
                      <span className="portal-msg-at">{fmt.dateTime(m.at)}</span>
                    </div>
                    <div className="portal-msg-body">{m.body}</div>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* Respond */}
          <div className="portal-card">
            <h4 className="portal-section-title">Your response</h4>
            <textarea
              className="portal-reply"
              rows={4}
              value={reply}
              onChange={(e) => setReply(e.target.value)}
              placeholder="Type your response…"
              aria-label="Your response"
            />
            <div className="btnrow" style={{ marginTop: 8 }}>
              <Button onClick={sendReply} busy={sending} disabled={!reply.trim()}>Submit response</Button>
            </div>
          </div>

          {/* Upload */}
          <div className="portal-card">
            <h4 className="portal-section-title">Upload a document</h4>
            <p className="portal-lede">Attach a supporting document (PDF, image or office file).</p>
            <div className="portal-upload-row">
              <input
                type="file"
                aria-label="Choose a document to upload"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              <Button onClick={doUpload} busy={uploading} disabled={!file}>Upload</Button>
            </div>
            {file && <div className="portal-file muted">Selected: {file.name}</div>}
          </div>
        </>
      )}

      <div className="portal-foot">
        Secured by Helix · Do not share your access token. This session acts as <span className="mono">{actor}</span>.
      </div>
    </div>
  );
}
