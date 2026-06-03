package com.helix.copilot.api;

import com.helix.copilot.dto.Dtos.AskRequest;
import com.helix.copilot.dto.Dtos.CopilotAnswer;
import com.helix.copilot.service.CopilotService;
import com.helix.copilot.service.PersonaScope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

    private final CopilotService copilot;

    public CopilotController(CopilotService copilot) {
        this.copilot = copilot;
    }

    /** The persona is the named actor (X-Actor) — its role determines the data scope. */
    @PostMapping("/ask")
    public CopilotAnswer ask(@Valid @RequestBody AskRequest req,
                             @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return copilot.ask(actor, req);
    }

    /** What a given persona is allowed to ask about (RBAC/ABAC scope). */
    @GetMapping("/scope")
    public Map<String, Object> scope(@RequestParam(defaultValue = "rm.user") String persona) {
        PersonaScope.Role role = PersonaScope.roleOf(persona);
        List<String> scope = PersonaScope.scopeOf(role);
        return Map.of("persona", persona, "role", role.name(), "scope", scope);
    }
}
