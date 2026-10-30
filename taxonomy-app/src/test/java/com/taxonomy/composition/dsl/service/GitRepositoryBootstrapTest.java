package com.taxonomy.composition.dsl.service;

import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.AppInitializationStateService.State;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GitRepositoryBootstrapTest {
    private static final String DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "bootstrap";
            }
            element CP-1000 type Capability {
              title: "Global capability";
            }
            element IP-1000 type InformationProduct {
              title: "Global information product";
            }
            """;
    private static AtomicBoolean bootstrapGuard;
    private static boolean originalGuardValue;

    @BeforeAll
    static void captureJvmGuard() throws ReflectiveOperationException {
        Field field = GitRepositoryBootstrap.class.getDeclaredField("BOOTSTRAPPED");
        field.setAccessible(true);
        bootstrapGuard = (AtomicBoolean) field.get(null);
        originalGuardValue = bootstrapGuard.get();
    }

    @BeforeEach
    void resetJvmGuardForIsolatedTest() {
        bootstrapGuard.set(false);
    }

    @AfterAll
    static void restoreJvmGuard() {
        bootstrapGuard.set(originalGuardValue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "coordinator"})
    void earlyApplicationReadyDefersUntilInitializationReadyAndRepeatedEventsStayIdempotent(String role)
            throws IOException {
        try (DslGitRepository repository = new DslGitRepository()) {
            DslGitRepositoryFactory factory = factoryReturning(repository);
            TaxDslExportService exporter = exporterReturning(DSL);
            AppInitializationStateService state = new AppInitializationStateService();

            try (AnnotationConfigApplicationContext context = context(factory, exporter, state,
                    Map.of("taxonomy.analysis.runtime-role", role))) {
                assertThat(context.getBeansOfType(GitRepositoryBootstrap.class)).hasSize(1);

                publishApplicationReady(context);
                assertThat(repository.getHeadCommit("draft")).isNull();

                state.update(State.READY, "Taxonomy ready");
                assertThat(repository.getDslAtHead("draft")).isEqualTo(DSL);
                assertThat(repository.getCommitCount("draft")).isEqualTo(1);

                publishApplicationReady(context);
                state.update(State.READY, "Still ready");

                assertThat(repository.getDslAtHead("draft")).isEqualTo(DSL);
                assertThat(repository.getCommitCount("draft")).isEqualTo(1);
                assertThat(repository.getDslHistory("draft")).singleElement().satisfies(commit -> {
                    assertThat(commit.author()).isEqualTo("system");
                    assertThat(commit.message()).isEqualTo("Initial taxonomy materialization");
                });
                assertThat(repository.getBranchNames()).containsExactly("draft");

                verify(factory).getSystemRepository();
                verifyNoMoreInteractions(factory);
                verify(exporter).exportAll("default");
                verifyNoMoreInteractions(exporter);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "coordinator"})
    void readyAtApplicationStartBootstrapsOnApplicationReady(String role) throws IOException {
        try (DslGitRepository repository = new DslGitRepository()) {
            DslGitRepositoryFactory factory = factoryReturning(repository);
            TaxDslExportService exporter = exporterReturning(DSL);
            AppInitializationStateService state = readyState();

            try (AnnotationConfigApplicationContext context = context(factory, exporter, state,
                    Map.of("taxonomy.analysis.runtime-role", role))) {
                publishApplicationReady(context);
                assertThat(repository.getDslAtHead("draft")).isEqualTo(DSL);
                assertThat(repository.getCommitCount("draft")).isEqualTo(1);
            }
        }
    }

    @Test
    void workerSnapshotReadinessDoesNotMaterializeGlobalDraftOrConsumeCoordinatorBootstrap() throws IOException {
        try (DslGitRepository repository = new DslGitRepository()) {
            DslGitRepositoryFactory factory = factoryReturning(repository);
            TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
            TaxonomyRelationRepository relations = mock(TaxonomyRelationRepository.class);
            when(nodes.findAll()).thenReturn(List.of());
            when(relations.findAll()).thenReturn(List.of());
            TaxDslExportService workerExporter = spy(new TaxDslExportService(nodes, relations));
            AppInitializationStateService workerState = new AppInitializationStateService();
            workerState.update(State.READY, "Catalogue worker is ready for exact-source root snapshots");

            try (AnnotationConfigApplicationContext worker = context(factory, workerExporter, workerState,
                    Map.of("taxonomy.analysis.runtime-role", "worker", "taxonomy.analysis.worker.shards", "IP"))) {
                publishApplicationReady(worker);
                workerState.update(State.READY, "Catalogue worker is still ready for exact-source root snapshots");

                assertThat(workerState.isReady()).isTrue();
                assertThat(repository.getHeadCommit("draft")).isNull();
                assertThat(bootstrapGuard).isFalse();
                verifyNoInteractions(workerExporter, nodes, relations);
            }

            TaxDslExportService coordinatorExporter = exporterReturning(DSL);
            AppInitializationStateService coordinatorState = new AppInitializationStateService();
            try (AnnotationConfigApplicationContext coordinator = context(factory, coordinatorExporter, coordinatorState,
                    Map.of("taxonomy.analysis.runtime-role", "coordinator"))) {
                publishApplicationReady(coordinator);
                assertThat(repository.getHeadCommit("draft")).isNull();

                coordinatorState.update(State.READY, "Global taxonomy is loaded");
                assertThat(repository.getDslAtHead("draft")).isEqualTo(DSL);
                assertThat(repository.getCommitCount("draft")).isEqualTo(1);
                verify(coordinatorExporter).exportAll("default");
            }
        }
    }

    @Test
    void existingDraftHeadIsPreserved() throws IOException {
        try (DslGitRepository repository = new DslGitRepository()) {
            String existing = repository.commitDsl("draft", "existing\n", "owner", "Existing head");
            DslGitRepositoryFactory factory = factoryReturning(repository);
            TaxDslExportService exporter = mock(TaxDslExportService.class);

            try (AnnotationConfigApplicationContext context =
                         context(factory, exporter, readyState(), Map.of())) {
                publishApplicationReady(context);

                assertThat(repository.getHeadCommit("draft")).isEqualTo(existing);
                assertThat(repository.getDslAtHead("draft")).isEqualTo("existing\n");
                assertThat(repository.getCommitCount("draft")).isEqualTo(1);
            }
            verifyNoInteractions(exporter);
        }
    }

    @Test
    void explicitDisablementDoesNotCreateBootstrapBean() {
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        TaxDslExportService exporter = mock(TaxDslExportService.class);

        try (AnnotationConfigApplicationContext context = context(
                factory,
                exporter,
                new AppInitializationStateService(),
                Map.of("taxonomy.git.bootstrap", "false"))) {
            assertThat(context.getBeansOfType(GitRepositoryBootstrap.class)).isEmpty();
            verifyNoInteractions(factory, exporter);
        }
    }

    @Test
    void bootstrapIsEnabledWhenPropertyIsMissing() {
        try (DslGitRepository repository = new DslGitRepository()) {
            DslGitRepositoryFactory factory = factoryReturning(repository);

            try (AnnotationConfigApplicationContext context = context(
                    factory,
                    mock(TaxDslExportService.class),
                    new AppInitializationStateService(),
                    Map.of())) {
                assertThat(context.getBeansOfType(GitRepositoryBootstrap.class)).hasSize(1);
            }
            verify(factory).getSystemRepository();
            verify(factory, never()).getWorkspaceRepository(anyString());
            verify(factory, never()).getCentralRepository(anyString());
        }
    }

    @Test
    void ioFailureReleasesGuardForSuccessfulRetry() throws IOException {
        try (DslGitRepository actual = new DslGitRepository()) {
            DslGitRepository repository = spy(actual);
            doThrow(new IOException("temporary read failure"))
                    .doCallRealMethod()
                    .when(repository).getHeadCommit("draft");

            assertSuccessfulRetry(repository, exporterReturning(DSL));
        }
    }

    @Test
    void runtimeFailureReleasesGuardForSuccessfulRetry() throws IOException {
        try (DslGitRepository repository = new DslGitRepository()) {
            TaxDslExportService exporter = mock(TaxDslExportService.class);
            when(exporter.exportAll("default"))
                    .thenThrow(new IllegalStateException("temporary export failure"))
                    .thenReturn(DSL);

            assertSuccessfulRetry(repository, exporter);
        }
    }

    private static void assertSuccessfulRetry(
            DslGitRepository repository,
            TaxDslExportService exporter) throws IOException {
        try (AnnotationConfigApplicationContext context =
                     context(factoryReturning(repository), exporter, readyState(), Map.of())) {
            publishApplicationReady(context);
            assertThat(repository.getHeadCommit("draft")).isNull();

            publishApplicationReady(context);
            assertThat(repository.getDslAtHead("draft")).isEqualTo(DSL);
            assertThat(repository.getCommitCount("draft")).isEqualTo(1);
        }
    }

    private static AppInitializationStateService readyState() {
        AppInitializationStateService state = new AppInitializationStateService();
        state.update(State.READY, "Taxonomy ready before application event");
        return state;
    }

    @Test
    void firstWorkspaceReadDuringDelayedBootstrapCanRecoverWhenTheInitialCheckpointArrives() throws Exception {
        String owner = "bootstrap-window-owner", workspaceId = "7aacd595-9495-4091-aae3-6d5b108f4ac9";
        var workspace = new UserWorkspace();
        workspace.setUsername(owner);
        workspace.setWorkspaceId(workspaceId);
        workspace.setDefault(true);
        workspace.setCurrentBranch("draft");
        workspace.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        var rows = mock(UserWorkspaceRepository.class);
        when(rows.findByUsernameAndSharedFalse(owner)).thenReturn(Optional.of(workspace));
        when(rows.findByWorkspaceId(workspaceId)).thenReturn(Optional.of(workspace));
        when(rows.save(any(UserWorkspace.class))).thenAnswer(call -> call.getArgument(0));
        when(rows.claimProvisioning(eq(workspaceId), eq(owner),
                eq(WorkspaceProvisioningStatus.PROVISIONING), anyCollection())).thenAnswer(call -> {
            if (workspace.getProvisioningStatus() != WorkspaceProvisioningStatus.NOT_PROVISIONED) return 0;
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
            return 1;
        });
        var primary = new SystemRepository();
        primary.setRepositoryId("bootstrap-window-primary");
        primary.setPrimaryRepo(true);
        primary.setDefaultBranch("draft");
        primary.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        var catalog = mock(SystemRepositoryService.class);
        when(catalog.getPrimaryRepository()).thenReturn(primary);
        when(catalog.getRepository(primary.getRepositoryId())).thenReturn(primary);
        var exportStarted = new CountDownLatch(1);
        var releaseExport = new CountDownLatch(1);
        var exporter = mock(TaxDslExportService.class);
        when(exporter.exportAll("default")).thenAnswer(call -> {
            exportStarted.countDown();
            assertThat(releaseExport.await(10, TimeUnit.SECONDS)).as("The first workspace read releases bootstrap").isTrue();
            return DSL;
        });
        var state = new AppInitializationStateService();
        try (var factory = new DslGitRepositoryFactory(null, catalog);
             var context = context(factory, exporter, state, Map.of())) {
            var manager = new WorkspaceManager(rows, 50, catalog, factory);
            var resolver = new WorkspaceContextResolver(manager, catalog, rows);
            var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try {
                var bootstrap = executor.submit(() -> state.update(State.READY, "Catalogue ready before initial checkpoint"));
                assertThat(exportStarted.await(10, TimeUnit.SECONDS)).as("The real bootstrap listener is exporting").isTrue();
                assertThat(state.isReady()).isTrue();
                assertThat(factory.getSystemRepository().getHeadCommit("draft")).isNull();

                Throwable firstFailure = catchThrowable(() -> resolver.resolveRepositoryContextForUser(owner));
                var pendingStatus = workspace.getProvisioningStatus();
                String pendingError = workspace.getProvisioningError();
                releaseExport.countDown();
                bootstrap.get(10, TimeUnit.SECONDS);
                String initialCommit = factory.getSystemRepository().getHeadCommit("draft");
                assertThat(initialCommit).matches("[0-9a-f]{40}");
                var resolved = new AtomicReference<RepositoryContext>();
                Throwable retryFailure = catchThrowable(() -> resolved.set(resolver.resolveRepositoryContextForUser(owner)));

                org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(firstFailure).as("An unseeded source denies the first repository read")
                            .isInstanceOf(ResponseStatusException.class);
                    if (firstFailure instanceof ResponseStatusException response) softly.assertThat(response.getStatusCode().value()).isEqualTo(409);
                    softly.assertThat(pendingStatus).as("An early read must not poison the default workspace")
                            .isEqualTo(WorkspaceProvisioningStatus.NOT_PROVISIONED);
                    softly.assertThat(pendingError).as("Waiting for bootstrap is not a failed allocation").isNull();
                    softly.assertThat(retryFailure).as("The same implicit selection recovers after the real initial commit").isNull();
                    softly.assertThat(resolved.get()).isEqualTo(RepositoryContext.workspace(primary.getRepositoryId(), workspaceId, "draft", owner));
                    softly.assertThat(workspace.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.READY);
                    softly.assertThat(workspace.getBaseCommit()).isEqualTo(initialCommit);
                });
                assertThat(factory.openWorkspaceRepository(workspaceId).getDslAtHead("draft")).isEqualTo(DSL);
            } finally {
                releaseExport.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("Bootstrap worker terminated").isTrue();
            }
        }
    }

    private static DslGitRepositoryFactory factoryReturning(DslGitRepository repository) {
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        when(factory.getSystemRepository()).thenReturn(repository);
        return factory;
    }

    private static TaxDslExportService exporterReturning(String dsl) {
        TaxDslExportService exporter = mock(TaxDslExportService.class);
        when(exporter.exportAll("default")).thenReturn(dsl);
        return exporter;
    }

    private static AnnotationConfigApplicationContext context(
            DslGitRepositoryFactory factory,
            TaxDslExportService exporter,
            AppInitializationStateService state,
            Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("bootstrap-test", properties));
        context.registerBean(DslGitRepositoryFactory.class, () -> factory);
        context.registerBean(TaxDslExportService.class, () -> exporter);
        context.registerBean(AppInitializationStateService.class, () -> state);
        context.register(CatalogueRuntimePolicy.class, GitRepositoryBootstrap.class);
        context.refresh();
        return context;
    }

    private static void publishApplicationReady(AnnotationConfigApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], context, Duration.ZERO));
    }
}
