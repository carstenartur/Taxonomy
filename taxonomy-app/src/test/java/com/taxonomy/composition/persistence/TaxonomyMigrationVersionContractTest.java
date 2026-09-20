package com.taxonomy.composition.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast companion to the real PostgreSQL migration and schema-validation tests. */
class TaxonomyMigrationVersionContractTest {

    @Test
    void applicationMigrationVersionsAreUniqueOnTheActualClasspath() throws Exception {
        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources(
                "classpath*:db/migration/taxonomy/postgresql/V*__*.sql");
        assertThat(migrations).as("application migration resources").isNotEmpty();
        Map<MigrationVersion, String> resourcesByVersion = new LinkedHashMap<>();
        for (Resource migration : migrations) {
            String filename = migration.getFilename();
            assertThat(filename).isNotNull().contains("__");
            MigrationVersion version = MigrationVersion.fromVersion(
                    filename.substring(1, filename.indexOf("__")));
            String previous = resourcesByVersion.putIfAbsent(version, filename);
            assertThat(previous)
                    .as("duplicate Flyway version %s: %s and %s", version, previous, filename)
                    .isNull();
        }
    }
}
