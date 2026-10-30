package com.taxonomy.composition.architecture;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Separate provisioner instances share only the real database claim and logical Git storage. */
@SpringBootTest
class ArchitectureLifecycleCreationRecoveryTest {
    private static final String PATH = "/api/architecture/lifecycle/architectures";
    private static final String USER = "creation-recovery-owner";
    @Autowired SystemRepositoryService catalog;
    @Autowired SystemRepositoryRepository rows;
    @Autowired RepositoryMembershipService membership;
    @Autowired RepositoryMembershipRepository memberships;
    @Autowired UserWorkspaceRepository workspaces;
    @Autowired DslGitRepositoryFactory git;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void failedInitialGitWriteRetriesTheSameOwnedCreationWithoutAllocatingAnotherIdentity() throws Exception {
        var request = request("first-write");
        var failing = mock(DslGitRepositoryFactory.class, delegatesTo(git));
        var failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            DslGitRepository actual = git.createCentralRepository(call.getArgument(0), call.getArgument(1));
            if (!failOnce.getAndSet(false)) return actual;
            var broken = mock(DslGitRepository.class, delegatesTo(actual));
            doThrow(new IOException("Initial ref update failed")).when(broken).commitDsl(anyString(), anyString(), anyString(), anyString());
            doThrow(new IOException("Initial ref update failed")).when(broken).commitDslIfHeadMatches(anyString(), isNull(), anyString(), anyString(), anyString());
            return broken;
        }).when(failing).createCentralRepository(anyString(), anyString());
        var controller = controller(catalog, failing, USER);

        assertThatThrownBy(() -> controller.create(request)).isInstanceOf(IllegalStateException.class);
        var failed = rows.findBySlug(request.slug()).orElseThrow();
        assertThat(failed.getLifecycleState()).isEqualTo(RepositoryLifecycleState.FAILED);
        String originalId = failed.getRepositoryId();
        var resumed = assertDoesNotThrow(() -> controller.create(request));
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().repositoryId()).isEqualTo(originalId);
        assertThat(rows.findBySlug(request.slug()).orElseThrow().getLifecycleState()).isEqualTo(RepositoryLifecycleState.ACTIVE);
        assertThat(rows.findAll().stream().filter(row -> request.slug().equals(row.getSlug()))).hasSize(1);
        assertThat(memberships.findByRepositoryIdOrderByUsernameAsc(originalId)).singleElement()
                .satisfies(owner -> assertThat(owner.getUsername()).isEqualTo(USER));
        String retainedCommit = git.getCentralRepository(originalId).getHeadCommit("draft");
        assertThat(retainedCommit).isNotNull();
        assertThat(controller.create(request).getBody().repositoryId()).isEqualTo(originalId);
        assertThat(git.getCentralRepository(originalId).getHeadCommit("draft")).isEqualTo(retainedCommit);
        assertThat(git.getCentralRepository(originalId).getCommitCount("draft")).isEqualTo(1);
    }

    @Test
    void failedCompletionPreservesThePublishedInitialCommitForTheSameCreationRetry() throws Exception {
        var request = request("completion");
        var interrupted = mock(SystemRepositoryService.class, delegatesTo(catalog));
        var failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            if (failOnce.getAndSet(false)) throw new IllegalStateException("Completion response interrupted");
            return catalog.markProvisioningReady(call.getArgument(0));
        }).when(interrupted).markProvisioningReady(anyString());
        var controller = controller(interrupted, git, USER);
        assertThatThrownBy(() -> controller.create(request)).isInstanceOf(IllegalStateException.class);
        var failed = rows.findBySlug(request.slug()).orElseThrow();
        assertThat(failed.getLifecycleState()).isEqualTo(RepositoryLifecycleState.FAILED);
        String initialCommit = git.getCentralRepository(failed.getRepositoryId()).getHeadCommit("draft");
        assertThat(initialCommit).as("The committed initial source must survive a later catalogue failure").isNotNull();
        var resumed = controller.create(request);
        assertThat(resumed.getBody().repositoryId()).isEqualTo(failed.getRepositoryId());
        assertThat(git.getCentralRepository(failed.getRepositoryId()).getHeadCommit("draft")).isEqualTo(initialCommit);
        assertThat(git.getCentralRepository(failed.getRepositoryId()).getCommitCount("draft")).isEqualTo(1);
    }

    @Test
    void concurrentRetriesAcrossProvisionersClaimTheFailedCreationOnlyOnce() throws Exception {
        var request = request("concurrent");
        var failed = failed(request, USER);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var allocations = new AtomicInteger();
        var gated = mock(DslGitRepositoryFactory.class, delegatesTo(git));
        doAnswer(call -> {
            allocations.incrementAndGet();
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Provisioning test release missing");
            return git.createCentralRepository(call.getArgument(0), call.getArgument(1));
        }).when(gated).createCentralRepository(eq(failed.getRepositoryId()), eq(failed.getStorageRepositoryName()));
        var first = controller(catalog, gated, USER);
        var independentCatalog = new ProxyFactory(new SystemRepositoryService(rows));
        independentCatalog.setProxyTargetClass(true);
        independentCatalog.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var second = controller((SystemRepositoryService) independentCatalog.getProxy(), gated, USER);
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        var completed = new ExecutorCompletionService<Object>(workers);
        var start = new CountDownLatch(1);
        try {
            completed.submit(() -> { start.await(); return createOutcome(first, request); });
            completed.submit(() -> { start.await(); return createOutcome(second, request); });
            start.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("A failed creation must be reclaimable").isTrue();
            var rejected = completed.poll(5, TimeUnit.SECONDS);
            assertThat(rejected).as("The other instance must reject the in-progress claim before allocating Git storage").isNotNull();
            assertThat(rejected.get()).isInstanceOf(Throwable.class);
            assertConflict((Throwable) rejected.get());
            assertThat(rows.findBySlug(request.slug()).orElseThrow().getLifecycleState()).isEqualTo(RepositoryLifecycleState.PROVISIONING);
            release.countDown();
            var ready = completed.poll(10, TimeUnit.SECONDS);
            assertThat(ready).isNotNull();
            assertThat(ready.get()).isEqualTo(failed.getRepositoryId());
            assertThat(allocations).hasValue(1);
            assertThat(second.create(request).getBody().repositoryId()).isEqualTo(failed.getRepositoryId());
            assertThat(allocations).hasValue(1);
            assertThat(git.getCentralRepository(failed.getRepositoryId()).getCommitCount("draft")).isEqualTo(1);
            assertThat(memberships.findByRepositoryIdOrderByUsernameAsc(failed.getRepositoryId())).hasSize(1);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-owner", "organization-owner", "archived", "deleting", "fork", "public", "changed-name", "changed-description"})
    void recoveryRequiresTheExactOwnedIndependentCreationAndNeverReactivatesUnavailableMetadata(String difference) {
        var request = request(difference);
        var failed = failed(request, USER);
        switch (difference) {
            case "foreign-owner" -> failed.setOwnerId("another-private-owner");
            case "organization-owner" -> failed.setOwnerType(RepositoryOwnerType.ORGANIZATION);
            case "archived" -> failed.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
            case "deleting" -> failed.setLifecycleState(RepositoryLifecycleState.DELETING);
            case "fork" -> failed.setUpstreamRepositoryId("retained-fork-source");
            case "public" -> failed.setVisibility(RepositoryVisibility.PUBLIC);
            case "changed-name" -> failed.setDisplayName("Another reserved name");
            case "changed-description" -> failed.setDescription("Another reserved description");
            default -> throw new IllegalArgumentException(difference);
        }
        catalog.save(failed);
        var before = rows.findBySlug(request.slug()).orElseThrow();
        var untouchedGit = mock(DslGitRepositoryFactory.class);
        assertConflict(catchThrowable(() -> controller(catalog, untouchedGit, USER).create(request)));
        var after = rows.findBySlug(request.slug()).orElseThrow();
        assertThat(after.getRepositoryId()).isEqualTo(before.getRepositoryId());
        assertThat(after.getLifecycleState()).isEqualTo(before.getLifecycleState());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(memberships.findByRepositoryIdOrderByUsernameAsc(failed.getRepositoryId())).isEmpty();
        verifyNoInteractions(untouchedGit);
    }

    @Test
    void existingUnrelatedGitSourceSurvivesARejectedRecovery() throws Exception {
        var request = request("retained-source");
        var failed = failed(request, USER);
        var stored = git.createCentralRepository(failed.getRepositoryId(), failed.getStorageRepositoryName());
        String source = "element retained type Component {\n title: \"Retained authored decision\";\n}\n";
        String head = stored.commitDsl("draft", source, USER, "An existing authored architecture");
        var guarded = mock(DslGitRepositoryFactory.class, delegatesTo(git));
        assertConflict(catchThrowable(() -> controller(catalog, guarded, USER).create(request)));
        assertThat(git.getCentralRepository(failed.getRepositoryId()).getHeadCommit("draft")).isEqualTo(head);
        assertThat(git.getCentralRepository(failed.getRepositoryId()).getDslAtHead("draft")).isEqualTo(source);
        assertThat(rows.findBySlug(request.slug()).orElseThrow().getLifecycleState()).isEqualTo(RepositoryLifecycleState.FAILED);
        verify(guarded, never()).deleteCentralRepository(anyString());
    }

    @Test
    void onlyOwnFailedOrPendingIndependentCreationsAreReadableForRecoveryAfterReload() throws Exception {
        var own = failed(request("visible"), USER);
        var pendingRequest = request("pending-visible");
        var pending = catalog.createCentralRepository(pendingRequest.displayName(), pendingRequest.slug(), pendingRequest.description(),
                RepositoryVisibility.PRIVATE, USER, "draft");
        var foreign = failed(request("private-hidden"), "another-private-owner");
        var organization = failed(request("organization-hidden"), USER);
        organization.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        catalog.save(organization);
        var archived = failed(request("archive-hidden"), USER);
        archived.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
        catalog.save(archived);
        var mvc = standaloneSetup(controller(catalog, git, USER)).build();
        mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[*].slug", hasItem(own.getSlug())))
                .andExpect(jsonPath("$[?(@.slug == '" + own.getSlug() + "')].lifecycleState", hasItem("FAILED")))
                .andExpect(jsonPath("$[?(@.slug == '" + own.getSlug() + "')].description", hasItem(own.getDescription())))
                .andExpect(jsonPath("$[?(@.slug == '" + pending.getSlug() + "')].lifecycleState", hasItem("PROVISIONING")))
                .andExpect(jsonPath("$[?(@.slug == '" + foreign.getSlug() + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.slug == '" + organization.getSlug() + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.slug == '" + archived.getSlug() + "')]").isEmpty());
    }

    @Test
    void descriptionLimitMatchesThePersistedColumnBeforeAnyCreation() {
        assertThatCode(() -> new ArchitectureLifecycleCreationController.Create("Architecture", "bounded-description", "a".repeat(1000)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ArchitectureLifecycleCreationController.Create("Architecture", "bounded-description", "a".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ArchitectureLifecycleCreationController controller(SystemRepositoryService selectedCatalog, DslGitRepositoryFactory selectedGit, String user) {
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn(user);
        return new ArchitectureLifecycleCreationController(selectedCatalog,
                new ArchitectureRepositoryProvisioningService(selectedCatalog, membership, selectedGit), membership, resolver, workspaces);
    }

    private SystemRepository failed(ArchitectureLifecycleCreationController.Create request, String owner) {
        var reserved = catalog.createCentralRepository(request.displayName(), request.slug(), request.description(), RepositoryVisibility.PRIVATE, owner, "draft");
        return catalog.markProvisioningFailed(reserved.getRepositoryId(), "Initial storage operation interrupted");
    }

    private static ArchitectureLifecycleCreationController.Create request(String purpose) {
        return new ArchitectureLifecycleCreationController.Create("Independent architecture", "creation-" + purpose + "-" + UUID.randomUUID(), "Retained creation description");
    }

    private static void assertConflict(Throwable problem) {
        assertThat(problem).isInstanceOfSatisfying(ResponseStatusException.class,
                conflict -> assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static Object createOutcome(ArchitectureLifecycleCreationController controller, ArchitectureLifecycleCreationController.Create request) {
        try { return controller.create(request).getBody().repositoryId(); }
        catch (RuntimeException failure) { return failure; }
    }
}
