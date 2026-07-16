package com.helix.common.dms;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The governed DMS lane. {@link #store} computes the content SHA-256, hands the bytes to the
 * active {@link DocumentStore} backend under a fresh opaque key, persists the searchable
 * {@link StoredDocument} metadata, and stamps a {@code DOCUMENT_STORED} HUMAN audit event.
 * {@link #retrieve} reads the bytes back through the same backend and stamps
 * {@code DOCUMENT_RETRIEVED}. There is no figure computation here — a document store is
 * deterministic byte storage, additive to the credit lifecycle.
 *
 * <p>Audit writes join the caller's transaction (SQLite single-writer), so the store/retrieve
 * methods are read-write transactions rather than {@code readOnly}.</p>
 */
@Service
public class DocumentStoreService {

    private final StoredDocumentRepository repo;
    private final DocumentStore store;
    private final AuditService audit;

    public DocumentStoreService(StoredDocumentRepository repo, DocumentStore store, AuditService audit) {
        this.repo = repo;
        this.store = store;
        this.audit = audit;
    }

    /** Bytes + resolved metadata returned from a download. */
    public record Download(StoredDocument document, byte[] content) {
    }

    @Transactional
    public StoredDocument store(String subjectType, String subjectRef, String filename, String contentType,
                                byte[] content, String actor) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("Document content is empty");
        }
        if (filename == null || filename.isBlank()) {
            throw ApiException.badRequest("filename is required");
        }
        String ct = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        String sha = sha256Hex(content);
        String storageKey = UUID.randomUUID().toString().replace("-", "");

        DocumentStore.PutResult put = store.put(storageKey, content, ct);

        StoredDocument doc = new StoredDocument();
        doc.setSubjectType(subjectType);
        doc.setSubjectRef(subjectRef);
        doc.setFilename(filename);
        doc.setContentType(ct);
        doc.setSizeBytes(content.length);
        doc.setSha256(sha);
        doc.setStorageBackend(store.backend());
        doc.setStorageKey(storageKey);
        doc.setStorageLocation(put.location());
        doc.setProviderRef(put.providerRef());
        doc.setUploadedBy(actor);
        StoredDocument saved = repo.save(doc);

        audit.human(actor == null ? "system" : actor, "DOCUMENT_STORED", "StoredDocument",
                String.valueOf(saved.getId()),
                "Stored '%s' (%d bytes) for %s via %s".formatted(filename, content.length,
                        subjectRef == null ? "(unlinked)" : subjectRef, store.backend()),
                Map.of("filename", filename, "contentType", ct, "sizeBytes", content.length,
                        "sha256", sha, "backend", store.backend(),
                        "subjectType", String.valueOf(subjectType), "subjectRef", String.valueOf(subjectRef)));
        return saved;
    }

    @Transactional
    public Download retrieve(Long id, String actor) {
        StoredDocument doc = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("No document: " + id));
        byte[] content = store.get(doc.getStorageKey());
        audit.human(actor == null ? "system" : actor, "DOCUMENT_RETRIEVED", "StoredDocument",
                String.valueOf(doc.getId()),
                "Retrieved '%s' (%d bytes) via %s".formatted(doc.getFilename(), doc.getSizeBytes(),
                        doc.getStorageBackend()),
                Map.of("filename", doc.getFilename(), "backend", doc.getStorageBackend(),
                        "sha256", doc.getSha256(), "subjectRef", String.valueOf(doc.getSubjectRef())));
        return new Download(doc, content);
    }

    @Transactional(readOnly = true)
    public StoredDocument metadata(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No document: " + id));
    }

    @Transactional(readOnly = true)
    public List<StoredDocument> list(String subjectType, String subjectRef) {
        if (subjectRef != null && !subjectRef.isBlank()) {
            return (subjectType != null && !subjectType.isBlank())
                    ? repo.findBySubjectTypeAndSubjectRefOrderByIdDesc(subjectType, subjectRef)
                    : repo.findBySubjectRefOrderByIdDesc(subjectRef);
        }
        return repo.findTop200ByOrderByIdDesc();
    }

    static String sha256Hex(byte[] content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
