package com.helix.common.dms;

import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Default {@link DocumentStore}: writes bytes to the local filesystem under
 * {@code ${HELIX_DATA_DIR}/documents}, one file per opaque {@code storageKey}. This is a
 * real store — the bytes are durable on disk and read back verbatim — not a stub. It is
 * active whenever {@code helix.dms.store} is absent or {@code filesystem}, so a fresh
 * install stores documents locally with zero external dependency.
 *
 * <p>{@code storageKey} is always an internally-generated UUID (never client input); the
 * store still defensively rejects any key containing a path separator or {@code ..} so the
 * write can never escape the documents root.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.dms.store", havingValue = "filesystem", matchIfMissing = true)
public class FilesystemDocumentStore implements DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemDocumentStore.class);

    private final Path root;

    public FilesystemDocumentStore(@Value("${HELIX_DATA_DIR:./data}") String dataDir) {
        this.root = Paths.get(dataDir, "documents").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("DMS filesystem store rooted at {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create DMS documents dir " + root, e);
        }
    }

    @Override
    public String backend() {
        return "FILESYSTEM";
    }

    @Override
    public PutResult put(String storageKey, byte[] content, String contentType) {
        Path target = resolve(storageKey);
        try {
            Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not store document bytes: " + e.getMessage());
        }
        return new PutResult(target.toString(), "file:" + storageKey);
    }

    @Override
    public byte[] get(String storageKey) {
        Path target = resolve(storageKey);
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("Document bytes missing for key " + storageKey);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read document bytes: " + e.getMessage());
        }
    }

    /** Resolve a key under the documents root, rejecting traversal defensively. */
    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()
                || storageKey.contains("/") || storageKey.contains("\\") || storageKey.contains("..")) {
            throw ApiException.badRequest("Invalid storage key");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw ApiException.badRequest("Storage key escapes documents root");
        }
        return target;
    }
}
