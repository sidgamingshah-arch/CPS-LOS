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
        return rulePacks.findFirstByJurisdictionAndTypeAndActiveTrueOrderByVersionDesc(jurisdiction, type)
                .orElseThrow(() -> ApiException.notFound(
                        "No active %s rule pack for %s".formatted(type, jurisdiction)));
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
     * Dual sign-off (PRD §10/§11). A pack only becomes active once BOTH policy and
     * model-risk have signed. Activating supersedes the prior active version.
     */
    @Transactional
    public RulePack signOff(Long id, String control, String actor) {
        RulePack pack = getPack(id);
        Instant now = Instant.now();
        switch (control.toLowerCase()) {
            case "policy" -> {
                pack.setPolicySignedOffBy(actor);
                pack.setPolicySignedOffAt(now);
            }
            case "model-risk", "modelrisk" -> {
                pack.setModelRiskSignedOffBy(actor);
                pack.setModelRiskSignedOffAt(now);
            }
            default -> throw ApiException.badRequest("control must be 'policy' or 'model-risk'");
        }
        audit.human(actor, "RULEPACK_SIGNOFF", "RulePack", String.valueOf(id),
                "%s sign-off recorded for pack %s v%d".formatted(control, pack.getCode(), pack.getVersion()),
                Map.of("control", control, "fullySignedOff", pack.isFullySignedOff()));

        if (pack.isFullySignedOff() && !pack.isActive()) {
            rulePacks.findByJurisdictionAndTypeOrderByVersionDesc(pack.getJurisdiction(), pack.getType())
                    .forEach(p -> p.setActive(false));
            pack.setActive(true);
            audit.engine("RULEPACK_ACTIVATED", "RulePack", String.valueOf(id),
                    "Pack %s v%d activated after dual sign-off".formatted(pack.getCode(), pack.getVersion()),
                    Map.of("jurisdiction", pack.getJurisdiction(), "type", pack.getType()));
        }
        return rulePacks.save(pack);
    }
}
