package com.sclera.applicationplane.helper.storage;

import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * No Spring context, no container — a folder on disk is all this needs, which
 * is also what proves the whole point of the local provider: it works with
 * nothing else running.
 */
class LocalDocumentStoreTest {

    @TempDir
    Path tempDir;

    private LocalDocumentStore store(Path root) {
        return new LocalDocumentStore(root.toString());
    }

    @Test
    void storingReturnsAStubPrefixedLocationAndTheRightSize() {
        LocalDocumentStore store = store(tempDir);
        byte[] bytes = "NFPA 10 extract".getBytes();

        StoredDocument stored = store.store(UUID.randomUUID(), "NFPA 10.pdf", new ByteArrayInputStream(bytes));

        assertThat(stored.location()).startsWith("stub-t_");
        assertThat(stored.sizeBytes()).isEqualTo(bytes.length);
    }

    @Test
    void aStoredDocumentExistsAndAnUnknownLocationDoesNot() {
        LocalDocumentStore store = store(tempDir);
        StoredDocument stored = store.store(UUID.randomUUID(), "a.pdf", new ByteArrayInputStream("x".getBytes()));

        assertThat(store.exists(stored.location())).isTrue();
        assertThat(store.exists("stub-t_does-not-exist/nope.pdf")).isFalse();
    }

    @Test
    void loadingReadsBackExactlyWhatWasStored() throws IOException {
        LocalDocumentStore store = store(tempDir);
        byte[] bytes = "exact bytes".getBytes();
        StoredDocument stored = store.store(UUID.randomUUID(), "x.pdf", new ByteArrayInputStream(bytes));

        Resource resource = store.load(stored.location());

        assertThat(resource.getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    void loadingAnUnknownLocationIsRefused() {
        LocalDocumentStore store = store(tempDir);

        assertThatThrownBy(() -> store.load("stub-t_x/nope.pdf"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theFilenameCannotEscapeTheStorageRootEvenIfSomeoneTries() {
        LocalDocumentStore store = store(tempDir);
        // sanitize() strips separators, so this becomes one harmless segment
        // rather than a path that climbs out of the root.
        StoredDocument stored = store.store(UUID.randomUUID(), "../../../etc/passwd", new ByteArrayInputStream("x".getBytes()));

        assertThat(stored.location()).doesNotContain("..");
        assertThat(store.exists(stored.location())).isTrue();
    }

    @Test
    void existsNeverThrowsOnAHandCraftedTraversalAttempt() {
        LocalDocumentStore store = store(tempDir);

        assertThat(store.exists("../../outside")).isFalse();
    }

    @Test
    void theLocalStoreIsAlwaysMarkedAsAStub() {
        assertThat(store(tempDir).isStub()).isTrue();
    }

    @Test
    void downloadUrlPointsBackAtThisServicesOwnContentEndpoint() {
        LocalDocumentStore store = store(tempDir);
        StoredDocument stored = store.store(UUID.randomUUID(), "a b.pdf", new ByteArrayInputStream("x".getBytes()));

        String url = store.downloadUrl(stored.location()).toString();

        assertThat(url).startsWith("/api/v1/helper/documents/content?location=");
    }
}
