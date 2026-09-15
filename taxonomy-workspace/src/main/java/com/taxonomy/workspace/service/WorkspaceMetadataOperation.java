package com.taxonomy.workspace.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A workspace lifecycle endpoint that requires authorized metadata, not a ready
 * Git repository. Pin validation still checks the owner and archive/shared flags.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkspaceMetadataOperation {
}
