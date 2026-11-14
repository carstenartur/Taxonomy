package com.taxonomy.security.service;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class RepositoryOwnershipTransferTest {
    private SessionFactory factory;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private RepositoryOwnershipTransfer transfer;
    private PrincipalIdentityService identities;
    private RepositoryMembershipService memberships;
    private SystemRepositoryRepository repositories;
    private long revision;

    @BeforeEach void setup() {
        var db = new DriverManagerDataSource("jdbc:hsqldb:mem:handoff-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.execute("create table app_role(id bigint primary key, name varchar(255))");
        jdbc.execute("create table user_roles(user_id bigint,role_id bigint)");
        jdbc.update("insert into app_user values (1,'owner',true),(2,'alice',true),(3,'bob',true)");
        PrincipalSchemaMigration.migrate(db);
        identities = new PrincipalIdentityService(db);
        for (int id = 1; id <= 3; id++) identities.local(id);
        var config = new Configuration().addAnnotatedClass(SystemRepository.class).addAnnotatedClass(RepositoryMembership.class)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.search.enabled", "false");
        config.getProperties().put("hibernate.connection.datasource", db);
        factory = config.buildSessionFactory();
        var manager = new JpaTransactionManager(factory);
        manager.setDataSource(db);
        transaction = new TransactionTemplate(manager);
        var repoFactory = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
        repositories = repoFactory.getRepository(SystemRepositoryRepository.class);
        memberships = new RepositoryMembershipService(repoFactory.getRepository(RepositoryMembershipRepository.class));
        transfer = new RepositoryOwnershipTransfer(identities, repositories, memberships, jdbc);
        revision = transaction.execute(s -> {
            var repo = new SystemRepository(); repo.setRepositoryId("project"); repo.setOwnerId("owner");
            repo.setCreatedAt(Instant.now()); repo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
            repositories.saveAndFlush(repo); memberships.assignOwner("project", "owner");
            return repo.getVersion();
        });
    }
    @AfterEach void close() { if (factory != null) factory.close(); }

    @Test void handsOffCatalogueAndMembershipsWithStableAuditInOneCommit() {
        var before = identities.local(1).id(); var after = identities.local(2).id();
        var receipt = move(auth(1), after, revision);
        assertThat(receipt.version()).isEqualTo(revision + 1);
        assertThat(jdbc.queryForObject("select owner_id from system_repository", String.class)).isEqualTo("alice");
        assertThat(jdbc.queryForObject("select count(*) from repository_membership where username='owner' and revoked_at is null",Integer.class)).isZero(); assertThat(role("alice")).isEqualTo("OWNER");
        var audit = jdbc.queryForMap("select * from principal_access_audit where audit_id=?", receipt.auditId().toString());
        assertThat(audit).containsEntry("ACTOR_PRINCIPAL", before.value().toString()).containsEntry("TARGET_PRINCIPAL", after.value().toString());
        assertThat(audit.get("DETAIL").toString()).contains("project","Team transition");
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> memberships.removeMembership(repositories.findByRepositoryId("project").orElseThrow(),"alice","alice",0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> move(auth(2), identities.local(3).id(), revision)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void aCoOwnerCannotTakeTheCatalogueAndAnArchivedOrDisabledTargetCannotReceiveIt() {
        transaction.executeWithoutResult(s -> memberships.assignRole("project","bob",RepositoryRole.OWNER,"owner"));
        assertThatThrownBy(() -> move(auth(3), identities.local(2).id(), revision)).isInstanceOf(ResponseStatusException.class);
        var target = identities.local(2).id();
        jdbc.update("update app_user set enabled=false where id=2");
        assertThatThrownBy(() -> move(auth(1),target,revision)).isInstanceOf(ResponseStatusException.class);
        jdbc.update("update app_user set enabled=true where id=2");
        jdbc.update("update system_repository set lifecycle_state='ARCHIVED'");
        assertThatThrownBy(() -> move(auth(1),target,revision)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }
    @Test void systemAndOrganizationCustodyRemainWithTheirOwnOwnerTypes() {
        var target = identities.local(2).id();
        var owner = auth(1);
        for (String type : List.of("SYSTEM", "ORGANIZATION")) {
            jdbc.update("update system_repository set owner_type=?", type);
            assertThatThrownBy(() -> move(owner, target, revision)).isInstanceOf(ResponseStatusException.class);
            assertThat(jdbc.queryForObject("select owner_type from system_repository", String.class)).isEqualTo(type);
        }
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",Integer.class)).isZero();
    }

    @Test void deniedOrStaleRequestsDoNotAlterDirectOrIndirectAuthority() {
        var owner = auth(1);
        var target = identities.local(2).id();
        assertThatThrownBy(() -> move(owner, target, revision + 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> move(owner, identities.local(1).id(), revision)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> transfer.transfer(owner, "project",target,revision," ")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> move(auth(3), target, revision)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",Integer.class)).isZero();
    }

    @Test void aRevokedOwnerSessionCannotPublishAndAnUnrelatedGrantSurvivesAHandoff() {
        var owner = auth(1);
        var successor = identities.local(2).id();
        jdbc.update("update app_user set enabled=false where id=1");
        assertThatThrownBy(() -> move(owner, successor, revision)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("update app_user set enabled=true where id=1");
        transaction.executeWithoutResult(s -> memberships.assignRole("project", "bob", RepositoryRole.OWNER, "owner"));
        move(owner, successor, revision);
        assertThat(role("bob")).isEqualTo("OWNER");
    }

    @Test void aRenamedLocalAccountCannotReceiveUnusableCatalogueOwnership() {
        var successor = identities.local(2);
        jdbc.update("update app_user set username='alice-renamed' where id=2");
        // The regular local login still returns the same UUID, with the account's current login name.
        var login = new PrincipalUserDetails(identities.local(2).id(), "alice-renamed", "unused", true, List.of());
        var acceptedLogin = UsernamePasswordAuthenticationToken.authenticated(login, null, List.of());
        assertThatThrownBy(() -> identities.requireCurrent(acceptedLogin)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> move(auth(1), successor.id(), revision)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select owner_id from system_repository",String.class)).isEqualTo("owner");
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",Integer.class)).isZero();
    }

    @Test void oneRemainingValidExternalLoginCanUseTheTransferredOwnership() {
        var successor = identities.local(2);
        jdbc.update("update app_user set username='alice-renamed' where id=2");
        String issuer = "https://issuer.example.test";
        for (String subject : List.of("old-subject", "current-subject")) {
            var binding = new com.taxonomy.backup.IdentityBinding(com.taxonomy.backup.IdentityBinding.Kind.OIDC,issuer,subject);
            jdbc.update("insert into principal_binding values (?, ?, 'OIDC', ?, ?, ?)",
                    com.taxonomy.security.model.PrincipalBinding.key(binding),successor.id().value().toString(),issuer,subject,
                    subject.equals("current-subject") ? 1 : 0);
        }
        assertThat(identities.oidc(issuer,"current-subject")).isEqualTo(successor);
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                .header("alg","test").issuer(issuer).subject("current-subject").build();
        var login = new com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken(token,List.of(),successor);
        assertThat(identities.requireCurrent(login)).isEqualTo(successor.id());
        var first = move(auth(1),successor.id(),revision);
        var next = move(login,identities.local(3).id(),first.version());
        assertThat(next.previousOwner()).isEqualTo(successor.id().value());
    }

    @Test void aFailedAuditRollsBackTheCatalogueAndBothMembershipChanges() {
        jdbc.execute("alter table principal_access_audit add constraint fail_transfer check(decision_kind <> 'TRANSFER_REPOSITORY_OWNER')");
        assertThatThrownBy(() -> move(auth(1),identities.local(2).id(),revision)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select owner_id from system_repository", String.class)).isEqualTo("owner");
        assertThat(role("owner")).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select count(*) from repository_membership where username='alice'",Integer.class)).isZero();
    }
    @Test void twoTransfersOfOneObservedRevisionCommitOneOwnerAndOneReceipt() throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var owner = auth(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = List.of(identities.local(2).id(),identities.local(3).id()).stream().map(target -> pool.submit(() -> {
                ready.countDown(); start.await(5,TimeUnit.SECONDS);
                try { move(owner,target,revision); return true; }
                catch (ResponseStatusException denied) { return false; }
            })).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(calls.get(0).get(10,TimeUnit.SECONDS),calls.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from repository_membership where repository_role='OWNER' and revoked_at is null",Integer.class)).isOne();
    }
    private RepositoryOwnershipTransfer.Receipt move(Authentication actor, PrincipalId target, long version) {
        return transaction.execute(s -> transfer.transfer(actor,"project",target,version,"Team transition"));
    }
    private String role(String name) { return jdbc.queryForObject("select repository_role from repository_membership where username=? and revoked_at is null",String.class,name); }
    private Authentication auth(long id) {
        var p = identities.local(id);
        var login = new PrincipalUserDetails(p.id(),p.scopeKey(),"unused",true,List.of());
        return UsernamePasswordAuthenticationToken.authenticated(login,null,List.of());
    }
}
