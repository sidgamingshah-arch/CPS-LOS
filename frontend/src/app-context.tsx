import { createContext, useContext } from "react";

export type Nav = (view: string, ref?: string) => void;

export interface AppCtx {
  actor: string;
  /**
   * The acting identity's roles, resolved from the ACTOR_ROLE master at login
   * (echoed by /config/api/auth/login). Drives role-scoped navigation + the
   * role-scoped landing dashboards. Empty array = no roles known → treated as
   * default-permissive (see role-scope.ts). The server-side RBAC gate remains
   * the source of truth; this only shapes the UI.
   */
  roles: string[];
  notify: (text: string, err?: boolean) => void;
  nav: Nav;
  /**
   * The active deal reference (set by nav(view, ref) — e.g. arriving from the
   * Deal Workspace deep links). Deal-scoped screens seed their own deal
   * selector from this so context carries across screens; undefined when no
   * deal is active. Screens stay fully usable standalone.
   */
  ref?: string;
  /**
   * Resolved enabled-state of every governed AI capability, keyed by capability key
   * (e.g. "doc-intel", "rag-overlay"). When a capability is off, the matching UI
   * surface is hidden — the server-side gate (HTTP 403) is the source of truth, the
   * UI just stops nudging users to a disabled endpoint. Empty map = treat all as
   * enabled (conservative fallback).
   */
  aiEnabled: Record<string, boolean>;
}

export const AppContext = createContext<AppCtx>({
  actor: "demo.user",
  roles: [],
  notify: () => {},
  nav: () => {},
  aiEnabled: {},
});

export const useApp = () => useContext(AppContext);

/** Stable mapping of nav-key → AI capability (the one a screen hosts). */
export const AI_BY_NAV: Record<string, string> = {
  docintel: "doc-intel",
  commentary: "commentary",
  risklab: "rag-overlay",         // Risk Lab hosts RAG + macro overlays
  pricinglab: "pricing-optimiser",
  cpt: "cpt",
  copilot: "copilot",
};

/** True iff a nav screen's primary AI capability is enabled (or it isn't an AI screen). */
export function isNavEnabled(view: string, aiEnabled: Record<string, boolean>): boolean {
  const cap = AI_BY_NAV[view];
  if (!cap) return true;
  return aiEnabled[cap] !== false;
}

export const ACTORS = [
  "demo.user",
  "rm.user",
  "analyst.user",
  "credit.officer",
  "credit.committee",
  "compliance.officer",
  "credit.ops",
  "treasury.ops",
  "loan.ops",
  "loan.checker",
  "cad.maker",
  "lie.engineer",
  "portfolio.manager",
  "cro",
];
