package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmTransport;
import com.taxonomy.extension.api.llm.LlmTransportException;

/** Public SPI view of an already host-managed gateway; no second policy wrapper. */
final class ProviderTransportView implements LlmTransport {
    private final LlmGateway gateway;

    ProviderTransportView(LlmGateway gateway) { this.gateway = java.util.Objects.requireNonNull(gateway); }

    @Override public String providerName() { return gateway.providerName(); }
    @Override public String extractResponseText(String response) {
        try { return gateway.extractResponseText(response); }
        catch (AnalysisStoppedException stopped) { throw stopped; }
        catch (RuntimeException failure) {
            throw new LlmTransportException(LlmTransportException.FailureKind.INVALID_RESPONSE,
                    providerName() + ": invalid response");
        }
    }
    @Override public String sendHttpRequest(String prompt, String apiKey) {
        try { return gateway.sendHttpRequest(prompt, apiKey); }
        catch (LlmRateLimitException failure) {
            throw new LlmTransportException(LlmTransportException.FailureKind.RATE_LIMIT,
                    providerName() + ": rate limit");
        } catch (LlmTimeoutException failure) {
            throw new LlmTransportException(LlmTransportException.FailureKind.TIMEOUT,
                    providerName() + ": timeout");
        } catch (LlmProviderException failure) {
            throw new LlmTransportException(
                    failure.getReason() == LlmProviderException.Reason.ENDPOINT_UNREACHABLE
                            ? LlmTransportException.FailureKind.UNAVAILABLE
                            : LlmTransportException.FailureKind.INVALID_RESPONSE,
                    providerName() + ": provider failure");
        }
    }
}
