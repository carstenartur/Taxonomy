package com.taxonomy.analysis.service;

import java.util.Objects;

/**
 * Replaces exactly the external model reply for a scenario-replay provider.
 * There is no network transport, no record/replay store and no live fallback:
 * an unanswerable prompt fails visibly instead of reaching a real provider.
 */
final class ScenarioReplayGateway implements LlmGateway {
    private final String provider;
    private final ScenarioReplayProviders.ReplyBook replies;

    ScenarioReplayGateway(ScenarioReplayProviders.Replay replay) {
        Objects.requireNonNull(replay);
        this.provider = replay.provider().value();
        this.replies = replay.replies();
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
        } catch (ScenarioReplayProviders.MissingReplyException missing) {
            throw new LlmProviderException(LlmProviderException.Reason.REQUEST_REJECTED,
                    provider + ": " + missing.getMessage());
        }
    }

    @Override public String extractResponseText(String rawResponseBody) { return rawResponseBody; }
}
