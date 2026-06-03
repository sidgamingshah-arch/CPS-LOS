// Thin client over the Helix API gateway. Each call sets X-Actor so the audit
// trail attributes actions to a named user (PRD §9/§11 human accountability).

const GATEWAY: string =
  (import.meta as any).env?.VITE_GATEWAY_URL || "http://localhost:8080";

export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

async function call<T>(path: string, method: Method, body?: unknown, actor = "demo.user"): Promise<T> {
  const res = await fetch(GATEWAY + path, {
    method,
    headers: { "Content-Type": "application/json", "X-Actor": actor },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const message = data?.message || res.statusText || "Request failed";
    throw new Error(message);
  }
  return data as T;
}

// ---- config / abstraction layer ----
export const config = {
  jurisdictions: () => call<any[]>("/config/api/jurisdictions", "GET"),
  pack: (jurisdiction: string, type: string) =>
    call<any>(`/config/api/rulepacks?jurisdiction=${jurisdiction}&type=${type}`, "GET"),
};

// ---- counterparty ----
export const counterparty = {
  list: () => call<any[]>("/counterparty/api/counterparties", "GET"),
  get: (id: number) => call<any>(`/counterparty/api/counterparties/${id}`, "GET"),
  create: (body: any, actor: string) => call<any>("/counterparty/api/counterparties", "POST", body, actor),
  verifyKyc: (id: number, actor: string) =>
    call<any>(`/counterparty/api/counterparties/${id}/kyc/verify`, "POST", undefined, actor),
  runScreening: (id: number, actor: string) =>
    call<any[]>(`/counterparty/api/counterparties/${id}/screening/run`, "POST", undefined, actor),
  screening: (id: number) => call<any[]>(`/counterparty/api/counterparties/${id}/screening`, "GET"),
  disposition: (hitId: number, body: any, actor: string) =>
    call<any>(`/counterparty/api/counterparties/screening/${hitId}/disposition`, "POST", body, actor),
  resolveUbo: (id: number, body: any, actor: string) =>
    call<any[]>(`/counterparty/api/counterparties/${id}/ubo`, "POST", body, actor),
  ubo: (id: number) => call<any[]>(`/counterparty/api/counterparties/${id}/ubo`, "GET"),
  ingestScreening: (id: number, envelope: any, actor: string) =>
    call<any>(`/counterparty/api/counterparties/${id}/ingest/screening`, "POST", envelope, actor),
};

// ---- origination ----
export const origination = {
  list: () => call<any[]>("/origination/api/applications", "GET"),
  get: (ref: string) => call<any>(`/origination/api/applications/${ref}`, "GET"),
  create: (body: any, actor: string) => call<any>("/origination/api/applications", "POST", body, actor),
  status: (ref: string, status: string, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/status`, "PATCH", { status }, actor),
  uploadDoc: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/documents`, "POST", body, actor),
  docs: (ref: string) => call<any[]>(`/origination/api/applications/${ref}/documents`, "GET"),
  spread: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/spread`, "POST", body, actor),
  override: (cellId: number, body: any, actor: string) =>
    call<any>(`/origination/api/applications/spread/cells/${cellId}/override`, "PATCH", body, actor),
  confirmSpread: (ref: string, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/spread/confirm`, "POST", undefined, actor),
  analysis: (ref: string) => call<any>(`/origination/api/applications/${ref}/analysis`, "GET"),
};

// ---- risk ----
export const risk = {
  summary: (ref: string) => call<any>(`/risk/api/risk/${ref}`, "GET"),
  rate: (ref: string, actor: string) => call<any>(`/risk/api/risk/${ref}/rate`, "POST", undefined, actor),
  overrideRating: (ref: string, body: any, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/rating/override`, "POST", body, actor),
  confirmRating: (ref: string, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/rating/confirm`, "POST", undefined, actor),
  capital: (ref: string, actor: string) => call<any>(`/risk/api/risk/${ref}/capital`, "POST", undefined, actor),
  explainCapital: (ref: string) => call<any>(`/risk/api/risk/${ref}/capital/explain`, "GET"),
  pricing: (ref: string, actor: string) => call<any>(`/risk/api/risk/${ref}/pricing`, "POST", undefined, actor),
  overrideStats: (segment: string) => call<any>(`/risk/api/risk/override-stats?segment=${segment}`, "GET"),
};

// ---- decision ----
export const decision = {
  route: (ref: string, actor: string) => call<any>(`/decision/api/decisions/${ref}/route`, "POST", undefined, actor),
  decide: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/decisions/${ref}/decide`, "POST", body, actor),
  latest: (ref: string) => call<any>(`/decision/api/decisions/${ref}`, "GET"),
  committeeNote: (ref: string) => call<any>(`/decision/api/decisions/${ref}/committee-note`, "GET"),
  covenants: (ref: string) => call<any[]>(`/decision/api/decisions/${ref}/covenants`, "GET"),
  addCovenant: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/decisions/${ref}/covenants`, "POST", body, actor),
  suggest: (grade: string) => call<any[]>(`/decision/api/decisions/${grade}/covenants/suggest?grade=${grade}`, "GET"),
};

// ---- portfolio ----
export const portfolio = {
  exposures: () => call<any[]>("/portfolio/api/portfolio/exposures", "GET"),
  register: (ref: string, daysPastDue: number, actor: string) =>
    call<any>(`/portfolio/api/portfolio/exposures/${ref}/register`, "POST", { daysPastDue }, actor),
  ecl: (ref: string, actor: string) =>
    call<any>(`/portfolio/api/portfolio/exposures/${ref}/ecl`, "POST", undefined, actor),
  summary: () => call<any>("/portfolio/api/portfolio/summary", "GET"),
  concentration: (j: string) => call<any>(`/portfolio/api/portfolio/concentration?jurisdiction=${j}`, "GET"),
  stress: () => call<any>("/portfolio/api/portfolio/stress", "GET"),
  scan: (ref: string, actor: string) =>
    call<any[]>(`/portfolio/api/portfolio/exposures/${ref}/ews/scan`, "POST", undefined, actor),
  scanAll: (actor: string) => call<any[]>("/portfolio/api/portfolio/ews/scan-all", "POST", undefined, actor),
  watchlist: () => call<any[]>("/portfolio/api/portfolio/ews/watchlist", "GET"),
  disposition: (id: number, status: string, actor: string) =>
    call<any>(`/portfolio/api/portfolio/ews/${id}/disposition`, "POST", { status }, actor),
  ingestCoreBanking: (ref: string, envelope: any, actor: string) =>
    call<any>(`/portfolio/api/portfolio/exposures/${ref}/ingest/core-banking`, "POST", envelope, actor),
};

// ---- copilot ----
export const copilot = {
  ask: (body: any, actor: string) => call<any>("/copilot/api/copilot/ask", "POST", body, actor),
  scope: (persona: string) => call<any>(`/copilot/api/copilot/scope?persona=${persona}`, "GET"),
};

// ---- audit (any service exposes /api/audit) ----
export const audit = {
  recent: (svc: string) => call<any[]>(`/${svc}/api/audit`, "GET"),
  subject: (svc: string, type: string, id: string) =>
    call<any[]>(`/${svc}/api/audit/subject?type=${type}&id=${id}`, "GET"),
};

export const fmt = {
  money: (v: number, ccy = "INR") =>
    v == null ? "—" : new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 }).format(v) + (ccy ? " " + ccy : ""),
  pct: (v: number, dp = 2) => (v == null ? "—" : (v * 100).toFixed(dp) + "%"),
  num: (v: number, dp = 2) => (v == null ? "—" : v.toFixed(dp)),
};
