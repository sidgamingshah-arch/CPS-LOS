package com.helix.common.web;

import com.helix.common.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectProvider<AuditService> audit;

    public GlobalExceptionHandler(ObjectProvider<AuditService> audit) {
        this.audit = audit;
    }

    public record ApiError(Instant timestamp, int status, String error, String message, Object details) {
        static ApiError of(HttpStatus status, String message, Object details) {
            return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, details);
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        // A fail-closed posture deny (G7) is stamped HERE — after the caller's transaction has
        // rolled back and released the single SQLite connection — via an optional AuditService,
        // wrapped so an audit failure can never mask the 403.
        if (ex.isPostureDeny()) {
            AuditService svc = audit.getIfAvailable();
            if (svc != null) {
                try {
                    svc.engine("RBAC_POSTURE_DENY", "Actor", ex.getPostureActor(),
                            "Denied '" + ex.getPostureActor() + "' — directory outage under FAIL-CLOSED posture",
                            Map.of("posture", "FAIL_CLOSED", "reason", "DIRECTORY_OUTAGE",
                                    "actor", ex.getPostureActor() == null ? "" : ex.getPostureActor()));
                } catch (Exception auditErr) {
                    log.warn("could not stamp RBAC_POSTURE_DENY audit ({})", auditErr.getMessage());
                }
            }
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus(), ex.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, fe ->
                        fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(), (a, b) -> a, LinkedHashMap::new));
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "Validation failed", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage(), null));
    }

    /** A missing required header (e.g. X-Actor on a write) is the caller's error, not a 500. */
    @ExceptionHandler({MissingRequestHeaderException.class, ServletRequestBindingException.class})
    public ResponseEntity<ApiError> handleMissingHeader(ServletRequestBindingException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage(), null));
    }

    /**
     * Last-resort 500. The raw exception message can carry internals (hostnames,
     * connection strings, partial upstream payloads), so the client gets a generic
     * message + a correlation id; the full stack trace goes to the server log under
     * the same id. Intentional client-facing messages travel via {@link ApiException}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception [{}]", correlationId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal error — reference " + correlationId, null));
    }
}
