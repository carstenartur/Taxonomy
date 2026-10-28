package com.taxonomy.plugins.alfabet;

import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.extension.api.inventory.InventorySourceExtension;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Explicit report reads and evidence normalization; never classification against the taxonomy or a remote write. */
public final class AlfabetInventorySource implements InventorySourceExtension {
    private static final long serialVersionUID = 1L;
    @Override public String id() { return "alfabet"; }
    @Override public String displayName() { return "Alfabet inventory (read-only)"; }
    @Override public String description() { return "Configured object and usage report observations; no suitability verdict or remote write"; }

    @Override public Observation read(Request request, ReadAccess access) {
        Objects.requireNonNull(request); Objects.requireNonNull(access);
        Instant started = Instant.now();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(request.limits().timeoutSeconds());
        var configuration = AlfabetSourceConfiguration.from(request);
        AlfabetReadProfile profile;
        AlfabetReadClient client;
        try {
            profile = configuration.profile(access.allowPrivateNetworks());
            client = new AlfabetReadClient(reference -> switch (reference) {
                case AlfabetSourceConfiguration.SECRET -> credential(access::credential);
                case AlfabetSourceConfiguration.PRINCIPAL -> credential(access::principal);
                default -> throw new ReadFailure("ALFABET_CREDENTIAL_UNAVAILABLE");
            }, access.tls());
        } catch (RuntimeException failure) { throw new ReadFailure("ALFABET_ACCESS_UNAVAILABLE"); }
        var snapshot = client.read(profile, request.limits().maxObjects(), request.limits().maxRelations(), deadline);
        var objects = new ArrayList<ObservedObject>();
        var relations = new ArrayList<ObservedRelation>();
        var identities = new HashSet<ExternalKey>();
        for (var report : snapshot.reports()) for (var row : report.rows()) {
            AlfabetHttpTransport.requireTime(deadline);
            if (report.kind() == AlfabetReadProfile.Kind.OBJECT) {
                TypeProjection projection = request.projections().stream().filter(p -> p.sourceType().equals(row.className())).findFirst().orElseThrow();
                ExternalKey key = key(request, row.refStr());
                if (!identities.add(key)) throw new ReadFailure("ALFABET_DUPLICATE_OBJECT");
                Field subtype = projection.subtypeField() == null ? Field.missing(State.NOT_SUPPORTED, "subtype") : field(row, projection.subtypeField());
                var fields = new TreeMap<String,Field>();
                projection.textFields().forEach((name, column) -> fields.put(name, field(row, column)));
                objects.add(new ObservedObject(key, row.className(), subtype, projection.classify(subtype), null, fields, State.NOT_SUPPORTED, List.of()));
            } else {
                var definition = profile.reports().stream().filter(r -> r.name().equals(report.report())).findFirst().orElseThrow();
                var fields = new TreeMap<String,Field>();
                definition.fields().forEach((name, column) -> fields.put(name, field(row, column)));
                fields.put("baseRef", Field.text(row.refStr(), "RefStr"));
                relations.add(new ObservedRelation("row-" + (relations.size() + 1), null,
                        key(request, row.fields().get("sourceRef")), key(request, row.fields().get("targetRef")),
                        report.report(), "USES", fields));
            }
        }
        var limitations = new HashSet<>(Set.of("NO_ATOMIC_SNAPSHOT", "NO_DELETION_AUTHORITY", "NO_AVAILABILITY_OR_SUITABILITY_VERDICT",
                "WORKSPACE_BINDING_OPERATOR_DECLARED", "REPORTS_EXHAUSTED_NOT_GLOBAL_INVENTORY", "LIFECYCLE_NOT_INTERPRETED"));
        if (!request.readUsageRelations()) limitations.add("RELATIONS_NOT_REQUESTED");
        if (relations.stream().anyMatch(r -> !identities.contains(r.source()) || !identities.contains(r.target())))
            limitations.add("RELATION_ENDPOINTS_OUTSIDE_OBSERVATION");
        AlfabetHttpTransport.requireTime(deadline);
        return new Observation(request, started, Instant.now(), Completeness.SELECTED_SCOPE_READ_NON_ATOMIC,
                snapshot.pageCount() + configuration.authenticationRequests(), snapshot.responseBytes(), objects, relations, limitations);
    }

    private static ExternalKey key(Request request, String id) { return new ExternalKey(request.instanceId(), "refstr", id); }
    private static Field field(AlfabetSnapshot.Row row, String column) {
        if (row.columns().containsKey(column)) return Field.text(row.columns().get(column), column);
        return Field.missing(row.nullColumns().contains(column) ? State.NULL : State.ABSENT, column);
    }
    private static String credential(Supplier<char[]> supplier) {
        char[] value = null;
        try {
            value = supplier.get();
            if (value == null || value.length == 0 || value.length > 8192) throw new ReadFailure("ALFABET_CREDENTIAL_UNAVAILABLE");
            return new String(value);
        } catch (RuntimeException failure) { throw new ReadFailure("ALFABET_CREDENTIAL_UNAVAILABLE"); }
        finally { if (value != null) Arrays.fill(value, '\0'); }
    }
}
