/**
 * AI Governance admin — flip individual AI capabilities on/off, globally or for a
 * specific jurisdiction. Every change goes through the same maker-checker SoD as
 * any other master record: the propose call here creates a PENDING_APPROVAL row,
 * and a different actor must Approve.
 *
 * The frontend filters its own nav by the resolved map (a disabled capability is
 * hidden); the API also blocks the corresponding endpoint server-side with a 403,
 * so "AI off" is enforcement, not just UI discipline.
 */
import { useState } from "react";
import { config, governance, masters } from "../api";
import { useApp } from "../app-context";
import { AiBadge, Badge, Button, Card, EmptyState, Field, useAsync } from "../ui";

export default function Governance() {
  const { actor, notify } = useApp();
  const jurisdictions = useAsync(() => config.jurisdictions(), []);
  const [jurisdiction, setJurisdiction] = useState<string>("");
  const resolved = useAsync(
    () => governance.resolved(jurisdiction || undefined),
    [jurisdiction]);
  const pending = useAsync(
    () => masters.pending().then((rows: any[]) =>
      rows.filter((r) => r.masterType === "AI_GOVERNANCE")),
    []);
  const [busy, setBusy] = useState<string | null>(null);

  // Maker lane: propose the toggle. The proposal lands as PENDING_APPROVAL and a
  // DIFFERENT actor must approve it from the panel below — same SoD as every
  // other master change. No self-approval shortcut.
  async function toggle(key: string, enabled: boolean) {
    setBusy(key);
    try {
      const rec = await governance.setEnabled(key, enabled, jurisdiction || null, actor);
      if (rec?.status === "PENDING_APPROVAL") {
        notify(`${key} → ${enabled ? "ENABLE" : "DISABLE"} proposed by ${actor} — switch actor to approve`);
      } else {
        notify(`${key} toggled (status=${rec?.status})`);
      }
      pending.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setBusy(null);
    }
  }

  // Checker lane: approve/reject a pending toggle. Server enforces maker ≠ checker;
  // the button is also disabled client-side when the current actor is the maker.
  async function decide(rec: any, approve: boolean) {
    setBusy(`pending-${rec.id}`);
    try {
      await (approve ? masters.approve(rec.id, actor) : masters.reject(rec.id, actor));
      if (approve) {
        // Drop every AI service's governance cache so the toggle is live NOW.
        await governance.invalidateCaches();
      }
      notify(`${rec.recordKey} ${approve ? "approved — live immediately" : "rejected"} by ${actor}`);
      pending.reload();
      resolved.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setBusy(null);
    }
  }

  const caps = resolved.data?.capabilities || {};
  const entries = Object.entries(caps);

  return (
    <div className="grid">
      <div className="gov-banner">
        <h3>AI off-switch — capability-level, jurisdiction-overridable.</h3>
        <div className="gb-sub">
          Every governed AI capability is listed below. Disabling a capability
          blocks the endpoint server-side (HTTP 403) <b>and</b> hides the surface
          from this nav. A jurisdiction override (e.g. AE-CBUAE) layers on top of
          the default record and applies only to deals in that jurisdiction.
        </div>
        <div className="gb-chips">
          <span className="gb-chip"><b>SERVER-SIDE 403</b> enforced</span>
          <span className="gb-chip"><b>UI HIDE</b> on disable</span>
          <span className="gb-chip"><b>MAKER-CHECKER SoD</b> like every master</span>
        </div>
      </div>

      <Card title="Jurisdiction scope"
        sub="Leave blank to edit the default record (applies everywhere unless an override exists). Pick a jurisdiction to layer an override.">
        <Field label="Jurisdiction">
          <select value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)}>
            <option value="">— default (all jurisdictions) —</option>
            {(jurisdictions.data ?? []).map((j: any) => (
              <option key={j.code} value={j.code}>
                {j.code} · {j.name}
              </option>
            ))}
          </select>
        </Field>
      </Card>

      {(pending.data ?? []).length > 0 && (
        <Card title="Pending approvals — maker-checker"
          sub="A proposed toggle takes effect only after a DIFFERENT actor approves it. Switch the topbar actor to act as checker."
          right={<Badge kind="warn">{(pending.data ?? []).length} PENDING</Badge>}>
          <table>
            <thead>
              <tr>
                <th>Capability</th>
                <th>Jurisdiction</th>
                <th>Change</th>
                <th>Proposed by</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {(pending.data ?? []).map((rec: any) => {
                const isMaker = rec.maker === actor;
                return (
                  <tr key={rec.id}>
                    <td className="mono">{rec.recordKey}</td>
                    <td>{rec.jurisdiction || "default"}</td>
                    <td>
                      <Badge kind={rec.payload?.enabled ? "ok" : "bad"}>
                        {rec.payload?.enabled ? "ENABLE" : "DISABLE"}
                      </Badge>
                    </td>
                    <td className="mono">{rec.maker}</td>
                    <td>
                      <span title={isMaker ? "SoD: the maker cannot approve their own change" : undefined}>
                        <Button kind="subtle" busy={busy === `pending-${rec.id}`}
                          disabled={isMaker} onClick={() => decide(rec, true)}>
                          Approve
                        </Button>{" "}
                        <Button kind="danger" busy={busy === `pending-${rec.id}`}
                          disabled={isMaker} onClick={() => {
                            if (window.confirm(`Reject pending toggle ${rec.recordKey}?`)) decide(rec, false);
                          }}>
                          Reject
                        </Button>
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      )}

      <Card title="AI capabilities"
        sub="Switch a capability off to block the endpoint and hide the surface. Re-enabling restores both."
        right={<AiBadge label={`SCOPE: ${jurisdiction || "DEFAULT"}`} />}>
        {entries.length === 0 ? (
          <EmptyState
            glyph="◴"
            title="No capabilities resolved yet"
            sub="Config-service may still be starting up. The catalogue is /config/api/governance/ai/capabilities."
          />
        ) : (
          <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Capability</th>
                <th>Description</th>
                <th>Source</th>
                <th>State</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {entries.map(([key, v]: any) => (
                <tr key={key}>
                  <td className="mono">{key}</td>
                  <td>{v.description}</td>
                  <td>
                    <Badge kind={v.source === "JURISDICTION_OVERRIDE" ? "info"
                                : v.source === "DEFAULT" ? "ok" : "warn"}>
                      {v.source}
                    </Badge>
                  </td>
                  <td>
                    <Badge kind={v.enabled ? "ok" : "bad"}>
                      {v.enabled ? "ENABLED" : "DISABLED"}
                    </Badge>
                  </td>
                  <td>
                    <Button
                      kind={v.enabled ? "subtle" : "ghost"}
                      busy={busy === key}
                      onClick={() => toggle(key, !v.enabled)}
                    >
                      {v.enabled ? "Disable" : "Enable"}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </Card>

      <Card title="How this works"
        sub="The off-switch is wired into both layers — enforcement is server-side; the UI just stops nudging.">
        <ul style={{ lineHeight: 1.7, margin: 0 }}>
          <li>
            Every governed AI capability persists as an <b>AI_GOVERNANCE</b> master
            record (config-service). Toggles flow maker → checker like every other
            master change.
          </li>
          <li>
            Each AI endpoint calls <span className="mono">AiGovernanceClient.enforce(...)</span>{" "}
            at the head of the call; disabled capability ⇒ <b>HTTP 403</b>.
          </li>
          <li>
            Jurisdiction resolution: <b>override</b> (record with a jurisdiction)
            wins over <b>default</b> (null jurisdiction); conservative fallback
            (enabled) only if config-service is unreachable.
          </li>
          <li>
            E2E:{" "}
            <span className="mono">scripts/e2e_ai_governance.py</span> asserts the
            block + jurisdiction-override behaviour end-to-end.
          </li>
        </ul>
      </Card>
    </div>
  );
}
