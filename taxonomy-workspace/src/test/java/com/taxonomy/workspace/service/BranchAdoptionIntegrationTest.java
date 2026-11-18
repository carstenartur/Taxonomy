package com.taxonomy.workspace.service;

import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchAdoptionIntegrationTest {
    private RepositoryContext context = RepositoryContext.centralWrite("architecture", "draft", "alice");
    private final WorkspaceFetchAuthority.Grant grant = new WorkspaceFetchAuthority.Grant(UUID.randomUUID().toString(),
            "LOCAL", "a".repeat(64));
    private final WorkspaceFetchAuthority actors = mock(WorkspaceFetchAuthority.class);
    private final DslGitRepositoryFactory repositories = new DslGitRepositoryFactory(null);
    private final SystemRepositoryService catalogue = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
    private SessionFactory db;
    private TransactionTemplate tx;
    private BranchAdoptionService rules;
    private DslGitRepository git;
    private String local;

    @BeforeEach
    void setUp() throws IOException {
        var system = new SystemRepository();
        system.setDefaultBranch("main");
        when(catalogue.lockCurrentPublicationSource("architecture")).thenReturn(system);
        when(catalogue.getDefaultBranch("architecture")).thenReturn("main");
        when(memberships.canContribute(system, "alice")).thenReturn(true);
        when(actors.authorize("alice", RepositoryScope.CENTRAL_WRITE)).thenReturn(grant);
        startApplication();
        git = repositories.getCentralRepository("architecture");
        git.commitDsl("main", model("base", "base", "base"), "a", "base");
        git.createBranch("draft", "main");
        git.createBranch("untouched", "main");
        local = git.commitDsl("draft", model("mine", "base", "base"), "a", "local work");
    }

    private final String databaseUrl = "jdbc:hsqldb:mem:adopt" + UUID.randomUUID();
    private final UserWorkspaceRepository workspaceRows = mock(UserWorkspaceRepository.class);
    private final WorkspaceArchitectureVersionPort editor = mock(WorkspaceArchitectureVersionPort.class);
    private final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);

    private void startApplication() {
        db = new Configuration().setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", databaseUrl)
                .setProperty("hibernate.connection.username", "SA").setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "update").setProperty("hibernate.search.enabled", "false")
                .addAnnotatedClass(BranchAdoptionRule.class).addAnnotatedClass(BranchAdoptionEvent.class)
                .buildSessionFactory();
        tx = new TransactionTemplate(new JpaTransactionManager(db));
        var targets = new BranchAdoptionTargets(catalogue, memberships, workspaceRows, repositories);
        EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(db);
        rules = new BranchAdoptionService(em, targets, actors, editor,
                portfolio, CatalogueDefinitionResolver.standard());
    }

    private void restart() {
        db.close();
        startApplication();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void anExplicitActivationAfterRestoreStartsAtTheCurrentTipWithAFreshGrant() throws IOException {
        configure(true);
        var current = git.commitDsl("main", model("base", "new", "base"), "alice", "new source");
        in(() -> {
            var session = SharedEntityManagerCreator.createSharedEntityManager(db);
            var rule = session.find(BranchAdoptionRule.class, BranchAdoptionRule.key(context));
            rule.enabled = false;
            rule.status = "RESTORED_REVIEW_REQUIRED";
            rule.loginKind = "RESTORED";
            rule.bindingKey = "0".repeat(64);
            rule.cursor = rule.sourceTip = rule.prepared = null;
            rule.nextCheck = Long.MAX_VALUE;
            return null;
        });
        restart();
        assertThat(rules.due()).isEmpty();
        configure(true);
        assertThat(view().sourceCommit()).isEqualTo(current);
        advance();
        assertThat(git.getHeadCommit("draft")).isEqualTo(local);
        assertThat(view().status()).isEqualTo("ENABLED");
    }

    @Test
    void optionalPerBranchRuleStopsForReviewAndPreservesOrderedGitAndSqlAudit() throws IOException {
        assertThat(rules.inspect(context).enabled()).isFalse();
        String untouched = git.getHeadCommit("untouched");
        assertThat(rules.due()).isEmpty();
        configure(true);
        var first = git.commitDsl("main", model("base", "first", "base"), "a", "one");
        var second = git.commitDsl("main", model("base", "second", "base"), "a", "two on one");
        var conflict = git.commitDsl("main", model("other", "second", "base"), "a", "change same object");
        advance();
        advance();
        advance();
        assertThat(view().sourceCommit()).isEqualTo(first);
        assertThat(view().status()).isEqualTo("APPLIED");
        var firstResult = view().targetAfter();
        advance();
        advance();
        var race = git.commitDsl("draft", model("mine", "first", "race"), "b", "concurrent writer");
        failTick(false);
        assertThat(view().sourceCommit()).isEqualTo(second);
        assertThat(view().status()).isEqualTo("PREREQUISITE");
        assertThat(git.getHeadCommit("draft")).isEqualTo(race);
        decide("RETRY", second);
        advance();
        advance();
        advance();
        assertThat(git.getDslAtHead("draft")).isEqualTo(model("mine", "second", "race"));
        advance();
        advance();
        assertThat(view().status()).isEqualTo("CONFLICT");
        assertThat(view().sourceCommit()).isEqualTo(conflict);
        restart();
        assertThat(view().status()).isEqualTo("CONFLICT");
        assertThat(rules.due()).isEmpty();
        assertThatThrownBy(() -> decide("SKIP", first)).isInstanceOf(IllegalStateException.class);
        decide("SKIP", conflict);
        var next = git.commitDsl("main", model("other", "third", "base"), "a", "independent after skip");
        advance();
        advance();
        advance();
        assertThat(view().sourceCommit()).isEqualTo(next);
        assertThat(git.getDslAtHead("draft")).isEqualTo(model("mine", "third", "race"));
        configure(false);
        git.commitDsl("main", model("other", "disabled", "base"), "a", "still isolated");
        assertThat(rules.due()).isEmpty();
        assertThat(git.getHeadCommit("untouched")).isEqualTo(untouched);
        var history = rules.history(context).reversed();
        assertThat(history).extracting(BranchAdoptionService.Event::status)
                .containsSubsequence("ENABLED", "APPLIED", "PREREQUISITE", "APPLIED", "CONFLICT", "SKIPPED", "APPLIED",
                        "DISABLED");
        assertThat(history).anyMatch(e -> first.equals(e.sourceCommit()) && local.equals(e.targetBefore())
                && firstResult.equals(e.targetAfter()));
    }

    @Test
    void restoredAuthorityAndPartiallyPublishedGitUseOnlyPinnedInputs() throws IOException {
        configure(true);
        var change = git.commitDsl("main", model("base", "new", "base"), "a", "change");
        advance();
        advance();
        doThrow(new AccessDeniedException("revoked")).when(actors).checkPublication(grant, "alice",
                RepositoryScope.CENTRAL_WRITE, true);
        failTick(true);
        assertThat(git.getHeadCommit("draft")).isEqualTo(local);
        assertThat(view().status()).isEqualTo("AUTHORIZATION");
        doNothing().when(actors).checkPublication(grant, "alice", RepositoryScope.CENTRAL_WRITE, true);
        decide("RETRY", change);
        advance();
        advance();
        // Lost SQL acknowledgement: actual Git CAS succeeds, then the enclosing transaction rolls back.
        var due = due();
        assertThatThrownBy(() -> in(() -> {
            rules.advance(due);
            throw new IllegalStateException("process interrupted");
        }))
                .isInstanceOf(IllegalStateException.class);
        restart();
        var actual = git.getHeadCommit("draft");
        assertThat(actual).isNotEqualTo(local);
        assertThat(view().status()).isEqualTo("PREPARED");
        advance();
        assertThat(view().targetAfter()).isEqualTo(actual);
        assertThat(git.getHeadCommit("draft")).isEqualTo(actual);
    }

    @Test
    void aCurrentCentralMaintainerCanStopAnotherActorsRuleWithoutReplacingItsEvidence() throws IOException {
        configure(true);
        var incoming = git.commitDsl("main", model("base", "new", "base"), "alice", "incoming");
        advance();
        advance();
        assertThat(view().status()).isEqualTo("PREPARED");
        var original = in(() -> {
            var rule = SharedEntityManagerCreator.createSharedEntityManager(db)
                    .find(BranchAdoptionRule.class, BranchAdoptionRule.key(context));
            return new String[] { rule.principalId, rule.username, rule.bindingKey, rule.cursor, rule.sourceTip,
                    rule.prepared, rule.targetBefore, rule.targetAfter };
        });
        var successor = new WorkspaceFetchAuthority.Grant(UUID.randomUUID().toString(), "LOCAL", "b".repeat(64));
        when(actors.authorize("bob", RepositoryScope.CENTRAL_WRITE)).thenReturn(successor);
        var system = catalogue.lockCurrentPublicationSource("architecture");
        when(memberships.canContribute(system, "bob")).thenReturn(true);
        context = RepositoryContext.centralWrite("architecture", "draft", "bob");
        assertThatThrownBy(() -> configure(false)).isInstanceOf(AccessDeniedException.class);
        assertThat(view().enabled()).isTrue();

        when(memberships.canMaintain(system, "bob")).thenReturn(true);
        configure(false);
        assertThat(view().enabled()).isFalse();
        assertThat(view().status()).isEqualTo("DISABLED");
        assertThat(view().sourceCommit()).isEqualTo(incoming);
        assertThat(git.getHeadCommit("draft")).isEqualTo(local);
        assertThat(rules.due()).isEmpty();
        var retained = in(() -> {
            var rule = SharedEntityManagerCreator.createSharedEntityManager(db)
                    .find(BranchAdoptionRule.class, BranchAdoptionRule.key(context));
            return new String[] { rule.principalId, rule.username, rule.bindingKey, rule.cursor, rule.sourceTip,
                    rule.prepared, rule.targetBefore, rule.targetAfter };
        });
        assertThat(retained).containsExactly(original);
        var history = rules.history(context);
        assertThat(history.getFirst().status()).isEqualTo("DISABLED");
        assertThat(history.getFirst().actor()).isEqualTo(successor.principalId());
        assertThat(history.getFirst().sourceCommit()).isEqualTo(incoming);
        assertThat(history.stream().skip(1)).allMatch(event -> grant.principalId().equals(event.actor()));

        configure(true);
        assertThat(view().enabled()).isTrue();
        assertThat(view().sourceCommit()).isEqualTo(incoming);
        assertThat(rules.history(context).getFirst().actor()).isEqualTo(successor.principalId());
    }

    @Test
    void theOriginalActorCanStillStopAfterLosingUpstreamContribution() {
        configure(true);
        when(memberships.canContribute(any(), eq("alice"))).thenReturn(false);
        configure(false);
        assertThat(view().enabled()).isFalse();
        assertThat(rules.history(context).getFirst().actor()).isEqualTo(grant.principalId());
    }

    @Test
    void aCleanTextMergeCannotImportABrokenSemanticDependencyAfterAnExplicitSkip() throws IOException {
        configure(true);
        var source = git.getDslAtHead("main");
        var incoming = git.commitDsl("main", source.replace("element A type System", "element Renamed type System"),
                "a", "rename");
        git.commitDsl("main", git.getDslAtHead("main") + "view V {\n  include: Renamed;\n}\n", "a",
                "depends on renamed element");
        advance();
        advance();
        assertThat(view().status()).isEqualTo("CONFLICT");
        decide("SKIP", incoming);
        advance();
        advance();
        assertThat(view().status()).isEqualTo("PREREQUISITE");
        assertThat(git.getHeadCommit("draft")).isEqualTo(local);
    }

    @Test
    void aPrivateCopyCannotPublishThroughALostOwnerBindingOrDirtyPortfolio() throws IOException {
        var privateGit = repositories.getWorkspaceRepository("__shared__");
        com.taxonomy.workspace.storage.GitHistoryTransfer.copyCommitClosure(git, privateGit, local,
                java.util.List.of());
        privateGit.createBranchAtCommit("draft", local);
        var row = new com.taxonomy.workspace.model.UserWorkspace();
        row.setUsername("alice");
        row.setWorkspaceId("__shared__");
        row.setSourceRepositoryId("architecture");
        row.setCurrentBranch("draft");
        row.setCurrentCommit(local);
        row.setProvisioningStatus(com.taxonomy.workspace.model.WorkspaceProvisioningStatus.READY);
        row.assignOwnerPrincipal(UUID.fromString(grant.principalId()));
        when(workspaceRows.findByWorkspaceIdForUpdate("__shared__")).thenReturn(java.util.Optional.of(row));
        context = RepositoryContext.workspace("architecture", "__shared__", "draft", "alice");
        configure(true);
        var next = git.commitDsl("main", model("base", "new", "base"), "a", "change");
        advance();
        advance();
        var scope = WorkspaceResolver.workspaceContext(context);
        when(portfolio.matchesCheckpoint(anyString(), eq("alice"), eq(scope))).thenReturn(false);
        failTick(false);
        assertThat(view().status()).isEqualTo("PREREQUISITE");
        assertThat(privateGit.getHeadCommit("draft")).isEqualTo(local);
        assertThat(git.getHeadCommit("draft")).isEqualTo(local);
        assertThat(rules.inspect(RepositoryContext.centralRead("architecture", "draft", "alice")).enabled()).isFalse();
        when(actors.authorize("alice", RepositoryScope.CENTRAL_WRITE)).thenReturn(new WorkspaceFetchAuthority.Grant(
                UUID.randomUUID().toString(), "LOCAL", "b".repeat(64)));
        assertThatThrownBy(() -> decide("RETRY", next)).isInstanceOf(AccessDeniedException.class);
        when(memberships.canMaintain(any(), eq("alice"))).thenReturn(true);
        assertThatThrownBy(() -> configure(false)).isInstanceOf(AccessDeniedException.class);
        assertThat(view().enabled()).isTrue();
        verifyNoInteractions(editor);
    }

    @Test
    void concurrentWorkerObservationsCanOnlySettleOnePreparedChange() throws Exception {
        configure(true);
        git.commitDsl("main", model("base", "next", "base"), "alice", "incoming");
        advance();
        advance();
        var observed = due();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> execute = () -> {
                start.await();
                return in(() -> {
                    rules.advance(observed);
                    return null;
                });
            };
            var a = workers.submit(execute);
            var b = workers.submit(execute);
            start.countDown();
            a.get(15, java.util.concurrent.TimeUnit.SECONDS);
            b.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(view().status()).isEqualTo("APPLIED");
        assertThat(rules.history(context)).filteredOn(event -> "APPLIED".equals(event.status())).hasSize(1);
    }

    @Test
    void sameBranchNameInAPrivateCopyIsIsolatedFromItsRecordedSource() throws IOException {
        var upstream = git.getHeadCommit("main");
        var copy = repositories.getWorkspaceRepository("copy");
        com.taxonomy.workspace.storage.GitHistoryTransfer.copyCommitClosure(git, copy, upstream, java.util.List.of());
        copy.createBranchAtCommit("main", upstream);
        var row = new com.taxonomy.workspace.model.UserWorkspace();
        row.setUsername("alice");
        row.setWorkspaceId("copy");
        row.setSourceRepositoryId("architecture");
        row.setCurrentBranch("main");
        row.setCurrentCommit(upstream);
        row.setProvisioningStatus(com.taxonomy.workspace.model.WorkspaceProvisioningStatus.READY);
        when(workspaceRows.findByWorkspaceIdForUpdate("copy")).thenReturn(java.util.Optional.of(row));
        context = RepositoryContext.workspace("architecture", "copy", "main", "alice");
        configure(true);
        git.commitDsl("main", model("base", "next", "base"), "alice", "upstream");
        advance();
        advance();
        assertThat(view().status()).isEqualTo("PREPARED");
        var scope = WorkspaceResolver.workspaceContext(context);
        when(portfolio.matchesCheckpoint(anyString(), eq("alice"), eq(scope))).thenReturn(true);
        doAnswer(call -> call.<WorkspaceArchitectureVersionPort.GitAction<?>>getArgument(4).run())
                .when(editor).applyPreparedWorkspaceRebase(eq(context), eq(upstream), anyString(), anyString(), any());
        advance();
        assertThat(view().status()).isEqualTo("APPLIED");
        assertThat(row.getCurrentCommit()).isEqualTo(copy.getHeadCommit("main"))
                .isNotEqualTo(git.getHeadCommit("main"));
        assertThat(copy.getDslAtHead("main")).isEqualTo(git.getDslAtHead("main"));
        context = RepositoryContext.centralWrite("architecture", "main", "alice");
        assertThatThrownBy(() -> configure(true)).isInstanceOf(IllegalArgumentException.class);
    }

    private BranchAdoptionRule.View view() {
        return rules.inspect(context);
    }

    private BranchAdoptionService.Due due() {
        return new BranchAdoptionService.Due(BranchAdoptionRule.key(context), view().revision());
    }

    private void configure(boolean enabled) {
        in(() -> rules.configure(context, enabled, view().revision()));
    }

    private void decide(String action, String commit) {
        in(() -> rules.decide(context, action, commit, view().revision()));
    }

    private void advance() {
        var due = due();
        in(() -> {
            rules.advance(due);
            return null;
        });
    }

    private void failTick(boolean denied) {
        var observed = due();
        assertThatThrownBy(() -> in(() -> {
            rules.advance(observed);
            return null;
        })).isInstanceOf(RuntimeException.class);
        in(() -> {
            rules.failed(observed, denied);
            return null;
        });
    }

    private <T> T in(WorkspaceArchitectureVersionPort.GitAction<T> action) {
        return tx.execute(status -> {
            try {
                return action.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static String model(String a, String b, String c) {
        return "element A type System {\n  title: \"" + a + "\";\n}\n"
                + "element B type System {\n  title: \"" + b + "\";\n}\n"
                + "element C type System {\n  title: \"" + c + "\";\n}\n";
    }
}
