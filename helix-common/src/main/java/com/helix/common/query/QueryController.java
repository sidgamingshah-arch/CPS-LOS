package com.helix.common.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Query / RFI collaboration endpoints, automatically present in every service that includes
 * helix-common (like {@code /api/audit} and {@code /api/notifications}). The gateway prefixes
 * per service, e.g. {@code POST /decision/api/queries}. Every write takes {@code X-Actor},
 * which is persisted into the audit trail (and identifies the raiser for SoD on resolve).
 */
@RestController
@RequestMapping("/api/queries")
public class QueryController {

    private final QueryService queries;

    public QueryController(QueryService queries) {
        this.queries = queries;
    }

    public record RaiseRequest(String channel, String subjectType, String subjectRef, String topic,
                               String question, String addressee, String addresseeRole, Integer slaHours,
                               String scheduleAt, Long scheduleInSeconds, Integer reminderEveryHours,
                               Integer maxReminders, List<String> recipientRoles, String jurisdiction) {
    }

    public record ReplyRequest(String body) {
    }

    public record ResolveRequest(String resolution) {
    }

    public record CancelRequest(String reason) {
    }

    public record ExternalResponseRequest(String body, String from, String token) {
    }

    @PostMapping
    public QueryService.View raise(@RequestBody RaiseRequest req,
                                   @RequestHeader("X-Actor") String actor) {
        QueryService.Raise cmd = new QueryService.Raise(req.channel(), req.subjectType(), req.subjectRef(),
                req.topic(), req.question(), req.addressee(), req.addresseeRole(), req.slaHours(),
                req.scheduleAt(), req.scheduleInSeconds(), req.reminderEveryHours(), req.maxReminders(),
                req.recipientRoles(), req.jurisdiction());
        return queries.raise(cmd, actor);
    }

    /**
     * Caller-scoped listing (Fix 2): the verified {@code X-Actor} sees only threads they raised,
     * are the named addressee of, or are directed at by a role they hold — unless they hold a
     * supervisor/admin role (then unrestricted). {@code subjectRef}/{@code addressee} narrow
     * within that visible set. X-Actor is optional here so a read never hard-fails on a missing
     * header (a blank actor simply scopes to nothing but their own — see the service).
     */
    @GetMapping
    public List<QueryThread> list(@RequestParam(required = false) String subjectRef,
                                  @RequestParam(required = false) String addressee,
                                  @RequestHeader(value = "X-Actor", required = false) String actor) {
        return queries.list(subjectRef, addressee, actor);
    }

    /**
     * Deterministic query / RFI SLA rollup for this service's own threads — open / scheduled /
     * responded / resolved / cancelled + overdue, broken down by channel. Read-only report.
     */
    @GetMapping("/sla-rollup")
    public Map<String, Object> slaRollup() {
        return queries.slaRollup();
    }

    @GetMapping("/{ref}")
    public QueryService.View get(@PathVariable String ref) {
        return queries.get(ref);
    }

    @PostMapping("/{ref}/reply")
    public QueryService.View reply(@PathVariable String ref, @RequestBody ReplyRequest req,
                                   @RequestHeader("X-Actor") String actor) {
        return queries.reply(ref, req.body(), actor);
    }

    @PostMapping("/{ref}/resolve")
    public QueryService.View resolve(@PathVariable String ref, @RequestBody(required = false) ResolveRequest req,
                                     @RequestHeader("X-Actor") String actor) {
        return queries.resolve(ref, req == null ? null : req.resolution(), actor);
    }

    @PostMapping("/{ref}/cancel")
    public QueryService.View cancel(@PathVariable String ref, @RequestBody(required = false) CancelRequest req,
                                    @RequestHeader("X-Actor") String actor) {
        return queries.cancel(ref, req == null ? null : req.reason(), actor);
    }

    /**
     * Inbound external reply through the tokenised callback (Fix 1). The one-time response token
     * may arrive as {@code ?token=} (the callback link) or in the request body; the query param
     * wins when both are present. A missing token is 401, an invalid/spent token 403.
     */
    @PostMapping("/{ref}/external-response")
    public QueryService.View externalResponse(@PathVariable String ref,
                                              @RequestBody ExternalResponseRequest req,
                                              @RequestParam(value = "token", required = false) String token,
                                              @RequestHeader("X-Actor") String actor) {
        String effectiveToken = (token != null && !token.isBlank()) ? token
                : (req == null ? null : req.token());
        return queries.externalResponse(ref, req == null ? null : req.body(),
                req == null ? null : req.from(), effectiveToken, actor);
    }
}
