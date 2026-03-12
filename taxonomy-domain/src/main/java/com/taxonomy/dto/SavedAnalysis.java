package com.taxonomy.dto;

import java.util.Map;

/**
 * DTO representing a saved (exported/imported) taxonomy analysis result.
 *
 * <p>Semantic distinction:
 * <ul>
 *   <li>A node code <b>present with value {@code 0}</b> → the node was <b>evaluated and scored 0%</b> (not relevant)</li>
 *   <li>A node code <b>absent / {@code null}</b> → the node was <b>not yet evaluated</b></li>
 * </ul>
 */
public class SavedAnalysis {

    /** Format version — currently always {@code 1}. */
    private int version = 1;

    /** The business requirement text that was analyzed. */
    private String requirement;

    /** ISO 8601 timestamp of when the analysis was performed or exported. */
    private String timestamp;

    /** Name of the LLM provider that generated these scores (informational). */
    private String provider;

    /**
     * Node code → score (0–100).
     * {@code 0} means the node was evaluated but scored zero (not relevant).
     * An absent key means the node was not evaluated at all.
     */
    private Map<String, Integer> scores;

    /**
     * Node code → reason text (optional, may be sparse).
     */
    private Map<String, String> reasons;

    public SavedAnalysis() {}

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public Map<String, Integer> getScores() { return scores; }
    public void setScores(Map<String, Integer> scores) { this.scores = scores; }

    public Map<String, String> getReasons() { return reasons; }
    public void setReasons(Map<String, String> reasons) { this.reasons = reasons; }
}
