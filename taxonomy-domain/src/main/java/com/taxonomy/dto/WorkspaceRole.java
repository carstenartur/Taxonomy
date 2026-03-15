package com.taxonomy.dto;

/**
 * Role of a workspace in the multi-user architecture.
 *
 * <ul>
 *   <li>{@code PERSONAL} — a per-user workspace for isolated editing</li>
 *   <li>{@code SHARED} — the shared integration workspace for team synchronization</li>
 * </ul>
 */
public enum WorkspaceRole {
    PERSONAL,
    SHARED
}
