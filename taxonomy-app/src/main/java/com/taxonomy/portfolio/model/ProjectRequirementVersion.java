package com.taxonomy.portfolio.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Immutable text/provenance version of a project requirement. */
@Entity
@Table(name = "project_req_version", indexes = {
        @Index(name = "idx_reqver_req", columnList = "requirement_id"),
        @Index(name = "idx_reqver_hash", columnList = "requirement_id,content_hash")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_reqver_number", columnNames = {"requirement_id", "version_number"}),
        @UniqueConstraint(name = "uq_reqver_hash", columnNames = {"requirement_id", "content_hash"})
})
public class ProjectRequirementVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private ProjectRequirement requirement;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Lob
    @Column(name = "requirement_text", nullable = false)
    private String text;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "change_reason", length = 1000)
    private String changeReason;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "source_artifact_id")
    private Long sourceArtifactId;

    @Column(name = "source_version_id")
    private Long sourceVersionId;

    /** JSON array of source-fragment database IDs. */
    @Lob
    @Column(name = "source_fragment_ids")
    private String sourceFragmentIdsJson;

    @Column(name = "section_ref", length = 500)
    private String sectionReference;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Lob
    @Column(name = "original_text")
    private String originalText;

    protected ProjectRequirementVersion() {
    }

    public ProjectRequirementVersion(ProjectRequirement requirement,
                                     int versionNumber,
                                     String text,
                                     String contentHash,
                                     String changeReason,
                                     String createdBy,
                                     Instant createdAt,
                                     Long sourceArtifactId,
                                     Long sourceVersionId,
                                     String sourceFragmentIdsJson,
                                     String sectionReference,
                                     Integer pageNumber,
                                     String originalText) {
        this.requirement = requirement;
        this.versionNumber = versionNumber;
        this.text = text;
        this.contentHash = contentHash;
        this.changeReason = changeReason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.sourceArtifactId = sourceArtifactId;
        this.sourceVersionId = sourceVersionId;
        this.sourceFragmentIdsJson = sourceFragmentIdsJson;
        this.sectionReference = sectionReference;
        this.pageNumber = pageNumber;
        this.originalText = originalText;
    }

    public Long getId() { return id; }
    public ProjectRequirement getRequirement() { return requirement; }
    public int getVersionNumber() { return versionNumber; }
    public String getText() { return text; }
    public String getContentHash() { return contentHash; }
    public String getChangeReason() { return changeReason; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getSourceArtifactId() { return sourceArtifactId; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public String getSourceFragmentIdsJson() { return sourceFragmentIdsJson; }
    public String getSectionReference() { return sectionReference; }
    public Integer getPageNumber() { return pageNumber; }
    public String getOriginalText() { return originalText; }
}
