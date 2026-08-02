package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.ConflictStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import jakarta.persistence.Version;

import java.time.Instant;

/** Evidence-backed, human-reviewable conflict hypothesis between requirements. */
@Entity
@Table(name = "project_conflict", indexes = {
        @Index(name = "idx_conf_project", columnList = "project_id,status"),
        @Index(name = "idx_conf_req_a", columnList = "requirement_a_id"),
        @Index(name = "idx_conf_req_b", columnList = "requirement_b_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_conf_signature",
                columnNames = {"project_id", "requirement_a_id", "requirement_b_id", "conflict_type", "fingerprint"})
})
public class ProjectConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ArchitectureProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_a_id", nullable = false)
    private ProjectRequirement requirementA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_b_id", nullable = false)
    private ProjectRequirement requirementB;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_type", nullable = false, length = 32)
    private ConflictType conflictType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConflictStatus status = ConflictStatus.PROPOSED;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 500)
    private String title;

    @Lob
    @Column(nullable = false)
    private String evidence;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "resolution_note", length = 4000)
    private String resolutionNote;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "reviewed_by", length = 160)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ProjectConflict() {
    }

    public ProjectConflict(ArchitectureProject project,
                           ProjectRequirement requirementA,
                           ProjectRequirement requirementB,
                           ConflictType conflictType,
                           String fingerprint,
                           String title,
                           String evidence,
                           double confidence,
                           Instant detectedAt) {
        this.project = project;
        this.requirementA = requirementA;
        this.requirementB = requirementB;
        this.conflictType = conflictType;
        this.fingerprint = fingerprint;
        this.title = title;
        this.evidence = evidence;
        this.confidence = confidence;
        this.detectedAt = detectedAt;
    }

    public void review(ConflictStatus status,
                       String resolutionNote,
                       String reviewedBy,
                       Instant reviewedAt) {
        if (status != null) this.status = status;
        this.resolutionNote = resolutionNote;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
    }

    public Long getId() { return id; }
    public ArchitectureProject getProject() { return project; }
    public ProjectRequirement getRequirementA() { return requirementA; }
    public ProjectRequirement getRequirementB() { return requirementB; }
    public ConflictType getConflictType() { return conflictType; }
    public ConflictStatus getStatus() { return status; }
    public String getFingerprint() { return fingerprint; }
    public String getTitle() { return title; }
    public String getEvidence() { return evidence; }
    public double getConfidence() { return confidence; }
    public String getResolutionNote() { return resolutionNote; }
    public Instant getDetectedAt() { return detectedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public long getRowVersion() { return rowVersion; }
}
