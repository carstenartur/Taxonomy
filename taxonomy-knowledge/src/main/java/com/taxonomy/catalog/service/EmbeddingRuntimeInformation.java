package com.taxonomy.catalog.service;

/** Public, read-only embedding status. Operator paths, prompts and raw errors stay in the runtime. */
public record EmbeddingRuntimeInformation(State state, ConfiguredProfile configuredProfile,
                                          LoadedArtifact loadedArtifact) {

    public enum State { DISABLED, NOT_LOADED, LOADING, READY, FAILED, STOPPED }

    public enum Source { PROFILE_DEFAULT, OPERATOR_OVERRIDE }

    public enum Provenance { VERIFIED_UPSTREAM, CUSTOM_OR_UNKNOWN }

    public record ConfiguredProfile(String id, Source source, UpstreamModel upstream) { }

    /** Metadata for a pinned upstream export. */
    public record UpstreamModel(String modelId, String origin, String license, String revision) { }

    public record LoadedArtifact(Provenance provenance, UpstreamModel upstream, String modelSha256,
                                 String tokenizerSha256, String inferenceVersion, String inferenceIdentity) { }
}
