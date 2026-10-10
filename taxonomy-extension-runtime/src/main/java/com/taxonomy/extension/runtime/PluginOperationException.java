package com.taxonomy.extension.runtime;

/** Stable operator-facing reason; never exposes paths or plugin exception text. */
public final class PluginOperationException extends IllegalStateException {
    private final String code;
    public PluginOperationException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
