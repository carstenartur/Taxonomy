package com.taxonomy.security.access;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Named capabilities shared by request authorization and the OpenAPI description. */
public enum ApiPermission {
    ARCHITECTURE_READ("architecture:read", "Read architecture and taxonomy data", Effect.READ,
            "READER", "USER", "ARCHITECT", "ADMIN"),
    ARCHITECTURE_WRITE("architecture:write", "Create or change business data", Effect.WRITE, "ARCHITECT"),
    ARCHITECTURE_DELETE("architecture:delete", "Call dedicated DELETE endpoints; command and DSL edits use architecture:write", Effect.DELETE, "ARCHITECT"),
    ARCHITECTURE_PUBLISH("architecture:publish", "Publish or synchronize architecture changes", Effect.WRITE, "ARCHITECT"),
    ANALYSIS_READ("analysis:read", "Read existing analysis results", Effect.READ,
            "READER", "USER", "ARCHITECT", "ADMIN"),
    ANALYSIS_RUN("analysis:run", "Execute analyses and manage personal analysis drafts", Effect.ANALYSIS,
            "USER", "ARCHITECT"),
    INTEGRATION_IMPORT("integration:import", "Import or apply integration changes", Effect.WRITE, "ARCHITECT"),
    INTEGRATION_EXPORT("integration:export", "Generate or download permitted exports", Effect.EXPORT,
            "READER", "USER", "ARCHITECT", "ADMIN"),
    REPOSITORY_MANAGE("repository:manage", "Manage repository memberships as an authorized owner", Effect.ADMINISTRATION,
            "ARCHITECT", "ADMIN"),
    BACKUP_READ("backup:read", "Read or download an authorized backup", Effect.READ,
            "READER", "USER", "ARCHITECT", "ADMIN"),
    BACKUP_CREATE("backup:create", "Create or cancel an authorized backup job", Effect.ADMINISTRATION,
            "USER", "ARCHITECT", "ADMIN"),
    ADMIN_USERS("admin:users", "Manage users, access and browser sessions", Effect.ADMINISTRATION, "ADMIN"),
    ADMIN_CONFIGURATION("admin:configuration", "Manage technical configuration", Effect.ADMINISTRATION, "ADMIN"),
    ADMIN_DIAGNOSTICS("admin:diagnostics", "Read administrative diagnostics", Effect.ADMINISTRATION, "ADMIN");

    public enum Effect { READ, WRITE, DELETE, ANALYSIS, EXPORT, ADMINISTRATION }

    private final String scope;
    private final String description;
    private final Effect effect;
    private final List<String> roles;

    ApiPermission(String scope, String description, Effect effect, String... roles) {
        this.scope = scope;
        this.description = description;
        this.effect = effect;
        this.roles = List.of(roles);
    }

    public String scope() { return scope; }
    public String description() { return description; }
    public Effect effect() { return effect; }
    public List<String> roles() { return roles; }

    public static Map<String, String> scopes() {
        var scopes = new LinkedHashMap<String, String>();
        Arrays.stream(values()).forEach(permission -> scopes.put(permission.scope, permission.description));
        return scopes;
    }
}
