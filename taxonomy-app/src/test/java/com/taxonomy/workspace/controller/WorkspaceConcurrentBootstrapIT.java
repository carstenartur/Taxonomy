package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real MVC/JPA/JGit requests overlap while the winning initializer is deliberately paused. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key=",
        "taxonomy.features.multi-repository-api.enabled=false"
})
class WorkspaceConcurrentBootstrapIT {
    enum RequestKind { CURRENT_WORKSPACE, UNPINNED_BRANCHES, EXPLICIT_PENDING_BRANCHES }

    @Autowired private MockMvc mvc;
    @Autowired private UserWorkspaceRepository rows;
    @Autowired private WorkspaceManager manager;
    @MockitoSpyBean private DslGitRepositoryFactory factory;

    @ParameterizedTest
    @EnumSource(RequestKind.class)
    void overlappingImplicitReadsWaitForBootstrapButExplicitPinsStillFailClosed(RequestKind kind)
            throws Exception {
        String actor = "bootstrap-" + UUID.randomUUID();
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId(UUID.randomUUID().toString());
        workspace.setUsername(actor);
        workspace.setDisplayName("Concurrent bootstrap");
        workspace.setDefault(true);
        workspace.setCurrentBranch("draft");
        workspace.setBaseBranch("draft");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        workspace.setCreatedAt(Instant.now());
        workspace.setLastAccessedAt(Instant.now());
        rows.saveAndFlush(workspace);

        var provisioningStarted = new CountDownLatch(1);
        var releaseProvisioning = new CountDownLatch(1);
        doAnswer(invocation -> {
            provisioningStarted.countDown();
            assertTrue(releaseProvisioning.await(15, TimeUnit.SECONDS), "Test must release the initializer");
            return invocation.callRealMethod();
        }).when(factory).openWorkspaceRepository(eq(workspace.getWorkspaceId()));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mvc.perform(get("/api/workspace/current")
                    .with(user(actor).roles("ADMIN"))).andReturn());
            try {
                assertTrue(provisioningStarted.await(15, TimeUnit.SECONDS), "First request did not start provisioning");
                assertEquals(WorkspaceProvisioningStatus.PROVISIONING,
                        rows.findByWorkspaceId(workspace.getWorkspaceId()).orElseThrow().getProvisioningStatus());
                var arrived = new CountDownLatch(1);
                var following = executor.submit(() -> {
                    var request = get(kind == RequestKind.CURRENT_WORKSPACE
                            ? "/api/workspace/current" : "/api/dsl/branches")
                            .with(user(actor).roles("ADMIN"));
                    if (kind == RequestKind.EXPLICIT_PENDING_BRANCHES) {
                        request.header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace.getWorkspaceId());
                    }
                    arrived.countDown();
                    return mvc.perform(request).andReturn();
                });
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
                MvcResult early = null;
                try {
                    // Give the real overlapping HTTP request a chance to expose
                    // the old premature 409/PROVISIONING response while Git is paused.
                    early = following.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillWaiting) {
                    // A coordinated implicit request is expected to wait here.
                } finally {
                    releaseProvisioning.countDown();
                }
                MvcResult winner = first.get(15, TimeUnit.SECONDS);
                MvcResult second = following.get(15, TimeUnit.SECONDS);
                assertEquals(200, winner.getResponse().getStatus(), winner.getResponse().getContentAsString());
                assertTrue(winner.getResponse().getContentAsString().contains("\"provisioningStatus\":\"READY\""));
                if (kind == RequestKind.EXPLICIT_PENDING_BRANCHES) {
                    assertNotNull(early, "An explicit pending pin must not join implicit initialization");
                    assertEquals(409, second.getResponse().getStatus());
                } else {
                    assertEquals(200, second.getResponse().getStatus(), second.getResponse().getContentAsString());
                    if (kind == RequestKind.CURRENT_WORKSPACE) {
                        assertTrue(second.getResponse().getContentAsString().contains("\"provisioningStatus\":\"READY\""),
                                second.getResponse().getContentAsString());
                    }
                }
                UserWorkspace ready = rows.findByWorkspaceId(workspace.getWorkspaceId()).orElseThrow();
                assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
                assertEquals("draft", ready.getCurrentBranch());
                assertNotNull(ready.getCurrentCommit());
                assertEquals(1, factory.openWorkspaceRepository(ready.getWorkspaceId()).getDslHistory("draft").size());
            } finally {
                releaseProvisioning.countDown();
            }
        } finally {
            manager.evictWorkspace(actor);
            factory.deleteWorkspaceRepository(workspace.getWorkspaceId());
            rows.deleteById(workspace.getId());
        }
    }
}
