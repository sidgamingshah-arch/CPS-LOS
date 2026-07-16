package com.helix.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Real SMTP email transport (config-gated). Reached only when {@code helix.notify.transport}
 * is {@code smtp} or {@code all} — the {@link NotificationTransportRouter} decides; in the
 * default ({@code outbox}) profile this bean exists but is never invoked, so behaviour is
 * byte-identical to today.
 *
 * <p>Transmits the already-rendered {@code subject}/{@code body} to the recipients' resolved
 * email addresses via the Spring Boot autoconfigured {@link JavaMailSender} (present only when
 * {@code spring.mail.host} is set — hence the {@link ObjectProvider}, so a missing sender never
 * breaks bean wiring). Best-effort + fail-soft: a missing sender, no resolvable address, or any
 * send error returns a {@code FAILED} {@link Result} and NEVER throws — the business transaction
 * that enqueued the notification is unaffected.</p>
 */
@Component
public class SmtpTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpTransport.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final ObjectProvider<NotificationContactResolver> contacts;
    private final String from;

    public SmtpTransport(ObjectProvider<JavaMailSender> mailSender,
                         ObjectProvider<NotificationContactResolver> contacts,
                         @Value("${helix.notify.smtp.from:helix-notifications@helix.local}") String from) {
        this.mailSender = mailSender;
        this.contacts = contacts;
        this.from = from;
    }

    @Override
    public String name() {
        return "SMTP";
    }

    @Override
    public Result dispatch(Notification n) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return Result.failed("SMTP transport selected but no JavaMailSender configured (set spring.mail.host)");
        }
        NotificationContactResolver resolver = contacts.getIfAvailable();
        List<String> to = resolver != null
                ? resolver.emailsFor(n.getRecipients(), n.getRecipientRoles())
                : NotificationContactResolver.directEmails(n.getRecipients());
        if (to.isEmpty()) {
            return Result.failed(truncate("no email address resolved for recipients=" + n.getRecipients()
                    + " roles=" + n.getRecipientRoles()));
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to.toArray(String[]::new));
            msg.setSubject(n.getRenderedSubject() == null ? "" : n.getRenderedSubject());
            msg.setText(n.getRenderedBody() == null ? "" : n.getRenderedBody());
            sender.send(msg);
            return Result.sent("smtp:" + n.getId() + "->" + String.join(",", to));
        } catch (Exception e) {
            log.warn("SMTP send failed for notification {} ({})", n.getId(), e.getMessage());
            return Result.failed(truncate("SMTP send failed: " + e.getMessage()));
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 480 ? s : s.substring(0, 480);
    }
}
