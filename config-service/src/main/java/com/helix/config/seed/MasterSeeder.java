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
                "identifierFields", List.of("registrationNo", "pan", "passport", "gstin"),
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
        masters.seedActive("EWS_TRIGGER", "DPD", null, map("enabled", true, "thresholds", map("red", ">60", "amber", ">30", "green", "<=30"), "criticality", "HIGH", "nature", "REAL_TIME"));
        masters.seedActive("EWS_TRIGGER", "NET_LEVERAGE", null, map("enabled", true, "thresholds", map("red", ">4.5", "amber", ">3.5", "green", "<=3.5"), "criticality", "MEDIUM", "nature", "LAGGING"));
        masters.seedActive("EWS_TRIGGER", "DSCR", null, map("enabled", true, "thresholds", map("red", "<1.0", "amber", "<1.25", "green", ">=1.25"), "criticality", "HIGH", "nature", "LAGGING"));

        // ---- Industry benchmark master ----
        masters.seedActive("INDUSTRY_BENCHMARK", "MANUFACTURING", null, map("ebitdaMargin", 0.15, "netLeverage", 3.0, "currentRatio", 1.3, "interestCoverage", 3.0));
        masters.seedActive("INDUSTRY_BENCHMARK", "INFRASTRUCTURE", null, map("ebitdaMargin", 0.25, "netLeverage", 5.0, "currentRatio", 1.1, "dscr", 1.3));

        // ---- CAD / documentation masters ----
        masters.seedActive("CHECKLIST_MASTER", "CORP_TERM_LOAN_SECURED", null, map("items", List.of("Sanction letter", "Facility agreement", "Mortgage deed", "Board resolution", "Insurance assignment")));
        masters.seedActive("DOC_TEMPLATE_MASTER", "FACILITY_AGREEMENT", null, map("format", "DOCX", "clauses", List.of("definitions", "facility", "interest", "covenants", "events_of_default")));
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

        // ---- actor-role directory (RBAC layer over name-equality SoD) ----
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
        seedActor("cro", "Chief Risk Officer", "CRO", "CREDIT_COMMITTEE");
        seedActor("treasury.ops", "Treasury Operations", "TREASURY_OPS");
        seedActor("finance.ops", "Finance Operations", "TREASURY_OPS");
        seedActor("ops.checker", "Operations Control", "TREASURY_OPS", "CREDIT_OFFICER");
        seedActor("cad.maker", "Credit Administration", "CAD_OPS");
        seedActor("loan.ops", "Loan Servicing Ops", "LOAN_OPS");
        seedActor("loan.checker", "Loan Servicing Control", "LOAN_OPS");
        seedActor("lie.engineer", "Lender's Independent Engineer", "LIE");
        // The frontend's default actor — a demo super-user holding every operational
        // role so the UI walkthrough is frictionless. SoD still applies on top: even
        // with every role, the same person cannot be maker AND checker.
        seedActor("demo.user", "Demo Super User",
                "CREDIT_OPS", "CREDIT_OFFICER", "TREASURY_OPS", "LOAN_OPS",
                "CAD_OPS", "CREDIT_COMMITTEE", "LIE");
    }

    private void seedActor(String actor, String displayName, String... roles) {
        masters.seedActive("ACTOR_ROLE", actor, null,
                map("displayName", displayName, "roles", java.util.List.of(roles)));
    }

    private Map<String, Object> hier(String group, String subGroup, String type, String subType, double rw, String valMethod) {
        return map("group", group, "subGroup", subGroup, "type", type, "subType", subType,
                "riskWeight", rw, "valuationMethod", valMethod);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
