package com.sclera.applicationplane.helper.external.vocabulary;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A row of {@code location_type}: a {@link VocabularyKind#LOCATION_TYPE} key. */
@Entity
@Table(name = "location_type")
public class LocationTypeEntry extends VocabularyEntry {
}