package com.taxonomy.workspace.service;

/** Initial source for a new architecture. Forks and recovered repositories retain their existing source. */
@FunctionalInterface
public interface ProjectCatalogueSeed {
    String newProject(String namespace);
}
