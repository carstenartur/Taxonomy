package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed high-severity gate for one or more CodeQL SARIF reports. */
final class CodeQlSarifGate {

    private CodeQlSarifGate() {
    }

    static int run(
            String[] rawArguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        CommandArguments arguments = null;
        try {
            arguments = CommandArguments.parse(rawArguments, workingDirectory);
            Inspection inspection = inspect(
                    arguments.sarifPaths(), arguments.threshold());
            writeReport(arguments.report(), inspection);

            output.println("CodeQL results: " + inspection.resultCount()
                    + "; blocking: " + inspection.blocking().size());
            for (Finding finding : inspection.blocking()) {
                error.println("- [" + finding.level()
                        + "/security-severity="
                        + finding.securitySeverity() + "] "
                        + finding.ruleId() + ": " + finding.message());
            }
            return inspection.blocking().isEmpty() ? 0 : 1;
        } catch (IOException | IllegalArgumentException failure) {
            if (arguments != null) {
                removeStaleReport(arguments.report(), failure);
            }
            error.println("::error::CodeQL gate failed: " + failure.getMessage());
            return 1;
        }
    }

    static Inspection inspect(List<Path> candidates, double threshold)
            throws IOException {
        if (!Double.isFinite(threshold) || threshold < 0.0) {
            throw new IllegalArgumentException(
                    "CodeQL security-severity threshold must be a finite non-negative number");
        }

        List<Path> existing = candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .toList();
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("no SARIF files supplied");
        }

        List<Finding> blocking = new ArrayList<>();
        int resultCount = 0;
        for (Path path : existing) {
            Map<String, Object> report = FlatJson.parseObject(
                    Files.readString(path, StandardCharsets.UTF_8));
            for (Object runValue : optionalList(report.get("runs"), "runs")) {
                Map<String, Object> run = object(runValue, "SARIF run");
                Map<String, Map<String, Object>> rules = rules(run);
                for (Object resultValue : optionalList(
                        run.get("results"), "results")) {
                    resultCount++;
                    Map<String, Object> result = object(
                            resultValue, "SARIF result");
                    String ruleId = scalarText(
                            result.get("ruleId"), "unknown");
                    double severity = securitySeverity(rules.get(ruleId));
                    String level = scalarText(
                            result.get("level"), "warning");
                    if (severity >= threshold || "error".equals(level)) {
                        blocking.add(new Finding(
                                path.toString(),
                                ruleId,
                                severity,
                                level,
                                messageText(result)));
                    }
                }
            }
        }
        return new Inspection(
                List.copyOf(existing),
                resultCount,
                threshold,
                List.copyOf(blocking));
    }

    private static Map<String, Map<String, Object>> rules(
            Map<String, Object> run) {
        Object toolValue = run.get("tool");
        if (toolValue == null) {
            return Map.of();
        }
        Map<String, Object> tool = object(toolValue, "SARIF tool");
        Object driverValue = tool.get("driver");
        if (driverValue == null) {
            return Map.of();
        }
        Map<String, Object> driver = object(
                driverValue, "SARIF tool driver");
        LinkedHashMap<String, Map<String, Object>> result =
                new LinkedHashMap<>();
        for (Object ruleValue : optionalList(
                driver.get("rules"), "tool.driver.rules")) {
            Map<String, Object> rule = object(ruleValue, "SARIF rule");
            Object id = rule.get("id");
            if (id != null) {
                result.put(String.valueOf(id), rule);
            }
        }
        return result;
    }

    private static double securitySeverity(Map<String, Object> rule) {
        if (rule == null) {
            return 0.0;
        }
        Object propertiesValue = rule.get("properties");
        if (propertiesValue == null) {
            return 0.0;
        }
        Map<String, Object> properties = object(
                propertiesValue, "SARIF rule properties");
        Object value = properties.get("security-severity");
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static String messageText(Map<String, Object> result) {
        Object messageValue = result.get("message");
        if (messageValue == null) {
            return "";
        }
        Map<String, Object> message = object(
                messageValue, "SARIF result message");
        return scalarText(message.get("text"), "");
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

    private static List<?> optionalList(Object value, String description) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "SARIF " + description + " must be an array");
        }
        return list;
    }

    private static String scalarText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException(
                "SARIF scalar field contains a structured value");
    }

    record Inspection(
            List<Path> sarifFiles,
            int resultCount,
            double threshold,
            List<Finding> blocking) {
    }

    record Finding(
            String file,
            String ruleId,
            double securitySeverity,
            String level,
            String message) {
        Map<String, Object> toJson() {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("file", file);
            value.put("ruleId", ruleId);
            value.put("securitySeverity", securitySeverity);
            value.put("level", level);
            value.put("message", message);
            return value;
        }
    }

    private record CommandArguments(
            List<Path> sarifPaths,
            double threshold,
            Path report) {

        static CommandArguments parse(
                String[] rawArguments,
                Path workingDirectory) {
            List<Path> paths = new ArrayList<>();
            double threshold = 7.0;
            Path report = workingDirectory.resolve("target/codeql-gate.json");
            for (int index = 0; index < rawArguments.length; index++) {
                String token = rawArguments[index];
                if ("--threshold".equals(token)) {
                    threshold = Double.parseDouble(
                            requiredValue(rawArguments, ++index, token));
                } else if ("--report".equals(token)) {
                    report = resolve(
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
                    List.copyOf(paths), threshold, report);
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
