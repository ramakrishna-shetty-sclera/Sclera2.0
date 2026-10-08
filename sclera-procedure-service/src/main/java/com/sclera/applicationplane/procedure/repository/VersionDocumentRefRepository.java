package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.VersionDocumentRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Which documents published versions cite. Lives in each organization's
 * schema, so a document id is only ever compared with its own organization's
 * versions.
 */
public interface VersionDocumentRefRepository
        extends JpaRepository<VersionDocumentRef, VersionDocumentRef.Key> {

    /**
     * How many published versions cite this document. The primary key allows
     * one row per version per document, so counting rows counts versions.
     */
    long countByDocumentId(UUID documentId);
}
