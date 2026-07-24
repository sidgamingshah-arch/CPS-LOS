/**
 * authz.ts — client-side AUTHORITY HINTS for action buttons.
 *
 * PURELY ADVISORY UI polish. It greys out an action the acting identity plainly
 * lacks the authority for and explains why, so a user doesn't chase a server-side
 * 403. The server RBAC / segregation-of-duties gates (HTTP 403) remain the ONLY
 * source of truth — this never GRANTS access, it only mirrors a subset of the
 * server's refusals in the chrome.
 *
 * DEFAULT-PERMISSIVE, exactly like role-scope.ts `seesAll`: an empty role set, a
 * see-all identity, an unmapped/unknown role, or an unmapped action is ALWAYS
 * allowed. Gating only ever NARROWS for a role we positively recognise as ranking
 * below the requirement — so nothing that works today can break.
 */

/** Mirrors risk-service RiskService.AUTHORITY_RANK. Higher = more authority. */
export const AUTHORITY_RANK: Record<string, number> = {
  ANALYST: 1,
  CREDIT_OFFICER: 2,
  CREDIT_COMMITTEE: 3,
  CRO: 3,
  BOARD_COMMITTEE: 4,
};

/** See-all roles/actors — always allowed (mirrors role-scope.ts SEE_ALL_*). */
const SEE_ALL_ROLES = new Set<string>(["CRO", "BOARD_COMMITTEE", "ADMIN", "SUPER"]);
const SEE_ALL_ACTORS = new Set<string>(["demo.user"]);

/**
 * Minimum authority ROLE each governed rating action needs, mirroring the
 * server's confirm/override gates in risk-service. Confirm is floored at
 * CREDIT_OFFICER (the legacy baseline gate); override starts at ANALYST (the
 * lowest notch-limited override role).
 */
export const ACTION_MIN_ROLE: Record<string, string> = {
  "rating.confirm": "CREDIT_OFFICER",
  "rating.override": "ANALYST",
};

const up = (s: string) => s.toUpperCase();

/**
 * True iff the acting identity may perform an action requiring `minRole` (or a
 * higher rank). Default-permissive — see the module header.
 */
export function canRole(
  minRole: string | undefined,
  roles: string[] | undefined,
  actor?: string,
): boolean {
  if (actor && SEE_ALL_ACTORS.has(actor)) return true;
  const list = (roles ?? []).map(up);
  if (list.length === 0) return true;                       // no roles → permissive
  if (list.some((r) => SEE_ALL_ROLES.has(r))) return true;  // see-all role → permissive
  const required = minRole ? (AUTHORITY_RANK[up(minRole)] ?? 0) : 0;
  if (required === 0) return true;                          // requirement not rank-mapped → permissive
  // Any role we do NOT positively recognise as ranked → permissive: never narrow
  // for an unknown role (mirrors role-scope's "roles present but all unmapped → see-all").
  if (list.some((r) => !(r in AUTHORITY_RANK))) return true;
  const best = Math.max(...list.map((r) => AUTHORITY_RANK[r] ?? 0));
  return best >= required;
}

/** Action-keyed variant of {@link canRole}. Unknown actions are permissive. */
export function can(
  action: string,
  roles: string[] | undefined,
  actor?: string,
): boolean {
  return canRole(ACTION_MIN_ROLE[action], roles, actor);
}

/** Human-friendly required-role label for a disabled-button tooltip. */
export function authorityLabel(minRole: string | undefined): string {
  return (minRole ?? "").replace(/_/g, " ");
}

/**
 * Client-side mirror of the maker≠checker (SoD) 403 that counterparty-service
 * enforces on CDD sign-off and obligor approval: the acting human must differ
 * from the record's creator. Default-permissive — an unknown creator is allowed
 * (the server fails closed on that; the UI just never blocks on missing data).
 */
export function differsFromCreator(actor: string, creator?: string | null): boolean {
  if (!creator) return true;
  return actor !== creator;
}
