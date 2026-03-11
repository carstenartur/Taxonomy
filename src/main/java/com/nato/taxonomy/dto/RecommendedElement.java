package com.nato.taxonomy.dto;

/**
 * A single element in an architecture recommendation — either a confirmed
 * match (high score) or a proposed fill for an identified gap.
 */
public class RecommendedElement {

    private String nodeCode;
    private String title;
    private String taxonomyRoot;
    private int score;
    private String reasoning;
    private ExplanationTrace explanationTrace;

    public RecommendedElement() {}

    public RecommendedElement(String nodeCode, String title, String taxonomyRoot,
                              int score, String reasoning) {
        this.nodeCode = nodeCode;
        this.title = title;
        this.taxonomyRoot = taxonomyRoot;
        this.score = score;
        this.reasoning = reasoning;
    }

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTaxonomyRoot() { return taxonomyRoot; }
    public void setTaxonomyRoot(String taxonomyRoot) { this.taxonomyRoot = taxonomyRoot; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public ExplanationTrace getExplanationTrace() { return explanationTrace; }
    public void setExplanationTrace(ExplanationTrace explanationTrace) { this.explanationTrace = explanationTrace; }
}
