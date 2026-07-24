# Helix — Full Demo Walkthrough (showcase run **+ live end-to-end case**)

A concrete, step-by-step presenter script for a **live** client demo. Two ways to run it:

- **A. Showcase run** (§3) — the ten capabilities on the five pre-seeded "golden" obligors. Fast,
  everything pre-populated, zero dependencies. Use this if the room is short on time.
- **B. Live end-to-end case** (§4) — onboard a **brand-new borrower** and drive the *entire*
  lifecycle live: real document upload + OCR extraction, live spreading, rating, the customer
  self-service portal, decision, sanction, limits, disbursement. This is where AI/OCR run **for
  real** on your own documents. §5 is the **document upload map** for this run.

> **One-line story, repeat often:** *AI where it helps, humans where regulation demands,
> deterministic figures throughout.* Every AI output on screen is advisory and sits next to an
> authoritative figure the AI never moved.

**What's new since the last cut of this script** (all merged): customer/vendor **self-service
portal** (token-scoped RFI response + document upload), **Syndication Information Memorandum**
workspace, **generalized auto-case-movement** (auto-lapse / auto-advance), a **quick-create** modal
on the lists, and an **XSS-safe rich-text** editor on the free-text fields.

---

## 0. One-time setup (before the client arrives)

```bash
mvn -DskipTests package
bash scripts/run-all.sh                      # health-gated on :8080–8089
python3 scripts/seed_demo_data.py            # India book + 78 advanced artifacts (~2 min)
cd frontend && npm install && npm run dev    # UI on http://localhost:5173
```

The seeder prints the five showcase obligors at the end — confirm they match §2. Open
**http://localhost:5173**.

### LLM configuration (decide before you start services)

All AI reads one externalised file: **`config/llm.yml`** (env vars override it). Switch:
`helix.llm.provider`.

- **`none` (default):** no external call; every AI screen uses its built-in deterministic grounded
  output. Fully offline, repeatable. Doc-capture/OCR, spreading, XAI, governance visuals all still
  work — none of the authoritative path depends on a model.
- **A real provider (`openai` / `anthropic` / `azure-openai`):** the GenAI *prose* (borrower
  summaries, commentary, screening rationale, copilot) is generated **live**. No code change:
  ```bash
  export HELIX_LLM_PROVIDER=anthropic     # or openai / azure-openai
  export HELIX_LLM_API_KEY=<your key>      # kept in env, never written to git
  export HELIX_LLM_MODEL=<your model/deployment name>
  export HELIX_LLM_BASE_URL=<endpoint>     # required for azure; optional otherwise
  bash scripts/run-all.sh
  ```
  Fail-soft: a slow/unreachable endpoint silently falls back within the timeout — a live model can
  never break the demo.

**For the live case (§4) you want the LLM configured** so the borrower summaries read as live prose.
The **five seeded obligors keep their stored content** (viewing = no model call); **any fresh action
on the new borrower calls the model live**. Governance is identical either way — the model only
drafts advisory, human-gated text; it never writes a figure, grade, price or decision.

---

## 1. Login & personas

- **URL:** http://localhost:5173  ·  **Username:** `demo.user`  **Password:** `Helix@2026`
- `demo.user` is **see-all** — use it for a smooth single-persona run.
- The **top-right actor picker** switches persona (same password). Persona = RBAC role — this is how
  you *show* segregation-of-duties (a second, different human must approve).

| Persona | Role in the demo |
|---|---|
| `demo.user` | Sees every screen (main run) |
| `rm.user` | Relationship manager — onboarding, deal creation, auto-fetch, RFIs |
| `analyst.user` | Credit analyst — spreading, doc extraction, risk, commentary |
| `credit.officer` | Approver (officer tier) |
| `credit.committee` | Committee voter (higher tier) |
| `cro` | Chief Risk Officer (highest DoA) |
| `portfolio.manager` | Monitoring, exceptions, portfolio |
| `cad.maker` / `loan.checker` | CAD/ops maker-checker pair (SoD) |
| `compliance.officer` | Screening dispositions, COI |

---

## 2. The five showcase obligors (for the §3 showcase run)

Every advanced screen is pre-populated on **these five booked obligors**. **Showcase-1 is the
"golden deal"** — it also carries the full monitoring tail (limits, CP drawdown, CAD, MER).

| # | Obligor | Segment |
|---|---|---|
| **1 (golden)** | **Nair Auto Components 093 Ltd** | LARGE_CORPORATE |
| 2 | **Verma Trading House 094 Pvt Ltd** | TRADE_FINANCE |
| 3 | **Jaipur Packaging 095 Pvt Ltd** | MID_CORPORATE |
| 4 | **Vadodara Foods 096 Pvt Ltd** | SME |
| 5 | **Gupta Foods 097 Pvt Ltd** | MID_CORPORATE |

Singletons (seeded on Showcase-1 unless noted): **Escrow**, **SCF program**, **IP Note**, **MOE
Perfection**, **COI**, one **Exception/tickler**, one **Borrower Group** (→ Global Cash-flow), plus
**116 counterparties** across the full PROSPECT→…→BOOKED funnel (STANDARD/SMA/NPA) — every figure
engine-computed.

---

## 3. Showcase run — the ten capabilities (seeded obligors)

Each step: **persona · nav path · click · what the client sees · the governance line.** Run as
`demo.user`; switch persona only to *show* SoD (steps 6, 9).

**1) Customer Onboarding & Auto Data Fetch — `rm.user`** — Originate → **Counterparties**. Pick
Nair Auto (or **＋ Quick create** for the fast inline modal). Show CDD tier, KYC verify (human gate),
**Run screening** → hits with named-human disposition, **UBO** graph, and the **Auto data fetch**
panel (**Pull bureau** / **Pull CRM** → advisory records with provenance chips + FIGURES-UNCHANGED).
*Line:* "Onboarding auto-pulls bureau/CRM/core-banking, but every pull is advisory — a named human
owns the decision."

**2) Configurable Workflow & Delegation — `demo.user`/`rm.user`** — Configure & Govern → **Approval
Rules** (visual first-match scoring matrix; **Simulate routing**; **Save** = maker-checker). Limits &
Portfolio → **Casework** (My tasks / My team, **Claim**, **Reassign** w/ reason, **Send-back**,
**Complete**; round-robin + OOO on the timeline). **New:** stale tasks **auto-lapse / auto-advance**
per config (auto-case-movement, SYSTEM-stamped, never bypasses a human gate). *Line:* "Workflow is
configured, not coded — parameters route approvals; work delegates by round-robin/OOO and moves
automatically on rules, all with SoD."

**3) Intelligent Document Capture (OCR + NLP) — `analyst.user` ★ real OCR** — Originate → **Doc
Intelligence**, select Nair Auto, **Choose file → Upload & extract** the financial statement (§5
doc A). Show the **extraction-method badge** (PDFBox / Text), the **extracted-text preview**, the
**content-derived fields** (each with confidence + line citation), then **Confirm** (GovFlow: AI
EXTRACTS → HUMAN CONFIRMS). *Line:* "It reads your actual PDF — numbers trace to lines in the file,
not a template — and it's still a suggestion a human confirms."

**4) Integration Hub — `demo.user`** — Configure & Govern → **Integration Hub**: summary tiles,
inbound connector cards (Bureau/CRM/Screening green, Core Banking amber), outbound feeds
(ERM/Finance-GL/CPR/CRILC — **Generate** live), append-only **event stream**. *Line:* "One canonical
ingestion contract in, the symmetric export contract out, immutable audit in the middle."

**5) AI-Powered Financial Spreading — `analyst.user`** — Originate → **Financial Spreading**, select
Nair Auto. Multi-period grid with per-cell **provenance dots**, computed rows, Ratios/Trends. Click
**Populate grid from confirmed extraction** → **DRAFT** (AI EXTRACTS → ANALYST CONFIRMS); edit a cell
→ **override needs a reason** and flips to DRAFT. *Line:* "AI extracts, the grid is human-confirmed,
every override is reasoned."

**6) Security, Compliance & Audit — `demo.user` then a 2nd persona** — Configure & Govern → **Audit
Trail** (filter HUMAN/AI/SYSTEM; every row names an actor). **AI Governance**: as `rm.user` propose
disabling a capability (PENDING) → switch to `credit.officer` and **approve** (self-approve → 403);
the capability leaves the nav. *Line:* "Immutable audit, field-level encryption at rest, maker-checker
everywhere — AI is governed and can be switched off per jurisdiction."

**7) GenAI Borrower Summarization — `analyst.user`/`demo.user`** — Assess & Decide → **AI Commentary**
(grounded narrative + **Sources** citations; edit in the new **rich-text** editor) and **Credit
Proposal** (generate a CAM by format, cited); Overview → **Copilot** ("summarise Nair Auto's credit
profile"). *Line:* "GenAI drafts the narrative grounded in the deal's own figures, with citations —
advisory, human-reviewed, quoted numbers verbatim." (Live prose when the LLM is configured.)

**8) Explainable AI (XAI) Layer — `analyst.user`** — Assess & Decide → **Risk Lab**, select Nair
Auto. Unified **"Why this output"** cards: RAG factor/weight/contribution, **Macro** what-if →
stressed PD + notch, scoring-model section weights — all **AI · ADVISORY** on the left beside the
**AUTHORITATIVE · UNCHANGED** grade (GovSplit). *Line:* "Every AI score is explainable and sits next
to the deterministic grade it never changed."

**9) Approver Decision Cockpit — `credit.officer`** — Overview → **My Workspace** → **Awaiting my
decision** → *open cockpit* (or Assess & Decide → **Decision Cockpit**, pick a PENDING deal). One
read-first screen: rating, RAROC (below-hurdle flag), covenants, AI summary, relationship exposure,
sticky **Approve / Approve-with-conditions / Refer / Reject**. Show SoD: wrong-authority approve →
**403**; a committee deal → **Committee Room** quorum + AI sanction letter. *Line:* "The approver
sees everything on one screen and decides at the right delegated authority."

**10) Demo-Ready Data & Test Cases — `demo.user`/`portfolio.manager`** — Overview → **Portfolio
Dashboard**, then Limits & Portfolio → **MIS · Reports** and **Customer-360**. Grade distribution,
STANDARD/SMA/NPA, RAROC variance, ECL by stage — engine-computed across 116 obligors. *Line:* "Not a
hollow shell — a realistic seeded book with every advanced module populated."

**Extra capability callouts (new):**
- **Customer/vendor portal** — Overview → **Customer Portal** (external-facing). Covered live in §4
  step 7.
- **Syndication IM** — Originate → **Syndication IM**. Covered in §4 (optional) step S.

---

## 4. LIVE END-TO-END CASE (new borrower, everything runs live)

**Borrower:** *Deccan Cements Pvt Ltd* — a brand-new MID_CORPORATE cement manufacturer requesting a
**₹300 cr (INR 3,000,000,000) term loan** for a kiln line-3 expansion. Because this obligor is **not
seeded**, every AI/OCR/extraction step below executes **live** (against `config/llm.yml` if a model
is configured). Have the §5 documents ready to upload.

Persona in **bold** at each step. Follow the order — later steps depend on earlier ones (the
application must exist before you can upload documents against it).

**Step 1 — Onboard the borrower. `rm.user`**
Originate → **Counterparties** → **＋ New** (or **＋ Quick create** for the fast modal). Enter:

| Field | Value |
|---|---|
| Legal name | `Deccan Cements Pvt Ltd` |
| Legal form | `PRIVATE_LTD` |
| Jurisdiction | `IN-RBI` · Segment `MID_CORPORATE` · Sector `MANUFACTURING` · Country `IN` |
| CIN | `U26940TG2016PTC110022` |
| PAN | `AABCD1234K` · GSTIN `36AABCD1234K1Z9` · LEI `3358 0099 …` (any 20-char) |
| PEP / Adverse media / High-risk juris. | `false` / `false` / `false` |

Save → **Pull bureau** and **Pull CRM** (advisory records appear with provenance). Switch to
**`compliance.officer`** → **Run screening** → set dispositions on any hits; open the **UBO** panel
and add two owners (60/40) → resolve. Back as **`rm.user`** → **Verify KYC** (human gate). Note the
counterparty ref (`CP-…`).

**Step 2 — Create the deal (application). `rm.user`**
Originate → **Applications** (or **Deals**) → **New**. Enter:

| Field | Value |
|---|---|
| Counterparty | `Deccan Cements Pvt Ltd` (pick the one you just created) |
| Facility type | `TERM_LOAN` · Amount `3,000,000,000` · Currency `INR` · Tenor `84` months |
| Purpose | `Kiln line-3 capacity expansion` |
| Collateral | type `PROPERTY` · value `2,000,000,000` · secured `true` |
| CAM format | `Standard CAM` (or pick `NBFC` / `Rental Discounting` / any of the 16 to show format-aware CAM) |

**Write down the application ref** it returns (e.g. `HLX-2026-XXXXXX`) — you select it on every
downstream screen.

**Step 3 — Upload & extract the documents (real OCR). `analyst.user`**
Originate → **Doc Intelligence** → select your **Deccan Cements application** (by the ref from step 2).
For each document in §5, click **Choose file → Upload & extract**. Do **doc A (financials) first** —
it's the money shot:
- The **extraction-method badge** shows **PDFBox · real text** (text PDF) or **Text** (.txt).
- The **extracted-text preview** proves the app parsed *your* file.
- The **content-derived fields** appear with confidence + a line citation (see §5 for exact values).
- Click **Confirm** on the financials extraction (named-human gate) — this is the one you'll pull
  into the spread.

Upload docs B–E the same way to populate KYC/GST/bank/facility fields. *Line:* "Every figure traces
to a line in your document; it's a suggestion a human confirms — the AI never writes a figure."

**Step 4 — Spread the financials. `analyst.user`**
Originate → **Financial Spreading** → select the deal → **Populate grid from confirmed extraction**
(uses the doc A extraction you confirmed) → lands a **DRAFT** spread. Fill/adjust the second period
and any missing lines so the ratios compute, then **Confirm** the spread (authoritative gate).
Overriding any cell **requires a reason** and flips back to DRAFT.

**Step 5 — Rate + explain + scoring approval. `analyst.user` → `credit.officer`**
Assess & Decide → **Risk Lab** → select the deal → **Rate** → grade / PD / LGD / EAD (deterministic).
Open the **"Why this output"** XAI cards, run a **Macro** what-if (Stagflation → stressed PD + notch),
review the scoring-model weights. If your scoring-approval policy routes this exposure, a
**RATING_APPROVAL** task is created → switch to **`credit.officer`** and **confirm the rating** (a
different human; the analyst cannot self-approve).

**Step 6 — Capital + RAROC pricing. `analyst.user`**
Risk Lab → **Capital** then **Pricing** → recommended rate / RAROC (below-hurdle flag if any) →
**Optimise** to a target RAROC; if a concession is needed, raise the **pricing exception** (L1/L2
approvers, proposer ≠ approver).

**Step 7 — Collect a document from the customer via the PORTAL (new). `rm.user` + "the customer"**
This shows the external self-service surface. Raise an RFI to the customer for a still-missing
document, then act as the customer to respond and upload it.

1. **Mint the secure link.** The RM-side "send RFI" is issued on the query lane; grab the one-time
   portal token with (through the gateway):
   ```bash
   curl -s -X POST http://localhost:8080/counterparty/api/queries \
     -H "Content-Type: application/json" -H "X-Actor: rm.user" \
     -d '{"channel":"EXTERNAL_CUSTOMER","subjectType":"Application","subjectRef":"<APP-REF>",
          "topic":"FY2026 projected financials",
          "question":"Please upload your FY2026 board-approved projections.",
          "addresseeRole":"customer.contact"}'
   ```
   The response carries `thread.responseToken` — copy it. (At deployment this link is emailed to the
   customer via the notification transport; the curl just surfaces it for the demo.)
2. **Act as the customer.** Open Overview → **Customer Portal** (or a fresh incognito tab at
   `http://localhost:5173/?token=<responseToken>`) and paste the token. The customer sees **only**
   this RFI (topic, question, timeline) — no login, no other data. Type a response and
   **Upload document** → §5 **doc F** (board resolution / projections).
3. **Back as `rm.user`**, the query timeline shows the customer's response + attachment, author
   redacted to **the customer** (internal names are never exposed the other way). *Line:* "External
   parties never log in — a token-scoped portal, one thread only, every action stamped EXTERNAL, and
   the uploaded file lands in the governed DMS."

**Step 8 — GenAI borrower summary + proposal. `analyst.user` / `demo.user`**
Assess & Decide → **AI Commentary** → **Draft** the borrower narrative (grounded in *this* deal's
confirmed figures, with a Sources list) — edit it in the **rich-text** editor → send for review
(a different human confirms). **Credit Proposal** → **Generate** the CAM in your chosen format
(cited). Overview → **Copilot** → "summarise Deccan Cements' credit profile and key risks." With the
LLM configured these are **live** generations; the quoted figures are still verbatim/deterministic.

**Step 9 — Decide. `credit.officer` (or `credit.committee` / `cro` if DoA routes higher)**
Overview → **My Workspace** → **Awaiting my decision** → open the Deccan Cements deal in the
**Decision Cockpit**. Review rating, RAROC, covenants, the AI summary, relationship exposure on one
screen → **Approve** (or **Approve-with-conditions**). To show SoD, try approving at the wrong
authority → **403**. If it routed to committee, cross to **Committee Room** for quorum voting + the
AI-drafted sanction letter.

**Step 10 — Documents, CAD & sanction. `cad.maker` → `loan.checker`**
Assess & Decide → **Doc Generation** → generate the **facility agreement** / **sanction letter**
(clause surgery + **confirm-lock**; **Print** to PDF, or download **Word/Excel**). Run the **CAD**
checklist + 2-level waiver; **Document Execution** → tag the **signatory matrix** (INTERNAL/CUSTOMER)
and track SENT → SIGNED → RECEIVED.

**Step 11 — Limits & disbursement. `rm.user` → `cad.maker`/`loan.checker`/`credit.officer`**
Limits & Portfolio → the **limit tree** is built from the deal's facility/sublimits. Raise a
**Disbursement** request → the **CP gate** + drawing-power check apply; disbursement uses **3-actor
SoD**. Draw down the first tranche.

**Step 12 — Monitoring. `portfolio.manager`**
Book the exposure → **Customer-360**, **covenant tracking**, **MER** register, **EWS** scan. Leave a
task idle to show **auto-case-movement** lapse it on the next sweep. The deal now shows across the
Portfolio Dashboard and MIS/TAT reports.

**Step S (optional) — Syndication Information Memorandum. `rm.user` → a 2nd human**
If you want to show the IM workspace: Originate → **Syndication** (create the syndicated structure +
add participants/deal team) → Originate → **Syndication IM** → select the deal → **Create IM** (7
sections auto-seeded from the deal, deterministic) → edit sections in the **rich-text** editor →
**Circulate** → **Finalise**. Finalising as the *same* person who created it → **403** (finaliser ≠
creator SoD); a different human finalises. The syndicate book/allocations are **unchanged** — the IM
is a document artifact only.

---

## 5. Document upload map — what to prepare and where each goes

You're sourcing these externally. Save each as a **`.txt`** (extracts as `TEXT`) **or a text `.pdf`**
(extracts via **PDFBox**) — **not** a scanned image (scans need the config-gated OCR provider set at
deploy). Keep the **labels exactly as written** — the content parser keys on them. Use **Western
digit grouping** (`3,000,000,000`), not lakh/crore. On upload you can leave *declared type* blank
(the classifier types it from content) or set it explicitly.

### Where each document is uploaded

| Doc | Upload at | Screen · action | Persona | Declared type | Extracts → lands as |
|---|---|---|---|---|---|
| **A. Audited financials** | **Doc Intelligence** | select the Deccan Cements app → **Choose file → Upload & extract → Confirm** | `analyst.user` | FINANCIAL_STATEMENT | revenue / ebitda / total_debt / reporting_period / auditor → confirmed extraction → **pull into Financial Spreading** |
| **B. GST return** | **Doc Intelligence** | same, upload → extract | `analyst.user` | TAX_GST | gstin / annual_turnover → hygiene/turnover cross-check |
| **C. Bank statement** | **Doc Intelligence** | same | `analyst.user` | BANK_STATEMENT | account_number / average_balance → conduct / Banking ASR |
| **D. Incorporation / MOA** | **Doc Intelligence** | same | `analyst.user` | MOA_AOA (or KYC_ID) | legal_name / cin → KYC evidence |
| **E. Facility agreement** | **Doc Intelligence** | same | `analyst.user` | FACILITY_DOC | borrower / facility_amount → docs/limits cross-check |
| **F. Customer-submitted doc** | **Customer Portal** | paste the RFI token → **Upload document** | *"the customer"* (token, no login) | — (stored, not parsed) | stored in the governed **DMS**, tagged to the RFI thread; visible to `rm.user` in the query timeline |

**All extraction uploads (A–E) happen on one screen — Originate → Doc Intelligence — against the
Deccan Cements application.** Only **F** is uploaded elsewhere: the external **Customer Portal**.

### Content templates (Deccan Cements values)

**A — `DeccanCements_AuditedFS_FY2025.txt`**  *(FINANCIAL_STATEMENT)*
```
Deccan Cements Pvt Ltd — Audited Financial Statements
Reporting Period: FY2025
Revenue from operations: INR 4,200,000,000
EBITDA: INR 780,000,000
Total Debt: INR 1,600,000,000
Statutory Auditor: Kapoor & Rao LLP
```
Extracts: reporting_period `FY2025` · revenue `4,200,000,000` · ebitda `780,000,000` ·
total_debt `1,600,000,000` · auditor `Kapoor & Rao LLP`.

**B — `DeccanCements_GST_FY2025.txt`**  *(TAX_GST)*
```
Goods and Services Tax — Annual Return
GSTIN: 36AABCD1234K1Z9
Aggregate turnover: INR 4,500,000,000
```
Extracts: gstin `36AABCD1234K1Z9` · annual_turnover `4,500,000,000`.

**C — `DeccanCements_BankStatement.txt`**  *(BANK_STATEMENT)*
```
Current Account Statement
Account No: 000987654321
Average balance: INR 68,000,000
```
Extracts: account_number `000987654321` · average_balance `68,000,000`.

**D — `DeccanCements_MOA.txt`**  *(MOA_AOA / KYC_ID)*
```
Memorandum of Association
Name of the company: Deccan Cements Pvt Ltd
CIN: U26940TG2016PTC110022
```
Extracts: legal_name `Deccan Cements Pvt Ltd` · cin `U26940TG2016PTC110022`.

**E — `DeccanCements_FacilityAgreement.txt`**  *(FACILITY_DOC)*
```
Facility Agreement
Name of borrower: Deccan Cements Pvt Ltd
Facility amount: INR 3,000,000,000
```
Extracts: borrower `Deccan Cements Pvt Ltd` · facility_amount `3,000,000,000`.

**F — `DeccanCements_FY2026_Projections.txt`** (or a board resolution) — *uploaded by the customer via
the Portal.* Content is free-form (it is stored, not parsed); anything readable works, e.g.:
```
Board Resolution — Deccan Cements Pvt Ltd
Resolved that the company seek a term loan of INR 3,000,000,000 for kiln line-3.
FY2026 projected revenue: INR 4,900,000,000; projected EBITDA: INR 910,000,000.
```

> **To prove "it reads my real PDF":** open template **A** in any editor, export it to a **text PDF**
> (not a scan), and upload that at Doc Intelligence — the badge shows **PDFBox** and the same field
> values appear, traced to the document.

---

## 6. Tips & gotchas
- **Showcase run (§3):** run as `demo.user`; switch persona only to *show* SoD (steps 6, 9). Don't
  re-run an AI action on the five seeded obligors if a live LLM is configured — it would replace
  their stored draft. Drive live AI from the **new** borrower in §4.
- **Live case (§4):** the **application must exist (step 2) before uploading documents (step 3)** —
  Doc Intelligence attaches documents to the deal, and you select it by ref on every screen.
- **Decision Cockpit / Financial Spreading / Risk Lab** need a deal selected — pick your Deccan
  Cements ref (or a showcase obligor).
- **Doc capture:** text PDFs / `.txt` extract live; **scanned images** need `helix.ocr.provider`
  (`tesseract` / `http`) set at deploy — say so plainly if asked.
- **Customer Portal:** the token is one thread only, view is a safe read, and it never logs in — a
  clean way to show the external surface without exposing anything internal.
- If a showcase screen looks empty, the seed didn't run — re-run `python3 scripts/seed_demo_data.py`
  and confirm it prints the five showcase names.
- Governance chips (AI · ADVISORY / HUMAN-GATED / DETERMINISTIC / RBAC) sit top-right on every
  screen — point at them once and let them reinforce the story.
