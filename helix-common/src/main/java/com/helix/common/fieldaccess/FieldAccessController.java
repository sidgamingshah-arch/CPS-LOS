package com.helix.common.fieldaccess;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field-level access read + enforcement endpoint, automatically present in every service that
 * includes helix-common (like {@code /api/audit}, {@code /api/notifications} and
 * {@code /api/field-policy}). A screen fetches its form+role access map once and applies
 * hide/read-only client-side for convenience — {@link FieldAccessService#enforce} is the
 * authoritative gate the client cannot bypass.
 *
 * <p>{@link FieldAccessService} is a conditional bean (present only when
 * {@code helix.config-service.base-url} is set), so this controller reads it via
 * {@link ObjectProvider} and DEFAULT-PERMITS (full access / body unchanged) when the bean is
 * absent — fail-open.</p>
 */
@RestController
@RequestMapping("/api/field-access")
public class FieldAccessController {

    private final ObjectProvider<FieldAccessService> service;

    public FieldAccessController(ObjectProvider<FieldAccessService> service) {
        this.service = service;
    }

    /**
     * {@code {field: access}} map for a form + role. Empty when unmapped, when the role is
     * omitted, or when the service is absent (fail-open / full access).
     */
    @GetMapping("/{formKey}")
    public Map<String, Object> get(@PathVariable String formKey,
                                   @RequestParam(value = "role", required = false) String role) {
        FieldAccessService svc = service.getIfAvailable();
        Map<String, String> access = (svc == null || role == null || role.isBlank())
                ? Map.of() : svc.accessFor(formKey, role);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formKey", formKey);
        out.put("role", role);
        out.put("fields", access);
        return out;
    }

    /**
     * Enforce the role's field-access policy over a submitted field map, returning the allowed
     * (writable) subset under {@code allowed}. Fail-open: an absent service (or an unmapped
     * form/role) returns the body unchanged. A HIDDEN field carrying a real value -> 403
     * ({@link FieldAccessService#enforce}).
     */
    @PostMapping("/{formKey}/enforce")
    public Map<String, Object> enforce(@PathVariable String formKey,
                                       @RequestParam(value = "role", required = false) String role,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> submitted = body == null ? Map.of() : body;
        FieldAccessService svc = service.getIfAvailable();
        Map<String, Object> allowed = (svc == null || role == null || role.isBlank())
                ? new LinkedHashMap<>(submitted)
                : svc.enforce(formKey, role, submitted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formKey", formKey);
        out.put("role", role);
        out.put("allowed", allowed);
        return out;
    }
}
