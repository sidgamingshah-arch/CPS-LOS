/**
 * Syndication agency desk — the mechanics on top of syndicate participation capture.
 *
 *   1. Syndicate book — lenders with commitment, share, funded-to-date, undrawn.
 *   2. Fee waterfall — arrangement / underwriting / agency to the lead/agent;
 *      participation fee to each lender on its share.
 *   3. Agency reconciliation — allocate a funded drawdown pro-rata (idempotent).
 *   4. Participant feed — the canonical downstream statement per lender.
 *
 * Deterministic figures throughout — fees and allocations are computed, not drafted.
 */
import { useState } from "react";
import { fmt, origination, syndication } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, DeterministicBadge, EmptyState, Field, Stat, useAsync } from "../ui";

export default function Syndication() {
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>("");

  const book = useAsync(() => (ref ? safe(syndication.book(ref)) : Promise.resolve(null)), [ref]);
  const ledger = useAsync(() => (ref ? safe(syndication.allocations(ref)) : Promise.resolve([])), [ref]);

  function safe<T>(p: Promise<T>): Promise<T | null> {
    return p.catch(() => null as any);
  }

  async function allocate() {
    if (!ref) return;
    const dref = prompt("Drawdown reference (e.g. DRAW-3)") || "";
    if (!dref) return;
    const amtStr = prompt("Funded amount") || "";
    const amt = parseFloat(amtStr.replace(/[, ]/g, ""));
    if (!amt || amt <= 0) return;
    try {
      const res = await syndication.allocate(ref, { drawdownRef: dref, amount: amt }, actor);
      notify(res.reused ? "Drawdown already allocated (idempotent)" : "Drawdown allocated pro-rata");
      book.reload(); ledger.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function downloadFeed() {
    if (!ref) return;
    try {
      const feed = await syndication.feed(ref);
      notify(`Participant feed generated — ${feed.recordCount} lines to ${feed.destination}`);
    } catch (e: any) { notify(e.message, true); }
  }

  const b = book.data;

  return (
    <div className="grid">
      <Card title="Syndication agency"
        sub="Fee waterfall · agency reconciliation · participant statements — the agent-bank mechanics on top of the syndicate participation capture."
        right={<DeterministicBadge label="DETERMINISTIC · COMPUTED" />}>
        <Field label="Syndicated deal">
          <select value={ref} onChange={(e) => setRef(e.target.value)}>
            <option value="">— select —</option>
            {(apps.data || []).map((a: any) => (
              <option key={a.reference} value={a.reference}>{a.counterpartyName} · {a.reference}</option>
            ))}
          </select>
        </Field>
        {ref && !b && (
          <EmptyState glyph="◌" title="Not a syndicated deal (or no lenders captured)"
            sub="Set the deal structure to SYNDICATION and add LEAD_BANK + PARTICIPANT_LENDER participants in Deal Structuring." />
        )}
      </Card>

      {b && (
        <Card title="Syndicate book"
          right={
            <div style={{ display: "flex", gap: 8 }}>
              <Button kind="primary" onClick={allocate}>Allocate drawdown</Button>
              <Button kind="subtle" onClick={downloadFeed}>Generate participant feed</Button>
            </div>
          }>
          <div className="grid cols-4" style={{ marginBottom: 12 }}>
            <Stat label="Facility (syndicated)" value={fmt.money(b.facilityAmount, b.currency)} />
            <Stat label="Total committed" value={fmt.money(b.totalCommitment, b.currency)} />
            <Stat label="Total funded" value={fmt.money(b.totalFunded, b.currency)} />
            <Stat label="Total fees" value={fmt.money(b.feeTotals.totalFee, b.currency)} />
          </div>
          <table>
            <thead>
              <tr>
                <th>Lender</th><th>Role</th><th className="num">Commitment</th><th className="num">Share</th>
                <th className="num">Funded</th><th className="num">Undrawn</th>
                <th className="num">Arr.</th><th className="num">U/W</th><th className="num">Agency</th>
                <th className="num">Particip.</th><th className="num">Total fee</th>
              </tr>
            </thead>
            <tbody>
              {b.lenders.map((l: any) => (
                <tr key={l.participantId}>
                  <td>{l.name}</td>
                  <td>
                    <Badge kind={l.role === "LEAD_BANK" ? "info" : ""}>
                      {l.role === "LEAD_BANK" ? "LEAD / AGENT" : "PARTICIPANT"}
                    </Badge>
                  </td>
                  <td className="num">{fmt.money(l.commitment, b.currency)}</td>
                  <td className="num">{fmt.pct(l.sharePct)}</td>
                  <td className="num">{fmt.money(l.fundedToDate, b.currency)}</td>
                  <td className="num">{fmt.money(l.undrawn, b.currency)}</td>
                  <td className="num">{l.fees.arrangementFee ? fmt.money(l.fees.arrangementFee, b.currency) : "—"}</td>
                  <td className="num">{l.fees.underwritingFee ? fmt.money(l.fees.underwritingFee, b.currency) : "—"}</td>
                  <td className="num">{l.fees.agencyFee ? fmt.money(l.fees.agencyFee, b.currency) : "—"}</td>
                  <td className="num">{fmt.money(l.fees.participationFee, b.currency)}</td>
                  <td className="num"><b>{fmt.money(l.fees.totalFee, b.currency)}</b></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {b && (ledger.data ?? []).length > 0 && (
        <Card title="Agency reconciliation ledger"
          sub="Every funded drawdown allocated pro-rata to lenders. Idempotent per drawdown reference.">
          <table>
            <thead>
              <tr><th>Drawdown</th><th>Lender</th><th className="num">Share</th><th className="num">Drawdown</th><th className="num">Allocated</th></tr>
            </thead>
            <tbody>
              {(ledger.data ?? []).map((a: any) => (
                <tr key={a.id}>
                  <td className="mono">{a.drawdownRef}</td>
                  <td>{a.participantName}</td>
                  <td className="num">{fmt.pct(a.sharePct)}</td>
                  <td className="num">{fmt.money(a.drawdownAmount, a.currency)}</td>
                  <td className="num">{fmt.money(a.allocatedAmount, a.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}
