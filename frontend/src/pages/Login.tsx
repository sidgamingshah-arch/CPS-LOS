import { useState } from "react";
import { auth } from "../api";
import { ACTORS } from "../app-context";
import { Button, Field } from "../ui";

const DEMO_PASSWORD = "Helix@2026";

/**
 * Login screen — the front door. Authenticates against config-service and hands the
 * minted token up to the app shell. Demo users are the RBAC personas (one per actor),
 * all sharing the demo password; pick a persona to act as them. Because the gateway
 * derives X-Actor from the verified token, "acting as" someone now means logging in
 * as them — no more client-asserted identity.
 */
export default function Login({ onLogin }: { onLogin: (token: string, actor: string, displayName: string, roles: string[]) => void }) {
  const [username, setUsername] = useState("demo.user");
  const [password, setPassword] = useState(DEMO_PASSWORD);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setBusy(true); setErr(null);
    try {
      const r = await auth.login(username.trim(), password);
      onLogin(r.token, r.actor, r.displayName, r.roles);
    } catch (e: any) {
      setErr(e.message || "Login failed");
    } finally { setBusy(false); }
  }

  return (
    <div style={{ minHeight: "100vh", display: "grid", placeItems: "center", background: "var(--bg, #0d1117)" }}>
      <div style={{ width: 380, padding: 28, borderRadius: 12, background: "var(--surface, #fff)",
                    boxShadow: "0 12px 40px rgba(0,0,0,0.25)" }}>
        <div style={{ fontSize: 22, fontWeight: 700, marginBottom: 2 }}>Helix</div>
        <div style={{ fontSize: 13, opacity: 0.7, marginBottom: 20 }}>Governed AI for wholesale credit</div>
        <form onSubmit={(e) => { e.preventDefault(); submit(); }}>
          <Field label="User">
            <select value={username} onChange={(e) => setUsername(e.target.value)}>
              {ACTORS.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </Field>
          <Field label="Password">
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              autoFocus />
          </Field>
          {err && <div style={{ color: "var(--bad, #c0392b)", fontSize: 13, margin: "8px 0" }}>{err}</div>}
          <div style={{ marginTop: 14 }}>
            <Button kind="primary" busy={busy} onClick={submit}>Sign in</Button>
          </div>
        </form>
        <div style={{ fontSize: 12, opacity: 0.6, marginTop: 16, lineHeight: 1.5 }}>
          Demo personas map to RBAC roles. Shared demo password: <span className="mono">{DEMO_PASSWORD}</span>.
          The gateway injects the verified actor — pick a persona to act as them.
        </div>
      </div>
    </div>
  );
}
