package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmTransport;
import com.taxonomy.extension.api.llm.LlmTransportException;
import com.taxonomy.extension.api.llm.ProviderId;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Host policy around an independently supplied single-attempt transport. */
final class ContributedLlmGateway implements LlmGateway {
    private final ProviderId provider;
    private final LlmTransport transport;
    private final AnalysisRuntimeSettings settings;
    private final LlmRecordReplayService recordings;
    private final ObjectMapper mapper;
    private final LlmRequestAdmission admission;

    ContributedLlmGateway(ProviderId provider, LlmTransport transport,
                          AnalysisRuntimeSettings settings, LlmRecordReplayService recordings,
                          ObjectMapper mapper) {
        this.provider = Objects.requireNonNull(provider);
        this.transport = Objects.requireNonNull(transport);
        if (!provider.equals(new ProviderId(transport.providerName()))) {
            throw new IllegalArgumentException("Provider transport identity mismatch: " + provider);
        }
        this.settings = settings;
        this.recordings = recordings;
        this.mapper = Objects.requireNonNull(mapper);
        admission = new LlmRequestAdmission(provider, 0, settings);
    }

    void configureRequestLimits(ProviderRequestLimiter.Limits limits) { admission.configure(limits); }
    void configureProviderPermits(ProviderConcurrencyPermits permits) { admission.configure(permits); }

    @Override public String providerName() { return provider.value(); }

    @Override public String sendHttpRequest(String prompt, String apiKey) {
        AnalysisRunControl.checkpoint();
        String invocation = LlmTransportMeter.newInvocation();
        if (recordings != null && recordings.isReplayMode()) {
            var recorded = recordings.replay(prompt);
            if (recorded.isPresent()) {
                LlmTransportMeter.replay(invocation, provider.value());
                return recorded.get();
            }
            if (!recordings.isFallbackLive()) return null;
        }
        int maximumRetries = Math.max(0, Math.min(6,
                settings == null ? 2 : settings.getInt("llm.retry.max", 2)));
        for (int attempt = 0; ; attempt++) {
            int retry = attempt;
            try {
                String response = admission.execute(() -> {
                    AnalysisRunControl.checkpoint();
                    AnalysisRunControl.phase("LLM_REQUEST", null);
                    return LlmTransportMeter.exchange(invocation, provider.value(), retry, mapper, () -> {
                        String raw;
                        try { raw = transport.sendHttpRequest(prompt, apiKey); }
                        catch (LlmTransportException typed) { throw typed; }
                        catch (RuntimeException unsafeFailure) {
                            AnalysisRunControl.checkpoint();
                            throw new LlmProviderException(LlmProviderException.Reason.REQUEST_REJECTED,
                                    provider + ": transport failed without a typed failure");
                        }
                        if (raw == null) throw new LlmTransportException(
                                LlmTransportException.FailureKind.INVALID_RESPONSE, "Empty provider response");
                        return ResponseEntity.ok(raw);
                    }).getBody();
                });
                AnalysisRunControl.checkpoint();
                if (recordings != null && recordings.isRecordMode()) {
                    recordings.record(prompt, response, provider.value(), null);
                }
                return response;
            } catch (LlmTransportException failure) {
                if (failure.kind() == LlmTransportException.FailureKind.RATE_LIMIT) {
                    if (admission.retryRateLimit(failure.retryAfter().orElse(null), attempt, maximumRetries)) continue;
                } else if (failure.kind() != LlmTransportException.FailureKind.INVALID_RESPONSE
                        && attempt < maximumRetries) {
                    AnalysisRunControl.pause("RETRY_WAIT", ProviderRetryPolicy.backoffMillis(attempt));
                    continue;
                }
                throw hostFailure(failure);
            }
        }
    }

    @Override public String extractResponseText(String rawResponseBody) {
        try { return transport.extractResponseText(rawResponseBody); }
        catch (LlmTransportException failure) { throw hostFailure(failure); }
        catch (RuntimeException unsafeFailure) {
            throw new LlmProviderException(LlmProviderException.Reason.REQUEST_REJECTED,
                    provider + ": invalid response");
        }
    }

    private RuntimeException hostFailure(LlmTransportException failure) {
        // Never propagate a third-party message/body/cause into diagnostics or job state.
        String message = provider + ": " + failure.kind();
        return switch (failure.kind()) {
            case RATE_LIMIT -> new LlmRateLimitException(message);
            case TIMEOUT -> new LlmTimeoutException(message);
            case UNAVAILABLE -> new LlmProviderException(
                    LlmProviderException.Reason.ENDPOINT_UNREACHABLE, message);
            case INVALID_RESPONSE -> new LlmProviderException(
                    LlmProviderException.Reason.REQUEST_REJECTED, message);
        };
    }
}
