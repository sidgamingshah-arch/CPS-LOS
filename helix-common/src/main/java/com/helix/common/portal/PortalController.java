package com.helix.common.portal;

import com.helix.common.web.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

/**
 * Token-scoped customer / vendor self-service PORTAL, automatically present in every service that
 * includes helix-common (like {@code /api/audit} and {@code /api/queries}). The gateway prefixes
 * per service, e.g. {@code GET /counterparty/api/portal/{token}}.
 *
 * <p><b>No {@code X-Actor}, no auth.</b> This surface is reached by EXTERNAL parties who never
 * authenticate — the one-time response token minted for their RFI is the sole credential (it is
 * also allow-listed in {@code HelixSecurityConfig} for the secure profiles). Every action is
 * scoped strictly to the single thread the token belongs to; there is deliberately no listing
 * endpoint and no thread id in any path, so a token can only ever address its own thread.</p>
 *
 * <ul>
 *   <li>{@code GET /api/portal/{token}} — the single RFI's context (topic / question / message
 *       timeline / deadline / allowed actions). A safe idempotent read; does not consume the token.</li>
 *   <li>{@code POST /api/portal/{token}/respond} {@code {message}} — append the party's response,
 *       flipping the thread to RESPONDED.</li>
 *   <li>{@code POST /api/portal/{token}/documents} — upload a document (multipart {@code file}, or a
 *       base64-JSON body) into the governed DMS store, tagged to the thread.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/portal")
public class PortalController {

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    public record RespondRequest(String message) {
    }

    /** Base64-JSON upload alternative to multipart (works through any JSON client). */
    public record UploadRequest(String filename, String contentType, String contentBase64) {
    }

    @GetMapping("/{token}")
    public PortalService.PortalContext view(@PathVariable String token) {
        return portal.context(token);
    }

    @PostMapping("/{token}/respond")
    public PortalService.PortalContext respond(@PathVariable String token,
                                               @RequestBody(required = false) RespondRequest req) {
        return portal.respond(token, req == null ? null : req.message());
    }

    @PostMapping(value = "/{token}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PortalService.UploadResult uploadMultipart(@PathVariable String token,
                                                      @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("file part is required");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("could not read uploaded file: " + e.getMessage());
        }
        String filename = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        return portal.upload(token, filename, file.getContentType(), content);
    }

    @PostMapping(value = "/{token}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PortalService.UploadResult uploadJson(@PathVariable String token,
                                                 @RequestBody UploadRequest req) {
        if (req == null || req.contentBase64() == null || req.contentBase64().isBlank()) {
            throw ApiException.badRequest("contentBase64 is required");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(req.contentBase64().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("contentBase64 is not valid base64");
        }
        return portal.upload(token, req.filename(), req.contentType(), content);
    }
}
