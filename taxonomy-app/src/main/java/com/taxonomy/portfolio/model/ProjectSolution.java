package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectSolutionStatus;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/** Project-specific use and decision state for a reusable solution definition. */
@Entity
@Table(name = "project_solution", indexes = {
        @Index(name = "idx_psol_project", columnList = "project_id"),
        @Index(name = "idx_psol_status", columnList = "project_id,status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_psol_project_sol", columnNames = {"project_id", "solution_id"})
})
public class ProjectSolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ArchitectureProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solution_id", nullable = false)
    private SolutionDefinition solution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectSolutionStatus status = ProjectSolutionStatus.PROPOSED;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_status", nullable = false, length = 32)
    private ActionStatus actionStatus = ActionStatus.UNDECIDED;

    @Column(nullable = false)
    private int priority = 50;

    @Column(length = 2000)
    private String rationale;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ProjectSolution() {
    }

    public ProjectSolution(ArchitectureProject project,
                           SolutionDefinition solution,
                           ProjectSolutionStatus status,
                           ActionStatus actionStatus,
                           int priority,
                           String rationale,
                           String createdBy,
                           Instant now) {
        this.project = project;
        this.solution = solution;
        this.status = status != null ? status : ProjectSolutionStatus.PROPOSED;
        this.actionStatus = actionStatus != null ? actionStatus : ActionStatus.UNDECIDED;
        this.priority = priority;
        this.rationale = rationale;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(ProjectSolutionStatus status,
                       ActionStatus actionStatus,
                       Integer priority,
                       String rationale,
                       Instant now) {
        if (status != null) this.status = status;
        if (actionStatus != null) this.actionStatus = actionStatus;
        if (priority != null) this.priority = priority;
        if (rationale != null) this.rationale = rationale;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public ArchitectureProject getProject() { return project; }
    public SolutionDefinition getSolution() { return solution; }
    public ProjectSolutionStatus getStatus() { return status; }
    public ActionStatus getActionStatus() { return actionStatus; }
    public int getPriority() { return priority; }
    public String getRationale() { return rationale; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
