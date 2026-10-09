package com.taxonomy.analysis.service;

/** Stable durable stop reason; never includes credentials or plugin-supplied exception text. */
public final class ProviderPluginUnavailableException extends IllegalStateException {
    public ProviderPluginUnavailableException(String reason) { super(reason); }
    public String reason() { return getMessage(); }
}
