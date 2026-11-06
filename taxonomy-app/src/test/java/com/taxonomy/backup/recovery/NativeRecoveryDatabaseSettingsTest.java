package com.taxonomy.backup.recovery;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class NativeRecoveryDatabaseSettingsTest {
    @Test void existingHsqlFixtureRetainsItsColdFileContract() {
        var settings = NativeRecoveryDatabaseSettings.from(Map.of(), Path.of("private-data"), "seed");
        assertThat(settings.profiles()).isEqualTo("hsqldb-file");
        assertThat(settings.url()).startsWith("jdbc:hsqldb:file:private-data/taxonomydb;");
        assertThat(settings.schemaAction()).isEqualTo("update");
        assertThat(settings.expectedSchema()).isEqualTo("PUBLIC");
    }

    @Test void existingPostgresFixtureAlwaysUsesManagedMigrationsAndValidation() {
        for (String phase : new String[]{"seed", "restart", "undo", "redo"}) {
            var settings = NativeRecoveryDatabaseSettings.from(external("postgres", "jdbc:postgresql://localhost/source"), Path.of("data"), phase);
            assertThat(settings.profiles()).isEqualTo("postgres,kubernetes");
            assertThat(settings.schemaAction()).isEqualTo("validate");
            assertThat(settings.expectedSchema()).isEqualTo("PUBLIC");
        }
    }

    @Test void onlyFreshOracleSeedMayCreateAndEverySubsequentStartMustValidate() {
        var environment = external("oracle", "jdbc:oracle:thin:@localhost:1521/FREEPDB1");
        assertThat(NativeRecoveryDatabaseSettings.from(environment, Path.of("data"), "seed").schemaAction()).isEqualTo("create");
        for (String phase : new String[]{"restart", "undo", "redo"}) {
            var settings = NativeRecoveryDatabaseSettings.from(environment, Path.of("data"), phase);
            assertThat(settings.profiles()).isEqualTo("oracle,kubernetes");
            assertThat(settings.schemaAction()).isEqualTo("validate");
            assertThat(settings.expectedSchema()).isEqualTo("private-user");
            assertThat(settings.toString()).doesNotContain("private-password", "FREEPDB1", "private-user");
        }
    }

    @Test void refusesVendorDriftAndMissingExternalConnectionInputs() {
        assertThatThrownBy(() -> NativeRecoveryDatabaseSettings.from(external("oracle", "jdbc:postgresql://localhost/source"), Path.of("data"), "seed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeRecoveryDatabaseSettings.from(Map.of("TAXONOMY_RECOVERY_DATABASE_PROFILE", "oracle"), Path.of("data"), "seed"))
                .isInstanceOf(IllegalArgumentException.class);
        var environment = external("oracle", "jdbc:oracle:thin:@localhost:1521/FREEPDB1");
        environment.remove("TAXONOMY_RECOVERY_DB_PASSWORD");
        assertThatThrownBy(() -> NativeRecoveryDatabaseSettings.from(environment, Path.of("data"), "seed"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refusesUnknownPhasesBeforeApplicationStartup() {
        assertThatThrownBy(() -> NativeRecoveryDatabaseSettings.from(Map.of(), Path.of("data"), "reseeding"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> external(String vendor, String url) {
        return new HashMap<>(Map.of("TAXONOMY_RECOVERY_DATABASE_PROFILE", vendor, "TAXONOMY_RECOVERY_JDBC_URL", url,
                "TAXONOMY_RECOVERY_DB_USER", "private-user", "TAXONOMY_RECOVERY_DB_PASSWORD", "private-password"));
    }
}
