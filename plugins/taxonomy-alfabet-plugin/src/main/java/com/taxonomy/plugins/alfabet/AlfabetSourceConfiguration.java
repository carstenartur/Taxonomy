package com.taxonomy.plugins.alfabet;

import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import java.util.*;

/** The fixed alfabet-read@1 option schema; never accepts browser-supplied transport or secret settings. */
final class AlfabetSourceConfiguration {
    static final String SECRET = "ALFABET_BOUND_SECRET", PRINCIPAL = "ALFABET_BOUND_PRINCIPAL";
    private final Request request;
    private final String currentProfile;
    private final AlfabetReadProfile.Grant grant;
    private final List<AlfabetReadProfile.Report> reports;

    private AlfabetSourceConfiguration(Request request, String currentProfile, AlfabetReadProfile.Grant grant,
                                        List<AlfabetReadProfile.Report> reports) {
        this.request = request; this.currentProfile = currentProfile; this.grant = grant; this.reports = List.copyOf(reports);
    }

    static AlfabetSourceConfiguration from(Request request) {
        if (!"alfabet".equals(request.context().externalScope().systemType())
                || !"alfabet-read".equals(request.context().profile()) || !"1".equals(request.context().profileVersion()))
            throw new IllegalArgumentException("Unsupported Alfabet read profile");
        if (!request.selectedIds().isEmpty()) throw new IllegalArgumentException("Profile 1 requires administratively scoped reports; ID selection is unsupported");
        AlfabetReadProfile.validateBase(request.origin(), false);
        Map<String,String> options = request.sourceOptions();
        var consumed = new HashSet<String>();
        String currentProfile = required(options, consumed, "currentProfile");
        var grant = AlfabetReadProfile.Grant.valueOf(required(options, consumed, "authentication"));
        var reports = new ArrayList<AlfabetReadProfile.Report>();
        for (TypeProjection projection : request.projections()) {
            String report = required(options, consumed, "object." + projection.sourceType() + ".report");
            var fields = new TreeMap<>(projection.textFields());
            if (fields.containsKey("__subtype")) throw new IllegalArgumentException("Reserved evidence field name");
            if (projection.subtypeField() != null) fields.put("__subtype", projection.subtypeField());
            reports.add(new AlfabetReadProfile.Report(report, projection.sourceType(), AlfabetReadProfile.Kind.OBJECT, fields));
        }
        var aliases = new TreeSet<String>();
        for (String key : options.keySet()) if (key.startsWith("relation.")) {
            String[] parts = key.split("\\.", -1);
            if (parts.length != 3 || !parts[1].matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("Invalid relation option");
            aliases.add(parts[1]);
        }
        var names = new HashSet<String>();
        reports.forEach(r -> { if (!names.add(r.name())) throw new IllegalArgumentException("Duplicate report name"); });
        for (String alias : aliases) {
            String prefix = "relation." + alias + ".";
            String name = required(options, consumed, prefix + "report");
            String className = required(options, consumed, prefix + "className");
            var fields = new TreeMap<String,String>();
            for (String field : List.of("sourceRef", "targetRef")) fields.put(field, required(options, consumed, prefix + field));
            for (String field : List.of("activeFrom", "activeUntil"))
                if (options.containsKey(prefix + field)) fields.put(field, required(options, consumed, prefix + field));
            var report = new AlfabetReadProfile.Report(name, className, AlfabetReadProfile.Kind.RELATION, fields);
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate report name");
            if (request.readUsageRelations()) reports.add(report);
        }
        if (names.size() > 16) throw new IllegalArgumentException("Configure at most 16 reports");
        if (request.readUsageRelations() && aliases.isEmpty()) throw new IllegalArgumentException("No usage report configured");
        if (!consumed.equals(options.keySet())) throw new IllegalArgumentException("Unknown Alfabet source option");
        var configuration = new AlfabetSourceConfiguration(request, currentProfile, grant, reports);
        configuration.profile(false); // Complete validation before consulting any host access grant.
        return configuration;
    }

    AlfabetReadProfile profile(boolean privateNetworks) {
        var l = request.limits();
        return new AlfabetReadProfile(request.context().externalScope(), request.configurationRevision(), request.origin(),
                currentProfile, new AlfabetReadProfile.Authentication(grant, authenticates() ? PRINCIPAL : null, SECRET), reports,
                new AlfabetReadProfile.Limits(l.pageSize(), l.maxRequests() - authenticationRequests(), l.maxObjects() + l.maxRelations(),
                        l.maxResponseBytes(), l.maxTotalBytes(), l.timeoutSeconds()), privateNetworks, false);
    }
    int authenticationRequests() { return authenticates() ? 1 : 0; }
    private boolean authenticates() { return grant != AlfabetReadProfile.Grant.BEARER; }
    private static String required(Map<String,String> options, Set<String> consumed, String key) {
        String value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Missing Alfabet profile setting");
        consumed.add(key); return value;
    }
}
