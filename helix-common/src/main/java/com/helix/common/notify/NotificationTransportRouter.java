package com.helix.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The single {@code @Primary} {@link NotificationTransport} that {@link NotificationService}
 * consumes. It selects the real delivery mechanism from {@code helix.notify.transport}:
 *
 * <ul>
 *   <li>{@code outbox} (DEFAULT) — delegates straight to {@link OutboxTransport}; the persisted,
 *       examiner-visible row IS the deliverable. Byte-identical to the pre-transport behaviour
 *       (no real transmission, {@code providerRef = outbox:<id>}, {@code transport = OUTBOX}).</li>
 *   <li>{@code smtp} — {@link SmtpTransport} (JavaMail).</li>
 *   <li>{@code sms} — {@link SmsTransport} (HTTP gateway).</li>
 *   <li>{@code graph} — {@link GraphMailTransport} (Microsoft Graph {@code sendMail}, config-gated,
 *       fail-soft; token/secret never logged).</li>
 *   <li>{@code all} — both email (SMTP) and SMS; the combined {@link Result} is {@code SENT} when at
 *       least one channel delivered, recording every provider id and any per-channel failure.</li>
 * </ul>
 *
 * <p>An unknown/blank value is treated as {@code outbox} (safe default). This bean never throws:
 * a selected transport that is somehow unavailable, or that fails, is recorded as {@code FAILED}
 * — the enqueue/sweep discipline in {@link NotificationService} keeps the business transaction
 * intact regardless.</p>
 */
@Component
@Primary
public class NotificationTransportRouter implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(NotificationTransportRouter.class);

    private final OutboxTransport outbox;
    private final ObjectProvider<SmtpTransport> smtp;
    private final ObjectProvider<SmsTransport> sms;
    private final ObjectProvider<GraphMailTransport> graph;
    private final String mode;

    public NotificationTransportRouter(OutboxTransport outbox,
                                       ObjectProvider<SmtpTransport> smtp,
                                       ObjectProvider<SmsTransport> sms,
                                       ObjectProvider<GraphMailTransport> graph,
                                       @Value("${helix.notify.transport:outbox}") String mode) {
        this.outbox = outbox;
        this.smtp = smtp;
        this.sms = sms;
        this.graph = graph;
        this.mode = mode == null ? "outbox" : mode.trim().toLowerCase();
        if (!List.of("outbox", "smtp", "sms", "graph", "all").contains(this.mode)) {
            log.warn("unknown helix.notify.transport='{}' — defaulting to outbox (record-only)", mode);
        }
    }

    @Override
    public String name() {
        return switch (mode) {
            case "smtp" -> "SMTP";
            case "sms" -> "SMS";
            case "graph" -> "GRAPH";
            case "all" -> "ALL";
            default -> "OUTBOX";
        };
    }

    @Override
    public Result dispatch(Notification n) {
        return switch (mode) {
            case "smtp" -> viaSmtp(n);
            case "sms" -> viaSms(n);
            case "graph" -> viaGraph(n);
            case "all" -> combine(viaSmtp(n), viaSms(n));
            default -> outbox.dispatch(n);   // outbox / unknown — record-only, byte-identical
        };
    }

    private Result viaSmtp(Notification n) {
        SmtpTransport t = smtp.getIfAvailable();
        return t != null ? t.dispatch(n) : Result.failed("SMTP transport unavailable");
    }

    private Result viaSms(Notification n) {
        SmsTransport t = sms.getIfAvailable();
        return t != null ? t.dispatch(n) : Result.failed("SMS transport unavailable");
    }

    private Result viaGraph(Notification n) {
        GraphMailTransport t = graph.getIfAvailable();
        return t != null ? t.dispatch(n) : Result.failed("Graph mail transport unavailable");
    }

    /** {@code all}-mode merge: SENT if either channel delivered; every provider id / error kept. */
    private static Result combine(Result email, Result sms) {
        boolean sent = "SENT".equals(email.status()) || "SENT".equals(sms.status());
        List<String> refs = new ArrayList<>();
        if (email.providerRef() != null) refs.add(email.providerRef());
        if (sms.providerRef() != null) refs.add(sms.providerRef());
        List<String> errs = new ArrayList<>();
        if (email.failureReason() != null) errs.add("email:" + email.failureReason());
        if (sms.failureReason() != null) errs.add("sms:" + sms.failureReason());
        String providerRef = refs.isEmpty() ? null : String.join(" | ", refs);
        String failureReason = errs.isEmpty() ? null : truncate(String.join(" | ", errs));
        return new Result(sent ? "SENT" : "FAILED", providerRef, failureReason);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 480 ? s : s.substring(0, 480);
    }
}
