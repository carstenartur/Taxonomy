package com.taxonomy.composition.analysis;

import com.taxonomy.dsl.planning.ArchitectureBrief;
import java.util.HashMap;
import java.util.List;

/** Read-only profile evidence for the selected model. No editor command or admission is executed. */
public record ProfilePreview(String repositoryId, String workspaceScopeKey, String branch, String commit,
        Long semanticRevision, String source, boolean editable, ArchitectureBrief.Snapshot brief, Decision decision) {
    public enum Decision { ASSIGNED, UNASSIGNED, OPEN, CONFLICTING_EDITIONS, UNRESOLVED }

    static Decision decision(ArchitectureBrief.Snapshot brief) {
        if (brief.problem() != null) return Decision.UNRESOLVED;
        if (!brief.present() || brief.references().isEmpty()) return Decision.UNASSIGNED;
        var editions = new HashMap<List<String>, String>();
        boolean open = false;
        for (var reference : brief.references()) {
            var values = reference.values();
            String role = values.get("role");
            if ("open".equals(role)) open = true;
            // Distinct sections, purposes or scopes may deliberately cite different editions.
            if ("binding".equals(role)) {
                String edition = values.get("edition");
                if (edition == null) {
                    open = true;
                } else {
                    var subject = List.of(reference.sourceId(), values.get("scope"), values.getOrDefault("appliesTo", ""),
                            values.getOrDefault("section", ""), values.getOrDefault("purpose", ""));
                    String other = editions.putIfAbsent(subject, edition);
                    if (other != null && !other.equals(edition)) return Decision.CONFLICTING_EDITIONS;
                }
            }
        }
        return open ? Decision.OPEN : Decision.ASSIGNED;
    }
}
