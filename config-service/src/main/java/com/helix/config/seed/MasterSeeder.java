package com.helix.config.seed;

import com.helix.config.service.MasterDataService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds representative records across many master types — demonstrating that the
 * single generic master engine covers the entity-specific masters in the spec
 * (dedup rules, negative list, thresholds, email templates, facility/collateral/
 * covenant libraries, RAROC masters, agencies, EWS triggers, benchmarks, …).
 */
@Component
@Order(20)
public class MasterSeeder implements CommandLineRunner {

    private final MasterDataService masters;

    public MasterSeeder(MasterDataService masters) {
        this.masters = masters;
    }

    @Override
    public void run(String... args) {
        if (masters.hasAny()) {
            return;
        }
        // ---- Credit-initiation masters ----
        masters.seedActive("DEDUP_RULES", "default", null, map(
                "strategy", "NAME_AND_IDENTIFIER",
                "identifierFields", List.of("registrationNo"),
                "nameMatchThreshold", 0.82,
                "combineWith", "OR"));
        masters.seedActive("NEGATIVE_LIST", "country:CU", null, map("type", "COUNTRY", "value", "CU", "reason", "sanctions"));
        masters.seedActive("NEGATIVE_LIST", "country:KP", null, map("type", "COUNTRY", "value", "KP", "reason", "sanctions"));
        masters.seedActive("NEGATIVE_LIST", "country:IR", null, map("type", "COUNTRY", "value", "IR", "reason", "sanctions"));
        masters.seedActive("NEGATIVE_LIST", "entity:ACME_SHELL", null, map("type", "ENTITY", "value", "Acme Shell Holdings", "reason", "fraud-watch"));
        masters.seedActive("INACTIVITY_THRESHOLD", "default", null, map("days", 90));
        masters.seedActive("DRAFT_CLEANUP", "default", null, map("months", 6));

        // ---- Notification templates ----
        masters.seedActive("EMAIL_TEMPLATE", "OWNERSHIP_CLAIM", null, map(
                "subject", "Ownership claim on {{borrower}}",
                "body", "{{newRm}} has claimed ownership of {{borrower}}. Prior RM: {{oldRm}}."));
        masters.seedActive("EMAIL_TEMPLATE", "OWNERSHIP_CHANGE", null, map(
                "subject", "Ownership changed for {{borrower}}",
                "body", "Ownership of {{borrower}} reassigned to {{newRm}}."));
        masters.seedActive("EMAIL_TEMPLATE", "OBLIGOR_APPROVED", null, map(
                "subject", "Obligor {{borrower}} created",
                "body", "Obligor {{borrower}} has been created. RM {{rm}}, Group RM {{groupRm}}."));
        masters.seedActive("EMAIL_TEMPLATE", "COVENANT_DUE", null, map(
                "subject", "Covenant due for {{borrower}}",
                "body", "Covenant {{metric}} test is due on {{dueDate}}."));
        // G5-notify: templates for the previously audit-only lifecycle events.
        masters.seedActive("EMAIL_TEMPLATE", "COVENANT_BREACH", null, map(
                "subject", "Covenant breach on {{borrower}}",
                "body", "Covenant {{metric}} {{operator}} {{threshold}} tested {{result}} as of {{asOf}}; action: {{action}}."));
        masters.seedActive("EMAIL_TEMPLATE", "MER_DUE", null, map(
                "subject", "MER item due for {{borrower}}",
                "body", "MER item '{{description}}' is due on {{dueDate}} (owner {{owner}})."));
        masters.seedActive("EMAIL_TEMPLATE", "MER_OVERDUE", null, map(
                "subject", "MER item overdue for {{borrower}}",
                "body", "MER item '{{description}}' has been overdue since {{dueDate}} (owner {{owner}})."));
        masters.seedActive("EMAIL_TEMPLATE", "MER_ESCALATED", null, map(
                "subject", "MER escalation for {{borrower}}",
                "body", "MER item '{{description}}' escalated to {{escalatedTo}} after {{days}} day(s) overdue."));
        masters.seedActive("EMAIL_TEMPLATE", "CP_NUDGE", null, map(
                "subject", "Open conditions precedent on {{borrower}}",
                "body", "{{openCount}} mandatory CP(s) still open on {{facilityRef}}: {{codes}}."));
        masters.seedActive("EMAIL_TEMPLATE", "COMMITTEE_QUORUM_PENDING", null, map(
                "subject", "Committee decision pending — {{borrower}}",
                "body", "{{approvals}} of {{quorum}} approvals recorded on {{reference}} ({{authority}}); awaiting quorum."));
        masters.seedActive("EMAIL_TEMPLATE", "CRILC_REPORT_DUE", null, map(
                "subject", "CRILC large-credit report ready ({{asOf}})",
                "body", "{{count}} reportable borrower(s) in SMA/NPA at/above the threshold as of {{asOf}}."));
        masters.seedActive("EMAIL_TEMPLATE", "EWS_BREACH", null, map(
                "subject", "Early-warning signal — {{borrower}}",
                "body", "{{signalType}} ({{severity}}) on {{reference}}: {{rationale}}."));
        masters.seedActive("EMAIL_TEMPLATE", "REKYC_DUE", null, map(
                "subject", "Re-KYC due for {{borrower}}",
                "body", "{{borrower}} ({{reference}}, CDD {{cddTier}}) is due for re-KYC as of {{dueDate}}. RM {{rm}}."));

        // G5-notify: recipient-role routing per event (recordKey = eventType). Absent -> no routing.
        masters.seedActive("NOTIFICATION_ROUTE", "COVENANT_DUE", null, map("roles", List.of("RM", "CREDIT_OFFICER")));
        masters.seedActive("NOTIFICATION_ROUTE", "COVENANT_BREACH", null, map("roles", List.of("RM", "CREDIT_OFFICER", "CREDIT_COMMITTEE")));
        masters.seedActive("NOTIFICATION_ROUTE", "MER_DUE", null, map("roles", List.of("CAD_OPS")));
        masters.seedActive("NOTIFICATION_ROUTE", "MER_OVERDUE", null, map("roles", List.of("CAD_OPS", "CREDIT_OFFICER")));
        masters.seedActive("NOTIFICATION_ROUTE", "MER_ESCALATED", null, map("roles", List.of("CREDIT_OFFICER", "CREDIT_COMMITTEE")));
        masters.seedActive("NOTIFICATION_ROUTE", "CP_NUDGE", null, map("roles", List.of("CAD_OPS", "RM")));
        masters.seedActive("NOTIFICATION_ROUTE", "COMMITTEE_QUORUM_PENDING", null, map("roles", List.of("CREDIT_COMMITTEE")));
        masters.seedActive("NOTIFICATION_ROUTE", "CRILC_REPORT_DUE", null, map("roles", List.of("REGULATORY_REPORTING")));
        masters.seedActive("NOTIFICATION_ROUTE", "EWS_BREACH", null, map("roles", List.of("RM", "CREDIT_OFFICER")));
        masters.seedActive("NOTIFICATION_ROUTE", "REKYC_DUE", null, map("roles", List.of("RM", "COMPLIANCE")));

        // ---- Facility master (classification / type / category) ----
        masters.seedActive("FACILITY_MASTER", "TERM_LOAN", null, map("classification", "FUND_BASED", "type", "NON_REVOLVING", "category", "LONG_TERM"));
        masters.seedActive("FACILITY_MASTER", "WORKING_CAPITAL", null, map("classification", "FUND_BASED", "type", "REVOLVING", "category", "SHORT_TERM"));
        masters.seedActive("FACILITY_MASTER", "CASH_CREDIT", null, map("classification", "FUND_BASED", "type", "REVOLVING", "category", "SHORT_TERM"));
        masters.seedActive("FACILITY_MASTER", "LETTER_OF_CREDIT", null, map("classification", "NON_FUND_BASED", "type", "REVOLVING", "category", "SHORT_TERM"));
        masters.seedActive("FACILITY_MASTER", "BANK_GUARANTEE", null, map("classification", "NON_FUND_BASED", "type", "NON_REVOLVING", "category", "LONG_TERM"));

        // ---- Collateral master (hierarchy + valuation + risk weight) ----
        masters.seedActive("COLLATERAL_MASTER", "CASH", null, hier("TANGIBLE", "FINANCIAL", "MARKETABLE_SECURITIES", "CASH_DEPOSIT", 0.0, "MARK_TO_MARKET"));
        masters.seedActive("COLLATERAL_MASTER", "GOVT_SECURITIES", null, hier("TANGIBLE", "FINANCIAL", "MARKETABLE_SECURITIES", "BOND", 0.02, "MARK_TO_MARKET"));
        masters.seedActive("COLLATERAL_MASTER", "EQUITY_LISTED", null, hier("TANGIBLE", "FINANCIAL", "MARKETABLE_SECURITIES", "SHARES", 0.25, "MARK_TO_MARKET"));
        masters.seedActive("COLLATERAL_MASTER", "PROPERTY", null, hier("TANGIBLE", "NON_FINANCIAL", "IMMOVABLE_FIXED_ASSET", "COMMERCIAL_BUILDING", 0.40, "VALUATION_REPORT"));
        masters.seedActive("COLLATERAL_MASTER", "RECEIVABLES", null, hier("TANGIBLE", "NON_FINANCIAL", "CURRENT_ASSET", "TRADE_RECEIVABLES", 0.50, "BOOK_VALUE"));

        // ---- Covenant library (category + standard definition) ----
        masters.seedActive("COVENANT_LIBRARY", "DSCR", null, map("category", "FINANCIAL", "operator", ">=", "defaultThreshold", 1.25, "definition", "Debt service coverage ratio"));
        masters.seedActive("COVENANT_LIBRARY", "NET_LEVERAGE", null, map("category", "FINANCIAL", "operator", "<=", "defaultThreshold", 3.5, "definition", "Net debt / EBITDA"));
        masters.seedActive("COVENANT_LIBRARY", "CURRENT_RATIO", null, map("category", "FINANCIAL", "operator", ">=", "defaultThreshold", 1.1, "definition", "Current assets / current liabilities"));
        masters.seedActive("COVENANT_LIBRARY", "AUDITED_FS_SUBMISSION", null, map("category", "INFORMATION", "operator", "BY_DATE", "definition", "Submit audited financials within 180 days of FYE"));
        masters.seedActive("COVENANT_LIBRARY", "NEGATIVE_PLEDGE", null, map("category", "NEGATIVE", "definition", "No further charge on assets without lender consent"));
        masters.seedActive("COVENANT_LIBRARY", "INTEREST_COVERAGE", null, map("category", "INFORMATION", "operator", ">=", "defaultThreshold", 2.0, "definition", "EBITDA / interest expense"));

        // ---- Agencies ----
        masters.seedActive("VALUATION_AGENCY", "ARGUS_VALUERS", null, map("name", "Argus Valuers LLP", "empanelled", true));
        masters.seedActive("CHARGE_AGENCY", "MCA_ROC", "IN-RBI", map("name", "MCA / Registrar of Companies", "country", "IN"));
        masters.seedActive("CHARGE_AGENCY", "EIRC", "AE-CBUAE", map("name", "Emirates Integrated Registries", "country", "AE"));
        masters.seedActive("EXTERNAL_RATING_AGENCY", "SP", null, map("name", "S&P Global", "scale", List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC")));
        masters.seedActive("EXTERNAL_RATING_AGENCY", "MOODYS", null, map("name", "Moody's", "scale", List.of("Aaa", "Aa", "A", "Baa", "Ba", "B", "Caa")));

        // ---- RAROC masters ----
        masters.seedActive("RAROC_PD_TERM_STRUCTURE", "default", null, map("AAA", 0.0003, "AA", 0.0005, "A", 0.0010, "BBB", 0.0030, "BB", 0.0100, "B", 0.0350, "CCC", 0.1200));
        masters.seedActive("RAROC_CCF", "default", null, map("undrawn_commitment", 0.40, "trade_contingent", 0.20, "direct_credit_substitute", 1.00));
        masters.seedActive("RAROC_OPERATING_COST", "MID_CORPORATE", null, map("rate", 0.010));
        masters.seedActive("RAROC_LIQUIDITY_PREMIUM", "default", null, map("rate", 0.0025));
        masters.seedActive("RAROC_FTP", "default", null, map("rate", 0.075));
        masters.seedActive("RAROC_BENCHMARK", "MIBOR", "IN-RBI", map("rate", 0.069));
        masters.seedActive("RAROC_BENCHMARK", "EIBOR", "AE-CBUAE", map("rate", 0.045));

        // ---- EWS trigger master (threshold + criticality) ----
        masters.seedActive("EWS_TRIGGER", "DPD", null, map("enabled", true, "thresholds", map("red", ">=90", "amber", ">=30", "green", "<30"), "criticality", "HIGH", "nature", "REAL_TIME"));
        masters.seedActive("EWS_TRIGGER", "NET_LEVERAGE", null, map("enabled", true, "thresholds", map("red", ">4.0", "amber", ">4.0", "green", "<=4.0"), "criticality", "MEDIUM", "nature", "LAGGING"));
        masters.seedActive("EWS_TRIGGER", "DSCR", null, map("enabled", true, "thresholds", map("red", "<1.1", "amber", "<1.1", "green", ">=1.1"), "criticality", "HIGH", "nature", "LAGGING"));

        // ---- Industry benchmark master ----
        masters.seedActive("INDUSTRY_BENCHMARK", "MANUFACTURING", null, map("ebitdaMargin", 0.15, "netLeverage", 3.0, "currentRatio", 1.3, "interestCoverage", 3.0));
        masters.seedActive("INDUSTRY_BENCHMARK", "INFRASTRUCTURE", null, map("ebitdaMargin", 0.25, "netLeverage", 5.0, "currentRatio", 1.1, "dscr", 1.3));

        // ---- CAD / documentation masters ----
        masters.seedActive("CHECKLIST_MASTER", "CORP_TERM_LOAN_SECURED", null, map("items", List.of("Sanction letter", "Facility agreement", "Mortgage deed", "Board resolution", "Insurance assignment")));
        masters.seedActive("DOC_TEMPLATE_MASTER", "FACILITY_AGREEMENT", null, map("format", "DOCX", "clauses", List.of("definitions", "facility", "interest", "covenants", "events_of_default")));
        masters.seedActive("DOC_TEMPLATE_MASTER", "SANCTION_LETTER", null, map("format", "PDF", "clauses", List.of("sanction_summary", "approved_facilities", "pricing_terms", "conditions_precedent", "conditions_general", "validity", "acceptance")));
        masters.seedActive("TNC_MASTER", "REGISTERED_MORTGAGE", null, map("text", "Borrower to maintain valid insurance on the mortgaged property assigned to the bank.", "appliesTo", "PROPERTY"));

        // ---- CP_MASTER — pre-disbursement Conditions Precedent templates per facility type ----
        // The pre-disbursement gate (decision-service) seeds an application's CP register
        // from this master at sanction-time. Jurisdiction-specific keys layer on top:
        // a "TERM_LOAN:IN-RBI" key wins over the plain "TERM_LOAN" default.
        masters.seedActive("CP_MASTER", "TERM_LOAN", null, map("items", List.of(
                map("code", "CP-FA",      "title", "Executed facility agreement",      "mandatory", true,  "description", "Original facility agreement executed by all parties and held in safe custody."),
                map("code", "CP-BR",      "title", "Board resolution",                 "mandatory", true,  "description", "Borrower board / shareholder resolution authorising the borrowing."),
                map("code", "CP-SEC",     "title", "Security perfection",              "mandatory", true,  "description", "All collateral charges registered with the regulator (ROC/CERSAI) and on-record."),
                map("code", "CP-INS",     "title", "Insurance assignment",             "mandatory", true,  "description", "Valid insurance on charged assets with the bank named as loss-payee."),
                map("code", "CP-VAL",     "title", "Independent valuation within 90 days", "mandatory", true,  "description", "Empanelled valuer report not older than 90 days, at sanction value or better."),
                map("code", "CP-MAC",     "title", "No material adverse change",       "mandatory", true,  "description", "RM confirmation that no MAC has occurred since the sanction date."),
                map("code", "CP-KYC",     "title", "KYC re-verified",                  "mandatory", false, "description", "KYC refreshed within INACTIVITY_THRESHOLD if the relationship was dormant."))));
        masters.seedActive("CP_MASTER", "WORKING_CAPITAL", null, map("items", List.of(
                map("code", "CP-FA",      "title", "Executed facility agreement",      "mandatory", true),
                map("code", "CP-DPN",     "title", "Demand promissory note",           "mandatory", true,  "description", "Borrower-executed DPN held on file."),
                map("code", "CP-SEC",     "title", "Hypothecation of current assets",  "mandatory", true,  "description", "Charge over book debts + inventory perfected."),
                map("code", "CP-STK",     "title", "Stock statement template signed",  "mandatory", true,  "description", "Borrower acknowledges monthly stock/debtor statement obligation."),
                map("code", "CP-MAC",     "title", "No material adverse change",       "mandatory", true))));
        masters.seedActive("CP_MASTER", "OVERDRAFT", null, map("items", List.of(
                map("code", "CP-FA",      "title", "Executed OD agreement",            "mandatory", true),
                map("code", "CP-SEC",     "title", "Lien on FD / counter-security",    "mandatory", true,  "description", "Lien marked on the counter-security (FD/cash margin) before account is enabled for drawing."),
                map("code", "CP-MAC",     "title", "No material adverse change",       "mandatory", true))));
        masters.seedActive("CP_MASTER", "BG", null, map("items", List.of(
                map("code", "CP-CG",      "title", "Counter-guarantee + indemnity",    "mandatory", true,  "description", "Borrower counter-guarantee + indemnity executed before issuance."),
                map("code", "CP-MAR",     "title", "Cash margin lodged",               "mandatory", true,  "description", "Cash margin per sanction terms lodged in lien-marked account."),
                map("code", "CP-DRAFT",   "title", "BG draft text approved",           "mandatory", true,  "description", "Beneficiary BG text reviewed and approved by Legal."))));
        masters.seedActive("CP_MASTER", "LC", null, map("items", List.of(
                map("code", "CP-AGR",     "title", "Executed LC agreement",            "mandatory", true),
                map("code", "CP-MAR",     "title", "LC margin lodged",                 "mandatory", true),
                map("code", "CP-TRADE",   "title", "Underlying trade documents",       "mandatory", true,  "description", "Proforma invoice / contract / import licence on record."),
                map("code", "CP-MAC",     "title", "No material adverse change",       "mandatory", true))));
        masters.seedActive("CP_MASTER", "PROJECT_FINANCE", null, map("items", List.of(
                map("code", "CP-CTA",     "title", "Common Terms Agreement executed",  "mandatory", true),
                map("code", "CP-IA",      "title", "Intercreditor / inter-se",         "mandatory", true,  "description", "Inter-creditor / inter-se agreement signed by every lender in the consortium."),
                map("code", "CP-EQ",      "title", "Sponsor equity tied up",           "mandatory", true,  "description", "Sponsor equity contribution evidenced (bank statement / share allotment)."),
                map("code", "CP-PERMITS", "title", "All required statutory permits",   "mandatory", true,  "description", "Environmental, land, regulatory approvals on file."),
                map("code", "CP-DSRA",    "title", "DSRA / TRA funded",                "mandatory", true,  "description", "Debt-service reserve / trust-and-retention account funded per facility doc."),
                map("code", "CP-MILES",   "title", "Milestone certification",          "mandatory", true,  "description", "LIE / lender's engineer certifies the milestone for the tranche being drawn."),
                map("code", "CP-INS",     "title", "Construction & operating insurance", "mandatory", true,  "description", "CAR/EAR + operating insurance with lender as loss-payee."))));

        // ---- CP_MASTER jurisdiction overrides ----
        // Layered ON TOP of the default-keyed records above. The ConditionPrecedentService
        // picker prefers a {facilityType}:{jurisdiction} key over the plain default — so
        // when a deal is jurisdiction=IN-RBI on a TERM_LOAN, this overrides the seed.
        // IN-RBI specifics: ROC charge filing (Indian Companies Act §77/78), CERSAI
        // registration (SARFAESI §20A), revenue-stamping, India-specific KYC refresh.
        masters.seedActive("CP_MASTER", "TERM_LOAN", "IN-RBI", map("items", List.of(
                map("code", "CP-FA",       "title", "Executed facility agreement",     "mandatory", true),
                map("code", "CP-BR",       "title", "Board resolution",                "mandatory", true),
                map("code", "CP-STAMP",    "title", "Revenue stamping per state",      "mandatory", true,  "description", "Facility + security documents stamped per the borrower's state Stamp Act."),
                map("code", "CP-ROC",      "title", "ROC charge filing (Form CHG-1)",  "mandatory", true,  "description", "Charge on company assets filed with Registrar of Companies within 30 days."),
                map("code", "CP-CERSAI",   "title", "CERSAI registration (SARFAESI)",  "mandatory", true,  "description", "Security interest registered on the CERSAI portal under SARFAESI §20A."),
                map("code", "CP-INS",      "title", "Insurance assignment to bank",    "mandatory", true),
                map("code", "CP-VAL",      "title", "Empanelled valuer report ≤90d",   "mandatory", true,  "description", "Valuation by an RBI-empanelled valuer not older than 90 days."),
                map("code", "CP-CIBIL",    "title", "CIBIL refresh + flagging check",  "mandatory", true,  "description", "Fresh CIBIL commercial report; willful-defaulter / SMA flag check."),
                map("code", "CP-MAC",      "title", "No material adverse change",      "mandatory", true))));
        masters.seedActive("CP_MASTER", "WORKING_CAPITAL", "IN-RBI", map("items", List.of(
                map("code", "CP-FA",       "title", "Executed WC agreement",           "mandatory", true),
                map("code", "CP-DPN",      "title", "Demand promissory note + stamp",  "mandatory", true,  "description", "DPN executed and revenue-stamped per state."),
                map("code", "CP-HYPO",     "title", "Hypothecation of current assets", "mandatory", true,  "description", "Charge on book debts + inventory perfected via ROC + CERSAI."),
                map("code", "CP-DP",       "title", "Drawing-power computation signed","mandatory", true,  "description", "Borrower signs the drawing-power working from latest stock statement."),
                map("code", "CP-MAC",      "title", "No material adverse change",      "mandatory", true))));
        // AE-CBUAE specifics: Emirates ID + UAE mortgage at the Land Department, no Indian
        // CERSAI/ROC; UAE-specific KYC (AECB Al Etihad) + economic-substance.
        masters.seedActive("CP_MASTER", "TERM_LOAN", "AE-CBUAE", map("items", List.of(
                map("code", "CP-FA",       "title", "Executed facility agreement",     "mandatory", true),
                map("code", "CP-EID",      "title", "Authorised-signatory Emirates ID","mandatory", true,  "description", "Emirates IDs of signing officers + power of attorney on file."),
                map("code", "CP-MOR-LD",   "title", "Mortgage with Land Department",   "mandatory", true,  "description", "Registered mortgage at the relevant emirate's Land Department / Tabu."),
                map("code", "CP-INS",      "title", "Insurance assigned to bank",      "mandatory", true),
                map("code", "CP-VAL",      "title", "RICS-equivalent valuation ≤90d",  "mandatory", true),
                map("code", "CP-AECB",     "title", "Al Etihad bureau report fresh",   "mandatory", true,  "description", "AECB commercial bureau report pulled within 30 days."),
                map("code", "CP-ESR",      "title", "Economic substance compliance",   "mandatory", true,  "description", "ESR notification + report filing evidenced where applicable."),
                map("code", "CP-MAC",      "title", "No material adverse change",      "mandatory", true))));
        masters.seedActive("CP_MASTER", "WORKING_CAPITAL", "AE-CBUAE", map("items", List.of(
                map("code", "CP-FA",       "title", "Executed WC agreement",           "mandatory", true),
                map("code", "CP-CHQ",      "title", "Security cheques on file",        "mandatory", true,  "description", "Undated security cheques per UAE practice, in line with bank policy."),
                map("code", "CP-CHARGE",   "title", "Charge on receivables (UAE)",     "mandatory", true,  "description", "Movable-collateral pledge registered with the Emirates Movable Collateral Registry."),
                map("code", "CP-AECB",     "title", "Al Etihad bureau report fresh",   "mandatory", true),
                map("code", "CP-MAC",      "title", "No material adverse change",      "mandatory", true))));

        // ---- FTP_CURVE master — funds-transfer-pricing curve per currency ----
        // The pricing path reads this to derive a term-structured, behaviourally-adjusted
        // cost of funds instead of a flat number. recordKey = currency; jurisdiction
        // optional (a jurisdiction-specific curve overrides the default). tenorPoints is
        // the funding curve; behavioural maps each facility type to its behavioural life.
        masters.seedActive("FTP_CURVE", "INR", null, map(
                "tenorPoints", List.of(
                        map("months", 1, "rate", 0.0625), map("months", 3, "rate", 0.0655),
                        map("months", 12, "rate", 0.0690), map("months", 36, "rate", 0.0730),
                        map("months", 60, "rate", 0.0760), map("months", 120, "rate", 0.0795)),
                "liquidityPremiumBpsPerYear", 2.0,
                "behavioural", map(
                        "TERM_LOAN", map("lifeFactor", 0.6, "type", "AMORTISING"),
                        "PROJECT_FINANCE", map("lifeFactor", 0.7, "type", "AMORTISING"),
                        "WORKING_CAPITAL", map("behaviouralMonths", 12, "type", "REVOLVING"),
                        "OVERDRAFT", map("behaviouralMonths", 6, "type", "DEMAND"),
                        "BG", map("behaviouralMonths", 12, "type", "CONTINGENT"),
                        "LC", map("behaviouralMonths", 6, "type", "CONTINGENT"))));
        masters.seedActive("FTP_CURVE", "USD", null, map(
                "tenorPoints", List.of(
                        map("months", 1, "rate", 0.0530), map("months", 3, "rate", 0.0545),
                        map("months", 12, "rate", 0.0560), map("months", 36, "rate", 0.0585),
                        map("months", 60, "rate", 0.0605), map("months", 120, "rate", 0.0635)),
                "liquidityPremiumBpsPerYear", 1.5,
                "behavioural", map(
                        "TERM_LOAN", map("lifeFactor", 0.6, "type", "AMORTISING"),
                        "PROJECT_FINANCE", map("lifeFactor", 0.7, "type", "AMORTISING"),
                        "WORKING_CAPITAL", map("behaviouralMonths", 12, "type", "REVOLVING"),
                        "OVERDRAFT", map("behaviouralMonths", 6, "type", "DEMAND"))));
        masters.seedActive("FTP_CURVE", "AED", null, map(
                "tenorPoints", List.of(
                        map("months", 1, "rate", 0.0540), map("months", 12, "rate", 0.0570),
                        map("months", 36, "rate", 0.0595), map("months", 60, "rate", 0.0615)),
                "liquidityPremiumBpsPerYear", 1.5,
                "behavioural", map(
                        "TERM_LOAN", map("lifeFactor", 0.6, "type", "AMORTISING"),
                        "WORKING_CAPITAL", map("behaviouralMonths", 12, "type", "REVOLVING"))));

        // ---- FX_RATE master — DATED period-end rates for financial-analysis currency ----
        // The historical/dated authority for Level-1 currency normalisation: a borrower's
        // foreign-currency period is restated at the rate AS-AT that period-end, not at
        // today's spot. recordKey = "<CCY>@<asOf>" (ISO date); payload carries the
        // rate to the INR base. limit-service's FxService remains the runtime SPOT table
        // for Level-2 limit math; these dated points are what cross-period analysis reads.
        // Rates are deliberately a touch off today's spot so restatement is observably
        // period-dated rather than current.
        seedFxRate("USD", "2023-03-31", 82.0);
        seedFxRate("USD", "2024-03-31", 83.4);
        seedFxRate("AED", "2023-03-31", 22.3);
        seedFxRate("AED", "2024-03-31", 22.7);
        seedFxRate("EUR", "2023-03-31", 89.0);
        seedFxRate("EUR", "2024-03-31", 90.5);
        seedFxRate("GBP", "2023-03-31", 101.0);
        seedFxRate("GBP", "2024-03-31", 105.5);

        // ---- SYNDICATION_FEE_MASTER — fee schedule for the syndication agency engine ----
        // recordKey 'default'; jurisdiction-overridable. bps applied as: arrangement +
        // underwriting + agency accrue to the lead/agent on the total; participation
        // fee accrues to each lender on its committed share.
        masters.seedActive("SYNDICATION_FEE_MASTER", "default", null, map(
                "arrangementFeeBps", 75.0, "underwritingFeeBps", 25.0,
                "agencyFeeBps", 10.0, "participationFeeBps", 30.0));

        // ---- AI governance master (default record per capability, jurisdiction=null) ----
        // Default posture: every governed AI capability is ENABLED. A bank can flip an
        // individual capability off, or layer a per-jurisdiction override on top of
        // the default. The frontend reads /config/api/governance/ai/resolved to render
        // the surface; each AI service consults AiGovernanceClient.enforce(...) before
        // doing AI work, returning 403 forbiddenAutonomy when disabled.
        for (com.helix.common.governance.AiCapability cap : com.helix.common.governance.AiCapability.values()) {
            masters.seedActive("AI_GOVERNANCE", cap.key(), null,
                    map("enabled", true, "description", cap.description()));
        }

        // G7 — RBAC governance posture. Default FAIL-OPEN: on a directory cold-start outage the
        // authority checks allow (humans must be able to work; name-equality SoD still applies).
        // A regulator-cautious bank flips this to failClosed:true (maker-checker) to DENY on outage.
        masters.seedActive("GOVERNANCE_POSTURE", "rbac", null,
                map("failClosed", false,
                        "description", "RBAC directory-outage posture: fail-open (allow) by default"));

        // ---- floating-rate benchmarks ----        // currentRate = the rate the next reset boundary will apply. history is for
        // back-testing/audit (decision-service reads from it when the schedule's
        // reset date is in the past).
        seedBenchmark("EBLR", "INR", 0.0870, "Reserve Bank of India External Benchmark Lending Rate");
        seedBenchmark("MCLR_1Y", "INR", 0.0895, "1-year Marginal Cost of Funds Lending Rate");
        seedBenchmark("SOFR_3M", "USD", 0.0530, "3-month Secured Overnight Financing Rate");
        seedBenchmark("EIBOR_3M", "AED", 0.0460, "3-month Emirates Interbank Offered Rate");
        seedBenchmark("EURIBOR_3M", "EUR", 0.0375, "3-month Euro Interbank Offered Rate");

        // ---- CODE_VALUE master — the generic code-value table behind UI dropdowns ----
        // One record per DOMAIN (e.g. GRADE_SCALE, FACILITY_TYPE, COLLATERAL_TYPE, SEGMENT,
        // DOC_KIND, STRUCTURE_TYPE, OVERRIDE_REASON, OVERRIDE_ROLE, DECISION_OUTCOME, ...).
        // Payload: { label, "values": [{code, label, score?, sortOrder?}], "version": n }.
        // Closes the configurability gap: every dropdown reads from here under maker-checker.
        seedCodeValues();

        // ---- MODEL_DEFINITION master — the configurable scoring-model engine ----
        // Replaces the flat QUAL_SCORECARD. A model is selected by (jurisdiction, sector,
        // segment), holds SECTIONS (qualitative AND quantitative) of typed questions
        // (DROPDOWN / INPUT / NUMBER / ITERATIVE) with visibility rules, min/max-answered
        // constraints, master-driven options, and a weighted composite -> band. The model
        // is built / re-weighted in the Model Builder UI under maker-checker.
        seedModels();


        // recordKey = actor name, payload.roles = the roles the actor holds. The
        // ProtectedAction catalogue (helix-common) maps each money-movement action to
        // its permitted roles; ActorDirectory enforces at the head of the transition.
        // Role grants flow maker -> checker like every other master change.
        seedActor("rm.user", "Relationship Manager", "RM");
        seedActor("analyst.user", "Credit Analyst", "ANALYST");
        seedActor("credit.ops", "Credit Operations", "CREDIT_OPS");
        seedActor("credit.officer", "Credit Officer", "CREDIT_OFFICER");
        seedActor("credit.committee", "Credit Committee", "CREDIT_COMMITTEE");
        seedActor("compliance.officer", "Compliance Officer", "COMPLIANCE");
        seedActor("portfolio.manager", "Portfolio Manager", "PORTFOLIO");
        seedActor("rm.head", "Relationship Head", "RM_HEAD");
        seedActor("cro", "Chief Risk Officer", "CRO", "CREDIT_COMMITTEE", "BOARD_COMMITTEE");
        seedActor("treasury.ops", "Treasury Operations", "TREASURY_OPS");
        seedActor("finance.ops", "Finance Operations", "TREASURY_OPS");
        seedActor("ops.checker", "Operations Control", "TREASURY_OPS", "CREDIT_OFFICER");
        seedActor("cad.maker", "Credit Administration", "CAD_OPS");
        seedActor("loan.ops", "Loan Servicing Ops", "LOAN_OPS");
        seedActor("loan.checker", "Loan Servicing Control", "LOAN_OPS");
        seedActor("lie.engineer", "Lender's Independent Engineer", "LIE");
        seedActor("collections.ops", "Collections Officer", "COLLECTIONS_OPS");
        seedActor("collections.head", "Collections Head", "COLLECTIONS_HEAD", "COLLECTIONS_OPS",
                "CREDIT_OFFICER");
        seedActor("legal.counsel", "Legal Counsel", "LEGAL");
        // The frontend's default actor — a demo super-user holding every operational
        // role so the UI walkthrough is frictionless. SoD still applies on top: even
        // with every role, the same person cannot be maker AND checker.
        seedActor("demo.user", "Demo Super User",
                "CREDIT_OPS", "CREDIT_OFFICER", "TREASURY_OPS", "LOAN_OPS",
                "CAD_OPS", "CREDIT_COMMITTEE", "BOARD_COMMITTEE", "LIE", "RM",
                "COLLECTIONS_OPS", "COLLECTIONS_HEAD", "LEGAL");

        // Bulk-stress bot identities (e2e_100_obligors) mirror their human peers so the
        // 100-obligor book exercises the same RBAC / DoA gates at scale (G1). credit.officer.bot
        // holds CREDIT_OFFICER (rating-confirm gate) plus CREDIT_COMMITTEE + BOARD_COMMITTEE so
        // it can decide every tier the distributed book routes to.
        seedActor("seed.bot", "Seed Bot", "CREDIT_OPS");
        seedActor("rm.bot", "RM Bot", "RM");
        seedActor("analyst.bot", "Analyst Bot", "ANALYST");
        seedActor("compliance.bot", "Compliance Bot", "COMPLIANCE");
        seedActor("credit.ops.bot", "Credit Ops Bot", "CREDIT_OPS");
        seedActor("credit.officer.bot", "Credit Officer Bot", "CREDIT_OFFICER", "CREDIT_COMMITTEE", "BOARD_COMMITTEE");
        seedActor("portfolio.bot", "Portfolio Bot", "PORTFOLIO");
    }

    // ---------------------------------------------------------------- model definitions

    private void seedModels() {
        // A small option master proving master-driven dropdowns: a question with
        // optionsFromMaster="ESG_BAND" resolves its choices from these records at
        // render time. Each record's payload carries the display label + 0-100 score.
        masters.seedActive("ESG_BAND", "LEADING", null, map("label", "Leading", "score", 90));
        masters.seedActive("ESG_BAND", "ADEQUATE", null, map("label", "Adequate", "score", 60));
        masters.seedActive("ESG_BAND", "LAGGING", null, map("label", "Lagging", "score", 30));

        // ---- FINANCIAL_TEMPLATE master — configurable chart-of-accounts augmentation ----
        // The canonical 15 input / 8 derived lines + standard ratios are always computed
        // (CanonicalTaxonomy + Ratios). A FINANCIAL_TEMPLATE ADDS sector/segment-specific
        // extra input lines, derived lines, and ratios (formula-driven) on top — so a bank
        // can spread NBFCs / manufacturers / SMEs without code changes. Resolved by the same
        // most-specific (jurisdiction,sector,segment) rule as scoring models.
        seedFinancialTemplates();
        seedProjectionTemplates();

        // Default corporate rating model — jurisdiction/sector/segment all wildcard.
        masters.seedActive("MODEL_DEFINITION", "corporate-rating-v1", null,
                corporateRatingModel("corporate-rating-v1", "Corporate Credit Rating Model",
                        map(), /* extra qualitative question */ false, /* manufacturing risks */ false));

        // Sector-specific override: MANUFACTURING adds a capacity-utilisation question
        // and a master-driven ESG-band dropdown — proving sector specialisation.
        masters.seedActive("MODEL_DEFINITION", "corporate-rating-mfg-v1", null,
                corporateRatingModel("corporate-rating-mfg-v1",
                        "Corporate Rating — Manufacturing",
                        map("sector", "MANUFACTURING"), true, true));

        // Segment-specific override: SME uses a lighter model (fewer mandatory questions).
        masters.seedActive("MODEL_DEFINITION", "sme-rating-v1", null,
                smeRatingModel());
    }

    private void seedCodeValues() {
        // Credit-rating master scale (ladder order matters — sortOrder preserved on render).
        codes("GRADE_SCALE", "Credit-rating master scale",
                v("AAA", "AAA"), v("AA", "AA"), v("A", "A"), v("BBB", "BBB"),
                v("BB", "BB"), v("B", "B"), v("CCC", "CCC"), v("CC", "CC"),
                v("C", "C"), v("D", "D"));
        // Manual rating override reason codes.
        codes("OVERRIDE_REASON", "Manual rating override reasons",
                v("POST_BALANCE_SHEET_EVENT", "Post balance sheet event"),
                v("MANAGEMENT_QUALITY", "Management quality"),
                v("GROUP_SUPPORT", "Group support"),
                v("SECTOR_OUTLOOK", "Sector outlook"),
                v("DATA_QUALITY", "Data quality"),
                v("COLLATERAL_STRENGTH", "Collateral strength"),
                v("OTHER", "Other"));
        // Override role + per-role notch limit (score field).
        codes("OVERRIDE_ROLE", "Override roles + notch limits",
                vs("ANALYST", "Analyst", 1),
                vs("CREDIT_OFFICER", "Credit officer", 2),
                vs("CREDIT_COMMITTEE", "Credit committee", 99),
                vs("CRO", "Chief risk officer", 99));
        // Facility types — kept aligned with FACILITY_MASTER record keys.
        codes("FACILITY_TYPE", "Facility types",
                v("TERM_LOAN", "Term loan"),
                v("WORKING_CAPITAL", "Working capital"),
                v("CASH_CREDIT", "Cash credit"),
                v("LETTER_OF_CREDIT", "Letter of credit"),
                v("BANK_GUARANTEE", "Bank guarantee"),
                v("REVOLVING_CREDIT", "Revolving credit"),
                v("PROJECT_LOAN", "Project loan"),
                v("GUARANTEE", "Guarantee"),
                v("TRADE_LINE", "Trade line"));
        // Collateral types — aligned with COLLATERAL_MASTER record keys.
        codes("COLLATERAL_TYPE", "Collateral types",
                v("CASH", "Cash"),
                v("GOVT_SECURITIES", "Government securities"),
                v("EQUITY_LISTED", "Listed equity"),
                v("PROPERTY", "Property"),
                v("RECEIVABLES", "Receivables"));
        // Counterparty segment classifications.
        codes("SEGMENT", "Counterparty segments",
                v("MID_CORPORATE", "Mid corporate"),
                v("LARGE_CORPORATE", "Large corporate"),
                v("SME", "SME"),
                v("PROJECT_FINANCE", "Project finance"),
                v("TRADE_FINANCE", "Trade finance"),
                v("FINANCIAL_INSTITUTION", "Financial institution"));
        // Specialised deal-structure variants.
        codes("STRUCTURE_TYPE", "Deal structure types",
                v("SINGLE", "Single"),
                v("GROUP", "Group"),
                v("JOINT_OBLIGOR", "Joint obligor"),
                v("DUAL_OBLIGOR", "Dual obligor"),
                v("SYNDICATION", "Syndication"),
                v("FI_ICR", "FI ICR"));
        codes("PARTICIPANT_ROLE", "Participant roles",
                v("PRIMARY_OBLIGOR", "Primary obligor"),
                v("CO_OBLIGOR", "Co-obligor"),
                v("GUARANTOR", "Guarantor"),
                v("GROUP_MEMBER", "Group member"),
                v("LEAD_BANK", "Lead bank"),
                v("PARTICIPANT_LENDER", "Participant lender"));
        codes("LIABILITY_TYPE", "Liability types",
                v("JOINT", "Joint"),
                v("SEVERAL", "Several"),
                v("JOINT_AND_SEVERAL", "Joint and several"));
        // Decision outcomes (credit decision).
        codes("DECISION_OUTCOME", "Credit decision outcomes",
                v("APPROVE", "Approve"),
                v("CONDITIONAL_APPROVE", "Conditional approve"),
                v("DECLINE", "Decline"),
                v("REFER", "Refer"));
        // Document classification kinds (doc-intel).
        codes("DOC_KIND", "Document kinds",
                v("FINANCIAL_STATEMENT", "Financial statement"),
                v("KYC_ID", "KYC / identity"),
                v("FACILITY_DOC", "Facility document"),
                v("SECURITY_DOC", "Security document"),
                v("BANK_STATEMENT", "Bank statement"),
                v("TAX_GST", "Tax / GST"),
                v("MOA_AOA", "MoA / AoA"),
                v("BUREAU_REPORT", "Bureau report"),
                v("OTHER", "Other"));
        codes("TRANSLATION_LANGUAGE", "Translation languages",
                v("en", "English"), v("ar", "Arabic"), v("hi", "Hindi"), v("fr", "French"));
        // RAG / qualitative sector outlook for Risk Lab macro card.
        codes("SECTOR_OUTLOOK", "Macro sector outlook",
                v("IMPROVING", "Improving"),
                v("STABLE", "Stable"),
                v("DETERIORATING", "Deteriorating"));
        // Sort direction — tiny, used by the report builder.
        codes("SORT_DIRECTION", "Sort direction",
                v("DESC", "Descending"), v("ASC", "Ascending"));
    }

    /** Seed one CODE_VALUE record per domain. {@code values} ARE the dropdown options
     *  in order; sortOrder pinned by position. */
    private void codes(String domain, String label, Map<String, Object>... values) {
        java.util.List<Object> ordered = new java.util.ArrayList<>();
        int i = 0;
        for (Map<String, Object> v : values) {
            v = new java.util.LinkedHashMap<>(v);
            v.putIfAbsent("sortOrder", i++);
            ordered.add(v);
        }
        masters.seedActive("CODE_VALUE", domain, null,
                map("domain", domain, "label", label, "values", ordered));
    }

    private Map<String, Object> v(String code, String label) {
        return map("code", code, "label", label);
    }

    private Map<String, Object> vs(String code, String label, double score) {
        return map("code", code, "label", label, "score", score);
    }

    private void seedFinancialTemplates() {
        // Default — matches everything, adds nothing: the canonical chart is unchanged.
        masters.seedActive("FINANCIAL_TEMPLATE", "fin-default", null, map(
                "templateKey", "fin-default", "displayName", "Standard chart of accounts",
                "selector", map(),
                "extraInputLines", java.util.List.of(),
                "extraDerivedLines", java.util.List.of(),
                "extraRatios", java.util.List.of()));

        // SME — adds promoter net worth (extra input) + asset-turnover & promoter-cover ratios.
        masters.seedActive("FINANCIAL_TEMPLATE", "fin-sme", null, map(
                "templateKey", "fin-sme", "displayName", "SME chart (promoter-aware)",
                "selector", map("segment", "SME"),
                "extraInputLines", java.util.List.of(
                        map("key", "PROMOTER_NET_WORTH", "label", "Promoter net worth (external)")),
                "extraDerivedLines", java.util.List.of(),
                "extraRatios", java.util.List.of(
                        finRatio("ASSET_TURNOVER", "Asset turnover (x)", "REVENUE / TOTAL_ASSETS"),
                        finRatio("PROMOTER_COVERAGE", "Promoter cover (x)", "PROMOTER_NET_WORTH / TOTAL_DEBT"))));

        // MANUFACTURING — adds inventory + capacity-utilisation inputs, an inventory-days
        // derived line, and an asset-turnover ratio (resolved by sector).
        masters.seedActive("FINANCIAL_TEMPLATE", "fin-mfg", null, map(
                "templateKey", "fin-mfg", "displayName", "Manufacturing chart",
                "selector", map("sector", "MANUFACTURING"),
                "extraInputLines", java.util.List.of(
                        map("key", "INVENTORY", "label", "Inventory"),
                        map("key", "CAPACITY_UTIL_PCT", "label", "Capacity utilisation (%)")),
                "extraDerivedLines", java.util.List.of(
                        finDerived("INVENTORY_DAYS", "Inventory days", "INVENTORY / COGS * 365")),
                "extraRatios", java.util.List.of(
                        finRatio("ASSET_TURNOVER", "Asset turnover (x)", "REVENUE / TOTAL_ASSETS"))));
    }

    private void seedProjectionTemplates() {
        // PROJECTION_TEMPLATE — a multi-year proforma driver model. Each line's formula may
        // reference: a driver key (revenue_growth, …), base_<LINE> (base-year actual),
        // prev_<LINE> (prior projected year), or <LINE> (this year, computed in order).
        // Advisory: projections never move authoritative figures. Resolved by selector.
        masters.seedActive("PROJECTION_TEMPLATE", "proj-corp-v1", null, map(
                "templateKey", "proj-corp-v1",
                "displayName", "5-year corporate projection",
                "selector", map(),
                "horizonYears", 5,
                "drivers", java.util.List.of(
                        projDriver("revenue_growth", "Revenue growth (YoY)", 0.10),
                        projDriver("ebitda_margin", "EBITDA margin", 0.18),
                        projDriver("interest_rate", "Interest rate on debt", 0.09),
                        projDriver("amortisation_pct", "Debt amortisation / yr", 0.15),
                        projDriver("tax_rate", "Tax rate", 0.25),
                        projDriver("capex_pct_revenue", "Capex (% revenue)", 0.05)),
                "lines", java.util.List.of(
                        projLine("REVENUE", "Revenue", "prev_REVENUE * (1 + revenue_growth)", "REVENUE"),
                        projLine("EBITDA", "EBITDA", "REVENUE * ebitda_margin", "EBITDA"),
                        projLine("DEBT", "Total debt (closing)", "prev_DEBT * (1 - amortisation_pct)", "TOTAL_DEBT"),
                        projLine("INTEREST", "Interest expense", "prev_DEBT * interest_rate", null),
                        projLine("TAX", "Tax", "(EBITDA - INTEREST) * tax_rate", null),
                        projLine("PAT", "Profit after tax", "EBITDA - INTEREST - TAX", null),
                        projLine("CAPEX", "Capex", "REVENUE * capex_pct_revenue", null),
                        projLine("CFO", "Operating cash flow", "EBITDA - TAX", "CFO"),
                        projLine("DEBT_SERVICE", "Debt service", "INTEREST + prev_DEBT * amortisation_pct", null),
                        projLine("DSCR", "Projected DSCR", "CFO / DEBT_SERVICE", null))));
    }

    private Map<String, Object> projDriver(String key, String label, double dflt) {
        return map("key", key, "label", label, "defaultValue", dflt);
    }

    /** A projected line. {@code seedFrom} names the base-year actual line that seeds prev_/base_ (null = computed only). */
    private Map<String, Object> projLine(String key, String label, String formula, String seedFrom) {
        Map<String, Object> m = map("key", key, "label", label, "formula", formula);
        if (seedFrom != null) m.put("seedFrom", seedFrom);
        return m;
    }

    private Map<String, Object> finRatio(String key, String label, String formula) {
        return map("key", key, "label", label, "formula", formula);
    }

    private Map<String, Object> finDerived(String key, String label, String formula) {
        return map("key", key, "label", label, "formula", formula);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> corporateRatingModel(String modelKey, String displayName,
                                                     Map<String, Object> selector,
                                                     boolean sectorExtra, boolean masterDrivenEsg) {
        // Qualitative parameters are STANDALONE — not fed by another module, so the model's
        // advisory recommender scores them (prompt grounds each one); a human reviews.
        java.util.List<Object> qualQuestions = new java.util.ArrayList<>(java.util.List.of(
                withSource(dropdown("mgmt_quality", "Management quality & track record", 0.30, true, null,
                        opt("Strong", 90), opt("Adequate", 60), opt("Weak", 30)),
                        standaloneSrc("Assess management depth, succession, governance and delivery vs. prior plans.")),
                withSource(dropdown("business_profile", "Business & operating profile", 0.25, true, null,
                        opt("Diversified / resilient", 90), opt("In line with peers", 60),
                        opt("Concentrated / fragile", 30)),
                        standaloneSrc("Assess scale, diversification and operating resilience.")),
                // Conditional/visibility: only asked when management is not Weak.
                withSource(dropdown("succession", "Succession & key-person depth", 0.15, false,
                        "mgmt_quality != 'Weak'",
                        opt("Deep bench", 85), opt("Adequate", 55), opt("Key-person risk", 25)),
                        standaloneSrc("Assess succession planning and key-person concentration.")),
                // Iterative/repeating group: capture related-party exposures.
                iterative("related_parties", "Related-party exposures", 0, 8,
                        field("name", "INPUT", "Counterparty"),
                        field("amount", "NUMBER", "Amount (INR m)"))));
        if (masterDrivenEsg) {
            // Master-driven dropdown: options come from the ESG_BAND master at render time.
            qualQuestions.add(withSource(dropdownFromMaster("esg_band", "ESG posture", 0.15, false, "ESG_BAND"),
                    standaloneSrc("Assess governance independence, disclosure quality and material ESG risk.")));
        } else {
            qualQuestions.add(withSource(dropdown("esg_band", "Governance & ESG", 0.15, false, null,
                    opt("Leading", 85), opt("Adequate", 55), opt("Weak", 25)),
                    standaloneSrc("Assess governance independence, disclosure quality and material ESG risk.")));
        }
        if (sectorExtra) {
            qualQuestions.add(number("capacity_utilisation", "Capacity utilisation (%)", 0.10, false,
                    band("min", 85, 90), band("min", 65, 60), band("min", 0, 30)));
        }

        // Quantitative parameters are MODULE-sourced — pulled from the spreading module's ratios.
        java.util.List<Object> quantQuestions = java.util.List.of(
                withSource(number("leverage", "Net leverage (Net debt / EBITDA, x)", 0.40, true,
                        band("max", 2.0, 90), band("max", 3.5, 60), band("max", 99, 30)),
                        moduleSrc("RATIO:NET_LEVERAGE")),
                withSource(number("dscr", "DSCR (x)", 0.35, true,
                        band("min", 1.5, 90), band("min", 1.25, 60), band("min", 0, 30)),
                        moduleSrc("RATIO:DSCR")),
                withSource(number("current_ratio", "Current ratio (x)", 0.25, false,
                        band("min", 1.5, 90), band("min", 1.1, 60), band("min", 0, 30)),
                        moduleSrc("RATIO:CURRENT_RATIO")));

        Map<String, Object> model = map(
                "modelKey", modelKey,
                "displayName", displayName,
                "selector", selector,
                "constraints", map("minAnswered", 4, "maxAnswered", 40,
                        "mandatory", java.util.List.of("mgmt_quality", "business_profile", "leverage", "dscr")),
                "scoring", map("bands", java.util.List.of(
                        map("band", "STRONG", "min", 67),
                        map("band", "ADEQUATE", "min", 45),
                        map("band", "WEAK", "min", 0))),
                "sections", java.util.List.of(
                        map("key", "QUALITATIVE", "kind", "QUALITATIVE", "label", "Qualitative assessment",
                                "weight", 0.4, "questions", qualQuestions),
                        map("key", "QUANTITATIVE", "kind", "QUANTITATIVE", "label", "Quantitative ratios",
                                "weight", 0.6, "questions", quantQuestions)));
        return model;
    }

    private Map<String, Object> smeRatingModel() {
        return map(
                "modelKey", "sme-rating-v1",
                "displayName", "SME Rating Model",
                "selector", map("segment", "SME"),
                "constraints", map("minAnswered", 2, "maxAnswered", 20,
                        "mandatory", java.util.List.of("promoter_strength", "leverage")),
                "scoring", map("bands", java.util.List.of(
                        map("band", "STRONG", "min", 67),
                        map("band", "ADEQUATE", "min", 45),
                        map("band", "WEAK", "min", 0))),
                "sections", java.util.List.of(
                        map("key", "QUALITATIVE", "kind", "QUALITATIVE", "label", "Promoter & business",
                                "weight", 0.5, "questions", java.util.List.of(
                                        withSource(dropdown("promoter_strength", "Promoter strength", 0.6, true, null,
                                                opt("Strong", 90), opt("Adequate", 60), opt("Weak", 30)),
                                                standaloneSrc("Assess promoter capability, commitment and track record.")),
                                        withSource(dropdown("banking_conduct", "Banking conduct", 0.4, false, null,
                                                opt("Clean", 85), opt("Minor irregularities", 55),
                                                opt("Adverse", 25)),
                                                standaloneSrc("Assess account conduct, cheque returns and covenant history.")))),
                        map("key", "QUANTITATIVE", "kind", "QUANTITATIVE", "label", "Financials",
                                "weight", 0.5, "questions", java.util.List.of(
                                        withSource(number("leverage", "Net leverage (x)", 1.0, true,
                                                band("max", 3.0, 90), band("max", 4.5, 60), band("max", 99, 30)),
                                                moduleSrc("RATIO:NET_LEVERAGE"))))));
    }

    // ---- question builders ----

    @SafeVarargs
    private final Map<String, Object> dropdown(String key, String label, double weight, boolean required,
                                         String visibleWhen, Map<String, Object>... options) {
        Map<String, Object> q = map("key", key, "type", "DROPDOWN", "label", label,
                "weight", weight, "required", required,
                "options", java.util.List.of((Object[]) options));
        if (visibleWhen != null) q.put("visibleWhen", visibleWhen);
        return q;
    }

    private Map<String, Object> dropdownFromMaster(String key, String label, double weight,
                                                   boolean required, String masterType) {
        return map("key", key, "type", "DROPDOWN", "label", label, "weight", weight,
                "required", required, "optionsFromMaster", masterType);
    }

    @SafeVarargs
    private final Map<String, Object> number(String key, String label, double weight, boolean required,
                                       Map<String, Object>... scoreBands) {
        return map("key", key, "type", "NUMBER", "label", label, "weight", weight,
                "required", required, "scoreBands", java.util.List.of((Object[]) scoreBands));
    }

    @SafeVarargs
    private final Map<String, Object> iterative(String key, String label, int min, int max,
                                          Map<String, Object>... itemFields) {
        return map("key", key, "type", "ITERATIVE", "label", label, "weight", 0.0,
                "required", false, "min", min, "max", max,
                "itemFields", java.util.List.of((Object[]) itemFields));
    }

    private Map<String, Object> field(String key, String type, String label) {
        return map("key", key, "type", type, "label", label);
    }

    private Map<String, Object> opt(String label, double score) {
        return map("label", label, "score", score);
    }

    /** Attach a parameter source to a question and return it (fluent). */
    private Map<String, Object> withSource(Map<String, Object> q, Map<String, Object> source) {
        q.put("source", source);
        return q;
    }

    /** MODULE source: the parameter's value comes from another CPS module/screen ({@code namespace:key}). */
    private Map<String, Object> moduleSrc(String ref) {
        return map("kind", "MODULE", "ref", ref);
    }

    /** STANDALONE source: no upstream module — scored by the model's advisory recommender (prompt grounds it). */
    private Map<String, Object> standaloneSrc(String prompt) {
        return map("kind", "STANDALONE", "prompt", prompt);
    }

    /** A numeric score band: {@code edge} is "min" or "max", {@code threshold} the bound, {@code score} the award. */
    private Map<String, Object> band(String edge, double threshold, double score) {
        return map(edge, threshold, "score", score);
    }

    private void seedActor(String actor, String displayName, String... roles) {
        masters.seedActive("ACTOR_ROLE", actor, null,
                map("displayName", displayName, "roles", java.util.List.of(roles)));
    }

    private void seedBenchmark(String code, String currency, double currentRate, String displayName) {
        masters.seedActive("BENCHMARK", code, null,
                map("displayName", displayName, "currency", currency, "currentRate", currentRate,
                        "history", java.util.List.of(
                                map("asOf", java.time.LocalDate.now().minusMonths(6).toString(),
                                        "rate", currentRate - 0.0025),
                                map("asOf", java.time.LocalDate.now().minusMonths(3).toString(),
                                        "rate", currentRate - 0.0010),
                                map("asOf", java.time.LocalDate.now().toString(),
                                        "rate", currentRate))));
    }

    private Map<String, Object> hier(String group, String subGroup, String type, String subType, double rw, String valMethod) {
        return map("group", group, "subGroup", subGroup, "type", type, "subType", subType,
                "riskWeight", rw, "valuationMethod", valMethod);
    }

    /** A dated FX point: rate is units of the INR base per 1 unit of {@code currency}. */
    private void seedFxRate(String currency, String asOf, double rateToInr) {
        masters.seedActive("FX_RATE", currency + "@" + asOf, null,
                map("currency", currency, "asOf", asOf, "base", "INR", "rateToInr", rateToInr));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
