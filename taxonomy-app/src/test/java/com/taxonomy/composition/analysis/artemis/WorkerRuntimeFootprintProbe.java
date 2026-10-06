package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.EmbeddingModelProfile;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.search.mapper.orm.Search;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A fresh, full Spring JVM; the real TCP broker lives outside this measured process. */
public final class WorkerRuntimeFootprintProbe {
    public static void main(String[] args) throws Exception {
        String role = args[0], shards = args[1], broker = args[3];
        boolean nativeEnabled = Boolean.parseBoolean(args[2]);
        Path model = Path.of(args[4]), directory = Path.of(args[5]);
        Path indexes = directory.resolve("indexes");
        System.setProperty("taxonomy.admin-password", UUID.randomUUID().toString());
        long started = System.nanoTime();
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--spring.profiles.active=hsqldb", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.main.lazy-initialization=false", "--taxonomy.init.async=true",
                "--taxonomy.analysis.transport.mode=artemis", "--taxonomy.analysis.runtime-role=" + role,
                "--taxonomy.analysis.worker.shards=" + shards, "--taxonomy.analysis.worker.consumers-per-shard=1",
                "--taxonomy.analysis.artemis.broker-url=" + broker, "--taxonomy.analysis.artemis.require-tls=false",
                "--embedding.enabled=" + nativeEnabled, "--embedding.allow-download=false",
                "--embedding.model.profile=" + EmbeddingModelProfile.MULTILINGUAL_MINILM_L12.name(),
                "--embedding.query.prefix=",
                "--embedding.model.dir=" + model, "--llm.provider=LOCAL_ONNX", "--llm.mock=false",
                "--spring.jpa.properties.hibernate.search.backend.directory.type=local-filesystem",
                "--spring.jpa.properties.hibernate.search.backend.directory.root=" + indexes,
                "--spring.jpa.properties.hibernate.search.indexing.plan.synchronization.strategy=sync",
                "--taxonomy.security.require-password-change=false")) {
            var initialization = app.getBean(AppInitializationStateService.class);
            await(() -> {
                if (initialization.getState() == AppInitializationStateService.State.FAILED)
                    throw new IllegalStateException(initialization.getMessage());
                return initialization.isReady();
            }, 600, "catalogue readiness");
            var brokerHealth = app.getBean(ArtemisAnalysisBrokerHealthIndicator.class);
            await(() -> "UP".equals(brokerHealth.health().getStatus().getCode()), 60, "broker readiness");
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + app.getEnvironment().getProperty("local.server.port") + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            var readiness = http.send(request, HttpResponse.BodyHandlers.ofString());
            require(readiness.statusCode() == 200, "HTTP readiness failed: " + readiness.statusCode());

            var embeddings = app.getBean(LocalEmbeddingService.class);
            require(embeddings.modelProfile() == EmbeddingModelProfile.MULTILINGUAL_MINILM_L12,
                    "Measured embedding profile must be pinned multilingual MiniLM");
            require(embeddings.effectiveQueryPrefix().isEmpty(), "Multilingual MiniLM must not add a query instruction");
            var indexInitializer = app.getBean(LocalOnnxIndexInitializer.class);
            int nativeVectorDimensions = 0;
            if (nativeEnabled) {
                if (role.equals("all")) {
                    await(() -> {
                        var state = indexInitializer.getState();
                        if (state == LocalOnnxIndexInitializer.State.FAILED || state == LocalOnnxIndexInitializer.State.PARTIAL)
                            throw new IllegalStateException(indexInitializer.getDetail());
                        return state == LocalOnnxIndexInitializer.State.READY;
                    }, 600, "complete native node and relation indexes");
                }
                nativeVectorDimensions = embeddings.embedQuery("Command and control communication").length;
                require(nativeVectorDimensions == 384, "Pinned model inference did not produce a 384D vector");
            }
            var emf = app.getBean(EntityManagerFactory.class);
            long nodes, indexedNodes = 0;
            List<String> roots;
            try (var em = emf.createEntityManager()) {
                nodes = em.createQuery("select count(n) from TaxonomyNode n", Long.class).getSingleResult();
                roots = em.createQuery("select distinct n.taxonomyRoot from TaxonomyNode n order by n.taxonomyRoot", String.class).getResultList();
                if (role.equals("all")) indexedNodes = Search.session(em).search(TaxonomyNode.class)
                        .where(f -> f.matchAll()).fetchTotalHitCount();
            }
            boolean searchEnabled = !Boolean.FALSE.equals(emf.getProperties().get("hibernate.search.enabled"));
            var modelField = LocalEmbeddingService.class.getDeclaredField("model");
            modelField.setAccessible(true);
            Object embeddingTarget = embeddings;
            for (Object target; (target = org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(embeddingTarget)) != null;)
                embeddingTarget = target;
            boolean modelLoaded = modelField.get(embeddingTarget) != null;
            require(modelLoaded == nativeEnabled, "Model load state differs from the measured phase");
            if (role.equals("worker")) {
                require(!searchEnabled && nodes == 0, "Worker loaded the global catalogue or enabled its index");
            } else {
                require(searchEnabled && nodes > 0 && indexedNodes == nodes && roots.size() == 8,
                        "Full baseline did not load and index all eight catalogue roots");
            }
            for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(100); }
            // Use the same HotSpot diagnostic command as jcmd without requiring OS attach permissions.
            String nativeMemory = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory",
                    new Object[] { new String[] { "summary", "scale=KB" } }, new String[] { "[Ljava.lang.String;" });
            Files.writeString(directory.resolve("native-memory.txt"), nativeMemory);
            var nativeTotals = java.util.regex.Pattern.compile("Total: reserved=(\\d+)KB, committed=(\\d+)KB").matcher(nativeMemory);
            require(nativeTotals.find(), "Native memory tracking was not enabled");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", role); result.put("configuredRoots", List.of(shards.split(",")));
            result.put("phase", nativeEnabled ? "native-enabled-ready" : "embeddings-disabled-ready");
            result.put("pid", ProcessHandle.current().pid());
            result.put("jvm", System.getProperty("java.runtime.version"));
            result.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
            result.put("lazyInitialization", false); result.put("database", "fresh in-memory HSQLDB");
            result.put("broker", "real external-process Artemis over loopback TCP; TLS disabled only for this fixture");
            result.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            result.put("heapCommittedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
            result.put("heapMaxBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
            result.put("nonHeapUsedBytes", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
            result.put("nativeMemoryCapture", "HotSpot DiagnosticCommand MBean: vmNativeMemory summary scale=KB");
            result.put("nmtTotalReservedBytes", Long.parseLong(nativeTotals.group(1)) * 1024);
            result.put("nmtTotalCommittedBytes", Long.parseLong(nativeTotals.group(2)) * 1024);
            result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
            result.put("readyMillis", (System.nanoTime() - started) / 1_000_000);
            result.put("catalogueNodes", nodes); result.put("catalogueRoots", roots);
            result.put("hibernateSearchEnabled", searchEnabled); result.put("indexedNodes", indexedNodes);
            result.put("indexDirectoryBytes", bytes(indexes));
            result.put("globalEmbeddingIndexState", indexInitializer.getState().name());
            result.put("nativeModelLoaded", modelLoaded); result.put("nativeInferenceDimensions", nativeVectorDimensions);
            result.put("modelProfile", embeddings.modelProfile().name());
            result.put("configuredModelId", embeddings.configuredModelId());
            result.put("cachedCandidateVectors", embeddings.frozenCacheStatistics().vectors());
            if (nativeEnabled) result.put("modelIdentity", embeddings.embeddingIdentity());
            Path status = Path.of("/proc/self/status"), maps = Path.of("/proc/self/maps");
            if (Files.exists(status)) {
                String processStatus = Files.readString(status);
                Files.writeString(directory.resolve("process-status.txt"), processStatus);
                result.put("rssBytes", procKilobytes(processStatus, "VmRSS:") * 1024);
                result.put("peakRssBytes", procKilobytes(processStatus, "VmHWM:") * 1024);
            }
            if (Files.exists(maps)) result.put("onnxRuntimeMapped", Files.readString(maps).contains("libonnxruntime"));
            Path pendingMeasurement = directory.resolve("measurement.json.tmp");
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(pendingMeasurement.toFile(), result);
            Files.move(pendingMeasurement, directory.resolve("measurement.json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            // Parent confirms actual broker consumers while the measured application is still alive.
            await(() -> Files.exists(directory.resolve("finish")), 120, "measurement owner acknowledgement");
        }
    }

    private static long bytes(Path directory) throws Exception {
        if (!Files.exists(directory)) return 0;
        try (var paths = Files.walk(directory)) {
            long total = 0;
            for (Path path : paths.filter(Files::isRegularFile).toList()) total += Files.size(path);
            return total;
        }
    }
    private static long procKilobytes(String value, String key) {
        return value.lines().filter(line -> line.startsWith(key)).map(line -> line.substring(key.length()).strip().split("\\s+")[0])
                .mapToLong(Long::parseLong).findFirst().orElseThrow();
    }
    private static void await(java.util.function.BooleanSupplier done, int seconds, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!done.getAsBoolean()) {
            require(System.nanoTime() < deadline, "Timed out waiting for " + description);
            Thread.sleep(200);
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
