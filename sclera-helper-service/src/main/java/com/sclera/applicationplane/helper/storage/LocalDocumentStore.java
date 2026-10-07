package com.sclera.applicationplane.helper.storage;

import com.sclera.applicationplane.helper.tenancy.TenantSchemas;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The dev stand-in for an object store: writes to a local directory, keyed the
 * same way the tenant schema is ({@code t_<org-no-dashes>}), so a document's
 * location is human-readable on disk while still being treated as opaque by
 * every caller.
 *
 * <p>Default provider — {@code sclera.storage.provider} defaults to
 * {@code local}. The production provider is S3, specified in
 * {@code docs/external-services.md} and deliberately not written until a
 * bucket exists to write to.
 *
 * <p>In Docker this directory is a named volume ({@code helper-documents}), so
 * uploads survive a container restart the same way Postgres's data does. On a
 * bare {@code java -jar} run it defaults to a relative {@code data/documents}
 * under the working directory.
 */
@Service
@ConditionalOnProperty(name = "sclera.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStore implements DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStore.class);

    private final Path root;

    public LocalDocumentStore(@Value("${sclera.storage.local.root-dir:data/documents}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create local document storage directory: " + root, e);
        }
        log.info("Local document storage: {}", root);
    }

    @Override
    public StoredDocument store(UUID orgId, String filename, InputStream content) {
        // Prefixed stub-, per C3: nothing this returns may be mistaken for a
        // real, permanent artifact.
        String location = "stub-" + TenantSchemas.schemaFor(orgId) + "/" + UUID.randomUUID() + "-" + sanitize(filename);
        Path target = resolve(location);
        try {
            Files.createDirectories(target.getParent());
            long size = Files.copy(content, target);
            return new StoredDocument(location, size);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store document at " + location, e);
        }
    }

    /**
     * No bucket to sign against, so the URL points back at this service's own
     * content endpoint rather than at the file directly — the same shape a
     * signed S3 URL has from the caller's side: a link to follow, not a path to
     * read.
     */
    @Override
    public URI downloadUrl(String location) {
        return URI.create("/api/v1/helper/documents/content?location="
                + URLEncoder.encode(location, StandardCharsets.UTF_8));
    }

    @Override
    public boolean exists(String location) {
        try {
            return Files.isRegularFile(resolve(location));
        } catch (IllegalArgumentException badLocation) {
            return false;
        }
    }

    @Override
    public boolean isStub() {
        return true;
    }

    /**
     * Reads the bytes back. Not on {@link DocumentStore}: serving content is
     * only ever this implementation's job — a real object store's
     * {@code downloadUrl} already is the final link, and nothing calls back
     * into helper to fetch it.
     */
    public Resource load(String location) {
        Path file = resolve(location);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("No document at " + location);
        }
        return new FileSystemResource(file);
    }

    /** Confines every path under the configured root, whatever the location string claims. */
    private Path resolve(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Blank location");
        }
        Path target = root.resolve(location).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Illegal location: " + location);
        }
        return target;
    }

    /**
     * Strips directory separators and {@code ..} so the filename can only ever
     * be a plain file name, never a path or a hint of one — {@link #resolve}
     * is the real guard against escaping the root, but a sanitised name should
     * not even look like an attempt.
     */
    private static String sanitize(String filename) {
        String name = filename == null ? "" : filename.strip().replaceAll("[\\\\/]+", "_").replace("..", "_");
        if (name.isBlank()) {
            name = "file";
        }
        return name.length() > 150 ? name.substring(name.length() - 150) : name;
    }
}
