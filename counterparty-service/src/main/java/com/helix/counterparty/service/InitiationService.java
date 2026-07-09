package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.util.References;
import com.helix.common.web.ApiException;
import com.helix.counterparty.client.ConfigMasterClient;
import com.helix.counterparty.client.ConfigMasterClient.MasterRecordDto;
import com.helix.counterparty.dto.InitiationDtos.CreateProspectRequest;
import com.helix.counterparty.dto.InitiationDtos.CreationSummary;
import com.helix.counterparty.dto.InitiationDtos.DedupMatch;
import com.helix.counterparty.dto.InitiationDtos.DedupResult;
import com.helix.counterparty.dto.InitiationDtos.NegativeHit;
import com.helix.counterparty.dto.InitiationDtos.NegativeResult;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.ExternalCheck;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.ExternalCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Credit-initiation lifecycle (PRD Stage 1-3): create a prospect, run the
 * configurable deduplication check and negative check, assemble the obligor
 * creation summary, capture the RM decision (proceed/drop), and approve a prospect
 * into an obligor. Auto-cleanup discards stale drafts per the configurable master.
 */
@Service
public class InitiationService {

    private static final Set<String> NAME_STOPWORDS = Set.of(
            "ltd", "limited", "pvt", "private", "llc", "llp", "inc", "co", "company", "corp",
            "plc", "the", "and", "spv", "holdings", "holding", "group");

    /**
     * Identifier accessors keyed by the field names DEDUP_RULES.identifierFields may list. The
     * Counterparty entity carries only registrationNo today; adding pan/passport/gstin is a
     * one-line accessor per new column. A configured field with no accessor is ignored (not an error).
     */
    private static final Map<String, Function<Counterparty, String>> ID_ACCESSORS = Map.of(
            "registrationNo", Counterparty::getRegistrationNo);

    private final CounterpartyRepository repository;
    private final ExternalCheckRepository checks;
    private final ConfigMasterClient config;
    private final AuditService audit;

    public InitiationService(CounterpartyRepository repository, ExternalCheckRepository checks,
                             ConfigMasterClient config, AuditService audit) {
        this.repository = repository;
        this.checks = checks;
        this.config = config;
        this.audit = audit;
    }

    @Transactional
    public Counterparty createProspect(CreateProspectRequest req, String actor) {
        Counterparty cp = new Counterparty();
        cp.setReference(References.forCounterparty());
        cp.setLegalName(req.legalName());
        cp.setLegalForm(req.legalForm());
        cp.setRegistrationNo(req.registrationNo());
        cp.setJurisdiction(req.jurisdiction());
        cp.setSegment(req.segment());
        cp.setSector(req.sector());
        cp.setCountry(req.country());
        cp.setIndustry(req.industry());
        cp.setSubIndustry(req.subIndustry());
        cp.setBusinessSegment(req.businessSegment());
        cp.setSubSegment(req.subSegment());
        cp.setPep(req.pep());
        cp.setAdverseMedia(req.adverseMedia());
        cp.setHighRiskJurisdiction(req.highRiskJurisdiction());
        cp.setComplexOwnership(req.complexOwnership());
        cp.setRecordType("PROSPECT");
        cp.setLifecycleStatus("DRAFT");
        cp.setBorrowerType(req.borrowerType() == null ? "NTB" : req.borrowerType().toUpperCase());
        cp.setRmId(actor);                       // default RM = creator (automated ownership resolution)
        cp.setKycStatus("NOT_STARTED");
        cp.setCddTier("STANDARD");
        cp.setLastActivityAt(Instant.now());
        Counterparty saved = repository.save(cp);
        audit.human(actor, "PROSPECT_CREATED", "Counterparty", saved.getReference(),
                "Initiated prospect %s (%s, %s)".formatted(saved.getLegalName(), saved.getBorrowerType(), saved.getSegment()),
                Map.of("borrowerType", saved.getBorrowerType(), "defaultRm", actor));
        return saved;
    }

    // ---------------------------------------------------------- deduplication

    @Transactional(readOnly = true)
    public DedupResult dedupCheck(Long prospectId) {
        Counterparty subject = get(prospectId);
        Map<String, Object> rules = config.dedupRules();
        double threshold = ((Number) rules.getOrDefault("nameMatchThreshold", 0.82)).doubleValue();
        String strategy = String.valueOf(rules.getOrDefault("strategy", "NAME_AND_IDENTIFIER"));
        List<String> idFields = ((List<?>) rules.getOrDefault("identifierFields", List.of("registrationNo")))
                .stream().map(String::valueOf).toList();
        boolean andCombine = "AND".equalsIgnoreCase(String.valueOf(rules.getOrDefault("combineWith", "OR")));
        Set<String> subjectTokens = nameTokens(subject.getLegalName());

        List<DedupMatch> matches = new ArrayList<>();
        for (Counterparty c : repository.findAll()) {
            if (c.getId().equals(prospectId) || "DISCARDED".equals(c.getLifecycleStatus())) {
                continue;
            }
            boolean idMatch = identifierMatch(subject, c, idFields, andCombine);
            double nameScore = jaccard(subjectTokens, nameTokens(c.getLegalName()));
            boolean nameMatch = nameScore >= threshold;
            if (!idMatch && !nameMatch) {
                continue;
            }
            String matchType = idMatch && nameMatch ? "NAME_AND_IDENTIFIER" : idMatch ? "IDENTIFIER" : "NAME";
            double score = idMatch ? Math.max(1.0, nameScore) : nameScore;
            matches.add(new DedupMatch(c.getId(), c.getReference(), c.getLegalName(), c.getRmId(),
                    c.getSegment(), c.getKycStatus(), c.getLifecycleStatus(), matchType,
                    Math.round(score * 1000.0) / 1000.0,
                    c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString()));
        }
        matches.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new DedupResult(prospectId, strategy, idFields, matches.size(), matches);
    }

    /**
     * Match on the identifiers DEDUP_RULES configures (identifierFields + combineWith). Today the
     * Counterparty entity carries only registrationNo, so ID_ACCESSORS registers that one. The blank
     * guard is load-bearing: two prospects that both lack an identifier must never match on it.
     */
    private boolean identifierMatch(Counterparty a, Counterparty b, List<String> fields, boolean andCombine) {
        boolean any = false, all = true, sawField = false;
        for (String f : fields) {
            Function<Counterparty, String> acc = ID_ACCESSORS.get(f);
            if (acc == null) {
                continue;   // configured identifier the entity does not (yet) carry — ignore
            }
            String av = acc.apply(a);
            if (!notBlank(av)) {
                all = false;
                continue;   // blank on the subject side cannot match
            }
            sawField = true;
            boolean eq = av.equalsIgnoreCase(acc.apply(b));
            any |= eq;
            all &= eq;
        }
        return sawField && (andCombine ? all : any);
    }

    private Set<String> nameTokens(String name) {
        if (name == null) return Set.of();
        return Arrays.stream(name.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                .filter(t -> t.length() > 1 && !NAME_STOPWORDS.contains(t))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    // ------------------------------------------------------------ negative check

    @Transactional(readOnly = true)
    public NegativeResult negativeCheck(Long prospectId) {
        Counterparty cp = get(prospectId);
        return negativeCheck(cp.getLegalName(), cp.getCountry());
    }

    @Transactional(readOnly = true)
    public NegativeResult negativeCheck(String name, String country) {
        List<NegativeHit> hits = new ArrayList<>();
        Set<String> nameTokens = nameTokens(name);
        for (MasterRecordDto r : config.negativeList()) {
            Map<String, Object> p = r.payload();
            String type = String.valueOf(p.getOrDefault("type", ""));
            String value = String.valueOf(p.getOrDefault("value", ""));
            String reason = String.valueOf(p.getOrDefault("reason", ""));
            if ("COUNTRY".equalsIgnoreCase(type) && value.equalsIgnoreCase(country)) {
                hits.add(new NegativeHit(type, value, reason, "country=" + country));
            } else if ("ENTITY".equalsIgnoreCase(type)) {
                double s = jaccard(nameTokens, nameTokens(value));
                if (s >= 0.6 || (name != null && name.toLowerCase().contains(value.toLowerCase()))) {
                    hits.add(new NegativeHit(type, value, reason, "name~" + value));
                }
            }
        }
        return new NegativeResult(!hits.isEmpty(), hits);
    }

    // -------------------------------------------------------- creation summary

    @Transactional(readOnly = true)
    public CreationSummary creationSummary(Long prospectId) {
        Counterparty cp = get(prospectId);
        DedupResult dedup = dedupCheck(prospectId);
        NegativeResult negative = negativeCheck(prospectId);
        List<Map<String, Object>> extView = checks.findByCounterpartyIdOrderByCheckTypeAsc(prospectId).stream()
                .map(this::checkView).toList();
        Map<String, Object> groupExposure = cp.getGroupId() == null ? Map.of("tagged", false)
                : Map.of("tagged", true, "groupId", cp.getGroupId(),
                "members", repository.findByGroupId(cp.getGroupId()).size());

        Map<String, Object> industry = config.listActive("INDUSTRY_BENCHMARK").stream()
                .filter(r -> r.recordKey().equalsIgnoreCase(cp.getSector()) || r.recordKey().equalsIgnoreCase(cp.getIndustry()))
                .findFirst().map(MasterRecordDto::payload).orElse(Map.of());

        List<String> blockers = new ArrayList<>();
        if (negative.hit()) blockers.add("On negative list: " + negative.matches().size() + " match(es)");
        long openHits = checks.findByCounterpartyIdOrderByCheckTypeAsc(prospectId).stream()
                .filter(c -> "HIT".equals(c.getStatus())).count();
        if (openHits > 0) blockers.add(openHits + " external check HIT(s) require disposition");
        if (dedup.matchCount() > 0) blockers.add(dedup.matchCount() + " possible duplicate(s) — confirm linkage");

        return new CreationSummary(prospectId, cp.getLegalName(), cp.getRecordType(), cp.getLifecycleStatus(),
                dedup, negative, extView, groupExposure, industry, blockers);
    }

    private Map<String, Object> checkView(ExternalCheck c) {
        return Map.of("checkType", c.getCheckType(), "status", c.getStatus(), "source", c.getSourceSystem(),
                "entityType", c.getEntityType(), "entityName", c.getEntityName(),
                "lastFetchedAt", String.valueOf(c.getLastFetchedAt()));
    }

    // -------------------------------------------------------------- decisions

    @Transactional
    public Counterparty decide(Long prospectId, boolean proceed, String reason, String actor) {
        Counterparty cp = get(prospectId);
        cp.setLastActivityAt(Instant.now());
        if (!proceed) {
            cp.setLifecycleStatus("DROPPED");
            cp.setDroppedReason(reason);
            audit.human(actor, "PROSPECT_DROPPED", "Counterparty", cp.getReference(),
                    "Lead dropped: " + reason, Map.of("reason", String.valueOf(reason)));
        } else {
            audit.human(actor, "PROSPECT_PROCEED", "Counterparty", cp.getReference(),
                    "RM decided to proceed with obligor creation", Map.of());
        }
        return repository.save(cp);
    }

    @Transactional
    public Counterparty approveObligor(Long prospectId, String actor) {
        Counterparty cp = get(prospectId);
        if ("DROPPED".equals(cp.getLifecycleStatus()) || "DISCARDED".equals(cp.getLifecycleStatus())) {
            throw ApiException.conflict("Cannot approve a dropped/discarded prospect");
        }
        NegativeResult neg = negativeCheck(prospectId);
        if (neg.hit()) {
            throw ApiException.conflict("Cannot create obligor: subject is on the negative list");
        }
        cp.setRecordType("OBLIGOR");
        cp.setLifecycleStatus("ACTIVE");
        cp.setExternalId("CRM-" + cp.getReference());     // mapping to CRM/core obligor id
        cp.setLastActivityAt(Instant.now());
        Counterparty saved = repository.save(cp);
        audit.human(actor, "OBLIGOR_CREATED", "Counterparty", saved.getReference(),
                "Prospect approved into obligor %s".formatted(saved.getLegalName()),
                Map.of("externalId", saved.getExternalId(), "rm", String.valueOf(saved.getRmId())));
        // Notification (template-driven, logged as a SYSTEM action — no SMTP integration here).
        audit.engine("NOTIFICATION_SENT", "Counterparty", saved.getReference(),
                "EMAIL_TEMPLATE OBLIGOR_APPROVED -> RM %s, Group RM".formatted(saved.getRmId()),
                Map.of("template", "OBLIGOR_APPROVED", "rm", String.valueOf(saved.getRmId())));
        return saved;
    }

    /** Discards prospects left in DRAFT beyond the configurable cleanup window. */
    @Transactional
    public Map<String, Object> autoCleanup(String actor) {
        int months = config.draftCleanupMonths();
        Instant cutoff = Instant.now().minus((long) months * 30, ChronoUnit.DAYS);
        List<Counterparty> stale = repository.findByRecordTypeAndLifecycleStatus("PROSPECT", "DRAFT").stream()
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isBefore(cutoff))
                .toList();
        stale.forEach(c -> {
            c.setLifecycleStatus("DISCARDED");
            repository.save(c);
        });
        audit.engine("PROSPECT_AUTO_CLEANUP", "Counterparty", "batch",
                "Discarded %d draft prospect(s) older than %d months".formatted(stale.size(), months),
                Map.of("discarded", stale.size(), "months", months));
        return Map.of("cleanupMonths", months, "discarded", stale.size(),
                "discardedRefs", stale.stream().map(Counterparty::getReference).toList());
    }

    @Transactional(readOnly = true)
    public Counterparty get(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("No counterparty: " + id));
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
