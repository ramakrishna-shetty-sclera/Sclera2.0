package com.sclera.applicationplane.procedure.client.vocabulary;

import java.util.UUID;

/**
 * What the procedure service needs from whatever holds the property
 * vocabulary. The interface and its types are this service's; only the
 * implementation knows who answers.
 *
 * <p>Today that is the helper service, a temporary stand-in. Cutting over to a
 * real property service is meant to be a configuration change
 * ({@code sclera.external.vocabulary.app-id}), not a code change, which is why
 * nothing outside this package may name the helper.
 */
public interface VocabularyClient {

    /**
     * The organization's whole vocabulary, retired keys included.
     *
     * @throws VocabularyUnavailableException when it cannot be read — never
     *         returns a partial or empty vocabulary instead, since an empty one
     *         would read as "no key exists"
     */
    Vocabulary fetch(UUID orgId);
}