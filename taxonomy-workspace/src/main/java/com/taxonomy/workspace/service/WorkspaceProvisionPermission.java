package com.taxonomy.workspace.service;

/** Optional application permission for automatic default-workspace allocation. */
@FunctionalInterface
public interface WorkspaceProvisionPermission {
    boolean mayProvision(String actor);
}
