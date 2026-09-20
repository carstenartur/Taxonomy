package com.taxonomy.portfolio.reformulation;
public final class ReformulationPreconditionException extends RuntimeException {
    public ReformulationPreconditionException(){super("Proposal revision changed; reload before saving a draft");}
}
