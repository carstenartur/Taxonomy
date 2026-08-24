package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fail-closed high-severity gate for one or more CodeQL SARIF reports. */
final class CodeQlSarifGate {

    private static final String SUPPORTED_SARIF_VERSION = "2.1.0";
    private static final long SUPPORTED_BASELINE_SCHEMA_VERSION = 1L;
    private static final double MAX_SECURITY_SEVERITY = 10.0;

    private CodeQlSarifGate() {
    }

    static int run(
            String[] rawArguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        Path report = workingDirectory.resolve("target/codeql-gate.json");
        try {
            report = CommandArguments.reportPath(
                    rawArguments, workingDirectory);
            CommandArguments arguments = CommandArguments.parse(
                    rawArguments, workingDirectory);
            report = arguments.report();
            Inspection inspection = inspect(
                    arguments.sarifPaths(),
                    arguments.threshold(),
                    arguments.baseline());
            writeReport(report, inspection);

            output.println("CodeQL results: " + inspection.resultCount()
                    + "; blocking: " + inspection.blocking().size());
            if (inspection.baselineFile() != null) {
                output.println("CodeQL baseline accepted: "
                        + inspection.acceptedBaseline().size()
                        + "; unused: " + inspection.unusedBaseline().size());
            }
            for (Finding finding : inspection.blocking()) {
                error.println("- [" + finding.level()
                        + "/security-severity="
                        + finding.securitySeverity() + "] "
                        + finding.ruleId() + ": " + finding.message());
            }
            return inspection.blocking().isEmpty() ? 0 : 1;
        } catch (IOException | IllegalArgumentException failure) {
            removeStaleReport(report, failure);
            error.println("::error::CodeQL gate failed: " + failure.getMessage());
            return 1;
        }
    }

    static Inspection inspect(List<Path> candidates, double threshold)
            throws IOException {
        return inspect(candidates, threshold, null);
    }

    static Inspection inspect(
            List<Path> candidates,
            double threshold,
            Path baselinePath) throws IOException {
        if (!Double.isFinite(threshold)
                || threshold < 0.0
                || threshold > MAX_SECURITY_SEVERITY) {
            throw new IllegalArgumentException(
                    "CodeQL security-severity threshold must be between 0.0 and 10.0");
        }

        List<Path> requested = candidates.stream()
                .map(Path::normalize)
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("no SARIF files supplied");
        }
        List<Path> invalid = requested.stream()
                .filter(path -> !Files.isRegularFile(path))
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(
                    "SARIF input is missing or not a regular file: "
                            + invalid.stream()
                                    .map(Path::toString)
                                    .reduce((left, right) -> left + ", " + right)
                                    .orElse("<unknown>"));
        }

        Baseline baseline = Baseline.load(baselinePath);
        Set<FindingKey> usedBaseline = new LinkedHashSet<>();
        List<Finding> acceptedBaseline = new ArrayList<>();
        List<Finding> blocking = new ArrayList<>();
        int resultCount = 0;
        for (Path path : requested) {
            Map<String, Object> report = FlatJson.parseObject(
                    Files.readString(path, StandardCharsets.UTF_8));
            requireSupportedVersion(report);
            List<?> runs = requiredListField(report, "runs", "runs");
            if (runs.isEmpty()) {
                throw new IllegalArgumentException(
                        "SARIF runs must contain at least one run");
            }
            for (Object runValue : runs) {
                Map<String, Object> run = object(runValue, "SARIF run");
                Map<String, Map<String, Object>> rules = rules(run);
                for (Object resultValue : listField(
                        run, "results", "results")) {
                    resultCount++;
                    Map<String, Object> result = object(
                            resultValue, "SARIF result");
                    String ruleId = requiredStringField(
                            result, "ruleId", "SARIF result ruleId");
                    if (ruleId.isBlank()) {
                        throw new IllegalArgumentException(
                                "SARIF result ruleId must not be blank");
                    }
                    Map<String, Object> rule = rules.get(ruleId);
                    if (rule == null) {
                        throw new IllegalArgumentException(
                                "SARIF result references rule '" + ruleId
                                        + "' without matching tool metadata");
                    }
                    double severity = securitySeverity(rule);
                    // Preserve the existing policy: only an explicit result-level
                    // override participates in the error branch. Missing result
                    // levels remain warnings; high security-severity is resolved
                    // independently from the referenced rule metadata.
                    String level = stringField(
                            result, "level", "warning")
                            .toLowerCase(Locale.ROOT);
                    requireKnownLevel(level);
                    String message = messageText(result);
                    if (severity >= threshold || "error".equals(level)) {
                        Finding finding = new Finding(
                                path.toString(),
                                ruleId,
                                severity,
                                level,
                                message,
                                primaryArtifactUri(result),
                                primaryLocationLineHash(result));
                        FindingKey key = finding.key();
                        if (key != null && baseline.contains(key)) {
                            acceptedBaseline.add(finding);
                            usedBaseline.add(key);
                        } else {
                            blocking.add(finding);
                        }
                    }
                }
            }
        }
        return new Inspection(
                List.copyOf(requested),
                resultCount,
                threshold,
                baseline.path(),
                List.copyOf(acceptedBaseline),
                baseline.unusedEntries(usedBaseline),
                List.copyOf(blocking));
    }

    private static void requireSupportedVersion(Map<String, Object> report) {
        String version = requiredStringField(
                report, "version", "SARIF version");
        if (!SUPPORTED_SARIF_VERSION.equals(version)) {
            throw new IllegalArgumentException(
                    "Unsupported SARIF version: " + version);
        }
    }

    private static Map<String, Map<String, Object>> rules(
            Map<String, Object> run) {
        Map<String, Object> tool = requiredObjectField(
                run, "tool", "SARIF tool");
        Map<String, Object> driver = requiredObjectField(
                tool, "driver", "SARIF tool driver");
        LinkedHashMap<String, Map<String, Object>> result =
                new LinkedHashMap<>();
        addRules(result, driver, "tool.driver.rules");

        for (Object extensionValue : listField(
                tool, "extensions", "tool.extensions")) {
            Map<String, Object> extension = object(
                    extensionValue, "SARIF tool extension");
            addRules(result, extension, "tool.extensions[].rules");
        }
        return result;
    }

    private static void addRules(
            Map<String, Map<String, Object>> target,
            Map<String, Object> component,
            String description) {
        for (Object ruleValue : listField(
                component, "rules", description)) {
            Map<String, Object> rule = object(ruleValue, "SARIF rule");
            String id = requiredStringField(
                    rule, "id", "SARIF rule id");
            if (id.isBlank()) {
                throw new IllegalArgumentException(
                        "SARIF rule id must not be blank");
            }
            if (target.putIfAbsent(id, rule) != null) {
                throw new IllegalArgumentException(
                        "SARIF tool metadata contains duplicate rule id '"
                                + id + "'");
            }
        }
    }

    private static double securitySeverity(Map<String, Object> rule) {
        Map<String, Object> properties = optionalObjectField(
                rule, "properties", "SARIF rule properties");
        if (properties == null || !properties.containsKey("security-severity")) {
            return 0.0;
        }
        Object value = properties.get("security-severity");
        if (!(value instanceof String || value instanceof Number)) {
            throw new IllegalArgumentException(
                    "SARIF security-severity must be a numeric scalar value");
        }
        final double severity;
        try {
            severity = Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException invalidNumber) {
            throw new IllegalArgumentException(
                    "SARIF security-severity must be numeric", invalidNumber);
        }
        if (!Double.isFinite(severity)) {
            throw new IllegalArgumentException(
                    "SARIF security-severity must be finite");
        }
        if (severity < 0.0 || severity > MAX_SECURITY_SEVERITY) {
            throw new IllegalArgumentException(
                    "SARIF security-severity must be between 0.0 and 10.0");
        }
        return severity;
    }

    private static void requireKnownLevel(String level) {
        switch (level) {
            case "none", "note", "warning", "error" -> {
                return;
            }
            default -> throw new IllegalArgumentException(
                    "SARIF result level is unsupported: " + level);
        }
    }

    private static String messageText(Map<String, Object> result) {
        Map<String, Object> message = requiredObjectField(
                result, "message", "SARIF result message");
        if (message.containsKey("text")) {
            return requiredStringField(
                    message, "text", "SARIF result message text");
        }
        if (message.containsKey("markdown")) {
            return requiredStringField(
                    message, "markdown", "SARIF result message markdown");
        }
        throw new IllegalArgumentException(
                "SARIF result message must contain text or markdown");
    }

    private static String primaryArtifactUri(Map<String, Object> result) {
        List<?> locations = listField(
                result,
                "locations",
                "result locations");
        if (locations.isEmpty()) {
            return null;
        }
        Map<String, Object> location = object(
                locations.get(0),
                "SARIF result location");
        Map<String, Object> physicalLocation = optionalObjectField(
                location,
                "physicalLocation",
                "SARIF result physicalLocation");
        if (physicalLocation == null) {
            return null;
        }
        Map<String, Object> artifactLocation = optionalObjectField(
                physicalLocation,
                "artifactLocation",
                "SARIF result artifactLocation");
        if (artifactLocation == null
                || !artifactLocation.containsKey("uri")) {
            return null;
        }
        String uri = requiredStringField(
                artifactLocation,
                "uri",
                "SARIF result artifact URI");
        return uri.isBlank() ? null : uri;
    }

    private static String primaryLocationLineHash(Map<String, Object> result) {
        Map<String, Object> partialFingerprints = optionalObjectField(
                result,
                "partialFingerprints",
                "SARIF result partialFingerprints");
        if (partialFingerprints == null
                || !partialFingerprints.containsKey("primaryLocationLineHash")) {
            return null;
        }
        String fingerprint = requiredStringField(
                partialFingerprints,
                "primaryLocationLineHash",
                "SARIF primaryLocationLineHash");
        return fingerprint.isBlank() ? null : fingerprint;
    }

    private static void writeReport(Path report, Inspection inspection)
            throws IOException {
        Path absolute = report.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "CodeQL gate report has no parent directory: " + absolute);
        }
        Files.createDirectories(parent);

        List<Object> accepted = inspection.acceptedBaseline().stream()
                .map(Finding::toJson)
                .map(value -> (Object) value)
                .toList();
        List<Object> unused = inspection.unusedBaseline().stream()
                .map(BaselineEntry::toJson)
                .map(value -> (Object) value)
                .toList();
        List<Object> findings = inspection.blocking().stream()
                .map(Finding::toJson)
                .map(value -> (Object) value)
                .toList();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sarifFiles", inspection.sarifFiles().stream()
                .map(Path::toString)
                .toList());
        payload.put("resultCount", (long) inspection.resultCount());
        payload.put("threshold", inspection.threshold());
        if (inspection.baselineFile() != null) {
            payload.put("baselineFile", inspection.baselineFile().toString());
            payload.put("acceptedBaseline", accepted);
            payload.put("unusedBaseline", unused);
        }
        payload.put("blocking", findings);
        payload.put("status", findings.isEmpty() ? "PASS" : "FAIL");
        Files.writeString(
                absolute,
                FlatJson.pretty(payload) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void removeStaleReport(Path report, Throwable failure) {
        try {
            Path absolute = report.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)
                    || Files.isSymbolicLink(absolute)) {
                Files.deleteIfExists(absolute);
            }
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(
            Object value,
            String description) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    description + " must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> requiredObjectField(
            Map<String, Object> owner,
            String field,
            String description) {
        if (!owner.containsKey(field)) {
            throw new IllegalArgumentException(
                    description + " is required");
        }
        return object(owner.get(field), description);
    }

    private static Map<String, Object> optionalObjectField(
            Map<String, Object> owner,
            String field,
            String description) {
        if (!owner.containsKey(field)) {
            return null;
        }
        return object(owner.get(field), description);
    }

    private static List<?> requiredListField(
            Map<String, Object> owner,
            String field,
            String description) {
        if (!owner.containsKey(field)) {
            throw new IllegalArgumentException(
                    "SARIF " + description + " is required");
        }
        return listValue(owner.get(field), description);
    }

    private static List<?> listField(
            Map<String, Object> owner,
            String field,
            String description) {
        if (!owner.containsKey(field)) {
            return List.of();
        }
        return listValue(owner.get(field), description);
    }

    private static List<?> listValue(
            Object value,
            String description) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "SARIF " + description + " must be an array");
        }
        return list;
    }

    private static String requiredStringField(
            Map<String, Object> owner,
            String field,
            String description) {
        if (!owner.containsKey(field)) {
            throw new IllegalArgumentException(
                    description + " is required");
        }
        return stringValue(owner.get(field), description);
    }

    private static String stringField(
            Map<String, Object> owner,
            String field,
            String fallback) {
        if (!owner.containsKey(field)) {
            return fallback;
        }
        return stringValue(owner.get(field), "SARIF " + field);
    }

    private static String stringValue(Object value, String description) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(
                description + " must be a string");
    }

    record Inspection(
            List<Path> sarifFiles,
            int resultCount,
            double threshold,
            Path baselineFile,
            List<Finding> acceptedBaseline,
            List<BaselineEntry> unusedBaseline,
            List<Finding> blocking) {
    }

    record Finding(
            String file,
            String ruleId,
            double securitySeverity,
            String level,
            String message,
            String artifactUri,
            String primaryLocationLineHash) {
        FindingKey key() {
            return artifactUri == null || primaryLocationLineHash == null
                    ? null
                    : new FindingKey(
                            ruleId,
                            artifactUri,
                            primaryLocationLineHash);
        }

        Map<String, Object> toJson() {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("file", file);
            value.put("ruleId", ruleId);
            value.put("securitySeverity", securitySeverity);
            value.put("level", level);
            value.put("message", message);
            if (artifactUri != null) {
                value.put("artifactUri", artifactUri);
            }
            if (primaryLocationLineHash != null) {
                value.put(
                        "primaryLocationLineHash",
                        primaryLocationLineHash);
            }
            return value;
        }
    }

    private record FindingKey(
            String ruleId,
            String artifactUri,
            String primaryLocationLineHash) {
    }

    record BaselineEntry(
            String ruleId,
            String artifactUri,
            String primaryLocationLineHash,
            String rationale) {
        FindingKey key() {
            return new FindingKey(
                    ruleId,
                    artifactUri,
                    primaryLocationLineHash);
        }

        Map<String, Object> toJson() {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ruleId", ruleId);
            value.put("artifactUri", artifactUri);
            value.put(
                    "primaryLocationLineHash",
                    primaryLocationLineHash);
            value.put("rationale", rationale);
            return value;
        }
    }

    private record Baseline(
            Path path,
            Map<FindingKey, BaselineEntry> entries) {

        static Baseline load(Path candidate) throws IOException {
            if (candidate == null) {
                return new Baseline(null, Map.of());
            }
            Path path = candidate.normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException(
                        "CodeQL baseline is missing or not a regular file: "
                                + path);
            }
            Map<String, Object> payload = FlatJson.parseObject(
                    Files.readString(path, StandardCharsets.UTF_8));
            long schemaVersion = requiredIntegralField(
                    payload,
                    "schemaVersion",
                    "CodeQL baseline schemaVersion");
            if (schemaVersion != SUPPORTED_BASELINE_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported CodeQL baseline schemaVersion: "
                                + schemaVersion);
            }

            LinkedHashMap<FindingKey, BaselineEntry> entries =
                    new LinkedHashMap<>();
            for (Object rawEntry : requiredEntries(payload)) {
                Map<String, Object> entry = object(
                        rawEntry,
                        "CodeQL baseline entry");
                String ruleId = requiredNonBlankStringField(
                        entry,
                        "ruleId",
                        "CodeQL baseline ruleId");
                String artifactUri = requiredNonBlankStringField(
                        entry,
                        "artifactUri",
                        "CodeQL baseline artifactUri");
                String fingerprint = requiredNonBlankStringField(
                        entry,
                        "primaryLocationLineHash",
                        "CodeQL baseline primaryLocationLineHash");
                String rationale = requiredNonBlankStringField(
                        entry,
                        "rationale",
                        "CodeQL baseline rationale");
                BaselineEntry baselineEntry = new BaselineEntry(
                        ruleId,
                        artifactUri,
                        fingerprint,
                        rationale);
                if (entries.putIfAbsent(
                        baselineEntry.key(), baselineEntry) != null) {
                    throw new IllegalArgumentException(
                            "CodeQL baseline contains duplicate entry for rule '"
                                    + ruleId + "', artifact '" + artifactUri
                                    + "' and fingerprint '" + fingerprint
                                    + "'");
                }
            }
            return new Baseline(
                    path,
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(entries)));
        }

        boolean contains(FindingKey key) {
            return entries.containsKey(key);
        }

        List<BaselineEntry> unusedEntries(Set<FindingKey> used) {
            return entries.entrySet().stream()
                    .filter(entry -> !used.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();
        }

        private static List<?> requiredEntries(
                Map<String, Object> payload) {
            if (!payload.containsKey("entries")) {
                throw new IllegalArgumentException(
                        "CodeQL baseline entries are required");
            }
            Object value = payload.get("entries");
            if (!(value instanceof List<?> entries)) {
                throw new IllegalArgumentException(
                        "CodeQL baseline entries must be an array");
            }
            return entries;
        }

        private static long requiredIntegralField(
                Map<String, Object> owner,
                String field,
                String description) {
            if (!owner.containsKey(field)) {
                throw new IllegalArgumentException(
                        description + " is required");
            }
            Object value = owner.get(field);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(
                        description + " must be an integer");
            }
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException invalid) {
                throw new IllegalArgumentException(
                        description + " must be an integer", invalid);
            }
        }

        private static String requiredNonBlankStringField(
                Map<String, Object> owner,
                String field,
                String description) {
            String value = requiredStringField(owner, field, description);
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        description + " must not be blank");
            }
            return value;
        }
    }

    private record CommandArguments(
            List<Path> sarifPaths,
            double threshold,
            Path report,
            Path baseline) {

        static CommandArguments parse(
                String[] rawArguments,
                Path workingDirectory) {
            List<Path> paths = new ArrayList<>();
            double threshold = 7.0;
            Path report = workingDirectory.resolve("target/codeql-gate.json");
            Path baseline = null;
            for (int index = 0; index < rawArguments.length; index++) {
                String token = rawArguments[index];
                if ("--threshold".equals(token)) {
                    threshold = Double.parseDouble(
                            requiredValue(rawArguments, ++index, token));
                } else if ("--report".equals(token)) {
                    report = resolve(
                            workingDirectory,
                            requiredValue(rawArguments, ++index, token));
                } else if ("--baseline".equals(token)) {
                    baseline = resolve(
                            workingDirectory,
                            requiredValue(rawArguments, ++index, token));
                } else if (token.startsWith("--")) {
                    throw new IllegalArgumentException(
                            "Unknown CodeQL gate option: " + token);
                } else {
                    paths.add(resolve(workingDirectory, token));
                }
            }
            return new CommandArguments(
                    List.copyOf(paths), threshold, report, baseline);
        }

        static Path reportPath(
                String[] rawArguments,
                Path workingDirectory) {
            Path report = workingDirectory.resolve("target/codeql-gate.json");
            for (int index = 0; index < rawArguments.length - 1; index++) {
                if ("--report".equals(rawArguments[index])
                        && !rawArguments[index + 1].startsWith("--")) {
                    report = resolve(
                            workingDirectory, rawArguments[index + 1]);
                }
            }
            return report;
        }

        private static String requiredValue(
                String[] values,
                int index,
                String option) {
            if (index >= values.length || values[index].startsWith("--")) {
                throw new IllegalArgumentException(
                        "Missing value for " + option);
            }
            return values[index];
        }

        private static Path resolve(Path root, String value) {
            Path path = Path.of(value);
            return path.isAbsolute() ? path : root.resolve(path);
        }
    }
}
