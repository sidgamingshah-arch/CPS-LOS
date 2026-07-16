package com.helix.common.fieldpolicy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Field-policy read endpoint, automatically present in every service that includes helix-common
 * (like {@code /api/audit} and {@code /api/notifications}). A screen fetches its form's specs
 * once and applies the label/help overrides + conditional visibility/required client-side for
 * convenience — server-side {@link FieldPolicyService#enforce} remains the authoritative gate.
 *
 * <p>{@link FieldPolicyService} is a conditional bean (present only when
 * {@code helix.config-service.base-url} is set), so this controller reads it via
 * {@link ObjectProvider} and returns an empty field list when the bean is absent (fail-open).</p>
 */
@RestController
@RequestMapping("/api/field-policy")
public class FieldPolicyController {

    private final ObjectProvider<FieldPolicyService> service;

    public FieldPolicyController(ObjectProvider<FieldPolicyService> service) {
        this.service = service;
    }

    @GetMapping("/{formKey}")
    public Map<String, Object> get(@PathVariable String formKey) {
        FieldPolicyService svc = service.getIfAvailable();
        List<Map<String, Object>> fields = svc == null ? List.of() : svc.specs(formKey);
        return Map.of("formKey", formKey, "fields", fields);
    }
}
