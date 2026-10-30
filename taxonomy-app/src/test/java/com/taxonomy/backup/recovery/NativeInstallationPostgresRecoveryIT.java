package com.taxonomy.backup.recovery;

import com.taxonomy.tooling.NativeInstallationSnapshot;
import com.taxonomy.support.ProcessTestDiagnostics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Existing PostgreSQL database owner: real pg_dump/psql plus the full native application recovery oracle. */
@Tag("db-postgres")
@Tag("docker-required")
class NativeInstallationPostgresRecoveryIT {
    @TempDir Path temporary;

    @Test void restoresSameVersionPostgresAndFilesIntoAnIsolatedDatabaseAcrossApplicationRestarts() throws Exception {
        Path evidence = Path.of("target/native-postgres-recovery"); Files.createDirectories(evidence);
        Files.deleteIfExists(evidence.resolve("result.json"));
        try (var database = database()) {
            Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("restored");
            Path oracle = temporary.resolve("oracle.properties");
            application(database, database.sourceUrl, source, oracle, "seed", evidence);
            Properties expected = new Properties(); try (var input = Files.newInputStream(oracle)) { expected.load(input); }
            String version = expected.getProperty("applicationVersion");
            long largeObject = Long.parseLong(database.query(database.sourceUrl, "select lo_from_bytea(0, decode('000102ff', 'hex'))"));
            Map<String, String> original = digests(source), originalRows = database.rows(database.sourceUrl);
            assertThat(original.keySet()).contains("source-content/requirements.txt").anyMatch(name -> name.startsWith("lucene-index/"));
            assertThat(tool(database, "postgres-capture", "source", source, snapshot, null, version, evidence)).isZero();
            var receipt = NativeInstallationSnapshot.verify(snapshot, version, () -> {});
            assertThat(tool(database, "postgres-plan", "source", source, snapshot, target, version, evidence)).as("A source alias must not be restored in place").isNotZero();
            database.sql(database.targetUrl, "create table recovery_collision (value integer)");
            assertThat(tool(database, "postgres-plan", "target", source, snapshot, target, version, evidence)).as("A populated target must not be changed").isNotZero();
            database.sql(database.targetUrl, "drop table recovery_collision");
            assertThat(tool(database, "postgres-plan", "target", source, snapshot, target, version, evidence)).isZero();
            assertThat(target).doesNotExist();
            Path failingClients = Files.createDirectory(temporary.resolve("failing-clients"));
            for (String client : List.of("psql", "pg_dump", "pg_restore")) {
                Path wrapper = failingClients.resolve(client);
                String real = quote(database.bin.resolve(client).toString());
                Files.writeString(wrapper, "#!/bin/sh\n" + (client.equals("psql")
                        ? "case \" $* \" in *' --single-transaction '*) exec " + real + " \"$@\" --command='SELECT 1 / 0';; esac\n" : "")
                        + "exec " + real + " \"$@\"\n");
                assertThat(wrapper.toFile().setExecutable(true, true)).isTrue();
            }
            assertThat(tool(database, "postgres-restore", "target", source, snapshot, target, version, evidence, failingClients))
                    .as("An SQL failure after data and receipt commands must roll back the whole transaction").isNotZero();
            assertThat(target).doesNotExist();
            assertThat(database.rows(database.targetUrl)).isEmpty();
            assertThat(database.query(database.targetUrl, "select count(*) from pg_largeobject_metadata")).isEqualTo("0");
            assertThat(database.query(database.targetUrl, "select count(*) from pg_namespace where nspname='taxonomy_native_recovery'")).isEqualTo("0");
            assertThat(tool(database, "postgres-restore", "target", source, snapshot, target, version, evidence)).isZero();
            assertThat(digests(target)).containsAllEntriesOf(original);
            assertThat(database.rows(database.targetUrl)).as("Every public table row survives native SQL restore before application startup").isEqualTo(originalRows);
            assertThat(database.query(database.targetUrl, "select encode(lo_get(" + largeObject + "), 'hex')")).isEqualTo("000102ff");
            assertThat(database.query(database.targetUrl, "select manifest_sha256 from taxonomy_native_recovery.receipt")).isEqualTo(receipt.sha256());
            assertThat(database.query(database.sourceUrl, "select count(*) from pg_namespace where nspname='taxonomy_native_recovery'")).isEqualTo("0");
            database.sql(database.targetUrl, "create table recovery_after_commit (value integer)");
            database.sql(database.targetUrl, "insert into recovery_after_commit values (41)");
            Path replay = temporary.resolve("replayed-files");
            assertThat(tool(database, "postgres-restore", "target", source, snapshot, replay, version, evidence))
                    .as("Even with an absent file target, a committed/changed database is never replayed or declared unchanged").isNotZero();
            assertThat(replay).doesNotExist();
            assertThat(database.query(database.targetUrl, "select value from recovery_after_commit")).isEqualTo("41");
            database.sql(database.targetUrl, "drop table recovery_after_commit");
            application(database, database.targetUrl, target, oracle, "undo", evidence);
            application(database, database.targetUrl, target, oracle, "redo", evidence);
            assertThat(digests(source)).isEqualTo(original);
            assertThat(database.rows(database.sourceUrl)).as("Every source public table stays unchanged after target edits/restarts").isEqualTo(originalRows);
            assertThat(database.query(database.sourceUrl, "select encode(lo_get(" + largeObject + "), 'hex')")).isEqualTo("000102ff");
            var result = new LinkedHashMap<String, Object>();
            result.put("status", "PASSED"); result.put("applicationVersion", version); result.put("databaseFamily", "POSTGRESQL");
            result.put("serverVersion", database.query(database.targetUrl, "show server_version"));
            result.put("manifestSha256", receipt.sha256()); result.put("fileCount", receipt.fileCount()); result.put("bytes", receipt.bytes());
            result.put("sourceDatabaseFingerprint", fingerprint(database.query(database.sourceUrl, "select system_identifier::text || '|' || current_database() from pg_control_system()")));
            result.put("targetDatabaseFingerprint", fingerprint(database.query(database.targetUrl, "select system_identifier::text || '|' || current_database() from pg_control_system()")));
            result.put("filesystemBinding", "snapshot of stopped source root; restored to a new isolated root; original relative source paths retained");
            result.put("sourcePublicTablesUnchanged", true); result.put("preservedPublicTableCount", originalRows.size());
            result.put("sourceFilesUnchanged", true); result.put("targetRestartCount", 2); result.put("sourceAliasRejected", true); result.put("nonemptyTargetRejected", true);
            result.put("largeObjectBytesPreserved", true);
            result.put("lateSqlFailureRollsBackDataAndReceipt", true); result.put("changedCommittedTargetRejectsReplay", true);
            result.put("coveredData", List.of("portfolio and requirement history", "immutable saved analysis and source provenance", "reviewed solution/product decisions",
                    "SQL-backed Git objects, branches and editor undo/redo", "Git-backed preferences/templates", "retained source files and Lucene catalogue search"));
            result.put("limits", List.of("Native trusted same-version PostgreSQL 16 only; no portable importer", "Database roles, external credentials and tablespaces are not copied",
                    "Persisted application identities/grants are preserved; no quarantine claim", "No in-flight remote jobs or configured external providers", "No Kubernetes volume/controller acceptance"));
            Files.writeString(evidence.resolve("result.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(result));
            Files.copy(snapshot.resolve("manifest.json"), evidence.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test void rejectsAnOtherwiseEmptyTargetContainingOnlyALargeObject() throws Exception {
        rejectsOccupiedCatalogTarget("select lo_from_bytea(0, decode('00cafeff', 'hex'))",
                "select string_agg(encode(lo_get(oid), 'hex'), ',' order by oid) from pg_largeobject_metadata", "00cafeff");
    }

    @Test void rejectsAnOtherwiseEmptyTargetContainingOnlyAPublicCollation() throws Exception {
        rejectsOccupiedCatalogTarget("create collation public.recovery_collation from pg_catalog.\"C\"",
                "select collname from pg_collation where collnamespace='public'::regnamespace", "recovery_collation");
    }

    private void rejectsOccupiedCatalogTarget(String setup, String probe, String expected) throws Exception {
        Path evidence = Files.createDirectories(temporary.resolve("catalog-evidence"));
        try (var database = database()) {
            Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("restored");
            database.sql(database.sourceUrl, "create table source_evidence (value integer)");
            database.sql(database.sourceUrl, "insert into source_evidence values (73)");
            assertThat(tool(database, "postgres-capture", "source", source, snapshot, null, "catalog-fixture", evidence)).isZero();
            database.sql(database.targetUrl, setup);
            int planned = tool(database, "postgres-plan", "target", source, snapshot, target, "catalog-fixture", evidence);
            int restored = tool(database, "postgres-restore", "target", source, snapshot, target, "catalog-fixture", evidence);
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertThat(planned).as("plan rejects an occupied catalog").isNotZero(),
                    () -> assertThat(restored).as("restore rejects an occupied catalog").isNotZero(),
                    () -> assertThat(target).doesNotExist(),
                    () -> assertThat(database.query(database.targetUrl, probe)).isEqualTo(expected),
                    () -> assertThat(database.rows(database.targetUrl)).isEmpty(),
                    () -> assertThat(database.query(database.sourceUrl, "select value from source_evidence")).isEqualTo("73"));
        }
    }

    private Database database() throws Exception {
        String config = System.getProperty("taxonomy.recovery.postgres.config");
        if (config != null) {
            var data = JsonMapper.builder().build().readTree(Files.readString(Path.of(config)));
            return new Database(data.get("source_jdbc_url").asText(), data.get("user").asText(),
                    Files.readString(Path.of(data.get("password_file").asText())).strip(), Path.of(data.get("bin").asText()), null);
        }
        @SuppressWarnings("rawtypes") var container = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("taxonomy_recovery_admin").withUsername("taxonomy_recovery_test").withPassword(UUID.randomUUID().toString())
                .withFileSystemBind(temporary.toAbsolutePath().toString(), temporary.toAbsolutePath().toString(), BindMode.READ_WRITE);
        container.start();
        try { return new Database(container.getJdbcUrl(), container.getUsername(), container.getPassword(), null, container); }
        catch (Exception failure) { container.close(); throw failure; }
    }

    private final class Database implements AutoCloseable {
        final String adminUrl, sourceUrl, targetUrl, user, password, sourceName, targetName;
        final Path bin, serviceFile, passFile;
        final PostgreSQLContainer container;
        int toolInvocation;
        Database(String url, String user, String password, Path nativeBin, PostgreSQLContainer container) throws Exception {
            this.adminUrl = url; this.user = user; this.password = password; this.container = container;
            String suffix = UUID.randomUUID().toString().replace("-", ""); sourceName = "native_source_" + suffix; targetName = "native_target_" + suffix;
            String prefix = url.substring(0, url.lastIndexOf('/') + 1); sourceUrl = prefix + sourceName; targetUrl = prefix + targetName;
            sql(adminUrl, "create database " + sourceName + " template template0"); sql(adminUrl, "create database " + targetName + " template template0");
            var uri = java.net.URI.create(url.substring("jdbc:".length()));
            String host = container == null ? uri.getHost() : "127.0.0.1"; int port = container == null ? uri.getPort() : 5432;
            serviceFile = temporary.resolve("pg_service.conf"); passFile = temporary.resolve("pgpass");
            Files.writeString(serviceFile, "[source]\nhost=" + host + "\nport=" + port + "\nuser=" + user + "\ndbname=" + sourceName
                    + "\n[target]\nhost=" + host + "\nport=" + port + "\nuser=" + user + "\ndbname=" + targetName + "\n");
            Files.writeString(passFile, host + ":" + port + ":*:" + user + ":" + password.replace("\\", "\\\\").replace(":", "\\:") + "\n");
            Files.setPosixFilePermissions(passFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            if (container == null) bin = nativeBin;
            else {
                bin = Files.createDirectory(temporary.resolve("pg-bin"));
                for (String client : List.of("psql", "pg_dump", "pg_restore")) {
                    Path script = bin.resolve(client);
                    Files.writeString(script, "#!/bin/sh\nexec docker exec --user 0 --env " + quote("PGSERVICEFILE=" + serviceFile)
                            + " --env " + quote("PGPASSFILE=" + passFile) + " " + quote(container.getContainerId()) + " " + client + " \"$@\"\n");
                    assertThat(script.toFile().setExecutable(true, true)).isTrue();
                }
            }
        }
        void sql(String url, String sql) throws Exception { try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) { statement.execute(sql); } }
        String query(String url, String sql) throws Exception { try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) { assertThat(result.next()).isTrue(); return result.getString(1); } }
        Map<String, String> rows(String url) throws Exception {
            var hashes = new TreeMap<String, String>(); var tables = new ArrayList<String>();
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement();
                 var result = statement.executeQuery("select tablename from pg_tables where schemaname='public' order by tablename")) {
                while (result.next()) tables.add(result.getString(1));
            }
            for (String table : tables) hashes.put(table, query(url,
                    "select md5(coalesce(string_agg(md5(row_to_json(t)::text), '' order by md5(row_to_json(t)::text)), '')) from public.\""
                            + table.replace("\"", "\"\"") + "\" t"));
            return hashes;
        }
        @Override public void close() throws Exception {
            try { sql(adminUrl, "drop database " + targetName); sql(adminUrl, "drop database " + sourceName); }
            finally { Files.deleteIfExists(passFile); if (container != null) container.close(); }
        }
    }

    private static int tool(Database db, String operation, String service, Path source, Path snapshot, Path target, String version, Path evidence) throws Exception {
        return tool(db, operation, service, source, snapshot, target, version, evidence, db.bin);
    }
    private static int tool(Database db, String operation, String service, Path source, Path snapshot, Path target, String version, Path evidence, Path clients) throws Exception {
        var command = new ArrayList<>(List.of(java(), "-Xmx128m", "-cp", classpath(), "com.taxonomy.tooling.TaxonomyTooling", "native-installation", operation,
                "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", version, "--pg-bin", clients.toString(), "--service", service, "--writers-stopped", "true"));
        if (target != null) command.addAll(List.of("--target", target.toString()));
        var builder = new ProcessBuilder(command); builder.environment().put("PGSERVICEFILE", db.serviceFile.toString()); builder.environment().put("PGPASSFILE", db.passFile.toString());
        return process(builder, evidence.resolve(String.format("%02d-%s-%s.log", ++db.toolInvocation, operation, service)));
    }
    private static void application(Database db, String url, Path data, Path oracle, String phase, Path evidence) throws Exception {
        var builder = new ProcessBuilder(java(), "-Xmx768m", "-cp", classpath(), NativeInstallationRecoveryDriver.class.getName(), data.toString(), oracle.toString(), phase);
        builder.environment().put("TAXONOMY_RECOVERY_JDBC_URL", url); builder.environment().put("TAXONOMY_RECOVERY_DB_USER", db.user); builder.environment().put("TAXONOMY_RECOVERY_DB_PASSWORD", db.password);
        Path log = evidence.resolve(phase + ".log");
        ProcessTestDiagnostics.assertCompleted(process(builder, log), log, "NATIVE_INSTALLATION_RECOVERY_OK " + phase);
    }
    private static int process(ProcessBuilder builder, Path log) throws Exception {
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start(); boolean done = process.waitFor(150, TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); process.waitFor(20, TimeUnit.SECONDS); }
        assertThat(done).as(() -> "Native recovery process completed; " + ProcessTestDiagnostics.describe(log)).isTrue();
        return process.exitValue();
    }
    private static String fingerprint(String identity) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static String classpath() { return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")); }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static Map<String, String> digests(Path root) throws Exception {
        var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                var hash = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[64 * 1024]; int count; while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count); }
                hashes.put(root.relativize(path).toString(), HexFormat.of().formatHex(hash.digest()));
            }
        }
        return hashes;
    }
}
