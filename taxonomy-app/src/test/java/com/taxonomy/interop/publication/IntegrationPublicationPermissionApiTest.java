package com.taxonomy.interop.publication;

import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.extension.api.integration.PublicationContracts.ItemState;
import com.taxonomy.extension.api.integration.PublicationContracts.PublicationMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Tag("ci-app-shard-domain-integration")
class IntegrationPublicationPermissionApiTest extends PublicationIntegrationFixture {
    @Autowired MockMvc mvc;
    @Autowired com.taxonomy.workspace.service.WorkspaceManager manager;

    @Test
    void remotePublicationRequiresItsOwnScopeAndNoTechnicalAdministratorRole() throws Exception {
        edit(new CreateArchitectureElement("scoped-publish", "System", Map.of("title", "Scoped publication")));
        var draft = preview(PublicationMode.PUSH);
        var review = review(draft);
        String path = "/api/integrations/" + connection + "/publish";

        mvc.perform(scoped(post(path), "integration:import").contentType("application/json").content(json.write(review)))
                .andExpect(status().isForbidden());
        assertEquals(0, provider.writes.get());
        assertEquals(draft, publication.publication(context, connection, draft.operationId()));

        mvc.perform(scoped(post(path), "architecture:publish").contentType("application/json").content(json.write(review)))
                .andExpect(status().isOk()).andExpect(jsonPath("phase").value("COMPLETED"));
        assertEquals(1, provider.writes.get());
    }

    @Test
    void retainedPublicationRetryCannotResumeRemoteWritesWithImportScope() throws Exception {
        edit(new CreateArchitectureElement("scoped-retry", "System", Map.of("title", "Scoped retry")));
        provider.noEffectAt = 1;
        var pending = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.RETRYABLE_NO_EFFECT, pending.items().getFirst().state());
        assertEquals(1, provider.writes.get());
        assertEquals(0, provider.mutationCount(pending.items().getFirst().resourceId()));
        String path = "/api/integrations/" + connection + "/operations/" + pending.operationId() + "/retry";
        var active = workspaces.createWorkingCopy(context.username(), context.repositoryId(), "draft", "Another selected copy", "");
        manager.switchWorkspace(context.username(), active.getWorkspaceId());
        assertEquals(active.getWorkspaceId(), manager.findActiveWorkspace(context.username()).getWorkspaceId());

        mvc.perform(scoped(post(path), "integration:import")).andExpect(status().isForbidden());
        assertEquals(pending, publication.publication(context, connection, pending.operationId()));
        assertEquals(1, provider.writes.get());
        assertEquals(0, provider.mutationCount(pending.items().getFirst().resourceId()));

        mvc.perform(scoped(post(path), "architecture:publish"))
                .andExpect(status().isOk()).andExpect(jsonPath("phase").value("COMPLETED"));
        assertEquals(2, provider.writes.get());
        assertEquals(1, provider.mutationCount(pending.items().getFirst().resourceId()));
        assertEquals(active.getWorkspaceId(), manager.findActiveWorkspace(context.username()).getWorkspaceId(),
                "An exact retry pin must not replace the active tab selection");
    }

    @Test
    void foreignMissingAndUnreadyWorkspacePinsCannotReachPublicationRetry() throws Exception {
        var draft = preview(PublicationMode.PUSH);
        String path = "/api/integrations/" + connection + "/operations/" + draft.operationId() + "/retry";
        String foreignActor = "foreign-" + java.util.UUID.randomUUID();
        var foreignRepo = repositories.createRepository("Foreign", foreignActor, "",
                com.taxonomy.workspace.model.RepositoryVisibility.PRIVATE, foreignActor, "draft");
        var foreign = workspaces.createWorkingCopy(foreignActor, foreignRepo.getRepositoryId(), "draft", "Foreign copy", "");
        for (String pin : java.util.List.of(foreign.getWorkspaceId(), "missing-workspace")) {
            mvc.perform(scoped(post(path), "architecture:publish").header("X-Taxonomy-Workspace-Id", pin))
                    .andExpect(status().isForbidden());
            assertEquals(0, provider.writes.get());
        }
        var pending = manager.createWorkspace(context.username(), "Pending copy", "Explicit unready test selection");
        mvc.perform(scoped(post(path), "architecture:publish").header("X-Taxonomy-Workspace-Id", pending.getWorkspaceId()))
                .andExpect(status().isConflict());
        assertEquals(0, provider.writes.get());
    }

    @Test
    void missingConnectionOrOperationCannotReachRemoteRetry() throws Exception {
        for (String path : java.util.List.of(
                "/api/integrations/" + java.util.UUID.randomUUID() + "/operations/" + java.util.UUID.randomUUID() + "/retry",
                "/api/integrations/" + connection + "/operations/" + java.util.UUID.randomUUID() + "/retry")) {
            mvc.perform(scoped(post(path), "architecture:publish")).andExpect(status().isNotFound());
            assertEquals(0, provider.writes.get());
        }
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder request, String scope) {
        return request.param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId())
                .param("branch", context.branch()).with(csrf()).with(jwt().jwt(jwt -> jwt.subject(context.username()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ARCHITECT"), new SimpleGrantedAuthority("SCOPE_" + scope)));
    }
}
