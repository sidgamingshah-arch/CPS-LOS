package com.helix.common.coedit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PRODUCT co-edit provider ({@code helix.coedit.provider=graph}). Talks to the Microsoft
 * Graph REST API directly through {@link RestClient} — no heavy Graph SDK dependency — and
 * is fully production-wireable by setting the environment:
 *
 * <pre>
 *   HELIX_COEDIT_PROVIDER=graph
 *   HELIX_COEDIT_TENANT_ID=&lt;aad-tenant-guid&gt;
 *   HELIX_COEDIT_CLIENT_ID=&lt;app-registration-client-id&gt;
 *   HELIX_COEDIT_CLIENT_SECRET=&lt;client-secret&gt;
 *   HELIX_COEDIT_DRIVE_ID=&lt;sharepoint-document-library-drive-id&gt;
 * </pre>
 *
 * <p>Real Graph sequence per publish:
 * <ol>
 *   <li><b>token</b> — client-credentials OAuth2 grant against the AAD token endpoint
 *       ({@code POST {authority}/{tenant}/oauth2/v2.0/token}), scope {@code .default}.</li>
 *   <li><b>upload</b> — simple upload of the artifact bytes to the SharePoint drive
 *       ({@code PUT /drives/{drive}/root:/{path}:/content}) → a DriveItem with a {@code webUrl}.</li>
 *   <li><b>link</b> — the DriveItem {@code webUrl} is the browser co-edit URL. For an Excel
 *       artifact a workbook session is additionally opened (best-effort).</li>
 * </ol>
 *
 * <p>The provider NEVER throws: any failure (token, upload, network, 5xx) is caught and
 * returned as a {@link CoEditResult#fallback fail-soft} result carrying the local artifact,
 * so a Graph outage degrades to today's local-export behaviour instead of breaking the call.
 */
@Component
@ConditionalOnProperty(name = "helix.coedit.provider", havingValue = "graph")
public class GraphCoEditProvider implements CoEditProvider {

    private static final Logger log = LoggerFactory.getLogger(GraphCoEditProvider.class);

    private final RestClient tokenClient;
    private final RestClient graphClient;
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String driveId;
    private final String scope;
    private final String uploadFolder;
    private final String graphBaseUrl;

    public GraphCoEditProvider(
            @Value("${helix.coedit.tenant-id:}") String tenantId,
            @Value("${helix.coedit.client-id:}") String clientId,
            @Value("${helix.coedit.client-secret:}") String clientSecret,
            @Value("${helix.coedit.drive-id:}") String driveId,
            @Value("${helix.coedit.scope:https://graph.microsoft.com/.default}") String scope,
            @Value("${helix.coedit.upload-folder:Helix}") String uploadFolder,
            @Value("${helix.coedit.graph-base-url:https://graph.microsoft.com/v1.0}") String graphBaseUrl,
            @Value("${helix.coedit.token-url:https://login.microsoftonline.com}") String tokenUrl) {
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.driveId = driveId;
        this.scope = scope;
        this.uploadFolder = uploadFolder;
        this.graphBaseUrl = trimTrailingSlash(graphBaseUrl);
        this.tokenClient = RestClient.builder().baseUrl(trimTrailingSlash(tokenUrl)).build();
        this.graphClient = RestClient.builder().build();
        log.info("GraphCoEditProvider active (graphBaseUrl={}, drive={}, tenant configured={})",
                this.graphBaseUrl, mask(driveId), !tenantId.isBlank());
    }

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public CoEditResult publish(CoEditRequest request) {
        if (request.content() == null || request.content().length == 0) {
            return CoEditResult.fallback(name(), request, "No artifact bytes to publish");
        }
        try {
            String token = fetchToken();
            DriveItem item = upload(token, request);
            if (item == null || item.webUrl() == null || item.webUrl().isBlank()) {
                return CoEditResult.fallback(name(), request,
                        "Graph upload returned no webUrl — using local artifact");
            }
            List<String> warnings = new ArrayList<>();
            String sessionId = null;
            if (isExcel(request)) {
                sessionId = createWorkbookSession(token, item.id(), warnings);
            }
            log.info("co-edit published to SharePoint: subject={}/{} file={} item={} ",
                    request.subjectType(), request.subjectId(), request.fileName(), mask(item.id()));
            return CoEditResult.coedit(name(), request, item.webUrl(), item.webUrl(), sessionId, warnings);
        } catch (Exception e) {
            // Fail soft — a Graph outage must never break the caller. Hand back the local artifact.
            log.warn("Graph co-edit unavailable for {}/{} ({}) — falling back to local artifact",
                    request.subjectType(), request.subjectId(), e.getMessage());
            return CoEditResult.fallback(name(), request, "Graph co-edit unavailable: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Graph calls

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

    /** Simple (single-shot) upload of the artifact bytes into the SharePoint drive. */
    private DriveItem upload(String token, CoEditRequest request) {
        String path = uploadFolder + "/" + request.subjectId() + "-" + request.fileName();
        URI uri = URI.create(graphBaseUrl + "/drives/" + enc(driveId)
                + "/root:/" + encodePath(path) + ":/content");
        MediaType ct = safeMediaType(request.contentType());
        return graphClient.put().uri(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(ct)
                .body(request.content())
                .retrieve()
                .body(DriveItem.class);
    }

    /**
     * Opens an Excel workbook session on the uploaded item (persisted). Best-effort: the
     * co-edit link is already the DriveItem webUrl, so a session failure only drops the
     * session id and records a warning — it never fails the publish.
     */
    private String createWorkbookSession(String token, String itemId, List<String> warnings) {
        if (itemId == null || itemId.isBlank()) return null;
        try {
            URI uri = URI.create(graphBaseUrl + "/drives/" + enc(driveId)
                    + "/items/" + enc(itemId) + "/workbook/createSession");
            WorkbookSession s = graphClient.post().uri(uri)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("persistChanges", true))
                    .retrieve()
                    .body(WorkbookSession.class);
            return s == null ? null : s.id();
        } catch (Exception e) {
            log.debug("workbook session not opened for {} ({})", mask(itemId), e.getMessage());
            warnings.add("Excel workbook session unavailable: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isExcel(CoEditRequest req) {
        String ct = req.contentType() == null ? "" : req.contentType().toLowerCase();
        String fn = req.fileName() == null ? "" : req.fileName().toLowerCase();
        return ct.contains("spreadsheet") || ct.contains("excel") || ct.contains("csv")
                || fn.endsWith(".xlsx") || fn.endsWith(".xls") || fn.endsWith(".csv");
    }

    private static MediaType safeMediaType(String ct) {
        try {
            return ct == null || ct.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(ct);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Percent-encode each path segment but keep the {@code /} folder separators. */
    private static String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String mask(String s) {
        if (s == null || s.isBlank()) return "<unset>";
        return s.length() <= 6 ? "***" : s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }

    // ---------------------------------------------------------------- wire records

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("token_type") String tokenType,
                                 @JsonProperty("expires_in") Long expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DriveItem(String id, String name, String webUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkbookSession(String id) {
    }
}
