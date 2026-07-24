package com.helix.common.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Graph email transport (config-gated, DEFAULT OFF). Reached only when
 * {@code helix.notify.transport=graph} — the {@link NotificationTransportRouter} decides; in the
 * default ({@code outbox}) profile this bean exists but is never invoked, so behaviour is
 * byte-identical to today. Mirrors {@link SmtpTransport} (recipient resolution + rendered
 * subject/body) but delivers through the Graph REST API directly over {@link RestClient} — no
 * heavy Graph SDK dependency, exactly like {@link com.helix.common.coedit.GraphCoEditProvider}.
 *
 * <p>Real Graph sequence per send:
 * <ol>
 *   <li><b>token</b> — client-credentials OAuth2 grant against the AAD token endpoint
 *       ({@code POST {authority}/{tenant}/oauth2/v2.0/token}), scope {@code .default}.</li>
 *   <li><b>sendMail</b> — {@code POST {graph}/users/{sender}/sendMail} with the rendered subject,
 *       body and resolved recipient addresses (Graph returns {@code 202 Accepted}).</li>
 * </ol>
 *
 * <p>Wire it via the environment (all under {@code helix.notify.graph.*}):
 * <pre>
 *   HELIX_NOTIFY_TRANSPORT=graph
 *   HELIX_NOTIFY_GRAPH_TENANT_ID=&lt;aad-tenant-guid&gt;
 *   HELIX_NOTIFY_GRAPH_CLIENT_ID=&lt;app-registration-client-id&gt;
 *   HELIX_NOTIFY_GRAPH_CLIENT_SECRET=&lt;client-secret&gt;
 *   HELIX_NOTIFY_GRAPH_SENDER=notifications@bank.com
 * </pre>
 *
 * <p><b>Fail-soft + secret-safe.</b> Missing config, no resolvable recipient, a token failure, a
 * network error or any 4xx/5xx returns a {@code FAILED} {@link Result} and NEVER throws — the
 * business transaction that enqueued the notification is unaffected (the persisted outbox row is
 * always the durable record). The client secret / access token are never logged (only masked
 * config presence is), and never persisted.</p>
 */
@Component
public class GraphMailTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(GraphMailTransport.class);

    private final ObjectProvider<NotificationContactResolver> contacts;
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String sender;
    private final String scope;
    private final String graphBaseUrl;
    private final boolean saveToSentItems;
    private final RestClient tokenClient;
    private final RestClient graphClient;

    public GraphMailTransport(ObjectProvider<NotificationContactResolver> contacts,
                              @Value("${helix.notify.graph.tenant-id:}") String tenantId,
                              @Value("${helix.notify.graph.client-id:}") String clientId,
                              @Value("${helix.notify.graph.client-secret:}") String clientSecret,
                              @Value("${helix.notify.graph.sender:}") String sender,
                              @Value("${helix.notify.graph.scope:https://graph.microsoft.com/.default}") String scope,
                              @Value("${helix.notify.graph.graph-base-url:https://graph.microsoft.com/v1.0}") String graphBaseUrl,
                              @Value("${helix.notify.graph.token-url:https://login.microsoftonline.com}") String tokenUrl,
                              @Value("${helix.notify.graph.save-to-sent-items:false}") boolean saveToSentItems) {
        this.contacts = contacts;
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.sender = sender;
        this.scope = scope;
        this.graphBaseUrl = trimTrailingSlash(graphBaseUrl);
        this.saveToSentItems = saveToSentItems;
        this.tokenClient = RestClient.builder().baseUrl(trimTrailingSlash(tokenUrl)).build();
        this.graphClient = RestClient.builder().build();
    }

    @Override
    public String name() {
        return "GRAPH";
    }

    @Override
    public Result dispatch(Notification n) {
        if (isBlank(tenantId) || isBlank(clientId) || isBlank(clientSecret) || isBlank(sender)) {
            return Result.failed("Graph mail transport selected but not configured "
                    + "(set helix.notify.graph.tenant-id/client-id/client-secret/sender)");
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
            String token = fetchToken();
            List<Map<String, Object>> toRecipients = new ArrayList<>();
            for (String addr : to) {
                toRecipients.add(Map.of("emailAddress", Map.of("address", addr)));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contentType", "Text");
            body.put("content", n.getRenderedBody() == null ? "" : n.getRenderedBody());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("subject", n.getRenderedSubject() == null ? "" : n.getRenderedSubject());
            message.put("body", body);
            message.put("toRecipients", toRecipients);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", message);
            payload.put("saveToSentItems", saveToSentItems);

            String uri = graphBaseUrl + "/users/" + enc(sender) + "/sendMail";
            graphClient.post().uri(uri)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return Result.sent("graph:" + n.getId() + "->" + String.join(",", to));
        } catch (Exception e) {
            // Fail soft — a Graph outage must never break the caller. The secret/token are never
            // in the message here (only the transport-level error), but keep it truncated + safe.
            log.warn("Graph mail send failed for notification {} ({})", n.getId(), e.getMessage());
            return Result.failed(truncate("Graph mail send failed: " + e.getMessage()));
        }
    }

    /** Client-credentials OAuth2 token grant against the AAD token endpoint. */
    private String fetchToken() {
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&scope=" + enc(scope);
        String path = "/" + enc(tenantId) + "/oauth2/v2.0/token";
        TokenResponse tr = tokenClient.post().uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (tr == null || tr.accessToken() == null || tr.accessToken().isBlank()) {
            throw new IllegalStateException("token endpoint returned no access_token");
        }
        return tr.accessToken();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 480 ? s : s.substring(0, 480);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("token_type") String tokenType,
                                 @JsonProperty("expires_in") Long expiresIn) {
    }
}
