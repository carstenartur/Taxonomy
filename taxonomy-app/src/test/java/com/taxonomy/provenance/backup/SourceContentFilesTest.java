package com.taxonomy.provenance.backup;

import com.taxonomy.backup.BackupEntry;
import com.taxonomy.backup.ComponentSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class SourceContentFilesTest {
    @TempDir Path temporary;

    @Test void fallbackCopiesNestedBinaryContentWithItsExactLengthAndDigest() throws Exception {
        Path root = temporary.toRealPath();
        Path file = Files.createDirectories(root.resolve("originals/nested")).resolve("source.bin");
        byte[] content = new byte[]{0, 1, -1, 13, 10, 42};
        Files.write(file, content);
        var result = fallback(root, file, (name, input) -> {
            byte[] captured = input.readAllBytes();
            assertThat(captured).containsExactly(content);
            return entry(name, captured);
        });
        assertThat(result).isEqualTo(entry("content/source.bin", content));
        assertThat(Files.readAllBytes(file)).containsExactly(content);
    }

    @Test void fallbackRejectsDirectoriesAndSymlinkedAncestorsBeforeWriting() throws Exception {
        Path root = temporary.toRealPath();
        Path directory = Files.createDirectory(root.resolve("originals"));
        ComponentSink mustNotWrite = (name, input) -> { throw new AssertionError("Unsafe source reached output"); };
        assertThatThrownBy(() -> fallback(root, directory, mustNotWrite)).isInstanceOf(IOException.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "This symlink fixture requires POSIX link creation; the fallback copy tests are platform independent");
        Path file = Files.writeString(directory.resolve("source.txt"), "SOURCE");
        Path link = Files.createSymbolicLink(root.resolve("alias"), directory);
        assertThatThrownBy(() -> fallback(root, link.resolve("source.txt"), mustNotWrite))
                .isInstanceOf(IOException.class).hasMessageContaining("links");
        assertThat(Files.readString(file)).isEqualTo("SOURCE");
    }

    @Test void fallbackRejectsAFileThatChangesDuringTheCopy() throws Exception {
        Path root = temporary.toRealPath();
        Path file = Files.writeString(root.resolve("source.txt"), "BEFORE");
        assertThatThrownBy(() -> fallback(root, file, (name, input) -> {
            byte[] captured = input.readAllBytes();
            Files.writeString(file, "LONGER-AFTER");
            return entry(name, captured);
        })).isInstanceOf(IOException.class).hasMessageContaining("changed during capture");
        Files.writeString(file, "BEFORE");
        FileTime before = Files.getLastModifiedTime(file);
        assertThatThrownBy(() -> fallback(root, file, (name, input) -> {
            byte[] captured = input.readAllBytes();
            Files.writeString(file, "AFTER!");
            Files.setLastModifiedTime(file, FileTime.fromMillis(before.toMillis() + 10_000));
            return entry(name, captured);
        })).isInstanceOf(IOException.class).hasMessageContaining("changed during capture");
    }

    @Test void captureRejectsInvalidPathsAndRedactsProviderDiagnostics() throws Exception {
        Path root = temporary.toRealPath();
        var files = new SourceContentFiles(root);
        ComponentSink mustNotWrite = (name, input) -> { throw new AssertionError("Invalid source reached output"); };
        assertThat(files.capture(null, "content/a", null, false, mustNotWrite)).isNull();
        assertThat(files.capture(" ", "content/a", null, false, mustNotWrite)).isNull();
        assertThatThrownBy(() -> files.capture("invalid\u0000path", "content/a", null, false, mustNotWrite))
                .isInstanceOf(IOException.class).hasMessage("Source content cannot be captured on this storage provider");
        for (String path : new String[]{".", "../outside", "missing-file", "level/".repeat(33) + "source"}) {
            assertThatThrownBy(() -> files.capture(path, "content/a", null, false, mustNotWrite))
                    .isInstanceOf(IOException.class).hasMessage("Referenced source content is unavailable, changed or unsafe");
        }
        for (String digest : new String[]{null, "invalid"}) {
            assertThatThrownBy(() -> files.capture("source", "content/a", digest, true, mustNotWrite))
                    .isInstanceOf(IOException.class).hasMessage("Original source digest is unavailable");
        }
    }

    // Exercise the actual provider fallback even on Linux, whose default provider takes the secure branch.
    // This is not a substitute for the native Windows acceptance run.
    private static BackupEntry fallback(Path root, Path file, ComponentSink sink) throws Exception {
        var method = SourceContentFiles.class.getDeclaredMethod("checkedCopy", Path.class, String.class, ComponentSink.class);
        if (!method.trySetAccessible()) throw new AssertionError("Cannot exercise provider fallback");
        try { return (BackupEntry) method.invoke(new SourceContentFiles(root), file, "content/source.bin", sink); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static BackupEntry entry(String name, byte[] content) {
        try { return new BackupEntry(name, content.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
