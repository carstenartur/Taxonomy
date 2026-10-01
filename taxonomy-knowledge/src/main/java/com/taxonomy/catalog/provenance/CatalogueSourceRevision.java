package com.taxonomy.catalog.provenance;

import jakarta.persistence.*;

@Entity @Table(name = "catalogue_source_revision")
@org.hibernate.annotations.Immutable
public class CatalogueSourceRevision {
    @Id @Column(name = "id", length = 36) String id;
    @Column(name = "created_at", length = 40, nullable = false) String createdAt;
    @Column(name = "workbook_sha256", length = 64) String workbook;
    @Column(name = "overlay_sha256", length = 64) String overlay;
    @Column(name = "relations_sha256", length = 64) String relations;
    @Column(name = "workbook_use", length = 24, nullable = false) String workbookUse;
    @Column(name = "overlay_use", length = 24, nullable = false) String overlayUse;
    @Column(name = "relations_use", length = 24, nullable = false) String relationsUse;
    protected CatalogueSourceRevision() { }
}
