package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.controller.WorkspaceController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitNativeSyncSourceAuthorityTest {
    enum Operation { PULL, PUBLISH, EXPLICIT_PULL, EXPLICIT_PUBLISH, MERGE, TAKE_SHARED, KEEP_MINE }
    enum SourcePolicyChange { ARCHIVE_BEFORE_PULL, ARCHIVE_BEFORE_PUBLISH, PUBLIC_TO_PRIVATE_BEFORE_PULL }

    @ParameterizedTest @EnumSource(Operation.class)
    void removedPrivateSourceMembershipBlocksEverySyncBeforeAnySideEffect(Operation operation) throws Exception {
        var rows = mock(UserWorkspaceRepository.class);
        var catalog = NativeSyncTestCatalog.create();
        var factory = mock(DslGitRepositoryFactory.class);
        var states = mock(SyncStateRepository.class);
        var merger = mock(SemanticGitMergeService.class);
        var portfolio = mock(WorkspacePortfolioGitPort.class);
        var contexts = mock(WorkspaceContextResolver.class);
        var versions = mock(WorkspaceArchitectureVersionPort.class);
        var source = new SystemRepository();
        source.setRepositoryId("private-source");
        source.setVisibility(RepositoryVisibility.PRIVATE);
        source.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("old-copy"); workspace.setUsername("alice");
        workspace.setSourceRepositoryId("private-source");
        when(rows.findByWorkspaceId("old-copy")).thenReturn(Optional.of(workspace));
            when(rows.findByWorkspaceIdForUpdate("old-copy")).thenReturn(Optional.of(workspace));
        when(catalog.getRepository("private-source")).thenReturn(source);
        when(contexts.resolveRepositoryContextForUser("alice")).thenReturn(
                RepositoryContext.workspace("private-source", "old-copy", "main", "alice"));
        var memberships = new RepositoryMembershipService(mock(RepositoryMembershipRepository.class));
        assertFalse(memberships.canRead(source, "alice"));
        assertFalse(memberships.canContribute(source, "alice"));
        var service = new GitNativeSyncIntegrationService(states, rows, catalog, factory, merger, portfolio, contexts, versions, memberships);
        clearInvocations(factory);

        assertThrows(AccessDeniedException.class, () -> run(service, operation));

        verifyNoInteractions(factory, merger, states, versions);
        verify(portfolio, times(operation == Operation.MERGE ? 2 : 1)).requireAvailable(); verifyNoMoreInteractions(portfolio);
        verify(rows, never()).save(any()); verify(catalog, never()).save(any());
        assertNull(workspace.getCurrentCommit()); assertNull(workspace.getLastFetchedCommit());
    }

    @ParameterizedTest @EnumSource(value = Operation.class, names = {"PULL", "EXPLICIT_PULL", "MERGE", "TAKE_SHARED"})
    void contributorDowngradedToReaderCanStillPullFreshSource(Operation operation) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.member.setRole(RepositoryRole.READER);
            fixture.source.commitDsl("draft", dsl("Fresh source"), "owner", "After downgrade");
            run(fixture.service, operation);
            assertTrue(fixture.local.getDslAtHead("main").contains("Fresh source"));
        }
    }

    @ParameterizedTest @EnumSource(value = Operation.class, names = {"PUBLISH", "EXPLICIT_PUBLISH", "KEEP_MINE"})
    void contributorDowngradedToReaderCannotPublishOrChangeSourceMetadata(Operation operation) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.member.setRole(RepositoryRole.READER);
            fixture.local.commitDsl("main", dsl("Local edit"), "alice", "After downgrade");
            String head = fixture.source.getHeadCommit("draft");
            assertThrows(AccessDeniedException.class, () -> run(fixture.service, operation));
            assertEquals(head, fixture.source.getHeadCommit("draft"));
            verify(fixture.portfolio).requireAvailable(); verifyNoMoreInteractions(fixture.portfolio);
            verify(fixture.catalog, never()).save(any());
            verify(fixture.rows, never()).save(any());
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sourceAuthorityIsRecheckedAfterCopyingHistoryBeforePublishingARef(boolean publish) throws Exception {
        try (var fixture = new Fixture(); var transfer = mockStatic(GitHistoryTransfer.class)) {
            String sourceHead = fixture.source.getHeadCommit("draft");
            String localHead = fixture.local.getHeadCommit("main");
            transfer.when(() -> GitHistoryTransfer.copyCommitClosure(any(), any(), anyString(), anyList()))
                    .thenAnswer(call -> {
                        Object result = call.callRealMethod();
                        when(fixture.memberRows.findByRepositoryIdAndUsername("private-source", "alice"))
                                .thenReturn(Optional.empty());
                        return result;
                    });
            assertThrows(AccessDeniedException.class,
                    () -> run(fixture.service, publish ? Operation.EXPLICIT_PUBLISH : Operation.EXPLICIT_PULL));
            assertEquals(sourceHead, fixture.source.getHeadCommit("draft"));
            assertEquals(localHead, fixture.local.getHeadCommit("main"));
            verify(fixture.portfolio, never()).materializePortfolio(anyString(), anyString(), any());
            verify(fixture.rows, never()).save(any());
        }
    }

    @ParameterizedTest @EnumSource(SourcePolicyChange.class)
    void replacementSourceMetadataRevokesAuthorityBeforeAnyTransferredHistoryIsAccepted(SourcePolicyChange change) throws Exception {
        try (var fixture = new Fixture(); var transfer = mockStatic(GitHistoryTransfer.class)) {
            fixture.sourceMetadata.setLifecycleState(RepositoryLifecycleState.ACTIVE);
            if (change == SourcePolicyChange.PUBLIC_TO_PRIVATE_BEFORE_PULL) {
                fixture.sourceMetadata.setVisibility(RepositoryVisibility.PUBLIC);
                when(fixture.memberRows.findByRepositoryIdAndUsername("private-source", "alice"))
                        .thenReturn(Optional.empty());
            }
            fixture.local.commitDsl("sync-base", dsl("Original"), "alice", "Existing accepted semantic base");
            String sourceHead = fixture.source.getHeadCommit("draft");
            String localHead = fixture.local.getHeadCommit("main");
            String trackingHead = fixture.local.getHeadCommit("sync-base");
            SystemRepository captured = fixture.sourceMetadata;
            SystemRepository replacement = new SystemRepository();
            replacement.setRepositoryId(captured.getRepositoryId()); replacement.setSlug(captured.getSlug());
            replacement.setDefaultBranch(captured.getDefaultBranch()); replacement.setVisibility(RepositoryVisibility.PRIVATE);
            replacement.setLifecycleState(change == SourcePolicyChange.PUBLIC_TO_PRIVATE_BEFORE_PULL
                    ? RepositoryLifecycleState.ACTIVE : RepositoryLifecycleState.ARCHIVED);
            transfer.when(() -> GitHistoryTransfer.copyCommitClosure(any(), any(), anyString(), anyList()))
                    .thenAnswer(call -> {
                        Object result = call.callRealMethod();
                        // A committed catalogue update is a different entity, not a mutation of the captured fixture.
                        when(fixture.catalog.getRepository("private-source")).thenReturn(replacement);
                        return result;
                    });

            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> run(fixture.service,
                    change == SourcePolicyChange.ARCHIVE_BEFORE_PUBLISH ? Operation.EXPLICIT_PUBLISH : Operation.EXPLICIT_PULL));

            assertAll(
                    () -> assertInstanceOf(AccessDeniedException.class, failure),
                    () -> assertEquals(sourceHead, fixture.source.getHeadCommit("draft"), "source ref must remain unchanged after revocation"),
                    () -> assertEquals(localHead, fixture.local.getHeadCommit("main"), "workspace ref must remain unchanged after revocation"),
                    () -> assertEquals(trackingHead, fixture.local.getHeadCommit("sync-base"), "revoked source must not become the accepted base"),
                    () -> assertEquals(RepositoryLifecycleState.ACTIVE, captured.getLifecycleState()),
                    () -> assertNotSame(captured, replacement));
            verify(fixture.portfolio, never()).materializePortfolio(anyString(), anyString(), any());
            verify(fixture.rows, never()).save(any());
            verify(fixture.catalog, never()).save(any());
        }
    }

    @org.junit.jupiter.api.Test
    void successfulPublicationCannotResaveTheCapturedCatalogueEntityOverConcurrentPolicyChanges() throws Exception {
        try (var fixture = new Fixture(); var transfer = mockStatic(GitHistoryTransfer.class)) {
            fixture.sourceMetadata.setLifecycleState(RepositoryLifecycleState.ACTIVE);
            SystemRepository replacement = new SystemRepository();
            replacement.setRepositoryId("private-source"); replacement.setSlug("private-source");
            replacement.setDefaultBranch("draft"); replacement.setLifecycleState(RepositoryLifecycleState.ACTIVE);
            replacement.setVisibility(RepositoryVisibility.PUBLIC);
            fixture.local.commitDsl("main", dsl("Publish current contribution"), "alice", "Local contribution");
            transfer.when(() -> GitHistoryTransfer.copyCommitClosure(any(), any(), anyString(), anyList()))
                    .thenAnswer(call -> {
                        Object result = call.callRealMethod();
                        when(fixture.catalog.getRepository("private-source")).thenReturn(replacement);
                        return result;
                    });

            String published = fixture.service.publishFromWorkspaceToShared("alice", "old-copy");

            assertEquals(published, fixture.source.getHeadCommit("draft"));
            assertEquals(published, fixture.local.getHeadCommit("main"));
            assertEquals(RepositoryVisibility.PRIVATE, fixture.sourceMetadata.getVisibility());
            assertNull(fixture.sourceMetadata.getLastPushAt(), "captured metadata must not be dirtied before an ORM flush");
            verify(fixture.catalog, never()).save(same(fixture.sourceMetadata));
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void primaryCompatibilityAndPublicReadRulesDoNotRestorePrivateContribution(boolean primary) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.sourceMetadata.setPrimaryRepo(primary);
            fixture.sourceMetadata.setVisibility(RepositoryVisibility.PUBLIC);
            when(fixture.memberRows.findByRepositoryIdAndUsername("private-source", "alice")).thenReturn(Optional.empty());
            fixture.source.commitDsl("draft", dsl("Public source"), "owner", "Public change");
            fixture.service.syncFromShared("alice", "main");
            assertTrue(fixture.local.getDslAtHead("main").contains("Public source"));
            fixture.local.commitDsl("main", dsl("Local edit"), "alice", "Local change");
            if (primary) fixture.service.publishToShared("alice", "main");
            else assertThrows(AccessDeniedException.class, () -> fixture.service.publishToShared("alice", "main"));
        }
    }

    @org.junit.jupiter.api.Test
    void repeatingAnInterruptedPublishDoesNotAdoptACommitWrittenByAnotherPublisher() throws Exception {
        var config=new org.hibernate.cfg.Configuration().addAnnotatedClass(WorkspacePublicationOperation.class)
                .addAnnotatedClass(WorkspacePublicationDecision.class).setProperty("hibernate.connection.url",
                        "jdbc:hsqldb:mem:publication-" + java.util.UUID.randomUUID() + ";hsqldb.tx=mvcc")
                .setProperty("hibernate.connection.username","SA").setProperty("hibernate.connection.password","")
                .setProperty("hibernate.hbm2ddl.auto","create").setProperty("hibernate.search.enabled","false");
        try (var f = new Fixture(); var factory=config.buildSessionFactory()) {
            var journal=new WorkspacePublicationJournal(org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory),
                    new org.springframework.orm.jpa.JpaTransactionManager(factory));
            var actor=mock(WorkspacePublicationAuthority.class);
            var grant=new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
            when(actor.authorizePublication("alice")).thenReturn(grant);
            when(actor.currentActor("alice")).thenReturn(grant.principalId());
            f.service.configurePublications(new WorkspacePublicationRecovery(journal,actor));
            var user = mock(WorkspaceResolver.class);
            when(user.resolveCurrentUsername()).thenReturn("alice");
            var endpoint = new WorkspaceController(null,user,null,null,null,f.service,null,f.catalog);
            var http = MockMvcBuilders.standaloneSetup(endpoint).build();
            var firstInput = f.local.commitDsl("main",dsl("Alice reviewed"),"alice","Reviewed change");
            doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "projection temporarily unavailable")).doNothing().when(f.portfolio)
                    .materializePortfolio(anyString(),eq("shared"),any());
            var first = http.perform(post("/api/workspace/publish").param("userBranch","main")
                    .header("Idempotency-Key","reviewed-publish")).andReturn().getResponse();
            assertEquals(202,first.getStatus(),first.getContentAsString());
            String operation=first.getHeader("X-Operation-Id");
            assertNotNull(operation);
            var published = f.source.getHeadCommit("draft");
            assertTrue(f.source.getDslAtCommit(published).contains("Alice reviewed"));
            String originalLocal = f.local.getHeadCommit("main");
            assertTrue(f.source.isAncestor(firstInput,published));
            var independent = f.source.commitDsl("draft",dsl("Bob advanced"),
                    "bob","Later publication");
            http.perform(post("/api/workspace/publish").param("userBranch","main")
                    .header("Idempotency-Key","reviewed-publish")).andReturn();
            var settled=f.service.retryPublication(RepositoryContext.workspace("private-source","old-copy","main","alice"),operation);
            assertEquals("SUPERSEDED",settled.status());
            assertEquals(published,settled.resultCommit());
            assertEquals(grant.principalId(),settled.originalActor());
            assertEquals(independent,f.source.getHeadCommit("draft"),"replaying Alice's operation changed Bob's later source ref");
            assertEquals(originalLocal,f.local.getHeadCommit("main"),
                    "replaying the admitted operation integrated a different publisher's later commit");
        }
    }

    @ParameterizedTest
    @EnumSource(value=WorkspacePublicationOperation.Step.class, mode=EnumSource.Mode.EXCLUDE, names={"COMPLETE"})
    void restartAfterEachPhaseReusesItsPreparedGitAndRecordedOwnership(WorkspacePublicationOperation.Step interrupted,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path disk) throws Exception {
        var context = RepositoryContext.workspace("private-source","old-copy","main","alice");
        var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var authority = mock(WorkspacePublicationAuthority.class);
        when(authority.authorizePublication("alice")).thenReturn(grant);
        when(authority.currentActor("alice")).thenReturn(grant.principalId());
        String url = "jdbc:hsqldb:file:"+disk.resolve("publication")+";hsqldb.tx=mvcc;shutdown=true";
        try (var fixture = new Fixture()) {
            String fork = fixture.source.getHeadCommit("draft");
            String reviewed = fixture.local.commitDsl("main",dsl("Reviewed phase matrix"),"alice","Review");
            String operation;
            try (var first = publicationDatabase(url,"create")) {
                var journal = publicationJournal(first);
                var recovery = new WorkspacePublicationRecovery(journal,authority);
                var pending = recovery.submit(context,false,"one-publication",null,
                        () -> new WorkspacePublicationJournal.Source("draft",fork,fork,reviewed,null), (row,authorize) -> {
                            fixture.service.applyPublicationPhase(row,authorize);
                            if (row.step==interrupted) throw new IOException("Lost acknowledgement after "+interrupted);
                        });
                assertEquals("RETRYABLE",pending.status());
                assertEquals(interrupted.name(),pending.nextPhase());
                assertEquals(1,pending.attempts());
                operation=pending.operationId();
                if (interrupted.ordinal()<=WorkspacePublicationOperation.Step.SOURCE_REF.ordinal()) assertNull(pending.resultCommit());
                else assertEquals(fixture.source.getHeadCommit("draft"),pending.resultCommit());
            }
            try (var restarted = publicationDatabase(url,"validate")) {
                var journal=publicationJournal(restarted);
                var recovered=new WorkspacePublicationRecovery(journal,authority)
                        .retry(context,operation,fixture.service::applyPublicationPhase);
                assertEquals(operation,recovered.operationId());
                assertEquals(grant.principalId(),recovered.originalActor());
                assertEquals(2,recovered.attempts());
                if (interrupted==WorkspacePublicationOperation.Step.PREPARE) {
                    assertEquals("MANUAL_REVIEW",recovered.status());
                    assertEquals(fork,fixture.source.getHeadCommit("draft"));
                } else {
                    assertEquals("COMPLETED",recovered.status(),recovered.decisionCode());
                    assertEquals(recovered.preparedCommit(),recovered.resultCommit());
                    assertEquals(recovered.resultCommit(),fixture.source.getHeadCommit("draft"));
                    assertTrue(fixture.source.isAncestor(reviewed,recovered.resultCommit()));
                    String canonical = new SemanticGitMergeService().mergeContent(dsl("Original"),dsl("Original"),
                            dsl("Reviewed phase matrix")).mergedText();
                    assertEquals(canonical,fixture.source.getDslAtCommit(recovered.resultCommit()));
                    assertEquals(canonical,fixture.local.getDslAtHead("main"));
                    assertEquals(canonical,fixture.local.getDslAtHead("sync-base"));
                }
                try (var session=restarted.openSession()) {
                    var decisions=session.createQuery("select d from WorkspacePublicationDecision d where d.operation=:id order by d.id",
                            WorkspacePublicationDecision.class).setParameter("id",operation).getResultList();
                    assertTrue(decisions.stream().allMatch(decision -> grant.principalId().equals(decision.actor)));
                    assertTrue(decisions.stream().anyMatch(decision -> "PUBLICATION_INTERRUPTED".equals(decision.decision)));
                    assertEquals(recovered.decisionCode(),decisions.getLast().decision);
                }
            }
        }
    }

    private static org.hibernate.SessionFactory publicationDatabase(String url,String schema) {
        return new org.hibernate.cfg.Configuration().addAnnotatedClass(WorkspacePublicationOperation.class)
                .addAnnotatedClass(WorkspacePublicationDecision.class).setProperty("hibernate.connection.url",url)
                .setProperty("hibernate.connection.username","SA").setProperty("hibernate.connection.password","")
                .setProperty("hibernate.hbm2ddl.auto",schema).setProperty("hibernate.search.enabled","false").buildSessionFactory();
    }

    private static WorkspacePublicationJournal publicationJournal(org.hibernate.SessionFactory factory) {
        return new WorkspacePublicationJournal(org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory),
                new org.springframework.orm.jpa.JpaTransactionManager(factory));
    }

    private static String dsl(String title) { return "element A type System {\n  title: \"" + title + "\";\n}\n"; }
    private static final class Fixture implements AutoCloseable {
        final DslGitRepositoryFactory repositories = new DslGitRepositoryFactory(null);
        final com.taxonomy.workspace.storage.DslGitRepository source = repositories.getCentralRepository("private-source");
        final com.taxonomy.workspace.storage.DslGitRepository local = repositories.openWorkspaceRepository("old-copy");
        final RepositoryMembershipRepository memberRows = mock(RepositoryMembershipRepository.class);
        final RepositoryMembership member = new RepositoryMembership();
        final SystemRepository sourceMetadata = new SystemRepository();
        final SystemRepositoryService catalog = NativeSyncTestCatalog.create();
        final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
        final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);
        final GitNativeSyncIntegrationService service;
        Fixture() throws Exception {
            sourceMetadata.setRepositoryId("private-source"); sourceMetadata.setSlug("private-source");
            sourceMetadata.setDefaultBranch("draft"); sourceMetadata.setVisibility(RepositoryVisibility.PRIVATE);
            when(catalog.getRepository("private-source")).thenReturn(sourceMetadata);
            member.setRepositoryId("private-source"); member.setUsername("alice"); member.setRole(RepositoryRole.CONTRIBUTOR);
            when(memberRows.findByRepositoryIdAndUsername("private-source", "alice")).thenReturn(Optional.of(member));
            var memberships = new RepositoryMembershipService(memberRows);
            assertTrue(memberships.canContribute(sourceMetadata, "alice"));
            var workspace = new UserWorkspace(); workspace.setWorkspaceId("old-copy"); workspace.setUsername("alice");
            workspace.setSourceRepositoryId("private-source"); workspace.setSourceBranch("draft"); workspace.setCurrentBranch("main");
            String base = source.commitDsl("draft", dsl("Original"), "owner", "Seed");
            workspace.setBaseCommit(base);
            local.commitDsl("main", dsl("Original"), "alice", "Seed");
            when(rows.findByWorkspaceId("old-copy")).thenReturn(Optional.of(workspace));
            when(rows.findByWorkspaceIdForUpdate("old-copy")).thenReturn(Optional.of(workspace));
            var states = mock(SyncStateRepository.class);
            when(states.findByUsername("alice")).thenReturn(Optional.of(new SyncState()));
            var contexts = mock(WorkspaceContextResolver.class);
            when(contexts.resolveRepositoryContextForUser("alice")).thenReturn(RepositoryContext.workspace("private-source", "old-copy", "main", "alice"));
            var versions = new WorkspaceArchitectureVersionPort() {
                public <T> T version(RepositoryContext c, String rationale, GitAction<T> action) throws IOException { return action.run(); }
            };
            service = new GitNativeSyncIntegrationService(states, rows, catalog, repositories, new SemanticGitMergeService(), portfolio, contexts, versions, memberships);
        }
        public void close() { repositories.close(); }
    }

    private static String run(GitNativeSyncIntegrationService service, Operation op) throws IOException {
        return switch (op) {
            case PULL -> service.syncFromShared("alice", "main");
            case PUBLISH -> service.publishToShared("alice", "main");
            case EXPLICIT_PULL -> service.syncFromSharedToWorkspace("alice", "old-copy");
            case EXPLICIT_PUBLISH -> service.publishFromWorkspaceToShared("alice", "old-copy");
            case MERGE -> service.resolveDiverged("alice", "main", SyncIntegrationService.DivergedStrategy.MERGE);
            case TAKE_SHARED -> service.resolveDiverged("alice", "main", SyncIntegrationService.DivergedStrategy.TAKE_SHARED);
            case KEEP_MINE -> service.resolveDiverged("alice", "main", SyncIntegrationService.DivergedStrategy.KEEP_MINE);
        };
    }
}
