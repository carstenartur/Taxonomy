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
import jakarta.persistence.Version;

/**
 * Entity representing a Git reflog entry stored in the database.
 * <p>
 * Adapted from {@code sandbox-jgit-storage-hibernate} module
 * in the {@code carstenartur/sandbox} repository.
 */
@Entity
@Table(name = "git_reflog", indexes = {
        @Index(name = "idx_reflog_repo", columnList = "repository_name"),
        @Index(name = "idx_reflog_repo_ref", columnList = "repository_name, ref_name") })
public class GitReflogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "ref_name", nullable = false)
    private String refName;

    @Column(name = "old_id", length = 40)
    private String oldId;

    @Column(name = "new_id", length = 40)
    private String newId;

    @Column(name = "who_name")
    private String whoName;

    @Column(name = "who_email")
    private String whoEmail;

    @Column(name = "who_when", nullable = false)
    private Instant when;

    @Column(name = "message", length = 2048)
    private String message;

    public GitReflogEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getRefName() { return refName; }
    public void setRefName(String refName) { this.refName = refName; }

    public String getOldId() { return oldId; }
    public void setOldId(String oldId) { this.oldId = oldId; }

    public String getNewId() { return newId; }
    public void setNewId(String newId) { this.newId = newId; }

    public String getWhoName() { return whoName; }
    public void setWhoName(String whoName) { this.whoName = whoName; }

    public String getWhoEmail() { return whoEmail; }
    public void setWhoEmail(String whoEmail) { this.whoEmail = whoEmail; }

    public Instant getWhen() { return when; }
    public void setWhen(Instant when) { this.when = when; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
