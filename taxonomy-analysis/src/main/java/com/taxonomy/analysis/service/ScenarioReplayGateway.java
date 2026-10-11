package com.taxonomy.analysis.service;

import com.taxonomy.analysis.demo.DemoProviderIds;
import com.taxonomy.analysis.demo.DemoReplayException;
import com.taxonomy.analysis.demo.DemoReplyBook;
import com.taxonomy.analysis.demo.DemoScenarioCatalog;

import java.util.Objects;

/**
 * Replaces exactly the external model reply for a product-demo provider.
 * There is no network transport, no record/replay store and no live fallback:
 * an unanswerable prompt fails visibly instead of reaching a real provider.
 */
final class ScenarioReplayGateway implements LlmGateway {
    private final String provider;
    private final DemoReplyBook replies;

    ScenarioReplayGateway(DemoProviderIds.Binding binding, DemoScenarioCatalog catalog) {
        Objects.requireNonNull(binding);
        this.provider = binding.providerId().value();
        this.replies = new DemoReplyBook(catalog.require(binding), binding.iteration());
    }

    @Override public String providerName() { return provider; }

    @Override public String sendHttpRequest(String prompt, String apiKey) {
        AnalysisRunControl.checkpoint();
        String invocation = LlmTransportMeter.newInvocation();
        try {
            String reply = replies.reply(prompt);
            LlmTransportMeter.replay(invocation, provider);
            AnalysisRunControl.checkpoint();
            return reply;
        } catch (DemoReplayException missing) {
            throw new LlmProviderException(LlmProviderException.Reason.REQUEST_REJECTED,
                    provider + ": " + missing.getMessage());
        }
    }

    @Override public String extractResponseText(String rawResponseBody) { return rawResponseBody; }
}
