package com.taxonomy.portfolio.reformulation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Durable dispatch/result envelope with explicit cancellation audit; automatic leases remain separate. */
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
    @Column(name="cancelled_by",length=255) private String cancelledBy;
    @Column(name="cancelled_at") private Instant cancelledAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected ReformulationRun() {}
    public ReformulationRun(String id,String proposalId,String scopeKey,String payload) {this.id=id;this.proposalId=proposalId;this.scopeKey=scopeKey;this.payload=payload;}
    public String getPayload(){return payload;}
    public void setPayload(String payload){this.payload=payload;}
    public void recordCancellation(String actor, Instant occurredAt) {
        if (cancelledAt == null) { cancelledBy = actor; cancelledAt = occurredAt; }
    }
}
