package com.sclera.applicationplane.helper.external.vocabulary;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A row of {@code asset_class}: a {@link VocabularyKind#ASSET_CLASS} key. */
@Entity
@Table(name = "asset_class")
public class AssetClass extends VocabularyEntry {
}