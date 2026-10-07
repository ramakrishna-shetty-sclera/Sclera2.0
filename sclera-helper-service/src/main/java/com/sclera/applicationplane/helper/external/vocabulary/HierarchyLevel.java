package com.sclera.applicationplane.helper.external.vocabulary;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A row of {@code hierarchy_level}: a {@link VocabularyKind#HIERARCHY_LEVEL} key. */
@Entity
@Table(name = "hierarchy_level")
public class HierarchyLevel extends VocabularyEntry {
}