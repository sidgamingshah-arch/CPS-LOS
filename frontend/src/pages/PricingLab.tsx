/**
 * PricingLab — pricing scenario optimiser; goal-seek RAROC/rate/fee/collateral
 * constraints against a target RAROC. All outputs are ADVISORY; the authoritative
 * pricing produced by the risk engine is never modified.
 */
import { useState } from "react";
import { origination, risk, optimiser, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, Stat, useAsync } from "../ui";

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
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>("");
  const [form, setForm] = useState<OptimiserForm>(BLANK_FORM);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OptimisationResult | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);

  const summaryAsync = useAsync(
    () => (ref ? risk.summary(ref) : Promise.reject(new Error("no-ref"))),
    [ref],
  );

  const sum = summaryAsync.data;
  const pricing = sum?.pricing ?? null;

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

  const breakdownEntries = result
    ? Object.entries(result.recommended.breakdown)
    : [];

  return (
    <div className="grid">
      {/* AI advisory banner */}
      <Card
        title="Pricing Lab"
        sub="Goal-seek RAROC scenarios against rate/fee/collateral constraints. All outputs are ADVISORY and NON-BINDING — authoritative pricing in the risk engine is never modified."
        right={<Badge kind="ai">AI · advisory</Badge>}
      >
        <div className="muted" style={{ fontSize: 13 }}>
          Select a priced deal, configure constraints, and run the optimiser. Scenarios
          are directional inputs only; final pricing decisions remain with a named human.
        </div>
      </Card>

      {/* Deal selector + authoritative pricing stats */}
      <Card title="Deal selector">
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

      {/* Optimiser form */}
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
                    const displayVal = numVal !== null
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
