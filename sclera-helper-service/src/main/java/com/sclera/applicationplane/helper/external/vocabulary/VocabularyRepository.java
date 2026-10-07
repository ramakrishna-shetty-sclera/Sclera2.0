package com.sclera.applicationplane.helper.external.vocabulary;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

/**
 * What every vocabulary table can be asked. One shape, four tables, so the
 * service can treat them alike; Spring derives the queries per table.
 */
@NoRepositoryBean
public interface VocabularyRepository<T extends VocabularyEntry> extends JpaRepository<T, String> {

    /** Display order first, then key, so the order is the same every time. */
    List<T> findAllByOrderByDisplayOrderAscKeyAsc();

    List<T> findAllByActiveOrderByDisplayOrderAscKeyAsc(boolean active);
}