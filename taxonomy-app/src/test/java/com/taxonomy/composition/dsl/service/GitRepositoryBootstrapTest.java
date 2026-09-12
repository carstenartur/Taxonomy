package com.taxonomy.composition.dsl.service;

import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.shared.service.AppInitializationStateService;
import com.taxonomy.shared.service.AppInitializationStateService.State;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
    private static final String DSL = "meta { namespace: \"bootstrap\"; }\n";
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

    @Test
    void earlyApplicationReadyDefersUntilInitializationReadyAndRepeatedEventsStayIdempotent()
            throws IOException {
        try (DslGitRepository repository = new DslGitRepository()) {
            DslGitRepositoryFactory factory = factoryReturning(repository);
            TaxDslExportService exporter = exporterReturning(DSL);
            AppInitializationStateService state = new AppInitializationStateService();

            try (AnnotationConfigApplicationContext context = context(factory, exporter, state, Map.of())) {
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

    @Test
    void readyAtApplicationStartBootstrapsOnApplicationReady() throws IOException {
        try (DslGitRepository repository = new DslGitRepository()) {
            DslGitRepositoryFactory factory = factoryReturning(repository);
            TaxDslExportService exporter = exporterReturning(DSL);
            AppInitializationStateService state = readyState();

            try (AnnotationConfigApplicationContext context = context(factory, exporter, state, Map.of())) {
                publishApplicationReady(context);
                assertThat(repository.getDslAtHead("draft")).isEqualTo(DSL);
                assertThat(repository.getCommitCount("draft")).isEqualTo(1);
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
        context.register(GitRepositoryBootstrap.class);
        context.refresh();
        return context;
    }

    private static void publishApplicationReady(AnnotationConfigApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], context, Duration.ZERO));
    }
}
