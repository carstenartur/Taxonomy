package com.taxonomy.relations.service;

import com.taxonomy.model.ProposalStatus;

/** Human review decision represented both in TaxDSL and the proposal projection. */
public enum ProposalReviewDecision {
    ACCEPT("accepted", ProposalStatus.ACCEPTED),
    REJECT("rejected", ProposalStatus.REJECTED);

    private final String dslStatus;
    private final ProposalStatus proposalStatus;

    ProposalReviewDecision(String dslStatus, ProposalStatus proposalStatus) {
        this.dslStatus = dslStatus;
        this.proposalStatus = proposalStatus;
    }

    public String dslStatus() {
        return dslStatus;
    }

    public ProposalStatus proposalStatus() {
        return proposalStatus;
    }
}
