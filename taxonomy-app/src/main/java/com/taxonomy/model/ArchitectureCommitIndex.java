package com.taxonomy.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.*;

import java.time.Instant;

/**
 * Index entry for a DSL architecture commit, enabling history search.
 *
 * <p>Each row corresponds to a single JGit commit on a DSL branch.
 * The tokenized fields allow Hibernate Search / full-text queries over
 * the architecture evolution timeline.
 *
 * <p>Hibernate Search annotations enable Lucene-backed full-text search:
 * <ul>
 *   <li>{@code tokenizedChangeText} — DSL-aware full-text search via custom {@code "dsl"} analyzer</li>
 *   <li>{@code message} — commit message full-text search via {@code "english"} analyzer</li>
 *   <li>{@code affectedElementIds} — comma-separated IDs searchable via {@code "csv-keyword"} analyzer</li>
 *   <li>{@code affectedRelationIds} — semicolon-separated keys searchable via {@code "csv-keyword"} analyzer</li>
 *   <li>{@code branch}, {@code commitId}, {@code author} — exact keyword filters</li>
 *   <li>{@code commitTimestamp} — sortable date field</li>
 * </ul>
 */
@Entity
@Indexed
@Table(name = "architecture_commit_index",
       uniqueConstraints = @UniqueConstraint(columnNames = "commit_id"))
public class ArchitectureCommitIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Nationalized
    @Column(name = "commit_id", nullable = false, length = 40)
    @KeywordField
    private String commitId;

    @Nationalized
    @Column
    @KeywordField
    private String author;

    @Column(name = "commit_timestamp", nullable = false)
    @GenericField(sortable = Sortable.YES)
    private Instant commitTimestamp;

    @Nationalized
    @Column(length = 500)
    @FullTextField(analyzer = "english")
    private String message;

    @Nationalized
    @Column(name = "changed_files", length = 2000)
    private String changedFiles;

    @Nationalized
    @Column(name = "tokenized_change_text", length = 10000)
    @FullTextField(analyzer = "dsl")
    private String tokenizedChangeText;

    @Nationalized
    @Column(name = "affected_element_ids", length = 2000)
    @FullTextField(analyzer = "csv-keyword")
    private String affectedElementIds;

    @Nationalized
    @Column(name = "affected_relation_ids", length = 2000)
    @FullTextField(analyzer = "csv-keyword")
    private String affectedRelationIds;

    @Nationalized
    @Column
    @KeywordField
    private String branch;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    @PrePersist
    protected void onCreate() {
        if (indexedAt == null) {
            indexedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public Instant getCommitTimestamp() { return commitTimestamp; }
    public void setCommitTimestamp(Instant commitTimestamp) { this.commitTimestamp = commitTimestamp; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getChangedFiles() { return changedFiles; }
    public void setChangedFiles(String changedFiles) { this.changedFiles = changedFiles; }

    public String getTokenizedChangeText() { return tokenizedChangeText; }
    public void setTokenizedChangeText(String tokenizedChangeText) { this.tokenizedChangeText = tokenizedChangeText; }

    public String getAffectedElementIds() { return affectedElementIds; }
    public void setAffectedElementIds(String affectedElementIds) { this.affectedElementIds = affectedElementIds; }

    public String getAffectedRelationIds() { return affectedRelationIds; }
    public void setAffectedRelationIds(String affectedRelationIds) { this.affectedRelationIds = affectedRelationIds; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public Instant getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Instant indexedAt) { this.indexedAt = indexedAt; }
}
