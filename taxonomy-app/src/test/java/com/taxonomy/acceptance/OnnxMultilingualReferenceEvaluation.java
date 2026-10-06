package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Supplementary real REST measurement, retaining unsuccessful independently authored cases. */
public final class OnnxMultilingualReferenceEvaluation {
    static final String RESOURCE = "/scenarios/local-onnx-multilingual-v1.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Set<String> KINDS = Set.of("ANCHOR", "PARAPHRASE", "DISTRACTOR", "AMBIGUOUS");
    private static final Path OUTPUT = Path.of("target", "failsafe-reports", "local-onnx-multilingual");

    private OnnxMultilingualReferenceEvaluation() { }

    record Case(String id, String language, String kind, String query, Set<String> required, Set<String> excluded) { }

    static byte[] resourceBytes() throws IOException {
        try (var input = OnnxMultilingualReferenceEvaluation.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing multilingual reference resource");
            byte[] bytes = input.readNBytes(65_537);
            if (bytes.length > 65_536) throw new IOException("Multilingual reference exceeds size budget");
            return bytes;
        }
    }

    static List<Case> load() throws Exception {
        var reference = JSON.readTree(resourceBytes());
        if (reference.path("schemaVersion").asInt() != 1 || reference.path("cases").size() != 16
                || !sha256(OnnxReferenceCases.resourceBytes()).equals(reference.path("anchorSha256").stringValue())) {
            throw new IOException("Multilingual reference version or original anchors changed");
        }
        var cases = new ArrayList<Case>();
        var ids = new HashSet<String>();
        for (var row : reference.path("cases")) {
            String id = row.path("id").stringValue(), language = row.path("language").stringValue();
            String kind = row.path("kind").stringValue(), query = row.path("query").stringValue();
            if (id == null || !id.matches("[a-z0-9-]{1,80}") || !ids.add(id)
                    || language == null || !Set.of("en", "de").contains(language)
                    || kind == null || !KINDS.contains(kind) || query == null || query.isBlank() || query.length() > 4096) {
                throw new IOException("Invalid multilingual reference case");
            }
            Set<String> required = codes(row.path("required")), excluded = codes(row.path("excluded"));
            if ((kind.equals("DISTRACTOR") ? required.size() != 0 || excluded.size() != 3
                    : required.isEmpty() || !excluded.isEmpty())) throw new IOException("Invalid reference targets");
            cases.add(new Case(id, language, kind, query, required, excluded));
        }
        var anchors = cases.stream().filter(test -> test.kind().equals("ANCHOR")).toList();
        if (anchors.size() != 6) throw new IOException("Missing original anchor cases");
        for (var original : OnnxReferenceCases.load()) {
            var anchor = anchors.stream().filter(test -> test.id().equals(original.id())).findFirst().orElseThrow();
            if (!anchor.language().equals(original.language()) || !anchor.query().equals(original.query())
                    || !anchor.required().equals(original.required())) throw new IOException("Original anchor was rewritten");
        }
        return List.copyOf(cases);
    }

    private static Set<String> codes(JsonNode array) throws IOException {
        if (!array.isArray()) throw new IOException("Missing reference targets");
        var result = new HashSet<String>();
        for (var code : array) {
            if (!code.isString() || !code.stringValue().matches("[A-Z]+-[0-9]+") || !result.add(code.stringValue())) {
                throw new IOException("Invalid reference target identity");
            }
        }
        return Set.copyOf(result);
    }

    public static void verify(URI origin, String authorization, Path modelDirectory, Path applicationJar) throws Exception {
        verify(origin, authorization, modelDirectory, applicationJar, OUTPUT);
    }

    static void verify(URI origin, String authorization, Path modelDirectory, Path applicationJar, Path output) throws Exception {
        Files.createDirectories(output);
        var report = new LinkedHashMap<String, Object>();
        var rows = new ArrayList<Map<String, Object>>();
        report.put("schemaVersion", 1);
        report.put("evidenceKind", "LOCAL_ONNX_MULTILINGUAL_EVALUATION_ATTEMPT");
        report.put("status", "ERROR");
        report.put("scope", "Positive references and targeted distractor/ambiguity observations; no exhaustive precision or calibrated probabilities");
        report.put("cases", rows);
        boolean anchorMiss = false;
        try {
            List<Case> cases = load();
            for (Case test : cases) {
                var row = new LinkedHashMap<String, Object>();
                row.put("caseId", test.id()); row.put("language", test.language()); row.put("kind", test.kind());
                row.put("status", "NOT_RUN"); row.put("qualityVerdict", "NOT_EVALUATED"); row.put("measurement", null);
                rows.add(row);
            }
            write(output, report);
            report.put("referenceSha256", sha256(resourceBytes()));
            report.put("originalAnchorSha256", sha256(OnnxReferenceCases.resourceBytes()));
            report.put("applicationJarSha256", hash(applicationJar));
            var modelHashes = new java.util.TreeMap<String, String>();
            for (String file : List.of("model.onnx", "tokenizer.json", "config.json", "tokenizer_config.json", "special_tokens_map.json")) {
                modelHashes.put(file, hash(modelDirectory.resolve(file)));
            }
            report.put("modelFileSha256", modelHashes);
            report.put("topK", 10);
            report.put("remoteProviderCalls", 0);
            report.put("runtimeModelDownloads", false);
            write(output, report);
            JsonNode status = requireReady(origin, authorization);
            if (!"MULTILINGUAL_MINILM_L12".equals(status.path("modelProfile").stringValue())) {
                throw new IOException("Runtime profile does not match the multilingual acceptance contract");
            }
            report.put("modelProfile", status.path("modelProfile").stringValue());
            JsonNode catalogue = get(origin, authorization, "/api/taxonomy");
            String fingerprint = OnnxReferenceEvaluation.catalogueFingerprint(catalogue);
            report.put("catalogueSha256", fingerprint);
            var identities = new LinkedHashMap<String, String>();
            collect(catalogue, identities);
            if (identities.size() != status.path("indexedNodesAtReadiness").asInt()) throw new IOException("Catalogue and node index disagree");
            var bindings = JSON.readTree(resourceBytes()).path("bindings");
            for (String code : List.of("UA-1604", "UA-1583", "UA-1222")) {
                if (!bindings.path(code).stringValue().equals(identities.get(code))) throw new IOException("Catalogue reference binding changed");
            }
            int calls = 0;
            for (int i = 0; i < cases.size(); i++) {
                var test = cases.get(i); var row = rows.get(i); long started = System.nanoTime();
                try {
                    requireReady(origin, authorization);
                    report.put("attemptedSearchCalls", ++calls);
                    // Only the independently authored query enters the productive search endpoint.
                    var hits = get(origin, authorization, "/api/search/semantic?q="
                            + URLEncoder.encode(test.query(), StandardCharsets.UTF_8) + "&maxResults=10");
                    if (!hits.isArray() || hits.isEmpty() || hits.size() > 10) throw new IOException("Malformed or technically ambiguous empty semantic response");
                    var predictions = new ArrayList<String>();
                    for (var hit : hits) {
                        String code = hit.path("code").stringValue();
                        if (code == null || !identities.containsKey(code) || predictions.contains(code)) throw new IOException("Invalid search result identity");
                        predictions.add(code);
                    }
                    requireReady(origin, authorization);
                    row.put("status", "MEASURED");
                    if (test.kind().equals("DISTRACTOR")) {
                        var unexpected = predictions.stream().filter(test.excluded()::contains).toList();
                        row.put("measurement", Map.of("predicted", predictions, "unexpectedTargetedReferences", unexpected));
                        row.put("qualityVerdict", unexpected.isEmpty() ? "TARGETED_DISTRACTORS_ABSENT" : "TARGETED_DISTRACTOR_FOUND");
                    } else {
                        var measured = OnnxReferenceCases.measure(test.required(), predictions, 10);
                        row.put("measurement", measured);
                        row.put("qualityVerdict", measured.missing().isEmpty() ? "REFERENCE_FOUND" : "REFERENCE_MISSED");
                        if (test.kind().equals("ANCHOR") && !measured.missing().isEmpty()) anchorMiss = true;
                    }
                } catch (InterruptedException interrupted) {
                    row.put("status", "CANCELLED"); Thread.currentThread().interrupt(); throw interrupted;
                } catch (Exception failure) {
                    row.put("status", failure instanceof java.net.http.HttpTimeoutException ? "TIMED_OUT" : "ERROR");
                    row.put("failureType", failure.getClass().getSimpleName());
                } finally {
                    row.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - started).toMillis()); write(output, report);
                }
            }
            if (!fingerprint.equals(OnnxReferenceEvaluation.catalogueFingerprint(get(origin, authorization, "/api/taxonomy")))) {
                throw new IOException("Catalogue changed during multilingual evaluation");
            }
            for (var entry : modelHashes.entrySet()) if (!entry.getValue().equals(hash(modelDirectory.resolve(entry.getKey())))) {
                throw new IOException("Model changed during multilingual evaluation");
            }
            if (rows.stream().anyMatch(row -> !"MEASURED".equals(row.get("status")))) throw new IOException("Incomplete multilingual evidence");
            boolean misses = rows.stream().anyMatch(row -> "REFERENCE_MISSED".equals(row.get("qualityVerdict"))
                    || "TARGETED_DISTRACTOR_FOUND".equals(row.get("qualityVerdict")));
            report.put("evidenceKind", "REAL_LOCAL_ONNX_MULTILINGUAL_RETRIEVAL");
            report.put("status", misses ? "MEASURED_WITH_REFERENCE_MISSES" : "MEASURED_REFERENCES_FOUND");
            if (anchorMiss) throw new AssertionError("Unchanged English or German ONNX anchor missed; inspect reports");
        } catch (Exception failure) {
            report.put("status", failure instanceof InterruptedException ? "CANCELLED"
                    : failure instanceof java.net.http.HttpTimeoutException ? "TIMED_OUT" : "ERROR");
            report.put("failureType", failure.getClass().getSimpleName()); throw failure;
        } finally { write(output, report); }
    }

    private static JsonNode requireReady(URI origin, String authorization) throws Exception {
        var status = get(origin, authorization, "/api/embedding/status");
        if (!OnnxReferenceCases.ready(status)) throw new IOException("Semantic index is not ready");
        return status;
    }

    private static void collect(JsonNode nodes, Map<String, String> identities) throws IOException {
        for (var node : nodes) {
            String code = node.path("code").stringValue(), title = node.path("nameEn").stringValue();
            if (code == null || title == null || identities.putIfAbsent(code, title) != null) throw new IOException("Invalid catalogue identity");
            if (node.path("children").isArray()) collect(node.path("children"), identities);
        }
    }

    private static JsonNode get(URI origin, String authorization, String path) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization).header("Accept", "application/json").GET().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > 16 * 1024 * 1024) throw new IOException("Invalid evaluation HTTP response");
        var result = JSON.readTree(response.body());
        if (result == null) throw new IOException("Empty evaluation JSON response");
        return result;
    }

    private static void write(Path output, Map<String, Object> report) throws IOException {
        Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static String hash(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
