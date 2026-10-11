package com.taxonomy.analysis.demo;

/** A replay request that the scenario cannot answer exactly. Never triggers a live call. */
public class DemoReplayException extends com.taxonomy.analysis.service.ScenarioReplayProviders.MissingReplyException {
    public enum Code { UNKNOWN_PROMPT, MISSING_REPLY, ITERATION_MISMATCH }

    private final Code code;

    public DemoReplayException(Code code, String message) {
        super("DEMO_REPLAY_" + code + ": " + message);
        this.code = code;
    }

    public Code code() { return code; }
}
