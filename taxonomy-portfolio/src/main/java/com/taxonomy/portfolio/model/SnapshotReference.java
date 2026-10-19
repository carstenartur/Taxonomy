package com.taxonomy.portfolio.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Read-only identity of an existing snapshot, not a second stored artifact.
 *
 * <p>Composite-key associations may require a unique-key lookup even when lazy.
 * Metadata owners use this payload-free view so those lookups cannot hydrate the
 * immutable evidence CLOBs. Full evidence remains owned by
 * {@link RequirementAnalysisSnapshot} on the same physical table.</p>
 */
@Entity
@Immutable
@Table(name = "req_analysis_snapshot")
public class SnapshotReference {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "scope_key", nullable = false,
            length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "requirement_version_id", nullable = false)
    private Long requirementVersionId;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    protected SnapshotReference() { }

    public String getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public Long getProjectId() { return projectId; }
    public Long getRequirementId() { return requirementId; }
    public Long getRequirementVersionId() { return requirementVersionId; }
    public String getJobId() { return jobId; }
}
