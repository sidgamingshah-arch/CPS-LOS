package com.helix.origination.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Real text extraction from an uploaded document's actual bytes. This is the honest counterpart
 * to the old filename-only path: the returned text is what the document really contains, so
 * downstream doc-intelligence can derive its extraction fields FROM the document rather than from
 * a content-agnostic template.
 *
 * <ul>
 *   <li><b>PDF</b> — PDFBox {@link PDFTextStripper} over the raw bytes (pure Java, in-container,
 *       no native dependency). A born-digital / text PDF yields its embedded text (method
 *       {@code PDFBOX}); a scanned / image-only PDF strips to blank and falls through to the
 *       configured OCR provider.</li>
 *   <li><b>text / csv / plain</b> — decoded as UTF-8 (method {@code TEXT}).</li>
 *   <li><b>image</b> — the configured OCR provider.</li>
 * </ul>
 *
 * <p>The OCR provider is config-gated via {@code helix.ocr.provider} (default {@code none}). It is
 * a deploy-time choice, never a code branch per document. Everything here is <b>fail-soft</b>: no
 * extraction path ever throws — on any error it returns empty text plus a human-readable note and
 * logs a warning, so a bad upload degrades gracefully instead of breaking the upload transaction.
 * This service computes NO credit figure; it only surfaces text for the advisory extraction path.</p>
 */
@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    /** Cap the returned text so a pathological document can never bloat the DB row / a prompt. */
    static final int MAX_TEXT_CHARS = 40_000;

    /** Extraction outcome: the real text, page count, the method used, whether OCR ran, and a note. */
    public record ExtractedText(String text, int pageCount, String method, boolean ocrUsed, String note) {
    }

    /** OCR provider selected at deploy time: {@code none} (default) | {@code tesseract} | {@code http}. */
    @Value("${helix.ocr.provider:none}")
    private String ocrProvider;

    /** For the {@code http} provider: the endpoint that accepts the raw bytes and returns text. */
    @Value("${helix.ocr.http.url:}")
    private String ocrHttpUrl;

    /** For the {@code tesseract} provider: the CLI binary name / path. */
    @Value("${helix.ocr.tesseract.cmd:tesseract}")
    private String tesseractCmd;

    public ExtractedText extract(String fileName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new ExtractedText("", 0, "EMPTY", false, "No bytes to extract");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        boolean isPdf = ct.contains("pdf") || name.endsWith(".pdf");
        boolean isText = ct.startsWith("text/") || ct.contains("csv") || ct.contains("plain")
                || name.endsWith(".txt") || name.endsWith(".csv");
        boolean isImage = ct.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".tif") || name.endsWith(".tiff");

        if (isPdf) {
            return extractPdf(fileName, contentType, bytes);
        }
        if (isText) {
            String text = cap(new String(bytes, StandardCharsets.UTF_8));
            return new ExtractedText(text, 1, "TEXT", false,
                    text.isBlank() ? "Decoded as UTF-8 text but the content was empty" : null);
        }
        if (isImage) {
            return runOcr(bytes, "Image document (" + (contentType == null ? "?" : contentType) + ")");
        }
        // Unknown type: best-effort UTF-8 decode so a mislabelled text file still yields text; if the
        // bytes are clearly binary we return a graceful note rather than garbage.
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (looksTextual(decoded)) {
            return new ExtractedText(cap(decoded), 1, "TEXT", false, null);
        }
        return new ExtractedText("", 0, "UNSUPPORTED", false,
                "Unsupported content type for text extraction (" + (contentType == null ? "unknown" : contentType)
                        + "); no embedded text read.");
    }

    private ExtractedText extractPdf(String fileName, String contentType, byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pages = doc.getNumberOfPages();
            String text = new PDFTextStripper().getText(doc);
            if (text != null && !text.isBlank()) {
                return new ExtractedText(cap(text), pages, "PDFBOX", false, null);
            }
            // Blank strip => scanned / image-only PDF: fall through to the OCR provider.
            ExtractedText ocr = runOcr(bytes, "Scanned/image PDF (no embedded text)");
            return new ExtractedText(ocr.text(), pages, ocr.method(), ocr.ocrUsed(), ocr.note());
        } catch (Exception e) {
            log.warn("PDFBox extraction failed for {} ({}): {}", fileName, contentType, e.getMessage());
            return new ExtractedText("", 0, "PDFBOX", false,
                    "Could not parse the PDF (" + e.getMessage() + "); no text extracted.");
        }
    }

    /**
     * Config-gated OCR. Fail-soft always: any missing config / process error / exception returns
     * empty text plus a note and logs a warning — it never throws.
     */
    private ExtractedText runOcr(byte[] bytes, String context) {
        String provider = ocrProvider == null ? "none" : ocrProvider.trim().toLowerCase(Locale.ROOT);
        switch (provider) {
            case "tesseract" -> {
                return runTesseract(bytes);
            }
            case "http" -> {
                return runHttpOcr(bytes);
            }
            default -> {
                return new ExtractedText("", 0, "OCR_NONE", false,
                        "No embedded text extracted; OCR provider not configured "
                                + "(set helix.ocr.provider=tesseract|http at deploy).");
            }
        }
    }

    private ExtractedText runTesseract(byte[] bytes) {
        File in = null;
        File outBase = null;
        try {
            in = File.createTempFile("helix-ocr-", ".img");
            Files.write(in.toPath(), bytes);
            outBase = File.createTempFile("helix-ocr-out-", "");
            // tesseract writes <outBase>.txt
            ProcessBuilder pb = new ProcessBuilder(tesseractCmd, in.getAbsolutePath(), outBase.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new ExtractedText("", 0, "OCR_TESSERACT", true, "Tesseract timed out; no text extracted.");
            }
            File txt = new File(outBase.getAbsolutePath() + ".txt");
            if (p.exitValue() != 0 || !txt.exists()) {
                return new ExtractedText("", 0, "OCR_TESSERACT", true,
                        "Tesseract returned no output (exit " + p.exitValue() + ").");
            }
            String text = new String(Files.readAllBytes(txt.toPath()), StandardCharsets.UTF_8);
            Files.deleteIfExists(txt.toPath());
            return new ExtractedText(cap(text), text.isBlank() ? 0 : 1, "OCR_TESSERACT", true,
                    text.isBlank() ? "Tesseract found no text." : null);
        } catch (Exception e) {
            log.warn("Tesseract OCR failed: {}", e.getMessage());
            return new ExtractedText("", 0, "OCR_TESSERACT", true,
                    "Tesseract OCR unavailable (" + e.getMessage() + "); no text extracted.");
        } finally {
            deleteQuietly(in);
            deleteQuietly(outBase);
        }
    }

    private ExtractedText runHttpOcr(byte[] bytes) {
        if (ocrHttpUrl == null || ocrHttpUrl.isBlank()) {
            return new ExtractedText("", 0, "OCR_HTTP", true,
                    "helix.ocr.provider=http but helix.ocr.http.url is not set; no text extracted.");
        }
        try {
            String text = RestClient.create().post()
                    .uri(ocrHttpUrl)
                    .header("Content-Type", "application/octet-stream")
                    .body(bytes)
                    .retrieve()
                    .body(String.class);
            String out = text == null ? "" : text;
            return new ExtractedText(cap(out), out.isBlank() ? 0 : 1, "OCR_HTTP", true,
                    out.isBlank() ? "OCR provider returned no text." : null);
        } catch (Exception e) {
            log.warn("HTTP OCR provider call failed ({}): {}", ocrHttpUrl, e.getMessage());
            return new ExtractedText("", 0, "OCR_HTTP", true,
                    "OCR provider call failed (" + e.getMessage() + "); no text extracted.");
        }
    }

    /** Heuristic: treat a decode as textual when it has no NUL and is mostly printable. */
    private static boolean looksTextual(String s) {
        if (s.isEmpty() || s.indexOf('\0') >= 0) {
            return false;
        }
        int printable = 0;
        int len = Math.min(s.length(), 4096);
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c >= 0x20) {
                printable++;
            }
        }
        return len > 0 && printable >= (int) (len * 0.85);
    }

    private static String cap(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT_CHARS ? s.substring(0, MAX_TEXT_CHARS) : s;
    }

    private static void deleteQuietly(File f) {
        if (f != null) {
            try {
                Files.deleteIfExists(f.toPath());
            } catch (Exception ignored) {
                // best-effort temp cleanup
            }
        }
    }
}
