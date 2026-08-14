package com.taxonomy.tooling;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates CodeQL SARIF reports and emits one deterministic gate manifest. */
public final class CodeQlSarifGate {

    private CodeQlSarifGate() {
    }

    public static Result inspect(List<Path> paths, double threshold)
            throws IOException {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("no SARIF files supplied");
        }
        List<Path> existing = paths.stream()
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
            for (Map<String, Object> run : objects(report.get("runs"))) {
                Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
                Map<String, Object> tool = object(run.get("tool"));
                Map<String, Object> driver = object(tool.get("driver"));
                for (Map<String, Object> rule : objects(driver.get("rules"))) {
                    String id = string(rule.get("id"), null);
                    if (id != null) {
                        rules.put(id, rule);
                    }
                }
                for (Map<String, Object> result : objects(run.get("results"))) {
                    resultCount++;
                    String ruleId = string(result.get("ruleId"), "unknown");
                    Map<String, Object> rule = rules.getOrDefault(
                            ruleId, Map.of());
                    double severity = securitySeverity(rule);
                    String level = string(result.get("level"), "warning");
                    if (severity >= threshold || "error".equals(level)) {
                        Map<String, Object> message = object(
                                result.get("message"));
                        blocking.add(new Finding(
                                path.toString(),
                                ruleId,
                                severity,
                                level,
                                string(message.get("text"), "")));
                    }
                }
            }
        }
        return new Result(existing, resultCount, threshold, List.copyOf(blocking));
    }

    public static void writeReport(Path path, Result result) throws IOException {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sarifFiles", result.paths().stream()
                .map(Path::toString)
                .toList());
        payload.put("resultCount", result.resultCount());
        payload.put("threshold", result.threshold());
        payload.put("blocking", result.blocking().stream()
                .map(Finding::asMap)
                .toList());
        payload.put("status", result.blocking().isEmpty() ? "PASS" : "FAIL");
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                JsonWriter.pretty(payload),
                StandardCharsets.UTF_8);
    }

    private static double securitySeverity(Map<String, Object> rule) {
        Map<String, Object> properties = object(rule.get("properties"));
        Object value = properties.get("security-severity");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text).doubleValue();
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> object = object(item);
            if (!object.isEmpty()) {
                objects.add(object);
            }
        }
        return objects;
    }

    private static String string(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    public record Finding(
            String file,
            String ruleId,
            double securitySeverity,
            String level,
            String message) {
        Map<String, Object> asMap() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("file", file);
            result.put("ruleId", ruleId);
            result.put("securitySeverity", securitySeverity);
            result.put("level", level);
            result.put("message", message);
            return result;
        }
    }

    public record Result(
            List<Path> paths,
            int resultCount,
            double threshold,
            List<Finding> blocking) {
        public boolean successful() {
            return blocking.isEmpty();
        }
    }
}
