package com.helix.origination.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * An ingested document with AI classification (PRD §3, US-3.1). High-confidence
 * classifications auto-route; low-confidence ones are flagged for human review.
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_doc_application", columnList = "applicationId")
})
@Getter
@Setter
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false)
    private String fileName;

    @Column(length = 40)
    private String declaredType;

    @Column(nullable = false, length = 40)
    private String classifiedType;     // Enums.DocumentType name

    @Column(nullable = false)
    private double classificationConfidence;

    private boolean needsReview;

    private boolean verified;
    private String verifiedBy;

    // ---- Real file capture (additive; all nullable so the legacy filename-only path is unchanged).
    // When a document is uploaded via the multipart /documents/upload path, the actual bytes are
    // stored in the DMS (storedDocId) and the document's REAL text is extracted so doc-intelligence
    // can derive fields from content. Reserved-word-safe explicit column names throughout.

    /** Links the DMS {@code StoredDocument} that holds the raw bytes (sha256 / backend / key). */
    @Column(name = "stored_doc_id")
    private Long storedDocId;

    /** The document's real extracted text (PDFBox / UTF-8 / OCR). Nullable for legacy rows. */
    @Lob
    @Column(name = "extracted_text", length = 40000)
    private String extractedText;

    /** How the text was extracted: PDFBOX | TEXT | OCR_NONE | OCR_TESSERACT | OCR_HTTP | … */
    @Column(name = "extraction_method", length = 20)
    private String extractionMethod;

    /** Whether an OCR provider was invoked (image / scanned PDF). */
    @Column(name = "ocr_used")
    private boolean ocrUsed;

    /** Page count when known (PDF), else null. */
    @Column(name = "page_count")
    private Integer pageCount;

    /** Content SHA-256 of the stored bytes (mirrors the DMS integrity hash). */
    @Column(name = "sha256", length = 80)
    private String sha256;

    /** Uploaded size in bytes. */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** The declared MIME content type of the upload. */
    @Column(name = "content_type", length = 120)
    private String contentType;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant uploadedAt;
}
