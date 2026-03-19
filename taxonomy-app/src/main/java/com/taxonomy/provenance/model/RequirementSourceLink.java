package com.taxonomy.provenance.model;

import com.taxonomy.model.LinkType;
import jakarta.persistence.*;

/**
 * Links a requirement to one or more source materials.
 */
@Entity
@Table(name = "requirement_source_link")
public class RequirementSourceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requirement_id", nullable = false)
    private String requirementId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_artifact_id", nullable = false)
    private SourceArtifact sourceArtifact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_version_id")
    private SourceVersion sourceVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_fragment_id")
    private SourceFragment sourceFragment;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false)
    private LinkType linkType;

    @Column
    private Double confidence;

    @Column(length = 1000)
    private String note;

    protected RequirementSourceLink() {}

    public RequirementSourceLink(String requirementId, SourceArtifact sourceArtifact,
                                  LinkType linkType) {
        this.requirementId = requirementId;
        this.sourceArtifact = sourceArtifact;
        this.linkType = linkType;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public SourceArtifact getSourceArtifact() { return sourceArtifact; }
    public void setSourceArtifact(SourceArtifact sourceArtifact) { this.sourceArtifact = sourceArtifact; }

    public SourceVersion getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(SourceVersion sourceVersion) { this.sourceVersion = sourceVersion; }

    public SourceFragment getSourceFragment() { return sourceFragment; }
    public void setSourceFragment(SourceFragment sourceFragment) { this.sourceFragment = sourceFragment; }

    public LinkType getLinkType() { return linkType; }
    public void setLinkType(LinkType linkType) { this.linkType = linkType; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
