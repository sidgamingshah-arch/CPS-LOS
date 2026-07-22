// Thin client over the Helix API gateway. A login mints a bearer token; the gateway
// verifies it and injects the verified X-Actor (PRD §9/§11 human accountability), so
// the actor can no longer be spoofed. We still send X-Actor as a hint, but a present
// token always wins server-side.

const GATEWAY: string =
  (import.meta as any).env?.VITE_GATEWAY_URL || "http://localhost:8080";

export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

let authToken: string | null = null;
export function setAuthToken(token: string | null) { authToken = token; }

async function call<T>(path: string, method: Method, body?: unknown, actor = "demo.user"): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", "X-Actor": actor };
  if (authToken) headers["Authorization"] = "Bearer " + authToken;
  const res = await fetch(GATEWAY + path, {
    method,
    headers,
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

// ---- authentication ----
export type LoginResult = {
  token: string; actor: string; displayName: string;
  expiresInSeconds: number; roles: string[];
};
export const auth = {
  login: (username: string, password: string) =>
    call<LoginResult>("/config/api/auth/login", "POST", { username, password }),
  me: () => call<{ actor: string; roles: string[]; expiresAtMillis: number }>(
    "/config/api/auth/me", "GET"),
  mode: () => call<{ mode: string; enforced: boolean; securityMode?: string }>("/auth/mode", "GET"),
};

// ---- SSO / real authentication (helix.security.mode: none | oidc | ldap) ----
// `mode` is UNAUTHENTICATED so the SPA can read it before login to decide between the
// mock/actor-selector login (none) and the Authorization-Code + PKCE SSO redirect (oidc).
export type OidcClientConfig = {
  authorizationUri?: string; tokenUri?: string; clientId?: string;
  scopes?: string; redirectUri?: string;
};
export type SecurityMode = {
  mode: string; oidc: boolean; ldap: boolean; secured: boolean;
  oidcClient?: OidcClientConfig;
};
export const security = {
  mode: () => call<SecurityMode>("/config/api/security/mode", "GET"),
  whoami: () => call<{ actor: string; roles: string[]; authenticated: boolean; mode: string }>(
    "/config/api/security/whoami", "GET"),
};

// ---- config / abstraction layer ----
export const config = {
  jurisdictions: () => call<any[]>("/config/api/jurisdictions", "GET"),
  pack: (jurisdiction: string, type: string) =>
    call<any>(`/config/api/rulepacks?jurisdiction=${jurisdiction}&type=${type}`, "GET"),
  // ---- G6 rule-pack authoring (draft -> dual sign-off -> activate) ----
  drafts: () => call<any[]>("/config/api/rulepacks/drafts", "GET"),
  createRulePack: (
    body: { code: string; type: string; jurisdiction: string; effectiveFrom: string; payload: any },
    actor: string,
  ) => call<any>("/config/api/rulepacks", "POST", body, actor),
  signoff: (id: number, control: "policy" | "model-risk", actor: string) =>
    call<any>(`/config/api/rulepacks/${id}/signoff?control=${control}`, "POST", undefined, actor),
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
  // G7 — effective RBAC governance posture on a service (query a downstream service, not
  // config-service, which has no ActorDirectory bean).
  posture: (svc = "decision") =>
    call<{ service: string; failClosed: boolean; source: string; simulateOutage: boolean }>(
      `/${svc}/api/governance/rbac/posture`, "GET"),
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
  // lifecycle (D9): governed CLOSED transition + deterministic re-KYC sweep
  close: (id: number, body: any, actor: string) =>
    call<any>(`/counterparty/api/counterparties/${id}/close`, "POST", body, actor),
  reKycSweep: (asOf: string | undefined, actor: string) =>
    call<any>(`/counterparty/api/counterparties/rekyc/sweep` + (asOf ? `?asOf=${asOf}` : ""), "POST", undefined, actor),
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

// ---- config-driven dynamic screen behaviour (FIELD_POLICY, helix-common auto-exposed) ----
// A form fetches its field specs once (label/help overrides + conditional visibility/required).
// Server-side enforcement is authoritative; this read only drives the UI convenience layer.
export const fieldPolicy = {
  get: (formKey: string) =>
    call<{ formKey: string; fields: any[] }>(`/origination/api/field-policy/${formKey}`, "GET"),
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
  // ---- configurable, parameter-routed scoring approval (gate, not a figure change) ----
  scoringApproval: (ref: string) => call<any>(`/risk/api/risk/${ref}/scoring-approval`, "GET"),
  // Read-only, NON-persisting: simulate the SCORING_APPROVAL_POLICY routing for hypothetical
  // params (exposure/grade/overrideNotches/overridden/segment/jurisdiction/scoreBand). Evaluates
  // the ACTIVE policy via the same engine the rating path uses; no rating is read or mutated.
  simulateScoringApproval: (body: {
    exposure?: number; grade?: string; overrideNotches?: number; overridden?: boolean;
    segment?: string; jurisdiction?: string; scoreBand?: string;
  }) => call<{ matchedRuleId: string; requireApproval: boolean; requiredAuthority: string | null }>(
    "/risk/api/risk/scoring-approval/simulate", "POST", body),
};

// ---- configurable scoring-model engine (sections of typed questions; advisory composite) ----
export const models = {
  render: (ref: string) => call<any>(`/risk/api/risk/${ref}/model`, "GET"),
  resolve: (ref: string, actor: string, sector?: string) =>
    call<any>(`/risk/api/risk/${ref}/model/resolve${sector ? `?sector=${encodeURIComponent(sector)}` : ""}`,
              "POST", undefined, actor),
  answer: (ref: string, answers: any[], actor: string) =>
    call<any>(`/risk/api/risk/${ref}/model/answer`, "POST", { answers }, actor),
  suggest: (ref: string, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/model/suggest`, "POST", undefined, actor),
  confirm: (ref: string, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/model/confirm`, "POST", undefined, actor),
  // resolve a definition by selector (config-service), for the builder's preview/test
  resolveDefinition: (jurisdiction?: string, sector?: string, segment?: string) => {
    const q = new URLSearchParams();
    if (jurisdiction) q.set("jurisdiction", jurisdiction);
    if (sector) q.set("sector", sector);
    if (segment) q.set("segment", segment);
    return call<any>(`/config/api/models/resolve?${q.toString()}`, "GET");
  },
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

// ---- Supply-Chain Finance (SCF) product paper (anchor programme + spokes) ----
export const scf = {
  list: (anchorRef?: string) =>
    call<any[]>(`/origination/api/scf/programs${anchorRef ? `?anchorRef=${encodeURIComponent(anchorRef)}` : ""}`, "GET"),
  get: (scfRef: string) => call<any>(`/origination/api/scf/programs/${scfRef}`, "GET"),
  create: (body: any, actor: string) => call<any>("/origination/api/scf/programs", "POST", body, actor),
  addSpoke: (scfRef: string, body: any, actor: string) =>
    call<any>(`/origination/api/scf/programs/${scfRef}/spokes`, "POST", body, actor),
  submit: (scfRef: string, actor: string) =>
    call<any>(`/origination/api/scf/programs/${scfRef}/submit`, "POST", undefined, actor),
  approve: (scfRef: string, body: any, actor: string) =>
    call<any>(`/origination/api/scf/programs/${scfRef}/approve`, "POST", body, actor),
  reject: (scfRef: string, body: any, actor: string) =>
    call<any>(`/origination/api/scf/programs/${scfRef}/reject`, "POST", body, actor),
  withdraw: (scfRef: string, actor: string) =>
    call<any>(`/origination/api/scf/programs/${scfRef}/withdraw`, "POST", undefined, actor),
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
  crilc: (actor: string) => call<any>("/portfolio/api/exports/crilc", "POST", undefined, actor),
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
  // Credit proposal: optional CAM `format` (omitted -> deal-segment default -> STANDARD, byte-identical
  // to the pre-format proposal). Existing callers pass no format and keep working unchanged.
  generateProposal: (ref: string, actor: string, format?: string) =>
    call<any>(`/decision/api/decisions/${ref}/credit-proposal/generate`, "POST",
      format ? { format } : undefined, actor),
  latestProposal: (ref: string) => call<any>(`/decision/api/decisions/${ref}/credit-proposal`, "GET"),
  proposalVersions: (ref: string) => call<any[]>(`/decision/api/decisions/${ref}/credit-proposal/versions`, "GET"),
  // committee / quorum voting (D9/committee mode) + sanction letter (P1 decisioning loop)
  votes: (ref: string) => call<any[]>(`/decision/api/decisions/${ref}/votes`, "GET"),
  sanctionLetter: (ref: string, actor: string) =>
    call<any>(`/decision/api/decisions/${ref}/sanction-letter`, "POST", undefined, actor),
  // Available CAM proposal formats for the picker; optional segment flags/sorts the default first.
  proposalFormats: (segment?: string) =>
    call<any[]>(`/decision/api/decisions/proposal-formats${segment ? `?segment=${encodeURIComponent(segment)}` : ""}`, "GET"),
  // Render-only, NON-persisting proposal under a chosen CAM format (side-by-side compare). Reuses
  // the format-aware assembly of `generate` but writes NO version and stamps NO audit — comparing
  // formats never spams versions. `generate` stays the real, persisting action.
  previewProposal: (ref: string, format?: string) =>
    call<{
      applicationReference: string; format: string; label: string; sections: string[];
      markdown: string; html: string; citations: Record<string, any>; llmDrafted: boolean;
    }>(`/decision/api/decisions/${ref}/credit-proposal/preview${format ? `?format=${encodeURIComponent(format)}` : ""}`, "GET"),
};

// ---- conflict-of-interest (COI) attestations ----
export const coi = {
  list: (subjectRef: string) =>
    call<any[]>(`/decision/api/coi?subjectRef=${encodeURIComponent(subjectRef)}`, "GET"),
  get: (coiRef: string) => call<any>(`/decision/api/coi/${coiRef}`, "GET"),
  attest: (
    body: { subjectType: string; subjectRef: string; role?: string; declaration: string; note?: string },
    actor: string,
  ) => call<any>("/decision/api/coi", "POST", body, actor),
};

// ---- document comparison / incremental-change diff (CLoM F57) ----
// Deterministic, read-only structured comparison of two versioned artifacts already in
// decision-service: two credit-proposal versions (leftRef/rightRef = version numbers) or two
// generated-document versions (leftRef/rightRef = document ids). Returns a change table
// (ADDED/REMOVED/CHANGED/UNCHANGED). Never mutates either source.
export type DiffRow = {
  section: string; changeType: "ADDED" | "REMOVED" | "CHANGED" | "UNCHANGED";
  oldValue: string | null; newValue: string | null;
};
export type Comparison = {
  comparisonRef: string; kind: string; subjectRef: string;
  leftRef: string; rightRef: string; leftLabel: string; rightLabel: string;
  added: number; removed: number; changed: number; unchanged: number;
  advisory: boolean; createdBy: string; createdAt: string; diff: DiffRow[];
};
export const docCompare = {
  list: (subjectRef: string) =>
    call<Comparison[]>(`/decision/api/doc-compare?subjectRef=${encodeURIComponent(subjectRef)}`, "GET"),
  get: (comparisonRef: string) => call<Comparison>(`/decision/api/doc-compare/${comparisonRef}`, "GET"),
  compare: (
    body: { kind: string; subjectRef: string; leftRef: string; rightRef: string },
    actor: string,
  ) => call<Comparison>("/decision/api/doc-compare", "POST", body, actor),
};

// ---- notings (governed decision records: TOD, CAM note, product paper, deferrals, …) ----
export const notings = {
  list: (params?: { subjectRef?: string; status?: string; type?: string }) => {
    const q = new URLSearchParams();
    if (params?.subjectRef) q.set("subjectRef", params.subjectRef);
    if (params?.status) q.set("status", params.status);
    if (params?.type) q.set("type", params.type);
    const qs = q.toString();
    return call<any[]>(`/decision/api/notings${qs ? `?${qs}` : ""}`, "GET");
  },
  get: (ref: string) => call<any>(`/decision/api/notings/${ref}`, "GET"),
  create: (body: any, actor: string) => call<any>("/decision/api/notings", "POST", body, actor),
  submit: (ref: string, actor: string) =>
    call<any>(`/decision/api/notings/${ref}/submit`, "POST", undefined, actor),
  approve: (ref: string, note: string | undefined, actor: string) =>
    call<any>(`/decision/api/notings/${ref}/approve`, "POST", { note }, actor),
  cadAuthorize: (ref: string, note: string | undefined, actor: string) =>
    call<any>(`/decision/api/notings/${ref}/cad-authorize`, "POST", { note }, actor),
  reject: (ref: string, reason: string, actor: string) =>
    call<any>(`/decision/api/notings/${ref}/reject`, "POST", { reason }, actor),
  reverse: (ref: string, reason: string, actor: string) =>
    call<any>(`/decision/api/notings/${ref}/reverse`, "POST", { reason }, actor),
  withdraw: (ref: string, actor: string) =>
    call<any>(`/decision/api/notings/${ref}/withdraw`, "POST", undefined, actor),
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
  monitorSweepAll: (actor: string) =>
    call<any[]>("/portfolio/api/portfolio/monitoring/sweep", "POST", undefined, actor),
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
  // working-capital drawing-power monitoring (D-RBI; advisory, deterministic)
  drawingPower: (ref: string, body: any, actor: string) =>
    call<any>(`/portfolio/api/portfolio/exposures/${ref}/drawing-power`, "POST", body, actor),
  drawingPowerHistory: (ref: string, facilityRef?: string) =>
    call<any[]>(`/portfolio/api/portfolio/exposures/${ref}/drawing-power` + (facilityRef ? `?facilityRef=${facilityRef}` : ""), "GET"),
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

// ---- monitoring artifacts (post-disbursement; ONE lifecycle, master-driven) ----
export const monitoringArtifacts = {
  list: (q?: { subjectRef?: string; status?: string; type?: string }) => {
    const p = new URLSearchParams();
    if (q?.subjectRef) p.set("subjectRef", q.subjectRef);
    if (q?.status) p.set("status", q.status);
    if (q?.type) p.set("type", q.type);
    const qs = p.toString();
    return call<any[]>("/portfolio/api/monitoring/artifacts" + (qs ? `?${qs}` : ""), "GET");
  },
  get: (ref: string) => call<any>(`/portfolio/api/monitoring/artifacts/${ref}`, "GET"),
  create: (body: any, actor: string) =>
    call<any>("/portfolio/api/monitoring/artifacts", "POST", body, actor),
  updateSections: (ref: string, sections: any, actor: string) =>
    call<any>(`/portfolio/api/monitoring/artifacts/${ref}/sections`, "PUT", { sections }, actor),
  submit: (ref: string, actor: string) =>
    call<any>(`/portfolio/api/monitoring/artifacts/${ref}/submit`, "POST", undefined, actor),
  review: (ref: string, notes: string, actor: string) =>
    call<any>(`/portfolio/api/monitoring/artifacts/${ref}/review`, "POST", { notes }, actor),
  approve: (ref: string, notes: string, actor: string) =>
    call<any>(`/portfolio/api/monitoring/artifacts/${ref}/approve`, "POST", { notes }, actor),
  authorize: (ref: string, notes: string, actor: string) =>
    call<any>(`/portfolio/api/monitoring/artifacts/${ref}/authorize`, "POST", { notes }, actor),
  vendorRfq: (ref: string, vendorId: string, question: string, actor: string) =>
    call<any>(`/portfolio/api/monitoring/artifacts/${ref}/vendor-rfq`, "POST", { vendorId, question }, actor),
};

// ---- escrow monitoring (record surface; deterministic budget-vs-actual + RAG) ----
export const escrow = {
  list: (subjectRef?: string) =>
    call<any[]>("/portfolio/api/escrow/accounts" + (subjectRef ? `?subjectRef=${encodeURIComponent(subjectRef)}` : ""), "GET"),
  get: (ref: string) => call<any>(`/portfolio/api/escrow/accounts/${ref}`, "GET"),
  create: (body: any, actor: string) => call<any>("/portfolio/api/escrow/accounts", "POST", body, actor),
  addBudgetLine: (ref: string, body: any, actor: string) =>
    call<any>(`/portfolio/api/escrow/accounts/${ref}/budget-lines`, "POST", body, actor),
  budgetHistory: (ref: string) => call<any[]>(`/portfolio/api/escrow/accounts/${ref}/budget-lines`, "GET"),
  postTransaction: (ref: string, body: any, actor: string) =>
    call<any>(`/portfolio/api/escrow/accounts/${ref}/transactions`, "POST", body, actor),
  transactions: (ref: string) => call<any[]>(`/portfolio/api/escrow/accounts/${ref}/transactions`, "GET"),
  budgetVsActual: (ref: string) => call<any>(`/portfolio/api/escrow/accounts/${ref}/budget-vs-actual`, "GET"),
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

// ---- MOE / mortgage security perfection ----
export const perfection = {
  list: (subjectRef?: string, status?: string) => {
    const q = [subjectRef ? `subjectRef=${encodeURIComponent(subjectRef)}` : "", status ? `status=${status}` : ""]
      .filter(Boolean).join("&");
    return call<any[]>("/decision/api/perfection/cases" + (q ? `?${q}` : ""), "GET");
  },
  create: (body: any, actor: string) => call<any>("/decision/api/perfection/cases", "POST", body, actor),
  view: (perfRef: string) => call<any>(`/decision/api/perfection/cases/${perfRef}`, "GET"),
  complete: (perfRef: string, stepKey: string, body: any, actor: string) =>
    call<any>(`/decision/api/perfection/cases/${perfRef}/steps/${stepKey}/complete`, "POST", body, actor),
  waive: (perfRef: string, stepKey: string, body: any, actor: string) =>
    call<any>(`/decision/api/perfection/cases/${perfRef}/steps/${stepKey}/waive`, "POST", body, actor),
  vendorRfq: (perfRef: string, stepKey: string, body: any, actor: string) =>
    call<any>(`/decision/api/perfection/cases/${perfRef}/steps/${stepKey}/vendor-rfq`, "POST", body, actor),
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

// ---- SRM (structured review / renewal — built on the Noting engine) ----
export const srm = {
  list: (subjectRef?: string) =>
    call<any[]>("/decision/api/srm/reviews" + (subjectRef ? `?subjectRef=${encodeURIComponent(subjectRef)}` : ""), "GET"),
  get: (id: number) => call<any>(`/decision/api/srm/reviews/${id}`, "GET"),
  create: (body: any, actor: string) => call<any>("/decision/api/srm/reviews", "POST", body, actor),
  markItem: (id: number, code: string, done: boolean, actor: string) =>
    call<any>(`/decision/api/srm/reviews/${id}/checklist/${encodeURIComponent(code)}`, "POST", { done }, actor),
  submitNoting: (id: number, actor: string) =>
    call<any>(`/decision/api/srm/reviews/${id}/submit-noting`, "POST", undefined, actor),
  refresh: (id: number, actor: string) =>
    call<any>(`/decision/api/srm/reviews/${id}/refresh`, "POST", undefined, actor),
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

// ---- workflow engine (lifecycle stage tracker + SLA) ----
export type WorkflowStage = {
  id: number; instanceId: number; ordinal: number; stageKey: string; label: string;
  autonomy: string; aiAllowed: boolean; humanGate: boolean; slaHours: number;
  status: string; enteredAt?: string; completedAt?: string;
  completedBy?: string; completedByType?: string; note?: string;
  slaDueAt?: string; slaBreached: boolean; blockedReason?: string;
};
export type WorkflowInstance = {
  id: number; applicationReference: string; definitionCode?: string; definitionVersion?: number;
  jurisdiction?: string; segment?: string; currentStageKey?: string; status: string;
  startedAt?: string; completedAt?: string; slaBreached: boolean;
};
export type WorkflowView = {
  instance: WorkflowInstance; stages: WorkflowStage[]; transitions: any[];
};
export const workflow = {
  materialise: (applicationReference: string, jurisdiction: string, segment: string, actor: string) =>
    call<WorkflowInstance>("/workflow/api/workflow/instances", "POST",
                            { applicationReference, jurisdiction, segment }, actor),
  view: (ref: string) => call<WorkflowView>(`/workflow/api/workflow/instances/${ref}`, "GET"),
  active: () => call<WorkflowInstance[]>("/workflow/api/workflow/instances", "GET"),
  advance: (ref: string, stageKey: string, actorType: string, note: string, actor: string) =>
    call<WorkflowInstance>(`/workflow/api/workflow/instances/${ref}/advance`, "POST",
                            { stageKey, actorType, note }, actor),
  record: (ref: string, stageKey: string, actorType: string, note: string, actor: string) =>
    call<WorkflowInstance>(`/workflow/api/workflow/instances/${ref}/stages/${stageKey}/record`,
                            "POST", { actorType, note }, actor),
  block: (ref: string, stageKey: string, reason: string, actor: string) =>
    call<WorkflowStage>(`/workflow/api/workflow/instances/${ref}/stages/${stageKey}/block`,
                        "POST", { reason }, actor),
  unblock: (ref: string, stageKey: string, actor: string) =>
    call<WorkflowStage>(`/workflow/api/workflow/instances/${ref}/stages/${stageKey}/unblock`,
                        "POST", undefined, actor),
  slaBreaches: () => call<any[]>("/workflow/api/workflow/sla-breaches", "GET"),
};

// ---- self-service reporting (ad-hoc query builder over the book) ----
export type ReportColumn = { key: string; label: string; type: string; role: string };
export type ReportResult = {
  datasetKey: string; columns: ReportColumn[]; rows: any[][];
  totals: Record<string, any>; scannedRows: number; returnedRows: number;
};
export type ReportDefinition = {
  title?: string; dataset: string; dimensions?: string[];
  measures?: { field: string; agg: string; as: string }[];
  filters?: { field: string; op: string; value: any }[];
  sort?: { by: string; dir: string }[]; limit?: number;
};
export const reports = {
  datasets: () => call<any[]>("/portfolio/api/reports/datasets", "GET"),
  run: (def: ReportDefinition, actor: string) =>
    call<ReportResult>("/portfolio/api/reports/run", "POST", def, actor),
  runSaved: (key: string, actor: string) =>
    call<ReportResult>(`/portfolio/api/reports/${key}/run`, "GET", undefined, actor),
};

// ---- financial projections (multi-year proforma; deterministic, advisory) ----
export const projections = {
  view: (ref: string) => call<any>(`/risk/api/risk/${ref}/projection`, "GET"),
  setDrivers: (ref: string, drivers: Record<string, number>, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/projection/drivers`, "POST", { drivers }, actor),
  sensitivity: (ref: string, driver: string, delta: number, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/projection/sensitivity`, "POST", { driver, delta }, actor),
  confirm: (ref: string, actor: string) =>
    call<any>(`/risk/api/risk/${ref}/projection/confirm`, "POST", undefined, actor),
};

// ---- code values (the generic dropdown source of truth) ----
export type CodeValue = { code: string; label: string; score?: number; sortOrder?: number };
export type CodeValueSet = { domain: string; label: string; values: CodeValue[] };
export const codeValues = {
  get: (domain: string) => call<CodeValueSet>(`/config/api/code-values/${domain}`, "GET"),
  all: () => call<CodeValueSet[]>("/config/api/code-values", "GET"),
};

// ---- audit (any service exposes /api/audit) ----
export const audit = {
  recent: (svc: string) => call<any[]>(`/${svc}/api/audit`, "GET"),
  subject: (svc: string, type: string, id: string) =>
    call<any[]>(`/${svc}/api/audit/subject?type=${type}&id=${id}`, "GET"),
};

// ---- notifications outbox (G5-notify; any service exposes /api/notifications) ----
export const notifications = {
  list: (svc: string, q?: { status?: string; eventType?: string; subjectRef?: string }) => {
    const p = new URLSearchParams();
    if (q?.status) p.set("status", q.status);
    if (q?.eventType) p.set("eventType", q.eventType);
    if (q?.subjectRef) p.set("subjectRef", q.subjectRef);
    const qs = p.toString();
    return call<any[]>(`/${svc}/api/notifications` + (qs ? `?${qs}` : ""), "GET");
  },
  get: (svc: string, id: number) => call<any>(`/${svc}/api/notifications/${id}`, "GET"),
  // ---- notification-center read-state (bell + unread badge) ----
  unreadCount: (svc: string, q?: { recipient?: string; role?: string }) => {
    const p = new URLSearchParams();
    if (q?.recipient) p.set("recipient", q.recipient);
    if (q?.role) p.set("role", q.role);
    const qs = p.toString();
    return call<{ unread: number }>(`/${svc}/api/notifications/unread-count` + (qs ? `?${qs}` : ""), "GET");
  },
  markRead: (svc: string, id: number, actor: string) =>
    call<any>(`/${svc}/api/notifications/${id}/read`, "POST", undefined, actor),
  markAllRead: (svc: string, actor: string, recipient?: string) =>
    call<{ read: number }>(
      `/${svc}/api/notifications/read-all` + (recipient ? `?recipient=${encodeURIComponent(recipient)}` : ""),
      "POST", undefined, actor),
};

/** Currency symbol per ISO code; falls back to the code itself for anything unmapped. */
const CCY_SYMBOL: Record<string, string> = { INR: "₹", AED: "AED ", USD: "$", EUR: "€", GBP: "£" };

export const fmt = {
  /**
   * Compact, currency-aware money. Large figures are scaled to readable units so a
   * book of ₹2.6 lakh-crore no longer prints as a wall of digits: INR uses the
   * Indian scale (Cr = crore = 1e7, L = lakh = 1e5); every other currency uses the
   * international scale (Bn/Mn/K). Pass the deal/exposure currency as `ccy`.
   * Use `moneyFull` when the exact rupee figure matters (tooltips, exports).
   */
  money: (v: number, ccy = "INR") => {
    if (v == null || Number.isNaN(v)) return "—";
    const sign = v < 0 ? "-" : "";
    const abs = Math.abs(v);
    const sym = CCY_SYMBOL[ccy] ?? (ccy ? ccy + " " : "");
    const scaled = (n: number, dp: number, locale: string) =>
      sign + sym + n.toLocaleString(locale, { minimumFractionDigits: 0, maximumFractionDigits: dp });
    if (ccy === "INR") {
      if (abs >= 1e7) return scaled(abs / 1e7, 2, "en-IN") + " Cr";
      if (abs >= 1e5) return scaled(abs / 1e5, 2, "en-IN") + " L";
      return scaled(abs, 0, "en-IN");
    }
    if (abs >= 1e9) return scaled(abs / 1e9, 2, "en-US") + " Bn";
    if (abs >= 1e6) return scaled(abs / 1e6, 2, "en-US") + " Mn";
    if (abs >= 1e3) return scaled(abs / 1e3, 1, "en-US") + " K";
    return scaled(abs, 0, "en-US");
  },
  /** Exact grouped figure with the currency code — no unit scaling. */
  moneyFull: (v: number, ccy = "INR") =>
    v == null ? "—" : new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 }).format(v) + (ccy ? " " + ccy : ""),
  pct: (v: number, dp = 2) => (v == null ? "—" : (v * 100).toFixed(dp) + "%"),
  num: (v: number, dp = 2) => (v == null ? "—" : v.toFixed(dp)),
  /** Date-only, unambiguous "12 Mar 2026" (locale-stable; accepts ISO strings/epoch/Date). */
  date: (v: string | number | Date | null | undefined) => {
    if (v == null || v === "") return "—";
    // Parse a bare YYYY-MM-DD as LOCAL midnight, not UTC — otherwise new Date("2026-03-15")
    // is UTC and renders as the 14th in any negative-offset timezone.
    let d: Date;
    if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v)) {
      const [y, m, day] = v.split("-").map(Number);
      d = new Date(y, m - 1, day);
    } else {
      d = new Date(v);
    }
    if (isNaN(d.getTime())) return String(v);
    return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
  },
  /** Date + 24h time "12 Mar 2026, 14:05" for timestamps (audit rows, events). */
  dateTime: (v: string | number | Date | null | undefined) => {
    if (v == null || v === "") return "—";
    const d = new Date(v);
    if (isNaN(d.getTime())) return String(v);
    return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" }) +
      ", " + d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
  },
};

// ---- IP notes (In-Principle sponsorship notes; precede a full application) ----
export const ipNotes = {
  list: (params?: { counterpartyRef?: string; status?: string }) => {
    const q = new URLSearchParams();
    if (params?.counterpartyRef) q.set("counterpartyRef", params.counterpartyRef);
    if (params?.status) q.set("status", params.status);
    const qs = q.toString();
    return call<any[]>(`/origination/api/ip-notes${qs ? `?${qs}` : ""}`, "GET");
  },
  get: (ref: string) => call<any>(`/origination/api/ip-notes/${ref}`, "GET"),
  create: (body: any, actor: string) => call<any>("/origination/api/ip-notes", "POST", body, actor),
  submit: (ref: string, actor: string) =>
    call<any>(`/origination/api/ip-notes/${ref}/submit`, "POST", undefined, actor),
  approve: (ref: string, note: string | undefined, actor: string) =>
    call<any>(`/origination/api/ip-notes/${ref}/approve`, "POST", { note }, actor),
  reject: (ref: string, reason: string, actor: string) =>
    call<any>(`/origination/api/ip-notes/${ref}/reject`, "POST", { reason }, actor),
  withdraw: (ref: string, actor: string) =>
    call<any>(`/origination/api/ip-notes/${ref}/withdraw`, "POST", undefined, actor),
  convert: (ref: string, actor: string) =>
    call<any>(`/origination/api/ip-notes/${ref}/convert`, "POST", undefined, actor),
};

// ---- TAT / MIS reporting over the case & query operational data (deterministic, read-only) ----
export const tatMis = {
  // Cycle-time / SLA / rework / throughput aggregations over WorkItem + WorkItemEvent.
  mis: (q?: { queueKey?: string; taskType?: string; from?: string; to?: string }) => {
    const p = new URLSearchParams();
    if (q?.queueKey) p.set("queueKey", q.queueKey);
    if (q?.taskType) p.set("taskType", q.taskType);
    if (q?.from) p.set("from", q.from);
    if (q?.to) p.set("to", q.to);
    const qs = p.toString();
    return call<any>("/workflow/api/tasks/mis" + (qs ? `?${qs}` : ""), "GET");
  },
  // Per-subject TAT (derived from the event timeline).
  tat: (subjectRef: string) =>
    call<any>(`/workflow/api/tasks/tat?subjectRef=${encodeURIComponent(subjectRef)}`, "GET"),
  // Query / RFI SLA rollup for a given service (auto-exposed per service by helix-common).
  querySla: (svc: string) => call<any>(`/${svc}/api/queries/sla-rollup`, "GET"),
};

// ---- case-management tasks (WorkItem inbox; workflow-service /api/tasks) ----
// Read-only surfaces used by the role-scoped landing dashboards ("my tasks").
export const tasks = {
  inbox: (assignee: string) =>
    call<any[]>(`/workflow/api/tasks/inbox?assignee=${encodeURIComponent(assignee)}`, "GET"),
  queue: (key: string) =>
    call<any[]>(`/workflow/api/tasks/queue/${encodeURIComponent(key)}`, "GET"),
  subject: (ref: string, type?: string) =>
    call<any[]>(`/workflow/api/tasks/subject?ref=${encodeURIComponent(ref)}${type ? `&type=${encodeURIComponent(type)}` : ""}`, "GET"),
  get: (ref: string) => call<any>(`/workflow/api/tasks/${ref}`, "GET"),
  claim: (ref: string, actor: string) =>
    call<any>(`/workflow/api/tasks/${ref}/claim`, "POST", undefined, actor),
  complete: (ref: string, note: string | undefined, actor: string) =>
    call<any>(`/workflow/api/tasks/${ref}/complete`, "POST", { note }, actor),
};

// ---- query / RFI collaboration (helix-common surface on every service) ----
// The inbox for a role dashboard reads the decision-service shared surface (the
// primary collaboration lane); `svc` overridable for other services if needed.
export const queries = {
  // Pass `actor` so the server-side, caller-scoped listing (Fix 2) reflects the signed-in
  // user (a token, when present, still wins at the gateway). subjectRef/addressee narrow
  // WITHIN the caller's visible set; they never widen it.
  list: (q?: { subjectRef?: string; addressee?: string }, svc = "decision", actor?: string) => {
    const p = new URLSearchParams();
    if (q?.subjectRef) p.set("subjectRef", q.subjectRef);
    if (q?.addressee) p.set("addressee", q.addressee);
    const qs = p.toString();
    return call<any[]>(`/${svc}/api/queries` + (qs ? `?${qs}` : ""), "GET", undefined, actor);
  },
  get: (ref: string, svc = "decision", actor?: string) =>
    call<any>(`/${svc}/api/queries/${ref}`, "GET", undefined, actor),
};

// ---- print / PDF rendering (dependency-free print pipeline) ----
// The backend returns a self-contained, print-optimised standalone HTML document
// (letterhead, governance header, embedded @media print CSS, page-break styling) whose
// body reproduces the AUTHORITATIVE artifact verbatim. The frontend opens it in a new
// window and invokes the browser's print-to-PDF ("Save as PDF"). These endpoints return
// text/html (not JSON), so they use a raw text fetch that still forwards the auth token
// and X-Actor (persisted as a *_RENDERED audit event server-side).
async function fetchText(path: string, actor = "demo.user"): Promise<string> {
  const headers: Record<string, string> = { "X-Actor": actor };
  if (authToken) headers["Authorization"] = "Bearer " + authToken;
  const res = await fetch(GATEWAY + path, { method: "GET", headers });
  const text = await res.text();
  if (!res.ok) throw new Error(text || res.statusText || "Print render failed");
  return text;
}

export const printing = {
  // Standalone print/PDF HTML for a generated document (facility/sanction letter, …).
  documentHtml: (id: number, actor: string) =>
    fetchText(`/decision/api/docs/${id}/print`, actor),
  // Standalone print/PDF HTML for the latest credit proposal on an application.
  proposalHtml: (ref: string, actor: string) =>
    fetchText(`/decision/api/decisions/${encodeURIComponent(ref)}/credit-proposal/print`, actor),
  // Open a loaded standalone-HTML string in a new window; the page auto-invokes the
  // browser print dialog so the user can "Save as PDF". Pop-up-blocker aware.
  //
  // Security: the HTML is served from a Blob URL opened with `noopener`, so the new
  // window gets NO `window.opener` handle back to this app (no reverse-tabnabbing) and
  // is NOT same-origin with us. We never `document.write` fetched HTML into a live,
  // opener-retaining, same-origin window. The blob page still auto-prints via its own
  // embedded <script>. The object URL is revoked after a short delay so the browser has
  // time to load it.
  openHtmlWindow: (html: string, notify?: (m: string, err?: boolean) => void): boolean => {
    const blobUrl = URL.createObjectURL(new Blob([html], { type: "text/html" }));
    const w = window.open(blobUrl, "_blank", "noopener");
    if (!w) {
      URL.revokeObjectURL(blobUrl);
      notify?.("Enable pop-ups for this site to download the PDF.", true);
      return false;
    }
    setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000);
    return true;
  },
  // Convenience: fetch + open in one call, surfacing errors through `notify`.
  print: async (
    loader: Promise<string>,
    notify?: (m: string, err?: boolean) => void,
  ): Promise<void> => {
    try {
      const html = await loader;
      printing.openHtmlWindow(html, notify);
    } catch (e: any) {
      notify?.(e?.message ?? "Print render failed", true);
    }
  },
};
