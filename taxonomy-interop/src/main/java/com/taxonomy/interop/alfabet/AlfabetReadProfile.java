package com.taxonomy.interop.alfabet;

import com.taxonomy.extension.api.integration.IntegrationContracts.ExternalScope;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Administrator-supplied, versioned read contract. Contains secret references, never secret values. */
public record AlfabetReadProfile(ExternalScope scope, String mappingVersion, URI baseUri,
                                 String currentProfile, Authentication authentication,
                                 List<Report> reports, Limits limits,
                                 boolean allowPrivateNetworks, boolean allowInsecureHttp) {
    public enum Grant { BEARER, CLIENT_CREDENTIALS, PASSWORD }
    public enum Kind { OBJECT, RELATION }

    public record Authentication(Grant grant, String principalEnvironmentVariable, String secretEnvironmentVariable) {
        public Authentication {
            Objects.requireNonNull(grant, "grant");
            environmentReference(secretEnvironmentVariable);
            if (grant != Grant.BEARER) environmentReference(principalEnvironmentVariable);
            else if (principalEnvironmentVariable != null) throw new IllegalArgumentException("Bearer auth has no principal reference");
        }
    }

    /** fields maps local evidence labels to configured report column names. No catalogue IDs are inferred. */
    public record Report(String name, String className, Kind kind, Map<String, String> fields) {
        public Report {
            text(name, 200); text(className, 200); Objects.requireNonNull(kind, "kind");
            fields = Map.copyOf(fields);
            if (fields.isEmpty() || fields.size() > 32) throw new IllegalArgumentException("Configure 1 to 32 evidence fields");
            fields.forEach((key, column) -> { text(key, 64); text(column, 256); });
            if (kind == Kind.RELATION && (!fields.containsKey("sourceRef") || !fields.containsKey("targetRef")))
                throw new IllegalArgumentException("Relation reports require sourceRef and targetRef column mappings");
        }
    }

    /** Budgets apply across all reports; neither pagination nor token acquisition resets the deadline. */
    public record Limits(int pageSize, int maxPages, int maxRows, int maxResponseBytes, int maxTotalBytes, int timeoutSeconds) {
        public Limits {
            bound(pageSize, 1, 1000); bound(maxPages, 1, 200); bound(maxRows, 1, 10000);
            bound(maxResponseBytes, 1024, 2 * 1024 * 1024);
            bound(maxTotalBytes, maxResponseBytes, 16 * 1024 * 1024); bound(timeoutSeconds, 1, 120);
        }
        public static Limits defaults() { return new Limits(250, 40, 5000, 2 * 1024 * 1024, 16 * 1024 * 1024, 60); }
    }

    public AlfabetReadProfile {
        Objects.requireNonNull(scope, "scope"); text(mappingVersion, 64); text(currentProfile, 200);
        Objects.requireNonNull(authentication, "authentication"); Objects.requireNonNull(limits, "limits");
        reports = List.copyOf(reports);
        if (reports.isEmpty() || reports.size() > 16) throw new IllegalArgumentException("Configure 1 to 16 read reports");
        var names = new HashSet<String>();
        for (Report report : reports) if (!names.add(report.name())) throw new IllegalArgumentException("Duplicate report name");
        validateBase(baseUri, allowInsecureHttp);
    }

    public AlfabetReadProfile withAuthentication(Authentication value) {
        return new AlfabetReadProfile(scope, mappingVersion, baseUri, currentProfile, value, reports, limits, allowPrivateNetworks, allowInsecureHttp);
    }
    public AlfabetReadProfile withReports(List<Report> value) {
        return new AlfabetReadProfile(scope, mappingVersion, baseUri, currentProfile, authentication, value, limits, allowPrivateNetworks, allowInsecureHttp);
    }

    static void validateBase(URI base, boolean insecure) {
        if (base == null || base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || base.toString().length() > 2048
                || !("https".equals(base.getScheme()) || insecure && "http".equals(base.getScheme()))
                || base.getPort() < -1 || base.getPort() == 0 || base.getPort() > 65535)
            throw new IllegalArgumentException("Configure an explicit HTTPS API-server application base");
        String path = base.getRawPath();
        if (path == null || !path.endsWith("/") || path.contains("%") || path.contains("\\") || path.contains("//")
                || path.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Configure an unescaped application path ending in slash");
        for (String part : path.split("/")) if (part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("Dot path segments are not permitted");
    }
    private static void environmentReference(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}"))
            throw new IllegalArgumentException("Use an environment-variable reference for credentials");
    }
    private static void bound(int value, int low, int high) {
        if (value < low || value > high) throw new IllegalArgumentException("Read budget outside supported bounds");
    }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Missing or invalid profile field");
    }
}
