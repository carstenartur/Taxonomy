package com.taxonomy.interop.publication;

import com.taxonomy.interop.IntegrationJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Separate provider/client JVMs, forcibly terminated at observed real durable boundaries. */
class IntegrationPublicationBoundaryRestartTest {
    @TempDir Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void pushBoundariesSurviveIndependentClientAndProviderTermination() throws Exception {
        matrix("PUSH", List.of("plan-commit", "dispatch-intent", "remote-commit-before-response", "response-before-journal", "partial-receipt", "all-receipts-before-finalization", "completed-commit", "replay"));
    }
    @Test void syncLocalAndGitCommitsSurviveIndependentClientAndProviderTermination() throws Exception {
        matrix("SYNCHRONIZE", List.of("local-apply-before-git", "git-before-ack", "completed-commit", "replay"));
    }
    private void matrix(String mode, List<String> boundaries) throws Exception {
        Path root = directory.resolve(mode); Files.createDirectories(root);
        JsonNode first = null, previous = null; var clientPids = new HashSet<Long>(); var providerPids = new HashSet<Long>();
        var evidence = new ArrayList<Map<String,Object>>();
        var boundRequests = new TreeMap<String,String>();
        for (int index = 0; index < boundaries.size(); index++) {
            String boundary = boundaries.get(index); Files.deleteIfExists(root.resolve("endpoint"));
            Path providerLog = root.resolve(boundary + ".provider.log"), clientLog = root.resolve(boundary + ".client.log");
            Process provider = start(root, "provider", boundary, mode, index, providerLog), client = null;
            try {
                awaitFile(root.resolve("endpoint"), provider, providerLog, 30);
                client = start(root, "client", boundary, mode, index, clientLog);
                assertTrue(providerPids.add(provider.pid())); assertTrue(clientPids.add(client.pid())); assertNotEquals(provider.pid(), client.pid());
                if (boundary.equals("replay")) {
                    assertTrue(client.waitFor(150, TimeUnit.SECONDS), () -> "Replay timeout " + read(clientLog));
                    assertEquals(0, client.exitValue(), () -> read(clientLog));
                } else {
                    Path marker = root.resolve(boundary + (boundary.equals("remote-commit-before-response") ? ".provider-marker" : ".client-marker"));
                    awaitFile(marker, boundary.equals("remote-commit-before-response") ? provider : client, clientLog, 150);
                    kill(client);
                }
                kill(provider);
                var durable = new IntegrationJson(mapper).read(Files.readString(root.resolve("provider.json")), PublicationContractProvider.Durable.class);
                durable.mutations().forEach((id,count) -> assertEquals(1, count, "No duplicate mutation: " + id));
                JsonNode captured = mapper.readTree(Files.readString(root.resolve(boundary + ".client-marker")));
                if (first == null) first = captured;
                var op = captured.path("operation");
                for (String key : List.of("operationId", "requestFingerprint", "planFingerprint", "reviewFingerprint")) assertEquals(first.path("operation").path(key), op.path(key), boundary + " " + key);
                assertEquals(first.path("frozenItems"), captured.path("frozenItems"), boundary + " frozen item IDs/keys/payloads");
                captured.path("dispatchRequestFingerprints").properties().forEach(entry -> {
                    String prior = boundRequests.putIfAbsent(entry.getKey(), entry.getValue().asText());
                    if (prior != null) assertEquals(prior, entry.getValue().asText(), "Exact same request on resend/receipt lookup");
                });
                boolean complete = boundary.equals("completed-commit") || boundary.equals("replay");
                assertEquals(complete, !op.path("commonCheckpointId").isNull(), boundary + " COMMON only after verified completion");
                if (mode.equals("PUSH")) {
                    assertEquals(first.path("gitCommits"), captured.path("gitCommits")); assertEquals(first.path("semanticOperations"), captured.path("semanticOperations"));
                    int expected = switch (boundary) { case "plan-commit", "dispatch-intent" -> 0; case "remote-commit-before-response", "response-before-journal", "partial-receipt" -> 1; default -> 3; };
                    assertEquals(expected, durable.mutations().size(), boundary);
                    assertEquals(expected + 1, durable.artifacts().size(), "Unchanged scope metadata plus newly published resources");
                } else {
                    assertTrue(durable.mutations().isEmpty()); assertEquals(2, durable.artifacts().size(), "Seeded element and scope metadata");
                    assertEquals(first.path("semanticOperations"), captured.path("semanticOperations"));
                    assertEquals(first.path("gitCommits").asLong() + (boundary.equals("local-apply-before-git") ? 0 : 1), captured.path("gitCommits").asLong());
                }
                evidence.add(Map.of("boundary", boundary, "clientPid", client.pid(), "providerPid", provider.pid(), "providerMutations", durable.mutations(), "snapshot", captured, "clientTermination", boundary.equals("replay") ? "NORMAL_VERIFIED_REPLAY" : "FORCIBLE", "providerTermination", "FORCIBLE", "clockOffsetSeconds", index * 60));
                previous = captured;
                System.out.println("PUBLICATION_INDEPENDENT_BOUNDARY_OK " + mode + " " + boundary);
            } finally {
                if (client != null) kill(client); kill(provider);
                copyEvidence(root, mode);
            }
        }
        Files.writeString(root.resolve("matrix.json"), mapper.writeValueAsString(Map.of("mode", mode, "provider", "taxonomy-publication-contract-v1", "testOnly", true, "productionCrashHooks", false, "atomicBoundary", "Plan acceptance and local staging share one transaction; Push plan and Sync local-apply are separate scenarios of that same commit", "boundaries", evidence)));
        copyEvidence(root, mode);
    }
    private Process start(Path root, String role, String boundary, String mode, int index, Path log) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx768m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PublicationBoundaryDriver.class.getName(), root.toString(), role, boundary, mode, Integer.toString(index * 60)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private void awaitFile(Path file, Process process, Path log, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!Files.exists(file) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(Files.exists(file), () -> "Missing real boundary " + file + "\n" + read(log));
    }
    private static void kill(Process process) throws Exception { if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Child JVM must terminate before restart"); } }
    private static String read(Path log) { try { return Files.readString(log); } catch (Exception failure) { return failure.toString(); } }
    private void copyEvidence(Path root, String mode) throws Exception {
        String path = System.getProperty("publication.restart.evidence"); if (path == null) return;
        Path output = Path.of(path).resolve(mode); Files.createDirectories(output);
        try (var files = Files.list(root)) { for (Path file : files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches(".*(log|marker|json)$")).toList()) Files.copy(file, output.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING); }
    }
}
