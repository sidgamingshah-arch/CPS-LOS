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
  envelope: (ref: string) => call<any>(`/origination/api/applications/${ref}/envelope`, "GET"),
  facilities: (ref: string) => call<any[]>(`/origination/api/applications/${ref}/facilities`, "GET"),
  facilityViews: (ref: string) => call<any[]>(`/origination/api/applications/${ref}/facilities/view`, "GET"),
  addFacility: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/facilities`, "POST", body, actor),
  removeFacility: (id: number, actor: string) =>
    call<any>(`/origination/api/applications/facilities/${id}`, "DELETE", undefined, actor),
  sublimits: (facilityId: number) => call<any[]>(`/origination/api/applications/facilities/${facilityId}/sublimits`, "GET"),
  addSublimit: (facilityId: number, body: any, actor: string) =>
    call<any>(`/origination/api/applications/facilities/${facilityId}/sublimits`, "POST", body, actor),
  removeSublimit: (id: number, actor: string) =>
    call<any>(`/origination/api/applications/sublimits/${id}`, "DELETE", undefined, actor),
  collaterals: (ref: string) => call<any[]>(`/origination/api/applications/${ref}/collaterals`, "GET"),
  addCollateral: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/collaterals`, "POST", body, actor),
  perfectCollateral: (id: number, actor: string) =>
    call<any>(`/origination/api/applications/collaterals/${id}/perfect`, "POST", undefined, actor),
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
  testCovenants: (ref: string, actor: string) =>
    call<any[]>(`/decision/api/decisions/${ref}/covenants/test`, "POST", undefined, actor),
  covenantTests: (ref: string) => call<any[]>(`/decision/api/decisions/${ref}/covenants/tests`, "GET"),
  generateProposal: (ref: string, actor: string) =>
    call<any>(`/decision/api/decisions/${ref}/credit-proposal/generate`, "POST", undefined, actor),
  latestProposal: (ref: string) => call<any>(`/decision/api/decisions/${ref}/credit-proposal`, "GET"),
  proposalVersions: (ref: string) => call<any[]>(`/decision/api/decisions/${ref}/credit-proposal/versions`, "GET"),
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
  rarocSnapshot: (ref: string, actor: string) =>
    call<any>(`/portfolio/api/portfolio/exposures/${ref}/raroc/snapshot`, "POST", undefined, actor),
  rarocCompute: (ref: string, period: string, realisedProvisionDelta: number, actor: string) =>
    call<any>(`/portfolio/api/portfolio/exposures/${ref}/raroc/compute?period=${encodeURIComponent(period)}&realisedProvisionDelta=${realisedProvisionDelta}`,
      "POST", undefined, actor),
  rarocHistory: (ref: string) => call<any[]>(`/portfolio/api/portfolio/exposures/${ref}/raroc`, "GET"),
  rarocVariance: () => call<any>("/portfolio/api/portfolio/raroc/variance", "GET"),
};

// ---- mis / reports / 360 dashboards ----
export const mis = {
  dashboard: () => call<any>("/portfolio/api/mis/dashboard", "GET"),
  composition: () => call<any>("/portfolio/api/mis/composition", "GET"),
  rarocVariance: () => call<any>("/portfolio/api/mis/raroc-variance", "GET"),
  pipelineAgeing: () => call<any>("/portfolio/api/mis/pipeline-ageing", "GET"),
  eclByStage: () => call<any>("/portfolio/api/mis/ecl-by-stage", "GET"),
  watchlist: () => call<any>("/portfolio/api/mis/watchlist", "GET"),
  customer360: (ref: string) => call<any>(`/portfolio/api/mis/customer360/${ref}`, "GET"),
  portfolio360: () => call<any>("/portfolio/api/mis/portfolio360", "GET"),
};

// ---- corrective action plan (CAP) ----
export const cap = {
  raise: (body: any, actor: string) => call<any>("/portfolio/api/cap/actions", "POST", body, actor),
  respond: (id: number, body: any, actor: string) => call<any>(`/portfolio/api/cap/actions/${id}/respond`, "POST", body, actor),
  close: (id: number, body: any, actor: string) => call<any>(`/portfolio/api/cap/actions/${id}/close`, "POST", body, actor),
  escalate: (id: number, body: any, actor: string) => call<any>(`/portfolio/api/cap/actions/${id}/escalate`, "POST", body, actor),
  forApp: (ref: string) => call<any[]>(`/portfolio/api/cap/${ref}`, "GET"),
  inbox: (status?: string, owner?: string) => {
    const q = owner ? `?owner=${owner}` : status ? `?status=${status}` : "";
    return call<any[]>("/portfolio/api/cap/inbox" + q, "GET");
  },
  sweep: (actor: string) => call<any>("/portfolio/api/cap/sweep", "POST", undefined, actor),
};

// ---- CAD ----
export const cad = {
  initiate: (body: any, actor: string) => call<any>("/decision/api/cad/cases", "POST", body, actor),
  inbox: (status?: string) => call<any[]>("/decision/api/cad/cases" + (status ? `?status=${status}` : ""), "GET"),
  view: (id: number) => call<any>(`/decision/api/cad/cases/${id}`, "GET"),
  updateItem: (id: number, body: any, actor: string) => call<any>(`/decision/api/cad/items/${id}`, "POST", body, actor),
  raiseDeviation: (itemId: number, body: any, actor: string) =>
    call<any>(`/decision/api/cad/items/${itemId}/deviation`, "POST", body, actor),
  decideDeviation: (devId: number, body: any, actor: string) =>
    call<any>(`/decision/api/cad/deviations/${devId}/decision`, "POST", body, actor),
  complete: (id: number, actor: string) => call<any>(`/decision/api/cad/cases/${id}/complete`, "POST", undefined, actor),
  limitRelease: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/cad/cases/${id}/limit-release`, "POST", body, actor),
};

// ---- MER (monitoring of exceptions & renewals) ----
export const mer = {
  generateFromCad: (caseId: number, owner: string, actor: string) =>
    call<any[]>(`/decision/api/mer/generate/from-cad/${caseId}?owner=${encodeURIComponent(owner)}`, "POST", undefined, actor),
  raise: (body: any, actor: string) => call<any>("/decision/api/mer/raise", "POST", body, actor),
  forApp: (ref: string) => call<any[]>(`/decision/api/mer/${ref}`, "GET"),
  inbox: (owner?: string, status?: string) => {
    const q = [owner ? `owner=${encodeURIComponent(owner)}` : "", status ? `status=${status}` : ""].filter(Boolean).join("&");
    return call<any[]>("/decision/api/mer/inbox" + (q ? `?${q}` : ""), "GET");
  },
  summary: (ref?: string) => call<any>("/decision/api/mer/summary" + (ref ? `?reference=${ref}` : ""), "GET"),
  submit: (id: number, body: any, actor: string) => call<any>(`/decision/api/mer/${id}/submit`, "POST", body, actor),
  verify: (id: number, body: any, actor: string) => call<any>(`/decision/api/mer/${id}/verify`, "POST", body, actor),
  waive: (id: number, body: any, actor: string) => call<any>(`/decision/api/mer/${id}/waive`, "POST", body, actor),
  sweep: (actor: string) => call<any>("/decision/api/mer/sweep", "POST", undefined, actor),
  upcoming: (days: number) => call<any[]>(`/decision/api/mer/upcoming?days=${days}`, "GET"),
  sendReminders: (days: number, actor: string) => call<any>(`/decision/api/mer/reminders/send?days=${days}`, "POST", undefined, actor),
};

// ---- limit management ----
export const limits = {
  build: (ref: string, actor: string) => call<any>(`/limits/api/limits/build/${ref}`, "POST", undefined, actor),
  view: (cif: string) => call<any>(`/limits/api/limits/view?cif=${cif}`, "GET"),
  exposure: (cif: string) => call<any>(`/limits/api/limits/${cif}/exposure`, "GET"),
  ledger: (cif: string) => call<any[]>(`/limits/api/limits/${cif}/ledger`, "GET"),
  utilise: (body: any, actor: string) => call<any>("/limits/api/limits/utilise", "POST", body, actor),
  validate: (cif: string, line: string, amount: number) =>
    call<any>(`/limits/api/limits/validate?cif=${cif}&line=${line}&amount=${amount}`, "POST"),
  freeze: (id: number, body: any, actor: string) => call<any>(`/limits/api/limits/${id}/freeze`, "POST", body, actor),
  unfreeze: (id: number, actor: string) => call<any>(`/limits/api/limits/${id}/unfreeze`, "POST", undefined, actor),
  countries: () => call<any[]>("/limits/api/limits/countries", "GET"),
  countryView: (country: string) => call<any>(`/limits/api/limits/country/${country}`, "GET"),
  upsertCountry: (body: any, actor: string) => call<any>("/limits/api/limits/country", "POST", body, actor),
  upsertDept: (body: any, actor: string) => call<any>("/limits/api/limits/department", "POST", body, actor),
  pendingFi: () => call<any[]>("/limits/api/limits/fi/transactions/pending", "GET"),
  submitFi: (body: any, actor: string) => call<any>("/limits/api/limits/fi/transactions", "POST", body, actor),
  decideFi: (id: number, body: any, actor: string) =>
    call<any>(`/limits/api/limits/fi/transactions/${id}/decision`, "POST", body, actor),
};

// ---- covenant tracking workflow ----
export const tracking = {
  init: (body: any, actor: string) => call<any[]>("/decision/api/covenants/tracking/init", "POST", body, actor),
  list: (ref: string) => call<any[]>(`/decision/api/covenants/tracking/${ref}`, "GET"),
  runDue: (ref: string, actor: string) => call<any[]>(`/decision/api/covenants/tracking/${ref}/run-due`, "POST", undefined, actor),
  upcoming: (days: number) => call<any[]>(`/decision/api/covenants/tracking/upcoming?days=${days}`, "GET"),
  sendAlerts: (days: number, actor: string) => call<any>(`/decision/api/covenants/tracking/alerts/send?days=${days}`, "POST", undefined, actor),
  requestExtension: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/tracking/schedules/${id}/request/extension`, "POST", body, actor),
  requestWaiver: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/tracking/schedules/${id}/request/waiver`, "POST", body, actor),
  freeze: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/tracking/schedules/${id}/freeze-accounts`, "POST", body, actor),
  decide: (actionId: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/tracking/actions/${actionId}/decision`, "POST", body, actor),
  actions: (id: number) => call<any[]>(`/decision/api/covenants/tracking/schedules/${id}/actions`, "GET"),
};

// ---- credit-initiation (prospect lifecycle) ----
export const initiation = {
  createProspect: (body: any, actor: string) => call<any>("/counterparty/api/initiation/prospects", "POST", body, actor),
  dedup: (id: number) => call<any>(`/counterparty/api/initiation/prospects/${id}/dedup`, "GET"),
  negative: (id: number) => call<any>(`/counterparty/api/initiation/prospects/${id}/negative-check`, "GET"),
  summary: (id: number) => call<any>(`/counterparty/api/initiation/prospects/${id}/summary`, "GET"),
  decide: (id: number, body: any, actor: string) => call<any>(`/counterparty/api/initiation/prospects/${id}/decision`, "POST", body, actor),
  approve: (id: number, actor: string) => call<any>(`/counterparty/api/initiation/prospects/${id}/approve`, "POST", undefined, actor),
  fetchCheck: (id: number, body: any, actor: string) => call<any>(`/counterparty/api/initiation/prospects/${id}/checks/fetch`, "POST", body, actor),
  refreshCheck: (checkId: number, actor: string) => call<any>(`/counterparty/api/initiation/checks/${checkId}/refresh`, "POST", undefined, actor),
  checks: (id: number) => call<any[]>(`/counterparty/api/initiation/prospects/${id}/checks`, "GET"),
};

// ---- master data (generic, maker-checker) ----
export const masters = {
  list: (type: string) => call<any[]>(`/config/api/masters/${type}`, "GET"),
  submit: (type: string, body: any, actor: string) => call<any>(`/config/api/masters/${type}`, "POST", body, actor),
  pending: () => call<any[]>("/config/api/masters/queue/pending", "GET"),
  approve: (id: number, actor: string) => call<any>(`/config/api/masters/records/${id}/approve`, "POST", undefined, actor),
  reject: (id: number, actor: string) => call<any>(`/config/api/masters/records/${id}/reject`, "POST", undefined, actor),
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
