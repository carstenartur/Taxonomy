package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NativeInstallationSnapshotTest {
    @TempDir Path temporary;
    private static final String VERSION = "recovery-fixture-1";

    @Test void filesOnlyProofRestoresExternalBytesWithoutClaimingADatabaseBackup() throws Exception {
        Path source = filesSource(), snapshot = temporary.resolve("files-snapshot"), target = temporary.resolve("files-target");
        var receipt = NativeInstallationSnapshot.captureFiles(source, snapshot, VERSION, () -> {});
        assertThat(receipt.fileCount()).isEqualTo(2);
        assertThat(Files.readString(snapshot.resolve("manifest.json")))
                .contains("taxonomy-native-files-only-v1", "FILES_ONLY", VERSION).doesNotContain("databaseFamily");
        assertThat(NativeInstallationSnapshot.verifyFiles(snapshot, VERSION, () -> {})).isEqualTo(receipt);
        assertThat(NativeInstallationSnapshot.restoreFiles(snapshot, source, target, VERSION, () -> {})).isEqualTo(receipt);
        assertThat(target.resolve("source-content/requirements.txt")).hasContent("Retain Ünicode and original\nbytes\n");
        assertThat(target.resolve("search-index/segment")).hasBinaryContent(new byte[]{0, 1, 2, -1});
        assertThat(target.resolve("empty")).isDirectory();
        Files.writeString(target.resolve("source-content/requirements.txt"), "target edit");
        assertThat(source.resolve("source-content/requirements.txt")).hasContent("Retain Ünicode and original\nbytes\n");
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(target)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(target.resolve("search-index/segment"))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test void databaseApisAndGenericCliRejectFilesOnlyProofsAndCannotInventANewDatabaseFamily() throws Exception {
        Path source = filesSource(), snapshot = temporary.resolve("files-snapshot"), target = temporary.resolve("target");
        NativeInstallationSnapshot.captureFiles(source, snapshot, VERSION, () -> {});
        assertThatThrownBy(() -> NativeInstallationSnapshot.verify(snapshot, VERSION, () -> {})).isInstanceOf(IOException.class);
        for (var family : NativeInstallationSnapshot.DatabaseFamily.values()) {
            assertThatThrownBy(() -> NativeInstallationSnapshot.verify(snapshot, VERSION, family, () -> {})).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> NativeInstallationSnapshot.capture(source, temporary.resolve(family.name()), VERSION, family, () -> {}))
                    .isInstanceOf(IOException.class);
        }
        assertThat(NativeInstallationSnapshot.DatabaseFamily.values()).containsExactly(
                NativeInstallationSnapshot.DatabaseFamily.HSQLDB, NativeInstallationSnapshot.DatabaseFamily.POSTGRESQL);
        assertThatThrownBy(() -> NativeInstallationSnapshot.plan(snapshot, source, target, VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, source, target, VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeInstallationSnapshot.resume(snapshot, source, target, VERSION, () -> {})).isInstanceOf(IOException.class);
        for (String operation : List.of("verify", "plan", "restore", "resume")) assertThat(command(operation, source, snapshot, target)).isNotZero();
        var output = new java.io.ByteArrayOutputStream(); var stream = new java.io.PrintStream(output);
        Path invalid = temporary.resolve("invented-family");
        assertThat(TaxonomyTooling.run(new String[]{"native-installation", "capture", "--source", source.toString(),
                "--snapshot", invalid.toString(), "--version", VERSION, "--writers-stopped", "true", "--database-family", "FILES_ONLY"},
                temporary, stream, stream)).isNotZero();
        assertThat(invalid).doesNotExist(); assertThat(target).doesNotExist();
        Path filesTarget = temporary.resolve("existing-files-target");
        NativeInstallationSnapshot.restoreFiles(snapshot, source, filesTarget, VERSION, () -> {});
        assertThatThrownBy(() -> NativeInstallationSnapshot.resume(snapshot, source, filesTarget, VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThat(command("resume", source, snapshot, filesTarget)).isNotZero();

        Path databaseSnapshot = temporary.resolve("hsql-snapshot");
        NativeInstallationSnapshot.capture(source(), databaseSnapshot, VERSION, () -> {});
        assertThatThrownBy(() -> NativeInstallationSnapshot.verifyFiles(databaseSnapshot, VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeInstallationSnapshot.restoreFiles(databaseSnapshot, source, target, VERSION, () -> {})).isInstanceOf(IOException.class);
    }

    @Test void filesOnlyProofRejectsTamperingAndWrongVersionsBeforePublishingAnyTarget() throws Exception {
        for (String fault : List.of("changed", "missing", "extra", "traversal", "version")) {
            Path source = filesSource(), snapshot = temporary.resolve(fault + "-snapshot"), target = temporary.resolve(fault + "-target");
            NativeInstallationSnapshot.captureFiles(source, snapshot, VERSION, () -> {});
            Path bytes = snapshot.resolve("data/search-index/segment");
            switch (fault) {
                case "changed" -> Files.write(bytes, new byte[]{0, 1, 2, 3});
                case "missing" -> Files.delete(bytes);
                case "extra" -> Files.writeString(snapshot.resolve("data/unlisted"), "unexpected");
                case "traversal" -> Files.writeString(snapshot.resolve("manifest.json"), Files.readString(snapshot.resolve("manifest.json")).replace("search-index/segment", "../escape"));
            }
            String version = fault.equals("version") ? "different-version" : VERSION;
            assertThatThrownBy(() -> NativeInstallationSnapshot.verifyFiles(snapshot, version, () -> {})).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> NativeInstallationSnapshot.restoreFiles(snapshot, source, target, version, () -> {})).isInstanceOf(IOException.class);
            assertThat(target).doesNotExist();
            assertThat(source.resolve("search-index/segment")).hasBinaryContent(new byte[]{0, 1, 2, -1});
        }
    }

    @Test void filesOnlyProofPreservesIsolationSymlinkAndNewDestinationGuards() throws Exception {
        Path source = filesSource(), snapshot = temporary.resolve("files-snapshot");
        NativeInstallationSnapshot.captureFiles(source, snapshot, VERSION, () -> {});
        Path occupied = Files.createDirectory(temporary.resolve("occupied")); Files.writeString(occupied.resolve("keep"), "unchanged");
        for (Path target : List.of(source, source.resolve("child"), snapshot.resolve("nested"), temporary, occupied)) {
            assertThatThrownBy(() -> NativeInstallationSnapshot.restoreFiles(snapshot, source, target, VERSION, () -> {})).isInstanceOf(IOException.class);
        }
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, source);
        assertThatThrownBy(() -> NativeInstallationSnapshot.captureFiles(alias, temporary.resolve("alias-snapshot"), VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeInstallationSnapshot.restoreFiles(snapshot, source, alias.resolve("child"), VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeInstallationSnapshot.restoreFiles(snapshot, source, alias.resolve("../target"), VERSION, () -> {})).isInstanceOf(IOException.class);
        Files.createSymbolicLink(snapshot.resolve("data/link"), source.resolve("search-index/segment"));
        assertThatThrownBy(() -> NativeInstallationSnapshot.verifyFiles(snapshot, VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThat(occupied.resolve("keep")).hasContent("unchanged");
        assertThat(temporary.resolve("target")).doesNotExist();
    }

    @Test void filesOnlyOperationsRefuseLiveSourceLocksEvenAfterCapture() throws Exception {
        Path source = filesSource(), snapshot = temporary.resolve("files-snapshot"), target = temporary.resolve("target");
        NativeInstallationSnapshot.captureFiles(source, snapshot, VERSION, () -> {});
        Path locks = Files.createDirectory(source.resolve("locks")); Files.writeString(locks.resolve("database.lck"), "writer");
        assertThatThrownBy(() -> NativeInstallationSnapshot.captureFiles(source, temporary.resolve("live-snapshot"), VERSION, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("lock");
        assertThatThrownBy(() -> NativeInstallationSnapshot.restoreFiles(snapshot, source, target, VERSION, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("lock");
        assertThat(NativeInstallationSnapshot.verifyFiles(snapshot, VERSION, () -> {}).fileCount()).isEqualTo(2);
        assertThat(temporary.resolve("live-snapshot")).doesNotExist(); assertThat(target).doesNotExist();
    }

    @Test void filesOnlyCancellationRemovesCaptureAndRestoreStagesWithoutPublishing() throws Exception {
        Path source = filesSource(), snapshot = temporary.resolve("files-snapshot"), target = temporary.resolve("target");
        NativeInstallationSnapshot.Checkpoint cancel = () -> {
            try (var paths = Files.list(temporary)) {
                if (paths.anyMatch(path -> path.getFileName().toString().startsWith(".native-recovery-")))
                    throw new java.io.InterruptedIOException("cancelled during staged copy");
            }
        };
        assertThatThrownBy(() -> NativeInstallationSnapshot.captureFiles(source, snapshot, VERSION, cancel))
                .isInstanceOf(java.io.InterruptedIOException.class);
        assertThat(snapshot).doesNotExist();
        assertNoStages();
        NativeInstallationSnapshot.captureFiles(source, snapshot, VERSION, () -> {});
        assertThatThrownBy(() -> NativeInstallationSnapshot.restoreFiles(snapshot, source, target, VERSION, cancel))
                .isInstanceOf(java.io.InterruptedIOException.class);
        assertThat(target).doesNotExist();
        assertNoStages();
        assertThat(NativeInstallationSnapshot.verifyFiles(snapshot, VERSION, () -> {}).fileCount()).isEqualTo(2);
        assertThat(source.resolve("search-index/segment")).hasBinaryContent(new byte[]{0, 1, 2, -1});
    }

    private void assertNoStages() throws IOException {
        try (var paths = Files.list(temporary)) {
            assertThat(paths.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".native-recovery-"));
        }
    }

    private Path filesSource() throws IOException {
        Path root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Files.createDirectory(root.resolve("source-content"));
        Files.writeString(root.resolve("source-content/requirements.txt"), "Retain Ünicode and original\nbytes\n");
        Files.createDirectory(root.resolve("search-index"));
        Files.write(root.resolve("search-index/segment"), new byte[]{0, 1, 2, -1});
        Files.createDirectory(root.resolve("empty"));
        return root;
    }

    @Test void capturesEveryByteAndRestoresIndependentFilesWithAnExactManifest() throws Exception {
        Path source = source(), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("restored");
        var captured = NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        assertThat(captured.fileCount()).isEqualTo(3);
        assertThat(Files.readString(snapshot.resolve("manifest.json"))).contains("HSQLDB", VERSION, "source-content/requirements.txt");
        assertThat(NativeInstallationSnapshot.verify(snapshot, VERSION, () -> {}).sha256()).isEqualTo(captured.sha256());
        NativeInstallationSnapshot.restore(snapshot, source, target, VERSION, () -> {});
        assertThat(Files.readAllBytes(target.resolve("taxonomydb.data"))).containsExactly(0, 1, 2, -1);
        assertThat(Files.readString(target.resolve("source-content/requirements.txt"))).isEqualTo("Retain Ünicode and original\nbytes\n");
        assertThat(Files.isDirectory(target.resolve("empty"))).isTrue();
        Files.writeString(target.resolve("source-content/requirements.txt"), "target edit");
        assertThat(Files.readString(source.resolve("source-content/requirements.txt"))).startsWith("Retain Ünicode");
    }

    @Test void refusesChangedMissingAndUnexpectedSnapshotFilesBeforeCreatingTarget() throws Exception {
        for (String fault : List.of("changed", "missing", "extra", "traversal")) {
            Path source = source(), snapshot = temporary.resolve(UUID.randomUUID().toString()), target = temporary.resolve(UUID.randomUUID().toString());
            NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
            Path payload = snapshot.resolve("data/taxonomydb.data");
            switch (fault) {
                case "changed" -> Files.write(payload, new byte[]{0, 1, 2, 3});
                case "missing" -> Files.delete(payload);
                case "extra" -> Files.writeString(snapshot.resolve("data/unlisted"), "unexpected");
                case "traversal" -> Files.writeString(snapshot.resolve("manifest.json"), Files.readString(snapshot.resolve("manifest.json")).replace("taxonomydb.data", "../escape.data"));
            }
            assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, source, target, VERSION, () -> {})).isInstanceOf(IOException.class);
            assertThat(target).doesNotExist();
            assertThat(source.resolve("taxonomydb.data")).hasBinaryContent(new byte[]{0, 1, 2, -1});
        }
    }

    @Test void refusesExistingTargetsAndOverlappingOrSymlinkedPaths() throws Exception {
        Path source = source(), snapshot = temporary.resolve("snapshot");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        Path existing = Files.createDirectory(temporary.resolve("occupied")); Files.writeString(existing.resolve("keep"), "unchanged");
        for (Path target : List.of(source, source.resolve("child"), temporary, snapshot.resolve("nested"), existing))
            assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, source, target, VERSION, () -> {})).isInstanceOf(IOException.class);
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, source);
        assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, source, alias.resolve("child"), VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThat(existing.resolve("keep")).hasContent("unchanged");
    }

    @Test void refusesSymlinkPayloadsAndAnActiveHsqlLock() throws Exception {
        Path source = source(); Files.createSymbolicLink(source.resolve("link"), source.resolve("taxonomydb.data"));
        assertThatThrownBy(() -> NativeInstallationSnapshot.capture(source, temporary.resolve("symlink-snapshot"), VERSION, () -> {})).isInstanceOf(IOException.class);
        Files.delete(source.resolve("link")); Files.writeString(source.resolve("taxonomydb.lck"), "writer");
        assertThatThrownBy(() -> NativeInstallationSnapshot.capture(source, temporary.resolve("live-snapshot"), VERSION, () -> {})).isInstanceOf(IOException.class);
        assertThat(temporary.resolve("symlink-snapshot")).doesNotExist();
        assertThat(temporary.resolve("live-snapshot")).doesNotExist();
    }

    @Test void refusesSourceSymlinksBeforeParentTraversalCanEraseThem() throws Exception {
        Path caller = Files.createDirectory(temporary.resolve("caller"));
        Path actual = Files.createDirectory(temporary.resolve("actual"));
        source(caller.resolve("data"));
        Path intended = source(actual.resolve("data"));
        Files.writeString(intended.resolve("taxonomydb.data"), "resolved intended source");
        Files.createSymbolicLink(caller.resolve("alias"), Files.createDirectory(actual.resolve("child")));
        Path supplied = caller.resolve("alias/../data"), snapshot = temporary.resolve("snapshot");
        assertThat(Files.readString(supplied.resolve("taxonomydb.data"))).isEqualTo("resolved intended source");

        assertThatThrownBy(() -> NativeInstallationSnapshot.capture(supplied, snapshot, VERSION, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("Symbolic links");
        assertThat(snapshot).doesNotExist();
    }

    @Test void refusesSourceSymlinksExposedByNormalizingAMissingPrefix() throws Exception {
        Path actual = Files.createDirectory(temporary.resolve("actual"));
        Path source = source(actual.resolve("data"));
        Files.createSymbolicLink(temporary.resolve("alias"), actual);
        Path supplied = temporary.resolve("missing/../alias/data"), snapshot = temporary.resolve("snapshot");
        assertThat(Files.exists(supplied)).isFalse();
        assertThat(Files.isSymbolicLink(supplied.normalize().getParent())).isTrue();

        assertThatThrownBy(() -> NativeInstallationSnapshot.capture(supplied, snapshot, VERSION, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("Symbolic links");
        assertThat(snapshot).doesNotExist();
        assertThat(source.resolve("taxonomydb.data")).hasBinaryContent(new byte[]{0, 1, 2, -1});
    }

    @Test void refusesDestinationSymlinksBeforeParentTraversalCanEraseThem() throws Exception {
        Path source = source(), actual = Files.createDirectory(temporary.resolve("actual"));
        Files.createSymbolicLink(temporary.resolve("alias"), Files.createDirectory(actual.resolve("child")));
        Path suppliedSnapshot = temporary.resolve("alias/../snapshot");
        assertThatThrownBy(() -> NativeInstallationSnapshot.capture(source, suppliedSnapshot, VERSION, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("Symbolic links");
        assertThat(temporary.resolve("snapshot")).doesNotExist();
        assertThat(actual.resolve("snapshot")).doesNotExist();

        Path snapshot = temporary.resolve("verified-snapshot"), suppliedTarget = temporary.resolve("alias/../target");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, source, suppliedTarget, VERSION, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("Symbolic links");
        assertThat(temporary.resolve("target")).doesNotExist();
        assertThat(actual.resolve("target")).doesNotExist();
    }

    @Test void toolingRestoresFromSnapshotAfterTheOriginalVolumeWasLost() throws Exception {
        Path volume = Files.createDirectory(temporary.resolve("original-volume"));
        Path source = source(volume.resolve("data")), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("restored");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        Files.move(volume, temporary.resolve("detached-volume"));
        assertThat(source).doesNotExist();
        var out = new java.io.ByteArrayOutputStream(); var stream = new java.io.PrintStream(out);
        String[] arguments = {"native-installation", "restore", "--source", source.toString(), "--snapshot", snapshot.toString(), "--target", target.toString(), "--version", VERSION};
        assertThat(TaxonomyTooling.run(arguments, temporary, stream, stream)).isNotZero();
        assertThat(target).doesNotExist();
        var acknowledged = new ArrayList<>(List.of(arguments)); acknowledged.addAll(List.of("--writers-stopped", "true"));

        assertThat(TaxonomyTooling.run(acknowledged.toArray(String[]::new), temporary, stream, stream)).as(out.toString()).isZero();
        assertThat(target.resolve("taxonomydb.data")).hasBinaryContent(new byte[]{0, 1, 2, -1});
        assertThat(target.resolve("source-content/requirements.txt")).hasContent("Retain Ünicode and original\nbytes\n");
        assertThat(source).doesNotExist();
    }

    @Test void lostOriginalPathStillRejectsOverlapSymlinksAndNondirectoryAncestors() throws Exception {
        Path source = source(), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("lost");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        for (Path original : List.of(target, target.resolve("data")))
            assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, original, target, VERSION, () -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("isolated");
        Path existingFile = Files.writeString(temporary.resolve("not-a-directory"), "keep");
        for (Path original : List.of(existingFile, existingFile.resolve("data")))
            assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, original, target, VERSION, () -> {}))
                    .isInstanceOf(IOException.class);
        Files.createSymbolicLink(temporary.resolve("alias"), Files.createDirectory(temporary.resolve("other")));
        for (Path original : List.of(temporary.resolve("alias/missing"), temporary.resolve("alias/../missing")))
            assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, original, target, VERSION, () -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("Symbolic links");
        assertThat(target).doesNotExist();
        assertThat(existingFile).hasContent("keep");
    }

    @Test void toolingRefusesExistingOriginalLocksEvenWithStoppedWriterAcknowledgement() throws Exception {
        for (String relativeLock : List.of("taxonomydb.lck", "nested/taxonomydb.lck")) {
            Path source = source(), snapshot = temporary.resolve(UUID.randomUUID().toString()), target = temporary.resolve(UUID.randomUUID().toString());
            NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
            Path lock = source.resolve(relativeLock); Files.createDirectories(lock.getParent()); Files.writeString(lock, "active writer");
            var out = new java.io.ByteArrayOutputStream(); var stream = new java.io.PrintStream(out);

            assertThat(TaxonomyTooling.run(new String[]{"native-installation", "restore", "--source", source.toString(),
                    "--snapshot", snapshot.toString(), "--target", target.toString(), "--version", VERSION, "--writers-stopped", "true"},
                    temporary, stream, stream)).as(out.toString()).isNotZero();
            assertThat(out.toString()).contains("HSQL lock");
            assertThat(target).doesNotExist();
            assertThat(lock).hasContent("active writer");
            assertThat(NativeInstallationSnapshot.verify(snapshot, VERSION, () -> {}).fileCount()).isEqualTo(3);
        }
    }

    @Test void interruptionCannotPublishOrOverwriteAnInstallation() throws Exception {
        Path source = source(), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, source, target, VERSION, () -> {
            if (counter.incrementAndGet() == 16) throw new java.io.InterruptedIOException("cancelled");
        })).isInstanceOf(java.io.InterruptedIOException.class);
        assertThat(target).doesNotExist();
        try (var files = Files.list(temporary)) { assertThat(files.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".native-recovery-")); }
        assertThat(source.resolve("taxonomydb.data")).hasBinaryContent(new byte[]{0, 1, 2, -1});
    }

    @Test void requiresExactApplicationVersionAndExplicitStoppedWriterAcknowledgement() throws Exception {
        Path source = source(), snapshot = temporary.resolve("snapshot");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        assertThatThrownBy(() -> NativeInstallationSnapshot.restore(snapshot, source, temporary.resolve("wrong-version"), "different-version", () -> {})).isInstanceOf(IOException.class);
        var out = new java.io.ByteArrayOutputStream();
        assertThat(TaxonomyTooling.run(new String[]{"native-installation", "capture", "--source", source.toString(), "--snapshot", temporary.resolve("no-ack").toString(), "--version", VERSION}, temporary, new java.io.PrintStream(out), new java.io.PrintStream(out))).isNotZero();
        assertThat(temporary.resolve("no-ack")).doesNotExist();
    }

    @Test void existingToolingCommandsProduceAnIsolatedPrivateRestore() throws Exception {
        Path source = source(), snapshot = temporary.resolve("operator-snapshot"), target = temporary.resolve("operator-target");
        var out = new java.io.ByteArrayOutputStream(); var stream = new java.io.PrintStream(out);
        for (String[] command : List.of(
                new String[]{"native-installation", "capture", "--source", source.toString(), "--snapshot", snapshot.toString(), "--version", VERSION, "--writers-stopped", "true"},
                new String[]{"native-installation", "verify", "--snapshot", snapshot.toString(), "--version", VERSION},
                new String[]{"native-installation", "restore", "--source", source.toString(), "--snapshot", snapshot.toString(), "--target", target.toString(), "--version", VERSION, "--writers-stopped", "true"}))
            assertThat(TaxonomyTooling.run(command, temporary, stream, stream)).as(out.toString()).isZero();
        assertThat(target.resolve("taxonomydb.data")).hasBinaryContent(new byte[]{0, 1, 2, -1});
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(target)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(target.resolve("taxonomydb.data"))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test void restorePlanChecksAllBytesAndPathsWithoutCreatingAnyTarget() throws Exception {
        Path source = source(), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        assertThat(command("plan", source, snapshot, target)).isZero();
        assertThat(target).doesNotExist();
        Files.writeString(snapshot.resolve("data/source-content/requirements.txt"), "changed");
        assertThat(command("plan", source, snapshot, target)).isNotZero();
        assertThat(target).doesNotExist();
        assertThat(source.resolve("source-content/requirements.txt")).hasContent("Retain Ünicode and original\nbytes\n");
    }

    @Test void resumePublishesOnceAndRefusesToMergeChangedOrExtraTargetFiles() throws Exception {
        Path source = source(), snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        NativeInstallationSnapshot.capture(source, snapshot, VERSION, () -> {});
        assertThat(command("resume", source, snapshot, target)).isZero();
        var modified = Files.getLastModifiedTime(target.resolve("taxonomydb.data"));
        assertThat(command("resume", source, snapshot, target)).isZero();
        assertThat(Files.getLastModifiedTime(target.resolve("taxonomydb.data"))).isEqualTo(modified);
        Files.writeString(target.resolve("extra"), "must survive");
        assertThat(command("resume", source, snapshot, target)).isNotZero();
        assertThat(target.resolve("extra")).hasContent("must survive");
        Files.delete(target.resolve("extra"));
        Files.writeString(target.resolve("taxonomydb.data"), "application changed this");
        assertThat(command("resume", source, snapshot, target)).isNotZero();
        assertThat(target.resolve("taxonomydb.data")).hasContent("application changed this");
    }

    @Test void postgresBundleBindsExplicitFamilyAndPreservesDumpAndExternalContent() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("postgres"));
        // Format marker only: this tooling unit fixture does not claim pg_restore acceptance.
        byte[] dump = "PGDMPfixture-format-marker-only".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source.resolve("database.dump"), dump);
        Files.createDirectory(source.resolve("source-content"));
        Files.writeString(source.resolve("source-content/evidence.txt"), "original evidence");
        Path snapshot = temporary.resolve("snapshot"), target = temporary.resolve("target");
        var output = new java.io.ByteArrayOutputStream(); var stream = new java.io.PrintStream(output);
        assertThat(TaxonomyTooling.run(new String[]{"native-installation", "capture", "--source", source.toString(),
                "--snapshot", snapshot.toString(), "--version", VERSION, "--writers-stopped", "true", "--database-family", "POSTGRESQL"}, temporary, stream, stream))
                .as(output.toString()).isZero();
        assertThat(Files.readString(snapshot.resolve("manifest.json"))).contains("POSTGRESQL", "taxonomy-native-postgres-bundle-v1");
        assertThat(command("plan", source, snapshot, target)).isZero();
        assertThat(command("resume", source, snapshot, target)).isZero();
        assertThat(target.resolve("database.dump")).hasBinaryContent(dump);
        assertThat(target.resolve("source-content/evidence.txt")).hasContent("original evidence");
        assertThat(command("resume", source, snapshot, target)).isZero();
    }

    @Test void postgresCaptureRejectsPlainSqlMissingDumpAndRawClusterDirectories() throws Exception {
        for (String fault : List.of("missing", "plain-sql", "raw-cluster")) {
            Path source = Files.createDirectory(temporary.resolve(fault)), snapshot = temporary.resolve(fault + "-snapshot");
            if (!fault.equals("missing")) Files.writeString(source.resolve("database.dump"), fault.equals("plain-sql") ? "CREATE TABLE secrets" : "PGDMPfixture");
            if (fault.equals("raw-cluster")) Files.writeString(source.resolve("PG_VERSION"), "16");
            var output = new java.io.ByteArrayOutputStream(); var stream = new java.io.PrintStream(output);
            assertThat(TaxonomyTooling.run(new String[]{"native-installation", "capture", "--source", source.toString(),
                    "--snapshot", snapshot.toString(), "--version", VERSION, "--writers-stopped", "true", "--database-family", "POSTGRESQL"}, temporary, stream, stream))
                    .isNotZero();
            assertThat(snapshot).doesNotExist();
        }
    }

    private int command(String operation, Path source, Path snapshot, Path target) {
        var output = new java.io.ByteArrayOutputStream(); var stream = new java.io.PrintStream(output);
        return TaxonomyTooling.run(new String[]{"native-installation", operation, "--source", source.toString(),
                "--snapshot", snapshot.toString(), "--target", target.toString(), "--version", VERSION,
                "--writers-stopped", "true"}, temporary, stream, stream);
    }

    private Path source() throws Exception {
        return source(temporary.resolve(UUID.randomUUID().toString()));
    }

    private Path source(Path destination) throws Exception {
        Path root = Files.createDirectory(destination);
        Files.write(root.resolve("taxonomydb.data"), new byte[]{0, 1, 2, -1});
        Files.writeString(root.resolve("taxonomydb.properties"), "modified=no\n");
        Files.createDirectory(root.resolve("source-content"));
        Files.writeString(root.resolve("source-content/requirements.txt"), "Retain Ünicode and original\nbytes\n");
        Files.createDirectory(root.resolve("empty")); return root;
    }
}
