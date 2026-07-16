/**
 * Config-driven dynamic screen behaviour (FIELD_POLICY) — the UI convenience layer.
 *
 * A business form fetches its field specs once from the FIELD_POLICY master (via the
 * helix-common `/api/field-policy/{formKey}` endpoint) and applies, per field:
 *   - label / help text overrides,
 *   - conditional visibility (visibleWhen) / static hide (hidden),
 *   - conditional-required (requiredWhen) / static required.
 *
 * The SAME condition ops the server evaluates are evaluated here client-side against the
 * current form values (PRESENT | BLANK | EQ | NE | IN), so the screen stays in lock-step
 * with server enforcement. But the SERVER is authoritative — `FieldPolicyService.enforce`
 * is the gate; hiding a field here never bypasses it. When the policy is empty (no master,
 * config-service down, or the form has no policy) the form behaves exactly as it does today.
 */
import { useEffect, useState } from "react";
import { fieldPolicy } from "./api";

export type FieldCondition = { field: string; op: string; value?: any; values?: any[] };

export type FieldSpec = {
  field: string;
  label?: string;
  help?: string;
  hidden?: boolean;
  required?: boolean;
  requiredSeverity?: string;
  visibleWhen?: FieldCondition;
  requiredWhen?: FieldCondition;
};

export type FieldState = { hidden: boolean; required: boolean; label?: string; help?: string };

const cache = new Map<string, FieldSpec[]>();
const inflight = new Map<string, Promise<FieldSpec[]>>();

async function fetchPolicy(formKey: string): Promise<FieldSpec[]> {
  const cached = cache.get(formKey);
  if (cached) return cached;
  const existing = inflight.get(formKey);
  if (existing) return existing;
  const p = fieldPolicy.get(formKey)
    .then((res) => {
      const specs = (res?.fields || []) as FieldSpec[];
      cache.set(formKey, specs);
      return specs;
    })
    .catch(() => {
      cache.set(formKey, []);   // negative cache; a re-seed + reload refreshes it
      return [];
    })
    .finally(() => inflight.delete(formKey));
  inflight.set(formKey, p);
  return p;
}

/** React hook: a form's field specs (empty until first load / on any failure). */
export function useFieldPolicy(formKey: string): FieldSpec[] {
  const [specs, setSpecs] = useState<FieldSpec[]>(() => cache.get(formKey) || []);
  useEffect(() => {
    let alive = true;
    fetchPolicy(formKey).then((s) => { if (alive) setSpecs(s); });
    return () => { alive = false; };
  }, [formKey]);
  return specs;
}

/** Imperative cache reset — call after an admin approves a new FIELD_POLICY revision. */
export function invalidateFieldPolicy(formKey?: string) {
  if (formKey) cache.delete(formKey);
  else cache.clear();
}

function isBlank(v: any): boolean {
  return v === undefined || v === null || String(v).trim() === "";
}

/** Evaluate one visibleWhen / requiredWhen condition against the current form values. */
function holds(cond: FieldCondition | undefined, values: Record<string, any>): boolean {
  if (!cond) return false;
  const raw = values[cond.field];
  const present = !isBlank(raw);
  const value = raw == null ? "" : String(raw).trim();
  switch ((cond.op || "").toUpperCase()) {
    case "PRESENT": return present;
    case "BLANK": return !present;
    case "EQ": return value === String(cond.value ?? "");
    case "NE": return value !== String(cond.value ?? "");
    case "IN": return Array.isArray(cond.values) && cond.values.some((v) => value === String(v));
    default: return false;   // unknown op → not-holding (fail-open, never hides/requires)
  }
}

/**
 * Per-field {hidden, required, label?, help?} for the current values. A field is hidden when
 * statically `hidden:true` OR its `visibleWhen` condition does not hold; it is required when
 * statically `required:true` OR its `requiredWhen` condition holds. Absent visibleWhen ⇒ always
 * visible; absent requiredWhen ⇒ never conditionally-required.
 */
export function evalPolicy(specs: FieldSpec[], values: Record<string, any>): Record<string, FieldState> {
  const out: Record<string, FieldState> = {};
  for (const s of specs) {
    if (!s.field) continue;
    const hidden = s.hidden === true || (s.visibleWhen ? !holds(s.visibleWhen, values) : false);
    const required = s.required === true || (s.requiredWhen ? holds(s.requiredWhen, values) : false);
    out[s.field] = { hidden, required, label: s.label, help: s.help };
  }
  return out;
}
