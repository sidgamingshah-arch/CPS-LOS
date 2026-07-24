package com.helix.common.rbac;

import java.util.Set;

/**
 * Catalogue of role-gated actions. Name-equality SoD (maker ≠ checker) says two
 * DIFFERENT humans acted; this layer says each of them was ALLOWED to act at all.
 * Like {@link com.helix.common.governance.AiCapability}, keeping the catalogue as
 * a compile-time enum makes "who may move money in this platform" an auditable
 * artefact, not a doc.
 *
 * <p>Each action carries the set of roles permitted to perform it (any-of). The
 * actor→roles mapping is the {@code ACTOR_ROLE} master in config-service —
 * maker-checker governed like every other master — resolved via
 * {@link ActorDirectory}.</p>
 */
public enum ProtectedAction {
    DISBURSEMENT_REQUEST("disbursement-request", Set.of("CREDIT_OPS")),
    DISBURSEMENT_AMEND("disbursement-amend", Set.of("CREDIT_OPS")),
    DISBURSEMENT_CANCEL("disbursement-cancel", Set.of("CREDIT_OPS", "CREDIT_OFFICER")),
    DISBURSEMENT_AUTHORIZE("disbursement-authorize", Set.of("CREDIT_OFFICER")),
    DISBURSEMENT_RELEASE("disbursement-release", Set.of("TREASURY_OPS")),
    DISBURSEMENT_REVERSE("disbursement-reverse", Set.of("TREASURY_OPS")),
    DISBURSEMENT_REJECT("disbursement-reject", Set.of("CREDIT_OFFICER")),
    CP_CLEAR("cp-clear", Set.of("CAD_OPS", "CREDIT_OPS")),
    CP_WAIVE("cp-waive", Set.of("CREDIT_COMMITTEE")),
    PF_CERTIFY("pf-certify", Set.of("LIE")),
    PF_RESERVE_FUND("pf-reserve-fund", Set.of("TREASURY_OPS")),
    PF_RESERVE_WITHDRAW("pf-reserve-withdraw", Set.of("TREASURY_OPS")),
    REPAYMENT_RECORD("repayment-record", Set.of("LOAN_OPS")),
    REPAYMENT_CONFIRM("repayment-confirm", Set.of("LOAN_OPS")),
    REPAYMENT_REJECT("repayment-reject", Set.of("LOAN_OPS")),
    /** Approval of an amendment is rank-checked against the DoA authority, not this set. */
    FACILITY_AMEND_PROPOSE("facility-amend-propose", Set.of("RM", "CREDIT_OPS")),
    COLLECTIONS_OPEN("collections-open", Set.of("COLLECTIONS_OPS", "CREDIT_OPS")),
    COLLECTIONS_UPDATE("collections-update", Set.of("COLLECTIONS_OPS", "CREDIT_OPS")),
    COLLECTIONS_LEGAL("collections-legal", Set.of("COLLECTIONS_HEAD", "LEGAL")),
    COLLECTIONS_CURE("collections-cure", Set.of("COLLECTIONS_OPS", "CREDIT_OPS")),
    /** Write-off proposal — approval is rank-checked against the DoA authority, not this set. */
    COLLECTIONS_WRITEOFF_PROPOSE("collections-writeoff-propose",
            Set.of("COLLECTIONS_OPS", "COLLECTIONS_HEAD", "CREDIT_OPS")),
    /** Execute a saved or inline ad-hoc report — read-only over the book. */
    REPORT_RUN("report-run",
            Set.of("RM", "CREDIT_OPS", "CREDIT_OFFICER", "CREDIT_HEAD", "RM_HEAD",
                   "PORTFOLIO_OPS", "RISK_OFFICER", "CREDIT_COMMITTEE")),
    /** Author/edit a report definition draft (the master goes through maker-checker before ACTIVE). */
    REPORT_DEFINE("report-define",
            Set.of("CREDIT_OPS", "PORTFOLIO_OPS", "RISK_OFFICER", "MASTER_MAKER")),
    /** Advance a workflow stage — the routed approver/owner of the next stage. */
    WORKFLOW_ADVANCE("workflow-advance",
            Set.of("RM", "CREDIT_OPS", "CREDIT_OFFICER", "CREDIT_HEAD", "RM_HEAD",
                   "TREASURY_OPS", "CAD_OPS", "COLLECTIONS_OPS", "CREDIT_COMMITTEE")),
    /** Force a utilisation past an available / exposure / country-cap breach — a credit-risk override, not a system action. */
    LIMIT_OVERRIDE("limit-override", Set.of("CREDIT_OFFICER", "CREDIT_HEAD", "CREDIT_COMMITTEE")),
    /**
     * Originate a prospect / loan application — a FIRST-LINE coverage act. Enforced with the SOFT
     * {@link ActorDirectory#requireRecognised} gate: an actor positively recognised as holding only
     * second/third-line roles (e.g. COMPLIANCE) is denied, while an unroled/unknown actor stays
     * permissive (the directory narrows only for a role it recognises). This is the fix for
     * "compliance can originate" — origination is coverage, not control.
     */
    ORIGINATE("originate", Set.of("RM", "RM_HEAD", "ANALYST", "CREDIT_OPS")),
    /**
     * Confirm/reject an advisory Client Planning Template. The relationship owner (or the credit
     * officer over them) signs off the plan — NOT an analyst. Hard-gated: this is a named sign-off.
     */
    CPT_REVIEW("cpt-review", Set.of("RM", "RM_HEAD", "CREDIT_OFFICER"));

    private final String key;
    private final Set<String> allowedRoles;

    ProtectedAction(String key, Set<String> allowedRoles) {
        this.key = key;
        this.allowedRoles = allowedRoles;
    }

    public String key() { return key; }

    /** Roles permitted to perform this action (any one suffices). */
    public Set<String> allowedRoles() { return allowedRoles; }
}
