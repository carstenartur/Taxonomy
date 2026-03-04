package com.nato.taxonomy.dto;

import java.util.List;
import java.util.Map;

public class AnalysisResult {

    private Map<String, Integer> scores;
    private List<TaxonomyNodeDto> tree;

    public AnalysisResult() {}

    public AnalysisResult(Map<String, Integer> scores, List<TaxonomyNodeDto> tree) {
        this.scores = scores;
        this.tree = tree;
    }

    public Map<String, Integer> getScores() { return scores; }
    public void setScores(Map<String, Integer> scores) { this.scores = scores; }

    public List<TaxonomyNodeDto> getTree() { return tree; }
    public void setTree(List<TaxonomyNodeDto> tree) { this.tree = tree; }
}
