package com.taxonomy.build;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic policy evaluator for the authoritative reactor-wide JaCoCo report. */
final class ReactorCoveragePolicy {

    static final List<String> COUNTER_TYPES = List.of(
            "INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS");
    private static final Set<String> COUNTER_TYPE_SET = Set.copyOf(COUNTER_TYPES);
    private static final Pattern NON_KEY_CHARACTER = Pattern.compile("[^a-z0-9]+");

    private final ObjectMapper objectMapper = new ObjectMapper();

    CoveragePolicy loadPolicy(Path path) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(path));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException(
                    "Cannot read coverage policy " + path + ": " + error.getMessage(),
                    error);
        }
        if (!root.isObject() || root.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Coverage policy schemaVersion must be 1");
        }

        JsonNode countersNode = root.path("requiredCounters");
        if (!countersNode.isArray() || countersNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "Coverage policy requiredCounters must be a non-empty list");
        }
        List<String> configuredCounters = new ArrayList<>();
        for (JsonNode counter : countersNode) {
            if (!counter.isTextual() || !COUNTER_TYPE_SET.contains(counter.asText())) {
                throw new IllegalArgumentException(
                        "Coverage policy requiredCounters may only contain "
                                + String.join(", ", COUNTER_TYPES));
            }
            configuredCounters.add(counter.asText());
        }
        if (new LinkedHashSet<>(configuredCounters).size() != configuredCounters.size()) {
            throw new IllegalArgumentException(
                    "Coverage policy requiredCounters contains duplicates");
        }
        if (!new LinkedHashSet<>(configuredCounters).equals(COUNTER_TYPE_SET)) {
            List<String> missing = COUNTER_TYPES.stream()
                    .filter(counter -> !configuredCounters.contains(counter))
                    .toList();
            String suffix = missing.isEmpty()
                    ? ""
                    : " (missing " + String.join(", ", missing) + ")";
            throw new IllegalArgumentException(
                    "Coverage policy requiredCounters must contain exactly "
                            + String.join(", ", COUNTER_TYPES) + suffix);
        }

        JsonNode minimumsNode = root.path("aggregateMinimums");
        if (!minimumsNode.isObject() || minimumsNode.size() != COUNTER_TYPES.size()) {
            throw new IllegalArgumentException(
                    "Coverage policy aggregateMinimums must define exactly every required counter");
        }
        Map<String, Double> minimums = new LinkedHashMap<>();
        for (String counter : COUNTER_TYPES) {
            JsonNode value = minimumsNode.path(counter);
            if (!value.isNumber()) {
                throw new IllegalArgumentException(
                        "Coverage minimum for " + counter + " must be numeric");
            }
            double ratio = value.asDouble();
            if (ratio < 0.0 || ratio > 1.0) {
                throw new IllegalArgumentException(
                        "Coverage minimum for " + counter + " must be between 0 and 1");
            }
            minimums.put(counter, ratio);
        }
        if (minimums.get("BRANCH") <= 0.0) {
            throw new IllegalArgumentException(
                    "Coverage policy BRANCH minimum must be greater than zero");
        }

        JsonNode groupsNode = root.path("expectedGroups");
        if (!groupsNode.isArray() || groupsNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "Coverage policy expectedGroups must be a non-empty string list");
        }
        List<String> groups = new ArrayList<>();
        Set<String> normalizedGroups = new LinkedHashSet<>();
        for (JsonNode group : groupsNode) {
            if (!group.isTextual() || group.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "Coverage policy expectedGroups must be a non-empty string list");
            }
            String name = group.asText();
            if (!normalizedGroups.add(normalizeGroupName(name))) {
                throw new IllegalArgumentException(
                        "Coverage policy expectedGroups contains duplicate normalized names");
            }
            groups.add(name);
        }
        return new CoveragePolicy(
                COUNTER_TYPES,
                Collections.unmodifiableMap(minimums),
                List.copyOf(groups));
    }

    CoverageReport parseReport(Path path, List<String> requiredCounters) {
        Document document;
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            document = factory.newDocumentBuilder().parse(path.toFile());
        } catch (IOException | SAXException | ParserConfigurationException error) {
            throw new IllegalArgumentException(
                    "Cannot parse JaCoCo report " + path + ": " + error.getMessage(),
                    error);
        }
        Element root = document.getDocumentElement();
        if (root == null || !"report".equals(root.getTagName())) {
            String actual = root == null ? "none" : root.getTagName();
            throw new IllegalArgumentException(
                    "Expected JaCoCo <report> root, found <" + actual + ">");
        }

        Map<String, Counter> aggregate = parseCounterSet(root, requiredCounters);
        Map<String, Map<String, Counter>> groups = new LinkedHashMap<>();
        for (Element group : directChildren(root, "group")) {
            String name = group.getAttribute("name");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "JaCoCo report contains an unnamed group");
            }
            if (groups.containsKey(name)) {
                throw new IllegalArgumentException(
                        "JaCoCo report contains duplicate group '" + name + "'");
            }
            groups.put(name, parseCounterSet(group, requiredCounters));
        }
        return new CoverageReport(
                Collections.unmodifiableMap(aggregate),
                Collections.unmodifiableMap(groups));
    }

    Evaluation evaluate(Path xmlPath, CoveragePolicy policy) {
        CoverageReport report = parseReport(xmlPath, policy.requiredCounters());
        Map<String, String> actualByKey = new LinkedHashMap<>();
        report.groups().keySet().forEach(
                name -> actualByKey.put(normalizeGroupName(name), name));
        Map<String, String> expectedByKey = new LinkedHashMap<>();
        policy.expectedGroups().forEach(
                name -> expectedByKey.put(normalizeGroupName(name), name));

        List<String> missing = expectedByKey.keySet().stream()
                .filter(key -> !actualByKey.containsKey(key))
                .sorted()
                .map(expectedByKey::get)
                .toList();
        List<String> unexpected = actualByKey.keySet().stream()
                .filter(key -> !expectedByKey.containsKey(key))
                .sorted()
                .map(actualByKey::get)
                .toList();
        List<String> violations = policy.requiredCounters().stream()
                .filter(counter -> report.aggregate().get(counter).ratio()
                        < policy.aggregateMinimums().get(counter))
                .toList();
        boolean passed = missing.isEmpty() && violations.isEmpty();

        StringBuilder text = new StringBuilder()
                .append("Taxonomy reactor-wide JaCoCo coverage\n\n")
                .append("Source: ").append(xmlPath).append('\n')
                .append("Policy: versioned multi-counter aggregate ratchet\n\n")
                .append("Per-module coverage:\n");
        report.groups().keySet().stream().sorted().forEach(name -> {
            text.append("- ").append(name).append('\n');
            for (String counter : policy.requiredCounters()) {
                text.append("  - ").append(counter).append(": ")
                        .append(formatCounter(report.groups().get(name).get(counter)))
                        .append('\n');
            }
        });
        text.append("\nAggregate coverage:\n");
        for (String counter : policy.requiredCounters()) {
            Counter value = report.aggregate().get(counter);
            double minimum = policy.aggregateMinimums().get(counter);
            text.append("- ").append(counter).append(": ")
                    .append(formatCounter(value))
                    .append("; required ")
                    .append(formatPercent(minimum))
                    .append("; ")
                    .append(value.ratio() >= minimum ? "PASS" : "FAIL")
                    .append('\n');
        }
        if (!missing.isEmpty()) {
            text.append("Missing required module groups: ")
                    .append(String.join(", ", missing)).append('\n');
        }
        if (!unexpected.isEmpty()) {
            text.append("Additional report groups: ")
                    .append(String.join(", ", unexpected)).append('\n');
        }
        if (!violations.isEmpty()) {
            text.append("Counters below minimum: ")
                    .append(String.join(", ", violations)).append('\n');
        }
        text.append("Result: ").append(passed ? "PASS" : "FAIL").append('\n');
        return new Evaluation(passed, text.toString());
    }

    static String normalizeGroupName(String value) {
        String normalized = NON_KEY_CHARACTER.matcher(
                value.strip().toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("^-+|-+$", "");
        return normalized.startsWith("taxonomy-")
                ? normalized
                : "taxonomy-" + normalized;
    }

    private Map<String, Counter> parseCounterSet(
            Element element,
            List<String> requiredCounters) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (Element node : directChildren(element, "counter")) {
            String type = node.getAttribute("type");
            if (!requiredCounters.contains(type)) {
                continue;
            }
            if (counters.containsKey(type)) {
                throw new IllegalArgumentException(
                        "Duplicate " + type + " counter on " + describe(element));
            }
            long covered = parseNonNegativeCounter(node, "covered", type, element);
            long missed = parseNonNegativeCounter(node, "missed", type, element);
            counters.put(type, new Counter(covered, missed));
        }
        List<String> missing = requiredCounters.stream()
                .filter(counter -> !counters.containsKey(counter))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing required counters " + String.join(", ", missing)
                            + " on " + describe(element));
        }
        List<String> empty = counters.entrySet().stream()
                .filter(entry -> entry.getValue().total() == 0)
                .map(Map.Entry::getKey)
                .toList();
        if (!empty.isEmpty()) {
            throw new IllegalArgumentException(
                    "Required counters have no measurable total: "
                            + String.join(", ", empty) + " on " + describe(element));
        }
        return Collections.unmodifiableMap(counters);
    }

    private long parseNonNegativeCounter(
            Element counter,
            String attribute,
            String type,
            Element owner) {
        final long value;
        try {
            value = Long.parseLong(counter.getAttribute(attribute));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Invalid " + type + " counter on " + describe(owner), error);
        }
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Negative " + type + " counter values are not valid");
        }
        return value;
    }

    private static List<Element> directChildren(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static String describe(Element element) {
        String name = element.getAttribute("name");
        return "<" + element.getTagName() + " name='" + name + "'>";
    }

    private static String formatCounter(Counter counter) {
        return formatPercent(counter.ratio())
                + " (" + counter.covered() + "/" + counter.total() + ")";
    }

    private static String formatPercent(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100.0);
    }

    record Counter(long covered, long missed) {
        Counter {
            if (covered < 0 || missed < 0) {
                throw new IllegalArgumentException("Coverage counters must not be negative");
            }
        }

        long total() {
            return covered + missed;
        }

        double ratio() {
            return total() == 0 ? 0.0 : covered / (double) total();
        }
    }

    record CoveragePolicy(
            List<String> requiredCounters,
            Map<String, Double> aggregateMinimums,
            List<String> expectedGroups) {
    }

    record CoverageReport(
            Map<String, Counter> aggregate,
            Map<String, Map<String, Counter>> groups) {
    }

    record Evaluation(boolean passed, String text) {
    }
}
