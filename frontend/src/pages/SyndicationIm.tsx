/**
 * Syndication IM Workspace (gap #80) — author, circulate and finalise the
 * Information Memorandum for a syndicated deal.
 *
 * Lifecycle: DRAFT → CIRCULATED → FINAL (or WITHDRAWN). Sections are free-text
 * (markdown) authored by a named human via the shared RichText editor; edits are
 * only allowed while the IM is a DRAFT. Circulating shares it with invitees;
 * finalising locks it and is human-gated with segregation of duties — the server
 * returns 403 if the finaliser is the same person who created the IM.
 *
 * Every version bumps as the IM moves through its states, so the desk always has
 * an audit-clean record of what was circulated versus what was finalised.
 */
import { useState } from "react";
import { syndication, syndicationIm, fmt, type SyndicationIm as Im } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, type Col, DataTable, EmptyState, Field, GovFlow, HumanBadge,
  MarkdownView, QuickCreate, RichText, statusTone, useAsync,
} from "../ui";

// Standard IM sections. Any non-standard section already present on the IM is
// merged in below so nothing authored elsewhere is ever hidden.
const IM_SECTIONS: { key: string; label: string }[] = [
  { key: "executive_summary", label: "Executive Summary" },
  { key: "borrower_overview", label: "Borrower Overview" },
  { key: "facility_terms", label: "Facility & Terms" },
  { key: "financial_highlights", label: "Financial Highlights" },
  { key: "risk_factors", label: "Risk Factors" },
  { key: "syndication_strategy", label: "Syndication Strategy" },
];

function humanise(key: string): string {
  return key.split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

export default function SyndicationIm() {
  const { actor, notify, ref: ctxRef } = useApp();
  // Only SYNDICATION-structured deals — an IM only makes sense for a syndicated deal.
  const deals = useAsync(() => syndication.deals().catch(() => []), []);
  const [ref, setRef] = useState<string>(ctxRef ?? "");
  const [selId, setSelId] = useState<number | null>(null);

  const ims = useAsync<Im[]>(
    () => (ref ? syndicationIm.list(ref) : Promise.resolve([])),
    [ref],
  );

  const selected = (ims.data ?? []).find((m) => m.id === selId) ?? null;

  const statusBadge = (s: Im["status"]) => {
    if (s === "FINAL") return <Badge kind="ok">FINAL</Badge>;
    if (s === "WITHDRAWN") return <Badge kind="bad">WITHDRAWN</Badge>;
    if (s === "CIRCULATED") return <Badge kind="info">CIRCULATED</Badge>;
    return <Badge kind="ai">DRAFT</Badge>;
  };

  const cols: Col<Im>[] = [
    { key: "imRef", header: "IM reference", render: (m) => <span className="mono">{m.imRef}</span> },
    { key: "version", header: "Ver.", align: "right", render: (m) => `v${m.version}` },
    { key: "status", header: "Status", render: (m) => statusBadge(m.status), value: (m) => m.status },
    { key: "createdBy", header: "Created by" },
    { key: "finalisedBy", header: "Finalised by", render: (m) => m.finalisedBy ?? "—" },
    { key: "createdAt", header: "Created", render: (m) => fmt.dateTime(m.createdAt), value: (m) => m.createdAt ?? "" },
  ];

  return (
    <div className="grid">
      <Card
        title="Syndication IM workspace"
        sub="Author, circulate and finalise the Information Memorandum for a syndicated deal — versioned, human-gated, audit-clean."
        right={<GovFlow ai="DESK DRAFTS" human="NAMED HUMAN FINALISES" note="finaliser ≠ creator (SoD)" />}
      >
        <Field label="Syndicated deal">
          <select value={ref} onChange={(e) => { setRef(e.target.value); setSelId(null); }}>
            <option value="">— select deal —</option>
            {(deals.data ?? []).map((d: any) => (
              <option key={d.reference} value={d.reference}>{d.borrower} · {d.reference}</option>
            ))}
          </select>
        </Field>
        <div className="scf-note">
          Only <b>SYNDICATION</b>-structured deals are listed — set a deal's structure to SYNDICATION
          in Deal Structuring for it to appear here.
        </div>
      </Card>

      {!ref && (
        <Card>
          <EmptyState
            glyph="◫"
            title="Select a deal to manage its Information Memoranda"
            sub="Pick a syndicated application above. Create a DRAFT IM, author its sections, circulate to invitees, then finalise (a different named human must finalise)."
          />
        </Card>
      )}

      {ref && (
        <Card
          title="Information Memoranda"
          sub={`${(ims.data ?? []).length} IM(s) for ${ref}`}
          right={
            <QuickCreate
              buttonLabel="＋ New IM (DRAFT)"
              title="New Information Memorandum"
              sub="Starts as a DRAFT. Add sections, then circulate and finalise."
              fields={[{ name: "title", label: "Title", hint: "Optional — a default title is used if left blank.", placeholder: "e.g. Project Meridian — Syndicated TL" }]}
              submitLabel="Create draft"
              onSubmit={async (v) => {
                const created = await syndicationIm.create(ref, { title: v.title?.trim() || undefined }, actor);
                notify(`IM ${created.imRef} created (DRAFT)`);
                ims.reload();
                setSelId(created.id);
              }}
            />
          }
        >
          <DataTable
            id="syndication-im"
            columns={cols}
            rows={ims.data ?? []}
            rowKey={(m) => String(m.id)}
            onRowClick={(m) => setSelId(m.id === selId ? null : m.id)}
            rowClassName={(m) => (m.id === selId ? "dt-row-selected" : undefined)}
            empty={ims.loading ? <div className="loading">Loading…</div> : (
              <EmptyState glyph="◫" title="No IMs on this deal yet" sub="Use ＋ New IM (DRAFT) to start one." />
            )}
          />
        </Card>
      )}

      {selected && (
        <ImDetail key={selected.id} im={selected} onChange={() => ims.reload()} />
      )}
    </div>
  );

  function ImDetail({ im, onChange }: { im: Im; onChange: () => void }) {
    const [busy, setBusy] = useState<string | null>(null);
    const isDraft = im.status === "DRAFT";

    const act = async (label: string, fn: () => Promise<Im>, ok: string) => {
      setBusy(label);
      try { await fn(); notify(ok); onChange(); }
      catch (e: any) { notify(e.message, true); }
      finally { setBusy(null); }
    };

    // Merge standard sections with any extra keys already stored on the IM.
    const extraKeys = Object.keys(im.sections ?? {}).filter((k) => !IM_SECTIONS.some((s) => s.key === k));
    const allSections = [...IM_SECTIONS, ...extraKeys.map((k) => ({ key: k, label: humanise(k) }))];

    return (
      <div className="grid">
        <Card
          title={im.imRef}
          sub={`Created by ${im.createdBy} · ${fmt.dateTime(im.createdAt)}${im.finalisedBy ? ` · Finalised by ${im.finalisedBy}` : ""}`}
          right={
            <div className="btnrow">
              <Badge kind="info">v{im.version}</Badge>
              {statusBadge(im.status)}
              <HumanBadge />
            </div>
          }
        >
          <div className="gate">
            HITL gate: finalising the IM requires a named human — and the finaliser must differ from the creator
            (segregation of duties, enforced server-side with a 403). Editing is locked once the IM leaves DRAFT.
          </div>
          <div className="btnrow" style={{ marginTop: 10 }}>
            {isDraft && (
              <Button busy={busy === "circulate"}
                onClick={() => act("circulate", () => syndicationIm.circulate(im.id, actor), "IM circulated to invitees")}>
                Circulate
              </Button>
            )}
            {im.status === "CIRCULATED" && (
              <Button busy={busy === "finalise"}
                onClick={() => act("finalise", () => syndicationIm.finalise(im.id, actor), "IM finalised")}>
                Finalise
              </Button>
            )}
            {(isDraft || im.status === "CIRCULATED") && (
              <Button kind="danger" busy={busy === "withdraw"}
                onClick={() => {
                  if (window.confirm("Withdraw this IM? It can no longer be circulated or finalised."))
                    act("withdraw", () => syndicationIm.withdraw(im.id, actor), "IM withdrawn");
                }}>
                Withdraw
              </Button>
            )}
          </div>
        </Card>

        <div className="grid cols-2">
          {allSections.map((s) => (
            <SectionEditor key={`${im.id}:${s.key}`} im={im} sectionKey={s.key} label={s.label} editable={isDraft} onSaved={onChange} />
          ))}
        </div>
      </div>
    );

    function SectionEditor({ im, sectionKey, label, editable, onSaved }: {
      im: Im; sectionKey: string; label: string; editable: boolean; onSaved: () => void;
    }) {
      const [content, setContent] = useState<string>(im.sections?.[sectionKey] ?? "");
      const [saving, setSaving] = useState(false);
      const dirty = content !== (im.sections?.[sectionKey] ?? "");

      const save = async () => {
        setSaving(true);
        try {
          await syndicationIm.section(im.id, { key: sectionKey, content }, actor);
          notify(`Section “${label}” saved`);
          onSaved();
        } catch (e: any) { notify(e.message, true); }
        finally { setSaving(false); }
      };

      return (
        <Card title={label}>
          {editable ? (
            <>
              <RichText value={content} onChange={setContent} rows={6} ariaLabel={`${label} content`}
                placeholder={`Draft the ${label.toLowerCase()} section…`} />
              <div className="btnrow" style={{ marginTop: 8 }}>
                <Button onClick={save} busy={saving} disabled={!dirty}>Save section</Button>
                {dirty && <small className="prov">Unsaved changes</small>}
              </div>
            </>
          ) : (
            <MarkdownView md={im.sections?.[sectionKey]} empty={<span className="muted">Not authored.</span>} />
          )}
        </Card>
      );
    }
  }
}
