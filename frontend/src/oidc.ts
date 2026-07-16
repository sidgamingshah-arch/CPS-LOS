// Browser-side OpenID Connect Authorization-Code + PKCE flow. Used only when the platform
// reports helix.security.mode=oidc (see api.ts `security.mode()`); the default `none` profile
// keeps the mock/actor-selector login and never touches this module.
//
// No client secret is used (public SPA client). The code_verifier is held in sessionStorage
// across the IdP redirect and exchanged for a JWT at the token endpoint; the returned token is
// then sent as `Authorization: Bearer` by api.ts and validated by each resource-server service.

import type { OidcClientConfig, SecurityMode } from "./api";

const VERIFIER_KEY = "helix.oidc.verifier";
const STATE_KEY = "helix.oidc.state";

function b64url(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomString(byteLen = 32): string {
  const a = new Uint8Array(byteLen);
  crypto.getRandomValues(a);
  return b64url(a);
}

async function codeChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return b64url(new Uint8Array(digest));
}

function redirectUri(cfg: OidcClientConfig): string {
  return cfg.redirectUri || window.location.origin + window.location.pathname;
}

/** True when the current URL is an IdP redirect carrying an authorization code + state. */
export function isOidcCallback(): boolean {
  const p = new URLSearchParams(window.location.search);
  return p.has("code") && p.has("state");
}

/** Kick off the redirect to the IdP's authorization endpoint (PKCE S256). */
export async function beginOidcLogin(mode: SecurityMode): Promise<void> {
  const cfg = mode.oidcClient || {};
  if (!cfg.authorizationUri || !cfg.clientId) {
    throw new Error("OIDC client is not configured (authorizationUri / clientId missing)");
  }
  const verifier = randomString(32);
  const state = randomString(16);
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
  const params = new URLSearchParams({
    response_type: "code",
    client_id: cfg.clientId,
    redirect_uri: redirectUri(cfg),
    scope: cfg.scopes || "openid profile",
    state,
    code_challenge: await codeChallenge(verifier),
    code_challenge_method: "S256",
  });
  window.location.assign(`${cfg.authorizationUri}?${params.toString()}`);
}

/** Complete the flow on redirect-back: validate state, exchange the code, return the JWT. */
export async function completeOidcLogin(mode: SecurityMode): Promise<string> {
  const cfg = mode.oidcClient || {};
  const p = new URLSearchParams(window.location.search);
  const code = p.get("code");
  const state = p.get("state");
  const expectedState = sessionStorage.getItem(STATE_KEY);
  const verifier = sessionStorage.getItem(VERIFIER_KEY);

  if (!code) throw new Error("Missing authorization code in the redirect");
  if (!state || state !== expectedState) throw new Error("OIDC state mismatch — possible CSRF");
  if (!verifier) throw new Error("Missing PKCE verifier (session expired?)");
  if (!cfg.tokenUri || !cfg.clientId) throw new Error("OIDC token endpoint is not configured");

  const res = await fetch(cfg.tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri(cfg),
      client_id: cfg.clientId,
      code_verifier: verifier,
    }).toString(),
  });
  if (!res.ok) throw new Error(`Token exchange failed (HTTP ${res.status})`);
  const json = (await res.json()) as { access_token?: string; id_token?: string };
  const token = json.access_token || json.id_token;
  if (!token) throw new Error("Token response contained no access_token");

  sessionStorage.removeItem(VERIFIER_KEY);
  sessionStorage.removeItem(STATE_KEY);
  // Strip ?code=…&state=… from the address bar.
  window.history.replaceState({}, document.title, redirectUri(cfg));
  return token;
}
