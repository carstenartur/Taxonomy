package com.taxonomy.composition.analysis.artemis;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Shared by the Maven-owned test and artifact-based measurement; no production beans are replaced. */
public final class WorkerRuntimeFootprintHarness {
    private static final List<String> ROOTS = List.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
    private static final List<String> FLAGS = List.of("-Xms128m", "-Xmx1536m", "-XX:+UseSerialGC",
            "-XX:ActiveProcessorCount=2", "-XX:NativeMemoryTracking=summary");
    private static final String PINNED_MODEL_SHA256 = "828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: output-directory pinned-model-directory");
        measure(Path.of(args[0]), Path.of(args[1]));
    }

    static void measure(Path output, Path model) throws Exception {
        output = output.toAbsolutePath(); model = model.toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("evidence.json"));
        require(PINNED_MODEL_SHA256.equals(sha256(model.resolve("model.onnx"))), "Pinned model checksum mismatch");
        require(Files.size(model.resolve("tokenizer.json")) > 0, "Pinned tokenizer is absent");
        String tokenizerSha256 = sha256(model.resolve("tokenizer.json"));
        var json = new ObjectMapper();
        String artifact = System.getProperty("taxonomy.footprint.application-jar", "");
        Map<String, Object> application = new LinkedHashMap<>();
        if (!artifact.isBlank()) {
            Path jar = Path.of(artifact).toAbsolutePath();
            Map<String, Object> manifest = json.readValue(Files.readString(jar.getParent().resolve("manifest.json")), new TypeReference<>() { });
            require(jar.getFileName().toString().equals(manifest.get("jarName")), "Artifact name differs from manifest");
            require(sha256(jar).equals(manifest.get("sha256")), "Application artifact checksum mismatch");
            application.putAll(manifest);
        } else {
            application.put("sourceCommit", git("rev-parse", "HEAD"));
            application.put("sourceTree", git("rev-parse", "HEAD^{tree}"));
            application.put("runtime", "current Maven reactor classpath");
        }
        Map<String, Object> measurements = new LinkedHashMap<>();
        for (boolean nativeEnabled : List.of(false, true)) {
            for (String selection : List.of("CP", "ALL_WORKER", "FULL_CATALOGUE")) {
                String label = selection + (nativeEnabled ? "_NATIVE" : "_COLD");
                Path directory = output.resolve(label);
                require(!Files.exists(directory), "Measurement directory already exists: " + directory);
                Files.createDirectories(directory);
                String role = selection.equals("FULL_CATALOGUE") ? "all" : "worker";
                List<String> shards = selection.equals("CP") ? List.of("CP") : ROOTS;
                int port;
                try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
                var configuration = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                        .setJournalType(org.apache.activemq.artemis.core.server.JournalType.NIO)
                        .setBindingsDirectory(directory.resolve("broker/bindings").toString())
                        .setJournalDirectory(directory.resolve("broker/journal").toString())
                        .setPagingDirectory(directory.resolve("broker/paging").toString())
                        .setLargeMessagesDirectory(directory.resolve("broker/large").toString())
                        .setThreadPoolMaxSize(2).setScheduledThreadPoolMaxSize(1)
                        .addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
                var broker = new EmbeddedActiveMQ().setConfiguration(configuration);
                Process child = null;
                try {
                    broker.start();
                    List<String> command = new ArrayList<>();
                    List<String> expectedVmArguments = new ArrayList<>(FLAGS);
                    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                    command.addAll(FLAGS);
                    // Isolate the measuring entry point from unrelated test @Configuration classes.
                    Path probeClasses = directory.resolve("probe-classes");
                    String probeResource = WorkerRuntimeFootprintProbe.class.getName().replace('.', '/') + ".class";
                    Path probeClass = probeClasses.resolve(probeResource);
                    Files.createDirectories(probeClass.getParent());
                    try (var input = WorkerRuntimeFootprintProbe.class.getResourceAsStream("/" + probeResource)) {
                        Files.copy(java.util.Objects.requireNonNull(input), probeClass);
                    }
                    if (artifact.isBlank()) {
                        String runtimeClasspath = java.util.Arrays.stream(System.getProperty("surefire.test.class.path",
                                        System.getProperty("java.class.path")).split(java.io.File.pathSeparator))
                                .filter(entry -> !entry.replace('\\', '/').endsWith("/target/test-classes"))
                                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                        command.addAll(List.of("-cp", probeClasses + java.io.File.pathSeparator + runtimeClasspath,
                                WorkerRuntimeFootprintProbe.class.getName()));
                    } else {
                        expectedVmArguments.addAll(List.of("-Dloader.path=" + probeClasses,
                                "-Dloader.main=" + WorkerRuntimeFootprintProbe.class.getName()));
                        command.addAll(List.of(expectedVmArguments.get(FLAGS.size()), expectedVmArguments.get(FLAGS.size() + 1),
                                "-cp", Path.of(artifact).toAbsolutePath().toString(), "org.springframework.boot.loader.launch.PropertiesLauncher"));
                    }
                    command.addAll(List.of(role, String.join(",", shards), Boolean.toString(nativeEnabled),
                            "tcp://127.0.0.1:" + port, model.toString(), directory.toString()));
                    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                            .redirectOutput(directory.resolve("application.log").toFile());
                    builder.environment().remove("JDK_JAVA_OPTIONS");
                    builder.environment().remove("JAVA_TOOL_OPTIONS");
                    builder.environment().remove("_JAVA_OPTIONS");
                    child = builder.start();
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(12);
                    while (!Files.exists(directory.resolve("measurement.json"))) {
                        require(child.isAlive(), "Child " + label + " failed; see " + directory.resolve("application.log"));
                        require(System.nanoTime() < deadline, "Timed out measuring " + label);
                        Thread.sleep(250);
                    }
                    Map<String, Object> result = json.readValue(Files.readString(directory.resolve("measurement.json")), new TypeReference<>() { });
                    require(expectedVmArguments.equals(result.get("jvmFlags")), "Measured JVM arguments differ from the declared flags");
                    Map<String, Integer> consumers = new LinkedHashMap<>();
                    for (String root : ROOTS) {
                        var queue = broker.getActiveMQServer().locateQueue(SimpleString.of("taxonomy.analysis.subtaxonomy." + root));
                        int count = queue == null ? 0 : queue.getConsumerCount();
                        require(count == (shards.contains(root) ? 1 : 0), "Actual root subscriptions differ for " + label + ": " + root);
                        consumers.put(root, count);
                    }
                    result.put("actualRootQueueConsumers", consumers);
                    require(((Number) result.get("heapUsedBytes")).longValue() > 0, "No heap measurement");
                    if (role.equals("worker")) require(((Number) result.get("indexDirectoryBytes")).longValue() == 0, "Worker opened unrelated global indexes");
                    else require(((Number) result.get("indexDirectoryBytes")).longValue() > 0, "Full baseline has no filesystem index");
                    if (nativeEnabled && Files.exists(Path.of("/proc/self/maps")))
                        require(Boolean.TRUE.equals(result.get("onnxRuntimeMapped")), "Native ONNX library was not mapped");
                    Files.writeString(directory.resolve("finish"), "measurement retained\n");
                    require(child.waitFor(45, TimeUnit.SECONDS) && child.exitValue() == 0, "Measured application did not stop cleanly");
                    measurements.put(label, result);
                    System.out.println("WORKER_FOOTPRINT_MEASURED " + label + " heap=" + result.get("heapUsedBytes")
                            + " rss=" + result.get("rssBytes") + " index=" + result.get("indexDirectoryBytes"));
                } finally {
                    if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS); }
                    broker.stop();
                }
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1); evidence.put("completedAt", Instant.now().toString());
        evidence.put("application", application); evidence.put("jvmFlags", FLAGS);
        evidence.put("modelSha256", PINNED_MODEL_SHA256); evidence.put("tokenizerSha256", tokenizerSha256);
        evidence.put("measurements", measurements);
        evidence.put("limits", List.of("Single sequential sample per configuration; no production capacity threshold",
                "Full Spring/Hibernate runtime with fresh HSQLDB; broker memory is outside each measured JVM",
                "Cold means embeddings disabled; native cases are separate JVMs after real inference/index readiness",
                "Worker startup retains no task snapshots or candidate vectors; task/cache footprint is measured separately",
                "RSS includes native allocations and committed/touched pages; NMT does not attribute all ONNX allocations",
                "No Kubernetes, external database, TLS, load, concurrency or steady-state throughput claim"));
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("evidence.json").toFile(), evidence);
    }

    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536];
            for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git")); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        require(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, "Cannot record Git source identity");
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
