import { useState } from "react";
import { decision, origination, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, GovFlow, HumanBadge, Stat, Toast, statusTone, useAsync } from "../ui";

/**
 * Committee Room — quorum voting on a committee-routed decision (D9/committee mode) and the
 * sanction letter that follows an approval. Voting is a named-human process with segregation
 * of duties (the deal's router cannot vote, no member votes twice — enforced server-side); the
 * sanction letter is AI-drafted then human-confirmed. AI never appears in the voter column.
 */
export default function Committee() {
  const { ref: ctxRef } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState(ctxRef ?? "");
  const [voter, setVoter] = useState("cro");
  const [outcome, setOutcome] = useState("APPROVE");
  const [role, setRole] = useState("CREDIT_COMMITTEE");
  const [rationale, setRationale] = useState("Within appetite");
  const [busy, setBusy] = useState(false);
  const [letter, setLetter] = useState<any>(null);
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);

  const { data, loading, error, reload } = useAsync(async () => {
    if (!ref) return null;
    const [d, votes] = await Promise.all([decision.latest(ref), decision.votes(ref)]);
    return { d, votes };
  }, [ref]);

  const d = data?.d;
  const votes = data?.votes || [];

  async function castVote() {
    setBusy(true);
    try {
      await decision.decide(ref, { outcome, role, rationale }, voter);
      setToast({ text: `Vote recorded by ${voter}` });
      reload();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  async function generateLetter() {
    setBusy(true);
    try {
      const gd = await decision.sanctionLetter(ref, "cad.officer");
      setLetter(gd);
      setToast({ text: "Sanction letter drafted (DRAFT — confirm in Doc Generation)" });
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />
      <Card title="Committee Room" sub="Quorum voting on committee-routed decisions + the sanction letter that follows approval. Named-human votes with segregation of duties; AI never votes.">
        <Field label="Deal">
          <select value={ref} onChange={(e) => setRef(e.target.value)}>
            <option value="">— select deal —</option>
            {(apps.data ?? []).map((a: any) => (
              <option key={a.reference} value={a.reference}>
                {a.reference} · {a.counterpartyName} · {a.status}
              </option>
            ))}
          </select>
        </Field>
      </Card>

      {!ref && <Card><EmptyState glyph="⚖" title="Load a routed decision" sub="Pick a deal whose decision was routed to a committee tier." /></Card>}
      {ref && error && <Card><div className="alert err">{error}</div></Card>}
      {ref && loading && <Card><div className="loading">Loading…</div></Card>}

      {d && (
        <Card title="Decision state" right={<Badge kind={statusTone(d.status)}>{d.status}</Badge>}>
          <div className="statgrid">
            <Stat label="Required authority" value={d.requiredAuthority} />
            <Stat label="Committee mode" value={d.committeeMode ? "YES" : "no"} />
            <Stat label="Quorum required" value={d.quorumRequired ?? 1} />
            <Stat label="Approving votes" value={votes.filter((v: any) => ["APPROVE", "CONDITIONAL_APPROVE"].includes(v.voteOutcome)).length} />
            <Stat label="Outcome" value={d.outcome || "—"} />
            <Stat label="Decided by" value={d.decidedBy || "—"} />
          </div>
          <div className="sub" style={{ marginTop: 8 }}>Routed by <b>{d.routedBy || "—"}</b> — the router cannot vote (SoD).</div>
        </Card>
      )}

      {d && d.committeeMode && d.status !== "DECIDED" && (
        <Card title="Cast a committee vote" right={<HumanBadge label="NAMED-HUMAN VOTE · SoD" />}>
          <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
            <Field label="Voter (X-Actor)"><input value={voter} onChange={(e) => setVoter(e.target.value)} /></Field>
            <Field label="Outcome">
              <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
                <option>APPROVE</option><option>CONDITIONAL_APPROVE</option><option>DECLINE</option>
              </select>
            </Field>
            <Field label="Authority role"><input value={role} onChange={(e) => setRole(e.target.value)} /></Field>
            <Field label="Rationale"><input value={rationale} onChange={(e) => setRationale(e.target.value)} /></Field>
            <Button onClick={castVote} busy={busy}>Cast vote</Button>
          </div>
        </Card>
      )}

      {d && (
        <Card title="Votes cast" sub={`${votes.length} vote(s)`}>
          {votes.length === 0 ? <div className="muted">No votes yet.</div> : (
            <div className="table-scroll">
            <table>
              <thead><tr><th>When</th><th>Voter</th><th>Role</th><th>Vote</th><th>Dissent</th><th>Rationale</th></tr></thead>
              <tbody>
                {votes.map((v: any) => (
                  <tr key={v.id}>
                    <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(v.castAt)}</td>
                    <td>{v.voter}</td>
                    <td className="mono">{v.voterRole}</td>
                    <td><Badge kind={statusTone(v.voteOutcome)}>{v.voteOutcome}</Badge></td>
                    <td>{v.dissent ? <Badge kind="warn">dissent</Badge> : "—"}</td>
                    <td>{v.rationale || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          )}
        </Card>
      )}

      {d && d.status === "DECIDED" && ["APPROVE", "CONDITIONAL_APPROVE"].includes(d.outcome) && (
        <Card title="Sanction letter" right={<GovFlow ai="AI DRAFTS" human="HUMAN CONFIRMS" note="confirm-lock in Doc Generation" />}>
          <Button onClick={generateLetter} busy={busy}>Generate sanction letter (AI draft)</Button>
          {letter && (
            <div style={{ marginTop: 12 }}>
              <div className="sub">
                <Badge kind={statusTone(letter.status)}>{letter.status}</Badge>{" "}
                {letter.advisory ? <Badge kind="ai">advisory</Badge> : null}{" "}
                <span className="mono">{letter.templateKey}</span> · clauses: {(letter.clauseOrder || []).join(", ")}
              </div>
              {letter.html && <div className="doc-preview" style={{ marginTop: 8 }} dangerouslySetInnerHTML={{ __html: letter.html }} />}
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
