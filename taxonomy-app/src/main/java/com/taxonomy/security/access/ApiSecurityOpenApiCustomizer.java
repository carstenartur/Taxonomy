package com.taxonomy.security.access;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.List;

/** Renders the enforced REST policy as visible Swagger descriptions and OpenAPI requirements. */
public final class ApiSecurityOpenApiCustomizer implements OpenApiCustomizer, Ordered {
    private static final String ACCESS_HEADER = "\n\n### Access requirements\n\n";
    private final boolean keycloak;
    private final String cookieName;

    public ApiSecurityOpenApiCustomizer(Environment environment) {
        keycloak = environment.matchesProfiles("keycloak");
        cookieName = environment.getProperty("server.servlet.session.cookie.name", "JSESSIONID");
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override
    public void customise(OpenAPI api) {
        if (api.getComponents() == null) api.setComponents(new Components());
        if (!keycloak) {
            api.getComponents()
                    .addSecuritySchemes("basicAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")
                            .description("Local REST credentials over HTTPS. Select Authorize to use HTTP Basic."));
        }
        api.getComponents().addSecuritySchemes("sessionCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE).name(cookieName)
                .description("Existing browser session after local or OIDC login. Uses roles; state-changing session requests additionally need the page's CSRF token."));
        if (api.getInfo() != null && !api.getInfo().getDescription().contains("### Application roles")) {
            api.getInfo().setDescription(api.getInfo().getDescription() + """


                    ### Application roles

                    | Role | Capability |
                    | --- | --- |
                    | Reader (`ROLE_READER`) | Read permitted data and export existing results. |
                    | Analyst (`ROLE_USER`) | Additionally execute analyses and manage personal drafts. |
                    | Architect (`ROLE_ARCHITECT`) | Additionally change, delete and publish architecture data. |
                    | Administrator (`ROLE_ADMIN`) | Manage technical configuration, accounts and access. Business editing requires an explicit ARCHITECT role. |

                    Any listed role can satisfy a single role group. When multiple role groups are listed, every group
                    is required. Bearer JWT calls additionally require
                    every listed OAuth scope. A scope never grants a role or access to another repository/workspace.
                    Organizational groups are assigned roles at the identity provider. Repository group grants and stored
                    memberships remain additional resource restrictions. Local Basic and browser sessions use roles;
                    browser writes retain CSRF protection. Unclassified write operations are denied by default.
                    """);
        }
        if (api.getPaths() == null) return;
        api.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/") && !path.startsWith("/oslc/")) return;
            item.readOperationsMap().forEach((method, operation) ->
                    describe(operation, ApiAccessPolicy.resolve(method.name(), path)));
        });
    }

    private void describe(Operation operation, ApiAccessPolicy.Rule rule) {
        boolean permissionRequired = rule.access() == ApiAccessPolicy.Access.PERMISSION;
        List<String> scopes = rule.permissions().stream().map(ApiPermission::scope).distinct().toList();
        List<List<String>> roleGroups = rule.permissions().stream().map(ApiPermission::roles).toList();
        List<String> roles = roleGroups.stream().flatMap(List::stream).distinct().toList();
        if (rule.access() == ApiAccessPolicy.Access.PUBLIC) {
            operation.setSecurity(List.of());
        } else if (keycloak) {
            operation.setSecurity(List.of(new SecurityRequirement().addList("keycloak", scopes),
                    new SecurityRequirement().addList("sessionCookie")));
        } else {
            // Separate requirement objects are alternatives (Basic OR browser session).
            operation.setSecurity(List.of(new SecurityRequirement().addList("basicAuth"),
                    new SecurityRequirement().addList("sessionCookie")));
        }
        operation.addExtension("x-required-roles", roles);
        operation.addExtension("x-required-role-groups", roleGroups);
        operation.addExtension("x-required-scopes", scopes);
        operation.addExtension("x-access-policy", rule.access().name());
        if (operation.getResponses() == null) operation.setResponses(new ApiResponses());
        if (rule.access() != ApiAccessPolicy.Access.PUBLIC) {
            operation.getResponses().putIfAbsent("401", new ApiResponse().description("Authentication is missing or invalid."));
            operation.getResponses().putIfAbsent("403", new ApiResponse().description(
                    "Required role, bearer scope or resource permission is missing; browser writes also require CSRF protection."));
        }
        String current = operation.getDescription() == null ? "" : operation.getDescription();
        if (current.contains(ACCESS_HEADER)) return;
        String effect = permissionRequired ? rule.permission().effect().name() : rule.access().name();
        String roleText = permissionRequired ? roleGroups.stream().map(group -> String.join(" OR ", group))
                .collect(java.util.stream.Collectors.joining(" AND ")) : switch (rule.access()) {
            case PUBLIC -> "Public";
            case AUTHENTICATED -> "Authenticated account";
            case DENIED -> "None — Denied by default";
            default -> throw new IllegalStateException("Unresolved permission");
        };
        operation.setDescription(current + ACCESS_HEADER
                + "| Requirement | Value |\n| --- | --- |\n"
                + "| Effect | " + effect + " |\n"
                + "| " + (roleGroups.size() > 1 ? "Required application role groups" : "Any application role") + " | " + roleText + " |\n"
                + "| Permission / bearer OAuth scope | " + (scopes.isEmpty() ? "None" : scopes.stream()
                        .map(scope -> "`" + scope + "`").collect(java.util.stream.Collectors.joining(" AND "))) + " |\n"
                + "| Resource restriction | " + rule.resourceRequirement() + " |\n"
                + (keycloak ? "\nBearer access requires the role groups and every listed scope. An existing OIDC browser session uses roles; session writes additionally require the CSRF token."
                : "\nLocal Basic and browser sessions use roles; session writes also require the CSRF token."));
        if (operation.getTags() == null || operation.getTags().isEmpty()) {
            operation.addTagsItem(permissionRequired ? switch (rule.permission().effect()) {
                case ANALYSIS -> "Analysis";
                case EXPORT -> "Export";
                case ADMINISTRATION -> "Administration";
                default -> "Architecture";
            } : "Account and access");
        }
    }
}
