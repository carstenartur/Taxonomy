/*******************************************************************************
 * Copyright (c) 2026 Carsten Hammer.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Carsten Hammer
 *******************************************************************************/
package com.taxonomy.dsl.storage.jgit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Entity representing a Git pack file stored in the database.
 * <p>
 * Adapted from {@code sandbox-jgit-storage-hibernate} module
 * in the {@code carstenartur/sandbox} repository.
 */
@Entity
@Table(name = "git_packs", indexes = {
        @Index(name = "idx_pack_repo", columnList = "repository_name"),
        @Index(name = "idx_pack_repo_name", columnList = "repository_name, pack_name") })
public class GitPackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "pack_name", nullable = false)
    private String packName;

    @Column(name = "pack_extension", nullable = false)
    private String packExtension;

    @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public GitPackEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getPackName() { return packName; }
    public void setPackName(String packName) { this.packName = packName; }

    public String getPackExtension() { return packExtension; }
    public void setPackExtension(String packExtension) { this.packExtension = packExtension; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
