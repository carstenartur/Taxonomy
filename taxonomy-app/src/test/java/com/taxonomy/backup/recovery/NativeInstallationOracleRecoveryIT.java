package com.taxonomy.backup.recovery;

import com.taxonomy.support.ProcessTestDiagnostics;
import com.taxonomy.tooling.NativeInstallationSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real Oracle Data Pump plus the same full-application fixture as native HSQL/PostgreSQL recovery. */
@Testcontainers
@Tag("db-oracle")
@Tag("docker-required")
class NativeInstallationOracleRecoveryIT {
    @Container
    static final OracleContainer DATABASE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("recovery_admin_fixture").withPassword(UUID.randomUUID().toString())
            .withDatabaseName("taxonomy").withStartupTimeout(Duration.ofMinutes(3));

    @TempDir Path temporary;

    @Test
    void nativeSchemaRestorePreservesSavedDataCredentialsAndOriginalHistoryAcrossValidatedApplicationRestarts() throws Exception {
        Path evidence = Files.createDirectories(Path.of("target/native-oracle-recovery"));
        Files.deleteIfExists(evidence.resolve("result.json"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String sourceUser = "REC_SRC_" + suffix, targetUser = "REC_DST_" + suffix;
        String sourcePassword = UUID.randomUUID().toString(), targetPassword = UUID.randomUUID().toString();
        String loginPassword = UUID.randomUUID().toString();
        String directory = "REC_DIR_" + suffix, pumpRoot = "/tmp/taxonomy-recovery-" + suffix;
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path snapshot = temporary.resolve("snapshot"), target = temporary.resolve("restored");
        Path oracle = temporary.resolve("expected.properties");
        try {
            createUser(sourceUser, sourcePassword);
            createUser(targetUser, targetPassword);
            // Compose the released fresh Core stream with the application's controlled
            // empty-schema initialization. Hibernate's existing Core filter prevents a second DDL owner.
            var migration = org.flywaydb.core.Flyway.configure()
                    .dataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(DATABASE.getJdbcUrl(), sourceUser, sourcePassword))
                    .locations(io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations.ORACLE_LOCATION)
                    .table(io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations.SCHEMA_HISTORY_TABLE).load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            application(sourceUser, sourcePassword, loginPassword, source, oracle, "seed", "seed", evidence);
            var foreignKeys = repositoryForeignKeys(sourceUser, sourcePassword, evidence.resolve("source-before-restart-foreign-keys.json"));
            // A second normal JVM must reuse the exact source Git objects and application authority.
            application(sourceUser, sourcePassword, loginPassword, source, oracle, "restart", "source-restart", evidence);
            assertThat(repositoryForeignKeys(sourceUser, sourcePassword, evidence.resolve("source-after-restart-foreign-keys.json")))
                    .as("Restart retains the exact enabled repository foreign keys to the natural UNIQUE key").isEqualTo(foreignKeys);
            Properties expected = new Properties();
            try (var input = Files.newInputStream(oracle)) { expected.load(input); }
            String version = expected.getProperty("applicationVersion");
            Map<String, String> originalFiles = files(source);
            Map<String, String> originalRows = rows(sourceUser, sourcePassword);
            assertThat(originalRows).containsKeys("APP_USER", "GIT_PACKS", "GIT_REFLOG", "EDITOR_OPERATION", "PROJECT_REQ_VERSION");
            assertThat(originalFiles).containsKey("source-content/requirements.txt");
            assertThat(originalFiles.keySet()).anyMatch(name -> name.startsWith("lucene-index/"));
            assertWritersStopped(sourceUser);
            var receipt = NativeInstallationSnapshot.capture(source, snapshot, version, () -> {});
            assertThat(DATABASE.execInContainer("mkdir", "-p", pumpRoot).getExitCode()).isZero();
            sql("CREATE DIRECTORY " + directory + " AS '" + pumpRoot + "'");
            pump(false, sourceUser, targetUser, directory, pumpRoot, evidence);
            // The target account/permissions are independently provisioned, never imported from the source.
            assertThat(rows(targetUser, targetPassword)).isEmpty();
            Map<String, String> targetGrants = grants(targetUser);
            assertThatThrownBy(() -> requireEmptyTarget(sourceUser, sourcePassword))
                    .as("An alias of the populated source cannot be a fresh restore target")
                    .isInstanceOf(IllegalStateException.class);
            sqlAs(targetUser, targetPassword, "CREATE TABLE recovery_collision (value INTEGER)");
            assertThatThrownBy(() -> requireEmptyTarget(targetUser, targetPassword))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(query(targetUser, targetPassword, "SELECT COUNT(*) FROM recovery_collision")).isEqualTo("0");
            sqlAs(targetUser, targetPassword, "DROP TABLE recovery_collision PURGE");
            requireEmptyTarget(targetUser, targetPassword);
            assertWritersStopped(targetUser);
            pump(true, sourceUser, targetUser, directory, pumpRoot, evidence);
            assertThat(rows(targetUser, targetPassword))
                    .as("Every table row, null, text and LOB byte is retained before target startup")
                    .isEqualTo(originalRows);
            assertThat(grants(targetUser)).isEqualTo(targetGrants);
            assertThatThrownBy(() -> requireEmptyTarget(targetUser, targetPassword))
                    .as("A completed Oracle import is never replayed into the populated schema")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> DriverManager.getConnection(DATABASE.getJdbcUrl(), targetUser, sourcePassword))
                    .as("Schema remapping must not replace the target database password")
                    .isInstanceOf(SQLException.class);
            NativeInstallationSnapshot.restore(snapshot, source, target, version, () -> {});
            assertThat(files(target)).isEqualTo(originalFiles);
            application(targetUser, targetPassword, loginPassword, target, oracle, "undo", "target-undo", evidence);
            application(targetUser, targetPassword, loginPassword, target, oracle, "redo", "target-redo", evidence);
            assertThat(files(source)).isEqualTo(originalFiles);
            assertThat(rows(sourceUser, sourcePassword)).as("Every source row remains unchanged after target edits/restarts").isEqualTo(originalRows);
            assertThat(grants(targetUser)).isEqualTo(targetGrants);
            var report = new LinkedHashMap<String, Object>();
            report.put("status", "PASSED"); report.put("applicationVersion", version); report.put("databaseFamily", "ORACLE");
            report.put("serverVersion", query(sourceUser, sourcePassword, "SELECT version_full FROM product_component_version WHERE product LIKE 'Oracle Database%'") );
            report.put("recovery", "trusted same-version stopped-writer native schema export/import to a separate restricted user in the same PDB");
            report.put("manifestSha256", receipt.sha256()); report.put("fileCount", receipt.fileCount()); report.put("bytes", receipt.bytes());
            var dumpHash = DATABASE.execInContainer("sha256sum", pumpRoot + "/installation.dmp");
            assertThat(dumpHash.getExitCode()).isZero();
            String dumpSha256 = dumpHash.getStdout().strip().split("\\s+")[0];
            assertThat(dumpSha256).matches("[0-9a-f]{64}"); report.put("dumpSha256", dumpSha256);
            report.put("sourceSchemaFingerprint", hash(sourceUser.getBytes(StandardCharsets.UTF_8)));
            report.put("targetSchemaFingerprint", hash(targetUser.getBytes(StandardCharsets.UTF_8)));
            report.put("sourceRowsUnchanged", true); report.put("preservedTableCount", originalRows.size());
            report.put("sourceFilesUnchanged", true); report.put("sourceRestartCount", 1); report.put("targetRestartCount", 2);
            report.put("targetDatabaseCredentialsAndGrantsUnchanged", true); report.put("persistedApplicationAuthenticationVerified", true);
            report.put("sharedWorkingCopyOptInPreserved", true); report.put("authenticatedActorPrincipalPreserved", true);
            report.put("freshCoreMigration", "released Oracle Core 0.12.0; application Hibernate schema creation excludes the library-owned Core tables");
            report.put("coveredData", List.of("portfolio and requirement versions", "saved analysis and source provenance", "reviewed solution/product decisions",
                    "original SQL-backed Git objects, branches and reflogs", "editor checkpoints, draft, journal and undo/redo", "Git-backed preferences and document templates",
                    "retained source files and Lucene search", "application credentials and admin-only connected-database diagnostics"));
            report.put("limits", List.of("Test fixture acceptance, not an operator-facing Oracle recovery CLI", "Native trusted same-version Oracle Free only; no cross-vendor/portable restore",
                    "No old-Oracle managed Core adoption stream in jgit-storage-hibernate 0.12.0", "No atomic rollback claim for Oracle Data Pump or interrupted DDL",
                    "No in-flight external work, live writers, power-loss or Kubernetes acceptance"));
            Files.writeString(evidence.resolve("result.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Files.copy(snapshot.resolve("manifest.json"), evidence.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // The container is disposable; cleanup failures must not hide acceptance diagnostics.
            for (String user : List.of(targetUser, sourceUser)) try { sql("DROP USER " + user + " CASCADE"); } catch (SQLException ignored) { }
            try { sql("DROP DIRECTORY " + directory); } catch (SQLException ignored) { }
        }
    }

    private static void createUser(String user, String password) throws SQLException {
        // Fixture-only DBA setup: credentials travel over JDBC, never in a process command or report.
        try (var connection = admin(); var statement = connection.prepareStatement("""
                DECLARE app_name VARCHAR2(30) := ?; app_password VARCHAR2(128) := ?;
                BEGIN
                  EXECUTE IMMEDIATE 'CREATE USER ' || SYS.DBMS_ASSERT.ENQUOTE_NAME(app_name, FALSE)
                    || ' IDENTIFIED BY "' || app_password || '" DEFAULT TABLESPACE USERS QUOTA 1G ON USERS';
                END;
                """)) {
            statement.setString(1, user); statement.setString(2, password); statement.execute();
        }
        sql("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + user);
    }

    private static void requireEmptyTarget(String user, String password) throws SQLException {
        if (!query(user, password, "SELECT COUNT(*) FROM user_objects WHERE generated = 'N'").equals("0")) {
            throw new IllegalStateException("Native Oracle fixture requires an empty separately provisioned schema");
        }
    }

    private static void assertWritersStopped(String user) throws SQLException {
        try (var connection = admin(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM v$session WHERE username = ? AND type = 'USER'")) {
            statement.setString(1, user);
            try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isZero(); }
        }
    }

    private static void pump(boolean importing, String source, String target, String directory, String root, Path evidence) throws Exception {
        String operation = importing ? "IMPORT" : "EXPORT", log = importing ? "import.log" : "export.log";
        try (var connection = admin(); var statement = connection.prepareCall("""
                DECLARE h NUMBER; state VARCHAR2(30);
                BEGIN
                  h := DBMS_DATAPUMP.OPEN(?, 'SCHEMA');
                  DBMS_DATAPUMP.ADD_FILE(h, 'installation.dmp', ?, filetype => DBMS_DATAPUMP.KU$_FILE_TYPE_DUMP_FILE);
                  DBMS_DATAPUMP.ADD_FILE(h, ?, ?, filetype => DBMS_DATAPUMP.KU$_FILE_TYPE_LOG_FILE);
                  DBMS_DATAPUMP.METADATA_FILTER(h, 'SCHEMA_EXPR', ?);
                  DBMS_DATAPUMP.METADATA_FILTER(h, 'EXCLUDE_PATH_EXPR', 'IN (''USER'')');
                """ + (importing ? "DBMS_DATAPUMP.METADATA_REMAP(h, 'REMAP_SCHEMA', ?, ?);\n" : "") + """
                  DBMS_DATAPUMP.START_JOB(h);
                  DBMS_DATAPUMP.WAIT_FOR_JOB(h, state);
                  ? := state;
                END;
                """)) {
            statement.setQueryTimeout(150);
            statement.setString(1, operation); statement.setString(2, directory);
            statement.setString(3, log); statement.setString(4, directory);
            statement.setString(5, "IN ('" + source + "')");
            int output = 6;
            if (importing) { statement.setString(6, source); statement.setString(7, target); output = 8; }
            statement.registerOutParameter(output, Types.VARCHAR);
            statement.execute();
            assertThat(statement.getString(output)).isEqualTo("COMPLETED");
        } finally {
            var capture = DATABASE.execInContainer("cat", root + "/" + log);
            Files.writeString(evidence.resolve(log), capture.getStdout());
        }
        String output = Files.readString(evidence.resolve(log));
        assertThat(output).as("Oracle Data Pump completion log must be retained").isNotBlank();
        assertThat(output).as("Oracle Data Pump must report no skipped/failed objects").doesNotContain("ORA-", "completed with");
    }

    private static void application(String user, String password, String loginPassword, Path data, Path oracle,
                                    String phase, String label, Path evidence) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx768m", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                NativeInstallationRecoveryDriver.class.getName(), data.toString(), oracle.toString(), phase);
        builder.environment().put("TAXONOMY_RECOVERY_DATABASE_PROFILE", "oracle");
        builder.environment().put("TAXONOMY_RECOVERY_JDBC_URL", DATABASE.getJdbcUrl());
        builder.environment().put("TAXONOMY_RECOVERY_DB_USER", user); builder.environment().put("TAXONOMY_RECOVERY_DB_PASSWORD", password);
        builder.environment().put("TAXONOMY_RECOVERY_LOGIN_PASSWORD", loginPassword);
        Path log = evidence.resolve(label + ".log");
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done = process.waitFor(150, TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); process.waitFor(20, TimeUnit.SECONDS); }
        assertThat(done).as(() -> "Oracle application process completed; " + ProcessTestDiagnostics.describe(log)).isTrue();
        ProcessTestDiagnostics.assertCompleted(process.exitValue(), log, "NATIVE_INSTALLATION_RECOVERY_OK " + phase);
        String output = Files.readString(log);
        assertThat(output.contains(password) || output.contains(loginPassword))
                .as("Application process logs must not disclose test credentials").isFalse();
    }

    private static Map<String, String> rows(String user, String password) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), user, password)) {
            var tables = new ArrayList<String>();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT table_name FROM user_tables WHERE nested = 'NO' ORDER BY table_name")) {
                while (result.next()) tables.add(result.getString(1));
            }
            return NativeRecoveryRowDigests.tables(connection, tables);
        }
    }

    private static List<Map<String, Object>> repositoryForeignKeys(String user, String password, Path evidence) throws Exception {
        var dictionary = new ArrayList<Map<String, Object>>();
        var jdbc = new ArrayList<Map<String, Object>>();
        var tables = List.of("RELATION_PROPOSAL", "TAXONOMY_RELATION", "RELATION_HYPOTHESIS", "ARCHITECTURE_COMMIT_INDEX");
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), user, password);
             var query = connection.prepareStatement("""
                     SELECT f.table_name, f.constraint_name, fc.position, fc.column_name,
                            p.owner, p.table_name, p.constraint_type, pc.column_name, f.status
                     FROM all_constraints f
                     JOIN all_constraints p ON p.owner=f.r_owner AND p.constraint_name=f.r_constraint_name
                     JOIN all_cons_columns fc ON fc.owner=f.owner AND fc.constraint_name=f.constraint_name AND fc.table_name=f.table_name
                     JOIN all_cons_columns pc ON pc.owner=p.owner AND pc.constraint_name=p.constraint_name
                       AND pc.table_name=p.table_name AND pc.position=fc.position
                     WHERE f.constraint_type='R' AND f.owner=? AND p.owner=? AND p.table_name='SYSTEM_REPOSITORY'
                     ORDER BY f.table_name, f.constraint_name, fc.position
                     """)) {
            query.setString(1, user); query.setString(2, user);
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    if (!tables.contains(result.getString(1))) continue;
                    dictionary.add(Map.of("table", result.getString(1), "constraint", result.getString(2),
                            "position", result.getInt(3), "column", result.getString(4), "parentOwner", result.getString(5),
                            "parentTable", result.getString(6), "parentConstraintType", result.getString(7),
                            "parentColumn", result.getString(8), "status", result.getString(9)));
                }
            }
            for (String table : tables) {
                try (var keys = connection.getMetaData().getImportedKeys(null, user, table)) {
                    while (keys.next()) jdbc.add(Map.of("table", table, "constraint", keys.getString("FK_NAME"),
                            "column", keys.getString("FKCOLUMN_NAME"), "parentOwner", keys.getString("PKTABLE_SCHEM"),
                            "parentTable", keys.getString("PKTABLE_NAME"), "parentColumn", keys.getString("PKCOLUMN_NAME")));
                }
            }
        }
        Files.writeString(evidence, JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("dictionary", dictionary, "jdbcImportedKeys", jdbc)));
        assertThat(dictionary).extracting(key -> key.get("table")).containsExactlyInAnyOrderElementsOf(tables);
        assertThat(dictionary).allSatisfy(key -> {
            assertThat(key.get("column")).isEqualTo("REPOSITORY_ID");
            assertThat(key.get("parentColumn")).isEqualTo("REPOSITORY_ID");
            assertThat(key.get("parentConstraintType")).isEqualTo("U");
            assertThat(key.get("status")).isEqualTo("ENABLED");
            assertThat(key.get("position")).isEqualTo(1);
        });
        return dictionary;
    }

    private static Map<String, String> grants(String user) throws SQLException {
        var result = new TreeMap<String, String>();
        for (var entry : Map.of(
                "system", "SELECT privilege || ':' || admin_option FROM dba_sys_privs WHERE grantee = ? ORDER BY privilege",
                "roles", "SELECT granted_role || ':' || admin_option FROM dba_role_privs WHERE grantee = ? ORDER BY granted_role",
                "quotas", "SELECT tablespace_name || ':' || max_bytes FROM dba_ts_quotas WHERE username = ? ORDER BY tablespace_name").entrySet()) {
            var values = new ArrayList<String>();
            try (var connection = admin(); var statement = connection.prepareStatement(entry.getValue())) {
                statement.setString(1, user);
                try (var rows = statement.executeQuery()) { while (rows.next()) values.add(rows.getString(1)); }
            }
            result.put(entry.getKey(), String.join(",", values));
        }
        assertThat(result.get("system")).isEqualTo("CREATE SEQUENCE:NO,CREATE SESSION:NO,CREATE TABLE:NO");
        assertThat(result.get("roles")).isEmpty();
        return result;
    }

    private static Connection admin() throws SQLException { return DriverManager.getConnection(DATABASE.getJdbcUrl(), "SYSTEM", DATABASE.getPassword()); }
    private static void sql(String sql) throws SQLException { try (var connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); } }
    private static void sqlAs(String user, String password, String sql) throws SQLException { try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), user, password); var statement = connection.createStatement()) { statement.execute(sql); } }
    private static String query(String user, String password, String sql) throws SQLException { try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), user, password); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) { assertThat(result.next()).isTrue(); return result.getString(1); } }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hash(InputStream input) throws Exception { var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); return HexFormat.of().formatHex(digest.digest()); }
    private static Map<String, String> files(Path root) throws Exception {
        var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) { for (Path path : paths.filter(Files::isRegularFile).toList()) try (var input = Files.newInputStream(path)) { hashes.put(root.relativize(path).toString(), hash(input)); } }
        return hashes;
    }
}
