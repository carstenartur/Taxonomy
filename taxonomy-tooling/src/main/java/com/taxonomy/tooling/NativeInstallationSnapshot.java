package com.taxonomy.tooling;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Native cold snapshot; no portable owner import or activation. */
public final class NativeInstallationSnapshot {
    private static final String FORMAT = "taxonomy-native-cold-hsql-v1";
    private static final String POSTGRES_FORMAT = "taxonomy-native-postgres-bundle-v1";
    public enum DatabaseFamily { HSQLDB, POSTGRESQL }
    private static final int MAX_ENTRIES = 100_000, MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;
    @FunctionalInterface public interface Checkpoint { void check() throws IOException; }
    public record Receipt(int fileCount, long bytes, String sha256) { }
    private record Entry(String path, long bytes, String sha256) { }
    private record Inventory(List<String> directories, List<Entry> files) { }
    private record Verified(Path root, Inventory inventory, Receipt receipt, DatabaseFamily family) { }
    private NativeInstallationSnapshot() { }

    public static Receipt capture(Path source, Path snapshot, String version, Checkpoint checkpoint) throws IOException {
        return capture(source, snapshot, version, DatabaseFamily.HSQLDB, checkpoint);
    }

    /** PostgreSQL input is a stopped filesystem bundle containing an operator-created custom dump. */
    public static Receipt capture(Path source, Path snapshot, String version, DatabaseFamily family, Checkpoint checkpoint) throws IOException {
        return capturePrepared(source, snapshot, version, family, ignored -> {}, checkpoint);
    }

    @FunctionalInterface interface Preparation { void prepare(Path data) throws IOException; }
    static Receipt capturePrepared(Path source, Path snapshot, String version, DatabaseFamily family,
                                   Preparation preparation, Checkpoint checkpoint) throws IOException {
        requireVersion(version); Objects.requireNonNull(family); source = directory(source); snapshot = newDestination(snapshot);
        separate(source, snapshot); checkpoint.check();
        Inventory expected = inventory(source, checkpoint);
        Path stage = Files.createTempDirectory(snapshot.getParent(), ".native-recovery-");
        try {
            copy(source, stage.resolve("data"), expected, checkpoint);
            preparation.prepare(stage.resolve("data"));
            if (!expected.equals(inventory(source, checkpoint))) throw new IOException("Stopped source changed during capture");
            expected = inventory(stage.resolve("data"), checkpoint);
            requireDatabasePayload(stage.resolve("data"), expected, family);
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("format", family == DatabaseFamily.HSQLDB ? FORMAT : POSTGRES_FORMAT); manifest.put("databaseFamily", family.name()); manifest.put("applicationVersion", version);
            manifest.put("directories", expected.directories());
            manifest.put("files", expected.files().stream().map(entry -> Map.of("path", entry.path(), "bytes", entry.bytes(), "sha256", entry.sha256())).toList());
            byte[] bytes = FlatJson.pretty(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Native snapshot manifest exceeds its bound");
            privateFile(stage.resolve("manifest.json")); Files.write(stage.resolve("manifest.json"), bytes, WRITE); force(stage.resolve("manifest.json"));
            Receipt receipt = verify(stage, version, checkpoint);
            publish(stage, snapshot, checkpoint); return receipt;
        } finally { removeStage(stage); }
    }

    public static Receipt verify(Path snapshot, String version, Checkpoint checkpoint) throws IOException {
        return verified(snapshot, version, checkpoint).receipt();
    }

    public static Receipt verify(Path snapshot, String version, DatabaseFamily family, Checkpoint checkpoint) throws IOException {
        var verified = verified(snapshot, version, checkpoint); requireFamily(verified, family); return verified.receipt();
    }

    public static Receipt restore(Path snapshot, Path active, Path target, String version, Checkpoint checkpoint) throws IOException {
        snapshot = directory(snapshot); active = originalSource(active); target = newDestination(target);
        separate(active, target); separate(snapshot, target); separate(active, snapshot);
        requireStoppedSource(active, checkpoint);
        Verified verified = verified(snapshot, version, checkpoint);
        Path stage = Files.createTempDirectory(target.getParent(), ".native-recovery-");
        try {
            Path data = stage.resolve("data"); copy(verified.root().resolve("data"), data, verified.inventory(), checkpoint);
            if (!verified.inventory().equals(inventory(data, checkpoint))) throw new IOException("Restored file inventory changed");
            publish(data, target, checkpoint); return verified.receipt();
        } finally { removeStage(stage); }
    }

    /** Read-only inspection of the complete restore inputs and an absent destination. */
    public static Receipt plan(Path snapshot, Path active, Path target, String version, Checkpoint checkpoint) throws IOException {
        return plan(snapshot, active, target, version, null, checkpoint);
    }

    static Receipt plan(Path snapshot, Path active, Path target, String version, DatabaseFamily family, Checkpoint checkpoint) throws IOException {
        snapshot = directory(snapshot); active = originalSource(active); target = newDestination(target);
        separate(active, target); separate(snapshot, target); separate(active, snapshot);
        requireStoppedSource(active, checkpoint);
        var verified = verified(snapshot, version, checkpoint); requireFamily(verified, family); return verified.receipt();
    }

    private static void requireFamily(Verified verified, DatabaseFamily expected) throws IOException {
        if (expected != null && verified.family() != expected) throw new IOException("Native snapshot database family differs from the requested operation");
    }

    /** A publication retry never merges files: only an exactly matching, still-cold target is accepted. */
    public static Receipt resume(Path snapshot, Path active, Path target, String version, Checkpoint checkpoint) throws IOException {
        snapshot = directory(snapshot); active = originalSource(active);
        Path resolved = originalSource(target);
        separate(active, resolved); separate(snapshot, resolved); separate(active, snapshot);
        requireStoppedSource(active, checkpoint);
        if (Files.notExists(resolved, NOFOLLOW_LINKS)) return restore(snapshot, active, resolved, version, checkpoint);
        var expected = verified(snapshot, version, checkpoint);
        if (!expected.inventory().equals(inventory(directory(resolved), checkpoint)))
            throw new IOException("Existing target differs from snapshot; it must never be merged or overwritten");
        return expected.receipt();
    }

    private static void requireDatabasePayload(Path root, Inventory expected, DatabaseFamily family) throws IOException {
        if (family == DatabaseFamily.HSQLDB) {
            if (expected.files().stream().noneMatch(entry -> entry.path().endsWith(".properties")))
                throw new IOException("No HSQL properties file in the configured data root");
        } else {
            if (expected.files().stream().anyMatch(entry -> entry.path().equals("PG_VERSION") || entry.path().endsWith("/PG_VERSION")))
                throw new IOException("Raw PostgreSQL cluster directories are not supported; supply a custom-format logical dump");
            Path dump = root.resolve("database.dump");
            if (!Files.isRegularFile(dump, NOFOLLOW_LINKS)) throw new IOException("PostgreSQL bundle requires database.dump");
            try (var input = Files.newInputStream(dump, READ, NOFOLLOW_LINKS)) {
                if (!Arrays.equals(input.readNBytes(5), new byte[]{'P', 'G', 'D', 'M', 'P'}))
                    throw new IOException("PostgreSQL bundle requires a custom-format pg_dump archive");
            }
        }
    }

    private static Verified verified(Path snapshot, String version, Checkpoint checkpoint) throws IOException {
        requireVersion(version); Path root = directory(snapshot); checkpoint.check();
        try (var children = Files.list(root)) {
            if (!children.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("data", "manifest.json")))
                throw new IOException("Unexpected or missing snapshot entry");
        }
        Path manifestPath = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath, NOFOLLOW_LINKS) || Files.size(manifestPath) > MAX_MANIFEST_BYTES)
            throw new IOException("Invalid native snapshot manifest");
        byte[] bytes;
        try (var input = Files.newInputStream(manifestPath, READ, NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        }
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Native snapshot manifest exceeds its bound");
        try {
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8); requireBoundedJson(text);
            var manifest = FlatJson.parseObject(text);
            if (!manifest.keySet().equals(Set.of("format", "databaseFamily", "applicationVersion", "directories", "files"))
                    || !(FORMAT.equals(manifest.get("format")) && "HSQLDB".equals(manifest.get("databaseFamily"))
                         || POSTGRES_FORMAT.equals(manifest.get("format")) && "POSTGRESQL".equals(manifest.get("databaseFamily")))
                    || !version.equals(manifest.get("applicationVersion")))
                throw new IOException("Snapshot format or application version differs");
            if (!(manifest.get("directories") instanceof List<?> directories) || !(manifest.get("files") instanceof List<?> files)
                    || directories.size() + files.size() > MAX_ENTRIES) throw new IOException("Invalid snapshot inventory");
            var seen = new HashSet<String>(); var directoryNames = new ArrayList<String>(); var entries = new ArrayList<Entry>(); long total = 0;
            for (Object directory : directories) { checkpoint.check(); String path = path(directory); if (!seen.add(path)) throw new IOException("Duplicate snapshot path"); directoryNames.add(path); }
            for (Object file : files) {
                checkpoint.check();
                if (!(file instanceof Map<?, ?> fields) || !fields.keySet().equals(Set.of("path", "bytes", "sha256"))) throw new IOException("Invalid snapshot file");
                String path = path(fields.get("path"));
                if (!seen.add(path) || !(fields.get("sha256") instanceof String hash) || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid snapshot file proof");
                String length = String.valueOf(fields.get("bytes")); if (!length.matches("0|[1-9][0-9]{0,11}")) throw new IOException("Invalid snapshot byte length");
                long size = Long.parseLong(length); total = Math.addExact(total, size);
                if (total > MAX_BYTES) throw new IOException("Native snapshot exceeds its byte bound");
                entries.add(new Entry(path, size, hash));
            }
            directoryNames.sort(String::compareTo); entries.sort(Comparator.comparing(Entry::path));
            Inventory expected = new Inventory(List.copyOf(directoryNames), List.copyOf(entries));
            if (!expected.equals(inventory(directory(root.resolve("data")), checkpoint))) throw new IOException("Snapshot bytes or inventory differ from manifest");
            requireDatabasePayload(root.resolve("data"), expected, DatabaseFamily.valueOf((String) manifest.get("databaseFamily")));
            return new Verified(root, expected, new Receipt(entries.size(), total, hash(bytes)), DatabaseFamily.valueOf((String) manifest.get("databaseFamily")));
        } catch (IllegalArgumentException | ArithmeticException failure) { throw new IOException("Invalid native snapshot manifest", failure); }
    }

    private static Inventory inventory(Path root, Checkpoint checkpoint) throws IOException {
        var directories = new ArrayList<String>(); var files = new ArrayList<Entry>(); long[] total = {0};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            private void bound() throws IOException { checkpoint.check(); if (directories.size() + files.size() >= MAX_ENTRIES) throw new IOException("Native snapshot entry bound exceeded"); }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                bound(); if (!dir.equals(root)) directories.add(path(root.relativize(dir).toString().replace(java.io.File.separatorChar, '/'))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes before) throws IOException {
                bound(); if (!before.isRegularFile() || file.getFileName().toString().endsWith(".lck")) throw new IOException("Unsupported file or active HSQL lock; stop every writer first");
                String relative = path(root.relativize(file).toString().replace(java.io.File.separatorChar, '/'));
                if (before.size() > MAX_BYTES - total[0]) throw new IOException("Native snapshot byte bound exceeded");
                String digest = digest(file, before.size(), checkpoint);
                var after = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("Snapshot input changed during read");
                total[0] += before.size(); files.add(new Entry(relative, before.size(), digest)); return FileVisitResult.CONTINUE;
            }
        });
        directories.sort(String::compareTo); files.sort(Comparator.comparing(Entry::path)); return new Inventory(List.copyOf(directories), List.copyOf(files));
    }

    private static void copy(Path source, Path target, Inventory expected, Checkpoint checkpoint) throws IOException {
        privateDirectory(target);
        for (String directory : expected.directories()) { checkpoint.check(); Files.createDirectories(target.resolve(directory)); }
        for (Entry entry : expected.files()) {
            checkpoint.check(); Path input = source.resolve(entry.path()), output = target.resolve(entry.path());
            noSymlinks(input); var digest = sha256(); long count = 0;
            privateFile(output);
            try (var in = Files.newInputStream(input, READ, NOFOLLOW_LINKS); var out = Files.newOutputStream(output, WRITE, NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = in.read(buffer)) >= 0) {
                    checkpoint.check(); count += read; if (count > entry.bytes()) throw new IOException("Snapshot input grew during copy");
                    digest.update(buffer, 0, read); out.write(buffer, 0, read);
                }
            }
            if (count != entry.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256())) throw new IOException("Snapshot input differs during copy");
            force(output);
        }
    }

    private static String digest(Path file, long expected, Checkpoint checkpoint) throws IOException {
        var digest = sha256(); long count = 0;
        try (var in = Files.newInputStream(file, READ, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = in.read(buffer)) >= 0) { checkpoint.check(); count += read; if (count > expected) throw new IOException("Snapshot input grew"); digest.update(buffer, 0, read); }
        }
        if (count != expected) throw new IOException("Snapshot input shortened"); return HexFormat.of().formatHex(digest.digest());
    }

    static Path directory(Path path) throws IOException {
        path = path.toAbsolutePath(); noSymlinks(path); path = path.normalize(); noSymlinks(path);
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw new IOException("A configured directory is missing"); return path.toRealPath();
    }
    static Path newDestination(Path path) throws IOException {
        path = path.toAbsolutePath(); noSymlinks(path); path = path.normalize(); noSymlinks(path);
        if (path.getParent() == null || Files.exists(path, NOFOLLOW_LINKS)) throw new IOException("Destination must be new and absent");
        return directory(path.getParent()).resolve(path.getFileName());
    }
    private static Path originalSource(Path path) throws IOException {
        path = path.toAbsolutePath(); noSymlinks(path); path = path.normalize(); noSymlinks(path);
        Path ancestor = path;
        while (ancestor != null && Files.notExists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null) throw new IOException("Original source has no accessible directory ancestor");
        return directory(ancestor).resolve(ancestor.relativize(path));
    }
    private static void requireStoppedSource(Path source, Checkpoint checkpoint) throws IOException {
        if (Files.notExists(source, NOFOLLOW_LINKS)) return;
        Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            private int entries;
            private void check() throws IOException {
                checkpoint.check();
                if (++entries > MAX_ENTRIES) throw new IOException("Original source entry bound exceeded");
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(source)) checkpoint.check(); else check();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                check();
                if (!attrs.isRegularFile() || file.getFileName().toString().endsWith(".lck"))
                    throw new IOException("Unsupported file or active HSQL lock; stop every writer first");
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private static void noSymlinks(Path path) throws IOException {
        for (Path ancestor = path.toAbsolutePath(); ancestor != null; ancestor = ancestor.getParent()) if (Files.isSymbolicLink(ancestor)) throw new IOException("Symbolic links are not supported in native snapshot paths");
    }
    private static void separate(Path first, Path second) throws IOException { if (first.startsWith(second) || second.startsWith(first)) throw new IOException("Native recovery paths must be isolated"); }
    private static String path(Object value) throws IOException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 4096 || text.contains("\\") || text.contains(":") || text.chars().anyMatch(Character::isISOControl)
                || text.startsWith("/") || Arrays.stream(text.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) throw new IOException("Invalid relative snapshot path");
        return text;
    }
    private static void requireVersion(String version) throws IOException { if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")) throw new IOException("An exact application version is required"); }
    private static void publish(Path source, Path target, Checkpoint checkpoint) throws IOException {
        checkpoint.check(); newDestination(target);
        // The operator owns this private parent exclusively; never merge or use REPLACE_EXISTING.
        Files.move(source, target); // Same-filesystem private staging; no copy fallback.
    }
    private static void force(Path file) throws IOException { try (var channel = FileChannel.open(file, WRITE)) { channel.force(true); } }
    private static void privateDirectory(Path path) throws IOException {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))
            Files.createDirectory(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectory(path);
    }
    private static void privateFile(Path path) throws IOException {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))
            Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        else Files.createFile(path);
    }
    static void removeStage(Path stage) throws IOException {
        if (!Files.exists(stage, NOFOLLOW_LINKS)) return;
        Files.walkFileTree(stage, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException { if (failure != null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    static void requireBoundedJson(String text) throws IOException {
        int depth = 0; boolean quoted = false, escape = false;
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i);
            if (quoted) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '[' || c == '{') { if (++depth > 8) throw new IOException("Native manifest nesting exceeds its bound"); }
            else if (c == ']' || c == '}') depth--;
        }
    }

    static int run(String[] arguments, Path workingDirectory, PrintStream output, PrintStream error) {
        if (arguments.length > 0 && arguments[0].startsWith("postgres-"))
            return NativePostgresRecovery.run(arguments, workingDirectory, output, error);
        try {
            if (arguments.length == 0) throw new IOException("Expected capture, verify or restore");
            var options = new HashMap<String, String>();
            for (int i = 1; i < arguments.length; i += 2) {
                if (i + 1 == arguments.length || !Set.of("--source", "--snapshot", "--target", "--version", "--writers-stopped", "--database-family").contains(arguments[i]) || options.putIfAbsent(arguments[i], arguments[i + 1]) != null) throw new IOException("Invalid native snapshot option");
            }
            if (options.containsKey("--database-family") && !arguments[0].equals("capture"))
                throw new IOException("--database-family is a capture-only declaration; verify and restore use the manifest");
            String version = options.get("--version");
            if (!options.containsKey("--snapshot")) throw new IOException("--snapshot is required");
            Path snapshot = workingDirectory.resolve(options.get("--snapshot"));
            Checkpoint checkpoint = () -> { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Native snapshot interrupted"); };
            Receipt receipt;
            if (arguments[0].equals("verify")) receipt = verify(snapshot, version, checkpoint);
            else {
                if (!"true".equals(options.get("--writers-stopped")) || !options.containsKey("--source")) throw new IOException("--source and --writers-stopped true are required; stop every writer before capture/rehearsal");
                Path source = workingDirectory.resolve(options.get("--source"));
                if (arguments[0].equals("capture")) receipt = capture(source, snapshot, version,
                        DatabaseFamily.valueOf(options.getOrDefault("--database-family", "HSQLDB")), checkpoint);
                else if (arguments[0].equals("restore") && options.containsKey("--target")) receipt = restore(snapshot, source, workingDirectory.resolve(options.get("--target")), version, checkpoint);
                else if (arguments[0].equals("plan") && options.containsKey("--target")) receipt = plan(snapshot, source, workingDirectory.resolve(options.get("--target")), version, checkpoint);
                else if (arguments[0].equals("resume") && options.containsKey("--target")) receipt = resume(snapshot, source, workingDirectory.resolve(options.get("--target")), version, checkpoint);
                else throw new IOException("Expected capture, verify, plan, restore or resume with --target");
            }
            output.println("Native installation files " + arguments[0] + " verified: " + receipt.fileCount() + " files, " + receipt.bytes() + " bytes, manifest SHA-256 " + receipt.sha256()); return 0;
        } catch (IOException | IllegalArgumentException failure) { error.println("Native installation operation failed: " + failure.getMessage()); return 1; }
    }
}
