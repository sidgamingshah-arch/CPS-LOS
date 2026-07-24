/**
 * Role → workspace scope + landing view.
 *
 * The sidebar was historically filtered ONLY by the AI-capability gate
 * (`isNavEnabled` in app-context). This module layers a ROLE gate on top:
 * an item is shown when BOTH the AI-capability gate passes AND the acting
 * role's scope allows it.
 *
 * Two invariants govern the design and are the reason nothing is ever hidden
 * from the demo super-user, an ADMIN, or the CRO:
 *
 *  1. **Default-permissive.** The escape hatch (`seesAll`) short-circuits the
 *     whole filter to "show everything" for see-all actors (demo.user),
 *     see-all roles (CRO / BOARD_COMMITTEE / ADMIN), an actor with NO roles,
 *     and an actor whose roles are ALL unmapped. Filtering only ever *narrows*
 *     for a role we positively recognise.
 *  2. **Config-driven, with a baked-in fallback.** The concrete map lives in
 *     the `ROLE_WORKSPACE` master (generic master engine) and is read at login;
 *     when that master is absent/unreachable we fall back to {@link FALLBACK}
 *     below, so the UI works stand-alone. A master record for a role fully
 *     overrides that role's fallback entry.
 *
 * The `Overview` group (Portfolio Dashboard · My Workspace · Copilot) is always
 * in scope for everyone, so no role is ever stranded without a landing page.
 */

/* ---- nav-group titles (MUST match the NAV_GROUPS titles in App.tsx) ---- */
export const GROUP_OVERVIEW = "Overview";
export const GROUP_ORIGINATE = "Originate";
export const GROUP_ASSESS = "Assess & Decide";
export const GROUP_PORTFOLIO = "Limits & Portfolio";
export const GROUP_CONFIG = "Configure & Govern";

export const ALL_GROUPS = [
  GROUP_OVERVIEW, GROUP_ORIGINATE, GROUP_ASSESS, GROUP_PORTFOLIO, GROUP_CONFIG,
];

/** Roles that see the entire platform (default-permissive escape). */
export const SEE_ALL_ROLES = new Set<string>(["CRO", "BOARD_COMMITTEE", "ADMIN", "SUPER"]);

/** Actors that always see everything regardless of role (the demo super-user). */
export const SEE_ALL_ACTORS = new Set<string>(["demo.user"]);

/** The resolved, effective scope for the acting identity. */
export interface Workspace {
  /** True → show the whole platform (demo / admin / CRO / unmapped). */
  seeAll: boolean;
  /** Whole nav-groups granted (group titles). Always includes Overview. */
  groups: string[];
  /** Extra individual nav-keys granted on top of the groups. */
  items: string[];
  /** Landing nav-key on login / actor change. */
  landing: string;
}

/** One role's scope fragment (fallback entry or a ROLE_WORKSPACE master payload). */
export interface RoleScope {
  seeAll?: boolean;
  groups?: string[];
  items?: string[];
  landing?: string;
}

export type RoleWorkspaceMap = Record<string, RoleScope>;

/**
 * Baked-in conservative fallback map keyed by the roles in the ACTOR_ROLE master.
 * Additive: a multi-role actor gets the UNION of every mapped role's groups/items.
 * `home` is the role-scoped landing dashboard (RoleDashboard); `dashboard` is the
 * existing portfolio dashboard (kept as the PORTFOLIO / see-all landing).
 */
export const FALLBACK: RoleWorkspaceMap = {
  RM:               { groups: [GROUP_ORIGINATE], landing: "home" },
  RM_HEAD:          { groups: [GROUP_ORIGINATE], landing: "home" },
  ANALYST:          { groups: [GROUP_ORIGINATE, GROUP_ASSESS], landing: "home" },
  CREDIT_OPS:       { groups: [GROUP_ORIGINATE, GROUP_ASSESS], landing: "home" },
  CREDIT_OFFICER:   { groups: [GROUP_ASSESS], landing: "home" },
  CREDIT_COMMITTEE: { groups: [GROUP_ASSESS], landing: "home" },
  // Compliance is a control function (2nd/3rd line): oversight of governance + audit, NOT origination.
  // The backend also blocks a COMPLIANCE actor from originating (ProtectedAction.ORIGINATE).
  COMPLIANCE:       { groups: [GROUP_PORTFOLIO], items: ["governance", "audit", "notifications"], landing: "home" },
  PORTFOLIO:        { groups: [GROUP_PORTFOLIO], landing: "dashboard" },
  CAD_OPS:          { groups: [GROUP_ASSESS, GROUP_PORTFOLIO], landing: "home" },
  LOAN_OPS:         { groups: [GROUP_PORTFOLIO], landing: "home" },
  TREASURY_OPS:     { groups: [GROUP_PORTFOLIO], landing: "home" },
  LIE:              { groups: [GROUP_PORTFOLIO], landing: "home" },
  COLLECTIONS_OPS:  { groups: [GROUP_PORTFOLIO], landing: "home" },
  COLLECTIONS_HEAD: { groups: [GROUP_PORTFOLIO, GROUP_ASSESS], landing: "home" },
  LEGAL:            { groups: [GROUP_ASSESS], landing: "home" },
  // See-all roles — explicit for clarity; seesAll() also short-circuits on them.
  CRO:              { seeAll: true, landing: "dashboard" },
  BOARD_COMMITTEE:  { seeAll: true, landing: "dashboard" },
  ADMIN:            { seeAll: true, landing: "dashboard" },
};

/** Landing precedence when an actor holds several mapped roles (first match wins). */
const LANDING_PRIORITY = [
  "CRO", "BOARD_COMMITTEE", "ADMIN",
  "CREDIT_COMMITTEE", "CREDIT_OFFICER", "CREDIT_OPS",
  "RM_HEAD", "RM", "ANALYST",
  "CAD_OPS", "LEGAL", "COMPLIANCE",
  "PORTFOLIO", "COLLECTIONS_HEAD", "COLLECTIONS_OPS", "LOAN_OPS", "TREASURY_OPS", "LIE",
];

/**
 * Parse the ROLE_WORKSPACE master (generic master-engine records: `recordKey`
 * = role, `payload` = { groups?, items?, landing?, seeAll? }) into an override
 * map. Robust to a missing/oddly-shaped payload — a bad record is skipped, not
 * thrown, so a malformed master can never blank the nav.
 */
export function parseRoleWorkspaceMaster(records: any[] | null | undefined): RoleWorkspaceMap {
  const out: RoleWorkspaceMap = {};
  if (!Array.isArray(records)) return out;
  for (const r of records) {
    const role = r?.recordKey;
    const p = r?.payload;
    if (!role || !p || typeof p !== "object") continue;
    const scope: RoleScope = {};
    if (Array.isArray(p.groups)) scope.groups = p.groups.map(String);
    if (Array.isArray(p.items)) scope.items = p.items.map(String);
    if (typeof p.landing === "string") scope.landing = p.landing;
    if (typeof p.seeAll === "boolean") scope.seeAll = p.seeAll;
    out[String(role)] = scope;
  }
  return out;
}

/** Effective role map: baked-in fallback with any ROLE_WORKSPACE master overrides layered on top (per role). */
function effectiveMap(overrides?: RoleWorkspaceMap): RoleWorkspaceMap {
  if (!overrides || Object.keys(overrides).length === 0) return FALLBACK;
  return { ...FALLBACK, ...overrides };
}

/**
 * The default-permissive escape. True → the identity sees the whole platform.
 * Fires for the demo super-user, any see-all role, an actor with no roles, and
 * an actor whose roles are ALL unmapped (we never narrow for a role we don't
 * positively recognise).
 */
export function seesAll(actor: string, roles: string[], overrides?: RoleWorkspaceMap): boolean {
  if (SEE_ALL_ACTORS.has(actor)) return true;
  if (!roles || roles.length === 0) return true;              // no roles → permissive
  const map = effectiveMap(overrides);
  if (roles.some((r) => SEE_ALL_ROLES.has(r) || map[r]?.seeAll)) return true;
  const anyRecognised = roles.some((r) => map[r] && !map[r].seeAll);
  if (!anyRecognised) return true;                            // roles present but all unmapped → permissive
  return false;
}

function pickLanding(roles: string[], map: RoleWorkspaceMap, dflt: string): string {
  for (const r of LANDING_PRIORITY) {
    if (roles.includes(r) && map[r]?.landing) return map[r]!.landing!;
  }
  // any mapped role that isn't in the priority list
  for (const r of roles) if (map[r]?.landing) return map[r]!.landing!;
  return dflt;
}

/**
 * See-all landing: the portfolio dashboard, unless a see-all role explicitly
 * overrides it. A super-user's natural home is the whole-book view, never a
 * narrow persona home inherited from an incidental operational role.
 */
function seeAllLanding(list: string[], map: RoleWorkspaceMap): string {
  for (const r of list) if (SEE_ALL_ROLES.has(r) && map[r]?.landing) return map[r]!.landing!;
  return "dashboard";
}

/** Resolve the effective workspace (scope + landing) for the acting identity. */
export function resolveWorkspace(actor: string, roles: string[], overrides?: RoleWorkspaceMap): Workspace {
  const map = effectiveMap(overrides);
  const list = roles || [];
  if (seesAll(actor, list, overrides)) {
    return { seeAll: true, groups: [...ALL_GROUPS], items: [], landing: seeAllLanding(list, map) };
  }
  const groups = new Set<string>([GROUP_OVERVIEW]);
  const items = new Set<string>();
  for (const r of list) {
    const w = map[r];
    if (!w) continue;
    (w.groups || []).forEach((g) => groups.add(g));
    (w.items || []).forEach((i) => items.add(i));
  }
  return { seeAll: false, groups: [...groups], items: [...items], landing: pickLanding(list, map, "home") };
}

/** True iff a nav-group is (at least partly) in scope. */
export function isNavGroupInScope(ws: Workspace, groupTitle: string): boolean {
  return ws.seeAll || ws.groups.includes(groupTitle);
}

/** True iff a specific nav item is in the role's scope (group-granted OR item-granted). */
export function isNavItemInScope(ws: Workspace, groupTitle: string, itemKey: string): boolean {
  if (ws.seeAll) return true;
  if (ws.groups.includes(groupTitle)) return true;
  return ws.items.includes(itemKey);
}

/* ---- persona (drives the RoleDashboard layout) ---- */
export type Persona = "RELATIONSHIP" | "CREDIT" | "RISK" | "CAD" | "PORTFOLIO" | "ADMIN";

/**
 * Coarse persona bucket for the landing dashboard. See-all identities render the
 * ADMIN persona (quick links to everything); otherwise the highest-priority held
 * role decides the layout.
 */
export function personaFor(actor: string, roles: string[]): Persona {
  if (SEE_ALL_ACTORS.has(actor)) return "ADMIN";
  const has = (r: string) => (roles || []).includes(r);
  if (roles?.some((r) => SEE_ALL_ROLES.has(r))) return "ADMIN";
  if (has("CREDIT_OFFICER") || has("CREDIT_COMMITTEE") || has("CREDIT_OPS") || has("LEGAL")) return "CREDIT";
  if (has("CAD_OPS") || has("LOAN_OPS") || has("TREASURY_OPS") || has("LIE")) return "CAD";
  if (has("ANALYST")) return "RISK";
  if (has("RM") || has("RM_HEAD")) return "RELATIONSHIP";
  // Compliance is oversight, not coverage — bucket it with the portfolio/monitoring persona.
  if (has("PORTFOLIO") || has("COLLECTIONS_HEAD") || has("COLLECTIONS_OPS") || has("COMPLIANCE")) return "PORTFOLIO";
  return "ADMIN";
}

/** Human-friendly label for a persona (dashboard heading). */
export function personaLabel(p: Persona): string {
  switch (p) {
    case "RELATIONSHIP": return "Relationship workspace";
    case "CREDIT": return "Credit workspace";
    case "RISK": return "Risk & analysis workspace";
    case "CAD": return "Administration & operations workspace";
    case "PORTFOLIO": return "Portfolio workspace";
    default: return "Workspace";
  }
}
