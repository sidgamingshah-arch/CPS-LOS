package com.helix.counterparty.init;

import com.helix.common.audit.AuditEvent;
import com.helix.common.audit.AuditEventRepository;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.repo.CounterpartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * One-time startup backfill of {@code Counterparty.createdBy} for rows that predate the column
 * (Hibernate {@code ddl-auto=update} adds it as NULL on the existing book). The creator is
 * recovered AUTHORITATIVELY from the append-only audit trail — the same-DB {@code audit_events}
 * row stamped at creation ({@code COUNTERPARTY_CREATED} / {@code PROSPECT_CREATED} /
 * {@code OBLIGOR_CREATED}) carries the creator's actor. Without this, the maker≠checker gates on
 * KYC sign-off and obligor approval would be fail-open (skipped) for every pre-existing record.
 *
 * <p>Idempotent (only touches NULL rows) and cheap (no-op once filled). Any residual NULL after
 * this — a row with no creation audit at all — is deliberately handled fail-CLOSED at the gate
 * (the sign-off/approve is refused rather than silently bypassing SoD).</p>
 */
@Component
@Order(30)
public class CreatedByBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CreatedByBackfill.class);
    private static final String SUBJECT = "Counterparty";
    private static final Set<String> CREATE_EVENTS =
            Set.of("COUNTERPARTY_CREATED", "PROSPECT_CREATED", "OBLIGOR_CREATED");

    private final CounterpartyRepository counterparties;
    private final AuditEventRepository audit;

    public CreatedByBackfill(CounterpartyRepository counterparties, AuditEventRepository audit) {
        this.counterparties = counterparties;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(String... args) {
        List<Counterparty> pending = counterparties.findByCreatedByIsNull();
        if (pending.isEmpty()) {
            return;
        }
        int filled = 0;
        for (Counterparty cp : pending) {
            String creator = creatorFromAudit(cp.getReference());
            if (creator != null && !creator.isBlank()) {
                cp.setCreatedBy(creator);
                counterparties.save(cp);
                filled++;
            }
        }
        int residual = pending.size() - filled;
        log.info("createdBy backfill: filled {} of {} counterparties from the audit trail{}",
                filled, pending.size(),
                residual > 0 ? " (" + residual + " have no creation audit — SoD gates fail-closed on them)" : "");
    }

    /** Earliest creation-event actor for a counterparty reference, or null if none recorded. */
    private String creatorFromAudit(String reference) {
        // Repo returns occurredAt DESC; the earliest create event is the last matching element.
        String creator = null;
        for (AuditEvent e : audit.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(SUBJECT, reference)) {
            if (CREATE_EVENTS.contains(e.getEventType())) {
                creator = e.getActor();   // keep overwriting -> ends on the earliest (list is DESC)
            }
        }
        return creator;
    }
}
