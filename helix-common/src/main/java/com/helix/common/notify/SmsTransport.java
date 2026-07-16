package com.helix.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real SMS transport (config-gated) over the existing {@link RestClient}. Reached only when
 * {@code helix.notify.transport} is {@code sms} or {@code all} — the
 * {@link NotificationTransportRouter} decides; in the default ({@code outbox}) profile this
 * bean exists but is never invoked, so behaviour is byte-identical to today.
 *
 * <p>POSTs the rendered message to a config-driven HTTP SMS gateway
 * ({@code helix.notify.sms.gateway-url}, optional {@code helix.notify.sms.api-key} header) with a
 * JSON body {@code {to, subject, message}} and records the provider's returned id. Best-effort +
 * fail-soft: no gateway URL, no resolvable phone, or any HTTP error returns a {@code FAILED}
 * {@link Result} and NEVER throws — the business transaction is unaffected.</p>
 */
@Component
public class SmsTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(SmsTransport.class);

    private final ObjectProvider<NotificationContactResolver> contacts;
    private final String gatewayUrl;
    private final String apiKey;
    private final String apiKeyHeader;
    private volatile RestClient client;

    public SmsTransport(ObjectProvider<NotificationContactResolver> contacts,
                        @Value("${helix.notify.sms.gateway-url:}") String gatewayUrl,
                        @Value("${helix.notify.sms.api-key:}") String apiKey,
                        @Value("${helix.notify.sms.api-key-header:X-Api-Key}") String apiKeyHeader) {
        this.contacts = contacts;
        this.gatewayUrl = gatewayUrl;
        this.apiKey = apiKey;
        this.apiKeyHeader = apiKeyHeader;
    }

    @Override
    public String name() {
        return "SMS";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result dispatch(Notification n) {
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            return Result.failed("SMS transport selected but no gateway configured (set helix.notify.sms.gateway-url)");
        }
        NotificationContactResolver resolver = contacts.getIfAvailable();
        List<String> to = resolver != null
                ? resolver.phonesFor(n.getRecipients(), n.getRecipientRoles())
                : NotificationContactResolver.directPhones(n.getRecipients());
        if (to.isEmpty()) {
            return Result.failed(truncate("no phone number resolved for recipients=" + n.getRecipients()
                    + " roles=" + n.getRecipientRoles()));
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("to", to);
            payload.put("subject", n.getRenderedSubject());
            payload.put("message", n.getRenderedBody());
            payload.put("eventType", n.getEventType());
            Map<String, Object> resp = client().post()
                    .headers(h -> {
                        if (apiKey != null && !apiKey.isBlank()) h.add(apiKeyHeader, apiKey);
                    })
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            String providerId = resp != null && resp.get("id") != null
                    ? String.valueOf(resp.get("id")) : ("sms:" + n.getId());
            return Result.sent("sms:" + providerId + "->" + String.join(",", to));
        } catch (Exception e) {
            log.warn("SMS send failed for notification {} ({})", n.getId(), e.getMessage());
            return Result.failed(truncate("SMS send failed: " + e.getMessage()));
        }
    }

    private RestClient client() {
        RestClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = RestClient.builder().baseUrl(gatewayUrl).build();
                    client = c;
                }
            }
        }
        return c;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 480 ? s : s.substring(0, 480);
    }
}
