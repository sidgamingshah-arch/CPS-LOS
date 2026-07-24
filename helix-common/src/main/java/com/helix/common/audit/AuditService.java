package com.helix.common.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes immutable audit events. Insert-only by design (PRD §9, §11 governance):
 * there is deliberately no update or delete path. Audit writes join the caller's
 * transaction (SQLite is single-writer), so they share its connection.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final String serviceName;

    public AuditService(AuditEventRepository repository,
                        @Value("${spring.application.name:helix-service}") String serviceName) {
        this.repository = repository;
        this.serviceName = serviceName;
    }

    /** Record an action performed by a named human actor. */
    @Transactional
    public AuditEvent human(String actor, String eventType, String subjectType, String subjectId,
                            String summary, Map<String, Object> detail) {
        return write(actor, "HUMAN", eventType, subjectType, subjectId, summary, detail);
    }

    /** Record an action performed by an AI capability (governed, never credit-binding). */
    @Transactional
    public AuditEvent ai(String capability, String eventType, String subjectType, String subjectId,
                         String summary, Map<String, Object> detail) {
        return write(capability, "AI", eventType, subjectType, subjectId, summary, detail);
    }

    /** Record a deterministic action performed by the engine (capital/ECL computation). */
    @Transactional
    public AuditEvent engine(String eventType, String subjectType, String subjectId,
                             String summary, Map<String, Object> detail) {
        return write("engine", "SYSTEM", eventType, subjectType, subjectId, summary, detail);
    }

    /**
     * Record an action originated by an EXTERNAL party (a customer / vendor acting through the
     * tokened self-service portal). The {@code actorType} is stamped {@code EXTERNAL} — never
     * {@code HUMAN} — because it is NOT a named-human action inside the bank: possession of the
     * one-time portal token is the only credential, so the trail must not dress it up as an
     * internal user. Mirrors the {@code EXTERNAL} authorType on the query message log.
     */
    @Transactional
    public AuditEvent external(String actor, String eventType, String subjectType, String subjectId,
                               String summary, Map<String, Object> detail) {
        return write(actor == null || actor.isBlank() ? "external" : actor, "EXTERNAL",
                eventType, subjectType, subjectId, summary, detail);
    }

    private AuditEvent write(String actor, String actorType, String eventType, String subjectType,
                             String subjectId, String summary, Map<String, Object> detail) {
        AuditEvent event = new AuditEvent();
        event.setService(serviceName);
        event.setActor(actor);
        event.setActorType(actorType);
        event.setEventType(eventType);
        event.setSubjectType(subjectType);
        event.setSubjectId(subjectId);
        event.setSummary(summary);
        event.setDetail(detail == null ? Map.of() : detail);
        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forSubject(String subjectType, String subjectId) {
        return repository.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(subjectType, subjectId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recent() {
        return repository.findTop200ByOrderByOccurredAtDesc();
    }

    /**
     * Filtered audit search (examiner-ready). All arguments are optional; with none supplied this is
     * byte-identical to {@link #recent()}. {@code actor} is the USER-NAME filter (contains,
     * case-insensitive). {@code q} is a free-text COUNTERPARTY / text filter — it matches the event
     * summary (where counterparty names appear), the subject id and the actor — so a counterparty can
     * be found by name or reference. {@code subjectType}/{@code subjectId} scope precisely; {@code
     * eventType}/{@code actorType} narrow by kind; {@code from}/{@code to} bound the time window
     * (ISO-8601 instant or {@code yyyy-MM-dd}). Filtering is over a bounded recent window.
     */
    @Transactional(readOnly = true)
    public List<AuditEvent> search(String actor, String eventType, String actorType,
                                   String subjectType, String subjectId, String q,
                                   String from, String to, Integer limit) {
        if (blank(actor) && blank(eventType) && blank(actorType) && blank(subjectType)
                && blank(subjectId) && blank(q) && blank(from) && blank(to)) {
            return recent();
        }
        List<AuditEvent> base = (!blank(subjectType) && !blank(subjectId))
                ? repository.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(subjectType.trim(), subjectId.trim())
                : repository.findTop1000ByOrderByOccurredAtDesc();
        String actorL = lower(actor), eventL = lower(eventType), typeL = lower(actorType);
        String subTypeL = lower(subjectType), subIdL = lower(subjectId), qL = lower(q);
        Instant fromI = parseInstant(from, false), toI = parseInstant(to, true);
        int cap = (limit == null || limit <= 0) ? 200 : Math.min(limit, 1000);
        List<AuditEvent> out = new ArrayList<>();
        for (AuditEvent e : base) {
            if (actorL != null && !containsCi(e.getActor(), actorL)) continue;
            if (eventL != null && !containsCi(e.getEventType(), eventL)) continue;
            if (typeL != null && !typeL.equals(lower(e.getActorType()))) continue;
            if (subTypeL != null && !containsCi(e.getSubjectType(), subTypeL)) continue;
            if (subIdL != null && !containsCi(e.getSubjectId(), subIdL)) continue;
            if (qL != null && !(containsCi(e.getSummary(), qL) || containsCi(e.getSubjectId(), qL)
                    || containsCi(e.getActor(), qL) || containsCi(e.getEventType(), qL))) continue;
            Instant at = e.getOccurredAt();
            if (fromI != null && (at == null || at.isBefore(fromI))) continue;
            if (toI != null && (at == null || at.isAfter(toI))) continue;
            out.add(e);
            if (out.size() >= cap) break;
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return blank(s) ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsCi(String hay, String needleLower) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    /** Lenient bound parse: ISO-8601 instant, else {@code yyyy-MM-dd} (start-of-day, or end-of-day when {@code endOfDay}). */
    private static Instant parseInstant(String s, boolean endOfDay) {
        if (blank(s)) return null;
        try {
            return Instant.parse(s.trim());
        } catch (Exception ignore) {
            try {
                LocalDate d = LocalDate.parse(s.trim());
                return endOfDay ? d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
                                : d.atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception ignore2) {
                return null;   // unparseable bound is ignored, not an error
            }
        }
    }
}
