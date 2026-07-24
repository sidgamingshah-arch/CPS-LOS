/**
 * Groups — borrower-group workspace.
 *
 *  • AI-advisory group identification (suggest parent group + sibling counterparties)
 *      → human still tags via the existing endpoint
 *  • Group insights rollup (advisory, grounded in member figures)
 *  • Combined credit proposal across every group member (advisory, versioned)
 *
 * Authoritative member-level grades / capital / pricing are never mutated.
 */
import { useEffect, useMemo, useState } from "react";
import { groups as groupsApi, initiation, counterparty, fmt } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge,
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  GovFlow,
  Modal,
  Stat,
  Unchanged,
  useAsync,
} from "../ui";

export default function Groups() {
  const { actor, notify } = useApp();
  const allGroups = useAsync(() => initiation.listGroups(), []);
  const counterparties = useAsync(() => counterparty.list(), []);

  const [selectedGroupRef, setSelectedGroupRef] = useState<string>("");

  // Create-group modal state. `null` = closed; an object opens it, optionally pre-filling the
  // name and requesting a one-click tag of a counterparty (from the CREATE_NEW_GROUP suggestion).
  const [creatingGroup, setCreatingGroup] = useState<{ tagId?: number; defaultName?: string } | null>(null);

  // Auto-select the first group once loaded
  useEffect(() => {
    if (!selectedGroupRef && allGroups.data && allGroups.data.length > 0) {
      setSelectedGroupRef(allGroups.data[0].reference);
    }
  }, [allGroups.data, selectedGroupRef]);

  // ── advisory group identification ─────────────────────────────────────────
  const [suggestForId, setSuggestForId] = useState<number | "">("");
  const [suggestion, setSuggestion] = useState<any | null>(null);
  const [suggesting, setSuggesting] = useState(false);
  const runSuggest = async () => {
    if (!suggestForId) return;
    setSuggesting(true);
    try {
      const s = await initiation.suggestGroup(Number(suggestForId), actor);
      setSuggestion(s);
      notify(`Suggestion: ${s.recommendation} · top score ${(s.topScore ?? 0).toFixed(2)}`);
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setSuggesting(false);
    }
  };
  const tagFromSuggestion = async (groupId: number) => {
    if (!suggestForId) return;
    try {
      await initiation.tagToGroup(Number(suggestForId), groupId, actor);
      notify("Tagged to group");
      allGroups.reload();
      counterparties.reload();
      setSuggestion(null);
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  // ── group insights ────────────────────────────────────────────────────────
  const insights = useAsync<any>(
    () => (selectedGroupRef ? groupsApi.insights(selectedGroupRef, actor) : Promise.resolve(null)),
    [selectedGroupRef],
  );

  // ── combined credit proposal ──────────────────────────────────────────────
  const latestProposal = useAsync<any>(
    () =>
      selectedGroupRef
        ? groupsApi.combinedProposal(selectedGroupRef).catch(() => null)
        : Promise.resolve(null),
    [selectedGroupRef],
  );
  const [generating, setGenerating] = useState(false);
  const generateCombined = async () => {
    if (!selectedGroupRef) return;
    setGenerating(true);
    try {
      const p = await groupsApi.generateCombinedProposal(selectedGroupRef, actor);
      notify(`Combined CP v${p.version} generated`);
      latestProposal.reload();
      insights.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setGenerating(false);
    }
  };

  const gi = insights.data;
  const groupList = allGroups.data ?? [];
  const cpList = counterparties.data ?? [];

  const totalExposureLine = useMemo(() => {
    if (!gi || !gi.totalExposureByCurrency) return "—";
    return Object.entries<number>(gi.totalExposureByCurrency)
      .map(([ccy, amt]) => `${fmt.money(amt, "")} ${ccy}`)
      .join(" + ");
  }, [gi]);

  return (
    <div className="grid">
      {/* ── Governance banner ── */}
      <div className="gov-banner">
        <h3>One group. One rollup. Member figures never move.</h3>
        <div className="gb-sub">
          AI suggests the parent group from name + identifier fuzziness. A human still tags it. Group
          insights and the combined credit proposal aggregate every member's <b>authoritative</b>
          {" "}grade, capital and pricing — quoting them verbatim, never recomputing.
        </div>
        <div className="gb-chips">
          <span className="gb-chip"><b>AI · ADVISORY</b> group identification</span>
          <span className="gb-chip"><b>HUMAN-GATED</b> tag · sign-off</span>
          <span className="gb-chip"><b>DETERMINISTIC FIGURES</b> per member · unchanged</span>
        </div>
      </div>

      {/* ── Group selector ── */}
      <Card
        title="Group selector"
        right={<Button kind="ghost" onClick={() => setCreatingGroup({})}>＋ Create group</Button>}
      >
        <Field label="Borrower group">
          <select
            value={selectedGroupRef}
            onChange={(e) => setSelectedGroupRef(e.target.value)}
          >
            <option value="">— select group —</option>
            {groupList.map((g: any) => (
              <option key={g.id} value={g.reference}>
                {g.reference} · {g.name} · RM {g.groupRmId}
                {g.multiCountry ? " · multi-country" : ""}
              </option>
            ))}
          </select>
        </Field>
        {groupList.length === 0 && !allGroups.loading && (
          <div style={{ marginTop: 6 }}>
            <EmptyState
              glyph="◌"
              title="No borrower groups yet"
              sub="Use ＋ Create group above to add one. Once counterparties are tagged, members roll up here for combined credit decisioning."
            />
          </div>
        )}
      </Card>

      {/* ── AI advisory: group identification ── */}
      <Card
        title="Group identification (advisory)"
        sub="Suggest the parent group + ungrouped siblings using name + identifier + RM + sector fuzziness. The human still tags."
        right={
          <GovFlow
            ai="AI SUGGESTS"
            human="HUMAN TAGS"
            note="reasoning shown per candidate"
          />
        }
      >
        <div className="grid cols-2">
          <Field label="Counterparty">
            <select
              value={suggestForId}
              onChange={(e) => {
                setSuggestForId(e.target.value ? Number(e.target.value) : "");
                setSuggestion(null);
              }}
            >
              <option value="">— select counterparty —</option>
              {cpList.map((c: any) => (
                <option key={c.id} value={c.id}>
                  {c.reference} · {c.legalName}
                  {c.groupId ? ` · tagged group #${c.groupId}` : ""}
                </option>
              ))}
            </select>
          </Field>
          <div style={{ display: "flex", alignItems: "flex-end" }}>
            <Button onClick={runSuggest} busy={suggesting} disabled={!suggestForId}>
              Suggest group
            </Button>
          </div>
        </div>

        {suggestion && (
          <div style={{ marginTop: 12 }}>
            <div className="btnrow" style={{ marginBottom: 8 }}>
              <AiBadge label="AI · advisory" />
              <Badge>recommendation: {suggestion.recommendation}</Badge>
              <Stat label="Top score" value={(suggestion.topScore ?? 0).toFixed(3)} />
            </div>

            {suggestion.recommendation === "CREATE_NEW_GROUP" && (
              <div className="gate" style={{ marginBottom: 8, display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
                <span>No existing group is a strong match — spin up a new group and tag this counterparty in one step.</span>
                <Button
                  kind="subtle"
                  onClick={() => setCreatingGroup({
                    tagId: Number(suggestForId),
                    defaultName: deriveGroupName(suggestion.subjectLegalName),
                  })}
                >
                  Create group & tag
                </Button>
              </div>
            )}

            <h4 style={{ margin: "12px 0 6px" }}>Existing-group matches</h4>
            {(suggestion.groupMatches ?? []).length === 0 && (
              <div className="muted">No existing group matched above the floor.</div>
            )}
            {(suggestion.groupMatches ?? []).map((g: any) => (
              <div key={g.groupId} className="card" style={{ marginBottom: 8, background: "#fbfaff" }}>
                <div className="flexbetween" style={{ alignItems: "center" }}>
                  <div>
                    <b>{g.name}</b> · {g.reference} · RM {g.groupRm} · {g.memberCount} member(s)
                    <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                      signals: {(g.signals ?? []).join(" · ") || "—"}
                    </div>
                  </div>
                  <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                    <Stat label="Score" value={(g.score ?? 0).toFixed(3)} />
                    <Button kind="subtle" onClick={() => tagFromSuggestion(g.groupId)}>
                      Tag to this group
                    </Button>
                  </div>
                </div>
              </div>
            ))}

            <h4 style={{ margin: "12px 0 6px" }}>Ungrouped sibling counterparties</h4>
            {(suggestion.ungroupedSiblings ?? []).length === 0 && (
              <div className="muted">No ungrouped sibling candidates.</div>
            )}
            {(suggestion.ungroupedSiblings ?? []).map((s: any) => (
              <div key={s.counterpartyId} className="card" style={{ marginBottom: 8, background: "#fbfaff" }}>
                <b>{s.legalName}</b> · {s.reference} · {s.country ?? "—"} · {s.sector ?? "—"}
                <div className="muted" style={{ fontSize: 12 }}>
                  signals: {(s.signals ?? []).join(" · ") || "—"} · score {(s.score ?? 0).toFixed(3)}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* ── Group insights ── */}
      {selectedGroupRef && (
        <Card
          title="Group insights"
          sub="Exposure-weighted rollup of every tagged member. Advisory — member-level figures are unchanged."
          right={<AiBadge label="AI · ADVISORY rollup" />}
        >
          {insights.loading && <div className="loading">Loading insights…</div>}
          {gi && (
            <>
              <div className="grid cols-3">
                <Stat label="Members tagged" value={gi.memberCount} />
                <Stat label="Active obligors" value={gi.obligorCount} />
                <Stat label="With live application" value={gi.membersWithApplication} />
                <Stat label="Total proposed exposure" value={totalExposureLine} />
                <Stat
                  label="Weighted-avg RAROC"
                  value={gi.weightedAverageRaroc == null ? "—" : fmt.pct(gi.weightedAverageRaroc)}
                />
                <Stat
                  label="Weighted-avg PD"
                  value={gi.weightedAveragePd == null ? "—" : fmt.pct(gi.weightedAveragePd, 3)}
                />
                <Stat
                  label="Grade band"
                  value={
                    gi.highestGrade && gi.lowestGrade
                      ? `${gi.highestGrade} → ${gi.lowestGrade}`
                      : "—"
                  }
                />
                <Stat
                  label="Group grade (advisory, derived)"
                  value={gi.groupGrade ?? "—"}
                  delta={gi.groupGradeMethod || undefined}
                />
                <Stat label="Below hurdle" value={gi.membersBelowHurdle} />
                <Stat label="Rating overrides" value={gi.membersOverridden} />
              </div>

              {(gi.concentrations ?? []).length > 0 && (
                <>
                  <h4 style={{ margin: "12px 0 6px" }}>Concentrations</h4>
                  <ul style={{ margin: 0, lineHeight: 1.7 }}>
                    {gi.concentrations.map((c: string, i: number) => (
                      <li key={i}>{c}</li>
                    ))}
                  </ul>
                </>
              )}

              {(gi.riskCallouts ?? []).length > 0 && (
                <>
                  <h4 style={{ margin: "12px 0 6px" }}>Risk callouts (advisory)</h4>
                  <ul style={{ margin: 0, lineHeight: 1.7 }}>
                    {gi.riskCallouts.map((c: string, i: number) => (
                      <li key={i}>{c}</li>
                    ))}
                  </ul>
                </>
              )}

              {gi.narrative && (
                <>
                  <h4 style={{ margin: "12px 0 6px" }}>Narrative</h4>
                  <p style={{ margin: 0, lineHeight: 1.65, fontSize: 14 }}
                     dangerouslySetInnerHTML={{ __html: narrativeToHtml(gi.narrative) }} />
                </>
              )}

              <h4 style={{ margin: "12px 0 6px" }}>Members</h4>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Counterparty</th><th>Segment</th><th>App</th>
                      <th>Grade</th><th>Exposure</th><th>RAROC</th><th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(gi.members ?? []).map((m: any) => (
                      <tr key={m.counterpartyRef}>
                        <td>
                          <b>{m.counterpartyName}</b>
                          <div className="muted" style={{ fontSize: 11 }}>{m.counterpartyRef}</div>
                        </td>
                        <td>{m.segment ?? "—"}</td>
                        <td>
                          {m.latestApplicationReference ? (
                            <>
                              <span className="mono" style={{ fontSize: 11 }}>{m.latestApplicationReference}</span>
                              <div className="muted" style={{ fontSize: 11 }}>{m.applicationStatus ?? "—"}</div>
                            </>
                          ) : <span className="muted">—</span>}
                        </td>
                        <td>
                          {m.finalGrade ? (
                            <>
                              {m.finalGrade}
                              <Unchanged />
                              {m.ratingOverridden && <span className="muted" style={{ fontSize: 11, marginLeft: 4 }}>OVR</span>}
                            </>
                          ) : <span className="muted">—</span>}
                        </td>
                        <td>{m.exposure == null ? "—" : `${fmt.money(m.exposure, "")} ${m.currency ?? ""}`}</td>
                        <td>{m.raroc == null ? "—" : fmt.pct(m.raroc)}</td>
                        <td>
                          {m.belowHurdle ? <Badge kind="warn">below hurdle</Badge> : <Badge kind="ok">on/above</Badge>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </Card>
      )}

      {/* ── Combined credit proposal ── */}
      {selectedGroupRef && (
        <Card
          title="Combined credit proposal"
          sub="One advisory rollup across every member proposal. Versioned; each generation is a new immutable row."
          right={
            <GovFlow
              ai="AI DRAFTS"
              human="HUMAN SIGNS"
              note="figures quoted from each member service — never recomputed"
            />
          }
        >
          <div className="btnrow" style={{ marginBottom: 8 }}>
            <Button onClick={generateCombined} busy={generating} disabled={!selectedGroupRef}>
              Generate combined proposal
            </Button>
            {latestProposal.data && (
              <Badge kind="ai">v{latestProposal.data.version} on file</Badge>
            )}
          </div>
          {latestProposal.data && (
            <div
              className="proposal"
              style={{
                border: "1px solid var(--line)",
                borderRadius: 6,
                padding: 12,
                background: "#fff",
                maxHeight: 480,
                overflow: "auto",
              }}
              dangerouslySetInnerHTML={{ __html: latestProposal.data.html }}
            />
          )}
        </Card>
      )}

      {creatingGroup && (
        <CreateGroupModal
          init={creatingGroup}
          onClose={() => setCreatingGroup(null)}
          onCreated={(g: any, tagged: boolean) => {
            setCreatingGroup(null);
            allGroups.reload();
            counterparties.reload();
            setSelectedGroupRef(g.reference);
            if (tagged) setSuggestion(null);
          }}
        />
      )}
    </div>
  );

  // Create a borrower group (name · group RM · country · multi-country). Backend already exists
  // (POST /api/initiation/groups); this is the missing UI. When `init.tagId` is set (from the
  // AI CREATE_NEW_GROUP recommendation) it also tags that counterparty in one named-human step.
  function CreateGroupModal({
    init,
    onClose,
    onCreated,
  }: {
    init: { tagId?: number; defaultName?: string };
    onClose: () => void;
    onCreated: (g: any, tagged: boolean) => void;
  }) {
    const [name, setName] = useState(init.defaultName || "");
    const [groupRmId, setGroupRmId] = useState(actor);
    const [country, setCountry] = useState("");
    const [multiCountry, setMultiCountry] = useState(false);
    const [busy, setBusy] = useState(false);
    const submit = async () => {
      if (!name.trim()) return;
      setBusy(true);
      try {
        const g = await initiation.createGroup(
          {
            name: name.trim(),
            groupRmId: groupRmId.trim() || undefined,
            country: country.trim() || undefined,
            multiCountry,
          },
          actor,
        );
        let tagged = false;
        if (init.tagId) {
          await initiation.tagToGroup(init.tagId, g.id, actor);
          tagged = true;
        }
        notify(tagged ? `Created ${g.reference} and tagged the counterparty` : `Created group ${g.reference}`);
        onCreated(g, tagged);
      } catch (e: any) {
        notify(e.message, true);
      } finally {
        setBusy(false);
      }
    };
    return (
      <Modal
        title={init.tagId ? "Create group & tag counterparty" : "Create borrower group"}
        onClose={onClose}
        sub="A borrower group anchors the combined credit rollup. Group RM defaults to you."
        footer={
          <>
            <Button kind="subtle" onClick={onClose}>Cancel</Button>
            <Button onClick={submit} busy={busy} disabled={!name.trim()}>Create group</Button>
          </>
        }
      >
        <Field label="Group name" required>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Meridian Group" />
        </Field>
        <div className="grid cols-2">
          <Field label="Group RM" hint="Defaults to the current actor">
            <input value={groupRmId} onChange={(e) => setGroupRmId(e.target.value)} placeholder="e.g. rm.priya" />
          </Field>
          <Field label="Country" hint="Primary country (optional)">
            <input value={country} onChange={(e) => setCountry(e.target.value)} placeholder="e.g. IN" />
          </Field>
        </div>
        <label className="inline" style={{ fontSize: 13, marginTop: 4 }}>
          <input type="checkbox" style={{ width: "auto" }} checked={multiCountry} onChange={(e) => setMultiCountry(e.target.checked)} />{" "}
          Multi-country group
        </label>
        {init.tagId != null && (
          <div className="gate" style={{ marginTop: 10 }}>
            On create, this counterparty is tagged to the new group — a named-human action, audited.
          </div>
        )}
      </Modal>
    );
  }
}

/** Derives a sensible default group name from a counterparty's legal name (strips the legal form). */
function deriveGroupName(subjectLegalName?: string): string {
  const base = (subjectLegalName || "")
    .replace(/\b(Private|Public|Pvt|Ltd|Limited|LLP|LLC|Inc|Co|Corp|PLC)\b\.?/gi, "")
    .replace(/\s+/g, " ")
    .trim();
  return `${base || "New"} Group`;
}

function narrativeToHtml(s: string): string {
  // Minimal: bold via **…**, italics via _…_
  return s
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/\*\*(.+?)\*\*/g, "<b>$1</b>")
    .replace(/_(.+?)_/g, "<i>$1</i>");
}
