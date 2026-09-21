package com.taxonomy.portfolio.reformulation;

/** The submitted human decision does not satisfy the frozen question contract. */
public class ReformulationAnswerException extends RuntimeException {
    public ReformulationAnswerException(String message) { super(message); }
}
