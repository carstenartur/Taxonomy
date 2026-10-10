package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import com.taxonomy.backup.snapshot.GuardedBackupDataSource;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/** Full application composition, including identities, templates, preferences and every ORM module. */
@SpringBootTest(properties = {"taxonomy.backup.enabled=true", "embedding.enabled=false",
        "spring.datasource.url=jdbc:hsqldb:mem:backup-writer-coverage;hsqldb.tx=mvcc", "spring.jpa.hibernate.ddl-auto=update"})
@DirtiesContext
class BackupWriterCoverageIT {
    @Autowired ApplicationContext context;
    @Autowired DataSource database;
    @Autowired EntityManagerFactory entities;
    @Autowired BackupMaintenanceLease barrier;
    @Autowired PreferencesGitRepository preferences;

    @Test void everyManagedPersistenceFactoryUsesTheSameGuardAndRealGitWritesAreFenced() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(database).isInstanceOf(GuardedBackupDataSource.class);
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).hasSize(1);
        assertThat(context.getBeansOfType(HibernateRepositoryFactory.class)).hasSize(1);
        assertThat(entities.unwrap(SessionFactoryImplementor.class).getServiceRegistry()
                .getService(ConnectionProvider.class).unwrap(DataSource.class)).isSameAs(database);
        assertThat(preferences.isDatabaseBacked()).isTrue();
        assertThat(context.getBeansOfType(org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider.class)
                .values().stream().map(provider -> provider.getDataSourcePoolMetadata(database)).filter(java.util.Objects::nonNull))
                .as("pool monitoring must survive the writer guard").isNotEmpty();
        String previous = preferences.readHead();
        try (var capture = barrier.acquire(new BackupScope.Installation(), Duration.ofSeconds(10))) {
            assertThatThrownBy(() -> preferences.commit("{\"backupFence\":true}", "test", "Blocked preferences change"))
                    .hasStackTraceContaining("maintenance");
            try (var em = entities.createEntityManager()) {
                em.getTransaction().begin();
                try {
                    assertThatThrownBy(() -> em.createNativeQuery("update app_user set enabled=enabled").executeUpdate())
                            .hasStackTraceContaining("maintenance");
                } finally { em.getTransaction().rollback(); }
            }
            assertThat(preferences.readHead()).isEqualTo(previous);
            capture.checkValid();
        }
    }
}
