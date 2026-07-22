/**
 * Downstream canonical export feeds (ERM / Finance-GL / CPR), idempotent batches,
 * examiner-retrievable. Symmetric counterpart to connector ingestion — every write
 * produces a versioned Envelope with a stable idempotencyKey for the as-of day.
 */
import { useState } from "react";
import { exports as feeds, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, type Col, DataTable, EmptyState, useAsync } from "../ui";

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
  if (dest === "CRILC") return "warn";
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
  const [showAll, setShowAll] = useState(false);
  const { envelope } = batch;
  const all = envelope?.records ?? [];
  const records = showAll ? all : all.slice(0, 50);

  if (records.length === 0) {
    return (
      <div className="muted">No records — book some exposures / compute ECL first.</div>
    );
  }

  let table: any = null;

  if (batch.destination === "ERM") {
    table = (
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
    table = (
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
    table = (
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

  if (batch.destination === "CRILC") {
    table = (
      <table>
        <thead>
          <tr>
            <th>Borrower Ref</th>
            <th>Name</th>
            <th>Jurisdiction</th>
            <th className="num">Aggregate Exposure</th>
            <th>Classification</th>
            <th>SMA</th>
            <th className="num">Max DPD</th>
          </tr>
        </thead>
        <tbody>
          {records.map((r: any, i: number) => (
            <tr key={i}>
              <td className="mono">{r.borrowerRef}</td>
              <td>{r.name}</td>
              <td>{r.jurisdiction}</td>
              <td className="num">{fmt.money(r.aggregateExposure, r.currency)}</td>
              <td><Badge>{r.assetClassification}</Badge></td>
              <td>{r.smaClass}</td>
              <td className="num">{r.maxDaysPastDue}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  if (!table) return <div className="muted">Unknown destination — raw records not renderable.</div>;

  return (
    <>
      <div className="table-scroll">{table}</div>
      {all.length > 50 && (
        <div className="table-more">
          <span>showing {records.length} of {all.length}</span>
          <button onClick={() => setShowAll((s) => !s)}>{showAll ? "show less" : "show all"}</button>
        </div>
      )}
    </>
  );
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

  const batchCols: Col<ExportBatch>[] = [
    { key: "id", header: "ID", width: "64px", render: (b) => <span className="mono">{b.id}</span>, value: (b) => b.id },
    { key: "destination", header: "Destination", render: (b) => <Badge kind={destBadgeKind(b.destination)}>{b.destination}</Badge>, value: (b) => b.destination ?? "" },
    { key: "feedType", header: "Feed Type", render: (b) => <span className="mono">{b.feedType}</span>, value: (b) => b.feedType ?? "" },
    { key: "asOf", header: "As-Of", render: (b) => fmt.date(b.asOf), value: (b) => b.asOf ?? "" },
    { key: "recordCount", header: "Records", align: "right", render: (b) => b.recordCount, value: (b) => b.recordCount ?? 0 },
    { key: "status", header: "Status", render: (b) => <Badge kind={b.status === "DELIVERED" ? "ok" : "info"}>{b.status}</Badge>, value: (b) => b.status ?? "" },
    { key: "generatedBy", header: "Generated By", render: (b) => b.generatedBy, value: (b) => b.generatedBy ?? "" },
    { key: "createdAt", header: "Created At", render: (b) => fmt.dateTime(b.createdAt), value: (b) => b.createdAt ?? "" },
  ];

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
            <Button onClick={() => generate(feeds.crilc, "CRILC feed")}>Generate CRILC feed</Button>
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
        {!batches.loading && !batches.error && (
          <DataTable
            id="exports-batches"
            columns={batchCols}
            rows={batchList}
            rowKey={(b) => String(b.id)}
            onRowClick={(b) => setSelectedId(b.id === selectedId ? null : b.id)}
            rowClassName={(b) => (b.id === selectedId ? "dt-row-selected" : undefined)}
            empty={
              <EmptyState
                glyph="⇪"
                title="No export batches yet"
                sub="Generate one with the buttons above. Re-running the same as-of day is idempotent — you'll get the existing batch back, not a duplicate."
              />
            }
          />
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
              <RecordsTable key={detail.data.id} batch={detail.data} />
            </>
          )}
        </Card>
      )}
    </div>
  );
}
