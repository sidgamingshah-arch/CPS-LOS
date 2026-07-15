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

    public record ExternalResponseRequest(String body, String from) {
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

    @GetMapping
    public List<QueryThread> list(@RequestParam(required = false) String subjectRef,
                                  @RequestParam(required = false) String addressee) {
        return queries.list(subjectRef, addressee);
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

    @PostMapping("/{ref}/external-response")
    public QueryService.View externalResponse(@PathVariable String ref,
                                              @RequestBody ExternalResponseRequest req,
                                              @RequestHeader("X-Actor") String actor) {
        return queries.externalResponse(ref, req.body(), req.from(), actor);
    }
}
