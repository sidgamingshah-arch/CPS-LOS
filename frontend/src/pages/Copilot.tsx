import CopilotPanel from "../CopilotPanel";
import { Card } from "../ui";

export default function Copilot() {
  return (
    <div className="grid">
      <Card title="Conversational copilot" sub="Persona-scoped, grounded-and-cited, non-binding (PRD §6.6). It refuses to fabricate and refuses credit-consequential actions — routing them to the gated workflow.">
        <CopilotPanel />
      </Card>
      <Card title="How it's governed">
        <ul style={{ marginTop: 0, lineHeight: 1.7 }}>
          <li><b>Scoped</b> — your role (set by the actor selector) determines which topics are answerable; out-of-scope questions are refused, not answered.</li>
          <li><b>Grounded &amp; cited</b> — every fact is retrieved from a service and the source endpoint is shown; nothing is invented.</li>
          <li><b>Non-binding</b> — it can explain a rating, capital figure or decision, but it cannot approve, override, price, stage or book. Those route to a named human.</li>
          <li><b>Audited</b> — every question is logged to the immutable trail as an AI action (see the Audit Trail tab, <span className="mono">copilot</span>… not yet a separate service tab; events are under each service).</li>
        </ul>
      </Card>
    </div>
  );
}
