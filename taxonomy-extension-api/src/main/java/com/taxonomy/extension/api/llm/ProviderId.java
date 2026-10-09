package com.taxonomy.extension.api.llm;

import java.io.Serializable;
import java.util.Locale;

/** Stable open provider identity. Wire contracts continue to use {@link #value()}. */
public record ProviderId(String value) implements Serializable {
    public ProviderId {
        if (value == null) throw new IllegalArgumentException("Provider ID is required");
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid provider ID");
        }
    }

    @Override public String toString() { return value; }
}
