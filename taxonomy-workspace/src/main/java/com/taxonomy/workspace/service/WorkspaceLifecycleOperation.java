package com.taxonomy.workspace.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A workspace lifecycle endpoint that remains reachable before provisioning.
 * Explicit request pins must still select owned, non-archived private metadata;
 * this marker never authorizes repository reads or creates a repository context.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WorkspaceLifecycleOperation {
}
