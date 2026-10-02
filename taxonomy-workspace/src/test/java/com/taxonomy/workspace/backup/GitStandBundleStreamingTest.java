package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolates generator allocation from the storage provider and bounded TaxDSL projection. */
class GitStandBundleStreamingTest {
    @TempDir Path root;

    @Test void aBundleLargerThanTheGeneratorHeapRemainsAnOfflineUsableGitRepository() throws Exception {
        Path bundle = root.resolve("large.bundle"), log = root.resolve("generator.log"), arguments = root.resolve("java.args");
        Path agent = Path.of(Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(arguments, "-Xmx96m\n" + argument("-javaagent:" + agent) + "\n-cp\n" + argument(System.getProperty("java.class.path"))
                + "\n" + getClass().getName() + "\n" + argument(bundle.toString()) + "\n");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(log.toFile());
        // The child owns its explicit heap/agent; inherited JVM options must not change that ceiling.
        builder.environment().remove("JAVA_TOOL_OPTIONS"); builder.environment().remove("JDK_JAVA_OPTIONS");
        var child = builder.start();
        try {
            assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("bounded generator finished").isTrue();
            assertThat(child.exitValue()).as(Files.readString(log)).isZero();
            assertThat(Files.readString(log)).contains("STREAMED=201326592 COPIES=2");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
        assertThat(Files.size(bundle)).as("incompressible bundle exceeds the child heap").isGreaterThan(96L << 20);
        git("clone", "--bare", bundle.toString(), "offline.git"); git("-C", "offline.git", "fsck", "--full", "--strict");
        assertThat(git("-C", "offline.git", "cat-file", "-s", "HEAD:payload.bin")).isEqualTo("201326592\n");
        assertThat(git("-C", "offline.git", "rev-list", "--all", "--count")).isEqualTo("1\n");
    }

    /** Only the source port is synthetic. The graph, hashes, object loaders and JGit bundle writer are real. */
    public static void main(String[] args) throws Exception {
        long length = 192L << 20; byte[] buffer = new byte[8192]; var sha = MessageDigest.getInstance("SHA-256"); var random = new Random(42);
        for (long offset = 0; offset < length; offset += buffer.length) { random.nextBytes(buffer); sha.update(buffer); }
        var file = new GitStandBackupSource.File("payload.bin", 0100644, length, HexFormat.of().formatHex(sha.digest()));
        var source = mock(GitStandBackupSource.Stand.class); when(source.files()).thenReturn(List.of(file));
        var copies = new AtomicInteger(); Thread owner = Thread.currentThread();
        doAnswer(call -> {
            if (Thread.currentThread() != owner) throw new AssertionError("Blob producer escaped capture thread");
            OutputStream output = call.getArgument(1); BackupCheckpoint checkpoint = call.getArgument(2); copies.incrementAndGet(); var bytes = new Random(42);
            for (long offset = 0; offset < length; offset += buffer.length) { checkpoint.check(); bytes.nextBytes(buffer); output.write(buffer); }
            checkpoint.check(); return null;
        }).when(source).copy(eq(file), any(OutputStream.class), any(BackupCheckpoint.class));
        var bundle = GitStandBundle.capture(source, Instant.EPOCH, () -> { });
        var sink = new ComponentSink() {
            @Override public BackupEntry write(String path, InputStream input) { throw new AssertionError("Generated entry required"); }
            @Override public BackupEntry writeGenerated(String path, EntryWriter writer) throws IOException {
                var digest = digest(); long[] count = {0};
                try (var output = new DigestOutputStream(Files.newOutputStream(Path.of(args[0])), digest)) {
                    writer.write(new OutputStream() {
                        @Override public void write(int value) throws IOException { output.write(value); count[0]++; }
                        @Override public void write(byte[] bytes, int offset, int size) throws IOException { output.write(bytes, offset, size); count[0] += size; }
                        @Override public void flush() throws IOException { output.flush(); }
                    });
                }
                return new BackupEntry(path, count[0], HexFormat.of().formatHex(digest.digest()));
            }
        };
        bundle.write("repositories/stand/stand.bundle", sink);
        if (copies.get() != 2) throw new AssertionError("Expected one hashing pass and one streaming pass: " + copies.get());
        System.out.println("STREAMED=" + length + " COPIES=" + copies.get());
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String argument(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + root.resolve("no-hooks"))); command.addAll(List.of(args));
        Path out = Files.createTempFile(root, "git-", ".out"), err = Files.createTempFile(root, "git-", ".err"), config = root.resolve("empty-config"); Files.writeString(config, "");
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        var env = builder.environment(); env.keySet().removeIf(key -> key.startsWith("GIT_")); env.put("GIT_CONFIG_NOSYSTEM", "1"); env.put("GIT_CONFIG_GLOBAL", config.toString()); env.put("GIT_TERMINAL_PROMPT", "0");
        var process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("native Git finished").isTrue();
            assertThat(process.exitValue()).as(Files.readString(err)).isZero(); return Files.readString(out);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
