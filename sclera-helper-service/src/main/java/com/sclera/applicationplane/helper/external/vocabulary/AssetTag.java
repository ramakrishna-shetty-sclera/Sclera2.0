package com.sclera.applicationplane.helper.external.vocabulary;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A row of {@code asset_tag}: a {@link VocabularyKind#ASSET_TAG} key. */
@Entity
@Table(name = "asset_tag")
public class AssetTag extends VocabularyEntry {
}