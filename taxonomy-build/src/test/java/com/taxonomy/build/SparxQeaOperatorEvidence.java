package com.taxonomy.build;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Validates supplied file/evidence bindings. It never runs, certifies or impersonates EA or PCS. */
final class SparxQeaOperatorEvidence {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_JSON = 256 * 1024;
    private static final long MAX_FILE = 32L * 1024 * 1024;
    private static final Set<String> REQUIRED_CHECKS = Set.of("OPEN_IN_EA", "CLOSE_REOPEN", "SUPPORTED_SEMANTICS");

    static JsonNode validate(Path manifestPath) throws IOException {
        Path manifest = manifestPath.toAbsolutePath().normalize(), directory = manifest.getParent();
        JsonNode request = read(manifest);
        require(request.path("schemaVersion").asInt() == 1, "Unsupported operator manifest schema");
        Path template = resolve(directory, text(request, "template"));
        JsonNode exports = read(resolve(directory, text(request, "exports")));
        require(exports.path("schemaVersion").asInt() == 1 && "PASSED".equals(text(exports, "harnessStatus")), "The installed-plugin export run must have passed");
        require("NOT_EXECUTED".equals(text(exports, "realProductStatus")), "File-export evidence cannot assert EA execution");
        requireSha(text(exports, "hostSha256")); requireSha(text(exports, "pluginSha256"));
        requireSqlite(template); matchSha(template, text(exports, "templateSha256"));
        Path outputs = resolve(directory, text(request, "outputs"));
        JsonNode files = exports.path("files");
        require(files.isArray() && files.size() >= 2 && files.size() <= 32, "Both native output modes need bounded file evidence");
        Map<String, String> hashes = new LinkedHashMap<>(); Set<String> modes = new HashSet<>();
        for (var file : files) {
            String id = text(file, "operationId"), mode = text(file, "mode"), hash = text(file, "sha256");
            require(id.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"), "Invalid operation identity");
            require(Set.of("NEW_PROJECT", "UPDATE_COPY").contains(mode), "Unsupported native output mode");
            require(hashes.putIfAbsent(id, hash) == null, "Duplicate output operation identity"); modes.add(mode);
            Path output = outputs.resolve(id + ".qea"); requireSqlite(output); matchSha(output, hash);
            require(Files.size(output) == file.path("bytes").asLong(-1), "Downloaded size differs from export evidence");
        }
        require(modes.equals(Set.of("NEW_PROJECT", "UPDATE_COPY")), "Evidence must cover both native output modes");
        var result = JSON.createObjectNode(); result.put("schemaVersion", 1);
        result.put("status", "FILE_EVIDENCE_VALIDATED"); result.put("realProductStatus", "NOT_EXECUTED");
        result.put("operatorEvidenceStatus", "NOT_PROVIDED"); result.put("checkedFiles", hashes.size());
        result.put("templateSha256", text(exports, "templateSha256")); result.put("pluginSha256", text(exports, "pluginSha256"));
        result.put("limitation", "File and operator-evidence consistency only; this JUnit harness does not execute Enterprise Architect or Pro Cloud Server.");
        if (!request.hasNonNull("eaEvidence")) return result;

        Path eaPath = resolve(directory, text(request, "eaEvidence")); JsonNode ea = read(eaPath);
        require(ea.path("schemaVersion").asInt() == 1 && "Sparx Enterprise Architect".equals(text(ea, "product")), "An explicit EA product record is required");
        String version = text(ea, "version"); text(ea, "operator");
        try { Instant.parse(text(ea, "executionDate")); } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid EA evidence execution time", invalid); }
        Path log = resolve(eaPath.getParent(), text(ea, "log")); requireFile(log, MAX_FILE); matchSha(log, text(ea, "logSha256"));
        JsonNode observations = ea.path("results");
        require(observations.isArray() && observations.size() == hashes.size(), "Every output requires an EA observation");
        Set<String> observed = new HashSet<>();
        for (var observation : observations) {
            String id = text(observation, "operationId");
            require(observed.add(id) && hashes.containsKey(id), "Duplicate or unknown EA observation");
            require(hashes.get(id).equals(text(observation, "outputSha256")), "EA evidence belongs to different QEA bytes");
            require("PASSED".equals(text(observation, "result")), "An operator reported an unsuccessful EA check");
            JsonNode checked = observation.path("checked"); require(checked.isArray(), "EA checks must be explicit");
            Set<String> names = new HashSet<>(); checked.forEach(value -> names.add(value.asText()));
            require(names.containsAll(REQUIRED_CHECKS), "EA opening, reopen and supported semantics need individual observations");
            require(observation.path("knownLosses").isArray(), "Known losses must be recorded explicitly, including an empty list");
        }
        result.put("status", "OPERATOR_EVIDENCE_VALIDATED"); result.put("operatorEvidenceStatus", "VALIDATED");
        result.put("operatorReportedProductVersion", version); result.put("operatorEvidenceSha256", PackagedPluginSupport.sha256(eaPath));
        return result;
    }

    private static JsonNode read(Path file) throws IOException { requireFile(file, MAX_JSON); return JSON.readTree(Files.readString(file)); }
    private static String text(JsonNode node, String field) {
        require(node.path(field).isTextual(), "Missing textual evidence field: " + field);
        String value = node.path(field).asText(); require(!value.isBlank() && value.length() <= 4096, "Invalid evidence field: " + field); return value;
    }
    private static Path resolve(Path directory, String value) {
        Path path = Path.of(value); return (path.isAbsolute() ? path : directory.resolve(path)).normalize();
    }
    private static void requireFile(Path path, long limit) throws IOException {
        require(Files.isRegularFile(path), "Evidence input is not a regular file: " + path.getFileName());
        require(Files.size(path) > 0 && Files.size(path) <= limit, "Evidence input exceeds its byte bound");
    }
    private static void requireSqlite(Path file) throws IOException {
        requireFile(file, MAX_FILE);
        try (var stream = Files.newInputStream(file)) {
            require(Arrays.equals(stream.readNBytes(16), "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII)), "QEA input lacks the SQLite header");
        }
    }
    private static void matchSha(Path path, String expected) throws IOException {
        requireSha(expected); require(PackagedPluginSupport.sha256(path).equals(expected), "Evidence SHA-256 mismatch: " + path.getFileName());
    }
    private static void requireSha(String value) { require(value.matches("[0-9a-f]{64}"), "Invalid evidence SHA-256"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
