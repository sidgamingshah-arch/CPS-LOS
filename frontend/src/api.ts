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

// ---- AI governance (capability on/off switch with per-jurisdiction override) ----
export type AiGovernanceMap = {
  jurisdiction: string | null;
  capabilities: Record<string, { enabled: boolean; description: string; source: string }>;
};
export const governance = {
  capabilities: () => call<{ key: string; description: string }[]>(
    "/config/api/governance/ai/capabilities", "GET"),
  resolved: (jurisdiction?: string) => call<AiGovernanceMap>(
    "/config/api/governance/ai/resolved" + (jurisdiction ? `?jurisdiction=${jurisdiction}` : ""),
    "GET"),
  setEnabled: (key: string, enabled: boolean, jurisdiction: string | null, actor: string) =>
    call<any>("/config/api/masters/AI_GOVERNANCE", "POST",
      { recordKey: key, jurisdiction, payload: { enabled } }, actor),
  approve: (recordId: number, actor: string) =>
    call<any>(`/config/api/masters/records/${recordId}/approve`, "POST", undefined, actor),
  // Drop the AiGovernanceClient snapshot on every AI service so an approved toggle
  // takes effect immediately (instead of waiting out the cache TTL).
  invalidateCaches: () =>
    Promise.allSettled(
      ["counterparty", "origination", "risk", "decision", "portfolio", "copilot", "limits"]
        .map((s) => call<any>(`/${s}/api/governance/ai/cache/invalidate`, "POST"))),
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
  // collateral intelligence: extraction + LTV revaluation + charge-Excel
  colExtract: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/collateral-intel/${ref}/extract`, "POST", body, actor),
  colExtractions: (ref: string) =>
    call<any[]>(`/origination/api/collateral-intel/${ref}/extractions`, "GET"),
  colConfirm: (id: number, body: any, actor: string) =>
    call<any>(`/origination/api/collateral-intel/extractions/${id}/confirm`, "POST", body, actor),
  colReject: (id: number, body: any, actor: string) =>
    call<any>(`/origination/api/collateral-intel/extractions/${id}/reject`, "POST", body, actor),
  colRevalue: (collateralId: number, body: any, actor: string) =>
    call<any>(`/origination/api/collateral-intel/collaterals/${collateralId}/revalue`, "POST", body, actor),
  colReviewRevaluation: (revaluationId: number, body: any, actor: string) =>
    call<any>(`/origination/api/collateral-intel/revaluations/${revaluationId}/review`, "POST", body, actor),
  colRevaluations: (ref: string) =>
    call<any[]>(`/origination/api/collateral-intel/${ref}/revaluations`, "GET"),
  chargeExcelUrl: (ref: string) =>
    `/origination/api/collateral-intel/${ref}/charge-excel`,
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
  // ---- advisory overlays (non-binding) ----
  assessRag: (ref: string, actor: string) => call<any>(`/risk/api/risk/${ref}/rag`, "POST", undefined, actor),
  ragHistory: (ref: string) => call<any[]>(`/risk/api/risk/${ref}/rag`, "GET"),
  macroImpact: (ref: string, body: any, actor: string) => call<any>(`/risk/api/risk/${ref}/macro-impact`, "POST", body, actor),
  macroHistory: (ref: string) => call<any[]>(`/risk/api/risk/${ref}/macro-impact`, "GET"),
};

// ---- specialised deal structures (CP variants) ----
export const structure = {
  get: (ref: string) => call<any>(`/origination/api/applications/${ref}/structure`, "GET"),
  set: (ref: string, body: any, actor: string) => call<any>(`/origination/api/applications/${ref}/structure`, "POST", body, actor),
  addParticipant: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/structure/participants`, "POST", body, actor),
  removeParticipant: (id: number, actor: string) =>
    call<any>(`/origination/api/applications/structure/participants/${id}`, "DELETE", undefined, actor),
  copyFrom: (ref: string, sourceRef: string, actor: string) =>
    call<any>(`/origination/api/applications/${ref}/structure/copy-from/${sourceRef}`, "POST", undefined, actor),
};

// ---- document generation (templates · clause surgery · confirm) ----
export const docs = {
  templates: () => call<any[]>("/decision/api/docs/templates", "GET"),
  tncClauses: () => call<any[]>("/decision/api/docs/tnc-clauses", "GET"),
  generate: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/docs/applications/${ref}/generate`, "POST", body, actor),
  list: (ref: string) => call<any[]>(`/decision/api/docs/applications/${ref}`, "GET"),
  get: (id: number) => call<any>(`/decision/api/docs/${id}`, "GET"),
  addClause: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/docs/${id}/clauses`, "POST", body, actor),
  removeClause: (id: number, clauseRef: string, actor: string) =>
    call<any>(`/decision/api/docs/${id}/clauses/${encodeURIComponent(clauseRef)}`, "DELETE", undefined, actor),
  editClause: (id: number, clauseRef: string, body: any, actor: string) =>
    call<any>(`/decision/api/docs/${id}/clauses/${encodeURIComponent(clauseRef)}/edit`, "POST", body, actor),
  confirm: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/docs/${id}/confirm`, "POST", body, actor),
  withdraw: (id: number, actor: string) =>
    call<any>(`/decision/api/docs/${id}/withdraw`, "POST", undefined, actor),
};

// ---- AI narrative commentary ----
export const commentary = {
  draft: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/commentary/applications/${ref}/draft`, "POST", body, actor),
  list: (ref: string, section?: string) =>
    call<any[]>(`/decision/api/commentary/applications/${ref}${section ? `?section=${section}` : ""}`, "GET"),
  review: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/commentary/${id}/review`, "POST", body, actor),
  edit: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/commentary/${id}/edit`, "POST", body, actor),
};

// ---- pricing optimiser (goal-seek) + concession-exception approval ----
export const optimiser = {
  optimise: (ref: string, body: any, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/pricing/optimise`, "POST", body, actor),
  proposeException: (ref: string, body: any, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/pricing/exception`, "POST", body, actor),
  listExceptions: (ref: string) => call<any[]>(`/risk/api/risk/${ref}/pricing/exception`, "GET"),
  pendingExceptions: () => call<any[]>("/risk/api/risk/pricing/exception/pending", "GET"),
  decideException: (id: number, body: any, actor: string) =>
    call<any>(`/risk/api/risk/pricing/exception/${id}/decision`, "POST", body, actor),
};

// ---- downstream export feeds (ERM · Finance/GL · CPR) ----
export const exports = {
  erm: (actor: string) => call<any>("/portfolio/api/exports/erm", "POST", undefined, actor),
  financeGl: (actor: string) => call<any>("/portfolio/api/exports/finance-gl", "POST", undefined, actor),
  cpr: (actor: string) => call<any>("/portfolio/api/exports/cpr", "POST", undefined, actor),
  batches: (destination?: string) =>
    call<any[]>("/portfolio/api/exports/batches" + (destination ? `?destination=${destination}` : ""), "GET"),
  batch: (id: number) => call<any>(`/portfolio/api/exports/batches/${id}`, "GET"),
};

// ---- GenAI document intelligence ----
export const docIntel = {
  extract: (docId: number, actor: string) => call<any>(`/origination/api/doc-intel/documents/${docId}/extract`, "POST", undefined, actor),
  extractions: (docId: number) => call<any[]>(`/origination/api/doc-intel/documents/${docId}/extractions`, "GET"),
  confirm: (id: number, body: any, actor: string) => call<any>(`/origination/api/doc-intel/extractions/${id}/confirm`, "POST", body, actor),
  reject: (id: number, body: any, actor: string) => call<any>(`/origination/api/doc-intel/extractions/${id}/reject`, "POST", body, actor),
  normalise: (body: any, actor: string) => call<any>("/origination/api/doc-intel/normalise-language", "POST", body, actor),
  translate: (body: any, actor: string) => call<any>("/origination/api/doc-intel/translate", "POST", body, actor),
  checks: (docId: number) => call<any>(`/origination/api/doc-intel/documents/${docId}/checks`, "GET"),
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
  // covenant intelligence (advisory extraction + certificate assessment)
  covExtract: (ref: string, text: string, actor: string) =>
    call<any[]>(`/decision/api/covenants/intel/${ref}/extract`, "POST", { text }, actor),
  covExtractions: (ref: string) => call<any[]>(`/decision/api/covenants/intel/${ref}/extractions`, "GET"),
  covConfirmExtraction: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/intel/extractions/${id}/confirm`, "POST", body, actor),
  covRejectExtraction: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/intel/extractions/${id}/reject`, "POST", body, actor),
  certAssess: (ref: string, text: string, actor: string) =>
    call<any[]>(`/decision/api/covenants/intel/${ref}/certificate/assess`, "POST", { text }, actor),
  certAssessments: (ref: string) =>
    call<any[]>(`/decision/api/covenants/intel/${ref}/certificate/assessments`, "GET"),
  certConfirm: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/intel/certificate/assessments/${id}/confirm`, "POST", body, actor),
  certReject: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/covenants/intel/certificate/assessments/${id}/reject`, "POST", body, actor),
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
  concentrationMulti: (j: string) => call<any>(`/portfolio/api/portfolio/concentration/multi?jurisdiction=${j}`, "GET"),
  concentrationStress: (j: string, body: any, actor: string) =>
    call<any>(`/portfolio/api/portfolio/concentration/stress?jurisdiction=${j}`, "POST", body, actor),
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
  // ---- EOD batch (FX refresh · revaluation · reconciliation) ----
  eodFx: () => call<any>("/limits/api/limits/eod/fx", "GET"),
  eodRefreshFx: (body: any, actor: string) => call<any>("/limits/api/limits/eod/fx/refresh", "POST", body, actor),
  eodRun: (actor: string) => call<any>("/limits/api/limits/eod/run", "POST", undefined, actor),
  eodRuns: () => call<any[]>("/limits/api/limits/eod/runs", "GET"),
  eodRunDetail: (id: number) => call<any>(`/limits/api/limits/eod/runs/${id}`, "GET"),
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
  suggestGroup: (id: number, actor: string) =>
    call<any>(`/counterparty/api/initiation/counterparties/${id}/group/suggest`, "POST", undefined, actor),
  createGroup: (body: any, actor: string) =>
    call<any>("/counterparty/api/initiation/groups", "POST", body, actor),
  tagToGroup: (counterpartyId: number, groupId: number, actor: string) =>
    call<any>(`/counterparty/api/initiation/counterparties/${counterpartyId}/group/${groupId}`,
              "POST", undefined, actor),
  listGroups: () => call<any[]>("/counterparty/api/initiation/groups", "GET").catch(() => []),
};

// ---- syndication agency: book · fee waterfall · agency reconciliation · feed ----
// (invitations + secondary transfers extend this object further down — kept
// as a single export so the lifecycle surface is one import in the UI.)

// ---- pre-disbursement: Condition Precedent register + Disbursement workflow ----
export const cps = {
  seed: (ref: string, actor: string) =>
    call<any[]>(`/decision/api/cps/${ref}/seed`, "POST", undefined, actor),
  register: (ref: string, facilityRef?: string) =>
    call<any[]>(`/decision/api/cps/${ref}${facilityRef ? `?facilityRef=${facilityRef}` : ""}`, "GET"),
  gate: (ref: string, facilityRef: string) =>
    call<any>(`/decision/api/cps/gate/${ref}/${facilityRef}`, "GET"),
  add: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/cps/${ref}`, "POST", body, actor),
  clear: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/cps/${id}/clear`, "POST", body, actor),
  waive: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/cps/${id}/waive`, "POST", body, actor),
  reject: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/cps/${id}/reject`, "POST", body, actor),
};

// ---- project-finance post-drawdown mechanics: milestones + reserves ----
export const pf = {
  milestones: (ref: string, facilityRef?: string) =>
    call<any[]>(`/decision/api/pf/${ref}/milestones${facilityRef ? `?facilityRef=${facilityRef}` : ""}`, "GET"),
  defineMilestone: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/pf/${ref}/milestones`, "POST", body, actor),
  certify: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/pf/milestones/${id}/certify`, "POST", body, actor),
  reserves: (ref: string) => call<any[]>(`/decision/api/pf/${ref}/reserves`, "GET"),
  defineReserve: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/pf/${ref}/reserves`, "POST", body, actor),
  fund: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/pf/reserves/${id}/fund`, "POST", body, actor),
  withdraw: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/pf/reserves/${id}/withdraw`, "POST", body, actor),
  gate: (ref: string, facilityRef: string, seq?: number) =>
    call<any>(`/decision/api/pf/gate/${ref}/${facilityRef}${seq != null ? `?milestoneSequence=${seq}` : ""}`, "GET"),
  waterfall: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/pf/${ref}/waterfall`, "POST", body, actor),
};

export const disbursement = {
  request: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/disbursement/${ref}/request`, "POST", body, actor),
  authorize: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/disbursement/${id}/authorize`, "POST", body, actor),
  release: (id: number, actor: string) =>
    call<any>(`/decision/api/disbursement/${id}/release`, "POST", undefined, actor),
  reject: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/disbursement/${id}/reject`, "POST", body, actor),
  amend: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/disbursement/${id}/amend`, "POST", body, actor),
  cancel: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/disbursement/${id}/cancel`, "POST", body, actor),
  reverse: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/disbursement/${id}/reverse`, "POST", body, actor),
  history: (ref: string, facilityRef?: string) =>
    call<any[]>(`/decision/api/disbursement/${ref}${facilityRef ? `?facilityRef=${facilityRef}` : ""}`, "GET"),
};

// ---- repayments (inbound money leg: schedule + maker-checker + connector) ----
export const repayments = {
  schedule: (ref: string, facilityRef: string, method = "EMI", frequency = "MONTHLY") =>
    call<any>(`/decision/api/repayments/${ref}/schedule?facilityRef=${facilityRef}&method=${method}&frequency=${frequency}`, "GET"),
  history: (ref: string, facilityRef?: string) =>
    call<any[]>(`/decision/api/repayments/${ref}${facilityRef ? `?facilityRef=${facilityRef}` : ""}`, "GET"),
  outstanding: (ref: string, facilityRef: string) =>
    call<any>(`/decision/api/repayments/${ref}/outstanding?facilityRef=${facilityRef}`, "GET"),
  record: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/repayments/${ref}/record`, "POST", body, actor),
  confirm: (id: number, actor: string) =>
    call<any>(`/decision/api/repayments/${id}/confirm`, "POST", undefined, actor),
  reject: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/repayments/${id}/reject`, "POST", body, actor),
};

export const syndication = {
  // agency: book / fee waterfall / agency reconciliation / feed
  book: (ref: string) => call<any>(`/origination/api/syndication/${ref}/book`, "GET"),
  allocate: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/syndication/${ref}/allocate`, "POST", body, actor),
  allocations: (ref: string) => call<any[]>(`/origination/api/syndication/${ref}/allocations`, "GET"),
  feed: (ref: string) => call<any>(`/origination/api/syndication/${ref}/feed`, "GET"),
  // invitations
  invite: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/syndication/${ref}/invitations`, "POST", body, actor),
  acceptInvitation: (id: number, actor: string) =>
    call<any>(`/origination/api/syndication/invitations/${id}/accept`, "POST", undefined, actor),
  declineInvitation: (id: number, body: any, actor: string) =>
    call<any>(`/origination/api/syndication/invitations/${id}/decline`, "POST", body, actor),
  withdrawInvitation: (id: number, body: any, actor: string) =>
    call<any>(`/origination/api/syndication/invitations/${id}/withdraw`, "POST", body, actor),
  invitations: (ref: string) =>
    call<any[]>(`/origination/api/syndication/${ref}/invitations`, "GET"),
  // secondary transfers
  proposeTransfer: (ref: string, body: any, actor: string) =>
    call<any>(`/origination/api/syndication/${ref}/transfers`, "POST", body, actor),
  settleTransfer: (id: number, body: any, actor: string) =>
    call<any>(`/origination/api/syndication/transfers/${id}/settle`, "POST", body, actor),
  rejectTransfer: (id: number, body: any, actor: string) =>
    call<any>(`/origination/api/syndication/transfers/${id}/reject`, "POST", body, actor),
  transfers: (ref: string) =>
    call<any[]>(`/origination/api/syndication/${ref}/transfers`, "GET"),
};

// ---- post-sanction facility amendments (DoA-routed) ----
export const amendments = {
  propose: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/amendments/${ref}/propose`, "POST", body, actor),
  approve: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/amendments/${id}/approve`, "POST", body, actor),
  reject: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/amendments/${id}/reject`, "POST", body, actor),
  history: (ref: string) => call<any[]>(`/decision/api/amendments/${ref}`, "GET"),
};

// ---- client planning template (CPT) ----
export const cpt = {
  generate: (ref: string, body: any, actor: string) =>
    call<any>(`/decision/api/cpt/${ref}/generate`, "POST", body, actor),
  latest: (ref: string) => call<any>(`/decision/api/cpt/${ref}`, "GET").catch(() => null),
  versions: (ref: string) => call<any[]>(`/decision/api/cpt/${ref}/versions`, "GET").catch(() => [] as any[]),
  review: (id: number, body: any, actor: string) =>
    call<any>(`/decision/api/cpt/templates/${id}/review`, "POST", body, actor),
};

// ---- groups · decisioning (advisory rollup + combined CP) ----
export const groups = {
  byReference: (ref: string) =>
    call<any>(`/counterparty/api/initiation/groups/by-reference/${ref}`, "GET"),
  exposureByReference: (ref: string) =>
    call<any>(`/counterparty/api/initiation/groups/by-reference/${ref}/exposure`, "GET"),
  insights: (ref: string, actor: string) =>
    call<any>(`/decision/api/decisions/groups/${ref}/insights`, "GET", undefined, actor),
  generateCombinedProposal: (ref: string, actor: string) =>
    call<any>(`/decision/api/decisions/groups/${ref}/combined-proposal/generate`,
              "POST", undefined, actor),
  combinedProposal: (ref: string) =>
    call<any>(`/decision/api/decisions/groups/${ref}/combined-proposal`, "GET"),
  combinedVersions: (ref: string) =>
    call<any[]>(`/decision/api/decisions/groups/${ref}/combined-proposal/versions`, "GET"),
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
