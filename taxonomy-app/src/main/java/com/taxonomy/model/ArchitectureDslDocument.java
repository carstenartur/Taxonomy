package com.taxonomy.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Nationalized;

import java.time.Instant;

/**
 * Tracks a parsed/loaded DSL document with its Git commit context.
 */
@Entity
@Table(name = "architecture_dsl_document")
public class ArchitectureDslDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Nationalized
    @Column(nullable = false)
    private String path;

    @Nationalized
    @Column(name = "commit_id")
    private String commitId;

    @Nationalized
    @Column
    private String branch;

    @Nationalized
    @Column
    private String namespace;

    @Nationalized
    @Column(name = "dsl_version")
    private String dslVersion;

    @Nationalized
    @Lob
    @Column(name = "raw_content", nullable = false, length = 100000)
    private String rawContent;

    @Column(name = "parsed_at", nullable = false)
    private Instant parsedAt;

    @PrePersist
    protected void onCreate() {
        if (parsedAt == null) {
            parsedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getDslVersion() { return dslVersion; }
    public void setDslVersion(String dslVersion) { this.dslVersion = dslVersion; }

    public String getRawContent() { return rawContent; }
    public void setRawContent(String rawContent) { this.rawContent = rawContent; }

    public Instant getParsedAt() { return parsedAt; }
    public void setParsedAt(Instant parsedAt) { this.parsedAt = parsedAt; }
}
