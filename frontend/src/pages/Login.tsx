import { useEffect, useState } from "react";
import { auth, security, setAuthToken, type SecurityMode } from "../api";
import { beginOidcLogin, completeOidcLogin, isOidcCallback } from "../oidc";
import { ACTORS } from "../app-context";
import { Button, Field } from "../ui";

const DEMO_PASSWORD = "Helix@2026";

/**
 * Login screen — the front door. Its behaviour is driven by the platform's
 * `helix.security.mode` (fetched unauthenticated from `/config/api/security/mode`):
 *
 *  - `none` (DEFAULT): the original demo login is preserved unchanged — the RBAC personas
 *    (one per actor, shared demo password) authenticate against config-service, which mints
 *    the HMAC bearer token the gateway verifies and injects as X-Actor.
 *  - `oidc`: the persona form is replaced by a single "Sign in with SSO" action that runs an
 *    Authorization-Code + PKCE redirect to the IdP; the returned JWT is stored via setAuthToken
 *    and thereafter sent as `Authorization: Bearer` on every request. The verified token's
 *    username + roles (read back from `/config/api/security/whoami`) become the acting identity.
 */
export default function Login({ onLogin }: { onLogin: (token: string, actor: string, displayName: string, roles: string[]) => void }) {
  const [username, setUsername] = useState("demo.user");
  const [password, setPassword] = useState(DEMO_PASSWORD);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [mode, setMode] = useState<SecurityMode | null>(null);

  // Resolve the security mode, and — if we've landed back from an IdP redirect — finish the
  // OIDC exchange automatically.
  useEffect(() => {
    let cancelled = false;
    security.mode()
      .then(async (m) => {
        if (cancelled) return;
        setMode(m);
        if (m.oidc && isOidcCallback()) {
          setBusy(true);
          try {
            const token = await completeOidcLogin(m);
            setAuthToken(token);
            const me = await security.whoami();
            onLogin(token, me.actor, me.actor, me.roles || []);
          } catch (e: any) {
            if (!cancelled) setErr(e.message || "SSO sign-in failed");
          } finally {
            if (!cancelled) setBusy(false);
          }
        }
      })
      .catch(() => { if (!cancelled) setMode({ mode: "none", oidc: false, ldap: false, secured: false }); });
    return () => { cancelled = true; };
  }, [onLogin]);

  async function submitPassword() {
    setBusy(true); setErr(null);
    try {
      const r = await auth.login(username.trim(), password);
      onLogin(r.token, r.actor, r.displayName, r.roles);
    } catch (e: any) {
      setErr(e.message || "Login failed");
    } finally { setBusy(false); }
  }

  async function submitSso() {
    setBusy(true); setErr(null);
    try {
      if (mode) await beginOidcLogin(mode);
    } catch (e: any) {
      setErr(e.message || "SSO sign-in failed");
      setBusy(false);
    }
  }

  const oidc = mode?.oidc === true;

  return (
    <div style={{ minHeight: "100vh", display: "grid", placeItems: "center", background: "var(--bg, #0d1117)" }}>
      <div style={{ width: 380, padding: 28, borderRadius: 12, background: "var(--surface, #fff)",
                    boxShadow: "0 12px 40px rgba(0,0,0,0.25)" }}>
        <div style={{ fontSize: 22, fontWeight: 700, marginBottom: 2 }}>Helix</div>
        <div style={{ fontSize: 13, opacity: 0.7, marginBottom: 20 }}>Governed AI for wholesale credit</div>

        {oidc ? (
          <>
            <div style={{ fontSize: 13, opacity: 0.8, marginBottom: 16, lineHeight: 1.5 }}>
              Single sign-on is enabled. You'll be redirected to your identity provider; your
              verified identity and roles drive every action.
            </div>
            {err && <div style={{ color: "var(--bad, #c0392b)", fontSize: 13, margin: "8px 0" }}>{err}</div>}
            <Button kind="primary" busy={busy} onClick={submitSso}>Sign in with SSO</Button>
          </>
        ) : (
          <form onSubmit={(e) => { e.preventDefault(); submitPassword(); }}>
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
              <Button kind="primary" busy={busy} onClick={submitPassword}>Sign in</Button>
            </div>
            <div style={{ fontSize: 12, opacity: 0.6, marginTop: 16, lineHeight: 1.5 }}>
              Demo personas map to RBAC roles. Shared demo password: <span className="mono">{DEMO_PASSWORD}</span>.
              The gateway injects the verified actor — pick a persona to act as them.
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
