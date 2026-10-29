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
