package com.taxonomy.catalog.provenance;

import jakarta.persistence.*;

@Entity @Table(name = "catalogue_source_state")
public class CatalogueSourceState {
    @Id @Column(name = "id", length = 32) String id;
    @Column(name = "current_revision", length = 36) String currentRevision;
    @Version @Column(name = "row_version", nullable = false) long rowVersion;
    protected CatalogueSourceState() { }
}
