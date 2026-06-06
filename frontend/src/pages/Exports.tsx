/**
 * Downstream canonical export feeds (ERM / Finance-GL / CPR), idempotent batches,
 * examiner-retrievable. Symmetric counterpart to connector ingestion — every write
 * produces a versioned Envelope with a stable idempotencyKey for the as-of day.
 */
import { useState } from "react";
import { exports as feeds, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, useAsync } from "../ui";

type ExportBatch = {
  id: number;
  destination: string;
  feedType: string;
  idempotencyKey: string;
  asOf: string;
  recordCount: number;
  status: string;
  generatedBy: string;
  createdAt: string;
  envelope: {
    destination: string;
    feedType: string;
    idempotencyKey: string;
    payloadVersion: string;
    generatedAt: string;
    recordCount: number;
    records: any[];
  };
};

function destBadgeKind(dest: string): string {
  if (dest === "ERM") return "info";
  if (dest === "FINANCE_GL") return "ai";
  if (dest === "CPR") return "ok";
  return "";
}

function eclStageBadgeKind(stage: string): string {
  if (stage === "STAGE_1") return "ok";
  if (stage === "STAGE_2") return "warn";
  if (stage === "STAGE_3") return "bad";
  return "";
}

function gradeBadgeKind(grade: string): string {
  if (!grade) return "";
  if (["AAA", "AA", "A", "BBB"].includes(grade)) return "ok";
  if (["BB", "B"].includes(grade)) return "warn";
  return "bad";
}

function stageBadgeKind(stage: string): string {
  if (stage === "STAGE_1") return "ok";
  if (stage === "STAGE_2") return "warn";
  if (stage === "STAGE_3") return "bad";
  return "";
}

function RecordsTable({ batch }: { batch: ExportBatch }) {
  const { envelope } = batch;
  const records = (envelope?.records ?? []).slice(0, 50);

  if (records.length === 0) {
    return (
      <div className="muted">No records — book some exposures / compute ECL first.</div>
    );
  }

  if (batch.destination === "ERM") {
    return (
      <table>
        <thead>
          <tr>
            <th>Obligor Ref</th>
            <th>Name</th>
            <th>Grade</th>
            <th className="num">EAD</th>
            <th className="num">RWA</th>
            <th className="num">Capital Required</th>
            <th>ECL Stage</th>
            <th className="num">ECL</th>
          </tr>
        </thead>
        <tbody>
          {records.map((r: any, i: number) => (
            <tr key={i}>
              <td className="mono">{r.obligorRef}</td>
              <td>{r.name}</td>
              <td><Badge kind={gradeBadgeKind(r.grade)}>{r.grade}</Badge></td>
              <td className="num">{fmt.money(r.ead, r.currency)}</td>
              <td className="num">{fmt.money(r.rwa, r.currency)}</td>
              <td className="num">{fmt.money(r.capitalRequired, r.currency)}</td>
              <td><Badge kind={eclStageBadgeKind(r.eclStage)}>{r.eclStage}</Badge></td>
              <td className="num">{fmt.money(r.ecl, r.currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  if (batch.destination === "FINANCE_GL") {
    return (
      <table>
        <thead>
          <tr>
            <th>Obligor Ref</th>
            <th>GL Account</th>
            <th>Stage</th>
            <th className="num">Provision Amount</th>
            <th>Policy</th>
            <th>Currency</th>
          </tr>
        </thead>
        <tbody>
          {records.map((r: any, i: number) => (
            <tr key={i}>
              <td className="mono">{r.obligorRef}</td>
              <td className="mono">{r.glAccount}</td>
              <td><Badge kind={stageBadgeKind(r.stage)}>{r.stage}</Badge></td>
              <td className="num">{fmt.money(r.provisionAmount, r.currency)}</td>
              <td>{r.policy}</td>
              <td>{r.currency}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  if (batch.destination === "CPR") {
    return (
      <table>
        <thead>
          <tr>
            <th>Dimension</th>
            <th>Bucket</th>
            <th className="num">EAD</th>
            <th className="num">Share</th>
          </tr>
        </thead>
        <tbody>
          {records.map((r: any, i: number) => (
            <tr key={i}>
              <td>{r.dimension}</td>
              <td>{r.bucket}</td>
              <td className="num">{fmt.money(r.ead, "")}</td>
              <td className="num">{r.sharePct != null ? r.sharePct + "%" : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  return <div className="muted">Unknown destination — raw records not renderable.</div>;
}

/** Exports page: downstream canonical export feeds (ERM/Finance-GL/CPR), idempotent batches, examiner-retrievable. */
export default function Exports() {
  const { actor, notify } = useApp();
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const batches = useAsync(() => feeds.batches(), []);
  const detail = useAsync<ExportBatch | null>(
    () => (selectedId != null ? feeds.batch(selectedId) : Promise.resolve(null)),
    [selectedId]
  );

  const generate = async (
    fn: (a: string) => Promise<ExportBatch>,
    label: string
  ) => {
    try {
      const batch = await fn(actor);
      notify(`${label} — ${batch.recordCount} record(s)`);
      batches.reload();
      setSelectedId(batch.id);
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const batchList: ExportBatch[] = batches.data ?? [];

  return (
    <div className="grid">
      <Card
        title="Outbound export feeds"
        sub="Canonical downstream feeds — ERM (obligor risk), Finance/GL (provision entries), CPR (portfolio composition). Each batch is idempotent per as-of day; re-triggering on the same day returns the same idempotency key."
        right={
          <div className="btnrow">
            <Button onClick={() => generate(feeds.erm, "ERM feed")}>Generate ERM feed</Button>
            <Button onClick={() => generate(feeds.financeGl, "Finance/GL feed")}>Generate Finance/GL feed</Button>
            <Button onClick={() => generate(feeds.cpr, "CPR feed")}>Generate CPR feed</Button>
          </div>
        }
      >
        <></>
      </Card>

      <Card
        title="Export batches"
        sub={batches.loading ? "Loading…" : `${batchList.length} batch(es)`}
      >
        {batches.loading && <div className="loading">Loading batches…</div>}
        {batches.error && <div className="alert err">{batches.error}</div>}
        {!batches.loading && batchList.length === 0 && (
          <div className="muted">No export batches yet. Use the buttons above to generate a feed.</div>
        )}
        {batchList.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Destination</th>
                <th>Feed Type</th>
                <th>As-Of</th>
                <th className="num">Records</th>
                <th>Status</th>
                <th>Generated By</th>
                <th>Created At</th>
              </tr>
            </thead>
            <tbody>
              {batchList.map((b) => (
                <tr
                  key={b.id}
                  className="rowlink"
                  onClick={() => setSelectedId(b.id === selectedId ? null : b.id)}
                  style={{ background: b.id === selectedId ? "var(--hover)" : undefined }}
                >
                  <td className="mono">{b.id}</td>
                  <td><Badge kind={destBadgeKind(b.destination)}>{b.destination}</Badge></td>
                  <td className="mono">{b.feedType}</td>
                  <td>{b.asOf}</td>
                  <td className="num">{b.recordCount}</td>
                  <td>
                    <Badge kind={b.status === "DELIVERED" ? "ok" : "info"}>{b.status}</Badge>
                  </td>
                  <td>{b.generatedBy}</td>
                  <td>{new Date(b.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {selectedId != null && (
        <Card
          title={`Batch #${selectedId} — envelope`}
          sub={detail.loading ? "Loading…" : detail.data ? `${detail.data.destination} · ${detail.data.feedType}` : ""}
        >
          {detail.loading && <div className="loading">Loading batch detail…</div>}
          {detail.error && <div className="alert err">{detail.error}</div>}
          {detail.data && (
            <>
              <div className="prov" style={{ marginBottom: 8 }}>
                <span>{detail.data.destination}</span>
                {" · "}
                <span className="mono">{detail.data.feedType}</span>
                {" · v"}
                <span>{detail.data.envelope?.payloadVersion}</span>
                {" · "}
                <span className="num">{detail.data.recordCount}</span>
                {" records · "}
                <span className="mono">{detail.data.idempotencyKey}</span>
              </div>
              <RecordsTable batch={detail.data} />
            </>
          )}
        </Card>
      )}
    </div>
  );
}
