/**
 * PricingLab — pricing scenario optimiser; goal-seek RAROC/rate/fee/collateral
 * constraints against a target RAROC. All outputs are ADVISORY; the authoritative
 * pricing produced by the risk engine is never modified.
 */
import { useState } from "react";
import { origination, risk, optimiser, config, fmt } from "../api";
import { useApp } from "../app-context";
import { AiBadge, Badge, Button, Card, DeterministicBadge, EmptyState, Field, HumanBadge, Stat, Unchanged, useAsync } from "../ui";

interface PricingException {
  id: number;
  applicationReference: string;
  recommendedRate: number;
  proposedRate: number;
  concessionBps: number;
  proposedRaroc: number;
  hurdleRaroc: number;
  belowHurdle: boolean;
  ead: number;
  requiredAuthority: "RELATIONSHIP_HEAD" | "CREDIT_OFFICER" | "CREDIT_HEAD" | "CREDIT_COMMITTEE" | "NONE";
  requiredLevels: 0 | 1 | 2;
  status: "PENDING_L1" | "PENDING_L2" | "APPROVED" | "REJECTED";
  reason: string;
  proposedBy: string;
  approverL1?: string;
  approverL2?: string;
  decisionComment?: string;
  breakdown: Record<string, unknown>;
}

interface OptimiserForm {
  targetRaroc: string;
  rateCap: string;
  feeBpsCap: string;
  maxCollateralCover: string;
}

interface Scenario {
  name: string;
  rate: number;
  feeBps: number;
  lgdAfterCollateral: number;
  raroc: number;
  meetsTarget: boolean;
  constraintHit?: string;
  breakdown: Record<string, any>;
}

interface OptimisationResult {
  applicationReference: string;
  baselineRate: number;
  baselineRaroc: number;
  targetRaroc: number;
  hurdleRaroc: number;
  achievable: boolean;
  recommended: Scenario;
  scenarios: Scenario[];
  advisory: string;
}

const BLANK_FORM: OptimiserForm = {
  targetRaroc: "20",
  rateCap: "15",
  feeBpsCap: "200",
  maxCollateralCover: "50",
};

const PRESET_STRETCH: OptimiserForm = {
  targetRaroc: "20",
  rateCap: "15",
  feeBpsCap: "200",
  maxCollateralCover: "50",
};

const PRESET_CONSERVATIVE: OptimiserForm = {
  targetRaroc: "17",
  rateCap: "12",
  feeBpsCap: "100",
  maxCollateralCover: "30",
};

export default function PricingLab() {
  const { actor, notify, ref: ctxRef } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>(ctxRef ?? "");
  const [form, setForm] = useState<OptimiserForm>(BLANK_FORM);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OptimisationResult | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);

  // Concession / exception state
  const [excProposedRate, setExcProposedRate] = useState<string>("");
  const [excReason, setExcReason] = useState<string>("");
  const [excBusy, setExcBusy] = useState(false);

  const exceptionsAsync = useAsync<PricingException[]>(
    () => (ref ? optimiser.listExceptions(ref) : Promise.resolve([])),
    [ref],
  );

  const summaryAsync = useAsync(
    () => (ref ? risk.summary(ref) : Promise.reject(new Error("no-ref"))),
    [ref],
  );

  const sum = summaryAsync.data;
  const pricing = sum?.pricing ?? null;
  const detail = pricing?.detail ?? null;
  const perFacility: any[] = detail?.perFacility ?? [];
  const peer = detail?.peer ?? null;

  // Admin: the configured hurdle (flat + any per-segment override) read live from the PRICING
  // rule pack for this deal's jurisdiction. Editing stays under maker-checker in Rule Packs — this
  // surface makes the (config-driven, never hardcoded) hurdle visible where pricing is decided.
  const selectedApp = (apps.data ?? []).find((a: any) => a.reference === ref) ?? null;
  const jurisdiction: string = selectedApp?.jurisdiction ?? "";
  const segment: string = selectedApp?.segment ?? "";
  const pricingPackAsync = useAsync(
    () => (jurisdiction ? config.pack(jurisdiction, "PRICING").catch(() => null) : Promise.resolve(null)),
    [jurisdiction],
  );
  const pricingPack = pricingPackAsync.data;
  const hurdleOverrides: Record<string, number> = pricingPack?.payload?.hurdle_raroc_overrides ?? {};
  const flatHurdle: number | undefined = pricingPack?.payload?.hurdle_raroc;
  const segmentOverride = segment && hurdleOverrides[segment] != null ? hurdleOverrides[segment] : undefined;
  const resolvedHurdle = segmentOverride ?? flatHurdle;

  const handleOptimise = async () => {
    if (!ref) { notify("Select a deal first", true); return; }
    setBusy(true);
    try {
      const body = {
        targetRaroc: Number(form.targetRaroc) / 100,
        rateCap: Number(form.rateCap) / 100,
        feeBpsCap: Number(form.feeBpsCap),
        maxCollateralCover: Number(form.maxCollateralCover) / 100,
      };
      const res: OptimisationResult = await optimiser.optimise(ref, body, actor);
      setResult(res);
      notify(
        `Optimised — recommended scenario "${res.recommended.name}" with RAROC ${fmt.pct(res.recommended.raroc)}`,
      );
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setBusy(false);
    }
  };

  const setField = (key: keyof OptimiserForm) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const handleProposeException = async () => {
    if (!ref) { notify("Select a deal first", true); return; }
    if (!excProposedRate) { notify("Enter a proposed rate", true); return; }
    setExcBusy(true);
    try {
      await optimiser.proposeException(
        ref,
        { proposedRate: Number(excProposedRate) / 100, reason: excReason },
        actor,
      );
      notify("Concession proposed — pending authority approval");
      setExcProposedRate("");
      setExcReason("");
      exceptionsAsync.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setExcBusy(false);
    }
  };

  const handleDecideException = async (exc: PricingException, approve: boolean) => {
    const promptMsg = approve
      ? "Optional approval comment:"
      : "Reason for rejection (required):";
    const comment = window.prompt(promptMsg) ?? "";
    if (!approve && !comment.trim()) {
      notify("Rejection reason is required", true);
      return;
    }
    try {
      await optimiser.decideException(exc.id, { approve, comment }, actor);
      notify(approve ? "Exception approved" : "Exception rejected");
      exceptionsAsync.reload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const breakdownEntries = result
    ? Object.entries(result.recommended.breakdown)
    : [];

  return (
    <div className="grid">
      {/* Governance banner */}
      <div className="gov-banner">
        <h3>Optimise freely. The price of record stays preserved.</h3>
        <div className="gb-sub">
          The goal-seek optimiser explores rate, fee and collateral mixes; concessions route through a
          maker-checker authority workflow. Through all of it, the <b>authoritative pricing is never overwritten</b>.
        </div>
        <div className="gb-chips">
          <span className="gb-chip"><b>AI</b> · scenario optimiser</span>
          <span className="gb-chip"><b>Human</b> · concession approval (SoD, 1–2 levels)</span>
          <span className="gb-chip"><b>Deterministic</b> · RAROC engine</span>
        </div>
      </div>

      {/* Deal selector + authoritative pricing stats */}
      <Card title="Deal selector" right={pricing ? <Unchanged label="PRICING OF RECORD · PRESERVED" /> : undefined}>
        <Field label="Application">
          <select
            value={ref}
            onChange={(e) => { setRef(e.target.value); setResult(null); }}
          >
            <option value="">— select a deal —</option>
            {(apps.data ?? []).map((a: any) => (
              <option key={a.reference} value={a.reference}>
                {a.reference} · {a.counterpartyName} · {a.status}
              </option>
            ))}
          </select>
        </Field>

        {ref && (
          <div style={{ marginTop: 14 }}>
            {summaryAsync.loading && <div className="loading">Loading pricing…</div>}
            {!summaryAsync.loading && summaryAsync.error && (
              <div className="muted">Price the deal first to see authoritative pricing here.</div>
            )}
            {!summaryAsync.loading && pricing && (
              <div className="grid cols-3">
                <Stat label="Recommended rate" value={<span className="num">{fmt.pct(pricing.recommendedRate)}</span>} />
                <Stat label="RAROC" value={<span className="num">{fmt.pct(pricing.raroc)}</span>} />
                <Stat label="Hurdle RAROC" value={<span className="num">{fmt.pct(pricing.hurdleRaroc)}</span>} />
                <Stat
                  label="vs Hurdle"
                  value={
                    <Badge kind={pricing.belowHurdle ? "bad" : "ok"}>
                      {pricing.belowHurdle ? "Below hurdle" : "Meets hurdle"}
                    </Badge>
                  }
                />
              </div>
            )}
          </div>
        )}
      </Card>

      {/* Dual-approach recommended price: deterministic RAROC price beside the peer benchmark. */}
      {ref && pricing && (
        <Card
          title="Recommended price — two approaches"
          sub="The deterministic RAROC-based price sits beside the peer/benchmark price. Both are shown; they are never silently blended — a human weighs them."
          right={<DeterministicBadge label="RAROC · DETERMINISTIC" />}
        >
          <div className="grid cols-3">
            <Stat
              label="RAROC-based rate"
              value={<span className="num">{fmt.pct(pricing.recommendedRate)}</span>}
            />
            <Stat
              label="Peer / benchmark rate"
              value={
                peer?.available
                  ? <span className="num">{fmt.pct(peer.peerRate)}</span>
                  : <span className="muted">—</span>
              }
            />
            <Stat
              label="RAROC − peer"
              value={
                peer?.available
                  ? <Badge kind={peer.rarocMinusPeerBps >= 0 ? "ok" : "warn"}>
                      {peer.rarocMinusPeerBps >= 0 ? "+" : ""}{peer.rarocMinusPeerBps} bps
                    </Badge>
                  : <span className="muted">n/a</span>
              }
            />
          </div>
          {peer?.available ? (
            <div className="prov" style={{ marginTop: 10 }}>
              Peer source: {peer.source || "—"} · matched key <span className="mono">{peer.matchedKey}</span> ·
              basis {peer.basis === "ALL_IN_RATE" ? "all-in rate" : "spread over cost of funds"}.
              {" "}Advisory presentation; the RAROC price above is the deterministic figure of record.
            </div>
          ) : (
            <div className="muted" style={{ marginTop: 10 }}>
              No PEER_PRICING benchmark configured for this segment/grade/product — showing the RAROC price only (fail-soft).
            </div>
          )}
        </Card>
      )}

      {/* Per-facility RAROC pricing — each proposed facility priced on its own EAD/RWA. */}
      {ref && pricing && perFacility.length > 0 && (
        <Card
          title="Per-facility pricing"
          sub="Each proposed facility priced on its own deterministic EAD + RWA and term-structured funding. The deal aggregate above is the authoritative pricing of record."
          right={<DeterministicBadge label="PER-FACILITY · DETERMINISTIC" />}
        >
          <table>
            <thead>
              <tr>
                <th>Facility</th>
                <th>Type</th>
                <th className="num">EAD</th>
                <th className="num">RWA</th>
                <th className="num">Recommended rate</th>
                <th className="num">RAROC</th>
                <th className="num">Hurdle</th>
                <th>vs Hurdle</th>
              </tr>
            </thead>
            <tbody>
              {perFacility.map((f: any) => (
                <tr key={f.facilityReference}>
                  <td className="mono">{f.facilityReference}{f.primary && <span className="muted"> · primary</span>}</td>
                  <td>{f.facilityType}</td>
                  <td className="num">{fmt.money(f.ead)}</td>
                  <td className="num">{fmt.money(f.rwa)}</td>
                  <td className="num">{fmt.pct(f.recommendedRate)}</td>
                  <td className="num">{fmt.pct(f.raroc)}</td>
                  <td className="num">{fmt.pct(f.hurdleRaroc)}</td>
                  <td>
                    <Badge kind={f.belowHurdle ? "bad" : "ok"}>
                      {f.belowHurdle ? "Below" : "Meets"}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {/* Admin: the configurable hurdle RAROC (config-driven, dual-signed in Rule Packs). */}
      {ref && (
        <Card
          title="Hurdle configuration (admin)"
          sub="The RAROC hurdle is read from the PRICING rule pack — never hardcoded. It is authored under dual sign-off in Rule Packs; this panel makes the active value (and any per-segment override) visible where pricing is decided."
          right={<HumanBadge label="CONFIG · MAKER-CHECKER" />}
        >
          {pricingPackAsync.loading && <div className="loading">Loading pricing pack…</div>}
          {!pricingPackAsync.loading && !pricingPack && (
            <div className="muted">Pricing pack unavailable for {jurisdiction || "this jurisdiction"}.</div>
          )}
          {pricingPack && (
            <>
              <div className="grid cols-3">
                <Stat label="Flat hurdle RAROC" value={<span className="num">{flatHurdle != null ? fmt.pct(flatHurdle) : "—"}</span>} />
                <Stat
                  label={`Applies to ${segment || "this deal"}`}
                  value={<span className="num">{resolvedHurdle != null ? fmt.pct(resolvedHurdle) : "—"}</span>}
                />
                <Stat
                  label="Per-segment override"
                  value={segmentOverride != null
                    ? <Badge kind="info">{fmt.pct(segmentOverride)}</Badge>
                    : <span className="muted">none</span>}
                />
              </div>
              {Object.keys(hurdleOverrides).length > 0 && (
                <table style={{ marginTop: 10 }}>
                  <thead><tr><th>Segment</th><th className="num">Hurdle override</th></tr></thead>
                  <tbody>
                    {Object.entries(hurdleOverrides).map(([seg, v]) => (
                      <tr key={seg}><td className="mono">{seg}</td><td className="num">{fmt.pct(v as number)}</td></tr>
                    ))}
                  </tbody>
                </table>
              )}
              <div className="prov" style={{ marginTop: 10 }}>
                Source: rule pack <span className="mono">{pricingPack.code} v{pricingPack.version}</span> ·
                type PRICING · {jurisdiction}. Edit under maker-checker in Rule Packs — changes there flow straight into the RAROC price.
              </div>
            </>
          )}
        </Card>
      )}

      {!ref && (
        <Card>
          <EmptyState
            glyph="◎"
            title="Select a deal to optimise pricing"
            sub="Pick an application above. The goal-seek optimiser proposes rate/fee/collateral scenarios toward a target RAROC — advisory only; the authoritative pricing of record is never mutated."
          />
        </Card>
      )}

      {/* Optimiser form */}
      {ref && (
      <Card title="Optimiser constraints">
        <div className="btnrow" style={{ marginBottom: 10 }}>
          <Button kind="ghost" onClick={() => setForm(PRESET_STRETCH)}>
            Stretch (20%)
          </Button>
          <Button kind="ghost" onClick={() => setForm(PRESET_CONSERVATIVE)}>
            Conservative (17%)
          </Button>
        </div>

        <div className="grid cols-2">
          <Field label="Target RAROC (%)">
            <input type="number" value={form.targetRaroc} onChange={setField("targetRaroc")} />
          </Field>
          <Field label="Rate cap (%)">
            <input type="number" value={form.rateCap} onChange={setField("rateCap")} />
          </Field>
          <Field label="Fee cap (bps)">
            <input type="number" value={form.feeBpsCap} onChange={setField("feeBpsCap")} />
          </Field>
          <Field label="Max collateral cover (%)">
            <input type="number" value={form.maxCollateralCover} onChange={setField("maxCollateralCover")} />
          </Field>
        </div>

        <div className="btnrow" style={{ marginTop: 8 }}>
          <Button onClick={handleOptimise} disabled={!ref} busy={busy}>
            Optimise
          </Button>
          <span className="muted">Acting as {actor}</span>
        </div>
      </Card>
      )}

      {/* Pricing exceptions / concessions */}
      {ref && (
        <Card
          title="Pricing exceptions / concessions"
          sub="An RM proposes a rate below the recommended rate; the concession is routed to an authority tier by size and hurdle breach, and approved via maker-checker SoD (1 or 2 levels)."
        >
          {/* Propose concession form */}
          <div className="grid cols-2" style={{ marginBottom: 12 }}>
            <Field label="Proposed rate (%)">
              <input
                type="number"
                step="0.01"
                value={excProposedRate}
                onChange={(e) => setExcProposedRate(e.target.value)}
                placeholder="e.g. 7.5"
              />
            </Field>
            <Field label="Reason">
              <input
                type="text"
                value={excReason}
                onChange={(e) => setExcReason(e.target.value)}
                placeholder="Relationship / competitive pressure…"
              />
            </Field>
          </div>
          <div className="btnrow" style={{ marginBottom: 16 }}>
            <Button onClick={handleProposeException} disabled={!excProposedRate} busy={excBusy}>
              Propose
            </Button>
            <span className="muted">Acting as {actor}</span>
          </div>

          {/* Exceptions table */}
          {exceptionsAsync.loading && <div className="loading">Loading exceptions…</div>}
          {!exceptionsAsync.loading && !exceptionsAsync.error && (exceptionsAsync.data ?? []).length === 0 && (
            <div className="muted">No exceptions raised for this deal.</div>
          )}
          {!exceptionsAsync.loading && (exceptionsAsync.data ?? []).length > 0 && (
            <table>
              <thead>
                <tr>
                  <th className="num">Proposed rate</th>
                  <th className="num">Concession</th>
                  <th className="num">Proposed RAROC</th>
                  <th>Below hurdle</th>
                  <th>Required authority</th>
                  <th>Status</th>
                  <th>Proposed by</th>
                  <th>Action / decision</th>
                </tr>
              </thead>
              <tbody>
                {(exceptionsAsync.data ?? []).map((exc: PricingException) => {
                  const isPending = exc.status === "PENDING_L1" || exc.status === "PENDING_L2";
                  const statusTone =
                    isPending ? "warn" : exc.status === "APPROVED" ? "ok" : "bad";
                  const statusLabel =
                    exc.status === "PENDING_L1" ? "Pending L1"
                    : exc.status === "PENDING_L2" ? "Pending L2"
                    : exc.status === "APPROVED" ? "Approved"
                    : "Rejected";
                  return (
                    <tr key={exc.id}>
                      <td className="num">{fmt.pct(exc.proposedRate)}</td>
                      <td className="num">{exc.concessionBps} bps</td>
                      <td className="num">{fmt.pct(exc.proposedRaroc)}</td>
                      <td>
                        <Badge kind={exc.belowHurdle ? "bad" : "ok"}>
                          {exc.belowHurdle ? "Yes" : "No"}
                        </Badge>
                      </td>
                      <td>
                        <Badge kind="info">{exc.requiredAuthority}</Badge>
                      </td>
                      <td>
                        <Badge kind={statusTone}>{statusLabel}</Badge>
                      </td>
                      <td className="mono">{exc.proposedBy}</td>
                      <td>
                        {isPending ? (
                          <div className="btnrow">
                            <Button kind="subtle" onClick={() => handleDecideException(exc, true)}>
                              Approve
                            </Button>
                            <Button kind="subtle" onClick={() => handleDecideException(exc, false)}>
                              Reject
                            </Button>
                          </div>
                        ) : (
                          <div>
                            {exc.approverL1 && (
                              <small className="prov">L1: {exc.approverL1}</small>
                            )}
                            {exc.approverL2 && (
                              <small className="prov"> · L2: {exc.approverL2}</small>
                            )}
                            {exc.decisionComment && (
                              <div><small className="prov">{exc.decisionComment}</small></div>
                            )}
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {/* Result */}
      {result && (
        <>
          {/* Header stats */}
          <Card title="Optimisation result" right={<Badge kind="ai">AI · advisory</Badge>}>
            <div className="grid cols-3">
              <Stat
                label="Achievable"
                value={<Badge kind={result.achievable ? "ok" : "bad"}>{result.achievable ? "Yes" : "No"}</Badge>}
              />
              <Stat label="Target RAROC" value={<span className="num">{fmt.pct(result.targetRaroc)}</span>} />
              <Stat label="Hurdle RAROC" value={<span className="num">{fmt.pct(result.hurdleRaroc)}</span>} />
              <Stat
                label="Recommended scenario"
                value={<Badge kind="ai">{result.recommended.name}</Badge>}
              />
              <Stat label="Recommended RAROC" value={<span className="num">{fmt.pct(result.recommended.raroc)}</span>} />
              {result.recommended.constraintHit && (
                <Stat
                  label="Constraint hit"
                  value={<small className="prov">{result.recommended.constraintHit}</small>}
                />
              )}
            </div>

            {result.advisory && (
              <div className="prov" style={{ marginTop: 10 }}>{result.advisory}</div>
            )}
          </Card>

          {/* Scenarios table */}
          <Card title="Scenarios">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th className="num">Rate</th>
                  <th className="num">Fee (bps)</th>
                  <th className="num">LGD after coll.</th>
                  <th className="num">RAROC</th>
                  <th>Meets target</th>
                  <th>Constraint</th>
                </tr>
              </thead>
              <tbody>
                {result.scenarios.map((s: Scenario) => {
                  const isRec = s.name === result.recommended.name;
                  return (
                    <tr key={s.name}>
                      <td>
                        {isRec
                          ? <Badge kind="ai">{s.name}</Badge>
                          : <span className="mono">{s.name}</span>}
                      </td>
                      <td className="num">{fmt.pct(s.rate)}</td>
                      <td className="num">{s.feeBps}</td>
                      <td className="num">{fmt.pct(s.lgdAfterCollateral)}</td>
                      <td className="num">{fmt.pct(s.raroc)}</td>
                      <td>
                        <Badge kind={s.meetsTarget ? "ok" : "bad"}>
                          {s.meetsTarget ? "Yes" : "No"}
                        </Badge>
                      </td>
                      <td>
                        {s.constraintHit && (
                          <small className="prov">{s.constraintHit}</small>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>

          {/* Recommended breakdown expandable */}
          <Card title="Recommended scenario breakdown">
            <div className="btnrow" style={{ marginBottom: 8 }}>
              <Button kind="subtle" onClick={() => setDetailsOpen((o) => !o)}>
                {detailsOpen ? "Collapse" : "Expand"} details
              </Button>
            </div>
            {detailsOpen && breakdownEntries.length > 0 && (
              <table>
                <thead>
                  <tr><th>Key</th><th className="num">Value</th></tr>
                </thead>
                <tbody>
                  {breakdownEntries.map(([k, v]) => {
                    const numVal = typeof v === "number" ? v : null;
                    const isRate = ["rate", "raroc"].includes(k);
                    const isMoney = ["ead", "expectedLoss", "capitalCharge"].includes(k);
                    // The FTP block is a nested object — show the derived rate + how it was built.
                    const isObj = v !== null && typeof v === "object";
                    const displayVal = isObj
                      ? (k === "ftp" && typeof (v as any).ftp === "number"
                          ? `${fmt.pct((v as any).ftp)} · ${(v as any).source ?? ""}` +
                            ((v as any).behaviourType ? ` · ${(v as any).behaviourType} ${(v as any).behaviouralMonths}m` : "")
                          : JSON.stringify(v))
                      : numVal !== null
                      ? isRate
                        ? fmt.pct(numVal)
                        : isMoney
                          ? fmt.money(numVal)
                          : k === "feeBps"
                            ? String(numVal)
                            : ["pd", "lgd"].includes(k)
                              ? fmt.pct(numVal)
                              : String(numVal)
                      : String(v ?? "—");
                    return (
                      <tr key={k}>
                        <td className="mono">{k}</td>
                        <td className="num">{displayVal}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
