/**
 * Commentary — AI narrative commentary for credit-proposal sections.
 * Grounded, advisory, non-binding; the deterministic figure path is untouched.
 * Every draft must be reviewed (CONFIRMED or REJECTED) by a named human before
 * it may be included in a credit proposal.
 */

import { useState } from "react";
import { origination, commentary, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, GovFlow, Stat, useAsync } from "../ui";
import { CitationList, normalizeCitations } from "../xai";

// ── types ──────────────────────────────────────────────────────────────────

type CommentaryStatus = "DRAFT" | "CONFIRMED" | "REJECTED";

interface ProposalCommentary {
  id: number;
  applicationReference: string;
  section: string;
  narrative: string;
  bulletPoints: string[];
  sources: object;
  confidence: number;
  advisory: string;
  status: CommentaryStatus;
  draftedBy: string;
  reviewedBy?: string;
  reviewedAt?: string;
  reviewNote?: string;
}

// ── constants ──────────────────────────────────────────────────────────────

const SECTIONS = [
  "industry_outlook",
  "management_quality",
  "financial_commentary",
  "structure_commentary",
  "risk_commentary",
] as const;

type SectionKey = (typeof SECTIONS)[number];

function humaniseSection(key: string): string {
  return key
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

function statusBadge(status: CommentaryStatus) {
  if (status === "CONFIRMED") return <Badge kind="ok">CONFIRMED</Badge>;
  if (status === "REJECTED") return <Badge kind="bad">REJECTED</Badge>;
  return <Badge kind="ai">DRAFT</Badge>;
}

// ── sub-component: single commentary card ─────────────────────────────────

function CommentaryCard({
  entry,
  actor,
  notify,
  onReload,
}: {
  entry: ProposalCommentary;
  actor: string;
  notify: (msg: string, err?: boolean) => void;
  onReload: () => void;
}) {
  const handleEdit = async () => {
    const next = window.prompt("Edit narrative:", entry.narrative);
    if (next === null) return;
    try {
      await commentary.edit(entry.id, { narrative: next }, actor);
      notify("Narrative updated");
      onReload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const handleApprove = async () => {
    const note = window.prompt("Approval note (optional):") ?? "";
    try {
      await commentary.review(entry.id, { approve: true, note }, actor);
      notify("Commentary confirmed");
      onReload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const handleReject = async () => {
    const note = window.prompt("Rejection reason:");
    if (note === null) return;
    try {
      await commentary.review(entry.id, { approve: false, note }, actor);
      notify("Commentary rejected");
      onReload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  return (
    <div className="card" style={{ marginBottom: 12, background: "#fbfaff" }}>
      {/* Header row */}
      <div className="btnrow" style={{ marginBottom: 8, flexWrap: "wrap", gap: 8 }}>
        {statusBadge(entry.status)}
        <Stat label="Confidence" value={fmt.pct(entry.confidence)} />
        <small className="prov">drafted by {entry.draftedBy}</small>
      </div>

      {/* Narrative */}
      <p style={{ margin: "0 0 10px", lineHeight: 1.65, fontSize: 14 }}>{entry.narrative}</p>

      {/* Bullet points */}
      {entry.bulletPoints && entry.bulletPoints.length > 0 && (
        <ul style={{ margin: "0 0 10px", paddingLeft: 20, fontSize: 13 }}>
          {entry.bulletPoints.map((bp, i) => (
            <li key={i}>{bp}</li>
          ))}
        </ul>
      )}

      {/* Sources — grounded citations, rendered by the shared XAI citation list. */}
      <details style={{ marginBottom: 8 }} open>
        <summary className="muted" style={{ cursor: "pointer", fontSize: 12 }}>
          Sources
        </summary>
        <div style={{ marginTop: 6 }}>
          <CitationList
            citations={normalizeCitations(entry.sources)}
            empty="No sources cited for this draft."
          />
        </div>
      </details>

      {/* Advisory note */}
      {entry.advisory && (
        <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
          <Badge kind="ai">AI · advisory</Badge> {entry.advisory}
        </div>
      )}

      {/* DRAFT actions */}
      {entry.status === "DRAFT" && (
        <div className="btnrow" style={{ marginTop: 6 }}>
          <Button kind="ghost" onClick={handleEdit}>Edit</Button>
          <Button kind="ghost" onClick={handleApprove}>Approve</Button>
          <Button kind="subtle" onClick={handleReject}>Reject</Button>
        </div>
      )}

      {/* Reviewed-by line for CONFIRMED / REJECTED */}
      {(entry.status === "CONFIRMED" || entry.status === "REJECTED") && entry.reviewedBy && (
        <small className="prov" style={{ display: "block", marginTop: 6 }}>
          reviewed by {entry.reviewedBy}
          {entry.reviewedAt ? ` · ${fmt.dateTime(entry.reviewedAt)}` : ""}
          {entry.reviewNote ? ` · "${entry.reviewNote}"` : ""}
        </small>
      )}
    </div>
  );
}

// ── main page ──────────────────────────────────────────────────────────────

export default function Commentary() {
  const { actor, notify, ref: ctxRef } = useApp();

  // Deal selector
  const deals = useAsync(() => origination.list(), []);
  const [selectedRef, setSelectedRef] = useState<string>(ctxRef ?? "");

  // Commentary list — reloaded whenever selectedRef changes or reload() called
  const entries = useAsync<ProposalCommentary[]>(
    () =>
      selectedRef
        ? (commentary.list(selectedRef) as Promise<ProposalCommentary[]>)
        : Promise.resolve([]),
    [selectedRef],
  );

  // Draft-section form
  const [draftSection, setDraftSection] = useState<SectionKey>(SECTIONS[0]);
  const [draftHint, setDraftHint] = useState<string>("");
  const [drafting, setDrafting] = useState(false);

  const handleDraft = async () => {
    if (!selectedRef) return;
    setDrafting(true);
    try {
      await commentary.draft(
        selectedRef,
        { section: draftSection, hint: draftHint.trim() || undefined },
        actor,
      );
      notify("Commentary drafted — review it in the list below");
      setDraftHint("");
      entries.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setDrafting(false);
    }
  };

  // Group entries by section
  const grouped: Record<string, ProposalCommentary[]> = {};
  for (const e of entries.data ?? []) {
    (grouped[e.section] ??= []).push(e);
  }
  const sectionKeysWithEntries = SECTIONS.filter((s) => (grouped[s] ?? []).length > 0);

  return (
    <div className="grid">

      {/* ── AI advisory banner ── */}
      <Card
        title="AI Narrative Commentary"
        sub="Advisory, grounded, non-binding. The deterministic figure path (rating, capital, ECL, RAROC) is never modified by this module."
        right={<GovFlow ai="AI DRAFTS" human="HUMAN APPROVES" />}
      >
        <div className="muted" style={{ fontSize: 13, lineHeight: 1.65 }}>
          Commentary is drafted by an AI copilot grounded on deal data retrieved live from the
          platform. Every draft is labelled <Badge kind="ai">DRAFT</Badge> and must be{" "}
          <Badge kind="ok">CONFIRMED</Badge> or <Badge kind="bad">REJECTED</Badge> by a named human
          analyst before it may appear in a credit proposal. No figure, grade, or capital number is
          computed or altered here — those remain on the hard-coded deterministic path.
        </div>
      </Card>

      {/* ── Deal selector ── */}
      <Card title="Deal selector">
        <Field label="Deal">
          <select
            value={selectedRef}
            onChange={(e) => {
              setSelectedRef(e.target.value);
            }}
          >
            <option value="">— select deal —</option>
            {(deals.data ?? []).map((d: any) => (
              <option key={d.reference} value={d.reference}>
                {d.reference} · {d.counterpartyName} · {d.status}
              </option>
            ))}
          </select>
        </Field>
      </Card>

      {!selectedRef && (
        <Card>
          <EmptyState
            glyph="✎"
            title="Select a deal to draft AI commentary"
            sub="Pick an application above. Commentary is grounded in the deal's data; every draft is advisory and must be reviewed by a named human before it appears on the proposal."
          />
        </Card>
      )}

      {/* ── Draft section ── */}
      {selectedRef && (
        <Card
          title="Draft section"
          sub="Choose a section, add an optional hint, and the AI will draft commentary grounded on deal data."
        >
          <div className="grid cols-2">
            <Field label="Section">
              <select
                value={draftSection}
                onChange={(e) => setDraftSection(e.target.value as SectionKey)}
              >
                {SECTIONS.map((s) => (
                  <option key={s} value={s}>
                    {humaniseSection(s)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Hint (optional)">
              <textarea
                rows={2}
                value={draftHint}
                onChange={(e) => setDraftHint(e.target.value)}
                placeholder="E.g. focus on leverage trend and covenant headroom…"
              />
            </Field>
          </div>
          <div className="btnrow" style={{ marginTop: 8 }}>
            <Button onClick={handleDraft} busy={drafting}>
              Draft commentary
            </Button>
            <small className="prov">Acting as {actor}</small>
          </div>
        </Card>
      )}

      {/* ── Commentary list grouped by section ── */}
      {selectedRef && (
        <>
          {entries.loading && (
            <div className="loading">Loading commentary…</div>
          )}
          {!entries.loading && (entries.data ?? []).length === 0 && (
            <Card>
              <EmptyState
                glyph="✎"
                title="No commentary on this deal yet"
                sub="Use the “Draft section” card above to generate the first piece. Drafts stay advisory until a named human approves them."
              />
            </Card>
          )}

          {sectionKeysWithEntries.map((section) => (
            <Card
              key={section}
              title={humaniseSection(section)}
            >
              <div className="grid cols-2">
                {(grouped[section] ?? []).map((entry) => (
                  <CommentaryCard
                    key={entry.id}
                    entry={entry}
                    actor={actor}
                    notify={notify}
                    onReload={entries.reload}
                  />
                ))}
              </div>
            </Card>
          ))}
        </>
      )}
    </div>
  );
}
