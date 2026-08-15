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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic release-critical package and changed-source coverage gate.
 *
 * <p>The evaluator consumes the same authoritative aggregate JaCoCo XML as the
 * reactor-wide gate. It adds package-specific ratchets and, in a complete-history
 * pull-request checkout, coverage requirements for changed critical Java sources.</p>
 */
final class CriticalCoveragePolicy {

    static final List<String> COUNTER_TYPES = List.of("LINE", "BRANCH");
    private static final Set<String> COUNTER_TYPE_SET = Set.copyOf(COUNTER_TYPES);
    private static final Pattern COMMIT_ID = Pattern.compile("^[0-9a-f]{40}$");
    private static final String JACOCO_REPORT_DTD =
            "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">";

    private final ObjectMapper objectMapper = new ObjectMapper();

    CoveragePolicy loadPolicy(Path path) {
        return loadPolicy(path, LocalDate.now(ZoneOffset.UTC));
    }

    CoveragePolicy loadPolicy(Path path, LocalDate today) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(path));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException(
                    "Cannot read critical coverage policy " + path + ": "
                            + error.getMessage(), error);
        }
        if (!root.isObject() || root.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException(
                    "Critical coverage policy schemaVersion must be 1");
        }

        List<String> requiredCounters = readRequiredCounters(root);
        return new CoveragePolicy(
                requiredCounters,
                Collections.unmodifiableMap(readMinimums(
                        root.path("changedSourceMinimums"),
                        "changedSourceMinimums",
                        requiredCounters)),
                List.copyOf(readPrefixes(root.path("changedSourcePrefixes"))),
                List.copyOf(readPackageBudgets(
                        root.path("criticalPackages"), requiredCounters)),
                List.copyOf(readExceptions(
                        root.path("temporaryExceptions"), today)));
    }

    CoverageReport parseReport(Path path, List<String> requiredCounters) {
        final Document document;
        try {
            validateDoctype(path);
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            document = factory.newDocumentBuilder().parse(path.toFile());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (IOException | SAXException | ParserConfigurationException error) {
            throw new IllegalArgumentException(
                    "Cannot parse JaCoCo report " + path + ": "
                            + error.getMessage(), error);
        }
        Element root = document.getDocumentElement();
        if (root == null || !"report".equals(root.getTagName())) {
            throw new IllegalArgumentException(
                    "Expected JaCoCo <report> root for critical coverage");
        }

        Map<PackageKey, Map<String, Counter>> packages = new LinkedHashMap<>();
        Map<String, Map<String, Counter>> sourceFiles = new LinkedHashMap<>();
        for (Element group : directChildren(root, "group")) {
            String module = ReactorCoveragePolicy.normalizeGroupName(
                    requiredAttribute(group, "name", "JaCoCo group"));
            for (Element packageElement : directChildren(group, "package")) {
                String packageName = requiredAttribute(
                        packageElement, "name", "JaCoCo package");
                PackageKey packageKey = new PackageKey(module, packageName);
                if (packages.putIfAbsent(
                        packageKey,
                        parseCounterSet(
                                packageElement,
                                requiredCounters,
                                packageKey.scope(),
                                false)) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate JaCoCo package " + packageKey.scope());
                }
                for (Element sourceFile : directChildren(packageElement, "sourcefile")) {
                    String sourceName = requiredAttribute(
                            sourceFile, "name", "JaCoCo sourcefile");
                    String repositoryPath = module + "/src/main/java/"
                            + packageName + "/" + sourceName;
                    if (sourceFiles.putIfAbsent(
                            repositoryPath,
                            parseCounterSet(
                                    sourceFile,
                                    requiredCounters,
                                    "source:" + repositoryPath,
                                    true)) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate JaCoCo source file " + repositoryPath);
                    }
                }
            }
        }
        return new CoverageReport(
                Collections.unmodifiableMap(packages),
                Collections.unmodifiableMap(sourceFiles));
    }

    Evaluation evaluate(
            Path xmlPath,
            CoveragePolicy policy,
            ChangedSources changedSources) {
        CoverageReport report = parseReport(xmlPath, policy.requiredCounters());
        Map<String, TemporaryException> exceptionByScope = new LinkedHashMap<>();
        policy.temporaryExceptions().forEach(
                exception -> exceptionByScope.put(exception.scope(), exception));
        Set<String> appliedExceptions = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();
        StringBuilder text = new StringBuilder()
                .append("\nTaxonomy release-critical coverage\n\n")
                .append("Source: ").append(xmlPath).append('\n')
                .append("Policy: critical package and changed-source ratchets\n\n")
                .append("Critical package coverage:\n");

        for (PackageBudget budget : policy.criticalPackages()) {
            String scope = budget.key().scope();
            Map<String, Counter> counters = report.packages().get(budget.key());
            text.append("- ").append(scope).append('\n');
            if (counters == null) {
                recordViolation(
                        scope,
                        "package is absent from the authoritative JaCoCo report",
                        exceptionByScope,
                        appliedExceptions,
                        violations);
                text.append("  - MISSING\n");
                continue;
            }
            evaluateCounters(
                    scope,
                    counters,
                    budget.minimums(),
                    false,
                    text,
                    exceptionByScope,
                    appliedExceptions,
                    violations);
        }

        text.append("\nChanged critical source coverage:\n")
                .append("- Discovery: ").append(changedSources.description()).append('\n');
        if (changedSources.paths().isEmpty()) {
            text.append("- No changed critical Java source requires a diff gate.\n");
        }
        for (String path : changedSources.paths().stream().sorted().toList()) {
            String scope = "source:" + path;
            Map<String, Counter> counters = report.sourceFiles().get(path);
            text.append("- ").append(path).append('\n');
            if (counters == null) {
                recordViolation(
                        scope,
                        "changed source is absent from the authoritative JaCoCo report",
                        exceptionByScope,
                        appliedExceptions,
                        violations);
                text.append("  - MISSING\n");
                continue;
            }
            evaluateCounters(
                    scope,
                    counters,
                    policy.changedSourceMinimums(),
                    true,
                    text,
                    exceptionByScope,
                    appliedExceptions,
                    violations);
        }

        if (!policy.temporaryExceptions().isEmpty()) {
            text.append("\nTemporary exceptions:\n");
            for (TemporaryException exception : policy.temporaryExceptions()) {
                text.append("- ").append(exception.scope())
                        .append("; owner ").append(exception.owner())
                        .append("; expires ").append(exception.expiresOn())
                        .append("; ")
                        .append(appliedExceptions.contains(exception.scope())
                                ? "APPLIED" : "NOT NEEDED")
                        .append("; ").append(exception.rationale())
                        .append('\n');
            }
        }
        if (!violations.isEmpty()) {
            text.append("\nViolations:\n");
            violations.forEach(violation -> text.append("- ").append(violation).append('\n'));
        }
        boolean passed = violations.isEmpty();
        text.append("Result: ").append(passed ? "PASS" : "FAIL").append('\n');
        return new Evaluation(passed, text.toString());
    }

    ChangedSources discoverChangedSources(Path root, String baseRef) {
        Path repository = root.toAbsolutePath().normalize();
        if (baseRef == null || baseRef.isBlank()) {
            return new ChangedSources(Set.of(),
                    "not a pull-request checkout; aggregate and package gates only");
        }
        if (!Files.exists(repository.resolve(".git"))) {
            return new ChangedSources(Set.of(),
                    "source archive without Git history; aggregate and package gates only");
        }

        GitResult shallow = git(repository, "rev-parse", "--is-shallow-repository");
        if (shallow.exitCode() != 0) {
            throw new IllegalArgumentException(
                    "Cannot determine Git history completeness: " + shallow.output().strip());
        }
        if ("true".equals(shallow.output().strip())) {
            return new ChangedSources(Set.of(),
                    "shallow specialized checkout; canonical full-history CI owns diff coverage");
        }

        String normalizedBase = baseRef.strip();
        String candidate = COMMIT_ID.matcher(normalizedBase).matches()
                ? normalizedBase
                : "origin/" + normalizedBase;
        GitResult verified = git(repository, "rev-parse", "--verify", candidate + "^{commit}");
        if (verified.exitCode() != 0 && !candidate.equals(normalizedBase)) {
            candidate = normalizedBase;
            verified = git(repository, "rev-parse", "--verify", candidate + "^{commit}");
        }
        if (verified.exitCode() != 0) {
            throw new IllegalArgumentException(
                    "Cannot resolve coverage diff base '" + normalizedBase + "': "
                            + verified.output().strip());
        }
        String commit = verified.output().strip();
        GitResult diff = git(
                repository,
                "diff",
                "--name-only",
                "--diff-filter=ACMRTUXB",
                commit + "...HEAD",
                "--");
        if (diff.exitCode() != 0) {
            throw new IllegalArgumentException(
                    "Cannot determine changed sources from " + commit + ": "
                            + diff.output().strip());
        }

        Set<String> paths = new LinkedHashSet<>();
        for (String line : diff.output().lines().toList()) {
            String path = line.strip().replace('\\', '/');
            if (path.endsWith(".java")
                    && !path.endsWith("/package-info.java")
                    && !path.endsWith("/module-info.java")) {
                paths.add(path);
            }
        }
        return new ChangedSources(
                Collections.unmodifiableSet(paths),
                "complete Git diff against " + commit + " found " + paths.size()
                        + " changed Java source file(s) before critical-prefix filtering");
    }

    ChangedSources selectCriticalSources(
            ChangedSources discovered,
            List<String> prefixes) {
        Set<String> selected = new LinkedHashSet<>();
        discovered.paths().stream()
                .filter(path -> prefixes.stream().anyMatch(path::startsWith))
                .forEach(selected::add);
        return new ChangedSources(
                Collections.unmodifiableSet(selected),
                discovered.description() + "; " + selected.size()
                        + " file(s) are release-critical");
    }

    private void evaluateCounters(
            String scope,
            Map<String, Counter> counters,
            Map<String, Double> minimums,
            boolean branchMayBeAbsent,
            StringBuilder text,
            Map<String, TemporaryException> exceptionByScope,
            Set<String> appliedExceptions,
            List<String> violations) {
        for (String counterType : COUNTER_TYPES) {
            Counter counter = counters.get(counterType);
            double minimum = minimums.get(counterType);
            if (branchMayBeAbsent && "BRANCH".equals(counterType) && counter.total() == 0) {
                text.append("  - BRANCH: N/A (source has no branch counter total)\n");
                continue;
            }
            boolean passed = counter.total() > 0 && counter.ratio() >= minimum;
            text.append("  - ").append(counterType).append(": ")
                    .append(formatCounter(counter))
                    .append("; required ").append(formatPercent(minimum))
                    .append("; ").append(passed ? "PASS" : "FAIL")
                    .append('\n');
            if (!passed) {
                recordViolation(
                        scope,
                        counterType + " coverage " + formatPercent(counter.ratio())
                                + " is below " + formatPercent(minimum),
                        exceptionByScope,
                        appliedExceptions,
                        violations);
            }
        }
    }

    private List<String> readRequiredCounters(JsonNode root) {
        JsonNode node = root.path("requiredCounters");
        if (!node.isArray() || node.size() != COUNTER_TYPES.size()) {
            throw new IllegalArgumentException(
                    "Critical coverage requiredCounters must define LINE and BRANCH");
        }
        List<String> counters = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || !COUNTER_TYPE_SET.contains(value.asText())) {
                throw new IllegalArgumentException(
                        "Critical coverage requiredCounters may only contain LINE and BRANCH");
            }
            counters.add(value.asText());
        }
        if (!new LinkedHashSet<>(counters).equals(COUNTER_TYPE_SET)) {
            throw new IllegalArgumentException(
                    "Critical coverage requiredCounters must contain exactly LINE and BRANCH");
        }
        return List.copyOf(counters);
    }

    private Map<String, Double> readMinimums(
            JsonNode node,
            String label,
            List<String> requiredCounters) {
        if (!node.isObject() || node.size() != requiredCounters.size()) {
            throw new IllegalArgumentException(
                    label + " must define exactly LINE and BRANCH");
        }
        Map<String, Double> minimums = new LinkedHashMap<>();
        for (String counter : requiredCounters) {
            JsonNode value = node.path(counter);
            if (!value.isNumber()) {
                throw new IllegalArgumentException(
                        label + " minimum for " + counter + " must be numeric");
            }
            double ratio = value.asDouble();
            if (ratio <= 0.0 || ratio > 1.0) {
                throw new IllegalArgumentException(
                        label + " minimum for " + counter
                                + " must be greater than zero and at most one");
            }
            minimums.put(counter, ratio);
        }
        return minimums;
    }

    private List<String> readPrefixes(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "changedSourcePrefixes must be a non-empty list");
        }
        List<String> prefixes = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "changedSourcePrefixes entries must be non-empty strings");
            }
            String prefix = value.asText().replace('\\', '/');
            if (prefix.startsWith("/") || prefix.contains("../") || !prefix.endsWith("/")) {
                throw new IllegalArgumentException(
                        "changedSourcePrefixes must be repository-relative directories ending in '/'");
            }
            if (!unique.add(prefix)) {
                throw new IllegalArgumentException(
                        "changedSourcePrefixes contains duplicate '" + prefix + "'");
            }
            prefixes.add(prefix);
        }
        return prefixes;
    }

    private List<PackageBudget> readPackageBudgets(
            JsonNode node,
            List<String> requiredCounters) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "criticalPackages must be a non-empty list");
        }
        List<PackageBudget> budgets = new ArrayList<>();
        Set<PackageKey> unique = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isObject()) {
                throw new IllegalArgumentException(
                        "criticalPackages entries must be objects");
            }
            PackageKey key = new PackageKey(
                    ReactorCoveragePolicy.normalizeGroupName(requiredText(value, "module")),
                    requiredText(value, "package").replace('.', '/'));
            if (!unique.add(key)) {
                throw new IllegalArgumentException(
                        "criticalPackages contains duplicate " + key.scope());
            }
            budgets.add(new PackageBudget(
                    key,
                    Collections.unmodifiableMap(readMinimums(
                            value.path("minimums"),
                            "minimums for " + key.scope(),
                            requiredCounters))));
        }
        return budgets;
    }

    private List<TemporaryException> readExceptions(JsonNode node, LocalDate today) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(
                    "temporaryExceptions must be a list");
        }
        List<TemporaryException> exceptions = new ArrayList<>();
        Set<String> scopes = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isObject()) {
                throw new IllegalArgumentException(
                        "temporaryExceptions entries must be objects");
            }
            String scope = requiredText(value, "scope");
            if (!(scope.startsWith("package:") || scope.startsWith("source:"))) {
                throw new IllegalArgumentException(
                        "temporary exception scope must start with package: or source:");
            }
            if (!scopes.add(scope)) {
                throw new IllegalArgumentException(
                        "temporaryExceptions contains duplicate scope '" + scope + "'");
            }
            String rationale = requiredText(value, "rationale");
            String owner = requiredText(value, "owner");
            final LocalDate expiresOn;
            try {
                expiresOn = LocalDate.parse(requiredText(value, "expiresOn"));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "temporary exception expiresOn must use ISO date format", error);
            }
            if (expiresOn.isBefore(today)) {
                throw new IllegalArgumentException(
                        "temporary coverage exception for " + scope
                                + " expired on " + expiresOn);
            }
            exceptions.add(new TemporaryException(
                    scope, rationale, owner, expiresOn));
        }
        return exceptions;
    }

    private Map<String, Counter> parseCounterSet(
            Element element,
            List<String> requiredCounters,
            String scope,
            boolean allowMissingBranch) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (Element counter : directChildren(element, "counter")) {
            String type = counter.getAttribute("type");
            if (!requiredCounters.contains(type)) {
                continue;
            }
            if (counters.containsKey(type)) {
                throw new IllegalArgumentException(
                        "Duplicate " + type + " counter on " + scope);
            }
            counters.put(type, new Counter(
                    parseNonNegative(counter, "covered", type, scope),
                    parseNonNegative(counter, "missed", type, scope)));
        }
        if (allowMissingBranch && !counters.containsKey("BRANCH")) {
            counters.put("BRANCH", new Counter(0, 0));
        }
        List<String> missing = requiredCounters.stream()
                .filter(counter -> !counters.containsKey(counter))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing required counters " + String.join(", ", missing)
                            + " on " + scope);
        }
        return Collections.unmodifiableMap(counters);
    }

    private long parseNonNegative(
            Element counter,
            String attribute,
            String type,
            String scope) {
        final long value;
        try {
            value = Long.parseLong(counter.getAttribute(attribute));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Invalid " + type + " counter on " + scope, error);
        }
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Negative " + type + " counter on " + scope);
        }
        return value;
    }

    private static void recordViolation(
            String scope,
            String message,
            Map<String, TemporaryException> exceptionByScope,
            Set<String> appliedExceptions,
            List<String> violations) {
        if (exceptionByScope.containsKey(scope)) {
            appliedExceptions.add(scope);
        } else {
            violations.add(scope + ": " + message);
        }
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText().strip();
    }

    private static String requiredAttribute(
            Element element,
            String attribute,
            String label) {
        String value = element.getAttribute(attribute);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " has no " + attribute);
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

    private static void validateDoctype(Path path) throws IOException {
        String xml = Files.readString(path);
        int start = xml.indexOf("<!DOCTYPE");
        if (start < 0) {
            return;
        }
        int end = xml.indexOf('>', start);
        if (end < 0) {
            throw new IllegalArgumentException(
                    "Unterminated DOCTYPE in JaCoCo report " + path);
        }
        String declaration = xml.substring(start, end + 1)
                .replaceAll("\\s+", " ")
                .strip();
        if (!JACOCO_REPORT_DTD.equals(declaration)
                || xml.indexOf("<!DOCTYPE", end + 1) >= 0) {
            throw new IllegalArgumentException(
                    "Unsupported DOCTYPE in JaCoCo report " + path);
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static GitResult git(Path root, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new GitResult(process.waitFor(), output);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Cannot execute Git: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Git command was interrupted", error);
        }
    }

    private static String formatCounter(Counter counter) {
        if (counter.total() == 0) {
            return "N/A (0/0)";
        }
        return formatPercent(counter.ratio())
                + " (" + counter.covered() + "/" + counter.total() + ")";
    }

    private static String formatPercent(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100.0);
    }

    record PackageKey(String module, String packageName) {
        PackageKey {
            if (module == null || module.isBlank()
                    || packageName == null || packageName.isBlank()) {
                throw new IllegalArgumentException(
                        "Critical package identity must be complete");
            }
        }

        String scope() {
            return "package:" + module + ":" + packageName;
        }
    }

    record PackageBudget(PackageKey key, Map<String, Double> minimums) {
    }

    record TemporaryException(
            String scope,
            String rationale,
            String owner,
            LocalDate expiresOn) {
    }

    record CoveragePolicy(
            List<String> requiredCounters,
            Map<String, Double> changedSourceMinimums,
            List<String> changedSourcePrefixes,
            List<PackageBudget> criticalPackages,
            List<TemporaryException> temporaryExceptions) {
    }

    record Counter(long covered, long missed) {
        Counter {
            if (covered < 0 || missed < 0) {
                throw new IllegalArgumentException(
                        "Coverage counters must not be negative");
            }
        }

        long total() {
            return covered + missed;
        }

        double ratio() {
            return total() == 0 ? 0.0 : covered / (double) total();
        }
    }

    record CoverageReport(
            Map<PackageKey, Map<String, Counter>> packages,
            Map<String, Map<String, Counter>> sourceFiles) {
    }

    record ChangedSources(Set<String> paths, String description) {
    }

    record Evaluation(boolean passed, String text) {
    }

    private record GitResult(int exitCode, String output) {
    }
}
