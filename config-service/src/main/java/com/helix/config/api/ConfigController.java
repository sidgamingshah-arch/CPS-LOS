package com.helix.config.api;

import com.helix.config.entity.JurisdictionProfile;
import com.helix.config.entity.RulePack;
import com.helix.config.service.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/jurisdictions")
    public List<JurisdictionProfile> jurisdictions() {
        return configService.listProfiles();
    }

    @GetMapping("/jurisdictions/{code}")
    public JurisdictionProfile jurisdiction(@PathVariable String code) {
        return configService.getProfile(code);
    }

    /** Fetch the active rule pack of a given type for a jurisdiction (consumed by other services). */
    @GetMapping("/rulepacks")
    public RulePack activePack(@RequestParam String jurisdiction, @RequestParam String type) {
        return configService.activePack(jurisdiction, type);
    }

    @GetMapping("/rulepacks/history")
    public List<RulePack> history(@RequestParam String jurisdiction, @RequestParam String type) {
        return configService.packHistory(jurisdiction, type);
    }

    @GetMapping("/rulepacks/{id}")
    public RulePack pack(@PathVariable Long id) {
        return configService.getPack(id);
    }

    @PostMapping("/rulepacks/{id}/signoff")
    public RulePack signOff(@PathVariable Long id,
                            @RequestParam String control,
                            @RequestHeader(value = "X-Actor", defaultValue = "policy.officer") String actor) {
        return configService.signOff(id, control, actor);
    }

    /** G6 — propose a NEW draft rule-pack version (maker). effectiveFrom optional (defaults today). */
    @PostMapping("/rulepacks")
    public RulePack create(@RequestBody CreateRulePackRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "config.author") String actor) {
        return configService.createDraft(req.jurisdiction(), req.type(), req.code(),
                req.payload(), req.effectiveFrom(), actor, req.rationale());
    }

    /** G6 — checker queue: unsigned, inactive drafts awaiting sign-off. */
    @GetMapping("/rulepacks/drafts")
    public List<RulePack> drafts() {
        return configService.draftQueue();
    }

    // rationale is optional (older callers omit it → null).
    public record CreateRulePackRequest(String jurisdiction, String type, String code,
                                        Map<String, Object> payload, LocalDate effectiveFrom,
                                        String rationale) {}
}
