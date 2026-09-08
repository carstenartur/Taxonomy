package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Three-way field comparison. No timestamps, transport outcomes or list omissions choose an automatic winner. */
@Component
public class IntegrationDiff {
    private final IntegrationJson json;
    public IntegrationDiff(IntegrationJson json) { this.json = json; }
    public List<IntegrationChange> compare(ExchangeDocument incoming, AuthorityMode mode, List<Identity> mappings, Map<String, Artifact> current) {
        Map<String, Identity> baselines = new TreeMap<>(); mappings.forEach(m -> baselines.put(m.externalId(), m));
        Map<String, Artifact> external = ExchangeItems.flatten(incoming);
        TreeSet<String> ids = new TreeSet<>(external.keySet()); ids.addAll(baselines.keySet());
        List<IntegrationChange> changes = new ArrayList<>();
        for (String id : ids) {
            Identity baseline = baselines.get(id); Artifact next = external.get(id), local = current.get(id);
            if (next == null) {
                if (baseline == null || baseline.removed() || !incoming.completeScope()
                        || (mode != AuthorityMode.MIRROR_READ && mode != AuthorityMode.BIDIRECTIONAL)) continue;
                List<String> conflict = changed(baseline.internal(), local).isEmpty() ? List.of() : List.of("DELETE_VERSUS_LOCAL_CHANGE");
                changes.add(change(id, conflict.isEmpty() ? ChangeKind.REMOVE_CANDIDATE : ChangeKind.CONFLICT, Set.of("removed"), local, baseline.external(), null, conflict));
                continue;
            }
            if (baseline == null || baseline.removed()) {
                List<String> conflicts = baseline != null ? List.of("EXTERNAL_IDENTITY_REUSED") : local != null ? List.of("DUPLICATE_MAPPING") : List.of();
                changes.add(change(id, conflicts.isEmpty() ? ChangeKind.ADD : ChangeKind.CONFLICT, ExchangeItems.fields(next).keySet(), local, baseline == null ? null : baseline.external(), next, conflicts));
                continue;
            }
            Set<String> remoteFields = changed(baseline.external(), next);
            Set<String> localFields = changed(baseline.internal(), local);
            List<String> conflicts = new ArrayList<>();
            for (String field : remoteFields) if (localFields.contains(field)
                    && !Objects.equals(ExchangeItems.fields(local).get(field), ExchangeItems.fields(next).get(field))) conflicts.add(field);
            if (local == null && !remoteFields.isEmpty()) conflicts.add("LOCAL_DELETE_VERSUS_EXTERNAL_CHANGE");
            ChangeKind kind = !conflicts.isEmpty() ? ChangeKind.CONFLICT : remoteFields.isEmpty() ? ChangeKind.UNCHANGED
                    : next.kind() == ArtifactKind.PLACEMENT ? ChangeKind.MOVE : next.kind() == ArtifactKind.RELATION ? ChangeKind.RELATION : ChangeKind.UPDATE;
            changes.add(change(id, kind, remoteFields, local, baseline.external(), next, conflicts));
        }
        return List.copyOf(changes);
    }
    private IntegrationChange change(String id, ChangeKind kind, Set<String> fields, Artifact local, Artifact external, Artifact next, List<String> conflicts) {
        return new IntegrationChange(id, id, kind, fields, json.fingerprint(ExchangeItems.fields(local)), json.fingerprint(ExchangeItems.fields(external)), local, next, conflicts);
    }
    public static Set<String> changed(Artifact before, Artifact after) {
        Map<String, String> left = ExchangeItems.fields(before), right = ExchangeItems.fields(after);
        Set<String> keys = new TreeSet<>(left.keySet()); keys.addAll(right.keySet()); keys.removeIf(key -> Objects.equals(left.get(key), right.get(key))); return keys;
    }
}
