package com.taxonomy.tooling;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Trusted, stopped same-version native recovery. This never imports a portable backup or activates an application. */
public final class NativePostgresRecovery {
    private static final String IDENTITY_SQL = "select system_identifier::text || '|' || current_database() || '|' || (current_setting('server_version_num')::int / 10000)::text from pg_control_system()";
    private static final String CONNECTIONS_SQL = "select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()";
    private static final String OCCUPIED_SQL = "select (select count(*) from pg_namespace where nspname not like 'pg_%' and nspname not in ('public','information_schema'))"
            + " + (select count(*) from pg_class c join pg_namespace n on c.relnamespace=n.oid where n.nspname not like 'pg_%' and n.nspname<>'information_schema')"
            + " + (select count(*) from pg_proc p join pg_namespace n on p.pronamespace=n.oid where n.nspname not like 'pg_%' and n.nspname<>'information_schema')"
            + " + (select count(*) from pg_type t join pg_namespace n on t.typnamespace=n.oid where n.nspname not like 'pg_%' and n.nspname<>'information_schema')"
            + " + (select count(*) from pg_largeobject_metadata)"
            + " + (select count(*) from pg_collation c join pg_namespace n on c.collnamespace=n.oid where n.nspname not like 'pg_%' and n.nspname<>'information_schema')"
            + " + (select count(*) from pg_extension where extname<>'plpgsql') + (select count(*) from pg_foreign_server)"
            + " + (select count(*) from pg_publication) + (select count(*) from pg_subscription)";
    private static final long MAX_OUTPUT = 16L * 1024 * 1024, MAX_FILE = 64L * 1024 * 1024 * 1024;
    private static final Duration PROCESS_LIMIT = Duration.ofMinutes(10);
    private record Database(String system, String name, String major) {
        private Database {
            if (!system.matches("[0-9]{1,20}") || !name.matches("[A-Za-z_][A-Za-z0-9_-]{0,62}") || !major.matches("[0-9]{2}"))
                throw new IllegalArgumentException("Unsupported PostgreSQL database identity");
        }
    }
    private NativePostgresRecovery() { }

    public static NativeInstallationSnapshot.Receipt capture(Path source, Path snapshot, String version, Path bin,
                                                             String service, NativeInstallationSnapshot.Checkpoint checkpoint) throws IOException {
        var clients = new Clients(bin, service, checkpoint);
        return NativeInstallationSnapshot.capturePrepared(source, snapshot, version, NativeInstallationSnapshot.DatabaseFamily.POSTGRESQL, data -> {
            for (String reserved : List.of("database.dump", "database-source.json"))
                if (Files.exists(data.resolve(reserved), NOFOLLOW_LINKS)) throw new IOException("Source contains a reserved PostgreSQL recovery file");
            clients.requireVersions(data);
            Database before = clients.identity(data);
            requireSupported(before);
            clients.requireNoConnections(data);
            Path dump = data.resolve("database.dump");
            clients.execute("pg_dump", List.of("--dbname=service=" + service, "--no-password", "--format=custom", "--no-owner", "--no-privileges",
                    "--exclude-schema=taxonomy_native_recovery", "--file=" + dump), data, dump);
            clients.execute("pg_restore", List.of("--list", dump.toString()), data, null);
            if (!before.equals(clients.identity(data))) throw new IOException("PostgreSQL source identity changed during capture");
            clients.requireNoConnections(data);
            Files.writeString(data.resolve("database-source.json"), FlatJson.pretty(Map.of("format", "taxonomy-native-postgres-source-v1",
                    "systemIdentifier", before.system(), "database", before.name(), "majorVersion", before.major())), StandardOpenOption.CREATE_NEW);
        }, checkpoint);
    }

    /** Read-only database and filesystem preflight. Credentials are resolved by libpq's service/password files. */
    public static NativeInstallationSnapshot.Receipt plan(Path snapshot, Path original, Path target, String version, Path bin,
                                                          String service, NativeInstallationSnapshot.Checkpoint checkpoint) throws IOException {
        var receipt = NativeInstallationSnapshot.plan(snapshot, original, target, version, NativeInstallationSnapshot.DatabaseFamily.POSTGRESQL, checkpoint);
        Path stage = Files.createTempDirectory(target.toAbsolutePath().getParent(), ".postgres-recovery-");
        try {
            var clients = new Clients(bin, service, checkpoint);
            clients.requireVersions(stage);
            requireTarget(clients, sourceIdentity(snapshot), stage);
            clients.execute("pg_restore", List.of("--list", snapshot.toAbsolutePath().resolve("data/database.dump").toString()), stage, null);
            return receipt;
        } finally { NativeInstallationSnapshot.removeStage(stage); }
    }

    /** Only a pre-created, empty, exclusively held database and an absent filesystem target are accepted. */
    public static NativeInstallationSnapshot.Receipt restore(Path snapshot, Path original, Path target, String version, Path bin,
                                                             String service, NativeInstallationSnapshot.Checkpoint checkpoint) throws IOException {
        var receipt = plan(snapshot, original, target, version, bin, service, checkpoint);
        Path stage = Files.createTempDirectory(target.toAbsolutePath().getParent(), ".postgres-recovery-");
        try {
            var clients = new Clients(bin, service, checkpoint);
            Database source = sourceIdentity(snapshot), destination = requireTarget(clients, source, stage);
            Path sql = stage.resolve("restore.sql");
            clients.execute("pg_restore", List.of("--single-transaction", "--no-owner", "--no-privileges", "--no-tablespaces", "--file=" + sql,
                    snapshot.toAbsolutePath().resolve("data/database.dump").toString()), stage, sql);
            removeGeneratedTransactionEnvelope(sql);
            if (!receipt.equals(NativeInstallationSnapshot.verify(snapshot, version, NativeInstallationSnapshot.DatabaseFamily.POSTGRESQL, checkpoint))) throw new IOException("PostgreSQL snapshot changed after planning");
            if (!destination.equals(requireTarget(clients, source, stage))) throw new IOException("PostgreSQL target identity changed");
            String guard = "DO $$ BEGIN IF current_database() <> '" + destination.name()
                    + "' OR (SELECT system_identifier::text FROM pg_control_system()) <> '" + destination.system()
                    + "' OR current_setting('server_version_num')::int / 10000 <> 16 THEN RAISE EXCEPTION 'PostgreSQL transaction target identity changed'; END IF;"
                    + " IF (" + CONNECTIONS_SQL + ") <> 0 THEN RAISE EXCEPTION 'PostgreSQL target is no longer exclusive'; END IF;"
                    + " IF (" + OCCUPIED_SQL + ") <> 0 THEN RAISE EXCEPTION 'Native recovery target is no longer empty'; END IF; END $$";
            String marker = "CREATE SCHEMA taxonomy_native_recovery; CREATE TABLE taxonomy_native_recovery.receipt"
                    + " (manifest_sha256 varchar(64) PRIMARY KEY, source_system text NOT NULL, source_database text NOT NULL, target_system text NOT NULL, target_database text NOT NULL);"
                    + " INSERT INTO taxonomy_native_recovery.receipt VALUES ('" + receipt.sha256() + "','" + source.system() + "','" + source.name()
                    + "','" + destination.system() + "','" + destination.name() + "')";
            clients.execute("psql", List.of("--dbname=service=" + service, "--no-password", "-X", "-v", "ON_ERROR_STOP=1", "--single-transaction",
                    "--command=" + guard, "--file=" + sql, "--command=" + marker), stage, null);
            try { return NativeInstallationSnapshot.restore(snapshot, original, target, version, checkpoint); }
            catch (IOException interrupted) {
                throw new IOException("PostgreSQL transaction committed; files were not published. Keep target inactive and inspect taxonomy_native_recovery.receipt. Verify target custody before file-only publication; use a new empty target if commit state or subsequent database activity is uncertain", interrupted);
            }
        } finally { NativeInstallationSnapshot.removeStage(stage); }
    }

    /** PG16 emits separate BLOB transactions unless -1 is used. Remove only the verified -1 envelope,
     * so the caller's guarded psql transaction also owns large objects and the recovery receipt. */
    static void removeGeneratedTransactionEnvelope(Path sql) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(sql, StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
            long size = channel.size(); int bound = (int) Math.min(size, 64 * 1024);
            String prefix = readWindow(channel, 0, bound), suffix = readWindow(channel, size - bound, bound);
            int begin = prefix.indexOf("\nBEGIN;\n"), commit = suffix.lastIndexOf("\nCOMMIT;\n\n--\n-- PostgreSQL database dump complete\n--\n");
            long beginOffset = begin + 1L, commitOffset = size - bound + commit + 1L;
            if (!prefix.startsWith("--\n-- PostgreSQL database dump\n--\n") || begin < 0 || commit < 0
                    || commitOffset <= beginOffset + 6 || !envelopeOnly(prefix.substring(0, begin + 1), "restrict")
                    || !envelopeOnly(suffix.substring(commit + "\nCOMMIT;\n".length()), "unrestrict"))
                throw new IOException("Generated PostgreSQL transaction envelope is unsupported");
            writeSpaces(channel, beginOffset, 6); writeSpaces(channel, commitOffset, 7); channel.force(true);
        }
    }
    private static String readWindow(java.nio.channels.FileChannel channel, long offset, int count) throws IOException {
        var buffer = java.nio.ByteBuffer.allocate(count); channel.position(offset);
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new EOFException("Generated PostgreSQL SQL shortened");
        return new String(buffer.array(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    private static boolean envelopeOnly(String text, String metaCommand) {
        for (String line : text.split("\n", -1))
            if (!line.isBlank() && !line.startsWith("--") && !line.matches("\\\\" + metaCommand + " [A-Za-z0-9]+")) return false;
        return true;
    }
    private static void writeSpaces(java.nio.channels.FileChannel channel, long offset, int count) throws IOException {
        var buffer = java.nio.ByteBuffer.wrap(" ".repeat(count).getBytes(java.nio.charset.StandardCharsets.US_ASCII)); channel.position(offset);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static Database sourceIdentity(Path snapshot) throws IOException {
        Path metadata = snapshot.resolve("data/database-source.json");
        if (!Files.isRegularFile(metadata, NOFOLLOW_LINKS) || Files.size(metadata) > 4096) throw new IOException("Native PostgreSQL source identity is missing or invalid");
        try {
            String text = Files.readString(metadata); NativeInstallationSnapshot.requireBoundedJson(text);
            var data = FlatJson.parseObject(text);
            if (!data.keySet().equals(Set.of("format", "systemIdentifier", "database", "majorVersion"))
                    || !"taxonomy-native-postgres-source-v1".equals(data.get("format"))) throw new IOException("Native PostgreSQL source identity is missing or invalid");
            var identity = new Database((String) data.get("systemIdentifier"), (String) data.get("database"), (String) data.get("majorVersion"));
            requireSupported(identity); return identity;
        } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) { throw new IOException("Native PostgreSQL source identity is missing or invalid"); }
    }
    private static void requireSupported(Database identity) throws IOException {
        if (!identity.major().equals("16")) throw new IOException("Native PostgreSQL recovery currently requires PostgreSQL 16");
    }
    private static Database requireTarget(Clients clients, Database source, Path temporary) throws IOException {
        Database target = clients.identity(temporary); requireSupported(target);
        if (source.system().equals(target.system()) && source.name().equals(target.name())) throw new IOException("PostgreSQL target is the source database");
        if (Set.of("postgres", "template0", "template1").contains(target.name())) throw new IOException("A dedicated empty PostgreSQL target database is required");
        clients.requireNoConnections(temporary);
        if (!clients.query(OCCUPIED_SQL, temporary).equals("0")) throw new IOException("PostgreSQL target is not empty; no merge or overwrite is allowed");
        return target;
    }

    private static final class Clients {
        private final Path bin;
        private final String service;
        private final NativeInstallationSnapshot.Checkpoint checkpoint;
        private Clients(Path bin, String service, NativeInstallationSnapshot.Checkpoint checkpoint) throws IOException {
            this.bin = NativeInstallationSnapshot.directory(bin); this.checkpoint = checkpoint;
            if (service == null || !service.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IOException("An explicit PostgreSQL service alias is required");
            this.service = service;
        }
        private void requireVersions(Path temporary) throws IOException {
            for (String tool : List.of("psql", "pg_dump", "pg_restore")) {
                String version = execute(tool, List.of("--version"), temporary, null).trim();
                if (!version.matches(java.util.regex.Pattern.quote(tool) + " \\(PostgreSQL\\) 16\\.[0-9]+(?: .*)?"))
                    throw new IOException("Native PostgreSQL recovery requires version 16 clients: " + tool);
            }
        }
        private Database identity(Path temporary) throws IOException {
            String[] fields = query(IDENTITY_SQL, temporary).split("\\|", -1);
            if (fields.length != 3) throw new IOException("PostgreSQL physical identity is unproven");
            try { return new Database(fields[0], fields[1], fields[2]); }
            catch (IllegalArgumentException invalid) { throw new IOException("PostgreSQL physical identity is unproven"); }
        }
        private void requireNoConnections(Path temporary) throws IOException {
            if (!query(CONNECTIONS_SQL, temporary).equals("0")) throw new IOException("PostgreSQL has other connections; stop all application and worker clients first");
        }
        private String query(String sql, Path temporary) throws IOException {
            return execute("psql", List.of("--dbname=service=" + service, "--no-password", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "--command=" + sql), temporary, null).trim();
        }
        private String execute(String tool, List<String> args, Path temporary, Path boundedFile) throws IOException {
            checkpoint.check(); Path executable = bin.resolve(tool);
            if (!Files.isRegularFile(executable, NOFOLLOW_LINKS) || !Files.isExecutable(executable)) throw new IOException("Required PostgreSQL client is unavailable: " + tool);
            Path stdout = Files.createTempFile(temporary, ".postgres-output-", ".log"), stderr = Files.createTempFile(temporary, ".postgres-error-", ".log");
            Process process = null;
            try {
                // Keep the host as owner even when a container client runs as root and truncates this file.
                // CREATE_NEW refuses replacement; POSIX permissions are private at creation, not tightened later.
                if (boundedFile != null) {
                    if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                        Files.createFile(boundedFile, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                    else Files.createFile(boundedFile); // The private parent remains the access boundary on non-POSIX stores.
                }
                var command = new ArrayList<String>(); command.add(executable.toString()); command.addAll(args);
                var builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
                builder.environment().put("PGCONNECT_TIMEOUT", "10");
                builder.environment().put("PGAPPNAME", "taxonomy-native-recovery");
                builder.environment().put("PGOPTIONS", "-c statement_timeout=600000 -c lock_timeout=10000");
                process = builder.start(); process.getOutputStream().close(); long deadline = System.nanoTime() + PROCESS_LIMIT.toNanos();
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    checkpoint.check();
                    if (System.nanoTime() > deadline || Files.size(stdout) > MAX_OUTPUT || Files.size(stderr) > MAX_OUTPUT
                            || boundedFile != null && Files.exists(boundedFile) && Files.size(boundedFile) > MAX_FILE)
                        throw new IOException("PostgreSQL client exceeded its time or output bound: " + tool);
                }
                checkpoint.check();
                if (process.exitValue() != 0) throw new IOException("PostgreSQL client failed: " + tool + " (private client output suppressed)");
                if (Files.size(stdout) > MAX_OUTPUT || Files.size(stderr) > MAX_OUTPUT
                        || boundedFile != null && Files.exists(boundedFile) && Files.size(boundedFile) > MAX_FILE)
                    throw new IOException("PostgreSQL client exceeded its output bound: " + tool);
                return Files.readString(stdout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new InterruptedIOException("PostgreSQL recovery interrupted");
            } finally {
                if (process != null && process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
                Files.deleteIfExists(stdout); Files.deleteIfExists(stderr);
            }
        }
    }

    static int run(String[] arguments, Path workingDirectory, PrintStream output, PrintStream error) {
        try {
            var options = new HashMap<String, String>();
            for (int i = 1; i < arguments.length; i += 2)
                if (i + 1 == arguments.length || !Set.of("--source", "--snapshot", "--version", "--writers-stopped", "--pg-bin", "--service", "--target").contains(arguments[i])
                        || options.putIfAbsent(arguments[i], arguments[i + 1]) != null) throw new IOException("Invalid PostgreSQL recovery option");
            if (!"true".equals(options.get("--writers-stopped"))) throw new IOException("--writers-stopped true is required; stop all application and worker writers");
            for (String option : List.of("--source", "--snapshot", "--version", "--pg-bin", "--service"))
                if (!options.containsKey(option)) throw new IOException("Required PostgreSQL recovery option: " + option);
            var checkpoint = (NativeInstallationSnapshot.Checkpoint) () -> { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("PostgreSQL recovery interrupted"); };
            Path source = workingDirectory.resolve(options.get("--source")), snapshot = workingDirectory.resolve(options.get("--snapshot")), bin = workingDirectory.resolve(options.get("--pg-bin"));
            String version = options.get("--version"), service = options.get("--service");
            NativeInstallationSnapshot.Receipt receipt;
            if (arguments[0].equals("postgres-capture")) receipt = capture(source, snapshot, version, bin, service, checkpoint);
            else {
                if (!options.containsKey("--target")) throw new IOException("--target is required for PostgreSQL plan/restore");
                Path target = workingDirectory.resolve(options.get("--target"));
                if (arguments[0].equals("postgres-plan")) receipt = plan(snapshot, source, target, version, bin, service, checkpoint);
                else if (arguments[0].equals("postgres-restore")) receipt = restore(snapshot, source, target, version, bin, service, checkpoint);
                else throw new IOException("Unsupported PostgreSQL recovery operation");
            }
            output.println("Native PostgreSQL " + arguments[0] + " verified: " + receipt.fileCount() + " files, manifest SHA-256 " + receipt.sha256()); return 0;
        } catch (IOException | IllegalArgumentException failure) { error.println("Native PostgreSQL operation failed: " + failure.getMessage()); return 1; }
    }
}
