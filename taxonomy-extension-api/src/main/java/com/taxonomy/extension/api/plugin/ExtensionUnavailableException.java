package com.taxonomy.extension.api.plugin;

/** No active matching contribution exists, including while its plugin is draining. */
public final class ExtensionUnavailableException extends IllegalStateException {
    public ExtensionUnavailableException(ExtensionKey key) {
        super("Extension unavailable: " + key.kind() + "/" + key.id());
    }
}
