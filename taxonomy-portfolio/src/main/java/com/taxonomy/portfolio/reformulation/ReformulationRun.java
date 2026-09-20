package com.taxonomy.portfolio.reformulation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Minimal durable dispatch/result envelope; per-node leases/recovery are a later package. */
@Entity
@Table(name="reformulation_run",indexes=@Index(name="idx_reform_run_proposal",columnList="proposal_id,scope_key"))
public class ReformulationRun {
    @Id @Column(length=36) private String id;
    @Column(name="proposal_id",nullable=false,updatable=false,length=36) private String proposalId;
    @Column(name="scope_key",nullable=false,updatable=false,length=com.taxonomy.portfolio.model.PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumns({@JoinColumn(name="proposal_id",referencedColumnName="id",insertable=false,updatable=false),
        @JoinColumn(name="scope_key",referencedColumnName="scope_key",insertable=false,updatable=false)})
    private ReformulationProposal proposal;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name="run_payload",nullable=false) private String payload;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected ReformulationRun() {}
    public ReformulationRun(String id,String proposalId,String scopeKey,String payload) {this.id=id;this.proposalId=proposalId;this.scopeKey=scopeKey;this.payload=payload;}
    public String getPayload(){return payload;}
    public void setPayload(String payload){this.payload=payload;}
}
