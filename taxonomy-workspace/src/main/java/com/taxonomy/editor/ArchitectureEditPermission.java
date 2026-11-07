package com.taxonomy.editor;

import org.springframework.security.core.Authentication;

/** Optional application policy for the editor's visible editing capability. */
@FunctionalInterface
public interface ArchitectureEditPermission {
    boolean mayEdit(Authentication authentication);
}
