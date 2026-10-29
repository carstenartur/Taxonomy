package com.taxonomy.acceptance;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Durable evidence for the opt-in test, never an automated semantic quality assessor. */
final class ReformulationComparisonEvidence {
    static final List<String> DIMENSIONS = List.of("sourceConditionLoss", "unmarkedAdditions",
            "concreteQuestionUsefulness", "duplicatesAndConflicts", "readability");
    private static final List<String> VARIANTS = List.of("walk-up", "single-complete-prompt");
    private final Path directory;
    private final ObjectMapper json;
    private final ObjectNode manifest;

    private ReformulationComparisonEvidence(Path directory, ObjectMapper json, ObjectNode manifest) {
        this.directory = directory; this.json = json; this.manifest = manifest;
    }

    static ReformulationComparisonEvidence create(Path root, ObjectMapper json, String sourceCommit, JsonNode corpus) throws IOException {
        require(sourceCommit != null && sourceCommit.matches("[0-9a-f]{40}"), "source commit is required");
        inventory(corpus);
        String id = UUID.randomUUID().toString();
        Files.createDirectories(root);
        Path directory = Files.createDirectory(root.resolve(id));
        var manifest = json.createObjectNode().put("evidenceVersion", 1).put("runId", id)
                .put("sourceCommit", sourceCommit).put("recordedAt", Instant.now().toString())
                .put("status", "NOT_RUN").put("humanQualityGate", "NOT_REVIEWED")
                .put("pricingBasis", "NOT_PROVIDED; no currency cost inferred")
                .put("corpusSha256", hash(corpus.toString()));
        var cases = manifest.putArray("cases");
        for (var c : corpus.path("cases")) cases.addObject().put("id", c.path("id").asText())
                .put("sourceSha256", hash(c.path("source").asText())).put("caseSha256", hash(c.toString()))
                .put("outcome", "NOT_RUN");
        var run = new ReformulationComparisonEvidence(directory, json, manifest);
        atomic(directory.resolve("corpus.json"), corpus.toPrettyString());
        run.persist();
        return run;
    }

    Path directory() { return directory; }

    void start(String provider, String model) throws IOException {
        require(!provider.isBlank() && !model.isBlank(), "provider and model are required");
        manifest.put("provider", provider).put("model", model).put("status", "RUNNING");
        for (var c : manifest.path("cases")) ((ObjectNode) c).put("outcome", "PENDING");
        persist();
    }

    void notRun(String reason) throws IOException {
        manifest.put("status", "NOT_RUN").put("reason", reason);
        atomic(directory.resolve("summary.md"), summary(manifest, null));
        persist();
    }

    void beginCase(String id) throws IOException {
        require(manifest.path("status").asText().equals("RUNNING"), "run is not active");
        var entry = entry(id);
        require(entry.path("outcome").asText().equals("PENDING"), "case has already started");
        entry.put("outcome", "RUNNING"); persist();
    }

    void prepareCase(String id, JsonNode input) throws IOException {
        var entry = entry(id);
        require(entry.path("outcome").asText().equals("RUNNING"), "case is not active");
        require(hash(input.path("originalText").asText()).equals(entry.path("sourceSha256").asText()), "input source mismatch");
        require(input.path("frozenContext").isObject(), "frozen context is required");
        Path path = directory.resolve(id).resolve("input.json"); Files.createDirectories(path.getParent());
        atomic(path, input.toPrettyString());
        entry.put("inputSha256", hash(Files.readAllBytes(path)))
                .put("contextSha256", hash(input.path("frozenContext").toString()));
        persist();
    }

    void recordVariant(String id, String variant, ObjectNode result) throws IOException {
        require(VARIANTS.contains(variant), "unknown comparison variant");
        var entry = entry(id);
        require(entry.has("inputSha256") && entry.path("outcome").asText().equals("RUNNING"), "case input is not prepared");
        require(!entry.has(variant), "variant is already recorded");
        Path path = directory.resolve(id).resolve(variant + ".json");
        atomic(path, result.toPrettyString());
        var recorded = entry.putObject(variant).put("artifactSha256", hash(Files.readAllBytes(path)))
                .put("outputSha256", hash(result.path("output").toString()))
                .put("schemaOutcome", realValidated(result, manifest.path("provider").asText()) ? "VALIDATED" : "FAILED")
                .put("durationMillis", result.path("durationMillis").asLong())
                .put("actualHttpAttempts", result.path("actualHttpAttempts").asLong())
                .put("actualTransportRetries", result.path("actualTransportRetries").asLong());
        recorded.set("transportAttempts", result.path("transportAttempts").deepCopy());
        persist();
    }

    void completeCase(String id) throws IOException {
        var entry = entry(id);
        require(VARIANTS.stream().allMatch(entry::has), "both variants must be recorded");
        boolean valid = VARIANTS.stream().allMatch(v -> entry.path(v).path("schemaOutcome").asText().equals("VALIDATED"));
        if (valid) atomic(directory.resolve(id).resolve("human-review.json"), reviewTemplate(entry).toPrettyString());
        entry.put("outcome", valid ? "COMPLETE" : "SCHEMA_FAILURE"); persist();
    }

    String finish() throws IOException {
        for (var c : manifest.path("cases")) {
            var entry = (ObjectNode) c;
            if (entry.path("outcome").asText().equals("PENDING")) entry.put("outcome", "NOT_EXECUTED");
            if (entry.path("outcome").asText().equals("RUNNING")) entry.put("outcome", "INCOMPLETE");
        }
        String status = completionStatus(manifest);
        try {
            if (status.equals("OUTPUTS_REQUIRE_HUMAN_REVIEW")) validateOutputs(directory, json, manifest, read(directory.resolve("corpus.json"), json));
            var finalManifest = manifest.deepCopy(); finalManifest.put("status", status).put("finishedAt", Instant.now().toString());
            // A summary/template/output write failure can never precede a complete manifest on disk.
            atomic(directory.resolve("summary.md"), summary(finalManifest, null));
            manifest.put("status", status).put("finishedAt", finalManifest.path("finishedAt").asText()); persist();
            return status;
        } catch (IOException | RuntimeException failure) {
            abort(failure); throw failure;
        }
    }

    void abort(Throwable failure) throws IOException {
        manifest.put("status", "INCOMPLETE_EXECUTION").put("failureType", failure.getClass().getSimpleName())
                .put("finishedAt", Instant.now().toString());
        for (var c : manifest.path("cases")) {
            var entry = (ObjectNode) c;
            if (entry.path("outcome").asText().equals("RUNNING")) entry.put("outcome", "INCOMPLETE");
            if (entry.path("outcome").asText().equals("PENDING")) entry.put("outcome", "NOT_EXECUTED");
        }
        persist(); // Do this first; a broken summary path must not hide the terminal failure.
    }

    static String completionStatus(JsonNode manifest) {
        var cases = manifest.path("cases");
        if (cases.size() != 5) return "INCOMPLETE_EXECUTION";
        boolean failed = false;
        var ids = new HashSet<String>();
        for (var entry : cases) {
            if (!ids.add(entry.path("id").asText())) return "INCOMPLETE_EXECUTION";
            String outcome = entry.path("outcome").asText();
            if (!outcome.equals("COMPLETE") && !outcome.equals("SCHEMA_FAILURE")) return "INCOMPLETE_EXECUTION";
            failed |= outcome.equals("SCHEMA_FAILURE");
            for (String variant : VARIANTS) {
                if (!entry.path(variant).path("artifactSha256").asText().matches("[0-9a-f]{64}")) return "INCOMPLETE_EXECUTION";
                failed |= !entry.path(variant).path("schemaOutcome").asText().equals("VALIDATED");
            }
        }
        return failed ? "SCHEMA_FAILURE" : "OUTPUTS_REQUIRE_HUMAN_REVIEW";
    }

    @FunctionalInterface
    interface ArtifactPublisher { void publish(Path path, byte[] bytes) throws IOException; }

    static void validateReviews(Path directory, ObjectMapper json, JsonNode corpus) throws IOException {
        validateReviews(directory, json, corpus, ReformulationComparisonEvidence::atomic);
    }

    static void validateReviews(Path directory, ObjectMapper json, JsonNode corpus, ArtifactPublisher publisher) throws IOException {
        directory = checkedDirectory(directory);
        var manifest = (ObjectNode) read(directory.resolve("manifest.json"), json);
        manifest.put("humanQualityGate", "NOT_REVIEWED"); manifest.remove("reviewValidationSha256");
        Path archive = directory.resolveSibling(directory.getFileName() + "-reviewed.zip");
        try {
            // The manifest is the commit marker. Invalidate it before inspecting mutable evidence.
            atomic(directory.resolve("manifest.json"), manifest.toPrettyString());
            require(manifest.path("status").asText().equals("OUTPUTS_REQUIRE_HUMAN_REVIEW"), "run is not complete");
            require(hash(corpus.toString()).equals(manifest.path("corpusSha256").asText()), "corpus hash mismatch");
            var files = captureReviewEvidence(directory, corpus);
            clearReviewArtifacts(directory, archive, manifest);
            validateOutputs(directory, json, manifest, corpus, files);
            var validation = json.createObjectNode().put("runId", manifest.path("runId").asText())
                    .put("validatedAt", Instant.now().toString()).put("validation", "REVIEW_RECORDED")
                    .put("meaning", "Evidence binding and review completeness only; no semantic quality verdict");
            var reviews = validation.putArray("reviews");
            for (var entry : manifest.path("cases")) {
                Path path = directory.resolve(entry.path("id").asText()).resolve("human-review.json");
                var review = parse(evidenceBytes(directory, path, files), json);
                validateReview(manifest, entry, review, directory, json, files);
                var recorded = reviews.addObject().put("case", entry.path("id").asText()).put("reviewSha256", hash(evidenceBytes(directory, path, files)))
                        .put("reviewer", review.path("reviewer").asText()).put("reviewedAt", review.path("reviewedAt").asText());
                var assessment = recorded.putObject("assessment");
                for (String dimension : DIMENSIONS) assessment.set(dimension, review.path(dimension).deepCopy());
            }
            byte[] validationBytes = validation.toPrettyString().getBytes(StandardCharsets.UTF_8);
            var reviewed = manifest.deepCopy(); reviewed.put("humanQualityGate", "REVIEW_RECORDED")
                    .put("reviewValidationSha256", hash(validationBytes));
            byte[] summaryBytes = summary(reviewed, validation).getBytes(StandardCharsets.UTF_8);
            byte[] manifestBytes = reviewed.toPrettyString().getBytes(StandardCharsets.UTF_8);
            // Freeze exactly the bytes validated above. Generated artifacts are staged in memory,
            // never discovered by walking mutable directory contents during ZIP generation.
            files.put("manifest.json", manifestBytes);
            files.put("summary.md", summaryBytes);
            files.put("human-review-validation.json", validationBytes);
            byte[] zip = archive(files);
            publisher.publish(archive, zip);
            publisher.publish(directory.resolve("human-review-validation.json"), validationBytes);
            publisher.publish(directory.resolve("summary.md"), summaryBytes);
            publisher.publish(directory.resolve("manifest.json"), manifestBytes);
        } catch (IOException | RuntimeException failure) {
            // Any failed archive/write/rename must remove the successful presentation as well as
            // its commit marker. Use the real publisher for recovery, not a failed injected writer.
            try { atomic(directory.resolve("manifest.json"), manifest.toPrettyString()); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            try { clearReviewArtifacts(directory, archive, manifest); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static void clearReviewArtifacts(Path directory, Path archive, JsonNode manifest) throws IOException {
        IOException failed = null;
        for (Path path : List.of(directory.resolve("human-review-validation.json"), archive, directory.resolve("summary.md"))) {
            try { Files.deleteIfExists(path); }
            catch (IOException failure) { if (failed == null) failed = failure; else failed.addSuppressed(failure); }
        }
        try { atomic(directory.resolve("summary.md"), summary(manifest, null)); }
        catch (IOException failure) { if (failed == null) failed = failure; else failed.addSuppressed(failure); }
        if (failed != null) throw failed;
    }

    private static Map<String, byte[]> captureReviewEvidence(Path directory, JsonNode corpus) throws IOException {
        var raw = new LinkedHashSet<String>(); raw.add("corpus.json");
        var directories = inventory(corpus).keySet();
        for (String id : directories) for (String artifact : List.of("input.json", "walk-up.json", "single-complete-prompt.json", "human-review.json"))
            raw.add(id + "/" + artifact);
        var allowed = new HashSet<>(raw); allowed.addAll(List.of("manifest.json", "summary.md", "human-review-validation.json"));
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                if (path.equals(directory)) continue;
                var parts = java.util.stream.StreamSupport.stream(directory.relativize(path).spliterator(), false)
                        .map(Path::toString).toList();
                // Filenames are matched to a canonical inventory; never convert arbitrary names
                // (for example a literal Linux backslash) into different ZIP entry names.
                String canonical = String.join("/", parts);
                if (parts.stream().anyMatch(part -> part.indexOf('\\') >= 0) || Files.isSymbolicLink(path)) throw new IOException("Unsafe evidence path");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!directories.contains(canonical)) throw new IOException("Unexpected evidence directory");
                } else if (!allowed.contains(canonical) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected evidence artifact");
                }
            }
        }
        var snapshot = new LinkedHashMap<String, byte[]>();
        for (String name : raw) snapshot.put(name, safeBytes(directory.resolve(name)));
        return snapshot;
    }

    private static void validateOutputs(Path directory, ObjectMapper json, JsonNode manifest, JsonNode corpus) throws IOException {
        validateOutputs(directory, json, manifest, corpus, null);
    }

    private static void validateOutputs(Path directory, ObjectMapper json, JsonNode manifest, JsonNode corpus, Map<String, byte[]> files) throws IOException {
        require(manifest.path("runId").asText().equals(directory.getFileName().toString()), "run binding mismatch");
        try { UUID.fromString(manifest.path("runId").asText()); } catch (RuntimeException e) { throw new IllegalArgumentException("run binding mismatch"); }
        require(manifest.path("sourceCommit").asText().matches("[0-9a-f]{40}"), "source commit is required");
        require(hash(corpus.toString()).equals(manifest.path("corpusSha256").asText()), "corpus hash mismatch");
        require(hash(parse(evidenceBytes(directory, directory.resolve("corpus.json"), files), json).toString()).equals(manifest.path("corpusSha256").asText()), "recorded corpus hash mismatch");
        var expected = inventory(corpus);
        require(manifest.path("cases").size() == 5, "case inventory must contain exactly five cases");
        var seen = new HashSet<String>();
        for (var entry : manifest.path("cases")) {
            String id = entry.path("id").asText();
            require(expected.containsKey(id) && seen.add(id), "case inventory mismatch");
            require(hash(expected.get(id).toString()).equals(entry.path("caseSha256").asText()), "case inventory hash mismatch");
        }
        require(seen.equals(expected.keySet()), "case inventory mismatch");
        require(completionStatus(manifest).equals("OUTPUTS_REQUIRE_HUMAN_REVIEW"), "run has incomplete or failed outputs");
        require(!manifest.path("provider").asText().isBlank() && !manifest.path("model").asText().isBlank(), "provider/model missing");
        for (var entry : manifest.path("cases")) {
            String id = entry.path("id").asText(); Path path = directory.resolve(id);
            require(hash(evidenceBytes(directory, path.resolve("input.json"), files)).equals(entry.path("inputSha256").asText()), "input hash mismatch");
            var input = parse(evidenceBytes(directory, path.resolve("input.json"), files), json);
            require(hash(input.path("originalText").asText()).equals(entry.path("sourceSha256").asText())
                    && hash(expected.get(id).path("source").asText()).equals(entry.path("sourceSha256").asText()), "source hash mismatch");
            require(hash(input.path("frozenContext").toString()).equals(entry.path("contextSha256").asText()), "context hash mismatch");
            for (String variant : VARIANTS) {
                Path output = path.resolve(variant + ".json");
                require(hash(evidenceBytes(directory, output, files)).equals(entry.path(variant).path("artifactSha256").asText()), "output artifact hash mismatch");
                var result = parse(evidenceBytes(directory, output, files), json);
                require(hash(result.path("output").toString()).equals(entry.path(variant).path("outputSha256").asText()), "output hash mismatch");
                require(realValidated(result, manifest.path("provider").asText()), "output lacks validated real HTTP evidence");
                require(result.path("transportAttempts").equals(entry.path(variant).path("transportAttempts")), "recorded observation mismatch");
                for (String field : List.of("actualHttpAttempts", "actualTransportRetries", "durationMillis"))
                    require(result.path(field).isIntegralNumber() && result.path(field).asLong() == entry.path(variant).path(field).asLong(), "recorded observation mismatch");
            }
        }
    }

    private static void validateReview(JsonNode manifest, JsonNode entry, JsonNode review, Path directory, ObjectMapper json, Map<String, byte[]> files) throws IOException {
        require(review.path("runId").asText().equals(manifest.path("runId").asText())
                && review.path("case").asText().equals(entry.path("id").asText())
                && review.path("walkUpOutputSha256").asText().equals(entry.path("walk-up").path("outputSha256").asText())
                && review.path("singlePromptOutputSha256").asText().equals(entry.path("single-complete-prompt").path("outputSha256").asText()), "review binding mismatch");
        require(review.path("status").asText().equals("REVIEWED"), "review is pending");
        require(!review.path("reviewer").asText().isBlank(), "reviewer is required");
        try {
            Instant date = Instant.parse(review.path("reviewedAt").asText());
            require(!date.isBefore(Instant.parse(manifest.path("recordedAt").asText())) && !date.isAfter(Instant.now().plusSeconds(300)), "review date outside run interval");
        } catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("review date must be an ISO instant"); }
        var outputText = new HashMap<String, List<String>>();
        for (String variant : VARIANTS) {
            var strings = new ArrayList<String>();
            collectText(parse(evidenceBytes(directory, directory.resolve(entry.path("id").asText()).resolve(variant + ".json"), files), json).path("output"), strings);
            outputText.put(variant, strings);
        }
        for (String dimension : DIMENSIONS) {
            var criterion = review.path(dimension);
            for (String score : List.of("walkUpScore0to4", "singlePromptScore0to4"))
                require(criterion.path(score).isIntegralNumber() && criterion.path(score).canConvertToInt()
                        && criterion.path(score).intValue() >= 0 && criterion.path(score).intValue() <= 4, "missing or invalid score: " + dimension);
            require(!criterion.path("evidence").asText().isBlank(), "review rationale is required: " + dimension);
            var cited = new HashSet<String>();
            require(criterion.path("citations").isArray(), "citations are required: " + dimension);
            for (var citation : criterion.path("citations")) {
                String variant = citation.path("variant").asText(), passage = citation.path("passage").asText();
                require(VARIANTS.contains(variant) && !passage.isBlank(), "invalid citation: " + dimension);
                require(outputText.get(variant).stream().anyMatch(text -> text.contains(passage)), "citation not present in exact output: " + dimension);
                cited.add(variant);
            }
            require(cited.containsAll(VARIANTS), "both outputs require citations: " + dimension);
        }
    }

    private ObjectNode reviewTemplate(JsonNode entry) {
        var review = json.createObjectNode().put("runId", manifest.path("runId").asText()).put("case", entry.path("id").asText())
                .put("walkUpOutputSha256", entry.path("walk-up").path("outputSha256").asText())
                .put("singlePromptOutputSha256", entry.path("single-complete-prompt").path("outputSha256").asText())
                .put("status", "NOT_REVIEWED").put("reviewer", "").put("reviewedAt", "");
        for (String criterion : DIMENSIONS) {
            var item = review.putObject(criterion); item.putNull("walkUpScore0to4"); item.putNull("singlePromptScore0to4");
            item.put("evidence", ""); item.putArray("citations");
        }
        return review;
    }

    private static boolean realValidated(JsonNode result, String provider) {
        if (!result.path("schemaOutcome").asText().equals("VALIDATED") || !result.path("output").isObject()
                || !result.path("transportAttempts").isArray() || result.path("transportAttempts").isEmpty()) return false;
        long count = 0, retries = 0;
        for (var observation : result.path("transportAttempts")) {
            if (!observation.path("source").asText().equals("HTTP") || !observation.path("provider").asText().equals(provider)) return false;
            count++; if (observation.path("retryIndex").asInt() > 0) retries++;
        }
        return result.path("actualHttpAttempts").asLong() == count && result.path("actualTransportRetries").asLong() == retries;
    }

    private static Map<String, JsonNode> inventory(JsonNode corpus) {
        var cases = new LinkedHashMap<String, JsonNode>();
        require(corpus.path("cases").isArray() && corpus.path("cases").size() == 5, "corpus requires exactly five cases");
        for (var c : corpus.path("cases")) {
            String id = c.path("id").asText();
            require(id.matches("[a-z0-9-]+") && !c.path("source").asText().isBlank() && cases.put(id, c) == null, "invalid corpus case inventory");
        }
        return cases;
    }

    private ObjectNode entry(String id) {
        for (var c : manifest.path("cases")) if (c.path("id").asText().equals(id)) return (ObjectNode) c;
        throw new IllegalArgumentException("unknown comparison case");
    }
    private void persist() throws IOException { atomic(directory.resolve("manifest.json"), manifest.toPrettyString()); }
    private static JsonNode read(Path path, ObjectMapper json) throws IOException { return parse(safeBytes(path), json); }
    private static JsonNode parse(byte[] bytes, ObjectMapper json) throws IOException {
        return json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
    }
    private static byte[] evidenceBytes(Path directory, Path path, Map<String, byte[]> snapshot) throws IOException {
        if (snapshot == null) return safeBytes(path);
        String name = String.join("/", java.util.stream.StreamSupport.stream(directory.relativize(path).spliterator(), false)
                .map(Path::toString).toList());
        byte[] bytes = snapshot.get(name);
        if (bytes == null) throw new IOException("Artifact missing from validated inventory");
        return bytes;
    }
    private static Path checkedDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (!absolute.equals(absolute.normalize())) throw new IOException("Noncanonical evidence path");
        Path component = absolute.getRoot();
        for (Path part : absolute) {
            component = component.resolve(part);
            if (Files.isSymbolicLink(component) || !Files.isDirectory(component, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Unsafe evidence directory");
        }
        if (!absolute.toRealPath().equals(absolute)) throw new IOException("Evidence path escapes its root");
        return absolute;
    }
    private static byte[] safeBytes(Path path) throws IOException {
        Path parent = checkedDirectory(path.toAbsolutePath().getParent());
        Path file = parent.resolve(path.getFileName());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))
            throw new IOException("Unsafe evidence artifact");
        // NOFOLLOW also protects the leaf if it is replaced with a link after the metadata check.
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = Channels.newInputStream(channel)) { return stream.readAllBytes(); }
    }
    private static void collectText(JsonNode node, List<String> strings) {
        if (node.isString()) strings.add(node.asText()); else node.forEach(child -> collectText(child, strings));
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void atomic(Path path, String value) throws IOException { atomic(path, value.getBytes(StandardCharsets.UTF_8)); }
    private static void atomic(Path path, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), ".evidence-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static byte[] archive(Map<String, byte[]> files) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (var artifact : files.entrySet()) {
                String name = artifact.getKey();
                if (name.indexOf('\\') >= 0 || name.startsWith("/") || !Path.of(name).normalize().toString().replace('\\', '/').equals(name))
                    throw new IOException("Noncanonical archive artifact");
                zip.putNextEntry(new ZipEntry(name)); zip.write(artifact.getValue()); zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
    private static String summary(JsonNode manifest, JsonNode validation) {
        var text = new StringBuilder("# Recorded model-comparison evidence\n\nRun: ").append(manifest.path("runId").asText())
                .append("\n\nSource commit: ").append(manifest.path("sourceCommit").asText())
                .append("\n\nExecution: ").append(manifest.path("status").asText())
                .append("\n\nHuman review: ").append(manifest.path("humanQualityGate").asText())
                .append("\n\nProvider/model: ").append(manifest.path("provider").asText("unavailable"))
                .append(" / ").append(manifest.path("model").asText("unavailable"))
                .append("\n\n| Case | Outcome | Strategy | HTTP attempts | Retries | Duration ms |\n|---|---|---|---:|---:|---:|\n");
        long attempts = 0, retries = 0;
        for (var entry : manifest.path("cases")) for (String variant : VARIANTS) {
            var result = entry.path(variant); attempts += result.path("actualHttpAttempts").asLong(); retries += result.path("actualTransportRetries").asLong();
            text.append("| ").append(entry.path("id").asText()).append(" | ").append(entry.path("outcome").asText()).append(" | ")
                    .append(variant).append(" | ").append(result.path("actualHttpAttempts").asLong()).append(" | ")
                    .append(result.path("actualTransportRetries").asLong()).append(" | ").append(result.path("durationMillis").asLong()).append(" |\n");
        }
        text.append("\nRecorded HTTP attempts: ").append(attempts).append("; recorded retries: ").append(retries)
                .append(". Provider usage, when available, is retained in each output artifact.\n\n")
                .append("No semantic quality verdict or price/cost improvement is inferred. Literal presence is diagnostic only.\n");
        if (validation != null) for (var review : validation.path("reviews")) {
            text.append("\nReview: ").append(review.path("case").asText()).append(" — ")
                    .append(review.path("reviewer").asText()).append(" at ").append(review.path("reviewedAt").asText())
                    .append("\n\n| Human criterion | Walk-up score | Single-prompt score | Reviewer rationale |\n|---|---:|---:|---|\n");
            for (String dimension : DIMENSIONS) {
                var assessment = review.path("assessment").path(dimension);
                text.append("| ").append(dimension).append(" | ").append(assessment.path("walkUpScore0to4").asInt())
                        .append(" | ").append(assessment.path("singlePromptScore0to4").asInt()).append(" | ")
                        .append(assessment.path("evidence").asText().replace("|", "\\|").replace("\n", " ").replace("\r", " ")).append(" |\n");
            }
        }
        return text.toString();
    }
}
