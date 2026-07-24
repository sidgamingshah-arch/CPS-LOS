#!/usr/bin/env python3
"""
Regression orchestrator — runs every e2e_*.py against the live gateway, parses
pass/fail counts and durations, snapshots the reference data the suites
exercise (rule packs, masters, RBAC, AI governance), samples the audit trail
each backend produced, and emits a single markdown report under
regression-reports/<timestamp>.md.

Methodology contract (the "actual data" the user approved):
  * The seeded reference data (IN-RBI & AE-CBUAE rule packs, ~80 masters,
    ACTOR_ROLE, AI_GOVERNANCE) and the deterministic engines (rating, capital,
    ECL, RAROC, FTP) are the real system surfaces under test.
  * Counterparty identities and financial figures the suites POST are
    illustrative inputs that drive the real engines; the BEHAVIOUR being
    asserted is real.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GW = "http://localhost:8080"

# Suite ordering deliberately follows the lifecycle: smoke first (it lays the
# foundation), then domain suites, then governance / load.
SUITES = [
    ("e2e_smoke",            "Core lifecycle: counterparty -> spread -> rate -> capital -> price -> approve -> book -> ECL/RAROC/MER/CAD/limits/CAP"),
    ("e2e_masters",          "Generic master-engine: maker-checker + jurisdiction versioning (default vs override coexistence)"),
    ("e2e_auth",             "Authentication: X-Actor header validation + actor credentials from ACTOR_ROLE master"),
    ("e2e_rbac",             "Role-based access (RBAC) + segregation of duties enforcement"),
    ("e2e_ai_governance",    "AI capability on/off switch with per-jurisdiction overrides"),
    ("e2e_governance_posture", "Fail-closed RBAC posture (G7): default fail-open on directory outage; governed maker-checker flip to fail-closed denies (403) with an RBAC_POSTURE_DENY audit; posture observable; simulate-outage hook proves both postures deterministically"),
    ("e2e_ai_off",           "Full lifecycle with every AI capability disabled (deterministic-only proof)"),
    ("e2e_ftp",              "FTP master: tenor-structured curves + behavioural-life pricing lookup"),
    ("e2e_codevalue",        "Generic CODE_VALUE master: every UI dropdown is master-driven (maker-checker, versioned), domain extension picks up at the resolver immediately, brand-new domains addable without code change"),
    ("e2e_modelconfig",      "Model configuration engine: sector/segment resolution + sections + visibility/conditional + min/max + iterative + master-driven options + weighted composite + advisory invariant"),
    ("e2e_ratingmodel",      "Rating model of record (opt-in): ratingModelOfRecord flag + confirmed instance flips authoritative grade via the shared MasterScale ladder; inert until confirmed; advisory model stays advisory (flag is the sole gate)"),
    ("e2e_fintemplate",      "Configurable financial templates: chart-of-accounts augmentation (extra input/derived lines + formula ratios) resolved by sector/segment; default leaves the canonical chart untouched; maker-checker"),
    ("e2e_projection",       "Financial projections engine: multi-year proforma from base actuals × drivers × PROJECTION_TEMPLATE; revenue compounding; projected DSCR; driver overrides; sensitivity; human-confirm; advisory invariant; maker-checker"),
    ("e2e_concentration",    "Single-name / group / sector / geo / rating-x-sector concentration + correlation stress"),
    ("e2e_sector_provenance", "Sector provenance (D6): a booked exposure records the REAL counterparty sector (not the segment proxy), flowing into the concentration SECTOR dimension; segment-fallback preserved"),
    ("e2e_group_grade",      "Group grade ladder (D10): deterministic exposure-weighted-notch group grade on the AAA..D master ladder from member grades+exposures; config-driven method (flip -> WORST_OF); advisory + SYSTEM-audited; member ratings unchanged"),
    ("e2e_amendment",        "Post-sanction facility amendment via DoA"),
    ("e2e_syndication",      "Syndication agency engine: lead + participants + fee distribution + secondary transfer"),
    ("e2e_pf",               "Project-finance mechanics: tranche drawdown, DSRA/TRA, milestone certification, LIE approval"),
    ("e2e_predisbursement",  "Conditions Precedent (CP) gate + mandatory CP clearing + drawdown authorization with SoD"),
    ("e2e_postdisbursement", "Drawdown release -> limit ledger -> ECL reconciliation -> RAROC actual"),
    ("e2e_monitoring",       "Covenant testing history + EWS scan + waiver L1/L2 + extension + alerts"),
    ("e2e_collections",      "NPA workflow: DPD stages, restructure, legal path, write-off DoA, cure"),
    ("e2e_sicr_notch",       "SICR-by-notch staging: origination-grade snapshot (immutable across re-register) + rating-downgrade >= threshold notches -> ECL STAGE_2 at dpd 0 (notch rule is the sole trigger)"),
    ("e2e_100_obligors",     "Load test: 100 obligors across 6 segments x 2 jurisdictions; MIS/Customer-360/Portfolio-360"),
    ("e2e_workflow",         "Workflow engine: WORKFLOW_DEFINITION pack consumption + humanGate/autonomy guard + SLA + grade/pricing invariant"),
    ("e2e_reporting",        "Self-service ad-hoc reports: whitelisted dataset registry + RBAC/SoD + master-engine saved defs + cross-check vs MIS"),
    ("e2e_currency",         "Two-level currency: financial-analysis presentation-currency normalisation + consistency guard + shared FX source-of-truth + system-currency base reconciliation"),
    ("e2e_decisioning",      "Decisioning loop closure: CONDITIONAL_APPROVE conditions -> CP register (fan-out + gate enforcement); sanction letter on approval (advisory + maker-checker confirm); committee/quorum voting with SoD (router can't vote, no double vote, quorum finalises)"),
    ("e2e_india_rbi",        "India (RBI) regulatory pack: SMA-0/1/2 -> NPA sub-classification + CRILC large-credit feed (idempotent, threshold-filtered); doubtful age-bands D1/D2/D3 with secured/unsecured split; working-capital drawing-power monitoring (advisory); restructure classification floor; all IN-RBI-only (CBUAE unchanged)"),
    ("e2e_notifications",    "Notification transport (G5-notify): EMAIL_TEMPLATE-rendered outbox for covenant due/breach, CP nudge, CRILC report-due, EWS breach; idempotent per (event,subject,dedupe); additive to the audit event + unchanged business response; SYSTEM-actor; queryable per-service /api/notifications"),
    ("e2e_cdd_tiers",        "CDD tiering from the CDD_TIERS pack (E3): tier derivation now reads enhanced_triggers/simplified_eligible/default_tier from the rule pack (behaviour-preserving vs the old hardcoded logic); re-authoring the pack (drop PEP trigger) moves the tier; finally-restored"),
    ("e2e_scorecard_pack",   "SCORECARD rule pack: scorecard factors/weights read from a versioned, dual-signed pack (fallback == prior built-in constants; grade parity preserved); a new pack version moves a fresh rating while stored ratings are unchanged"),
    ("e2e_hygiene",          "Borrower identifiers + hygiene (CLoM): PAN/GSTIN/LEI/CIN capture with format validation via VALIDATION_PARAMETER; dedup wired on identifier fields (legacy registrationNo preserved); PAN/CIN/LEI verification via the ExternalCheck facade; GREEN/AMBER/RED hygiene summary"),
    ("e2e_spread_versions",  "Financial-spread version timeline: append-only SpreadVersion snapshots (who/when/source, single confirmed pointer); live spread tables, confirm-gate and rating path untouched; an auxiliary history failure never rolls back the authoritative spread"),
    ("e2e_notify_schedule",  "Notification schedule-later + reminder sweep: scheduled rows dispatch only after their due time; capped auto-reminders fire idempotently per reminder ordinal; immediate-enqueue path unchanged"),
    ("e2e_casework",         "Case-management task layer (CLoM): WorkItem task-mirror over workflow-service with round-robin assignment (OOO-skip + delegate), queue claim/assign SoD (403), send-back rework cycle, parallel fan-out joins (ANY/ALL/QUORUM) + TAT from the event timeline; mirroring a task leaves the authoritative CadCase / pricing-exception byte-identical (the task layer never approves and holds no figures)"),
    ("e2e_query_rfi",        "Query/RFI collaboration (CLoM): helix-common auto-exposed /api/queries with INTERNAL + EXTERNAL-facade lanes; raiser-only resolve SoD (addressee self-resolve -> 403); schedule-later dispatch + external-response callback; RFI rendered/enqueued via NotificationService; resolution-listener extension point; audit HUMAN on raise/resolve, SYSTEM on scheduled dispatch"),
    ("e2e_notify_center",    "Notification center read-state (additive to G5-notify): nullable readAt/readBy on the outbox row + auto-exposed unread-count / mark-read / read-all endpoints; a uniquely-scoped recipient counts deterministically; enqueue raises the count (immediate-enqueue behaviour unchanged); mark-read stamps + decrements + is idempotent; read-all zeroes the scope; a read row stays in the list (read != deleted)"),
    ("e2e_notings",          "Noting engine (CLoM Wave 2): NOTING_TYPE-master-driven governed decision records (TOD/CAM-note/product-paper/deferral/second-stage-disb/SRM-renewal) with DoA + fixed-role routing; DRAFT->PENDING_APPROVAL->APPROVED[->PENDING_CAD->AUTHORIZED]|REJECTED|REVERSED|WITHDRAWN; SoD approver!=raiser + must hold routed authority (403); mandatory-reason gates; a noting is a record only -- the subject's authoritative facility/exposure figures are byte-identical across the full lifecycle; all NOTING_* audit events HUMAN"),
    ("e2e_monitoring_artifacts", "Monitoring-artifact engine (CLoM Wave 2): one MONITORING_ARTIFACT_TYPE-master-driven lifecycle (CALL_MEMO/PLANT_VISIT/LCR/QPR/BROKER_REVIEW/STOCK_AUDIT/AUDIT_NOTE) with sections materialised + master-version pinned; DRAFT->SUBMITTED->REVIEWED->APPROVED[->AUTHORIZED]; SoD reviewer!=owner + approver!=reviewer (403); stock-audit vendor RFQ via an EXTERNAL_VENDOR query + notification facade + external-response callback (human vendor-select); artifacts are records -- authoritative ECL/IRAC/exposure byte-identical across the lifecycle"),
    ("e2e_ip_note",          "IP Note (CLoM Wave 2): In-Principle notes in origination with own approval loop (SoD approver!=raiser + authority, 403); convert (APPROVED-only) creates a real LoanApplication linked both ways by ipNoteRef with the structure inherited verbatim; terminal-state + mandatory-reason gates; IP_NOTE_* audit HUMAN"),
    ("e2e_scf",              "SCF product paper (CLoM Wave 2): anchor ScfProgram + ScfSpoke deterministic eligibility from SCF_ELIGIBILITY master (PASS/FAIL + reasons, per-spoke cap + programme roll-up); submit creates a linked PRODUCT_PAPER noting in decision-service; approval SoD (approver!=raiser + authority, 403); best-effort limit-node registration via limit-service; SCF_* audit HUMAN"),
    ("e2e_escrow",           "Escrow monitoring (CLoM Wave 2): EscrowAccount + append-only versioned budget lines + category-tagged transactions; deterministic budget-vs-actual with GREEN/AMBER/RED from VALIDATION_PARAMETER thresholds; re-versioning changes the active baseline without deleting history; record surface -- portfolio ECL/exposure/limit untouched"),
    ("e2e_srm",              "SRM renewal (CLoM Wave 2): reuses the Noting engine (NOTING_TYPE=SRM_RENEWAL) + SRM_CHECKLIST; driving the linked noting to AUTHORIZED advances the subject's MER next-review date one cycle (idempotent) while other MER items are unchanged; SRM_*/MER_RENEWAL_ADVANCED audit HUMAN"),
    ("e2e_perfection",       "Mortgage/MOE perfection (CLoM Wave 2): PerfectionCase steps materialised in order from CHECKLIST_MASTER PERFECTION_MOE (version-pinned); role-gated step completion; MOE-vetting SoD (403); vendor RFQ via EXTERNAL_VENDOR query + external-response callback; optional limit-release gate defaults OFF -- CAD limit-release byte-identical when no pack carries perfectionRequired"),
    ("e2e_coi",              "Conflict-of-interest (CLoM Wave 2): CoiAttestation per subject+actor (records X-Actor, audit HUMAN); DoA/committee decision gate via DOA pack key require_coi_attestation defaults OFF (decisioning byte-identical when absent); gate-ON: un-attested->403, CONFLICTED->403 (cannot self-clear), DECLARED_MANAGED->allowed"),
    ("e2e_tat_mis",          "TAT/MIS reporting (CLoM Wave 3): deterministic cycle-time / SLA-breach / rework / throughput aggregations over the WorkItem + WorkItemEvent case layer (/api/tasks/mis) + query SLA rollup (/api/queries/sla-rollup); numbers are the exact function of the seeded case data; read-only, byte-identical re-run"),
    ("e2e_docpdf",           "PDF/print rendering (CLoM Wave 3): print-optimized standalone HTML (or %PDF) for the credit proposal, sanction letter and generated documents with governance letterhead + authoritative body verbatim; rendering leaves the source artifact byte-identical (confirm-lock + version intact), unknown id -> 404, append-only DOCUMENT_RENDERED HUMAN audit"),
    ("e2e_field_encryption", "Field-level encryption at rest (CLoM Wave 3): AES-256-GCM EncryptedStringConverter on curated sensitive PII free-text columns (screening rationale/notes, UBO name, collateral/covenant extraction text); transparent plaintext round-trip via API + ciphertext at rest in SQLite; identifier/lookup columns (pan/gstin/lei/cin/registrationNo) left plaintext so dedup + hygiene still match; key from HELIX_FIELD_KEY with documented dev-default"),
    ("e2e_cam_packs_core",   "CAM config packs core-lending (CLoM Wave 3): 8 formats (Standard Corporate/NBFC/Working Capital/Term Loan/Project Finance/LRD/Trade LC-BG/Construction-RE) as pure config-as-data -- SEGMENT + MODEL_DEFINITION + FINANCIAL_TEMPLATE + CHECKLIST + CP per format via the generic master engine (maker-checker); each resolves its model + template augmentation (segment ratios) + checklist/CP; no engine branch"),
    ("e2e_cam_packs_specialty", "CAM config packs specialty/FI (CLoM Wave 3): 8 formats (SCF-Vendor/SCF-Dealer/IBPC/Bank-FI/Insurance/Broker/ECB-Structured/Infra-Priority) as config-as-data with segment-appropriate prudential ratios (CRAR/GNPA/LCR, solvency/combined-ratio, net-capital, project-DSCR); each resolves model + template + checklist/CP; a live Bank/FI spread computes the prudential ratios through the real engine; no engine branch"),
    ("e2e_workflow_engine",  "Workflow engine hardening (review fix): parallelGroup/joinPolicy/queueKey WORKFLOW_DEFINITION consumption — parallel co-entry + WorkItem mirror, ANY/QUORUM join marks non-winning siblings SKIPPED, send-back is backwards-only (forward/same-ordinal + blank-actor rejected) and reopens a fresh rework mirror, withdraw requires a named actor"),
    ("e2e_auth_sso",         "Product SSO (profile-gated): default helix.security.mode=none preserves the X-Actor identity model byte-identical (token-less read+write succeed); oidc resource-server mode — no/invalid/expired/tampered token -> 401, valid JWT derives actor from the username claim + roles from the roles claim, token identity overrides a spoofed X-Actor"),
    ("e2e_notify_transport", "Product notification transport (config-gated): default outbox mode is byte-identical (render+persist, no send); smtp mode actually transmits to an SMTP sink and flips the outbox row to SENT with a provider ref; sms mode POSTs to the gateway with the api-key; fail-soft (no address -> FAILED, business tx intact)"),
    ("e2e_dms_crm",          "Product DMS + CRM (config-gated): filesystem DocumentStore upload->store(sha256+metadata)->byte-identical download + list, DOCUMENT_STORED/RETRIEVED audit; CRM write-back simulated (idempotent ExportBatch per case/as-of) + a live-mode POST to a mock endpoint; defaults touch no existing engine path"),
    ("e2e_coedit",           "Product SharePoint/Excel-Online co-edit (config-gated): default provider=none returns the local artifact verbatim with NO external call; graph mode runs token->upload->webUrl (+ Excel workbook session) against a mock Graph server and returns the co-edit URL; a Graph 5xx degrades fail-soft to the local artifact"),
    ("e2e_llm",              "Centralised LLM endpoint config (config-gated): default provider=none keeps copilot/doc-intel/commentary byte-identical deterministic with zero external calls; a configured (mock) model drafts the advisory text/extraction while the authoritative rating/pricing/spread stay byte-identical, doc-intel stays SUGGESTED behind human-confirm, commentary stays an advisory DRAFT under SoD, copilot stays grounded+non-binding; a model 5xx degrades fail-soft to the deterministic path"),
    ("e2e_field_policy",     "Config-driven dynamic screen behaviour (FIELD_POLICY): a new master drives per-field label/help overrides + conditional visibility (visibleWhen) / conditional-required (requiredWhen) via helix-common FieldPolicyService (TTL-cached, fail-open) + auto-exposed /api/field-policy/{formKey}; wired into the origination application-capture form — collateralValue required-when collateralType present is enforced server-side (400, cannot be bypassed by client hiding), unsecured create still 200, explicit collateralValue=0 stays present (no regression to e2e_smoke NONE/0), unknown form -> empty specs (fail-open)"),
    ("e2e_source_ingest",    "Inbound source-system connectors (credit-bureau + inbound CRM): both ride the canonical ingestion contract (Envelope/Provenance/Connector/IngestionGuard) with PUSH (external POST) + PULL (Helix fetches out — simulated sample by default, live via env base-url, fail-soft); idempotent replay -> duplicate (not re-applied); every ingest is provenance-stamped; the ingested bureau score / CRM profile is advisory INPUT that never creates or mutates a Rating or any authoritative figure (counterparty byte-identical before vs after); simulated is the default (no external call, provenance vendor marks 'simulated')"),
    ("e2e_crm_obligor",      "CRM as primary obligor-creation system (pull-and-create): pull borrower(s) from CRM (simulated default / live via env) and CREATE them as governed PROSPECTS through the existing credit-initiation flow (createProspect) — never a bypass; idempotent on the CRM id (re-pull -> matchedExisting, no duplicate); dedup on identifiers before creating (match -> link/enrich the existing counterparty, no duplicate); negative-list hit flagged but NEVER auto-approved (human decides); a pulled prospect stays prospect-stage until a named human /approve promotes it to obligor; CrmProfile provenance attached; CRM_BORROWER_PULLED audit HUMAN; batch pull summary counts add up"),
    ("e2e_proposal_format",  "Format-selectable credit proposal (CAM formats): PROPOSAL_FORMAT master shapes the proposal's section SET/ORDER/titles via keyed section builders; no format resolves the deal-segment default else STANDARD (byte-identical to the pre-format universal proposal, empty-body generate preserved); an explicit format (PROJECT_FINANCE) assembles its layout incl. a segment-specific DSCR-waterfall section; a PROJECT_FINANCE-segment deal defaults to it; the authoritative figures quoted (grade/PD/pricing/DSCR) are unchanged by the format choice (a format is a rendering, not a figure source); print endpoint still renders the formatted proposal"),
    ("e2e_scoring_approval", "Configurable, parameter-routed scoring (score) approval: SCORING_APPROVAL_POLICY master (ordered, first-match-wins over exposure/grade/score-band/override-magnitude/segment/jurisdiction) resolves {requireApproval, requiredAuthority} per rating; behaviour-preserving default (ordinary deal -> CREDIT_OFFICER, confirm by credit.officer); large-exposure / >=2-notch-override / sub-BB route higher (CREDIT_COMMITTEE/CRO) with a routed RATING_APPROVAL work-item; sub-authority confirm -> 403 (forbiddenAutonomy), required authority -> APPROVED + task completed + credit decision can route; SoD (overrider cannot self-confirm); re-authoring the policy (raise the exposure threshold) moves an approval from committee back to CREDIT_OFFICER; the authoritative finalGrade/PD are byte-identical before vs after (gate, not a figure change)"),
    ("e2e_banking_asr",      "Banking ASR / account statement review (CLoM R1-10): capture a borrower's banking-arrangement monthly statement lines in origination -> deterministic account-conduct metrics (average balance, peak/avg utilisation = drawn/sanctioned, credit/debit summations, cheque-return split, min/max, txn count) computed with NO LLM in the figure path and hand-computable from known lines; an OPTIONAL advisory narrative summary is drafted at the governed LlmClient boundary (deterministic template fallback) and never changes any metric (byte-identical snapshot before vs after); the parent application's authoritative rating (modelGrade/finalGrade/pd) is byte-identical after the advisory run; human confirm DRAFT->CONFIRMED (records actor, re-confirm 409); BANKING_ASR_COMPUTED SYSTEM, _SUMMARISED AI, _CONFIRMED HUMAN"),
    ("e2e_annexures",        "CAM Annexure engine (CLoM R1-09): ONE ANNEXURE_TYPE-master-driven authoring lifecycle (CRI_SHEET/INDUSTRY_SCENARIO/ESG_ASSESSMENT/EXCHANGE_RISK/PROJECT_DEFERMENT/GROUP_ANALYSIS, config-as-data + MasterSeeder-seeded) attached to a deal/proposal; create materialises the section skeleton version-pinned (typeVersion); DRAFT->SUBMITTED->REVIEWED->APPROVED (or ->REJECTED with a mandatory reason); SoD reviewer/approver != author (403); optional advisory AI section draft at the governed LlmClient boundary (audit.ai, default provider none = no-op); an annexure is an advisory authoring artefact -- the subject deal's authoritative grade/PD/spread are byte-identical across the whole lifecycle; ANNEXURE_* audit HUMAN on the gates"),
    ("e2e_risk_note",        "Independent Risk Note (CLoM R1-13): the risk function's own narrative opinion record with a governed lifecycle (DRAFT->SUBMITTED->REVIEWED->APPROVED, + REJECTED/REVERSED + reassign) — DISTINCT from the advisory statistical RAG overlay; SoD reviewer/approver != author (403); reject/reverse mandatory-reason gates; reverse APPROVED-only (SUBMITTED->409); reassign moves assignedTo; AI section-draft is fail-soft (provider none -> blank sections stay blank); the deal's authoritative Rating finalGrade/PD is byte-identical before vs after the full lifecycle (opinion record, never a figure mutation); all RISK_NOTE_* gate audit events HUMAN"),
    ("e2e_doc_execution",    "Document Execution Workflow + Signatory Matrix (CLoM R1-14 / F73-F74): ExecutionPackage (EXE-*) opened over a deal from generated documents; per-document lifecycle PENDING->SENT (facade e-sign envelope id)->SIGNED (auto once all signatories sign)->RECEIVED; per-document signatory matrix (INTERNAL/CUSTOMER, mark-signed, no double-sign); deferral/waiver tags; package status auto-derives (all RECEIVED/waived -> COMPLETED); the source GeneratedDocument content + confirm-lock is byte-identical after the full execution run (execution tracks status only, never edits the doc); every transition audit HUMAN"),
    ("e2e_doc_compare",      "Document comparison / incremental-change diff (CLoM F57): a deterministic, read-only engine compares two versioned artifacts already in decision-service and emits a structured change table (ADDED/REMOVED/CHANGED/UNCHANGED). PROPOSAL_VERSIONS — generate v1, add a second covenant, generate v2; the returned diff is exactly the Covenants section CHANGED with every other section UNCHANGED and nothing ADDED/REMOVED (same STANDARD format), and the CHANGED row's new body carries the added covenant. DOCUMENT_VERSIONS — two docs from one template, one extra TNC clause added to the right, yields exactly one ADDED clause with the rest UNCHANGED. Re-running the same comparison is byte-identical (deterministic); the source proposals AND documents are byte-identical after (read-only invariant); a SYSTEM DOC_COMPARISON_COMPUTED audit event is stamped; GET /{ref} + GET ?subjectRef= read it back; unknown kind -> 400, missing artifact -> 404"),
    ("e2e_cam_packs_extra",  "CAM config packs additional/specialty-channel (CLoM comparative): 9 workbook formats (Commodity Exchange/Distributor-SCF/Dealer-Retail-Utility/Exchange House/Factoring-SCF/Fully-Cash-Collateralized/Mutual-Fund-AMC/Service-Providers/Stock-Exchange-Broker) as pure config-as-data -- SEGMENT + MODEL_DEFINITION + FINANCIAL_TEMPLATE + CHECKLIST + CP + PROPOSAL_FORMAT per format via the generic master engine (maker-checker); each resolves model + template with segment-appropriate prudential ratios (margin/position, channel-throughput/stock-turn, turnover÷net-worth, advance-rate/dilution, cash-cover, AUM-growth/expense, receivables-days/EBITDA-margin, net-capital/margin-cover) that COMPUTE through the real spreading engine on a live matching-segment deal; checklist/CP/proposal-format resolve; no engine branch"),
    ("e2e_email_action",     "Email-actionable approve/reject with comments (CLoM F12): an approvable notification is minted one-time approve/reject tokens/links (hashed at rest like the query external-response token, never serialised) + starts action-state PENDING; POST /api/notifications/action/{token} records the addressed recipient's decision + comment as an audit HUMAN NOTIFICATION_ACTION_APPROVE/REJECT and best-effort routes to the subject (fail-soft); single-use (replaying either link -> 403, one decision spends both); forged/unknown token -> 403; a plain non-approval notification enqueues with NO token/state and byte-identical immediate-enqueue behaviour"),
    ("e2e_doc_office",       "Word/Excel/CSV office output (CLoM F32): the credit-proposal + generated-document print endpoints gain an additive ?format=rtf|xlsx|csv (no external lib) alongside the byte-identical HTML default -- rtf returns a Word-openable '{\\rtf' document, xlsx a SpreadsheetML 2003 XML workbook (<?xml…><Workbook xmlns=urn:schemas-microsoft-com:office:spreadsheet> with ss:Type=String cells, formula-immune), csv the tabular content with the OWASP formula-injection guard; each sets the right Content-Type + attachment filename; injected <script> stays inert per format; a render mutates nothing (proposal version+content and document byte-identical before/after) and stamps a DOCUMENT_RENDERED HUMAN audit; unsupported format -> 400, unknown id -> 404"),
    ("e2e_global_cashflow",  "Global / combined cash-flow (relationship consolidated debt-service): portfolio-service consolidates each group member's latest CONFIRMED spread (revenue, EBITDA proxy, CFO, total debt service = interest expense + current-portion LTD) into a combined coverage view + per-member contribution; combined DSCR = combined CFO / combined debt service (hand-computed) and combined revenue = Σ members; fail-soft per member; GLOBAL_CASHFLOW_CONSOLIDATED SYSTEM-audited (never AI); read-side only -- each member's authoritative spread is byte-identical after"),
    ("e2e_exceptions",       "Unified exception / tickler register (U7): read-only rollup aggregates open covenant/MER/CAD/limit/EWS items into one normalised {source,type,subjectRef,description,owner,dueAt,severity,status} shape (best-effort — a down source degrades to a warning, never mutates a source of record; a real MER item is surfaced and byte-identical after); light manual Tickler (TKL-*) create->assign->resolve with SoD (owner resolving own -> 403) + audit HUMAN on transitions"),
    ("e2e_field_access",     "User hierarchy + field-level access (U9 / CLoM F13,F47,F77): FIELD_ACCESS master (role -> field -> READ|WRITE|HIDDEN) + helix-common FieldAccessService/Controller auto-exposed (TTL-cached, DEFAULT-PERMISSIVE fail-open) — GET returns the map, server-side enforce() strips READ and 403s an explicit WRITE to a HIDDEN field (client cannot bypass); USER_HIERARCHY master drives a workflow-service 'view my team' inbox scope (scope=team folds in the caller's subordinates' open tasks, scope=self/no-scope byte-identical to pre-U9); unmapped role -> full access, unmapped form -> empty specs, unmapped supervisor -> self-only (keeps e2e_casework/e2e_tat_mis/e2e_rbac/e2e_field_policy green); master authoring HUMAN maker-checker audited"),
    ("e2e_doc_spread",       "Doc-capture honesty + AI-EXTRACT->GRID->HUMAN-CONFIRM (U-G): the deterministic doc-intel fallback varies its sample field VALUES per borrower/segment (stable per-deal hash) so extraction differs across deals while the field KEYS/confidence/model stay fixed (contract preserved); POST /spread/from-extraction maps a CONFIRMED DocExtraction's figures onto canonical INPUT taxonomy keys and rebuilds the working spread as an UNCONFIRMED DRAFT (source DOC_INTEL) — confirming the EXTRACTION alone never populates the grid (review-only), from-extraction lands a DRAFT (spreadConfirmed=false, never auto-confirmed), the authoritative gate flips true ONLY on the separate spread/confirm, re-running always returns to DRAFT, and the source extraction row is byte-identical after; derived lines are engine-computed (never seeded); guards: cross-deal extraction->400, unknown id->404, non-financial (KYC) extraction with no figure fields->400 (nothing invented); SPREAD_DRAFTED_FROM_EXTRACTION audit AI"),
    ("e2e_doc_ocr",          "Real OCR / file upload for document capture (feat/real-ocr): the multipart /documents/upload path accepts actual file bytes, stores them in the governed DMS (storedDocId + sha256) and extracts the document's REAL text — PDFBox for text PDFs (pure Java, in-container), UTF-8 for text/csv, config-gated OCR (helix.ocr.provider default none) for images/scanned PDFs, fail-soft (never throws). A generated one-page PDF (correct xref byte offsets) embedding a known string is really parsed by PDFBox (extractedText CONTAINS 'Revenue'/'1250000000', method PDFBOX); classification is content-based (FINANCIAL_STATEMENT from the text keywords, not the neutral filename); doc-intel extract() derives fields FROM the text (revenue VALUE == 1250000000, the embedded number — NOT a template constant, model doc-intel-ocr-v1) as a SUGGESTED advisory, confirm stamps a HUMAN audit; the ADVISORY INVARIANT holds (authoritative rating finalGrade/modelGrade/pd + confirmed spread REVENUE byte-identical before vs after); a plain .txt extracts (method TEXT); a text-less image with provider=none degrades gracefully (OCR_NONE + note, no crash, nothing invented); the legacy filename-only upload path is unchanged (storedDocId null)"),
    ("e2e_auto_movement",    "Generalized config-driven automatic case movement (CLoM #72): OPTIONAL WORKFLOW_DEFINITION stage keys autoAdvanceAfterHours / autoLapseAfterHours (+autoLapseToStatus) and a nullable WorkItem autoLapseAfterHours drive a SYSTEM-actor sweep (POST /api/workflow/auto-movement/sweep, property-guarded helix.workflow.auto-movement.enabled) that auto-advances a dwelling non-gated stage and auto-lapses a stale instance/work-item with an append-only AUTO_ADVANCED/AUTO_LAPSED transition; human-gated (humanGate/autonomy=D) stages are NEVER auto-moved; a def/task with NO auto keys is byte-identical before/after the sweep (regression-safety proof); sweep is idempotent"),
    ("e2e_syndication_im",   "Syndication Information Memorandum workspace (CLoM #80 / R3-07): versioned IM per syndicated deal (IM-*, DRAFT->CIRCULATED->FINAL|WITHDRAWN, 7 deterministic seeded sections grounded from deal data, append-only re-draft at version+1) under /api/syndication; finalise enforces SoD (finaliser==creator -> 403 forbiddenAutonomy); the IM is a document artifact only -- the syndicate book + allocation ledger (commitments/shares/funded/fees) are byte-identical before vs after the full IM lifecycle; transitions HUMAN-audited"),
    ("e2e_customer_portal",  "Token-scoped customer/vendor self-service portal (CLoM #23) + Graph mail sender: /api/portal/{token} (helix-common, auto-exposed) resolves the ONE thread the RFI token maps to (GET context is a safe idempotent read, respond appends an EXTERNAL-actor message -> RESPONDED, multipart upload stores to the governed DMS tagged to the thread); SECURITY -- a token for thread A cannot read/respond/upload to thread B (no IDOR), no listing endpoint, invalid/withdrawn/resolved/legacy-spent tokens denied with a generic body carrying no topic/question/ref, internal usernames redacted to BANK/YOU; the internal /api/queries + one-time external-response paths are byte-identical; helix.notify.transport=graph adds a config-gated fail-soft MS Graph sendMail (default outbox unchanged, secret/token never logged)"),
    ("e2e_lifecycle_rekyc",  "Lifecycle terminal states + re-KYC sweep (D9): CLOSED reachable via a governed close transition (audited, idempotent); deterministic re-KYC sweep flips VERIFIED->RE_KYC_DUE past the CDD-tier interval (tier-differentiated from CDD_TIERS), SYSTEM-audited + advisory notification; fresh obligor untouched. Runs LAST (its as-of sweep touches the shared book)"),
]

RESULT_RE = re.compile(r"(\d+)\s+passed\s*,\s*(\d+)\s+failed\b", re.IGNORECASE)
# Suites count their own assertions via lines like "  PASS  …" / "  FAIL  …".
# This is the universal fallback when a suite doesn't print a summary line.
PASS_LINE = re.compile(r"^\s+PASS\s", re.MULTILINE)
FAIL_LINE = re.compile(r"^\s+FAIL\s", re.MULTILINE)


def http_json(path):
    try:
        with urllib.request.urlopen(GW + path, timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"_error": str(e)}


def run_suite(name):
    script = ROOT / "scripts" / f"{name}.py"
    t0 = time.time()
    proc = subprocess.run(
        [sys.executable, str(script)],
        capture_output=True,
        text=True,
        cwd=str(ROOT),
        timeout=900,
    )
    elapsed = time.time() - t0
    out = (proc.stdout or "") + ("\n[stderr]\n" + proc.stderr if proc.stderr else "")
    # Prefer the suite's own summary line ("N passed, M failed") if present;
    # otherwise fall back to counting PASS/FAIL lines directly.
    summary_matches = RESULT_RE.findall(out)
    if summary_matches:
        passed, failed = int(summary_matches[-1][0]), int(summary_matches[-1][1])
        tally_source = "summary_line"
    else:
        passed = len(PASS_LINE.findall(out))
        failed = len(FAIL_LINE.findall(out))
        tally_source = "pass_fail_lines"
    return {
        "suite": name,
        "exit_code": proc.returncode,
        "duration_sec": round(elapsed, 2),
        "passed": passed,
        "failed": failed,
        "tally_source": tally_source,
        "stdout_tail": "\n".join(out.splitlines()[-50:]),
        "ok": proc.returncode == 0 and failed == 0 and passed > 0,
    }


PACK_TYPES = [
    "CAPITAL_SA", "ECRA_MAPPING", "RATING_PD_MAP", "LGD_MAP", "PROVISIONING",
    "DOA_MATRIX", "KYC_TIERS", "LARGE_EXPOSURE", "CONCENTRATION_LIMITS",
    "PRICING", "WORKFLOW_DEF",
]


def snapshot_reference_data():
    """Snapshot what's seeded in config-service — proof of the real data surface."""
    snap = {}
    snap["jurisdictions"] = http_json("/config/api/jurisdictions")
    # The /rulepacks endpoint returns one ACTIVE pack per (jurisdiction, type);
    # we walk the known pack types to enumerate what's seeded per jurisdiction.
    for jur in ("IN-RBI", "AE-CBUAE"):
        packs = []
        for pt in PACK_TYPES:
            p = http_json(f"/config/api/rulepacks?jurisdiction={jur}&type={pt}")
            if isinstance(p, dict) and "_error" not in p and "status" not in p:
                packs.append(p)
        snap[f"rule_packs_{jur.split('-')[0]}"] = packs
    # Masters — group by type
    master_types = [
        "ACTOR_ROLE", "AI_GOVERNANCE", "GOVERNANCE_POSTURE", "FACILITY_MASTER", "COLLATERAL_MASTER",
        "COVENANT_LIBRARY", "CP_MASTER", "CHECKLIST_MASTER", "EWS_TRIGGER",
        "FTP_CURVE", "RAROC_PD_TERM_STRUCTURE", "RAROC_BENCHMARK",
        "DEDUP_RULES", "NEGATIVE_LIST", "MODEL_DEFINITION", "FINANCIAL_TEMPLATE",
        "PROJECTION_TEMPLATE", "ESG_BAND", "CODE_VALUE",
        "EXTERNAL_RATING_AGENCY", "VALUATION_AGENCY", "CHARGE_AGENCY",
        "EMAIL_TEMPLATE",
    ]
    by_type = {}
    for t in master_types:
        recs = http_json(f"/config/api/masters/{t}")
        if isinstance(recs, list):
            by_type[t] = len(recs)
        else:
            by_type[t] = recs
    snap["masters_active_counts"] = by_type
    return snap


def sample_audit_trail():
    """Hit each backend's audit endpoint for a small sample — proof the real
    engines and human gates fired during the run (with actorType breakdown)."""
    services = {
        "config":       "/config/api/audit",
        "counterparty": "/counterparty/api/audit",
        "origination":  "/origination/api/audit",
        "risk":         "/risk/api/audit",
        "decision":     "/decision/api/audit",
        "portfolio":    "/portfolio/api/audit",
        "limit":        "/limits/api/audit",
        "workflow":     "/workflow/api/audit",
    }
    out = {}
    for svc, path in services.items():
        evs = http_json(path)
        if isinstance(evs, list):
            by_actor = {}
            by_event = {}
            for e in evs:
                by_actor[e.get("actorType", "?")] = by_actor.get(e.get("actorType", "?"), 0) + 1
                et = e.get("eventType", "?")
                by_event[et] = by_event.get(et, 0) + 1
            top = sorted(by_event.items(), key=lambda kv: -kv[1])[:8]
            out[svc] = {"total_events": len(evs), "actor_types": by_actor, "top_events": top}
        else:
            out[svc] = {"_error": str(evs)}
    return out


def fmt_md(report):
    lines = []
    lines.append("# Helix CPS-LOS — Regression Report")
    lines.append("")
    lines.append(f"**Run timestamp:** {report['run_started']} (UTC)  ")
    lines.append(f"**Total duration:** {report['total_duration_sec']:.1f}s  ")
    lines.append(f"**Gateway:** `{GW}`  ")
    lines.append(f"**Source SHA:** `{report['git_sha']}` on `{report['git_branch']}`")
    lines.append("")
    lines.append("## Methodology")
    lines.append("")
    lines.append("Per session contract: this regression exercises **real** seeded")
    lines.append("reference data (RBI & CBUAE rule packs, ~80 masters, ACTOR_ROLE,")
    lines.append("AI_GOVERNANCE) and **real** deterministic engines (rating PD/LGD,")
    lines.append("capital RWA, ECL/IRAC, RAROC, FTP, limit ledger). Counterparty")
    lines.append("identities and financial figures the suites POST are illustrative")
    lines.append("inputs driving the real engines; the asserted behaviour is real.")
    lines.append("")
    lines.append("The AI-advisory invariant (authoritative grade/pricing unchanged")
    lines.append("after RAG/macro/qualitative overlays) is verified by the suites.")
    lines.append("")

    # Headline table
    lines.append("## Suite results")
    lines.append("")
    lines.append("| # | Suite | Passed | Failed | Duration (s) | Status |")
    lines.append("|---:|---|---:|---:|---:|:---:|")
    total_pass = total_fail = 0
    for i, r in enumerate(report["suites"], 1):
        status = "PASS" if r["ok"] else ("FAIL" if r["passed"] is not None else "ERROR")
        p = r["passed"] if r["passed"] is not None else "—"
        f = r["failed"] if r["failed"] is not None else "—"
        lines.append(f"| {i} | `{r['suite']}` — {next(d for s, d in SUITES if s == r['suite'])} | {p} | {f} | {r['duration_sec']} | {status} |")
        if isinstance(r["passed"], int): total_pass += r["passed"]
        if isinstance(r["failed"], int): total_fail += r["failed"]
    lines.append(f"| | **TOTAL** | **{total_pass}** | **{total_fail}** | **{report['total_duration_sec']:.1f}** | {'PASS' if total_fail == 0 and all(s['ok'] for s in report['suites']) else 'FAIL'} |")
    lines.append("")

    # Reference data
    snap = report["reference_data"]
    lines.append("## Reference data exercised (the 'real config' surface)")
    lines.append("")
    if isinstance(snap.get("jurisdictions"), list):
        lines.append(f"**Jurisdictions seeded:** {', '.join(j.get('code', '?') for j in snap['jurisdictions'])}")
    for jur_key, label in [("rule_packs_IN", "IN-RBI"), ("rule_packs_AE", "AE-CBUAE")]:
        packs = snap.get(jur_key)
        if isinstance(packs, list) and packs:
            lines.append(f"")
            lines.append(f"**{label} rule packs ({len(packs)} active):**")
            lines.append("")
            lines.append("| Pack | Type | Version | Policy sign-off | Model-risk sign-off | Dual-signed |")
            lines.append("|---|---|---:|---|---|:---:|")
            for p in packs:
                code = p.get("code") or p.get("packId") or p.get("id") or "?"
                typ = p.get("type") or p.get("packType") or "?"
                ver = p.get("version", "?")
                pol = p.get("policySignedOffBy") or "—"
                mr = p.get("modelRiskSignedOffBy") or "—"
                dual = "✓" if p.get("fullySignedOff") else "—"
                lines.append(f"| `{code}` | {typ} | {ver} | {pol} | {mr} | {dual} |")
    lines.append("")
    lines.append("**Active master records by type:**")
    lines.append("")
    lines.append("| Master type | Active records |")
    lines.append("|---|---:|")
    for t, c in snap.get("masters_active_counts", {}).items():
        lines.append(f"| `{t}` | {c if isinstance(c, int) else 'err'} |")
    lines.append("")

    # Audit trail
    lines.append("## Engine-execution evidence (audit trail samples)")
    lines.append("")
    lines.append("Sampling each backend's append-only `audit_events` table after the")
    lines.append("regression. `actorType` breakdown proves which actions were taken")
    lines.append("by a HUMAN, an AI capability, or a SYSTEM/engine path.")
    lines.append("")
    for svc, data in report["audit_samples"].items():
        if "_error" in data:
            lines.append(f"### {svc}-service\n\n_error: {data['_error']}_\n")
            continue
        lines.append(f"### {svc}-service")
        lines.append("")
        lines.append(f"- **Events recorded:** {data['total_events']}")
        lines.append(f"- **Actor mix:** " + ", ".join(f"`{k}`={v}" for k, v in sorted(data['actor_types'].items())))
        lines.append(f"- **Top events:** " + ", ".join(f"`{e}`({n})" for e, n in data['top_events']))
        lines.append("")

    # Tails
    lines.append("## Per-suite output tails")
    lines.append("")
    lines.append("Last 50 lines of each suite's stdout, for spot-checking which")
    lines.append("specific assertions executed (and how the real engines responded).")
    lines.append("")
    for r in report["suites"]:
        lines.append(f"<details><summary><code>{r['suite']}</code> — {r['passed']} passed, {r['failed']} failed, {r['duration_sec']}s</summary>\n")
        lines.append("```")
        lines.append(r["stdout_tail"])
        lines.append("```\n</details>\n")

    lines.append("## Environment")
    lines.append("")
    lines.append("| | |")
    lines.append("|---|---|")
    lines.append(f"| Java | {report['java_version']} |")
    lines.append(f"| Python | {sys.version.split()[0]} |")
    lines.append(f"| Services | 9 (config, counterparty, origination, risk, decision, portfolio, copilot, limit, gateway) |")
    lines.append(f"| Database | SQLite per service (1 connection each) in `./data/` |")
    return "\n".join(lines) + "\n"


def main():
    # --render-only <existing.json>: re-render the markdown from a saved JSON,
    # refreshing the reference-data + audit-trail snapshots from the live stack
    # without re-running the suites. Useful when the report template is fixed
    # after a successful run.
    if len(sys.argv) >= 3 and sys.argv[1] == "--render-only":
        prev = json.load(open(sys.argv[2]))
        prev["reference_data"] = snapshot_reference_data()
        prev["audit_samples"] = sample_audit_trail()
        out_dir = ROOT / "regression-reports"
        out_dir.mkdir(exist_ok=True)
        stamp = prev["run_started"].replace(":", "").replace("-", "")[:15]
        (out_dir / f"{stamp}.md").write_text(fmt_md(prev))
        (out_dir / f"{stamp}.json").write_text(json.dumps(prev, indent=2, default=str))
        print(f"[+] Re-rendered: regression-reports/{stamp}.md")
        return

    started = datetime.now(timezone.utc).isoformat(timespec="seconds")
    t0 = time.time()

    print(f"[*] Regression started {started}", flush=True)

    print("[*] Snapshotting reference data...", flush=True)
    ref_data = snapshot_reference_data()

    results = []
    for name, _ in SUITES:
        print(f"[*] running {name} ...", flush=True)
        r = run_suite(name)
        flag = "OK " if r["ok"] else ("FAIL" if r["passed"] is not None else "ERR ")
        print(f"    [{flag}] {name}: {r['passed']} passed, {r['failed']} failed, {r['duration_sec']}s",
              flush=True)
        results.append(r)

    print("[*] Sampling audit trail...", flush=True)
    audit = sample_audit_trail()

    java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
    git_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(ROOT), capture_output=True, text=True).stdout.strip()
    git_branch = subprocess.run(["git", "branch", "--show-current"], cwd=str(ROOT), capture_output=True, text=True).stdout.strip()

    report = {
        "run_started": started,
        "total_duration_sec": time.time() - t0,
        "java_version": java,
        "git_sha": git_sha[:12],
        "git_branch": git_branch,
        "reference_data": ref_data,
        "suites": results,
        "audit_samples": audit,
    }

    # Save report.
    out_dir = ROOT / "regression-reports"
    out_dir.mkdir(exist_ok=True)
    stamp = started.replace(":", "").replace("-", "")[:15]
    md_path = out_dir / f"{stamp}.md"
    json_path = out_dir / f"{stamp}.json"
    md_path.write_text(fmt_md(report))
    json_path.write_text(json.dumps(report, indent=2, default=str))

    overall_pass = all(r["ok"] for r in results)
    print(f"\n[+] Report written: {md_path.relative_to(ROOT)}")
    print(f"[+] Raw JSON:       {json_path.relative_to(ROOT)}")
    print(f"[+] Overall: {'PASS' if overall_pass else 'FAIL'}")
    sys.exit(0 if overall_pass else 1)


if __name__ == "__main__":
    main()
