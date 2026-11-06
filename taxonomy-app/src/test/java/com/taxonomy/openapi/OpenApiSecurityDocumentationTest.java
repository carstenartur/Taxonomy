package com.taxonomy.openapi;

import com.taxonomy.security.keycloak.SwaggerKeycloakConfig;
import com.taxonomy.shared.config.OpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSecurityDocumentationTest {
    @Test
    void localProfileDocumentsOnlyActiveSchemesAndVisibleRoleRequirements() {
        var api = document(false);
        assertThat(api.getComponents()).isNotNull();
        assertThat(api.getComponents().getSecuritySchemes()).containsOnlyKeys("basicAuth", "sessionCookie");
        var read = api.getPaths().get("/api/projects/{projectId}").getGet();
        assertThat(read.getDescription()).contains("### Access requirements", "READER", "architecture:read", "READ");
        assertThat(read.getSecurity()).hasSize(2);
        assertThat(read.getSecurity().getFirst()).containsEntry("basicAuth", java.util.List.of());
        assertThat(read.getResponses()).containsKeys("401", "403");
        assertThat(api.getInfo().getDescription()).contains("Analyst", "ROLE_USER", "Administrator");
    }

    @Test
    void oauthProfileDocumentsPerOperationScopesAndBothClientFlows() {
        var api = document(true);
        assertThat(api.getComponents().getSecuritySchemes()).containsOnlyKeys("keycloak", "sessionCookie");
        var flows = api.getComponents().getSecuritySchemes().get("keycloak").getFlows();
        assertThat(flows.getAuthorizationCode().getScopes()).containsKeys("architecture:read", "architecture:write", "analysis:run");
        assertThat(flows.getClientCredentials().getTokenUrl()).endsWith("/protocol/openid-connect/token");
        var write = api.getPaths().get("/api/projects/{projectId}").getPut();
        assertThat(write.getSecurity()).hasSize(2);
        assertThat(write.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("architecture:write"));
        assertThat(write.getSecurity().get(1)).containsEntry("sessionCookie", java.util.List.of());
        assertThat(write.getDescription()).contains("session", "CSRF");
        assertThat(write.getDescription()).contains("ARCHITECT", "repository", "existing explanation");
        assertThat(write.getExtensions().get("x-required-roles")).isEqualTo(java.util.List.of("ARCHITECT"));
    }

    @Test
    void exportPostIsDocumentedAsExportWithReaderAccessAndNotArchitectureWrite() {
        var export = document(true).getPaths().get("/api/export/mermaid").getPost();
        assertThat(export.getSecurity()).isNotNull();
        assertThat(export.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("integration:export"));
        assertThat(export.getDescription()).contains("READER", "EXPORT");
    }

    @Test
    void administrativeAccessAndDenyByDefaultAreVisibleAndPreflightIsPublic() {
        var api = document(true);
        var admin = api.getPaths().get("/api/admin/users").getGet();
        assertThat(admin.getSecurity()).isNotNull();
        assertThat(admin.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("admin:users"));
        assertThat(admin.getExtensions().get("x-required-roles")).isEqualTo(java.util.List.of("ADMIN"));
        var denied = api.getPaths().get("/api/unclassified-write").getPost();
        assertThat(denied.getDescription()).contains("Denied by default");
        assertThat(denied.getResponses()).containsKey("403");
        assertThat(api.getPaths().get("/api/projects/{projectId}").getOptions().getSecurity()).isEmpty();
    }

    @Test
    void analysisGetRoutesAreDocumentedAsExecutionRatherThanReading() {
        for (String path : java.util.List.of("/api/analyze-stream", "/api/analyze-node")) {
            var operation = document(true).getPaths().get(path).getGet();
            assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("analysis:run"));
            assertThat(operation.getExtensions().get("x-required-roles")).isEqualTo(java.util.List.of("USER", "ARCHITECT"));
        }
    }

    @Test
    void externalSyncDocumentsConjunctiveRolesAndScopes() {
        var sync = document(true).getPaths().get("/api/workspace/external/full-sync").getPost();
        assertThat(sync.getSecurity().getFirst()).containsEntry("keycloak",
                java.util.List.of("architecture:publish", "admin:configuration"));
        assertThat(sync.getExtensions().get("x-required-role-groups"))
                .isEqualTo(java.util.List.of(java.util.List.of("ARCHITECT"), java.util.List.of("ADMIN")));
        assertThat(sync.getDescription()).contains("ARCHITECT AND ADMIN", "architecture:publish", "admin:configuration");
    }

    @Test
    void workspaceDeletionDocumentsArchitectureDeletionInsteadOfTechnicalAdministration() {
        var deletion = document(true).getPaths().get("/api/workspace/{id}").getDelete();
        assertThat(deletion.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("architecture:delete"));
        assertThat(deletion.getExtensions().get("x-required-roles")).isEqualTo(java.util.List.of("ARCHITECT"));
    }

    private OpenAPI document(boolean keycloak) {
        try (var context = new AnnotationConfigApplicationContext()) {
            if (keycloak) context.getEnvironment().setActiveProfiles("keycloak");
            context.register(OpenApiConfig.class, SwaggerKeycloakConfig.class);
            context.refresh();
            var api = context.getBean(OpenAPI.class);
            api.setPaths(new Paths()
                    .addPathItem("/api/projects/{projectId}", new PathItem().get(new Operation())
                            .put(new Operation().description("existing explanation" )).options(new Operation()))
                    .addPathItem("/api/export/mermaid", new PathItem().post(new Operation()))
                    .addPathItem("/api/admin/users", new PathItem().get(new Operation()))
                    .addPathItem("/api/analyze-stream", new PathItem().get(new Operation()))
                    .addPathItem("/api/analyze-node", new PathItem().get(new Operation()))
                    .addPathItem("/api/workspace/external/full-sync", new PathItem().post(new Operation()))
                    .addPathItem("/api/workspace/{id}", new PathItem().delete(new Operation()))
                    .addPathItem("/api/unclassified-write", new PathItem().post(new Operation())));
            context.getBeanProvider(OpenApiCustomizer.class).orderedStream().forEach(customizer -> customizer.customise(api));
            return api;
        }
    }
}
