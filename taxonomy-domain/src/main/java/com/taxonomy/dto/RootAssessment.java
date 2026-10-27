package com.taxonomy.dto;

/** A root decision distinguishes supported relevance from an unanswered requirement question. */
public record RootAssessment(Status status, Double score, String reason, String question) {
    public enum Status { RELEVANT, IRRELEVANT, UNRESOLVED }

    public RootAssessment {
        if (status == null || reason == null || reason.isBlank() || reason.length() > 2000)
            throw new IllegalArgumentException("A root assessment requires a status and a bounded nonempty reason");
        reason = reason.strip();
        question = question == null ? "" : question.strip();
        if (question.length() > 1000)
            throw new IllegalArgumentException("A root clarification question must not exceed 1000 characters");
        boolean valid = switch (status) {
            case RELEVANT -> score != null && AnalysisPercentages.valid(score) && score > 0 && question.isEmpty();
            case IRRELEVANT -> score != null && score == 0 && question.isEmpty();
            case UNRESOLVED -> score == null && !question.isEmpty();
        };
        if (!valid) throw new IllegalArgumentException("Root assessment status, score and question disagree");
    }

    public boolean unresolved() { return status == Status.UNRESOLVED; }
}
