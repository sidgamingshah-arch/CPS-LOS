import CopilotPanel from "../CopilotPanel";
import { Card, GovFlow } from "../ui";

export default function Copilot() {
  return (
    <div className="grid">
      {/* Governance banner — same pattern as Risk Lab / Pricing Lab */}
      <div className="gov-banner">
        <h3>Ask anything. The copilot answers from your data — or refuses.</h3>
        <div className="gb-sub">
          A persona-scoped, grounded-and-cited, <b>non-binding</b> conversational layer that fans out read-only across the
          nine services. It refuses to fabricate, and refuses credit-consequential actions — routing them to the gated workflow.
        </div>
        <div className="gb-chips">
          <span className="gb-chip"><b>Persona-scoped</b> · 8 roles</span>
          <span className="gb-chip"><b>Grounded</b> · cites the service + endpoint</span>
          <span className="gb-chip"><b>Non-binding</b> · cannot approve / override / price / book</span>
          <span className="gb-chip"><b>Audited</b> · every Q&A as an AI event</span>
        </div>
      </div>

      <Card title="Conversational copilot"
            sub="Persona-scoped, grounded-and-cited, non-binding (PRD §6.6). It refuses to fabricate and refuses credit-consequential actions — routing them to the gated workflow."
            right={<GovFlow ai="USER ASKS · AI ANSWERS" human="HUMAN ACTS" note="answers cite the source; actions route to the gated workflow" />}>
        <CopilotPanel />
      </Card>
      <Card title="How it's governed">
        <ul style={{ marginTop: 0, lineHeight: 1.7 }}>
          <li><b>Scoped</b> — your role (set by the actor selector) determines which topics are answerable; out-of-scope questions are refused, not answered.</li>
          <li><b>Grounded &amp; cited</b> — every fact is retrieved from a service and the source endpoint is shown; nothing is invented.</li>
          <li><b>Non-binding</b> — it can explain a rating, capital figure or decision, but it cannot approve, override, price, stage or book. Those route to a named human.</li>
          <li><b>Audited</b> — every question is logged to the immutable trail as an AI action (intent · persona · grounded flag), visible under each service's <span className="mono">/api/audit</span>.</li>
        </ul>
      </Card>
    </div>
  );
}
