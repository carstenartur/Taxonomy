package com.taxonomy.editor;

import com.taxonomy.backup.BackupWriteBarrier;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioGitPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchitectureEditorPortfolioReconciliationTest {
    private static final String PROJECT = "project OBSERVATION {\n title: \"Original project\";\n x-portfolio-managed: true;\n}\n";
    private static final String BASE = PROJECT + "element recorder type Component {\n title: \"Recorder\";\n}\n"
            + "element reporter type Component {\n title: \"Reporter\";\n}\n";
    private final RepositoryContext scope = RepositoryContext.workspace("portfolio-repository", "portfolio-workspace", "draft", "alice");
    private final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);
    private EditorPersistenceFixture fixture;
    private ArchitectureEditorService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:portfolio-reconciliation-" + UUID.randomUUID() + ";shutdown=true");
        service = fixture.service;
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("portfolio", portfolio);
        beans.registerSingleton("writeBarrier", BackupWriteBarrier.disabled());
        var injection = new AutowiredAnnotationBeanPostProcessor();
        injection.setBeanFactory(beans);
        injection.processInjection(service);
        fixture.repositories.resolveRepository(scope).commitDsl("draft", BASE, "alice", "Baseline");
        service.execute(scope, new Command(service.read(scope, null).context(), metadata(),
                new SemanticCommand(new UpdateArchitectureElement("recorder", null, Map.of("title", "Local recorder")))));
    }

    @AfterEach
    void close() { fixture.close(); }

    @Test
    void unchangedPortfolioDoesNotRequireTheOptionalFeature() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Portfolio unavailable")).when(portfolio).requireAvailable();
        fixture.repositories.resolveRepository(scope).commitDsl("draft", BASE.replace("Reporter", "Incoming reporter"), "bob", "Incoming architecture");
        var accepted = service.acceptReconciliation(scope, request());
        assertThat(fixture.repositories.resolveRepository(scope).getDslAtCommit(accepted.commitId()))
                .contains("Local recorder", "Incoming reporter", "Original project");
        verifyNoInteractions(portfolio);
    }

    @Test
    void changedPortfolioJoinsTheJournalTransactionAndExactRetryDoesNotMaterializeTwice() throws Exception {
        fixture.repositories.resolveRepository(scope).commitDsl("draft", BASE.replace("Original project", "Incoming project"), "bob", "Incoming project");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(invocation.<String>getArgument(0)).contains("Incoming project", "Local recorder");
            return null;
        }).when(portfolio).materializePortfolio(anyString(), anyString(), any());
        var request = request();
        var accepted = service.acceptReconciliation(scope, request);
        assertThat(service.acceptReconciliation(scope, request).commitId()).isEqualTo(accepted.commitId());
        verify(portfolio).requireAvailable();
        verify(portfolio).materializePortfolio(fixture.repositories.resolveRepository(scope).getDslAtCommit(accepted.commitId()),
                "alice", new WorkspaceContext("alice", "portfolio-workspace", "draft", "portfolio-repository"));
        verify(portfolio, atLeastOnce()).validatePortfolio(anyString(), anyString());
        verifyNoMoreInteractions(portfolio);
        assertThat(fixture.journal.read(scope).operations()).hasSize(2);
        assertThat(fixture.journal.checkpoints(scope)).hasSize(1);
    }

    @Test
    void unavailablePortfolioRejectsManagedChangesBeforeAnyJournalOrGitMutation() throws Exception {
        var git = fixture.repositories.resolveRepository(scope);
        git.commitDsl("draft", BASE.replace("Original project", "Incoming project"), "bob", "Incoming project");
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Portfolio unavailable")).when(portfolio).requireAvailable();
        var request = request();
        var retained = fixture.journal.read(scope);
        String head = git.getHeadCommit("draft");
        assertThatThrownBy(() -> service.acceptReconciliation(scope, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, problem -> assertThat(problem.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(fixture.journal.read(scope)).isEqualTo(retained);
        assertThat(fixture.journal.checkpoints(scope)).isEmpty();
        assertThat(git.getHeadCommit("draft")).isEqualTo(head);
        verify(portfolio).requireAvailable();
        verify(portfolio, atLeastOnce()).validatePortfolio(anyString(), anyString());
        verifyNoMoreInteractions(portfolio);
    }

    @Test
    void managedBusinessDeletionRequiresExplicitArchivalBeforeAnyMutation() throws Exception {
        var git = fixture.repositories.resolveRepository(scope);
        git.commitDsl("draft", BASE.replace(PROJECT, ""), "bob", "Incoming omission");
        var retained = fixture.journal.read(scope);
        String head = git.getHeadCommit("draft");
        Context context = service.read(scope, null).context();
        Metadata metadata = metadata();
        var preview = service.previewReconciliation(scope, new ReconciliationRequest(context, metadata, Map.of(), null, null));
        assertThat(preview.canAccept()).isFalse();
        assertThat(preview.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.id()).isEqualTo("project:OBSERVATION");
            assertThat(conflict.local()).isEqualTo(PROJECT);
            assertThat(conflict.incoming()).isNull();
        });
        Map<String, Resolution> choices = new LinkedHashMap<>();
        preview.conflicts().forEach(conflict -> choices.put(conflict.id(), Resolution.LOCAL));
        choices.put("project:OBSERVATION", Resolution.INCOMING);
        assertThatThrownBy(() -> service.previewReconciliation(scope, new ReconciliationRequest(context, metadata, choices, null, null)))
                .isInstanceOfSatisfying(CommandProblem.class, problem -> {
                    assertThat(problem.code()).isEqualTo("PORTFOLIO_RECONCILIATION_REQUIRED");
                    assertThat(problem.getMessage()).containsIgnoringCase("archiv");
                    assertThat(problem.dependencies()).contains("project:OBSERVATION");
                });
        assertThat(fixture.journal.read(scope)).isEqualTo(retained);
        assertThat(fixture.journal.checkpoints(scope)).isEmpty();
        assertThat(git.getHeadCommit("draft")).isEqualTo(head);
        choices.put("project:OBSERVATION", Resolution.LOCAL);
        var local = service.previewReconciliation(scope, new ReconciliationRequest(context, metadata, choices, null, null));
        assertThat(local.canAccept()).isTrue();
        service.acceptReconciliation(scope, new ReconciliationRequest(context, metadata, choices, local.headCommit(), local.fingerprint()));
        assertThat(service.read(scope, null).dsl()).contains(PROJECT, "Local recorder");
        verifyNoInteractions(portfolio);
    }

    private ReconciliationRequest request() throws Exception {
        Context context = service.read(scope, null).context();
        Metadata metadata = metadata();
        var preview = service.previewReconciliation(scope, new ReconciliationRequest(context, metadata, Map.of(), null, null));
        assertThat(preview.canAccept()).isTrue();
        return new ReconciliationRequest(context, metadata, Map.of(), preview.headCommit(), preview.fingerprint());
    }

    private static Metadata metadata() {
        String id = UUID.randomUUID().toString();
        return new Metadata(id, id, id, "Reconcile source decisions");
    }
}
