package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.backup.snapshot.BackupBarrierConfiguration;
import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import com.taxonomy.backup.snapshot.GuardedBackupDataSource;
import com.taxonomy.composition.persistence.TaxonomySchemaMigrationConfig;
import com.taxonomy.workspace.storage.JgitStorageSchemaMigrationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class BackupBarrierStartupIT {
    @Test void realBootMigrationsAreHeldUntilApplicationRunnersFinishThenRuntimeDdlIsRejected() {
        try (var context = new SpringApplicationBuilder(Persistence.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run("--spring.profiles.active=hsqldb", "--taxonomy.backup.enabled=true",
                        "--spring.datasource.url=jdbc:hsqldb:mem:backup-boot-" + UUID.randomUUID(),
                        "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.enabled=false")) {
            var database = context.getBean(DataSource.class);
            assertThat(database).isInstanceOf(GuardedBackupDataSource.class);
            var jdbc = new JdbcTemplate(database);
            assertThat(jdbc.queryForObject("select count(*) from boot_evidence", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select phase from backup_barrier_state", Integer.class)).isZero();
            jdbc.update("insert into boot_evidence values(2)");
            assertThatThrownBy(() -> jdbc.execute("create table runtime_ddl(id integer)"))
                    .hasMessageContaining("startup maintenance");
            try (var capture = context.getBean(BackupMaintenanceLease.class)
                    .acquire(new BackupScope.Installation(), Duration.ofSeconds(2))) { capture.checkValid(); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.taxonomy.security.model")
    @Import({JgitStorageSchemaMigrationConfig.class, TaxonomySchemaMigrationConfig.class, BackupBarrierConfiguration.class})
    static class Persistence {
        @Bean ApplicationRunner migrate(DataSource database, BackupWriteBarrier writers) {
            return args -> {
                var jdbc = new JdbcTemplate(database);
                assertThat(jdbc.queryForObject("select phase from backup_barrier_state", Integer.class)).isEqualTo(3);
                try (var write = writers.enter(new BackupScope.Installation())) {
                    write.checkValid();
                    jdbc.execute("create table boot_evidence(id integer primary key)");
                    jdbc.update("insert into boot_evidence values(1)");
                }
            };
        }
    }
}
