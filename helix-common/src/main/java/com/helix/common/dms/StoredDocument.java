package com.helix.common.dms;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Metadata row for one stored document (the DMS index). The raw bytes live in the active
 * {@link DocumentStore} backend under {@code storageKey}; this row carries everything needed
 * to find, describe and integrity-check them: subject linkage, original filename/type, size,
 * SHA-256, the backend + opaque key, and who uploaded it. Automatically registered in every
 * service that includes helix-common (all services {@code @EntityScan("com.helix")}), so the
 * {@code /api/documents} endpoints are present platform-wide like {@code /api/audit}.
 *
 * <p>Manual accessors (not Lombok): helix-common entities do not carry Lombok on their
 * classpath — see {@code AuditEvent} / {@code IngestionRecord}.</p>
 */
@Entity
@Table(name = "stored_document", indexes = {
        @Index(name = "idx_doc_subject", columnList = "subjectType,subjectRef"),
        @Index(name = "idx_doc_subject_ref", columnList = "subjectRef"),
        @Index(name = "idx_doc_sha256", columnList = "sha256")
})
public class StoredDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 60)
    private String subjectType;      // e.g. LoanApplication, Counterparty, CadCase

    @Column(length = 120)
    private String subjectRef;       // the business reference the document belongs to

    @Column(nullable = false, length = 260)
    private String filename;

    @Column(nullable = false, length = 160)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 20)
    private String storageBackend;   // FILESYSTEM | S3

    @Column(name = "storage_key", nullable = false, length = 80)
    private String storageKey;       // opaque, internally-generated (UUID)

    @Column(name = "storage_location", length = 400)
    private String storageLocation;  // filesystem path or s3://bucket/key (traceability)

    @Column(length = 200)
    private String providerRef;      // backend provider reference (ETag / file:<key>)

    @Column(length = 120)
    private String uploadedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public void setSubjectRef(String subjectRef) {
        this.subjectRef = subjectRef;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStorageBackend() {
        return storageBackend;
    }

    public void setStorageBackend(String storageBackend) {
        this.storageBackend = storageBackend;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getStorageLocation() {
        return storageLocation;
    }

    public void setStorageLocation(String storageLocation) {
        this.storageLocation = storageLocation;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
