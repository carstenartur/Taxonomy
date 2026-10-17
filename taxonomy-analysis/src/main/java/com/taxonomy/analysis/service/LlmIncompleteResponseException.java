package com.taxonomy.analysis.service;

/** A provider stopped before completing its answer; partial text is diagnostic data only. */
public final class LlmIncompleteResponseException extends LlmProviderException {
    private final String rawText;
    private final String finishReason;

    public LlmIncompleteResponseException(String rawText, String finishReason) {
        super(Reason.REQUEST_REJECTED, message(normalize(finishReason)));
        this.rawText = rawText == null ? "" : rawText;
        this.finishReason = normalize(finishReason);
    }

    public String rawText() { return rawText; }
    public String finishReason() { return finishReason; }

    private static String normalize(String reason) {
        return switch (reason == null ? "" : reason) {
            case "length", "max_output_tokens" -> "length";
            case "content_filter" -> "content_filter";
            case "error", "failed" -> "error";
            default -> "incomplete";
        };
    }

    private static String message(String reason) {
        return "LLM_INCOMPLETE_RESPONSE (" + reason + "). " + switch (reason) {
            case "length" -> "Reduce the batch size or increase the configured output token allowance.";
            case "content_filter" -> "The provider filtered its answer; review the submitted content.";
            default -> "The provider did not complete its answer; inspect the provider service status.";
        };
    }
}
