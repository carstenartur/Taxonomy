package com.taxonomy.backup.recovery;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Explicit test-process configuration; never guesses a vendor from credentials or a service name. */
record NativeRecoveryDatabaseSettings(String profiles, String url, String username, String password,
                                      String schemaAction) {
    static NativeRecoveryDatabaseSettings from(Map<String, String> environment, Path data, String phase) {
        if (!Set.of("seed", "restart", "undo", "redo").contains(phase)) {
            throw new IllegalArgumentException("Unknown native recovery phase");
        }
        String url = environment.get("TAXONOMY_RECOVERY_JDBC_URL");
        if (url == null) {
            if (environment.containsKey("TAXONOMY_RECOVERY_DATABASE_PROFILE")) {
                throw new IllegalArgumentException("An external recovery profile requires an explicit JDBC URL");
            }
            return new NativeRecoveryDatabaseSettings("hsqldb-file", "jdbc:hsqldb:file:" + data.resolve("taxonomydb")
                    + ";hsqldb.default_table_type=cached;hsqldb.write_delay_millis=0;shutdown=true", "sa", "", "update");
        }
        String vendor = environment.getOrDefault("TAXONOMY_RECOVERY_DATABASE_PROFILE", "postgres");
        if (!Set.of("postgres", "oracle").contains(vendor) || !url.startsWith("jdbc:"
                + (vendor.equals("postgres") ? "postgresql:" : "oracle:"))) {
            throw new IllegalArgumentException("Unsupported or mismatched native recovery database profile");
        }
        String user = environment.get("TAXONOMY_RECOVERY_DB_USER");
        String password = environment.get("TAXONOMY_RECOVERY_DB_PASSWORD");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("External recovery requires explicit database credentials");
        }
        // Only this disposable Oracle seed uses create. Every later application process must validate.
        return new NativeRecoveryDatabaseSettings(vendor + ",kubernetes", url, user, password,
                vendor.equals("oracle") && phase.equals("seed") ? "create" : "validate");
    }

    String expectedSchema() {
        return profiles.startsWith("oracle,") ? username : "PUBLIC";
    }

    @Override public String toString() {
        return "NativeRecoveryDatabaseSettings[profiles=" + profiles + ", schemaAction=" + schemaAction + "]";
    }
}
