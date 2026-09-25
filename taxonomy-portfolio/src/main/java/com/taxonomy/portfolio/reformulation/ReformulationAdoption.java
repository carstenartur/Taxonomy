package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.model.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Insert-only receipt, committed in the SAME transaction as the explicit version transition. */
@Entity
@Table(name="reformulation_adoption",uniqueConstraints=@UniqueConstraint(name="uq_reform_adopt_preview",columnNames="preview_id"),
        indexes=@Index(name="idx_reform_adopt_offer",columnList="proposal_id,scope_key,created_at"))
public class ReformulationAdoption {
    @Id @Column(length=64,updatable=false) private String id;
    @Column(name="proposal_id",nullable=false,updatable=false,length=36) private String proposalId;
    @Column(name="preview_id",nullable=false,updatable=false,length=36) private String previewId;
    @Column(name="scope_key",nullable=false,updatable=false,length=PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @Column(name="command_hash",nullable=false,updatable=false,length=64) private String commandHash;
    @Column(name="requirement_id",nullable=false,updatable=false) private Long requirementId;
    @Column(name="target_version_id",nullable=false,updatable=false) private Long targetVersionId;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="receipt_payload",nullable=false,updatable=false) private String payload;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumns(value={@JoinColumn(name="preview_id",referencedColumnName="id",insertable=false,updatable=false),
            @JoinColumn(name="proposal_id",referencedColumnName="proposal_id",insertable=false,updatable=false),
            @JoinColumn(name="scope_key",referencedColumnName="scope_key",insertable=false,updatable=false)},foreignKey=@ForeignKey(name="fk_reform_adopt_preview"))
    private ReformulationAdoptionPreview preview;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumns(value={@JoinColumn(name="target_version_id",referencedColumnName="id",insertable=false,updatable=false),
            @JoinColumn(name="requirement_id",referencedColumnName="requirement_id",insertable=false,updatable=false),
            @JoinColumn(name="scope_key",referencedColumnName="scope_key",insertable=false,updatable=false)},foreignKey=@ForeignKey(name="fk_reform_adopt_version"))
    private ProjectRequirementVersion version;
    protected ReformulationAdoption() {}
    public ReformulationAdoption(String id,String proposalId,String previewId,String scope,String commandHash,
            Long requirementId,Long targetVersionId,String payload,Instant now) {
        this.id=id;this.proposalId=proposalId;this.previewId=previewId;this.scopeKey=scope;this.commandHash=commandHash;
        this.requirementId=requirementId;this.targetVersionId=targetVersionId;this.payload=payload;this.createdAt=now;
    }
    public String getId(){return id;} public String getProposalId(){return proposalId;}
    public String getScopeKey(){return scopeKey;} public Long getRequirementId(){return requirementId;}
    public String getCommandHash(){return commandHash;} public String getPayload(){return payload;}
    public String getPreviewId(){return previewId;} public Long getTargetVersionId(){return targetVersionId;}
    public ReformulationAdoptionPreview getPreview(){return preview;}
    public ProjectRequirementVersion getVersion(){return version;}
    public Instant getCreatedAt(){return createdAt;}
}
