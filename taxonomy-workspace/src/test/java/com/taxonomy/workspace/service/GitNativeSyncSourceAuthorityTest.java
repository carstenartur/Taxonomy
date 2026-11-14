package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.controller.WorkspaceController;
import com.taxonomy.workspace.controller.WorkspacePublicationController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void repeatedPullKeepsItsCapturedSourceAfterTheWorkspaceRefAdvanced(boolean legacy) throws Exception {
        var context = RepositoryContext.workspace("private-source","old-copy","main","alice");
        var actor = mock(WorkspacePublicationAuthority.class);
        var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        when(actor.authorizePublication("alice")).thenReturn(grant);
        when(actor.currentActor("alice")).thenReturn(grant.principalId());
        try (var fixture = new Fixture();
             var database = publicationDatabase("jdbc:hsqldb:mem:pull-" + java.util.UUID.randomUUID(),"create")) {
            fixture.member.setRole(RepositoryRole.READER);
            var journal=publicationJournal(database);
            fixture.service.configurePublications(new WorkspacePublicationRecovery(journal,actor));
            var user=mock(WorkspaceResolver.class);
            when(user.resolveCurrentUsername()).thenReturn("alice");
            var http=MockMvcBuilders.standaloneSetup(
                    new WorkspaceController(null,user,null,null,null,fixture.service,null,fixture.catalog)).build();
            var request=post("/api/workspace/sync-from-shared").param("userBranch","main");
            if (!legacy) request.header("Idempotency-Key","reviewed-original");
            String original=fixture.source.commitDsl("draft",dsl("Original pull"),"owner","Reviewed upstream");
            doThrow(new org.springframework.dao.TransientDataAccessResourceException("Offline after Git"))
                    .doNothing().when(fixture.portfolio).materializePortfolio(anyString(),eq("alice"),any());
            var pending=http.perform(request).andReturn().getResponse();
            assertEquals(202,pending.getStatus(),pending.getContentAsString());
            String id=pending.getHeader("X-Operation-Id");
            assertNotNull(id);
            var applied=fixture.local.getHeadCommit("main");
            assertTrue(fixture.local.getDslAtCommit(applied).contains("Original pull"));
            String later=fixture.source.commitDsl("draft",dsl("Later source"),"owner","Subsequent request");
            var duplicate=http.perform(request).andReturn().getResponse();
            assertEquals(id,duplicate.getHeader("X-Operation-Id"),duplicate.getContentAsString());
            var recovered=fixture.service.retryPublication(context,id);
            assertEquals("COMPLETED",recovered.status(),recovered.decisionCode());
            assertEquals(applied,recovered.resultCommit());
            assertEquals(original,recovered.expectedSourceCommit());
            assertEquals(grant.principalId(),recovered.originalActor());
            assertEquals(applied,fixture.local.getHeadCommit("main"));
            assertEquals(later,fixture.source.getHeadCommit("draft"));
            var owner=fixture.rows.findByWorkspaceId("old-copy").orElseThrow();
            assertEquals(original,owner.getLastIntegratedCommit());
            assertEquals(original,owner.getLastFetchedCommit());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void reconciliationUsesTheCallerContractAfterRecordingThePull(boolean requestReceipt) throws Exception {
        var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(), "LOCAL", "a".repeat(64));
        var actor = mock(WorkspacePublicationAuthority.class);
        when(actor.authorizePublication("alice")).thenReturn(grant);
        try (var fixture = new Fixture();
             var db = publicationDatabase("jdbc:hsqldb:mem:pull-command-" + java.util.UUID.randomUUID(), "create")) {
            var journal = publicationJournal(db);
            fixture.service.configurePublications(new WorkspacePublicationRecovery(journal, actor));
            String sourceHead = fixture.source.commitDsl("draft", dsl("Reviewed source"), "owner", "Review");
            String localHead = fixture.local.getHeadCommit("main");
            var conflict = new com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem(
                    "PORTFOLIO_RECONCILIATION_REQUIRED", "version", "Immutable requirement version 2", java.util.List.of());
            doThrow(conflict).when(fixture.portfolio).validatePortfolio(anyString(), anyString(), eq("alice"), any());

            if (requestReceipt) {
                var result = fixture.service.pullWithReceipt("alice", "main", "confirmed-pull", null).operation();
                assertEquals("MANUAL_REVIEW", result.status());
                assertEquals("RECONCILIATION_REQUIRED", result.decisionCode());
                assertNotNull(result.completedAt());
            } else {
                assertSame(conflict, assertThrows(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class,
                        () -> run(fixture.service, Operation.TAKE_SHARED)));
            }

            try (var session = db.openSession()) {
                var saved = session.createSelectionQuery("from WorkspacePublicationOperation", WorkspacePublicationOperation.class)
                        .getSingleResult();
                assertEquals("MANUAL_REVIEW", saved.phase);
                assertNotNull(saved.completedAt);
                assertEquals("RECONCILIATION_REQUIRED", saved.decision);
                assertTrue(saved.isPull());
                assertEquals(sourceHead, saved.inputSource);
            }
            assertEquals(sourceHead, fixture.source.getHeadCommit("draft"));
            assertEquals(localHead, fixture.local.getHeadCommit("main"));
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
    void interruptedPullCannotResumeAfterLosingTheOriginalReaderOrSourceLineage(boolean replaceSource) throws Exception {
        var context=RepositoryContext.workspace("private-source","old-copy","main","alice");
        var grant=new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var actor=mock(WorkspacePublicationAuthority.class);
        when(actor.authorizePublication("alice")).thenReturn(grant);
        when(actor.currentActor("alice")).thenReturn(grant.principalId());
        try (var f=new Fixture(); var db=publicationDatabase("jdbc:hsqldb:mem:pull-authorization-"+java.util.UUID.randomUUID(),"create")) {
            var journal=publicationJournal(db);
            var recovery=new WorkspacePublicationRecovery(journal,actor);
            var fork=f.source.getHeadCommit("draft");
            var original=f.source.commitDsl("draft",dsl("Reviewed source"),"owner","Pull");
            var before=f.local.getHeadCommit("main");
            var admitted=recovery.submitPull(context,true,"bound-reader",null,
                    () -> new WorkspacePublicationJournal.Source("draft",fork,original,before,null),
                    (row,check) -> {
                        if (row.step==WorkspacePublicationOperation.Step.WORKSPACE_REF) throw new IOException("Pause before Git");
                        f.service.applyPublicationPhase(row,check);
                    });
            if (replaceSource) {
                String unrelated=f.source.commitDsl("independent",dsl("Separate history"),"owner","Separate branch");
                var update=f.source.getGitRepository().updateRef("refs/heads/draft");
                update.setExpectedOldObjectId(org.eclipse.jgit.lib.ObjectId.fromString(original));
                update.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(unrelated));
                assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.FORCED,update.forceUpdate());
            } else {
                when(f.memberRows.findByRepositoryIdAndUsername("private-source","alice")).thenReturn(Optional.empty());
            }
            var result=recovery.retry(context,admitted.operationId(),f.service::applyPublicationPhase);
            assertEquals(replaceSource ? "MANUAL_REVIEW" : "DENIED",result.status());
            assertEquals(original,result.expectedSourceCommit());
            assertEquals(before,f.local.getHeadCommit("main"));
            verify(f.portfolio,never()).materializePortfolio(anyString(),anyString(),any());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> recovery.get(RepositoryContext.workspace("private-source","other","main","alice"),admitted.operationId()));
        }
    }

    @ParameterizedTest
    @EnumSource(value=WorkspacePublicationOperation.Step.class,names={"PREPARE","CANDIDATES","WORKSPACE_REF",
            "TRACKING_REF","LOCAL_PORTFOLIO","WORKSPACE_METADATA","PROGRESS"})
    void reopenedPullJournalCompletesOnlyItsRecordedOperation(WorkspacePublicationOperation.Step lost,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path disk) throws Exception {
        var context=RepositoryContext.workspace("private-source","old-copy","main","alice");
        var grant=new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var actor=mock(WorkspacePublicationAuthority.class);
        when(actor.authorizePublication("alice")).thenReturn(grant);
        String url="jdbc:hsqldb:file:"+disk.resolve("pull")+";hsqldb.tx=mvcc;shutdown=true";
        try (var fixture=new Fixture()) {
            String fork=fixture.source.getHeadCommit("draft");
            String upstream=fixture.source.commitDsl("draft",dsl("Reviewed pull"),"owner","Only accepted source");
            String local=fixture.local.getHeadCommit("main");
            String id;
            try (var first=publicationDatabase(url,"create")) {
                var recovery=new WorkspacePublicationRecovery(publicationJournal(first),actor);
                var result=recovery.submitPull(context,true,"one-pull",null,
                        () -> new WorkspacePublicationJournal.Source("draft",fork,upstream,local,null),(row,authorize) -> {
                            fixture.service.applyPublicationPhase(row,authorize);
                            if (row.step==lost) throw new IOException("Lost acknowledgement");
                        });
                assertEquals("RETRYABLE",result.status());
                assertEquals(lost.name(),result.nextPhase());
                id=result.operationId();
            }
            var later=fixture.source.commitDsl("draft",dsl("Unreviewed pull"),"owner","Later source change");
            try (var reopened=publicationDatabase(url,"validate")) {
                var recovery=new WorkspacePublicationRecovery(publicationJournal(reopened),actor);
                var result=recovery.retry(context,id,fixture.service::applyPublicationPhase);
                assertEquals(upstream,result.expectedSourceCommit());
                assertEquals(2,result.attempts());
                assertEquals(later,fixture.source.getHeadCommit("draft"));
                if (lost==WorkspacePublicationOperation.Step.PREPARE) {
                    assertEquals("MANUAL_REVIEW",result.status());
                } else {
                    assertEquals("COMPLETED",result.status(),result.decisionCode());
                    assertEquals(result.resultCommit(),fixture.local.getHeadCommit("main"));
                    assertTrue(fixture.local.getDslAtHead("main").contains("Reviewed pull"));
                    assertEquals(upstream,fixture.rows.findByWorkspaceId("old-copy").orElseThrow().getLastIntegratedCommit());
                }
            }
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
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void publicationRechecksCatalogueAndMembershipUnderThePortfolioFence(boolean archive) throws Exception {
        var authority = mock(WorkspacePublicationAuthority.class);
        var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(), "LOCAL", "a".repeat(64));
        when(authority.authorizePublication("alice")).thenReturn(grant);
        try (var fixture = new Fixture();
             var factory = publicationDatabase("jdbc:hsqldb:mem:publication-grant-" + java.util.UUID.randomUUID(), "create")) {
            fixture.service.configurePublications(new WorkspacePublicationRecovery(publicationJournal(factory), authority));
            String before = fixture.source.getHeadCommit("draft");
            fixture.local.commitDsl("main", dsl("Changed working copy"), "alice", "Edit");
            doAnswer(call -> {
                if (archive) fixture.sourceMetadata.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
                else fixture.member.setRole(RepositoryRole.READER);
                return fixture.sourceMetadata;
            }).when(fixture.catalog).lockCurrentPublicationSource("private-source");

            var publication = fixture.service.publishWithReceipt("alice", "main", "revoked-at-fence", null);
            assertEquals("DENIED", publication.operation().status());
            assertEquals(before, fixture.source.getHeadCommit("draft"));
        }
    }

    @org.junit.jupiter.api.Test
    void terminalCleanupAfterRestartReleasesOnlyItsOwnPinsAndPreservesAnotherOperationsPin() throws Exception {
        var context = RepositoryContext.workspace("private-source","old-copy","main","alice");
        var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var authority = mock(WorkspacePublicationAuthority.class);
        when(authority.authorizePublication("alice")).thenReturn(grant);
        when(authority.currentActor("alice")).thenReturn(grant.principalId());
        try (var fixture=new Fixture(); var factory=publicationDatabase("jdbc:hsqldb:mem:pin-cleanup-"+java.util.UUID.randomUUID(),"create")) {
            var journal=publicationJournal(factory);
            fixture.service.configurePublications(new WorkspacePublicationRecovery(journal,authority));
            fixture.local.commitDsl("main",dsl("Retain this published change"),"alice","Review");
            var published=fixture.service.publishWithReceipt("alice","main","cleanup-command",null).operation();
            assertEquals("COMPLETED",published.status());
            var original=fixture.source.getHeadCommit("draft");
            var other=fixture.source.pinExistingDslCommit("other-operation","draft",original,original);
            fixture.service.configurePublications(new WorkspacePublicationRecovery(publicationJournal(factory),authority));
            fixture.service.recoverDuePublications();
            assertNull(fixture.source.findPreparedDslCommit(published.operationId(),"draft"));
            assertNull(fixture.local.findPreparedDslCommit(published.operationId(),"main"));
            assertNull(fixture.local.findPreparedDslCommit(published.operationId(),"sync-base"));
            assertEquals(other,fixture.source.findPreparedDslCommit("other-operation","draft"));
            assertEquals(original,fixture.source.getHeadCommit("draft"));
            assertEquals("COMPLETED",fixture.service.publicationStatus(context,published.operationId()).status());
            assertEquals("ARCHIVED",fixture.service.archivePublication(context,published.operationId()).status());
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

    @org.junit.jupiter.api.Test
    void retentionRedactsOnlyReleasedScopedReceiptsAndPreventsTheirKeyFromCreatingAnotherPublication() throws Exception {
        var first=RepositoryContext.workspace("private-source","copy-A","main","alice");
        var second=RepositoryContext.workspace("private-source","copy-B","main","alice");
        var grant=new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var source=new WorkspacePublicationJournal.Source("draft","1".repeat(40),"1".repeat(40),"2".repeat(40),null);
        var origin=java.time.Instant.parse("2026-01-01T00:00:00Z");
        try(var factory=publicationDatabase("jdbc:hsqldb:mem:publication-retention-"+java.util.UUID.randomUUID(),"create")) {
            var manager=new org.springframework.orm.jpa.JpaTransactionManager(factory);
            var em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
            java.util.function.IntFunction<WorkspacePublicationJournal> journalAt=day -> new WorkspacePublicationJournal(em,manager,
                    java.time.Clock.fixed(origin.plus(java.time.Duration.ofDays(day)),java.time.ZoneOffset.UTC));
            var journal=journalAt.apply(0);
            var initial=journal.admit(first,grant,false,"shared-client-key",null,() -> source);
            var unrelated=journal.admit(second,grant,false,"shared-client-key",null,() -> source);
            var claim=journal.claim(initial.operationId(),grant.principalId(),false);
            journal.decide(claim,WorkspacePublicationOperation.Status.MANUAL_REVIEW,"REVIEW");
            journalAt.apply(30).expireDue();
            var archived=journalAt.apply(30).get(first,grant.principalId(),initial.operationId());
            assertEquals("ARCHIVED",archived.status());
            assertNotNull(archived.expectedLocalCommit());
            journalAt.apply(121).expireDue();
            assertNull(journalAt.apply(121).get(first,grant.principalId(),initial.operationId()).redactedAt());
            journalAt.apply(121).cleanup(initial.operationId(),grant.principalId(),true,row -> {});
            journalAt.apply(121).expireDue();
            var reduced=journalAt.apply(121).get(first,grant.principalId(),initial.operationId());
            assertNotNull(reduced.redactedAt()); assertNull(reduced.expectedLocalCommit());
            assertEquals(initial.operationId(),reduced.operationId());
            assertEquals(grant.principalId(),reduced.originalActor());
            assertEquals("ADMITTED",journal.get(second,grant.principalId(),unrelated.operationId()).status());
            var gone=assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> journal.admit(first,grant,false,"shared-client-key",null,() -> { throw new AssertionError("replayed operation recaptured Git"); }));
            assertEquals(410,gone.getStatusCode().value());
            try(var session=factory.openSession()){
                var decisions=session.createQuery("select d.decision from WorkspacePublicationDecision d where d.operation=:id order by d.id",String.class)
                        .setParameter("id",initial.operationId()).getResultList();
                assertEquals(java.util.List.of("ADMITTED","REVIEW","ARCHIVED","PINS_RELEASED","GIT_DETAILS_REDACTED"),decisions);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void aLaterPrivateDescendantBeforeCentralPublicationIsNeverReportedAsAPublishedResult() throws Exception {
        var selected=RepositoryContext.workspace("private-source","old-copy","main","alice");
        var grant=new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var authority=mock(WorkspacePublicationAuthority.class);
        when(authority.authorizePublication("alice")).thenReturn(grant);
        try (var fixture=new Fixture(); var factory=publicationDatabase("jdbc:hsqldb:mem:private-descendant-"+java.util.UUID.randomUUID(),"create")) {
            var recovery=new WorkspacePublicationRecovery(publicationJournal(factory),authority);
            String central=fixture.source.getHeadCommit("draft");
            String local=fixture.local.commitDsl("main",dsl("Reviewed edit"),"alice","Project");
            var operation=recovery.submit(selected,false,"private-descendant",null,
                    () -> new WorkspacePublicationJournal.Source("draft",central,central,local,null),(row,check) -> {
                        if (row.step==WorkspacePublicationOperation.Step.SOURCE_REF) {
                            fixture.local.publishPreparedDslCommit(row.id,row.localBranch,row.expectedLocal,row.preparedLocal);
                            fixture.local.commitDsl("main",dsl("Later private edit"),"alice","Amend private copy");
                            throw new java.io.IOException("Interrupted");
                        }
                        fixture.service.applyPublicationPhase(row,check);
                    });
            assertEquals("RETRYABLE",operation.status());
            var result=recovery.retry(selected,operation.operationId(),fixture.service::applyPublicationPhase);
            assertEquals("MANUAL_REVIEW",result.status());
            assertEquals("WORKSPACE_ADVANCED_BEFORE_SOURCE",result.decisionCode());
            assertNull(result.resultCommit());
            assertNull(result.sourceObservedAt());
            assertEquals(central,fixture.source.getHeadCommit("draft"));
            assertTrue(fixture.local.getDslAtHead("main").contains("Later private edit"));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"shared","alice"})
    void laterDatabaseOnlyPortfolioEditSurvivesRecovery(String changedOwner) throws Exception {
        var selected=RepositoryContext.workspace("private-source","old-copy","main","alice");
        var grant=new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var authority=mock(WorkspacePublicationAuthority.class);
        when(authority.authorizePublication("alice")).thenReturn(grant);
        try (var fixture=new Fixture(); var factory=publicationDatabase("jdbc:hsqldb:mem:portfolio-source-"+java.util.UUID.randomUUID(),"create")) {
            var recovery=new WorkspacePublicationRecovery(publicationJournal(factory),authority);
            fixture.local.commitDsl("main",dsl("Reviewed edit"),"alice","Publish");
            String central=fixture.source.getHeadCommit("draft");
            String local=fixture.local.getHeadCommit("main");
            var operation=recovery.submit(selected,false,"same-publication",null,() ->
                new WorkspacePublicationJournal.Source("draft",central,central,local,null),(row,check) -> {
                    if (row.step==WorkspacePublicationOperation.Step.SOURCE_REF) throw new java.io.IOException("Interrupted before publish");
                    fixture.service.applyPublicationPhase(row,check);
                });
            assertEquals("RETRYABLE",operation.status());
            when(fixture.portfolio.lockedPublicationDigest(eq(changedOwner),any())).thenReturn("c".repeat(64));
            var result=recovery.retry(selected,operation.operationId(),fixture.service::applyPublicationPhase);
            assertEquals("MANUAL_REVIEW",result.status());
            assertEquals("SEMANTIC_CONFLICT",result.decisionCode());
            assertNull(result.resultCommit());
            assertEquals(central,fixture.source.getHeadCommit("draft"));
            verify(fixture.portfolio,never()).materializePortfolio(anyString(),anyString(),any());
        }
    }

    @org.junit.jupiter.api.Test
    void finalExpiredLeaseHasOneTerminalDecisionAndOnePinCleanup() throws Exception {
        var owner=RepositoryContext.workspace("private-source","copy","main","alice");
        var grant=new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
        var original=java.time.Instant.parse("2026-01-01T00:00:00Z");
        try (var factory=publicationDatabase("jdbc:hsqldb:mem:expired-publication-"+java.util.UUID.randomUUID(),"create")) {
            var em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
            var manager=new org.springframework.orm.jpa.JpaTransactionManager(factory);
            java.util.function.IntFunction<WorkspacePublicationJournal> at=minute -> new WorkspacePublicationJournal(em,manager,
                    java.time.Clock.fixed(original.plusSeconds(minute*60L),java.time.ZoneOffset.UTC));
            String cause=java.util.UUID.randomUUID().toString();
            var operation=at.apply(0).admit(owner,grant,false,"interrupted",cause,
                    () -> new WorkspacePublicationJournal.Source("main",null,null,null,null));
            assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> at.apply(0).admit(owner,grant,false,"interrupted",java.util.UUID.randomUUID().toString(),
                            () -> {throw new AssertionError("Conflicting replay touched Git");})).getStatusCode().value());
            for (int i=0;i<6;i++) assertNotNull(at.apply(i*6).claim(operation.operationId(),"worker-"+i,false));
            var finalWorker=at.apply(36);
            assertNull(finalWorker.claim(operation.operationId(),"last-observer",false));
            assertNull(finalWorker.claim(operation.operationId(),"second-observer",false));
            var exhausted=finalWorker.get(owner,grant.principalId(),operation.operationId());
            assertEquals("MANUAL_REVIEW",exhausted.status());
            assertEquals(6,exhausted.attempts());
            assertEquals(java.util.List.of(operation.operationId()),finalWorker.dueForCleanup());
            try (var session=factory.openSession()) {
                var decisions=session.createQuery("select d.executor from WorkspacePublicationDecision d where d.operation=:id and d.decision='ATTEMPTS_EXHAUSTED'",String.class)
                        .setParameter("id",operation.operationId()).getResultList();
                assertEquals(java.util.List.of("last-observer"),decisions);
            }
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
            "active,false","sharing_revoked,false","role_revoked,false","ownership_changed,false",
            "active,true","sharing_revoked,true","role_revoked,true","ownership_changed,true"})
    void collaborativeSynchronizationRetainsTheInitiatorAndCurrentWorkspaceGrant(String change,boolean pull) throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Taxonomy-Workspace-Id","old-copy");
        var requestScope = new org.springframework.web.context.request.ServletRequestAttributes(request);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(requestScope);
        try (var fixture = new Fixture(true);
             var database = publicationDatabase("jdbc:hsqldb:mem:collaborator-"+java.util.UUID.randomUUID(),"create")) {
            var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(),"LOCAL","a".repeat(64));
            var authority = mock(WorkspacePublicationAuthority.class);
            when(authority.authorizePublication("alice")).thenReturn(grant);
            when(authority.currentActor("alice")).thenReturn(grant.principalId());
            var journal = publicationJournal(database);
            fixture.service.configurePublications(new WorkspacePublicationRecovery(journal,authority));
            if (pull) fixture.source.commitDsl("draft",dsl("Source for contributor"),"owner","Source version");
            else fixture.local.commitDsl("main",dsl("Collaborator contribution"),"alice","Contribute");
            String prior = fixture.source.getHeadCommit("draft");
            String priorLocal = fixture.local.getHeadCommit("main");
            var owner = fixture.rows.findByWorkspaceId("old-copy").orElseThrow();
            // Interrupt before the central ref is published; local projections and prepared objects remain recoverable.
            var interruption=new org.springframework.dao.TransientDataAccessResourceException("Portfolio temporarily unavailable");
            if (pull) doThrow(interruption).doNothing().when(fixture.portfolio).materializePortfolio(anyString(),eq("alice"),any());
            else doReturn("b".repeat(64)).doThrow(interruption).doReturn("b".repeat(64))
                    .when(fixture.portfolio).lockedPublicationDigest(eq("shared"),any());
            var pending=(pull ? fixture.service.pullWithReceipt("alice","main","shared-contribution",null)
                    : fixture.service.publishWithReceipt("alice","main","shared-contribution",null)).operation();
            assertEquals("RETRYABLE",pending.status(),pending.decisionCode());
            assertEquals(prior,fixture.source.getHeadCommit("draft"));
            switch (change) {
                case "sharing_revoked" -> owner.setCollaborative(false);
                case "role_revoked" -> {
                    fixture.sourceMetadata.setPrimaryRepo(true);
                    fixture.member.setRole(RepositoryRole.READER);
                }
                case "ownership_changed" -> owner.setUsername("different-owner");
                default -> {}
            }
            var selected=RepositoryContext.workspace("private-source","old-copy","main","alice");
            // An unattended run as a different executor still evaluates the initiating contributor.
            var claim=journal.claim(pending.operationId(),"controlled-worker",true);
            assertNotNull(claim);
            if ("active".equals(change)) {
                var complete=journal.get(selected,grant.principalId(),pending.operationId());
                for (var phase : WorkspacePublicationOperation.Step.values()) {
                    if (complete.completedAt()!=null) break;
                    complete=journal.step(claim,fixture.service::applyPublicationPhase);
                }
                assertEquals("COMPLETED",complete.status());
                assertEquals(grant.principalId(),complete.originalActor());
                assertEquals("owner",complete.workspaceOwner());
                when(authority.authorizePublication("alice")).thenReturn(
                        new WorkspaceFetchAuthority.Grant("d".repeat(64),"LEGACY_OIDC","d".repeat(64)));
                assertThrows(AccessDeniedException.class,()->fixture.service.publishWithReceipt(
                        "alice","main","legacy-unbound",null));
                assertEquals("controlled-worker",complete.lastExecutor());
                if (pull) {
                    assertEquals(prior,fixture.source.getHeadCommit("draft"));
                    assertTrue(fixture.local.getDslAtHead("main").contains("Source for contributor"));
                    assertNotEquals(priorLocal,fixture.local.getHeadCommit("main"));
                } else {
                    assertEquals(fixture.local.getHeadCommit("main"),fixture.source.getHeadCommit("draft"));
                    assertNotEquals(prior,fixture.source.getHeadCommit("draft"));
                }
            } else {
                assertThrows(AccessDeniedException.class,()->journal.step(claim,fixture.service::applyPublicationPhase));
                assertThrows(AccessDeniedException.class,()->fixture.service.publicationStatus(selected,pending.operationId()));
                assertEquals(prior,fixture.source.getHeadCommit("draft"));
            }
            try (var session=database.openSession()) {
                var row=session.find(WorkspacePublicationOperation.class,pending.operationId());
                assertEquals("owner",row.workspaceOwner());
                assertEquals("alice",row.username);
                assertEquals(grant.principalId(),row.actor);
            }
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "ADMITTED,202", "DENIED,403", "MANUAL_REVIEW,409", "COMPLETED,200"
    })
    void receiptRoutesReturnTheDecisionOfTheSelectedWorkingCopy(String scenario, int expectedStatus) throws Exception {
        var selected = RepositoryContext.workspace("private-source", "old-copy", "main", "alice");
        var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(), "LOCAL", "a".repeat(64));
        var authority = mock(WorkspacePublicationAuthority.class);
        when(authority.authorizePublication("alice")).thenReturn(grant);
        when(authority.currentActor("alice")).thenReturn(grant.principalId());

        try (var fixture = new Fixture();
             var factory = publicationDatabase("jdbc:hsqldb:mem:receipt-" + java.util.UUID.randomUUID(), "create")) {
            var journal = publicationJournal(factory);
            var recovery = new WorkspacePublicationRecovery(journal, authority);
            fixture.service.configurePublications(recovery);
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(selected);
            var http = MockMvcBuilders.standaloneSetup(new WorkspacePublicationController(resolver, fixture.service)).build();
            String reviewed = fixture.local.commitDsl("main", dsl("Reviewed receipt"), "alice", "Review");
            String original = fixture.source.getHeadCommit("draft");
            var admitted = journal.admit(selected, grant, false, "http-receipt", null,
                    () -> new WorkspacePublicationJournal.Source("draft", original, original, reviewed,
                            fixture.local.getHeadCommit("sync-base")));
            String id = admitted.operationId();
            switch (scenario) {
                case "ADMITTED" -> assertNotNull(journal.claim(id, "another-worker", false));
                case "DENIED" -> {
                    doThrow(new AccessDeniedException("grant revoked")).when(authority)
                            .checkPublicationGrant(grant, "alice", false);
                }
                case "MANUAL_REVIEW" -> recovery.retry(selected, id, (row, authorize) -> {
                    throw new WorkspacePublicationRecovery.SemanticConflict("conflicting architecture");
                });
                case "COMPLETED" -> { }
                default -> throw new AssertionError(scenario);
            }

            var response = http.perform(post("/api/workspace/publications/{id}/retry", id)).andReturn().getResponse();
            assertEquals(expectedStatus, response.getStatus(), response.getContentAsString());
            assertEquals(id, response.getHeader("X-Operation-Id"));
            String expectedPhase = scenario.equals("ADMITTED") ? "RUNNING" : scenario;
            var persisted = journal.get(selected, grant.principalId(), id);
            assertEquals(expectedPhase, persisted.status());
            assertEquals(grant.principalId(), persisted.originalActor());
            http.perform(get("/api/workspace/publications/{id}", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.operationId").value(id)).andExpect(jsonPath("$.status").value(expectedPhase));

            var neighbor = RepositoryContext.workspace("private-source", "old-copy", "neighbor", "alice");
            when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(neighbor);
            http.perform(get("/api/workspace/publications/{id}", id)).andExpect(status().isNotFound());
            http.perform(post("/api/workspace/publications/{id}/retry", id)).andExpect(status().isNotFound());
            http.perform(delete("/api/workspace/publications/{id}", id)).andExpect(status().isNotFound());

            when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(selected);
            if (persisted.completedAt() == null) {
                http.perform(delete("/api/workspace/publications/{id}", id)).andExpect(status().isConflict());
            } else {
                http.perform(delete("/api/workspace/publications/{id}", id)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("ARCHIVED"));
                http.perform(post("/api/workspace/publications/{id}/retry", id)).andExpect(status().isGone());
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "disconnected,RETRYABLE,PUBLICATION_INTERRUPTED",
            "reauthenticate,AWAITING_AUTHORIZATION,FRESH_LOGIN_REQUIRED",
            "descendant,SUPERSEDED,RETAINED_DESCENDANT",
            "reconciliation,MANUAL_REVIEW,RECONCILIATION_REQUIRED",
            "changed,MANUAL_REVIEW,AUTHORITY_CHANGED"
    })
    void recoveryPersistsTheDecisionAtTheInterruptedPhase(String cause, String status, String decision) throws Exception {
        var selected = RepositoryContext.workspace("repository", "copy", "draft", "alice");
        var grant = new WorkspaceFetchAuthority.Grant(java.util.UUID.randomUUID().toString(), "LOCAL", "a".repeat(64));
        var authority = mock(WorkspacePublicationAuthority.class);
        when(authority.authorizePublication("alice")).thenReturn(grant);
        try (var factory = publicationDatabase("jdbc:hsqldb:mem:decisions-" + java.util.UUID.randomUUID(), "create")) {
            var journal = publicationJournal(factory);
            var recovery = new WorkspacePublicationRecovery(journal, authority);
            var interrupted = recovery.submit(selected, false, "decision", null,
                    () -> new WorkspacePublicationJournal.Source("main", null, null, null, null), (row, authorize) -> {
                        authorize.run();
                        switch (cause) {
                            case "disconnected" -> throw new IOException("storage interrupted");
                            case "reauthenticate" -> throw new WorkspaceFetchAuthority.FreshLoginRequired();
                            case "descendant" -> throw new WorkspacePublicationRecovery.Superseded(true);
                            case "reconciliation" -> throw new com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem(
                                    "CONFLICT", "element", "manual reconciliation", java.util.List.of());
                            case "changed" -> throw new BranchHeadConflictException("main", null, "a".repeat(40), "head moved");
                            default -> throw new AssertionError(cause);
                        }
                    });
            var retained = publicationJournal(factory).get(selected, grant.principalId(), interrupted.operationId());
            assertEquals(status, retained.status());
            assertEquals(decision, retained.decisionCode());
            assertEquals(grant.principalId(), retained.originalActor());
            assertEquals(1, retained.attempts());
            if (retained.completedAt() == null) {
                var finished = recovery.retry(selected, retained.operationId(), (row, authorize) -> authorize.run());
                assertEquals("COMPLETED", finished.status());
                assertEquals(2, finished.attempts());
            } else {
                assertEquals(retained, recovery.retry(selected, retained.operationId(),
                        (row, authorize) -> fail("A terminal receipt must not repeat a phase")));
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
        Fixture() throws Exception { this(false); }

        Fixture(boolean sharedWithContributor) throws Exception {
            when(portfolio.lockedPublicationDigest(anyString(),any())).thenReturn("b".repeat(64));
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
            WorkspaceContextResolver contexts;
            if (sharedWithContributor) {
                workspace.setUsername("owner");
                workspace.setCollaborative(true);
                var manager = mock(WorkspaceManager.class);
                when(manager.getWorkspaceById("old-copy")).thenReturn(workspace);
                contexts = new WorkspaceContextResolver(manager,catalog,rows,memberships);
                contexts.setActorAccess(name -> assertEquals("alice",name));
                assertTrue(contexts.resolveRepositoryContextForUser("alice").collaborative());
            } else {
                contexts = mock(WorkspaceContextResolver.class);
                when(contexts.resolveRepositoryContextForUser("alice")).thenReturn(RepositoryContext.workspace("private-source", "old-copy", "main", "alice"));
            }
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
