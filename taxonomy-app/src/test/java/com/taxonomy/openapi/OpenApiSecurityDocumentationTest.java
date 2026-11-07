package com.taxonomy.openapi;

import com.taxonomy.security.keycloak.SwaggerKeycloakConfig;
import com.taxonomy.shared.config.OpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/projects/{projectId}/analysis-jobs/{jobId}/items/{itemId}/continue",
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations",
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/synthesis-runs",
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/answers"
    })
    void personalAnalysisExecutionDocumentsAnalysisCapabilityWithoutBusinessEditing(String path) {
        var operation = document(true, new Paths().addPathItem(path, new PathItem().post(new Operation())))
                .getPaths().get(path).getPost();
        assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("analysis:run"));
        assertThat(operation.getExtensions().get("x-required-roles")).isEqualTo(java.util.List.of("USER", "ARCHITECT"));
        assertThat(operation.getDescription()).contains("ANALYSIS", "analysis:run");
        assertThat(operation.getDescription()).doesNotContain("architecture:write");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/projects/{projectId}/requirements/{requirementId}/copilot",
            "/api/projects/{projectId}/copilot-operations/{operationId}/resume",
            "/api/projects/{projectId}/autopilot/run"
    })
    void automaticBusinessEnrichmentDocumentsEditingAndExecution(String path) {
        var operation = document(true, new Paths().addPathItem(path, new PathItem().post(new Operation())))
                .getPaths().get(path).getPost();
        assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak",
                java.util.List.of("architecture:write", "analysis:run"));
        assertThat(operation.getExtensions().get("x-required-role-groups"))
                .isEqualTo(java.util.List.of(java.util.List.of("ARCHITECT"), java.util.List.of("USER", "ARCHITECT")));
        assertThat(operation.getDescription()).contains("architecture:write", "analysis:run", " AND ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/architecture/editor.svg", "/api/architecture/editor.pdf", "/api/dsl/export",
            "/api/projects/git/export",
            "/api/projects/{projectId}/reports/{format}",
            "/api/projects/{projectId}/snapshots/{snapshotId}/architecture-report/docx",
            "/api/projects/{projectId}/snapshots/{snapshotId}/decision-report/{formatId}",
            "/api/projects/{projectId}/architecture-workbench/{snapshotId}.svg",
            "/api/projects/{projectId}/architecture-workbench/{snapshotId}.pdf",
            "/api/projects/{projectId}/architecture-workbench/{snapshotId}.archimate.xml",
            "/api/projects/{projectId}/architecture-workbench/{snapshotId}.archimate.zip",
            "/api/projects/{projectId}/architecture-workbench/{snapshotId}.vsdx",
            "/api/projects/{projectId}/architecture-workbench/{snapshotId}.visio.zip",
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/revisions/{revision}/export",
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/adoptions/{commandId}/export"
    })
    void downloadedArtifactsVisiblyDocumentExportRatherThanGenericReading(String path) {
        var operation = document(true, new Paths().addPathItem(path, new PathItem().get(new Operation())))
                .getPaths().get(path).getGet();
        assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("integration:export"));
        assertThat(operation.getDescription()).contains("integration:export", "EXPORT", "READER");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/projects/{projectId}/analysis-jobs",
            "/api/projects/{projectId}/analysis-jobs/{jobId}/items/{itemId}/continuation",
            "/api/projects/{projectId}/requirements/{requirementId}/snapshots",
            "/api/projects/{projectId}/snapshots/{snapshotId}",
            "/api/projects/{projectId}/architecture-workbench/{snapshotId}",
            "/api/projects/{projectId}/snapshots/{snapshotId}/decision-report/options",
            "/api/projects/{projectId}/requirements/{requirementId}/copilot/latest",
            "/api/projects/{projectId}/copilot-operations/{operationId}",
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}",
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/synthesis-runs/{runId}/progress"
    })
    void retainedAnalysisVisiblyDocumentsAnalysisReading(String path) {
        var operation = document(true, new Paths().addPathItem(path, new PathItem().get(new Operation())))
                .getPaths().get(path).getGet();
        assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("analysis:read"));
        assertThat(operation.getDescription()).contains("analysis:read", "READ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/projects/{projectId}/requirements/import", "/api/projects/{projectId}/requirements/import-review"
    })
    void importsVisiblyDocumentConditionalExecutionWithoutRequiringItForPureImports(String path) {
        var operation = document(true, new Paths().addPathItem(path, new PathItem().post(new Operation())))
                .getPaths().get(path).getPost();
        assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("architecture:write"));
        assertThat(operation.getExtensions().get("x-conditional-required-scopes"))
                .isEqualTo(java.util.Map.of("analyzeAfterImport=true", java.util.List.of("analysis:run")));
        assertThat(operation.getDescription()).contains("analyzeAfterImport=true", "analysis:run", "Conditional");
    }

    @Test
    void publicationAndRetryDocumentBusinessPublicationAndPersistedDirectionAlternatives() {
        String publishPath = "/api/integrations/{connection}/publish";
        String retryPath = "/api/integrations/{connection}/operations/{operation}/retry";
        var api = document(true, new Paths().addPathItem(publishPath, new PathItem().post(new Operation()))
                .addPathItem(retryPath, new PathItem().post(new Operation())));
        var publish = api.getPaths().get(publishPath).getPost();
        assertThat(publish.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("architecture:publish"));
        assertThat(publish.getExtensions().get("x-required-roles")).isEqualTo(java.util.List.of("ARCHITECT"));
        var retry = api.getPaths().get(retryPath).getPost();
        assertThat(retry.getSecurity()).hasSize(4);
        assertThat(retry.getSecurity()).filteredOn(requirement -> requirement.containsKey("keycloak"))
                .extracting(requirement -> requirement.get("keycloak")).containsExactlyInAnyOrder(
                        java.util.List.of("integration:import"), java.util.List.of("integration:export"),
                        java.util.List.of("architecture:publish"));
        assertThat(retry.getDescription()).contains("INBOUND", "OUTBOUND", "PUSH", "SYNCHRONIZE", "persisted");
        assertThat(retry.getDescription()).contains(" OR ", "integration:import", "integration:export", "architecture:publish");
    }

    @Test
    void optionalModelInvestigationsAreDocumentedAsCompleteExecutionOperations() {
        for (String path : java.util.List.of("/api/architecture/focus/suggestions", "/api/architecture/editor/focus/suggestions",
                "/api/documents/extract-ai", "/api/documents/map-regulation")) {
            var operation = document(true, new Paths().addPathItem(path, new PathItem().post(new Operation())))
                    .getPaths().get(path).getPost();
            assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("analysis:run"));
            assertThat(operation.getDescription()).contains("ANALYSIS", "USER", "ARCHITECT");
        }
    }

    @Test
    void reanalysisAndExistingViewDownloadsDocumentTheirDifferentEffects() {
        for (String path : java.util.List.of("/api/diagram/visio", "/api/diagram/archimate", "/api/diagram/mermaid",
                "/api/diagram/structurizr", "/api/diagram/export/{formatId}")) {
            var operation = document(true, new Paths().addPathItem(path, new PathItem().post(new Operation())))
                    .getPaths().get(path).getPost();
            assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("analysis:run", "integration:export"));
            assertThat(operation.getDescription()).contains("ANALYSIS", "EXPORT");
        }
        for (String path : java.util.List.of("/api/diagram/current/pdf", "/api/diagram/current/{formatId}", "/api/scores/export")) {
            var operation = document(true, new Paths().addPathItem(path, new PathItem().post(new Operation())))
                    .getPaths().get(path).getPost();
            assertThat(operation.getSecurity().getFirst()).containsEntry("keycloak", java.util.List.of("integration:export"));
        }
    }

    private OpenAPI document(boolean keycloak) {
        return document(keycloak, new Paths()
                .addPathItem("/api/projects/{projectId}", new PathItem().get(new Operation())
                        .put(new Operation().description("existing explanation" )).options(new Operation()))
                .addPathItem("/api/export/mermaid", new PathItem().post(new Operation()))
                .addPathItem("/api/admin/users", new PathItem().get(new Operation()))
                .addPathItem("/api/analyze-stream", new PathItem().get(new Operation()))
                .addPathItem("/api/analyze-node", new PathItem().get(new Operation()))
                .addPathItem("/api/workspace/external/full-sync", new PathItem().post(new Operation()))
                .addPathItem("/api/workspace/{id}", new PathItem().delete(new Operation()))
                .addPathItem("/api/unclassified-write", new PathItem().post(new Operation())));
    }

    private OpenAPI document(boolean keycloak, Paths paths) {
        try (var context = new AnnotationConfigApplicationContext()) {
            if (keycloak) context.getEnvironment().setActiveProfiles("keycloak");
            context.register(OpenApiConfig.class, SwaggerKeycloakConfig.class);
            context.refresh();
            var api = context.getBean(OpenAPI.class);
            api.setPaths(paths);
            context.getBeanProvider(OpenApiCustomizer.class).orderedStream().forEach(customizer -> customizer.customise(api));
            return api;
        }
    }
}
