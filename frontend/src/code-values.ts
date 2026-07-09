/**
 * useCodes(domain) — the single source of truth for every UI dropdown.
 *
 * Reads from the CODE_VALUE master via /api/code-values/{domain}, caches one
 * fetch per domain, and returns ordered { code, label } options ready to feed a
 * <select>. Falls back to an EMPTY array when the lookup fails (e.g. backend
 * down on a dev box) — the UI degrades gracefully rather than crashing.
 *
 * The intent: replace every hardcoded TS enum array in pages/ with a single
 * line — `const grades = useCodes("GRADE_SCALE")` — so an admin can re-order /
 * rename / extend options under maker-checker, not via a code change.
 */
import { useEffect, useState } from "react";
import { codeValues, CodeValue, CodeValueSet } from "./api";

const cache = new Map<string, CodeValue[]>();
const inflight = new Map<string, Promise<CodeValue[]>>();

async function fetchDomain(domain: string): Promise<CodeValue[]> {
  const cached = cache.get(domain);
  if (cached) return cached;
  const existing = inflight.get(domain);
  if (existing) return existing;
  const p = codeValues.get(domain)
    .then((set) => {
      const values = set.values || [];
      cache.set(domain, values);
      return values;
    })
    .catch(() => {
      cache.set(domain, []);   // negative cache; an admin re-seed + invalidate refreshes
      return [];
    })
    .finally(() => inflight.delete(domain));
  inflight.set(domain, p);
  return p;
}

/** React hook: returns the ordered values for a domain (empty until first load). */
export function useCodes(domain: string): CodeValue[] {
  const [values, setValues] = useState<CodeValue[]>(() => cache.get(domain) || []);
  useEffect(() => {
    let alive = true;
    fetchDomain(domain).then((v) => { if (alive) setValues(v); });
    return () => { alive = false; };
  }, [domain]);
  return values;
}

/** Convenience: the option-code list only (for parity with the old TS enum constants). */
export function useCodeList(domain: string): string[] {
  return useCodes(domain).map((v) => v.code);
}

/** Imperative cache reset — call after an admin approves a new CODE_VALUE revision. */
export function invalidateCodes(domain?: string) {
  if (domain) cache.delete(domain);
  else cache.clear();
}

/** Boot-time prefetch — preload all domains so dropdowns paint on first render. */
export async function prefetchAllCodes(): Promise<void> {
  try {
    const sets: CodeValueSet[] = await codeValues.all();
    for (const s of sets) cache.set(s.domain, s.values || []);
  } catch {
    // best-effort — per-domain useCodes still fetches on demand
  }
}
