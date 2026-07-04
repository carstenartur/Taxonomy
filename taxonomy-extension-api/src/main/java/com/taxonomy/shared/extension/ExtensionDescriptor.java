package com.taxonomy.shared.extension;

import java.io.Serializable;

/**
 * Framework-free descriptor for exposing extension metadata without
 * leaking implementation classes.
 *
 * @param id unique extension identifier within one {@link ExtensionKind}
 * @param displayName human-readable extension name
 * @param description concise description safe for REST/UI exposure
 * @param kind stable extension category
 */
public record ExtensionDescriptor(
        String id,
        String displayName,
        String description,
        ExtensionKind kind
) implements Serializable {
}
