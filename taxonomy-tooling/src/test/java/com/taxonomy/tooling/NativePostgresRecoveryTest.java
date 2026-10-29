package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

/** Subprocess boundary tests; actual pg_dump/restore/application acceptance belongs to the PostgreSQL IT. */
class NativePostgresRecoveryTest {
    @TempDir Path temporary;

    @Test void postgresCaptureRequiresAnExplicitServiceAndStoppedWritersBeforeOpeningAProcess() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("evidence.txt"), "original");
        Path bin = fakeClients("123456789|source_db|16", "0");
        Path snapshot = temporary.resolve("snapshot");
        var result = run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(),
                "--version", "fixture-1", "--pg-bin", bin.toString(), "--service", "source");
        assertThat(result.code()).isNotZero();
        assertThat(temporary.resolve("invoked")).doesNotExist();
        assertThat(snapshot).doesNotExist();
    }

    @Test void postgresCaptureCreatesABoundArchiveWithoutWritingTheStoppedSource() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("evidence.txt"), "original");
        Path bin = fakeClients("123456789|source_db|16", "0");
        Path snapshot = temporary.resolve("snapshot");
        var result = run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(),
                "--version", "fixture-1", "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true");
        assertThat(result.code()).as(result.message()).isZero();
        assertThat(snapshot.resolve("data/database.dump")).exists();
        assertThat(snapshot.resolve("data/database-source.json")).exists();
        assertThat(snapshot.resolve("data/evidence.txt")).hasContent("original");
        try (var entries = Files.list(source)) { assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("evidence.txt"); }
        assertThat(result.message()).doesNotContain("source_db", "123456789", "PRIVATE_CLIENT_ERROR");
    }

    @Test void failedClientDoesNotPublishOrExposeClientOutput() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path bin = fakeClients("123456789|source_db|16", "0");
        script(bin, "pg_dump", "printf PRIVATE_CLIENT_ERROR >&2\nexit 1\n");
        Path snapshot = temporary.resolve("snapshot");
        var result = run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(),
                "--version", "fixture-1", "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true");
        assertThat(result.code()).isNotZero();
        assertThat(result.message()).doesNotContain("PRIVATE_CLIENT_ERROR");
        assertThat(snapshot).doesNotExist();
        try (var entries = Files.list(temporary)) { assertThat(entries.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".postgres-recovery-")); }
    }

    @Test void planRejectsSourceAliasAndPopulatedTargetsThenAllowsAnEmptyIsolatedDatabaseWithoutWriting() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        Path bin = fakeClients("123456789|source_db|16", "0");
        assertThat(run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true").code()).isZero();
        assertThat(recover("postgres-plan", source, snapshot, target, bin).code()).isNotZero();
        script(bin, "psql", "case \"$*\" in *pg_control_system*) printf '123456789|target_db|16\\n';; *pg_stat_activity*) printf '0\\n';; *) printf '1\\n';; esac\n");
        assertThat(recover("postgres-plan", source, snapshot, target, bin).code()).isNotZero();
        script(bin, "psql", "case \"$*\" in *pg_control_system*) printf '123456789|target_db|16\\n';; *) printf '0\\n';; esac\n");
        var planned = recover("postgres-plan", source, snapshot, target, bin);
        assertThat(planned.code()).as(planned.message()).isZero();
        assertThat(target).doesNotExist();
    }

    @Test void restorePublishesFilesOnlyAfterTheDatabaseTransactionSucceeds() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        Files.writeString(source.resolve("evidence.txt"), "retained");
        Path bin = fakeClients("123456789|source_db|16", "0");
        assertThat(run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true").code()).isZero();
        script(bin, "pg_restore", "for arg in \"$@\"; do case \"$arg\" in --file=*) printf '%s\\n' '--' '-- PostgreSQL database dump' '--' '' 'BEGIN;' 'SELECT 1;' 'COMMIT;' '' '--' '-- PostgreSQL database dump complete' '--' > \"${arg#--file=}\";; esac; done\n");
        script(bin, "psql", "case \"$*\" in *--single-transaction*) exit 1;; *pg_control_system*) printf '123456789|target_db|16\\n';; *) printf '0\\n';; esac\n");
        assertThat(recover("postgres-restore", source, snapshot, target, bin).code()).isNotZero();
        assertThat(target).doesNotExist();
        script(bin, "psql", "case \"$*\" in *pg_control_system*) printf '123456789|target_db|16\\n';; *) printf '0\\n';; esac\n");
        var restored = recover("postgres-restore", source, snapshot, target, bin);
        assertThat(restored.code()).as(restored.message()).isZero();
        assertThat(target.resolve("evidence.txt")).hasContent("retained");
        assertThat(source.resolve("evidence.txt")).hasContent("retained");
    }

    @Test void captureRejectsOtherDatabaseClientsAndUnsupportedServerMajorWithoutPublishing() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot");
        Path bin = fakeClients("123456789|source_db|16", "1");
        assertThat(run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true").code()).isNotZero();
        assertThat(snapshot).doesNotExist();
        script(bin, "psql", "case \"$*\" in *pg_control_system*) printf '123456789|source_db|17\\n';; *) printf '0\\n';; esac\n");
        assertThat(run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true").code()).isNotZero();
        assertThat(snapshot).doesNotExist();
    }

    @Test void transactionMustProveItsActualDatabaseIdentityBeforeApplyingSql() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        Path bin = fakeClients("123456789|source_db|16", "0");
        assertThat(run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true").code()).isZero();
        script(bin, "pg_restore", "for arg in \"$@\"; do case \"$arg\" in --file=*) printf '%s\\n' '--' '-- PostgreSQL database dump' '--' '' 'BEGIN;' 'SELECT 1;' 'COMMIT;' '' '--' '-- PostgreSQL database dump complete' '--' > \"${arg#--file=}\";; esac; done\n");
        // The boundary requires the actual transaction to check physical identity; preflight alone cannot bind a later process.
        script(bin, "psql", "case \"$*\" in *--single-transaction*) case \"$*\" in *'IF current_database()'*) printf '0\\n';; *) exit 1;; esac;; *pg_control_system*) printf '123456789|target_db|16\\n';; *) printf '0\\n';; esac\n");
        var restored = recover("postgres-restore", source, snapshot, target, bin);
        assertThat(restored.code()).as(restored.message()).isZero();
        assertThat(target).isDirectory();
    }

    @Test void postgresPlanRejectsAPostgresPayloadWrappedInAnHsqlManifest() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        Path bin = fakeClients("123456789|source_db|16", "0");
        assertThat(run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true").code()).isZero();
        Files.writeString(snapshot.resolve("data/taxonomy.properties"), "modified=no\n");
        Path mixed = temporary.resolve("mixed-family");
        NativeInstallationSnapshot.capture(snapshot.resolve("data"), mixed, "fixture-1", () -> {});
        script(bin, "psql", "case \"$*\" in *pg_control_system*) printf '123456789|target_db|16\\n';; *) printf '0\\n';; esac\n");
        assertThat(recover("postgres-plan", source, mixed, target, bin).code()).isNotZero();
        assertThat(target).doesNotExist();
    }

    @Test void postgresCaptureRejectsAClientFromAnotherMajorVersion() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot");
        Path bin = fakeClients("123456789|source_db|16", "0");
        Files.writeString(bin.resolve("pg_dump"), "#!/bin/sh\nif [ \"$1\" = --version ]; then printf 'pg_dump (PostgreSQL) 17.1\\n'; exit 0; fi\nfor arg in \"$@\"; do case \"$arg\" in --file=*) printf PGDMPfixture > \"${arg#--file=}\";; esac; done\n");
        assertThat(run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true").code()).isNotZero();
        assertThat(snapshot).doesNotExist();
    }

    @Test void removesOnlyTheVerifiedGeneratedEnvelopeAndPreservesObjectPayloadBytes() throws Exception {
        Path sql = temporary.resolve("restore.sql");
        String header = "--\n-- PostgreSQL database dump\n--\n\n\\restrict key123\n\nBEGIN;\n";
        String body = "SET statement_timeout = 0;\nSELECT $$\nCOMMIT;\nBEGIN;\n$$;\n" + "-- bounded chunk fixture\n".repeat(4000);
        String footer = "COMMIT;\n\n--\n-- PostgreSQL database dump complete\n--\n\n\\unrestrict key123\n";
        Files.writeString(sql, header + body + footer);
        NativePostgresRecovery.removeGeneratedTransactionEnvelope(sql);
        assertThat(Files.readString(sql)).isEqualTo(header.replace("BEGIN;", "      ") + body + footer.replace("COMMIT;", "       "));
    }

    @Test void refusesUnknownGeneratedSqlEnvelopeBeforeModifyingAnyBytes() throws Exception {
        String header = "--\n-- PostgreSQL database dump\n--\n\nBEGIN;\n";
        String footer = "COMMIT;\n\n--\n-- PostgreSQL database dump complete\n--\n";
        for (String invalid : java.util.List.of("SELECT 1;", header + "SELECT 1;\n", "SELECT 1;\n" + footer,
                "--\n-- PostgreSQL database dump\n--\nSET row_security = off;\nBEGIN;\nSELECT 1;\n" + footer,
                header + "SELECT 1;\n" + footer + "\\connect another_database\n")) {
            Path sql = temporary.resolve(java.util.UUID.randomUUID() + ".sql"); Files.writeString(sql, invalid);
            assertThatThrownBy(() -> NativePostgresRecovery.removeGeneratedTransactionEnvelope(sql)).isInstanceOf(IOException.class);
            assertThat(sql).hasContent(invalid);
        }
    }

    @Test void clientOutputFilesAlreadyBelongToTheHostAndArePrivateBeforeClientsWrite() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        Path bin = fakeClients("123456789|source_db|16", "0");
        String check = "for arg in \"$@\"; do case \"$arg\" in --file=*) file=${arg#--file=}; "
                + "[ -f \"$file\" ] && [ \"$(stat -c %a \"$file\")\" = 600 ] && [ \"$(stat -c %u \"$file\")\" = \"$(id -u)\" ] || exit 42; ";
        script(bin, "pg_dump", check + "printf PGDMPfixture > \"$file\";; esac; done\n");
        var captured = run("postgres-capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "source", "--writers-stopped", "true");
        assertThat(captured.code()).as(captured.message()).isZero();
        assertThat(Files.getPosixFilePermissions(snapshot.resolve("data/database.dump")))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        script(bin, "psql", "case \"$*\" in *pg_control_system*) printf '123456789|target_db|16\\n';; *) printf '0\\n';; esac\n");
        script(bin, "pg_restore", check + "printf '%s\\n' '--' '-- PostgreSQL database dump' '--' '' 'BEGIN;' 'SELECT 1;' 'COMMIT;' '' '--' '-- PostgreSQL database dump complete' '--' > \"$file\";; esac; done\n");
        var restored = recover("postgres-restore", source, snapshot, target, bin);
        assertThat(restored.code()).as(restored.message()).isZero();
        assertThat(target).isDirectory();
    }

    private Result recover(String operation, Path source, Path snapshot, Path target, Path bin) {
        return run(operation, "--source", source.toString(), "--snapshot", snapshot.toString(), "--target", target.toString(), "--version", "fixture-1",
                "--pg-bin", bin.toString(), "--service", "target", "--writers-stopped", "true");
    }

    private Path fakeClients(String identity, String count) throws Exception {
        Path bin = Files.createDirectory(temporary.resolve("bin"));
        script(bin, "psql", "case \"$*\" in *pg_control_system*) printf '" + identity + "\\n';; *) printf '" + count + "\\n';; esac\n");
        script(bin, "pg_dump", "for arg in \"$@\"; do case \"$arg\" in --file=*) printf PGDMPfixture > \"${arg#--file=}\";; esac; done\n");
        script(bin, "pg_restore", "exit 0\n");
        return bin;
    }
    private void script(Path bin, String name, String body) throws Exception {
        Path file = bin.resolve(name); Files.writeString(file, "#!/bin/sh\nif [ \"$1\" = --version ]; then printf '" + name + " (PostgreSQL) 16.15\\n'; exit 0; fi\ntouch '" + temporary.resolve("invoked") + "'\n" + body);
        assertThat(file.toFile().setExecutable(true, true)).isTrue();
    }
    private record Result(int code, String message) { }
    private Result run(String... args) {
        var output = new ByteArrayOutputStream(); var stream = new PrintStream(output);
        var command = new String[args.length + 1]; command[0] = "native-installation"; System.arraycopy(args, 0, command, 1, args.length);
        int code = TaxonomyTooling.run(command, temporary, stream, stream); return new Result(code, output.toString());
    }
}
