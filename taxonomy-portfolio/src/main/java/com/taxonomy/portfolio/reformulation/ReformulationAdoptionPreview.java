package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Insert-only comparison: exact original, current version, proposed text, questions and decisions. */
@Entity
@Table(name="reformulation_adoption_preview", uniqueConstraints=@UniqueConstraint(name="uq_reform_adopt_preview_scope",columnNames={"id","proposal_id","scope_key"}),
        indexes=@Index(name="idx_reform_adopt_preview_offer",columnList="proposal_id,scope_key"))
public class ReformulationAdoptionPreview {
    @Id @Column(length=36,updatable=false) private String id;
    @Column(name="proposal_id",nullable=false,updatable=false,length=36) private String proposalId;
    @Column(name="scope_key",nullable=false,updatable=false,length=PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @Column(name="content_hash",nullable=false,updatable=false,length=64) private String contentHash;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="preview_payload",nullable=false,updatable=false) private String payload;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumns(value={@JoinColumn(name="proposal_id",referencedColumnName="id",insertable=false,updatable=false),
            @JoinColumn(name="scope_key",referencedColumnName="scope_key",insertable=false,updatable=false)},foreignKey=@ForeignKey(name="fk_reform_adopt_preview_offer"))
    private ReformulationProposal proposal;
    protected ReformulationAdoptionPreview() {}
    public ReformulationAdoptionPreview(String id,String proposalId,String scopeKey,String hash,String payload,Instant now) {
        this.id=id;this.proposalId=proposalId;this.scopeKey=scopeKey;this.contentHash=hash;this.payload=payload;this.createdAt=now;
    }
    public String getPayload(){return payload;} public String getContentHash(){return contentHash;}
}
