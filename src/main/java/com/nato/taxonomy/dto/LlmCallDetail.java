package com.nato.taxonomy.dto;

import java.util.Map;

/**
 * Captures details of a single LLM API call: the prompt sent, the raw text response,
 * parsed scores, provider name, and call duration.
 */
public class LlmCallDetail {

    private Map<String, Integer> scores;
    private String prompt;
    private String rawResponse;
    private String provider;
    private long durationMs;

    public LlmCallDetail() {}

    public Map<String, Integer> getScores() { return scores; }
    public void setScores(Map<String, Integer> scores) { this.scores = scores; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
