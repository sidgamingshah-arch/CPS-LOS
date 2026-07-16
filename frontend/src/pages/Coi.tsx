import { useState } from "react";
import { coi, origination, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, DataTable, EmptyState, Field, HumanBadge, Toast, statusTone, useAsync } from "../ui";

/**
 * Conflict-of-interest (COI) attestations. A named human records their <b>own</b> declaration
 * against a subject (the deal) — NONE, DECLARED_MANAGED or CONFLICTED. Attestations are a
 * human-accountable record; when a jurisdiction's DOA_MATRIX pack sets require_coi_attestation
 * the decision workflow uses them to gate a decision/committee-vote (a CONFLICTED attester is
 * recorded but can never self-approve their own conflict away). AI never attests.
 */
const DECLARATIONS = ["NONE", "DECLARED_MANAGED", "CONFLICTED"];

function declTone(d: string): string {
  if (d === "CONFLICTED") return "err";
  if (d === "DECLARED_MANAGED") return "warn";
  return "ok";
}

export default function Coi() {
  const { ref: ctxRef, actor } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState(ctxRef ?? "");
  const [role, setRole] = useState("CREDIT_OFFICER");
  const [declaration, setDeclaration] = useState("NONE");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);

  const { data, loading, error, reload } = useAsync(async () => {
    if (!ref) return [] as any[];
    return coi.list(ref);
  }, [ref]);

  const rows = data ?? [];

  async function attest() {
    if (!ref) return;
    setBusy(true);
    try {
      await coi.attest(
        { subjectType: "application", subjectRef: ref, role, declaration, note: note || undefined },
        actor,
      );
      setToast({ text: `COI attestation recorded by ${actor} (${declaration})` });
      setNote("");
      reload();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />
      <Card
        title="Conflict of Interest"
        sub="Named-human self-attestation against a deal. Advisory by default; gates the decision/committee-vote only where the DOA pack requires it. AI never attests."
        right={<HumanBadge label="NAMED-HUMAN ATTESTATION" />}
      >
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
      </Card>

      {!ref && (
        <Card>
          <EmptyState glyph="⚖" title="Select a deal" sub="Pick the deal you are attesting against." />
        </Card>
      )}

      {ref && (
        <Card title="Record my attestation" right={<HumanBadge label="X-ACTOR RECORDS OWN DECLARATION" />}>
          <div className="coi-form">
            <Field label="Attester (X-Actor)" hint="The verified signed-in human records their own declaration — cannot be overridden">
              <div className="coi-actor">Acting as <b>{actor}</b></div>
            </Field>
            <Field label="Role (claimed)">
              <input value={role} onChange={(e) => setRole(e.target.value)} />
            </Field>
            <Field label="Declaration">
              <select value={declaration} onChange={(e) => setDeclaration(e.target.value)}>
                {DECLARATIONS.map((d) => (
                  <option key={d} value={d}>{d}</option>
                ))}
              </select>
            </Field>
            <Field label="Note">
              <input value={note} placeholder="Optional context…" onChange={(e) => setNote(e.target.value)} />
            </Field>
            <Button onClick={attest} busy={busy} disabled={!ref}>Attest</Button>
          </div>
          {declaration === "CONFLICTED" && (
            <div className="coi-hint">
              A CONFLICTED declaration is recorded for the trail — it does <b>not</b> clear the attester to decide.
            </div>
          )}
        </Card>
      )}

      {ref && error && <Card><div className="alert err">{error}</div></Card>}
      {ref && loading && <Card><div className="loading">Loading…</div></Card>}

      {ref && !loading && (
        <Card title="Attestations for this deal" sub={`${rows.length} attestation(s)`}>
          {rows.length === 0 ? (
            <div className="muted">No attestations recorded yet.</div>
          ) : (
            <DataTable
              id="coi-attestations"
              rows={rows}
              rowKey={(r: any) => String(r.id)}
              columns={[
                { key: "coiRef", header: "Ref", render: (r: any) => <span className="mono">{r.coiRef}</span> },
                { key: "actor", header: "Attester", render: (r: any) => r.actor },
                { key: "attesterRole", header: "Role", render: (r: any) => <span className="mono">{r.attesterRole || "—"}</span> },
                {
                  key: "declaration", header: "Declaration",
                  render: (r: any) => <Badge kind={declTone(r.declaration)}>{r.declaration}</Badge>,
                },
                { key: "status", header: "Status", render: (r: any) => <Badge kind={statusTone(r.status)}>{r.status}</Badge> },
                { key: "note", header: "Note", render: (r: any) => r.note || "—" },
                {
                  key: "at", header: "When",
                  value: (r: any) => r.at ?? "",
                  render: (r: any) => <span className="mono">{fmt.dateTime(r.at)}</span>,
                },
              ]}
            />
          )}
        </Card>
      )}
    </div>
  );
}
