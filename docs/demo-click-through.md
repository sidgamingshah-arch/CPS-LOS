# Helix — Curated Demo Click-Through (Presenter's Script)

This is the read-along script for a **live** client walkthrough of Helix (governed AI for
wholesale credit). It assumes the demo book has been seeded, so every screen — including the
~14 advanced ones — opens on **populated** data instead of an empty state.

> **The one-line story to keep repeating:** *AI where it helps, humans where regulation
> demands, deterministic figures throughout.* Every AI output on screen is advisory and sits
> next to an authoritative figure the AI never moved.

---

## 0. Before you present (one-time setup)

```bash
mvn -DskipTests package
bash scripts/run-all.sh                 # health-gated on :8080–8088
python3 scripts/seed_demo_data.py       # seeds the India book + advanced artifacts (~2 min)
cd frontend && npm install && npm run dev   # UI on http://localhost:5173
```

The seeder is **idempotent** — re-running skips existing obligors and prints a no-op notice for
the advanced phase. At the end of its run it prints, e.g.:

```
 seeding advanced demo artifacts on 5 showcase obligor(s):
   <Obligor A>, <Obligor B>, <Obligor C>, <Obligor D>, <Obligor E>
 ...
 advanced demo artifacts  :  (showcase obligors — advanced screens now populated)
   banking_asr              5
   cam_annexure             5
   ...
```

**Write down those five names** — this script calls them **Showcase-1 … Showcase-5**, with
**Showcase-1 = the "golden deal"** that also carries the full monitoring tail (limits, CP
drawdown, CAD, MER). They are real **already-BOOKED** India obligors from the seeded book;
every advanced artifact below is layered onto *these exact refs*, never throwaway data.

### The demo book at a glance
- **116 counterparties** across the 7-stage funnel: PROSPECT → ONBOARDED → SPREADING → RATING →
  PENDING → DECISIONED → **BOOKED** (a realistic pipeline, not just a booked book).
- All six wholesale segments, India / **IN-RBI**, INR / IND_AS, procedural CIN-shaped reg. nos.
- Booked exposures spread across **STANDARD / SMA / NPA** buckets; every credit figure
  (rating PD/LGD, RWA, RAROC, ECL) is **engine-computed** — the seeder never hardcodes a figure.

### Login personas (top-right actor picker)
Log in with the persona a given screen expects. Handy ones: **rm.user** (relationship manager),
**analyst.user** (credit analyst), **credit.officer** / **credit.committee** / **cro** (approvers),
**portfolio.manager** (monitoring), **demo.user** (sees everything). Use **demo.user** if you
want a single persona that can reach every screen.

---

## The 10 client capabilities

Each capability below gives the **screen path** (left-nav *Section → Item*), **what to click**,
**what the client sees**, and **what seeded data backs it**.

---

### 1. Customer Onboarding & Auto Data Fetch

- **Screen:** *Originate → Counterparties* (then *Originate → Borrower Groups*).
- **Click:** Open the counterparties list; pick **Showcase-1**. Show the KYC status, CDD tier and
  dispositioned screening hits. Then open the **Auto Data Fetch** panel on the obligor and show
  the **credit-bureau** and **CRM** cards.
- **Client sees:** A fully onboarded obligor with KYC verified and screening hits dispositioned
  by a named human; a **CIBIL-style bureau score** and a **CRM relationship profile** that were
  *pulled* into Helix, each stamped **advisory INPUT** with source-system provenance (vendor
  marked *simulated* — no external system is called in the demo).
- **Backed by:** onboarding + screening + KYC for every obligor; **bureau + CRM pulls** seeded on
  two showcase obligors (`ingest/bureau/pull`, `ingest/crm/pull`); one **Borrower Group** with
  2–3 tagged members.

### 2. Configurable Workflow & Delegation

- **Screen:** *Overview → My Workspace* (Role Dashboard), plus *Limits & Portfolio → Workflow
  Tracker* and *TAT · MIS Reports*, and *Configure & Govern → Master Data*.
- **Click:** Log in as **rm.user** (then **analyst.user**) and open **My Workspace** → *My Tasks*.
  Then open *Master Data* and filter to **ASSIGNMENT_POOL** and **OOO_CALENDAR** to show routing
  is **config-as-data**, not code.
- **Client sees:** Each demo login lands on a populated **My Tasks** inbox — a mix of
  round-robin auto-assigned reviews and directly-assigned work items on real deals, plus
  OPEN queue tasks. The **delegation** story: a pool member is Out-of-Office (`vacation.rm`) so
  their tasks route to their **delegate (rm.user)** automatically. Round-robin, queues, OOO
  skip + delegate — all driven by editable master rows.
- **Backed by:** 3 **ASSIGNMENT_POOL** masters (2 round-robin, 1 manual queue), 1 **OOO_CALENDAR**
  delegate entry, and a handful of **WorkItems** assigned to the demo login identities
  (rm.user / analyst.user / credit.head) — never to seed bots.

### 3. Intelligent Document Capture

- **Screen:** *Originate → Doc Intelligence*.
- **Click:** Select **Showcase-1** (or -2/-3). Show the AI **extraction** with suggested fields
  and per-field **confidence**, then the **AI EXTRACTS → HUMAN CONFIRMS** flow badge.
- **Client sees:** The GenAI document-intelligence surface pre-populated with a **SUGGESTED**
  extraction from an uploaded financial statement — advisory, confidence-scored, and **gated on a
  human confirm** before anything is trusted. It never auto-writes a figure.
- **Backed by:** a document + **doc-intel extraction** seeded on 2–3 showcase deals.

### 4. Integration Hub

- **Screen:** *Limits & Portfolio → Downstream Exports* (outbound) and the *Auto Data Fetch*
  panel from capability 1 (inbound).
- **Click:** Open **Downstream Exports** and show the batches for **ERM**, **Finance-GL**, **CPR**
  and **CRILC**. Re-run one to show it returns the **same batch** (idempotent per as-of day).
- **Client sees:** Symmetric integration — canonical **inbound** connectors (bureau/CRM) and
  canonical **outbound** feeds to risk (ERM), finance (GL provisioning), portfolio (CPR) and the
  RBI regulatory feed (CRILC), each a typed, versioned, idempotent envelope.
- **Backed by:** one export batch generated per feed after exposures + ECL are booked; bureau/CRM
  pulls from capability 1.

### 5. AI-Powered Financial Spreading

- **Screen:** *Originate → Financial Spreading* (and *Originate → Banking ASR*).
- **Click:** Open **Financial Spreading** for **Showcase-1**; show the two-period **IND_AS / INR**
  spread with **cell-level provenance** (source document / page) and the engine-computed
  **ratios and trends**. Then open **Banking ASR** to show the account-conduct review.
- **Client sees:** Financials captured with provenance and turned into deterministic ratios,
  leverage, coverage and DSCR — plus a **Banking ASR** whose conduct metrics (average balance,
  utilisation, cheque returns) are hand-computable, with an **advisory AI summary** that leaves
  every metric byte-identical and a human **CONFIRMED** sign-off.
- **Backed by:** confirmed spreads on every obligor from the SPREADING stage upward; a **Banking
  ASR** (computed → AI-summarised → confirmed) on each of the 5 showcase obligors.

### 6. Security / Compliance / Audit

- **Screen:** *Configure & Govern → Audit Trail*, *Configure & Govern → AI Governance*, and
  *Assess & Decide → Conflict of Interest*.
- **Click:** In **Audit Trail**, filter by **actorType = AI**, then **HUMAN**, then **SYSTEM**.
  In **AI Governance**, show the capability toggles. In **Conflict of Interest**, show the
  attestation on **Showcase-1**.
- **Client sees:** Every write is attributed to a named actor with an actorType. The **AI** filter
  lights up with rows from RAG scoring, macro overlays, doc-intel, commentary and the ASR summary
  — provably advisory. **HUMAN** rows show maker-checker / SoD gates (reviewer ≠ author,
  L1 ≠ L2, approver ≠ raiser). A **COI attestation** demonstrates the recuse/declare control.
- **Backed by:** append-only audit across services; the AI-capability calls in capability 7–8
  stamp `audit.ai`; a **DECLARED_MANAGED COI** attestation.

### 7. GenAI Borrower Summarization

- **Screen:** *Assess & Decide → AI Commentary* and *→ Credit Proposal*; *Limits & Portfolio →
  Global Cash-flow*; *Originate → Borrower Groups* → group decisioning.
- **Click:** Open **AI Commentary** for **Showcase-1** and show the drafted *financial commentary*
  section with the **AI drafts → human reviews/confirms** gate. Open **Credit Proposal** to show
  the generated proposal. Then open **Global Cash-flow** for the borrower group and the
  **combined group proposal**.
- **Client sees:** GenAI drafts the narrative — commentary, the credit proposal, and a
  relationship-level combined proposal — all **grounded** in the deal's real figures, all
  **advisory** and **human-gated**. The deterministic figures are quoted verbatim; the AI writes
  the prose, not the numbers.
- **Backed by:** a generated **Credit Proposal** + a drafted **Commentary** section on 2–3
  showcase deals; a **Borrower Group** with **Global Cash-flow** consolidation, **group
  insights** and a **combined proposal**.

### 8. Explainable AI

- **Screen:** *Assess & Decide → Risk Lab* (the signature screen).
- **Click:** Open **Risk Lab** for **Showcase-1**. Show the **GovSplit**: on the left the AI
  **ADVISORY** statistical **RAG band** with its factor **breakdown**; on the right the
  **AUTHORITATIVE grade** carrying the green **● UNCHANGED** tag. Run/inspect the **macro-impact**
  scenario (adverse → PD pressure, notch estimate) and the resolved **scoring model**.
- **Client sees:** The explainability centrepiece — an AI risk signal that *explains itself*
  (factor contributions, macro sensitivity, model answers) sitting **beside** the source-of-record
  grade it is provably **not allowed to move**. This is the visual proof of the governance thesis.
- **Backed by:** an **advisory RAG assessment**, a **macro-impact scenario**, and a
  **resolved + auto-answered scoring model** on 2–3 showcase deals (all advisory; the authoritative
  rating is byte-identical before/after).

### 9. Approver Decision Cockpit

- **Screen:** *Assess & Decide* group (Committee Room, Risk Notes, CAM Annexures, Notings, CAD ·
  Documentation, MOE Perfection) and *Limits & Portfolio* group (Disbursement · CPs, Monitoring ·
  MER, Monitoring Artifacts, Escrow, Exceptions).
- **Click:** Walk the approver's world:
  - *Deals* / *Committee Room* — the **DECISIONED** cohort shows a mix of APPROVE / CONDITIONAL /
    REFER / DECLINE outcomes with named-human decisions.
  - *Risk Notes*, *CAM Annexures*, *Notings* — governed records driven to **APPROVED** on each
    showcase obligor (DRAFT → SUBMITTED → REVIEWED → APPROVED, SoD at each gate).
  - *Supply-Chain Finance* — an approved anchor programme with eligible/ineligible spokes.
  - *IP Notes* — an approved in-principle note.
  - *MOE Perfection* — a security-perfection case driven to **COMPLETED** through role-gated steps.
  - *CAD · Documentation* + *Disbursement · CPs* — the golden deal's CAD case (with a 2-level
    waiver), CP register cleared, and a **drawdown released** by three distinct humans.
  - *Monitoring · MER*, *Monitoring Artifacts*, *Escrow*, *Exceptions* — a materialised MER
    register, approved call memos, an escrow budget-vs-actual RAG, and an open exception/tickler.
- **Client sees:** A complete approver cockpit where nothing dead-ends on an empty state — every
  governed artifact is mid-flight or approved, with SoD, DoA routing and maker-checker visible.
- **Backed by:** Risk Note / CAM Annexure / Monitoring Artifact / Noting driven to APPROVED on all
  5 showcase obligors; SCF, IP Note, MOE Perfection, Escrow, COI, Exception singletons; the golden
  deal's limits + CP drawdown + CAD + MER tail.

### 10. Demo-Ready Data

- **Screen:** *Overview → Portfolio Dashboard*, *Limits & Portfolio → MIS · Reports* and
  *→ Customer-360*.
- **Click:** Open the **Portfolio Dashboard** and **MIS** to show the aggregate book; open
  **Customer-360** for **Showcase-1** to show one obligor's full 360 (exposure, rating, artifacts).
- **Client sees:** A believable, fully-populated India wholesale book — 100+ obligors across every
  stage, booked exposures across STANDARD/SMA/NPA, RWA / ECL / RAROC aggregates, segment and grade
  distributions — so every downstream screen has real data behind it.
- **Backed by:** the full seeded funnel (116 counterparties) plus the advanced-artifact phase that
  makes the ~14 advanced screens open populated.

---

## Suggested 5-minute happy path

1. **Portfolio Dashboard** — "here's a real book" (cap. 10).
2. **Counterparties → Showcase-1 → Auto Data Fetch** — onboarding + bureau/CRM pull (cap. 1, 4-in).
3. **Financial Spreading** + **Banking ASR** — provenance + deterministic ratios (cap. 5).
4. **Doc Intelligence** — AI extracts → human confirms (cap. 3).
5. **Risk Lab** — the AI-ADVISORY ↔ AUTHORITATIVE-UNCHANGED split (cap. 8). *This is the money shot.*
6. **AI Commentary → Credit Proposal** — GenAI writes the prose, not the numbers (cap. 7).
7. **My Workspace** (as rm.user) → **Workflow Tracker** — tasks + delegation (cap. 2).
8. **Committee Room / CAD / Disbursement / Monitoring** — the approver cockpit (cap. 9).
9. **Downstream Exports** — ERM / Finance-GL / CPR / CRILC feeds (cap. 4-out).
10. **Audit Trail** (filter AI vs HUMAN vs SYSTEM) — the governance proof (cap. 6).

## Talking points that always land
- On **Risk Lab**: *"The AI has an opinion. It is not allowed to change the grade. Watch the
  `● UNCHANGED` tag — the e2e suite asserts this figure is byte-identical before and after the AI
  runs."*
- On any **suggest → confirm** screen: *"AI drafts, a named human confirms, and only then does it
  lock. Segregation of duties is enforced server-side — the drafter cannot confirm their own work."*
- On **Master Data / Rule Packs**: *"A new jurisdiction or a new routing rule is overlay data, not
  a code change. Everything you see routing, checking and computing is config we can edit live."*
- On **Audit Trail**: *"Every row has an actor and an actorType. Filter to AI and you can see
  exactly where the AI touched the file — and prove it never touched a figure."*
