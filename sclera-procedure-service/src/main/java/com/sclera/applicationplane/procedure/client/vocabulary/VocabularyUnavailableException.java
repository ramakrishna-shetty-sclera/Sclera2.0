package com.sclera.applicationplane.procedure.client.vocabulary;

/**
 * The vocabulary could not be read: the service that holds it did not answer,
 * refused the call, or answered with something unreadable.
 *
 * <p>Deliberately not the same thing as a key that is not in the vocabulary.
 * "EXTINGUISHER is not an asset class" sends an author to fix a procedure that
 * is fine; "the property service did not answer" tells them to wait or call
 * someone. A caller that merges the two gives the wrong instruction in one of
 * the cases, so this is its own type and its message is about the vocabulary.
 */
public class VocabularyUnavailableException extends RuntimeException {

    public VocabularyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}