package com.taxonomy.relations.service;

/**
 * Defines whether one Git-authoritative relation decision represents an active
 * relation in the rebuildable branch projection.
 *
 * <p>Historic DSL documents may omit {@code status}; proposed and provisional
 * relations are also retained for compatibility with existing branch views.
 * A rejected relation remains in TaxDSL as audit evidence but is never exposed
 * as an active projected relation.</p>
 */
final class RelationDecisionStatusPolicy {

    private RelationDecisionStatusPolicy() {
    }

    static boolean isRelationPresent(String status) {
        return status == null || !"rejected".equalsIgnoreCase(status.strip());
    }
}
