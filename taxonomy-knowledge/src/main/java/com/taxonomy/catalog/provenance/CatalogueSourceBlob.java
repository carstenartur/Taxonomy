package com.taxonomy.catalog.provenance;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "catalogue_source_blob")
@org.hibernate.annotations.Immutable
public class CatalogueSourceBlob {
    @Id @Column(name = "sha256", length = 64) String sha256;
    @Column(name = "byte_length", nullable = false) long byteLength;
    @JdbcTypeCode(SqlTypes.LONG32VARBINARY) @Column(name = "payload", nullable = false) byte[] payload;
    protected CatalogueSourceBlob() { }
}
