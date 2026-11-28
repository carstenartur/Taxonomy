package com.taxonomy.workspace.service;

/** Initial source for a new architecture. Forks and recovered repositories retain their existing source. */
@FunctionalInterface
public interface ProjectCatalogueSeed {
    String newProject(String namespace);

    record Choice(String id, String name) {}
    default java.util.List<Choice> choices() { return java.util.List.of(); }
    default String newProject(String namespace, String provider) {
        if (provider != null) throw new IllegalArgumentException("Unknown project data provider");
        return newProject(namespace);
    }
    static String requireProvider(String provider) {
        if (provider == null || !provider.matches("[a-z][a-z0-9.-]{0,127}/[a-z][a-z0-9.-]{0,127}"))
            throw new IllegalArgumentException("Select a registered plugin and dataset identifier");
        return provider;
    }
}
