package com.taxonomy.shared.extension;

import java.io.Serializable;

/**
 * Base contract for internal taxonomy extension points.
 */
public interface TaxonomyExtension extends Serializable {

    /**
     * Stable unique identifier within the owning {@link ExtensionKind}.
     */
    String id();

    /**
     * Human-readable extension name safe for UI/REST exposure.
     */
    String displayName();

    /**
     * Concise description safe for UI/REST exposure.
     */
    default String description() {
        return "";
    }

    /**
     * Stable category of this extension.
     */
    ExtensionKind kind();

    /**
     * Generic descriptor view safe for REST/UI exposure.
     */
    default ExtensionDescriptor extensionDescriptor() {
        return new ExtensionDescriptor(id(), displayName(), description(), kind());
    }
}
