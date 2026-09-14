package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Distinct service instances and transactions share real JPA metadata, not a JVM monitor. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:hsqldb:mem:provisioning-atomicity;hsqldb.tx=mvcc",
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key="
})
class WorkspaceProvisioningAtomicityIT {
    @Autowired private ConfigurableApplicationContext application;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private UserWorkspaceRepository rows;
    @Autowired private WorkspaceManager actualManager;
    @Autowired private MockMvc mvc;

    @Test
    void concurrentManagersInitializeTheSelectedWorkspaceOnlyOnce() throws Exception {
        var saved = workspace("atomic-owner", false);
        var reads = new CyclicBarrier(2);
        var coordinatedRows = mock(UserWorkspaceRepository.class, delegatesTo(rows));
        doAnswer(invocation -> {
            Optional<UserWorkspace> selected = rows.findByWorkspaceId(saved.getWorkspaceId());
            // Force both managers to load the old state before either can claim it.
            reads.await(10, TimeUnit.SECONDS);
            return selected;
        }).when(coordinatedRows).findByWorkspaceId(saved.getWorkspaceId());
        var selections = new AtomicInteger();
        var catalogue = catalogue(selections);
        try (var factory = new DslGitRepositoryFactory(null)) {
            String source = factory.getSystemRepository().commitDsl("draft", "# source", "system", "Seed");
            var first = managed(coordinatedRows, catalogue, factory);
            var second = managed(coordinatedRows, catalogue, factory);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(() -> first.provisionWorkspaceRepository("atomic-owner", saved.getWorkspaceId()));
                var b = executor.submit(() -> second.provisionWorkspaceRepository("atomic-owner", saved.getWorkspaceId()));
                var left = a.get(30, TimeUnit.SECONDS);
                var right = b.get(30, TimeUnit.SECONDS);
                assertEquals(1, selections.get(), "Only the durable claim winner may initialize Git");
                assertEquals(WorkspaceProvisioningStatus.READY, left.getProvisioningStatus());
                assertEquals(WorkspaceProvisioningStatus.READY, right.getProvisioningStatus());
                assertEquals(left.getCurrentCommit(), right.getCurrentCommit());
                assertEquals(source, rows.findByWorkspaceId(saved.getWorkspaceId()).orElseThrow().getBaseCommit());
            }
        } finally {
            rows.deleteById(saved.getId());
        }
    }

    @Test
    void missingSourceFailureIsDurableAndAnExplicitRetryRecovers() throws Exception {
        var saved = workspace("failure-owner", false);
        try (var factory = new DslGitRepositoryFactory(null)) {
            var manager = managed(rows, catalogue(new AtomicInteger()), factory);
            assertThrows(RuntimeException.class,
                    () -> manager.provisionWorkspaceRepository("failure-owner", saved.getWorkspaceId()));
            var failed = rows.findByWorkspaceId(saved.getWorkspaceId()).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.FAILED, failed.getProvisioningStatus());
            assertNull(failed.getCurrentCommit());
            assertNull(failed.getProvisionedAt());
            assertNotNull(failed.getProvisioningError());
            factory.getSystemRepository().commitDsl("draft", "# restored", "system", "Recovery");
            manager.provisionWorkspaceRepository("failure-owner", saved.getWorkspaceId());
            var ready = rows.findByWorkspaceId(saved.getWorkspaceId()).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertNotNull(ready.getCurrentCommit());
            assertNull(ready.getProvisioningError());
        } finally {
            rows.deleteById(saved.getId());
        }
    }

    @Test
    @WithMockUser(username = "default-pin-owner", roles = "ADMIN")
    void firstUseDefaultPinIsInitializedBeforeBrowserRepositoryReads() throws Exception {
        var saved = workspace("default-pin-owner", true);
        try {
            actualManager.switchWorkspace("default-pin-owner", saved.getWorkspaceId());
            mvc.perform(get("/api/dsl/branches").header(WorkspaceContextResolver.WORKSPACE_HEADER, saved.getWorkspaceId()))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/proposals/pending").header(WorkspaceContextResolver.WORKSPACE_HEADER, saved.getWorkspaceId()))
                    .andExpect(status().isOk());
            var ready = rows.findByWorkspaceId(saved.getWorkspaceId()).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals("draft", ready.getCurrentBranch());
            assertNotNull(ready.getCurrentCommit());
        } finally {
            actualManager.evictWorkspace("default-pin-owner");
            rows.deleteById(saved.getId());
        }
    }

    private WorkspaceManager managed(UserWorkspaceRepository repository, SystemRepositoryService catalogue,
                                     DslGitRepositoryFactory factory) {
        var target = new WorkspaceManager(repository, 10, catalogue, factory);
        application.getAutowireCapableBeanFactory().autowireBean(target);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (WorkspaceManager) proxy.getProxy();
    }

    private UserWorkspace workspace(String owner, boolean defaultWorkspace) {
        var workspace = new UserWorkspace();
        String id = "atomic-" + UUID.randomUUID();
        workspace.setWorkspaceId(id);
        workspace.setDisplayName(id);
        workspace.setUsername(owner);
        workspace.setDefault(defaultWorkspace);
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        workspace.setCreatedAt(Instant.now());
        workspace.setLastAccessedAt(Instant.now());
        return rows.saveAndFlush(workspace);
    }

    private static SystemRepositoryService catalogue(AtomicInteger selections) {
        var service = mock(SystemRepositoryService.class);
        var metadata = new SystemRepository();
        metadata.setRepositoryId("atomic-source");
        metadata.setDefaultBranch("draft");
        when(service.getPrimaryRepository()).thenAnswer(invocation -> {
            selections.incrementAndGet();
            return metadata;
        });
        return service;
    }
}
