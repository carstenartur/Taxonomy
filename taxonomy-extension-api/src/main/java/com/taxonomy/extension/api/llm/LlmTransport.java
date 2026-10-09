package com.taxonomy.extension.api.llm;

/**
 * One provider attempt and its response decoder. Admission, budgets, retries,
 * recording and cancellation belong to the host. Implementations must not log or
 * retain the per-call credential, retry internally, or start background work.
 */
public interface LlmTransport {
    String sendHttpRequest(String prompt, String apiKey);
    String extractResponseText(String rawResponseBody);
    String providerName();
}
