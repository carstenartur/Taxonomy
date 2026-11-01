package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Numeric-only, bounded observation of a release runner; never controls the build. */
final class ReleaseResourceSampler {
    static final int MAX_SAMPLES = 601;
    static final int MAX_PROCESSES = 512;
    private static final long MAX_METRICS_BYTES = 128L * 1024 * 1024;
    private static final Path PROC = Path.of("/proc");
    private static final Path CGROUP = Path.of("/sys/fs/cgroup");

    private ReleaseResourceSampler() { }

    static int run(String[] raw, Map<String, String> environment, Path workingDirectory,
            PrintStream output, PrintStream error) {
        try {
            var arguments = TaxonomyTooling.Arguments.parse(raw);
            Path root = arguments.path("root", workingDirectory).toAbsolutePath().normalize();
            Path directory = Path.of(arguments.required("output")).toAbsolutePath().normalize();
            switch (arguments.required("mode")) {
                case "init" -> initialize(root, directory, arguments.required("release-version"), environment);
                case "sample" -> monitor(root, directory, output);
                case "finalize" -> finish(directory, output);
                default -> throw new IllegalArgumentException();
            }
            return 0;
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException failure) {
            // Paths, argv, environment and exception text are deliberately never printed.
            error.println("::warning::Release resource diagnostics unavailable (input_or_io).");
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error.println("::warning::Release resource diagnostics interrupted.");
            return 1;
        }
    }

    private static void initialize(Path root, Path directory, String version,
            Map<String, String> environment) throws IOException, InterruptedException {
        String source = checkout(root);
        if (source == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException();
        }
        var identity = new LinkedHashMap<String, Object>();
        identity.put("schema", 1);
        identity.put("source_sha", source);
        identity.put("release_version", version);
        identity.put("run_id", positive(environment.get("GITHUB_RUN_ID")));
        identity.put("run_attempt", positive(environment.get("GITHUB_RUN_ATTEMPT")));
        identity.put("created_utc_ms", Instant.now().toEpochMilli());
        identity.put("page_size_bytes", numeric(boundedCommand(root, "getconf", "PAGESIZE")));
        identity.put("clock_ticks_per_second", numeric(boundedCommand(root, "getconf", "CLK_TCK")));
        Files.createDirectory(directory);
        write(directory.resolve("identity.json"), identity);
    }

    private static long positive(String value) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) throw new IllegalArgumentException();
        return parsed;
    }

    private static Map<String, Object> identity(Path directory) throws IOException {
        Path path = directory.resolve("identity.json");
        if (!regular(path) || Files.size(path) > 4096) throw new IOException();
        Map<String, Object> value = FlatJson.parseObject(Files.readString(path));
        if (!String.valueOf(value.get("source_sha")).matches("[0-9a-f]{40}")) throw new IOException();
        return value;
    }

    private static void monitor(Path root, Path directory, PrintStream output)
            throws IOException, InterruptedException {
        identity(directory);
        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            stop.countDown();
            try { done.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "release-resource-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        int samples = 0;
        try {
            try (var writer = Files.newBufferedWriter(directory.resolve("metrics.jsonl"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                do {
                    Map<String, Object> sample = snapshot(root, PROC, CGROUP);
                    sample.put("sample", samples);
                    writer.write(FlatJson.pretty(sample).replace("\n", ""));
                    writer.newLine();
                    writer.flush();
                    // Compact live evidence remains useful if the VM disappears before upload.
                    var summary = new LinkedHashMap<String, Object>(sample);
                    summary.remove("processes");
                    output.println(FlatJson.pretty(summary).replace("\n", ""));
                    output.flush();
                    samples++;
                } while (samples < MAX_SAMPLES && !stop.await(30, TimeUnit.SECONDS));
            }
            // The marker follows the final close, so a complete manifest hashes immutable bytes.
            write(directory.resolve("status.json"), Map.of("samples", samples, "complete", true));
        } finally {
            done.countDown();
            try { Runtime.getRuntime().removeShutdownHook(hook); }
            catch (IllegalStateException shutdownInProgress) { /* The hook is already running. */ }
        }
    }

    static Map<String, Object> snapshot(Path root, Path proc, Path cgroup)
            throws InterruptedException {
        var sample = new LinkedHashMap<String, Object>();
        sample.put("utc_ms", Instant.now().toEpochMilli());
        sample.put("monitor_local_pid", ProcessHandle.current().pid());
        Long visiblePid = null;
        try { visiblePid = processStat(Files.readString(proc.resolve("self/stat"))).get("pid"); }
        catch (IOException | IllegalArgumentException unavailable) { /* procfs can use another PID namespace. */ }
        sample.put("monitor_pid", visiblePid);
        sample.put("current_checkout_sha", checkout(root));
        Map<String, Long> memory = counters(proc.resolve("meminfo"),
                List.of("MemAvailable", "MemTotal", "SwapFree", "SwapTotal"), true);
        sample.put("host_memory_kib", memory);
        String disk = boundedCommand(root, "stat", "-f", "-c", "%a %S %d", "--", root.toString());
        Long available = null;
        Long inodes = null;
        if (disk != null) {
            String[] fields = disk.strip().split("\\s+");
            try {
                if (fields.length == 3) {
                    available = Math.multiplyExact(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
                    inodes = Long.parseLong(fields[2]);
                }
            } catch (IllegalArgumentException | ArithmeticException ignored) { available = null; inodes = null; }
        }
        sample.put("disk_available_bytes", available);
        sample.put("disk_free_inodes", inodes);
        sample.put("current_cgroup", cgroupMemory(proc, cgroup));
        processSnapshot(proc, sample);
        return sample;
    }

    private static void processSnapshot(Path proc, Map<String, Object> sample) {
        Comparator<Map<String, Long>> order = Comparator
                .comparingLong((Map<String, Long> row) -> row.get("rss_pages"))
                .thenComparingLong(row -> row.get("pid"));
        var selected = new PriorityQueue<>(order);
        int observed = 0;
        int unavailable = 0;
        boolean readable = false;
        try (var entries = Files.newDirectoryStream(proc, "[0-9]*")) {
            Object uid = Files.getAttribute(proc.resolve("self"), "unix:uid");
            readable = true;
            for (Path entry : entries) {
                try {
                    if (!uid.equals(Files.getAttribute(entry, "unix:uid"))) continue;
                    Map<String, Long> row = processStat(Files.readString(entry.resolve("stat")));
                    observed++;
                    selected.add(row);
                    if (selected.size() > MAX_PROCESSES) selected.remove();
                } catch (IOException | IllegalArgumentException | UnsupportedOperationException vanished) {
                    unavailable++;
                }
            }
        } catch (IOException | UnsupportedOperationException missing) { /* Explicitly unavailable below. */ }
        sample.put("processes_available", readable);
        sample.put("processes_observed", observed);
        sample.put("processes_unavailable", unavailable);
        sample.put("processes_omitted", observed - selected.size());
        sample.put("processes", selected.stream().sorted(order.reversed()).toList());
    }

    static Map<String, Long> processStat(String stat) {
        int open = stat.indexOf('(');
        int close = stat.lastIndexOf(')');
        if (open <= 0 || close < open) throw new IllegalArgumentException();
        // comm can contain spaces, parentheses or newlines. It is neither retained nor logged.
        String[] fields = stat.substring(close + 1).strip().split("\\s+");
        if (fields.length < 22) throw new IllegalArgumentException();
        return Map.of("pid", positive(stat.substring(0, open).strip()),
                "ppid", Long.parseLong(fields[1]), "user_ticks", Long.parseLong(fields[11]),
                "system_ticks", Long.parseLong(fields[12]), "start_ticks", Long.parseLong(fields[19]),
                "rss_pages", Math.max(0, Long.parseLong(fields[21])));
    }

    private static Map<String, Object> cgroupMemory(Path proc, Path base) {
        var result = new LinkedHashMap<String, Object>();
        result.put("available", false);
        result.put("memory_current_bytes", null);
        result.put("memory_max_bytes", null);
        result.put("memory_max_unlimited", false);
        result.put("events", null);
        try {
            for (String line : Files.readAllLines(proc.resolve("self/cgroup"))) {
                if (!line.startsWith("0::/")) continue;
                Path path = base.resolve(line.substring(4)).normalize();
                if (!path.startsWith(base)) break;
                String limit = Files.readString(path.resolve("memory.max")).strip();
                result.put("available", true);
                result.put("memory_current_bytes", number(path.resolve("memory.current")));
                result.put("memory_max_bytes", "max".equals(limit) ? null : numeric(limit));
                result.put("memory_max_unlimited", "max".equals(limit));
                result.put("events", counters(path.resolve("memory.events"),
                        List.of("low", "high", "max", "oom", "oom_kill", "oom_group_kill"), false));
                break;
            }
        } catch (IOException | IllegalArgumentException missing) { /* Unavailable, never an invented zero. */ }
        return result;
    }

    static Map<String, Long> counters(Path file, List<String> keys, boolean colon) {
        var values = new LinkedHashMap<String, Long>();
        keys.forEach(key -> values.put(key, null));
        try {
            for (String line : Files.readAllLines(file)) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length < 2) continue;
                String key = colon ? fields[0].replace(":", "") : fields[0];
                if (values.containsKey(key)) values.put(key, numeric(fields[1]));
            }
        } catch (IOException missing) { /* null denotes missing counters. */ }
        return values;
    }

    private static Long number(Path file) {
        try { return numeric(Files.readString(file).strip()); }
        catch (IOException missing) { return null; }
    }

    private static Long numeric(String value) {
        if (value == null) return null;
        try { long parsed = Long.parseLong(value.strip()); return parsed >= 0 ? parsed : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String checkout(Path root) throws InterruptedException {
        String value = boundedCommand(root, "git", "rev-parse", "HEAD");
        return value != null && value.strip().matches("[0-9a-f]{40}") ? value.strip() : null;
    }

    static String boundedCommand(Path root, String... command) throws InterruptedException {
        Process child = null;
        try {
            child = new ProcessBuilder(command).directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!child.waitFor(1, TimeUnit.SECONDS) || child.exitValue() != 0) return null;
            byte[] bytes = child.getInputStream().readNBytes(4097);
            return bytes.length <= 4096 ? new String(bytes, java.nio.charset.StandardCharsets.US_ASCII) : null;
        } catch (IOException unavailable) { return null; }
        finally {
            if (child != null) {
                if (child.isAlive()) child.destroyForcibly();
                child.waitFor(1, TimeUnit.SECONDS);
            }
        }
    }

    private static void finish(Path directory, PrintStream output) throws IOException {
        Map<String, Object> identity = identity(directory);
        Path metrics = directory.resolve("metrics.jsonl");
        Path status = directory.resolve("status.json");
        long samples = 0;
        if (regular(metrics)) {
            if (Files.size(metrics) > MAX_METRICS_BYTES) throw new IOException();
            try (var lines = Files.lines(metrics)) { samples = lines.limit(MAX_SAMPLES + 1L).count(); }
        }
        boolean complete = false;
        try {
            if (regular(status) && Files.size(status) < 4096 && samples > 0 && samples <= MAX_SAMPLES) {
                Map<String, Object> state = FlatJson.parseObject(Files.readString(status));
                complete = Boolean.TRUE.equals(state.get("complete"))
                        && Long.toString(samples).equals(String.valueOf(state.get("samples")));
            }
        } catch (IOException | IllegalArgumentException incompleteMarker) {
            // Optional terminal evidence may be interrupted or unreadable; still emit the manifest.
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schema", 1);
        manifest.put("source_sha", identity.get("source_sha"));
        manifest.put("run_id", identity.get("run_id"));
        manifest.put("run_attempt", identity.get("run_attempt"));
        manifest.put("complete", complete);
        manifest.put("samples", samples);
        manifest.put("identity", evidence(directory.resolve("identity.json"), true));
        // An interrupted writer may still be unwinding; do not claim a final checksum for it.
        manifest.put("metrics", evidence(metrics, complete));
        write(directory.resolve("manifest.json"), manifest);
        if (!complete) output.println("::warning::Release resource diagnostics are incomplete.");
    }

    private static Map<String, Object> evidence(Path file, boolean finalized) throws IOException {
        var result = new LinkedHashMap<String, Object>();
        result.put("bytes", regular(file) ? Files.size(file) : null);
        result.put("sha256", null);
        if (finalized && regular(file)) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
                }
                result.put("sha256", HexFormat.of().formatHex(digest.digest()));
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        return result;
    }

    private static boolean regular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void write(Path file, Map<String, Object> value) throws IOException {
        Files.writeString(file, FlatJson.pretty(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
