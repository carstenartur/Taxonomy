package com.taxonomy.acceptance;

import com.taxonomy.dto.TaxonomyDataFingerprint;
import com.taxonomy.dto.TaxonomyNodeDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Real REST retrieval evaluation; reference codes never enter an inference request. */
public final class OnnxReferenceEvaluation {
    public static final String QUERY_PREFIX = "Represent this sentence for searching relevant passages: ";
    public static final Path OUTPUT = Path.of("target", "failsafe-reports", "local-onnx-reference");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TOP_K = 10;
    private static final List<String> MODEL_FILES = List.of("model.onnx", "tokenizer.json",
            "tokenizer_config.json", "special_tokens_map.json", "config.json");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private OnnxReferenceEvaluation() { }

    public static Path modelDirectory() throws IOException {
        String configured = System.getenv("TAXONOMY_EMBEDDING_MODEL_DIR");
        Path relative = Path.of("models", "bge-small-en-v1.5");
        var candidates = new ArrayList<Path>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        else {
            String reactor = System.getProperty("maven.multiModuleProjectDirectory");
            if (reactor != null) candidates.add(Path.of(reactor).resolve(relative));
            candidates.add(relative); candidates.add(Path.of("..").resolve(relative));
        }
        for (Path candidate : candidates) {
            boolean complete = true;
            for (String file : MODEL_FILES) {
                Path path = candidate.resolve(file);
                complete &= Files.isRegularFile(path) && Files.size(path) > 0;
            }
            if (complete) return candidate.toAbsolutePath().normalize();
        }
        throw new IOException("Provision the pinned model with -Ponnx before the offline evaluation; no runtime download is allowed");
    }

    public static void verify(URI origin, String authorization, Path modelDirectory, Path applicationJar) throws Exception {
        verify(origin, authorization, modelDirectory, applicationJar, OUTPUT);
    }

    public static void verify(URI origin, String authorization, Path modelDirectory, Path applicationJar,
                              Path output) throws Exception {
        Files.createDirectories(output);
        for (String file : List.of("report.json", "report.csv", "report.html")) Files.deleteIfExists(output.resolve(file));
        var report = new LinkedHashMap<String, Object>();
        var rows = new ArrayList<Map<String, Object>>();
        report.put("schemaVersion", 2);
        report.put("evidenceKind", "LOCAL_ONNX_EVALUATION_ATTEMPT");
        report.put("status", "ERROR");
        report.put("scope", "Retrieval only; no generated relations, reformulation or calibrated probabilities");
        report.put("cases", rows);
        long started = System.nanoTime();
        try {
            var cases = OnnxReferenceCases.load();
            // Serialize cases before running them so an interrupted run cannot omit failing cases.
            for (var test : cases) for (String adapter : List.of("LOCAL_ONNX", "FULL_TEXT")) {
                var row = new LinkedHashMap<String, Object>();
                row.put("caseId", test.id()); row.put("language", test.language()); row.put("domain", test.domain());
                row.put("adapter", adapter); row.put("status", "NOT_RUN"); row.put("qualityVerdict", "NOT_EVALUATED");
                row.put("measurement", null); rows.add(row);
            }
            write(output, report);

            report.put("referenceSha256", hash(OnnxReferenceCases.resourceBytes()));
            report.put("referenceKind", "SYNTHETIC_POSITIVE_REFERENCES_NOT_EXHAUSTIVE");
            report.put("topK", TOP_K);
            report.put("queryPrefix", QUERY_PREFIX);
            report.put("runtimeModelDownloads", false);
            report.put("remoteProviderCalls", 0);
            report.put("applicationJarSha256", hash(applicationJar));
            var source = new Properties();
            try (var jar = new JarFile(applicationJar.toFile())) {
                var entry = jar.getJarEntry("BOOT-INF/classes/git.properties");
                if (entry == null) throw new IOException("Application source provenance missing");
                try (var input = jar.getInputStream(entry)) { source.load(input); }
            }
            String revision = source.getProperty("git.commit.id", "");
            if (!revision.matches("[0-9a-f]{40}")) throw new IOException("Invalid application source identity");
            report.put("applicationSourceRevision", revision);
            report.put("applicationSourceDirty", source.getProperty("git.dirty", "unknown"));
            var modelHashes = new TreeMap<String, String>();
            for (String name : MODEL_FILES) modelHashes.put(name, hash(modelDirectory.resolve(name)));
            Path serving = modelDirectory.resolve("serving.properties");
            if (Files.isRegularFile(serving)) modelHashes.put("serving.properties", hash(serving));
            report.put("modelFileSha256", modelHashes);
            // Same ten-minute readiness ceiling as the existing ONNX browser suite.
            long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
            JsonNode status;
            do {
                status = get(origin, authorization, "/api/embedding/status");
                if (OnnxReferenceCases.ready(status)) break;
                String state = status.path("indexState").stringValue();
                if (Set.of("FAILED", "DISABLED").contains(state == null ? "" : state)) {
                    throw new IOException("Model/index unavailable");
                }
                if (System.nanoTime() >= deadline) throw new java.net.http.HttpTimeoutException("Index readiness deadline");
                Thread.sleep(500);
            } while (true);
            report.put("indexState", status.path("indexState").stringValue());
            report.put("indexedNodesAtReadiness", status.path("indexedNodesAtReadiness").asLong());
            JsonNode catalogueJson = get(origin, authorization, "/api/taxonomy");
            // Validate bounded hierarchy/identities before binding the canonical DTO tree.
            var catalogue = catalogue(catalogueJson);
            String catalogueHash = catalogueFingerprint(catalogueJson);
            String projectionHash = hash(JSON.writeValueAsBytes(catalogue));
            report.put("catalogueSha256", catalogueHash);
            report.put("catalogueFingerprintAlgorithm", "TaxonomyDataFingerprint.sha256");
            // Preserve the older text/translation check without calling it canonical.
            report.put("catalogueProjectionSha256", projectionHash);
            if (catalogue.size() != status.path("indexedNodesAtReadiness").asLong()) {
                throw new IOException("Catalogue and ready vector index disagree");
            }
            for (var test : cases) {
                for (String code : test.required()) {
                    if (!catalogue.containsKey(code) || !test.expectedTitle().equals(catalogue.get(code).get("nameEn"))) {
                        throw new IOException("Reference catalogue binding changed: " + test.id());
                    }
                }
            }
            int index = 0;
            int searchCalls = 0;
            for (var test : cases) for (String adapter : List.of("LOCAL_ONNX", "FULL_TEXT")) {
                var row = rows.get(index++);
                long callStarted = System.nanoTime();
                try {
                    requireReady(origin, authorization);
                    String path = adapter.equals("LOCAL_ONNX") ? "/api/search/semantic" : "/api/search";
                    report.put("attemptedSearchCalls", ++searchCalls);
                    JsonNode result = get(origin, authorization, path + "?q="
                            + URLEncoder.encode(test.query(), StandardCharsets.UTF_8) + "&maxResults=" + TOP_K);
                    if (!result.isArray() || result.size() > TOP_K) throw new IOException("Malformed retrieval response");
                    var predictions = new ArrayList<String>();
                    for (JsonNode hit : result) {
                        String code = hit.path("code").stringValue();
                        if (code == null || !catalogue.containsKey(code)) throw new IOException("Unknown result identity");
                        predictions.add(code);
                    }
                    // This KNN API has no relevance cutoff. Empty output may be its caught-error
                    // fallback: do not claim zero recall from that ambiguous technical outcome.
                    if (adapter.equals("LOCAL_ONNX") && predictions.isEmpty()) throw new IOException("Empty semantic response is not quality evidence");
                    requireReady(origin, authorization);
                    var measured = OnnxReferenceCases.measure(test.required(), predictions, TOP_K);
                    row.put("status", "MEASURED"); row.put("measurement", measured);
                    row.put("qualityVerdict", measured.missing().isEmpty() ? "REFERENCE_FOUND" : "REFERENCE_MISSED");
                } catch (InterruptedException interrupted) {
                    row.put("status", "CANCELLED"); Thread.currentThread().interrupt(); throw interrupted;
                } catch (Exception failure) {
                    row.put("status", failure instanceof java.net.http.HttpTimeoutException ? "TIMED_OUT" : "ERROR");
                    row.put("failureType", failure.getClass().getSimpleName());
                } finally {
                    row.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - callStarted).toMillis());
                    write(output, report);
                }
            }
            JsonNode finalCatalogueJson = get(origin, authorization, "/api/taxonomy");
            var finalCatalogue = catalogue(finalCatalogueJson);
            if (!catalogueHash.equals(catalogueFingerprint(finalCatalogueJson))
                    || !projectionHash.equals(hash(JSON.writeValueAsBytes(finalCatalogue)))) {
                throw new IOException("Catalogue changed during evaluation");
            }
            for (var entry : modelHashes.entrySet()) {
                if (!entry.getValue().equals(hash(modelDirectory.resolve(entry.getKey())))) throw new IOException("Model changed during evaluation");
            }
            if (rows.stream().anyMatch(row -> !"MEASURED".equals(row.get("status")))) throw new IOException("Incomplete retrieval evidence");
            boolean misses = rows.stream().anyMatch(row -> "LOCAL_ONNX".equals(row.get("adapter"))
                    && "REFERENCE_MISSED".equals(row.get("qualityVerdict")));
            report.put("evidenceKind", "REAL_LOCAL_ONNX_RETRIEVAL");
            report.put("status", misses ? "MEASURED_WITH_REFERENCE_MISSES" : "MEASURED_REFERENCES_FOUND");
            // English anchors are the initial regression contract for this English model.
            // German cases remain visible measurements, not a claimed multilingual pass.
            if (rows.stream().anyMatch(row -> "LOCAL_ONNX".equals(row.get("adapter")) && "en".equals(row.get("language"))
                    && "REFERENCE_MISSED".equals(row.get("qualityVerdict")))) throw new AssertionError("English ONNX reference missed; inspect report");
        } catch (InterruptedException interrupted) {
            report.put("status", "CANCELLED"); Thread.currentThread().interrupt(); throw interrupted;
        } catch (Exception failure) {
            report.put("status", failure instanceof java.net.http.HttpTimeoutException ? "TIMED_OUT" : "ERROR");
            report.put("failureType", failure.getClass().getSimpleName()); throw failure;
        } finally {
            report.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - started).toMillis());
            write(output, report);
        }
    }

    private static void requireReady(URI origin, String auth) throws Exception {
        if (!OnnxReferenceCases.ready(get(origin, auth, "/api/embedding/status"))) throw new IOException("Semantic index lost readiness");
    }
    private static JsonNode get(URI origin, String authorization, String path) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization).header("Accept", "application/json").GET().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = response.body();
        if (response.statusCode() != 200 || bytes.length > 16 * 1024 * 1024) throw new IOException("Invalid evaluation HTTP response");
        JsonNode result = JSON.readTree(bytes);
        if (result == null) throw new IOException("Empty JSON response");
        return result;
    }

    private static String catalogueFingerprint(JsonNode validatedRoots) {
        return TaxonomyDataFingerprint.sha256(Arrays.asList(
                JSON.treeToValue(validatedRoots, TaxonomyNodeDto[].class)));
    }

    private static Map<String, Map<String, String>> catalogue(JsonNode roots) throws IOException {
        var result = new TreeMap<String, Map<String, String>>(); collect(roots, result, 0); return result;
    }
    private static void collect(JsonNode nodes, Map<String, Map<String, String>> result, int depth) throws IOException {
        if (!nodes.isArray() || depth > 64 || result.size() > 100_000) throw new IOException("Invalid catalogue shape");
        for (JsonNode node : nodes) {
            if (!node.path("level").isIntegralNumber() || !node.path("level").canConvertToInt()
                    || (node.hasNonNull("analysisRole") && !node.path("analysisRole").isString())) {
                throw new IOException("Invalid canonical catalogue fields");
            }
            String code = node.path("code").stringValue();
            var identity = new TreeMap<String, String>();
            for (String field : List.of("nameEn", "nameDe", "descriptionEn", "descriptionDe", "parentCode", "taxonomyRoot")) {
                identity.put(field, node.path(field).isString() ? node.path(field).stringValue() : "");
            }
            if (code == null || result.putIfAbsent(code, identity) != null) throw new IOException("Duplicate/missing catalogue code");
            if (node.has("children")) collect(node.path("children"), result, depth + 1);
        }
    }
    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] block = new byte[64 * 1024]; int length;
            while ((length = input.read(block)) != -1) digest.update(block, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String csvValue(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) return "";
        return value.isString() ? value.stringValue() : value.toString();
    }
    private static void write(Path output, Map<String, Object> report) throws IOException {
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        String escaped = json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        Files.writeString(output.resolve("report.html"), "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Local ONNX references</title><h1>Local ONNX retrieval evidence</h1><p>Synthetic positive references. DE and EN remain separate. No exhaustive precision, generated relations or superiority claim.</p><pre>" + escaped + "</pre></html>");
        var csv = new StringBuilder("case,language,domain,adapter,state,quality,recall_at_10,first_relevant_rank\n");
        JsonNode rows = JSON.valueToTree(report.get("cases"));
        for (JsonNode row : rows) {
            for (String name : List.of("caseId", "language", "domain", "adapter", "status", "qualityVerdict")) csv.append(csvValue(row.path(name))).append(',');
            csv.append(csvValue(row.at("/measurement/recall"))).append(',').append(csvValue(row.at("/measurement/firstRelevantRank"))).append('\n');
        }
        Files.writeString(output.resolve("report.csv"), csv);
        Path temp = output.resolve("report.json.tmp"); Files.writeString(temp, json);
        Files.move(temp, output.resolve("report.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
