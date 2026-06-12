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
    REPAYMENT_REJECT("repayment-reject", Set.of("LOAN_OPS"));

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
