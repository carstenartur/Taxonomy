package com.taxonomy.interop;

/** Host capability check for execution selected from a retained integration operation. */
public interface IntegrationOperationPermission {
    enum Action { IMPORT, EXPORT, PUBLISH }

    boolean permits(String actor, Action action);
}
