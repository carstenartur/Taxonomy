package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real repository queries under opposite database defaults for NULL placement. */
class WorkspaceNullOrderingTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void accessedWorkspacePrecedesNeverAccessedRegardlessOfDatabaseDefault(boolean nullsFirst) {
        var configuration = new Configuration().addAnnotatedClass(UserWorkspace.class);
        configuration.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        configuration.setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:selection-" + UUID.randomUUID()
                + ";sql.nulls_first=" + nullsFirst + ";sql.nulls_order=true;shutdown=true");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.search.enabled", "false");
        try (var factory = configuration.buildSessionFactory(); var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            var never = row("a-never", null, false);
            var recent = row("z-recent", Instant.parse("2026-01-02T00:00:00Z"), false);
            session.persist(never);
            session.persist(recent);
            session.persist(row("older", Instant.parse("2026-01-01T00:00:00Z"), false));
            session.flush();
            var repository = new JpaRepositoryFactory(session).getRepository(UserWorkspaceRepository.class);
            assertThat(repository.findByUsernameAndSharedFalse("alice").orElseThrow().getWorkspaceId())
                    .isEqualTo("z-recent");

            // A designated default retains priority even if it has never been accessed.
            never.setDefault(true);
            session.flush();
            assertThat(repository.findByUsernameAndSharedFalse("alice").orElseThrow().getWorkspaceId())
                    .isEqualTo("a-never");
            never.setDefault(false);
            recent.setLastAccessedAt(null);
            for (var row : session.createQuery("from UserWorkspace", UserWorkspace.class).getResultList()) {
                row.setLastAccessedAt(null);
            }
            session.flush();
            // All-null histories are valid and use the documented stable ID tie-breaker.
            assertThat(repository.findByUsernameAndSharedFalse("alice").orElseThrow().getWorkspaceId())
                    .isEqualTo("a-never");
            assertThat(repository.findByUsernameAndSharedFalse("another-owner")).isEmpty();
            transaction.rollback();
        }
    }

    private static UserWorkspace row(String id, Instant accessed, boolean primary) {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId(id);
        workspace.setUsername("alice");
        workspace.setDisplayName(id);
        workspace.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        workspace.setLastAccessedAt(accessed);
        workspace.setDefault(primary);
        return workspace;
    }
}
