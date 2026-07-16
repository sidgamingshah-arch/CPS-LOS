package com.helix.common.dms;

import com.helix.common.web.ApiException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Document store endpoints, automatically present in every service that includes helix-common
 * (like {@code /api/audit}). Fills the "no document upload/store/DMS" gap with a real store:
 *
 * <ul>
 *   <li>{@code POST /api/documents} — base64-JSON upload (the simplest robust path: works through
 *       the gateway and the JSON e2e harness). Stores bytes + metadata; returns the metadata row.</li>
 *   <li>{@code POST /api/documents/multipart} — multipart file upload for browser/form clients.</li>
 *   <li>{@code GET /api/documents/{id}} — download the raw bytes (byte-identical to what was stored).</li>
 *   <li>{@code GET /api/documents/{id}/meta} — the metadata row.</li>
 *   <li>{@code GET /api/documents?subjectRef=&subjectType=} — list by subject.</li>
 * </ul>
 *
 * Every operation carries {@code X-Actor} into the append-only audit trail
 * ({@code DOCUMENT_STORED} / {@code DOCUMENT_RETRIEVED}).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentStoreService documents;

    public DocumentController(DocumentStoreService documents) {
        this.documents = documents;
    }

    /** Base64-JSON upload request. {@code contentBase64} is the raw file bytes, base64-encoded. */
    public record UploadRequest(String subjectType, String subjectRef, String filename,
                                String contentType, String contentBase64) {
    }

    @PostMapping
    public StoredDocument upload(@RequestBody UploadRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        if (req == null || req.contentBase64() == null || req.contentBase64().isBlank()) {
            throw ApiException.badRequest("contentBase64 is required");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(req.contentBase64().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("contentBase64 is not valid base64");
        }
        return documents.store(req.subjectType(), req.subjectRef(), req.filename(), req.contentType(), content, actor);
    }

    @PostMapping(value = "/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoredDocument uploadMultipart(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "subjectType", required = false) String subjectType,
                                          @RequestParam(value = "subjectRef", required = false) String subjectRef,
                                          @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
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
        return documents.store(subjectType, subjectRef, filename, file.getContentType(), content, actor);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        DocumentStoreService.Download dl = documents.retrieve(id, actor);
        StoredDocument doc = dl.document();
        MediaType type;
        try {
            type = MediaType.parseMediaType(doc.getContentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition cd = ContentDisposition.attachment().filename(doc.getFilename()).build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Document-Sha256", doc.getSha256())
                .contentLength(doc.getSizeBytes())
                .body(new ByteArrayResource(dl.content()));
    }

    @GetMapping("/{id}/meta")
    public StoredDocument meta(@PathVariable Long id) {
        return documents.metadata(id);
    }

    @GetMapping
    public List<StoredDocument> list(@RequestParam(required = false) String subjectRef,
                                     @RequestParam(required = false) String subjectType) {
        return documents.list(subjectType, subjectRef);
    }
}
