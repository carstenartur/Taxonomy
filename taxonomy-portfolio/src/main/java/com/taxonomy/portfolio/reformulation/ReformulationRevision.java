package com.taxonomy.portfolio.reformulation;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
/** Append-only immutable revision payload, including predecessor and actor. */
@Entity
@Table(name="reformulation_revision",uniqueConstraints=@UniqueConstraint(name="uq_reform_revision",columnNames={"proposal_id","revision_number"}))
@org.hibernate.annotations.Immutable
public class ReformulationRevision {
    @Id @Column(length=80) private String id;
    @Column(name="proposal_id",nullable=false,updatable=false,length=36) private String proposalId;
    @Column(name="scope_key",nullable=false,updatable=false,length=com.taxonomy.portfolio.model.PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @Column(name="revision_number",nullable=false,updatable=false) private long number;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name="revision_payload",nullable=false,updatable=false) private String payload;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumns({@JoinColumn(name="proposal_id",referencedColumnName="id",insertable=false,updatable=false),
            @JoinColumn(name="scope_key",referencedColumnName="scope_key",insertable=false,updatable=false)})
    private ReformulationProposal proposal;
    protected ReformulationRevision(){}
    public ReformulationRevision(String proposalId,String scopeKey,long number,String payload) {
        this.id=proposalId+":"+number;this.proposalId=proposalId;this.scopeKey=scopeKey;this.number=number;this.payload=payload;
    }
    public String getPayload(){return payload;}
}
