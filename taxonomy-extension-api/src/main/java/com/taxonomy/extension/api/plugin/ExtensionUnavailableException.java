package com.taxonomy.extension.api.plugin;

/** No active matching contribution exists, including while its plugin is draining. */
public final class ExtensionUnavailableException extends IllegalStateException {
    public enum Reason { UNKNOWN, UNAVAILABLE }
    private final Reason reason;

    public ExtensionUnavailableException(ExtensionKey key) {
        this(key, Reason.UNAVAILABLE);
    }

    public ExtensionUnavailableException(ExtensionKey key, Reason reason) {
        super("Extension unavailable: " + key.kind() + "/" + key.id());
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason reason() { return reason; }
}
