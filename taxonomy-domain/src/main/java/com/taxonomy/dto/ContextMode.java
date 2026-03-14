package com.taxonomy.dto;

/**
 * Mode of an architecture context.
 *
 * <ul>
 *   <li>{@code READ_ONLY} — viewing a historical or foreign-branch snapshot; no edits allowed</li>
 *   <li>{@code EDITABLE} — the normal working context; commits and materializations are permitted</li>
 *   <li>{@code TEMPORARY} — a throw-away context for comparison or preview; never persisted</li>
 * </ul>
 */
public enum ContextMode {
    READ_ONLY,
    EDITABLE,
    TEMPORARY
}
