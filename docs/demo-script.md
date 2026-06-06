# Helix — Live Pitch Demo Script (5 min 30 s)

A timed, screen-by-screen narration that maps to **`docs/helix-pitch-demo.html`**
(the auto-advancing viewer in this repo). Each beat lists what's on screen, the
exact talking points, and how long to dwell. Total runtime: **5:30**.

> Voice direction: confident, even pace, ~150 words/minute. Pause briefly between
> beats. Phrases in **bold** are the headline ideas; emphasise them.

---

## Beat 1 — Title & thesis · 0:00 → 0:20 · (20s)

**Screen:** Title card.

> "Helix — an **AI-first wholesale loan origination and lifecycle platform**.
> Java microservices, SQLite-per-service, React front-end. **Nine services, 239
> end-to-end assertions, zero failures.** Built around three structural bets:
> a regulatory abstraction layer, governed AI strictly at the boundaries, and a
> deterministic figure path that AI provably never touches."

---

## Beat 2 — Dashboard · 0:20 → 0:40 · (20s)

**Screen:** Portfolio Dashboard.

> "Walking in cold — portfolio dashboard. **Composition, RAROC variance, ECL by
> stage, watchlist** — all computed from live exposure data. Notice the sidebar:
> the full credit lifecycle is one product, not seven."

---

## Beat 3 — Deals pipeline · 0:40 → 0:55 · (15s)

**Screen:** Deals list.

> "Deals pipeline — every application visible by stage, status, segment.
> Let's open a live one — Meridian Steel, ₹95 crore term loan, approved."

---

## Beat 4 — Deal workspace · 0:55 → 1:15 · (20s)

**Screen:** Deal Workspace.

> "One deal, one workspace. **Intake → documents → spread → confirmed → rated
> → capital → priced → decided** — each step is an audited human action with a
> named accountable user. The badge in the top right is the AI autonomy marker —
> this is AI-executed, **human-gated**."

---

## Beat 5 — Financial spreading grid · 1:15 → 1:45 · (30s)

**Screen:** Financial Spreading.

> "Financial spreading. **SpreadJS-style grid** — line items down, periods across.
> Each cell shows its **confidence** as a green dot, and hovering reveals **source
> document and page**. Editing a cell beyond the material threshold triggers the
> server-enforced **override-with-reason gate** — the deal flips back to DRAFT;
> material changes can't sneak through. Derived rows in grey are read-only.
> Ratios and benchmark flags compute per period."

---

## Beat 6 — Doc Intelligence · 1:45 → 2:05 · (20s)

**Screen:** Doc Intelligence.

> "GenAI document intelligence at the boundary. **Multilingual extraction** —
> English, Arabic, Hindi, French. Each extracted field carries its own confidence;
> low-confidence items are routed to a human reviewer. **Suggest → human-confirm
> gate.** Confirmed extractions record review accountability — they are never
> auto-applied to the figures."

---

## Beat 7 — Risk Lab · 2:05 → 2:35 · (30s)

**Screen:** Risk Lab.

> "Risk Lab — the advisory overlays. **Statistical RAG scoring** — Green, 86.2
> out of 100 — with the per-factor contribution breakdown, so a credit officer
> sees exactly why a name is flagged. The **macro overlay** projects PD direction
> from rate, GDP, FX, sector and commodity shocks — there, the soft-landing
> scenario moves PD down by 1 basis point with 0.6 notch of upgrade headroom.
> Both are advisory. The authoritative AA rating, **provably unchanged**."

---

## Beat 8 — Pricing Lab + concession workflow · 2:35 → 3:15 · (40s)

**Screen:** Pricing Lab → scroll to concessions.

> "Pricing Lab. The authoritative pricing — 8.71% rate, 15% RAROC, exactly at
> hurdle. The **goal-seek optimiser** finds the rate, fee, or collateral mix that
> hits any target RAROC, subject to caps you set.
>
> Below, the **concession-approval sub-workflow**. An RM proposes a rate below
> the recommended rate. The concession is **routed to an authority tier sized to
> its magnitude and any hurdle breach** — relationship head, credit officer,
> credit head, or credit committee. Maker-checker, segregation of duties, one or
> two levels. The deep 522-bps concession here required L1 + L2 sign-off.
> Through all of this, the authoritative pricing **does not move**."

---

## Beat 9 — Deal Structuring · 3:15 → 3:40 · (25s)

**Screen:** Deal Structuring.

> "Specialised CP variants. **Group, joint-obligor, dual-obligor for Islamic,
> syndication, FI ICR.** Live validation per variant — this syndication needs at
> least two lenders, commitments tying to the total, our 25% share visible.
> Renewal? **Copy-from** clones the structure and participants into a fresh
> proposal — no re-keying."

---

## Beat 10 — Document generation · 3:40 → 4:00 · (20s)

**Screen:** Doc Generation.

> "Document generation from the master library. Templates and T&C clauses live
> in the **master-data engine** with maker-checker. The doc is **grounded** in
> the live deal — borrower, amount, rate, tenor all pulled from the source of
> record. **Clause add, remove, edit**. Human-confirm gate locks the document."

---

## Beat 11 — AI commentary · 4:00 → 4:20 · (20s)

**Screen:** AI Commentary.

> "AI narrative commentary for the credit proposal — industry, management,
> financials, structure, risk. Every paragraph is **grounded in real ratios and
> the live structure** — DSCR 2.3, leverage 4.5, AA grade — with source
> provenance the reviewer can drill into. Draft, edit, approve or reject —
> human-gated, advisory."

---

## Beat 12 — CAD + MER monitoring · 4:20 → 4:45 · (25s)

**Screen:** CAD then Monitoring · MER.

> "Post-sanction. **Credit Administration** — checklist driven by the master,
> two-level deviation approvals with SoD, the limit-release trigger that feeds
> limit management. Then **Monitoring of Exceptions and Renewals** — deferred
> documents, conditions subsequent, insurance and annual review renewals — with
> reminders, escalation sweep, and a DMS feed event on every submission."

---

## Beat 13 — Limits + EOD batch · 4:45 → 5:05 · (20s)

**Screen:** Limits → EOD panel.

> "Multi-level limit tree, fungibility pools, product-processor APIs for
> Utilise / Release / Reserve / Reversal. The **EOD batch** marks limits to
> today's FX and reconciles the ledger leaf-by-leaf and parent-by-children —
> any variance gets surfaced for the ops desk."

---

## Beat 14 — Downstream exports + audit close · 5:05 → 5:30 · (25s)

**Screen:** Downstream Exports → Audit Trail.

> "And canonical outbound feeds — **ERM, Finance / GL, CPR** — the symmetric
> counterpart to connector ingestion. Idempotent batches, full envelope,
> examiner-retrievable.
>
> Underneath it all, the **audit trail** — every action stamped HUMAN, AI or
> SYSTEM with a named user. **239 assertions through the gateway** prove the
> safety invariants. Helix — governed AI, deterministic figures, end-to-end
> wholesale credit."

---

## Cheat sheet

| Beat | Screen | Time |
|---|---|---|
| 1 | Title | 0:00 |
| 2 | Dashboard | 0:20 |
| 3 | Deals | 0:40 |
| 4 | Workspace | 0:55 |
| 5 | Spreading | 1:15 |
| 6 | Doc Intelligence | 1:45 |
| 7 | Risk Lab | 2:05 |
| 8 | Pricing Lab + concessions | 2:35 |
| 9 | Structuring | 3:15 |
| 10 | Doc Generation | 3:40 |
| 11 | AI Commentary | 4:00 |
| 12 | CAD + MER | 4:20 |
| 13 | Limits + EOD | 4:45 |
| 14 | Exports + Audit | 5:05 → 5:30 |
