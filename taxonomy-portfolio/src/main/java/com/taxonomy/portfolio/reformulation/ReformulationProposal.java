package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.model.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Proposal owns only its own revision pointer. Baseline columns are insert-only. */
@Entity
@Table(name="reformulation_proposal", uniqueConstraints=@UniqueConstraint(name="uq_reform_proposal_scope",columnNames={"id","scope_key"}),
        indexes=@Index(name="idx_reform_req",columnList="scope_key,project_id,requirement_id"))
public class ReformulationProposal {
    @Id @Column(length=36) private String id;
    @Column(name="scope_key",nullable=false,updatable=false,length=PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @Column(name="project_id",nullable=false,updatable=false) private Long projectId;
    @Column(name="requirement_id",nullable=false,updatable=false) private Long requirementId;
    @Column(name="source_version_id",nullable=false,updatable=false) private Long sourceVersionId;
    @Column(name="snapshot_id",nullable=false,updatable=false,length=36) private String snapshotId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumns({@JoinColumn(name="source_version_id",referencedColumnName="id",insertable=false,updatable=false),
        @JoinColumn(name="requirement_id",referencedColumnName="requirement_id",insertable=false,updatable=false),
        @JoinColumn(name="scope_key",referencedColumnName="scope_key",insertable=false,updatable=false)})
    private ProjectRequirementVersion sourceVersion;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumns({@JoinColumn(name="snapshot_id",referencedColumnName="id",insertable=false,updatable=false),
        @JoinColumn(name="source_version_id",referencedColumnName="requirement_version_id",insertable=false,updatable=false),
        @JoinColumn(name="requirement_id",referencedColumnName="requirement_id",insertable=false,updatable=false),
        @JoinColumn(name="project_id",referencedColumnName="project_id",insertable=false,updatable=false),
        @JoinColumn(name="scope_key",referencedColumnName="scope_key",insertable=false,updatable=false)})
    private RequirementAnalysisSnapshot snapshot;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name="baseline_payload",nullable=false,updatable=false) private String baselinePayload;
    @Column(name="created_by",nullable=false,updatable=false,length=160) private String createdBy;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="current_revision",nullable=false) private long currentRevision;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected ReformulationProposal() {}
    public ReformulationProposal(String id,String scopeKey,Long projectId,Long requirementId,Long sourceVersionId,
            String snapshotId,String baselinePayload,String actor,Instant now) {
        this.id=id;this.scopeKey=scopeKey;this.projectId=projectId;this.requirementId=requirementId;
        this.sourceVersionId=sourceVersionId;this.snapshotId=snapshotId;this.baselinePayload=baselinePayload;
        this.createdBy=actor;this.createdAt=now;this.currentRevision=1;
    }
    public String getId(){return id;} public String getScopeKey(){return scopeKey;}
    public String getBaselinePayload(){return baselinePayload;} public String getCreatedBy(){return createdBy;}
    public Instant getCreatedAt(){return createdAt;} public long getCurrentRevision(){return currentRevision;}
    public void advanceRevision(){currentRevision++;}
}
