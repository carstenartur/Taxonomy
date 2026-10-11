package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.ProviderId;

import java.util.Objects;
import java.util.Optional;

/**
 * Strategy for run-bound scenario-replay providers.
 *
 * <p>A scenario replay answers prompts from authored, versioned replies instead
 * of a model. It is never a live, plugin-backed or globally selectable provider:
 * it may only be used when a persisted run binds it explicitly. Generic provider
 * code dispatches on this capability and never on a concrete scenario format.</p>
 */
public interface ScenarioReplayProviders {

    /** Provider-id namespace check, independent of whether the replay is executable. */
    boolean owns(ProviderId provider);

    /** Executable replay for an owned provider id, empty when no authored replies exist. */
    Optional<Replay> find(ProviderId provider);

    /** Replies authored for one replay provider; never falls back to a model. */
    @FunctionalInterface
    interface ReplyBook {
        /** @throws MissingReplyException when the prompt is not answered exactly */
        String reply(String prompt);
    }

    /**
     * Description of one executable replay.
     *
     * @param model             stable model identity recorded in analysis provenance
     * @param endpointIdentity  stable non-network endpoint identity
     * @param displayName       locale-neutral technical provider name
     * @param contentIdentity   fingerprint of the authored replies; part of the analysis configuration digest
     */
    record Replay(ProviderId provider, String model, String endpointIdentity, String displayName,
                  String contentIdentity, ReplyBook replies) {
        public Replay {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(endpointIdentity, "endpointIdentity");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(contentIdentity, "contentIdentity");
            Objects.requireNonNull(replies, "replies");
        }
    }

    /** A replay request the authored replies cannot answer exactly. */
    class MissingReplyException extends RuntimeException {
        public MissingReplyException(String message) { super(message); }
    }

    /** Null object for contexts without scenario replays. */
    ScenarioReplayProviders NONE = new ScenarioReplayProviders() {
        @Override public boolean owns(ProviderId provider) { return false; }
        @Override public Optional<Replay> find(ProviderId provider) { return Optional.empty(); }
    };
}
