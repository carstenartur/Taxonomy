package com.taxonomy.dto;

import java.util.Map;

/**
 * Captures details of a single LLM API call: the prompt sent, the raw text response,
 * parsed scores, reasons per node, provider name, and call duration.
 */
public class LlmCallDetail {

    private Map<String, Integer> scores;
    private Map<String, String> reasons;
    private String prompt;
    private String rawResponse;
    private String provider;
    private long durationMs;
    private String error;

    public LlmCallDetail() {}

    public Map<String, Integer> getScores() { return scores; }
    public void setScores(Map<String, Integer> scores) { this.scores = scores; }

    public Map<String, String> getReasons() { return reasons; }
    public void setReasons(Map<String, String> reasons) { this.reasons = reasons; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
