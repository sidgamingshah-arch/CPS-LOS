package com.helix.config.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.config.entity.JurisdictionProfile;
import com.helix.config.entity.RulePack;
import com.helix.config.repo.JurisdictionProfileRepository;
import com.helix.config.repo.RulePackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class ConfigService {

    private final JurisdictionProfileRepository profiles;
    private final RulePackRepository rulePacks;
    private final AuditService audit;

    public ConfigService(JurisdictionProfileRepository profiles, RulePackRepository rulePacks, AuditService audit) {
        this.profiles = profiles;
        this.rulePacks = rulePacks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<JurisdictionProfile> listProfiles() {
        return profiles.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public JurisdictionProfile getProfile(String code) {
        return profiles.findById(code)
                .orElseThrow(() -> ApiException.notFound("No jurisdiction profile: " + code));
    }

    @Transactional(readOnly = true)
    public RulePack activePack(String jurisdiction, String type) {
        LocalDate today = LocalDate.now();
        // EFFECTIVE pack = highest-version ACTIVE pack whose effectiveFrom has arrived (G6). A
        // future-dated (activated) pack is skipped until its date; null effectiveFrom = always-effective.
        return rulePacks.findByJurisdictionAndTypeAndActiveTrueOrderByVersionDesc(jurisdiction, type)
                .stream()
                .filter(p -> p.getEffectiveFrom() == null || !p.getEffectiveFrom().isAfter(today))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "No effective %s rule pack for %s".formatted(type, jurisdiction)));
    }

    @Transactional(readOnly = true)
    public List<RulePack> packHistory(String jurisdiction, String type) {
        return rulePacks.findByJurisdictionAndTypeOrderByVersionDesc(jurisdiction, type);
    }

    @Transactional(readOnly = true)
    public RulePack getPack(Long id) {
        return rulePacks.findById(id).orElseThrow(() -> ApiException.notFound("No rule pack: " + id));
    }

    /**
     * G6 — author a NEW draft version. version = max+1, active=false, UNSIGNED. Payload is
     * free-form JSON (downstream engines fallback-tolerate missing keys); the draft only takes
     * effect once it is dual-signed (by two humans, neither the author) and its date arrives.
     */
    @Transactional
    public RulePack createDraft(String jurisdiction, String type, String code,
                                Map<String, Object> payload, LocalDate effectiveFrom, String author) {
        if (jurisdiction == null || jurisdiction.isBlank()) throw ApiException.badRequest("jurisdiction is required");
        if (type == null || type.isBlank()) throw ApiException.badRequest("type is required");
        if (author == null || author.isBlank()) throw ApiException.badRequest("author (X-Actor) is required");

        int nextVersion = rulePacks.findByJurisdictionAndTypeOrderByVersionDesc(jurisdiction, type)
                .stream().mapToInt(RulePack::getVersion).max().orElse(0) + 1;
        String packCode = (code == null || code.isBlank())
                ? "%s_%s_v%d".formatted(jurisdiction.toLowerCase().replace('-', '_'), type.toLowerCase(), nextVersion)
                : code;
        if (rulePacks.existsByCodeAndVersion(packCode, nextVersion)) {
            throw ApiException.conflict("Rule pack %s v%d already exists".formatted(packCode, nextVersion));
        }

        RulePack p = new RulePack();
        p.setCode(packCode);
        p.setType(type);
        p.setJurisdiction(jurisdiction);
        p.setVersion(nextVersion);
        p.setEffectiveFrom(effectiveFrom == null ? LocalDate.now() : effectiveFrom);
        p.setActive(false);
        p.setPayload(payload == null ? Map.of() : payload);
        p.setCreatedBy(author);
        RulePack saved = rulePacks.save(p);
        audit.human(author, "RULEPACK_DRAFTED", "RulePack", String.valueOf(saved.getId()),
                "Draft %s %s v%d authored (unsigned, inactive)".formatted(jurisdiction, type, nextVersion),
                Map.of("jurisdiction", jurisdiction, "type", type, "version", nextVersion,
                        "effectiveFrom", String.valueOf(saved.getEffectiveFrom()), "code", packCode));
        return saved;
    }

    /** G6 — checker queue: unsigned, inactive drafts awaiting sign-off. */
    @Transactional(readOnly = true)
    public List<RulePack> draftQueue() {
        return rulePacks.findByActiveFalseOrderByJurisdictionAscTypeAscVersionDesc()
                .stream().filter(p -> !p.isFullySignedOff()).toList();
    }

    /**
     * Dual sign-off (PRD §10/§11). A pack only becomes active once BOTH policy and
     * model-risk have signed. Activating supersedes the prior active version.
     */
    @Transactional
    public RulePack signOff(Long id, String control, String actor) {
        RulePack pack = getPack(id);
        Instant now = Instant.now();
        // G6: maker-checker — the pack AUTHOR cannot sign it off (layered on policy != model-risk).
        if (actor != null && actor.equalsIgnoreCase(pack.getCreatedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Sign-off actor '" + actor + "' authored this pack — the author cannot sign off "
                            + "their own rule pack (maker-checker)");
        }
        switch (control.toLowerCase()) {
            case "policy" -> {
                if (actor.equalsIgnoreCase(pack.getModelRiskSignedOffBy())) {
                    throw ApiException.forbiddenAutonomy(
                            "Policy sign-off actor '" + actor + "' cannot also be the model-risk signer — dual control requires two distinct humans");
                }
                pack.setPolicySignedOffBy(actor);
                pack.setPolicySignedOffAt(now);
            }
            case "model-risk", "modelrisk" -> {
                if (actor.equalsIgnoreCase(pack.getPolicySignedOffBy())) {
                    throw ApiException.forbiddenAutonomy(
                            "Model-risk sign-off actor '" + actor + "' cannot also be the policy signer — dual control requires two distinct humans");
                }
                pack.setModelRiskSignedOffBy(actor);
                pack.setModelRiskSignedOffAt(now);
            }
            default -> throw ApiException.badRequest("control must be 'policy' or 'model-risk'");
        }
        audit.human(actor, "RULEPACK_SIGNOFF", "RulePack", String.valueOf(id),
                "%s sign-off recorded for pack %s v%d".formatted(control, pack.getCode(), pack.getVersion()),
                Map.of("control", control, "fullySignedOff", pack.isFullySignedOff()));

        if (pack.isFullySignedOff() && !pack.isActive()) {
            pack.setActive(true);
            // G6: effective-date-aware supersession. An IMMEDIATE pack (effectiveFrom <= today)
            // supersedes all prior versions now (prior behaviour). A FUTURE-dated pack is activated
            // but left alongside the currently-effective version; the read-time resolver (activePack)
            // serves the current one until the date arrives.
            boolean effectiveNow = pack.getEffectiveFrom() == null
                    || !pack.getEffectiveFrom().isAfter(LocalDate.now());
            if (effectiveNow) {
                rulePacks.findByJurisdictionAndTypeOrderByVersionDesc(pack.getJurisdiction(), pack.getType())
                        .stream().filter(p -> !p.getId().equals(pack.getId()))
                        .forEach(p -> p.setActive(false));
            }
            audit.engine("RULEPACK_ACTIVATED", "RulePack", String.valueOf(id),
                    "Pack %s v%d activated after dual sign-off%s".formatted(pack.getCode(), pack.getVersion(),
                            effectiveNow ? "" : " (future-dated — effective " + pack.getEffectiveFrom() + ")"),
                    Map.of("jurisdiction", pack.getJurisdiction(), "type", pack.getType(),
                            "effectiveNow", effectiveNow, "effectiveFrom", String.valueOf(pack.getEffectiveFrom())));
        }
        return rulePacks.save(pack);
    }
}
