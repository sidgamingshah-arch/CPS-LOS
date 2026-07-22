# Helix — Full Demo Walkthrough (login → 10 capabilities → close)

A concrete, step-by-step presenter script for a **live** client demo: exact login, personas,
the five seeded showcase obligors, the documents to upload with the **field values that will
extract**, and the nav path + talking point for each of the ten showcase capabilities.

> **One-line story, repeat often:** *AI where it helps, humans where regulation demands,
> deterministic figures throughout.* Every AI output on screen is advisory and sits next to an
> authoritative figure the AI never moved.

---

## 0. One-time setup (before the client arrives)

```bash
mvn -DskipTests package
bash scripts/run-all.sh                      # health-gated on :8080–8088
python3 scripts/seed_demo_data.py            # India book + 78 advanced artifacts (~2 min)
cd frontend && npm install && npm run dev    # UI on http://localhost:5173
```

The seeder prints the five showcase obligors at the end — **confirm they match the list below**
(names are deterministic from a fixed seed). Open **http://localhost:5173**.

---

## 1. Login & personas

- **URL:** http://localhost:5173
- **Username:** `demo.user`  **Password:** `Helix@2026`
- `demo.user` is **see-all** — use it for a smooth single-persona run.
- The **top-right actor picker** switches persona (same password). Persona = RBAC role, and this
  is how you *show* segregation-of-duties (a second, different human must approve).

| Persona | Role in the demo |
|---|---|
| `demo.user` | Sees every screen (use for the main run) |
| `rm.user` | Relationship manager — onboarding, deal creation, auto-fetch |
| `analyst.user` | Credit analyst — spreading, doc extraction, risk |
| `credit.officer` | Approver (officer tier) |
| `credit.committee` | Committee voter (higher tier) |
| `cro` | Chief Risk Officer (highest DoA) |
| `portfolio.manager` | Monitoring, exceptions, portfolio |
| `cad.maker` / `loan.checker` | CAD/ops maker-checker pair (SoD) |
| `compliance.officer` | Screening dispositions, COI |

---

## 2. The five showcase obligors (write these down)

Every advanced screen is pre-populated on **these exact five booked obligors**. **Showcase-1 is
the "golden deal"** — it also carries the full monitoring tail (limits, CP drawdown, CAD, MER).

| # | Obligor | Segment | Pre-seeded so these screens open populated |
|---|---|---|---|
| **1 (golden)** | **Nair Auto Components 093 Ltd** | LARGE_CORPORATE | Banking ASR, Risk Note, Annexure, Monitoring Artifact, Noting, Credit Proposal, Commentary, RAG/macro/scoring (XAI), + limits/CP/drawdown/CAD/MER tail |
| 2 | **Verma Trading House 094 Pvt Ltd** | TRADE_FINANCE | same per-obligor advanced artifacts |
| 3 | **Jaipur Packaging 095 Pvt Ltd** | MID_CORPORATE | same |
| 4 | **Vadodara Foods 096 Pvt Ltd** | SME | same |
| 5 | **Gupta Foods 097 Pvt Ltd** | MID_CORPORATE | same |

Singletons (seeded once, on Showcase-1 unless noted): **Escrow**, **SCF program**, **IP Note**,
**MOE Perfection**, **COI**, one **Exception/tickler**, one **Borrower Group** (→ Global Cash-flow).
There are also **116 counterparties** across the full PROSPECT→…→BOOKED funnel, with booked
exposures across STANDARD / SMA / NPA — every figure engine-computed.

---

## 3. The ten-capability run

Each step: **persona · nav path · what to click · what the client sees · the governance line.**

### 1) Customer Onboarding & Auto Data Fetch — `rm.user`
- **Path:** Originate → **Counterparties**.
- Pick **Nair Auto Components 093 Ltd** (or click **+ New** to onboard live — sample values below).
- Show: CDD tier, KYC verify (human gate), **Run screening** → hits table with named-human
  disposition/escalate, **UBO** ownership graph.
- **Auto data fetch panel** (in the detail): **Pull bureau report** and **Pull CRM profile** →
  each returns an advisory record with a provenance chip (source · vendor · retrieved-at) and an
  **ADVISORY INPUT** badge + **FIGURES OF RECORD · UNCHANGED**.
- **Pull borrower from CRM** (list toolbar) → creates a **governed PROSPECT** (a human promotes it
  to obligor; auto-fetch never auto-approves).
- **Line:** "Onboarding pulls bureau/CRM/core-banking automatically, but every pull is advisory
  input — a named human still owns the decision."

*Optional live create-new values:* Legal name `Deccan Cements Pvt Ltd`, legal form `PRIVATE_LTD`,
jurisdiction `IN-RBI`, segment `MID_CORPORATE`, sector `MANUFACTURING`, country `IN`, PEP `false`,
adverse-media `false`.

### 2) Configurable Workflow & Delegation — `demo.user` / `rm.user`
- **Config:** Configure & Govern → **Approval Rules** — the visual first-match-wins scoring-approval
  matrix; drag a rule, use the **Simulate routing** panel (e.g. exposure 12,000,000,000 → routes to
  CREDIT_COMMITTEE), then **Save** (maker-checker: a different actor approves).
- **Delegation:** Limits & Portfolio → **Casework** — **My tasks** / **My team** toggle, a **queue**
  with **Claim**, and **Reassign** (mandatory reason) / **Send-back** (rework) / **Complete**; the
  **timeline** shows round-robin auto-assign + OOO-delegate routing.
- **Lifecycle:** Assess & Decide → **Workflow Tracker** — stage chips tagged HUMAN-GATED / AI-autonomy
  / SYSTEM with SLA + breach.
- **Line:** "The workflow is configured, not coded — parameters route approvals; work delegates by
  round-robin and out-of-office with full SoD."

### 3) Intelligent Document Capture (OCR + NLP) — `analyst.user` ★ real OCR
- **Path:** Originate → **Doc Intelligence**. Select **Nair Auto Components 093 Ltd**.
- Click **Choose file → Upload & extract** and upload the **financial-statement** document from the
  Appendix (`AuditedFinancials_FY2025.txt` or `.pdf`).
- Show: the **extraction-method badge** (**PDFBox · real text** for a PDF, **Text** for .txt), the
  **extracted-text preview** (the app really parsed your file), then the **content-derived fields**
  each with confidence + a line citation:

  | Field | Value extracted from the document |
  |---|---|
  | reporting_period | FY2025 |
  | revenue | 1,250,000,000 |
  | ebitda | 312,000,000 |
  | total_debt | 540,000,000 |
  | auditor | Sterling Audit LLP |

- Click **Confirm** (named-human gate). Point out **GovFlow: AI EXTRACTS → HUMAN CONFIRMS**.
- **Line:** "This reads your actual PDF — the numbers trace to lines in the file, not a template —
  and it's still a suggestion a human confirms; it never writes a figure by itself."
- **If asked about scanned images:** text PDFs extract here live; scanned images use a config-gated
  OCR provider (`helix.ocr.provider=tesseract` or `=http`) wired at deployment — no code change.

### 4) Integration Hub (API & Event Layer) — `demo.user`
- **Path:** Configure & Govern → **Integration Hub**.
- Show the summary tiles (Inbound events, Active connectors, Outbound batches, Records exported),
  the **inbound connector cards** (Credit Bureau / CRM / Screening = green, Core Banking = amber)
  with mode + last-activity, the **outbound feeds** (ERM / Finance-GL / CPR / CRILC — **Generate**
  live), and the **event stream** (append-only audit).
- **Line (honest):** "Inbound uses one canonical ingestion contract, outbound the symmetric export
  contract, and the event layer is the immutable audit stream — governed, not a black-box bus."

### 5) AI-Powered Financial Spreading — `analyst.user`
- **Path:** Originate → **Financial Spreading**. Select **Nair Auto Components 093 Ltd**.
- Show the multi-period grid: per-cell **provenance dots** + source-page tooltips, computed
  (grey) rows, **Ratios**, **Trends**, currency toggle, CONFIRMED/DRAFT.
- Click **Populate grid from confirmed extraction** (uses the doc you confirmed in step 3) → lands a
  **DRAFT** spread (AI EXTRACTS → ANALYST CONFIRMS); edit a cell → **override needs a reason** and
  flips back to DRAFT (governed).
- **Line:** "AI extracts, the grid is human-confirmed, and every override is reasoned — the
  authoritative figure only moves on a human confirm."

### 6) Security, Compliance & Audit — `demo.user` then a 2nd persona
- **Audit:** Configure & Govern → **Audit Trail** — filter actor-type **HUMAN / AI / SYSTEM**;
  open a subject trail. Every row names an actor.
- **AI off-switch:** Configure & Govern → **AI Governance** — as `rm.user` propose disabling a
  capability (PENDING), switch to `credit.officer` and **approve** — a **different human** must
  sign off (self-approve → 403). The capability's screen then disappears from nav.
- **Line:** "Immutable audit, field-level encryption at rest, and maker-checker everywhere — AI is
  governed and can be switched off per jurisdiction."

### 7) GenAI Borrower Summarization — `analyst.user` / `demo.user`
- **Path:** Assess & Decide → **AI Commentary** (grounded narrative with a **Sources** citation
  list) and **Credit Proposal** (generate a CAM, cited); Overview → **Copilot** ask a question
  ("summarise Nair Auto Components' credit profile").
- **Line:** "GenAI drafts the borrower narrative grounded in the deal's own figures, with citations
  — advisory, human-reviewed, and the quoted numbers are verbatim."

### 8) Explainable AI (XAI) Layer — `analyst.user`
- **Path:** Assess & Decide → **Risk Lab**. Select **Nair Auto Components 093 Ltd**.
- Show the unified **"Why this output"** cards: RAG **factor / weight / contribution** table, the
  **Macro** what-if (Stagflation / Soft-landing → stressed PD + notch), and the **scoring-model**
  section weights — all on the left as **AI · ADVISORY**, beside the **AUTHORITATIVE · UNCHANGED**
  grade on the right (GovSplit).
- **Line:** "Every AI score is explainable — factors, weights, confidence, citations, and a what-if
  — and it sits next to the deterministic grade it never changed."

### 9) Approver Decision Cockpit (Visual Deal View) — `credit.officer`
- **Path:** Overview → **My Workspace** → **Awaiting my decision** → *Open decision cockpit* (or
  Assess & Decide → **Decision Cockpit** and pick a **PENDING** showcase deal).
- One read-first screen: deal hero, **rating**, **RAROC pricing** (below-hurdle flag), **covenants**,
  **AI credit summary**, **relationship exposure**, and a sticky **Approve / Approve-with-conditions
  / Refer / Reject** bar.
- Decide as `credit.officer`; to show SoD, try to approve a committee-tier deal at the wrong
  authority → **403** (surfaced as a clear message). For a committee deal, cross to **Committee
  Room** for quorum voting + the AI-drafted sanction letter.
- **Line:** "The approver sees everything on one screen and decides at the right delegated authority
  — the cockpit shows figures, it never changes them."

### 10) Demo-Ready Data & Test Cases — `demo.user` / `portfolio.manager`
- **Path:** Overview → **Portfolio Dashboard**, then Limits & Portfolio → **MIS · Reports** and
  **Customer-360** (pick a showcase obligor).
- Show the populated book: grade distribution, STANDARD/SMA/NPA buckets, RAROC variance, ECL by
  stage — all engine-computed across 116 obligors.
- **Line:** "This isn't a hollow shell — it's a realistic seeded book with a full pipeline and every
  advanced module populated."

---

## 4. Golden-deal 5-minute happy path (single narrative on Showcase-1)

**Nair Auto Components 093 Ltd**, one story end-to-end:
Counterparties (onboard + **auto-fetch bureau/CRM**) → Doc Intelligence (**upload the audited FS,
real extraction**) → Financial Spreading (**populate from extraction → confirm**) → Risk Lab
(**rating with XAI**) → AI Commentary / Credit Proposal (**GenAI summary**) → **Decision Cockpit**
(approve at DoA) → Limits + Disbursement (CP gate + drawdown) → Monitoring / MER (the tail).

---

## 5. Appendix — documents to prepare (with the field values they extract)

Save each as a **`.txt`** (extracts as `TEXT`) or a **text `.pdf`** (extracts via **PDFBox**). Keep
the labels as written — the content parser keys on them. Western digit grouping (`1,250,000,000`),
not lakh/crore, so the numbers read as intended. When uploading, you may leave *declared type* blank
(the classifier types it from the content) or set it explicitly.

### A. Financial statement → `AuditedFinancials_FY2025.txt`  *(declared type: FINANCIAL_STATEMENT)*
```
Nair Auto Components Ltd — Audited Financial Statements
Reporting Period: FY2025
Revenue from operations: INR 1,250,000,000
EBITDA: INR 312,000,000
Total Debt: INR 540,000,000
Statutory Auditor: Sterling Audit LLP
```
**Extracts:** reporting_period `FY2025` · revenue `1,250,000,000` · ebitda `312,000,000` ·
total_debt `540,000,000` · auditor `Sterling Audit LLP`.

### B. GST return → `GST_Return_FY2025.txt`  *(declared type: TAX_GST)*
```
Goods and Services Tax — Annual Return
GSTIN: 27ABCDE1234F1Z5
Aggregate turnover: INR 1,875,000,000
```
**Extracts:** gstin `27ABCDE1234F1Z5` · annual_turnover `1,875,000,000`.

### C. Bank statement → `BankStatement_Q1.txt`  *(declared type: BANK_STATEMENT)*
```
Current Account Statement
Account No: 000123456789
Average balance: INR 42,500,000
```
**Extracts:** account_number `000123456789` · average_balance `42,500,000`.

### D. Incorporation / MOA → `MOA_NairAuto.txt`  *(declared type: KYC_ID or MOA_AOA)*
```
Memorandum of Association
Name of the company: Nair Auto Components Ltd
CIN: U34100MH2015PLC123456
```
**Extracts:** legal_name `Nair Auto Components Ltd` · cin `U34100MH2015PLC123456`.

### E. Facility agreement → `FacilityAgreement.txt`  *(declared type: FACILITY_DOC)*
```
Facility Agreement
Name of borrower: Nair Auto Components Ltd
Facility amount: INR 3,000,000,000
```
**Extracts:** borrower `Nair Auto Components Ltd` · facility_amount `3,000,000,000`.

> **To prove "it reads my real PDF":** open document **A** in any editor, print/export it to PDF
> (a *text* PDF, not a scan), and upload that — the badge shows **PDFBox** and the same field values
> appear, traced to the document. A scanned image needs `helix.ocr.provider` set at deploy.

---

## 6. Tips & gotchas
- Run the whole demo as **`demo.user`**; switch persona only to *show* SoD (steps 6 and 9).
- **Decision Cockpit** and **Financial Spreading** need a deal selected — pick a showcase obligor.
- If a screen looks empty, the seed didn't run — re-run `python3 scripts/seed_demo_data.py` and
  confirm it prints the five showcase names.
- Doc capture: **text PDFs / .txt extract live**; scanned images require the config-gated OCR
  provider — say so plainly if asked.
- Governance chips (AI · ADVISORY / HUMAN-GATED / DETERMINISTIC / RBAC) sit top-right on every
  screen — point at them once and let them reinforce the story.
