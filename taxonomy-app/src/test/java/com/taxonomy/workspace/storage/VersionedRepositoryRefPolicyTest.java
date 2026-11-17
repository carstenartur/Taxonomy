package com.taxonomy.workspace.storage;

import com.taxonomy.composition.architecture.RepositoryGrantIdentity;
import com.taxonomy.composition.architecture.SharedWorkspaceActorAccess;
import com.taxonomy.security.access.WorkspaceFetchAuthorityAdapter;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.security.service.VersionedRepositoryRefPolicy;
import com.taxonomy.security.controller.RepositoryRefPolicyController;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.repository.WorkspaceProvisioningFenceImpl;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryRefPolicy;
import com.taxonomy.workspace.service.WorkspaceActorAccess;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The real policy, registry, Spring transaction manager and secured Hibernate/JGit publication. */
class VersionedRepositoryRefPolicyTest {
    private SessionFactory database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private PrincipalIdentityService identities;
    private RepositoryMembershipService memberships;
    private SystemRepositoryRepository repositories;
    private UserWorkspaceRepository workspaces;
    private VersionedRepositoryRefPolicy policies;
    private DslGitRepositoryFactory git;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:hsqldb:mem:ref-policy-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.execute("create table app_role(id bigint primary key, name varchar(255))");
        jdbc.execute("create table user_roles(user_id bigint,role_id bigint)");
        jdbc.update("insert into app_user values (1,'owner',true),(2,'contributor',true),(3,'administrator',true)");
        jdbc.update("insert into app_role values (1,'ROLE_ARCHITECT'),(2,'ROLE_ADMIN')");
        jdbc.update("insert into user_roles values (1,1),(2,1),(3,2)");
        PrincipalSchemaMigration.migrate(source);
        identities = new PrincipalIdentityService(source);
        for (long id = 1; id <= 3; id++) identities.local(id);
        var config = new Configuration().addAnnotatedClass(SystemRepository.class).addAnnotatedClass(UserWorkspace.class)
                .addAnnotatedClass(RepositoryMembership.class).addAnnotatedClass(RepositoryRefRule.class)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.search.enabled", "false");
        CoreEntities.annotatedClasses().forEach(config::addAnnotatedClass);
        config.getProperties().put("hibernate.connection.datasource", source);
        database = config.buildSessionFactory();
        var manager = new JpaTransactionManager(database);
        manager.setDataSource(source);
        transaction = new TransactionTemplate(manager);
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(database);
        var factory = new JpaRepositoryFactory(entityManager);
        repositories = factory.getRepository(SystemRepositoryRepository.class);
        workspaces = factory.getRepository(UserWorkspaceRepository.class, RepositoryFragments.just(new WorkspaceProvisioningFenceImpl(entityManager)));
        var authority = new WorkspaceFetchAuthorityAdapter(identities);
        var identity = new RepositoryGrantIdentity(identities, Optional.empty(), authority, jdbc);
        memberships = new RepositoryMembershipService(factory.getRepository(RepositoryMembershipRepository.class),
                Optional.empty(), Optional.of(identity));
        try (var wiring = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            wiring.registerBean(WorkspaceActorAccess.class, () -> new SharedWorkspaceActorAccess(identities));
            wiring.refresh();
            wiring.getAutowireCapableBeanFactory().autowireBean(memberships);
        }
        policies = new VersionedRepositoryRefPolicy(entityManager, repositories, workspaces, memberships, identities, jdbc);
        var proxy = new org.springframework.aop.framework.ProxyFactory(policies);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        policies = (VersionedRepositoryRefPolicy) proxy.getProxy();
        git = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(database), null,
                GitCommitSignatureService.disabled(), database, policies);
        login(1);
        transaction.executeWithoutResult(status -> {
            for (String id : List.of("project", "neighbor")) {
                var repository = new SystemRepository();
                repository.setRepositoryId(id);
                repository.setStorageRepositoryName("central-" + id);
                repository.setOwnerId("owner");
                repository.setCreatedAt(Instant.now());
                repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
                repositories.saveAndFlush(repository);
                memberships.assignOwner(id, "owner");
            }
            memberships.assignRole(repositories.findByRepositoryId("project").orElseThrow(), "contributor",
                    RepositoryRole.CONTRIBUTOR, "owner", -1L);
            for (String workspaceId : List.of("__shared__", "other")) {
                var workspace = new UserWorkspace();
                workspace.setWorkspaceId(workspaceId);
                workspace.setUsername("owner");
                workspace.assignOwnerPrincipal(identities.local(1).id().value());
                workspace.setSourceRepositoryId("project");
                workspace.setSourceBranch("main");
                workspace.setCreatedAt(Instant.now());
                workspaces.saveAndFlush(workspace);
            }
        });
        jdbc.update("delete from principal_access_audit");
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        if (git != null) git.close();
        if (database != null) database.close();
    }

    @Test
    void aRetainedNativeHandleHonorsCurrentNormalForceAndDeleteRulesWithoutLeakingAcrossStorage() throws Exception {
        var selected = git.getCentralRepository("project");
        String base = selected.commitDsl("reviewed", "# first", "owner", "first");
        String latest = selected.commitDsl("reviewed", "# second", "owner", "second");
        selected.createBranch("neighbor", "reviewed");
        var other = git.getCentralRepository("neighbor");
        other.commitDsl("reviewed", "# unrelated", "owner", "unrelated");
        var privateCopy = git.openWorkspaceRepository("__shared__");
        privateCopy.commitDsl("reviewed", "# private", "owner", "private");
        var first = change(null, "reviewed", 0, false, true, true, false);
        assertThat(first.policy().revision()).isOne();
        String allowed = selected.commitDsl("reviewed", "# third", "owner", "third");
        assertThatThrownBy(() -> force(selected, "reviewed", allowed, base))
                .isInstanceOf(IOException.class).hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> selected.deleteBranch("reviewed", allowed, "owner")).isInstanceOf(AccessDeniedException.class);
        var frozen = change(null, "reviewed", 1, true, true, true, false);
        assertThat(frozen.policy().revision()).isEqualTo(2);
        assertThatThrownBy(() -> selected.commitDsl("reviewed", "# forbidden", "owner", "forbidden"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(selected.getHeadCommit("reviewed")).isEqualTo(allowed).isNotEqualTo(latest);
        assertThat(selected.commitDsl("neighbor", "# local neighbor", "owner", "neighbor")).isNotBlank();
        assertThat(other.commitDsl("reviewed", "# other central", "owner", "other")).isNotBlank();
        assertThat(privateCopy.commitDsl("reviewed", "# private survives", "owner", "private")).isNotBlank();
        assertThatThrownBy(() -> change("__shared__", "reviewed", 1, true, true, true, false))
                .isInstanceOf(ResponseStatusException.class);
        change("__shared__", "reviewed", 0, true, true, true, false);
        assertThatThrownBy(() -> privateCopy.commitDsl("reviewed", "# denied", "owner", "denied"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from repository_ref_rule", Integer.class)).isEqualTo(2);
    }

    @Test
    void caseDistinctBranchesRetainIndependentPoliciesAndNativePublicationRights() throws Exception {
        assertThat(RepositoryRefRule.class.getAnnotation(jakarta.persistence.Table.class).uniqueConstraints())
                .as("exact Git names must not inherit a database's case-insensitive text uniqueness").isEmpty();
        var selected = git.getCentralRepository("project");
        String base = selected.commitDsl("main", "# original", "owner", "original");
        selected.createBranch("Main", "main");
        var frozen = change(null, "main", 0, true, true, true, false);
        var permitted = change(null, "Main", 0, false, true, true, false);
        assertThat(frozen.policy().branch()).isEqualTo("main");
        assertThat(permitted.policy().branch()).isEqualTo("Main");
        assertThat(frozen.policy().revision()).isOne();
        assertThat(permitted.policy().revision()).isOne();
        assertThatThrownBy(() -> selected.commitDsl("main", "# denied", "owner", "denied"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(selected.commitDsl("Main", "# allowed", "owner", "allowed")).isNotEqualTo(base);
        assertThat(selected.getHeadCommit("main")).isEqualTo(base);
        assertThat(jdbc.queryForObject("select count(*) from repository_ref_rule", Integer.class)).isEqualTo(2);
    }

    @Test
    void authenticatedHttpRequiresTheReviewedRevisionAndReturnsTheCommittedPolicy() throws Exception {
        git.getCentralRepository("project");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new RepositoryRefPolicyController(policies)).build();
        var owner = SecurityContextHolder.getContext().getAuthentication();
        String endpoint = "/api/repositories/project/ref-policy";
        mvc.perform(get(endpoint).principal(owner).param("branch", "reviewed"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.revision").value(0));
        mvc.perform(put(endpoint).principal(owner).param("branch", "reviewed")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(endpoint).principal(owner).param("branch", "reviewed")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        String decision = """
                {"expectedRevision":0,"preventUpdates":true,"preventForce":true,"preventDelete":true,
                "rationale":"Reviewed branch"}
                """;
        mvc.perform(put(endpoint).principal(owner).param("branch", "../invalid")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isBadRequest());
        mvc.perform(put(endpoint).principal(owner).param("branch", "reviewed").param("workspaceId", "guessed")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isNotFound());
        mvc.perform(put(endpoint).principal(owner).param("branch", "reviewed").param("workspaceId", "")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policy.revision").value(1))
                .andExpect(jsonPath("$.policy.workspaceId").doesNotExist())
                .andExpect(jsonPath("$.policy.preventUpdates").value(true)).andExpect(jsonPath("$.auditId").isNotEmpty());
        mvc.perform(put(endpoint).principal(owner).param("branch", "reviewed")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isConflict());
        mvc.perform(get(endpoint).principal(owner).param("branch", "reviewed"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isOne();
    }

    @Test
    void policyAndStableAuditCommitTogetherAndFailureRestoresTheReviewedRevision() {
        git.getCentralRepository("project");
        var applied = change(null, "main", 0, true, true, true, false);
        var audit = jdbc.queryForMap("select actor_principal,detail from principal_access_audit where audit_id=?", applied.auditId().toString());
        assertThat(audit.get("actor_principal")).isEqualTo(identities.local(1).id().value().toString());
        assertThat(audit.get("detail").toString()).contains("Policy review", "main", "APPLIED");
        jdbc.execute("alter table principal_access_audit add constraint reject_ref_policy check (decision_kind <> 'CHANGE_REPOSITORY_REF_POLICY' or audit_id='"
                + applied.auditId() + "')");
        assertThatThrownBy(() -> change(null, "main", 1, false, false, false, false))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select revision from repository_ref_rule", Long.class)).isOne();
        assertThat(jdbc.queryForObject("select prevent_updates from repository_ref_rule", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isOne();
    }

    @Test
    void firstProtectionIsSerializedWithNativePublicationAcrossIndependentTransactions() throws Exception {
        var selected = git.getCentralRepository("project");
        String base = selected.commitDsl("main", "# base", "owner", "base");
        var policyWritten = new CountDownLatch(1);
        var commitPolicy = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var policy = pool.submit(() -> {
                login(1);
                try {
                    return transaction.execute(status -> {
                        var changed = policies.change(SecurityContextHolder.getContext().getAuthentication(), "project", null, "main",
                                0, true, true, true, false, "Policy review");
                        policyWritten.countDown();
                        await(commitPolicy);
                        return changed;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(policyWritten.await(10, TimeUnit.SECONDS)).isTrue();
            var writer = pool.submit(() -> selected.commitDslIfHeadMatches("main", base, "# raced", "owner", "raced"));
            assertThatThrownBy(() -> writer.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commitPolicy.countDown();
            assertThat(policy.get(10, TimeUnit.SECONDS).policy().revision()).isOne();
            assertThatThrownBy(() -> writer.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AccessDeniedException.class);
            assertThat(selected.getHeadCommit("main")).isEqualTo(base);
        } finally { commitPolicy.countDown(); }
    }

    @Test
    void simultaneousFirstChangesProduceOneAuditedRevisionAndOneConflict() throws Exception {
        git.getCentralRepository("project");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                login(1);
                await(start);
                try { change(null, "main", 0, true, index == 0, true, false); return 200; }
                catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
                finally { SecurityContextHolder.clearContext(); }
            })).toList();
            start.countDown();
            assertThat(List.of(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from repository_ref_rule", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isOne();
    }

    @Test
    void currentStableOwnerAndExplicitAdministratorAreRequiredAndRevocationInvalidatesTheOldLogin() {
        git.getCentralRepository("project");
        login(2);
        assertThatThrownBy(() -> change(null, "main", 0, true, true, true, false)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> change(null, "main", 0, true, true, true, true)).isInstanceOf(AccessDeniedException.class);
        login(3);
        assertThatThrownBy(() -> change(null, "main", 0, true, true, true, false)).isInstanceOf(RuntimeException.class);
        var applied = change(null, "main", 0, true, true, true, true);
        assertThat(applied.policy().revision()).isOne();
        jdbc.update("delete from user_roles where user_id=3");
        assertThatThrownBy(() -> change(null, "main", 1, false, false, false, true)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select revision from repository_ref_rule", Long.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isOne();
    }

    @Test
    void explicitAdministratorOverridePreservesPrivateWorkspaceAndArchivedRepositoryBoundaries() {
        git.getCentralRepository("project");
        git.openWorkspaceRepository("__shared__");
        login(3);
        assertThatThrownBy(() -> change("__shared__", "reviewed", 0, true, true, true, true))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        transaction.executeWithoutResult(status -> {
            var repository = repositories.findByRepositoryId("project").orElseThrow();
            repository.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
            repositories.saveAndFlush(repository);
        });
        assertThatThrownBy(() -> change(null, "reviewed", 0, true, true, true, true))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from repository_ref_rule", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }

    @Test
    void aRoleRevokedWhileThePolicyWaitsForPublicationCannotCommitAChange() throws Exception {
        git.getCentralRepository("project");
        var storageHeld = new CountDownLatch(1);
        var releaseStorage = new CountDownLatch(1);
        var policyStarted = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var currentPublication = pool.submit(() -> database.inTransaction(session -> {
                policies.require(session, "central-project", "main", RepositoryRefPolicy.Operation.UPDATE);
                storageHeld.countDown();
                await(releaseStorage);
            }));
            assertThat(storageHeld.await(10, TimeUnit.SECONDS)).isTrue();
            var policy = pool.submit(() -> {
                login(1);
                policyStarted.countDown();
                try { return change(null, "main", 0, true, true, true, false); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(policyStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> policy.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            jdbc.update("delete from user_roles where user_id=1");
            releaseStorage.countDown();
            currentPublication.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> policy.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AccessDeniedException.class);
        } finally { releaseStorage.countDown(); }
        assertThat(jdbc.queryForObject("select count(*) from repository_ref_rule", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }

    @Test
    void scalarNativeDecisionRechecksPolicyEvenAfterTheSessionHasLoadedAnOlderEntity() {
        git.getCentralRepository("project");
        change(null, "main", 0, false, false, false, false);
        try (var stale = database.openSession()) {
            var old = stale.find(RepositoryRefRule.class, RepositoryRefRule.key("central-project", "main"));
            assertThat(old.view().preventUpdates()).isFalse();
            change(null, "main", 1, true, true, true, false);
            stale.beginTransaction();
            assertThatThrownBy(() -> policies.require(stale, "central-project", "main", RepositoryRefPolicy.Operation.UPDATE))
                    .isInstanceOf(AccessDeniedException.class);
            stale.getTransaction().rollback();
            assertThat(old.view().preventUpdates()).isFalse();
        }
    }

    @Test
    void workspaceCleanupRetainsOtherScopesAndAnAppendOnlyStableDecision() {
        git.getCentralRepository("project");
        git.openWorkspaceRepository("__shared__");
        change(null, "reviewed", 0, true, true, true, false);
        change("__shared__", "reviewed", 0, true, true, true, false);
        change("__shared__", "second", 0, true, true, true, false);
        git.openWorkspaceRepository("other");
        change("other", "reviewed", 0, true, true, true, false);
        transaction.executeWithoutResult(status -> policies.removeWorkspace("project", "__shared__", "owner"));
        assertThat(jdbc.queryForObject("select count(*) from repository_ref_rule", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select workspace_id from repository_ref_rule", String.class)).containsExactlyInAnyOrder(null, "other");
        var actor = identities.local(1).id().value().toString();
        var decisions = jdbc.queryForList("select actor_principal,detail from principal_access_audit where decision_kind='REMOVE_WORKSPACE_REF_POLICY'");
        assertThat(decisions).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("actor_principal")).isEqualTo(actor);
            assertThat(row.get("detail").toString()).contains("__shared__", "OWNER_REMOVED_WORKSPACE", "\"after\":null");
        });
        SecurityContextHolder.clearContext();
        transaction.executeWithoutResult(status -> policies.removeWorkspace("project", "__shared__", "owner"));
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit where decision_kind='REMOVE_WORKSPACE_REF_POLICY'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void failedCleanupAuditRollsBackEveryRuleAndRetainsCurrentOwnerAuthorization() {
        git.openWorkspaceRepository("__shared__");
        change("__shared__", "reviewed", 0, true, true, true, false);
        change("__shared__", "second", 0, true, true, true, false);
        login(2);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> policies.removeWorkspace("project", "__shared__", "owner")))
                .isInstanceOf(AccessDeniedException.class);
        login(1);
        jdbc.execute("alter table principal_access_audit add constraint reject_policy_cleanup check (decision_kind <> 'REMOVE_WORKSPACE_REF_POLICY')");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> policies.removeWorkspace("project", "__shared__", "owner")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from repository_ref_rule", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit where decision_kind='REMOVE_WORKSPACE_REF_POLICY'", Integer.class))
                .isZero();
        jdbc.update("update app_user set enabled=false where id=1");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> policies.removeWorkspace("project", "__shared__", "owner")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private VersionedRepositoryRefPolicy.Change change(String workspace, String branch, long revision,
            boolean update, boolean force, boolean deletion, boolean override) {
        return transaction.execute(status -> policies.change(SecurityContextHolder.getContext().getAuthentication(),
                "project", workspace, branch, revision, update, force, deletion, override, "Policy review"));
    }

    private void login(long user) {
        var principal = identities.local(user);
        var authorities = List.of(new SimpleGrantedAuthority(user == 3 ? "ROLE_ADMIN" : "ROLE_ARCHITECT"));
        var local = new PrincipalUserDetails(principal.id(), principal.scopeKey(), "unused", true, authorities);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(local, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static void force(DslGitRepository git, String branch, String before, String after) throws IOException {
        var update = git.getGitRepository().updateRef(Constants.R_HEADS + branch);
        update.setExpectedOldObjectId(ObjectId.fromString(before));
        update.setNewObjectId(ObjectId.fromString(after));
        update.setForceUpdate(true);
        update.update();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
