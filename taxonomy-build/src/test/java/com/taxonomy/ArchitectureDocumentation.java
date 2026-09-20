package com.taxonomy;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Checks documentation against values supplied by the existing module gate. */
final class ArchitectureDocumentation {
    private static final Pattern ROW = Pattern.compile("(?m)^\\| `(?<module>taxonomy-[a-z0-9-]+)` \\|");
    private static final Pattern NODE = Pattern.compile("(taxonomy-[a-z0-9-]+)\\[\"(taxonomy-[a-z0-9-]+)\"\\]");
    private static final Pattern EDGE = Pattern.compile("(taxonomy-[a-z0-9-]+) --> (taxonomy-[a-z0-9-]+)");
    private static final String START = "<!-- architecture-feature-graph:start -->";
    private static final String END = "<!-- architecture-feature-graph:end -->";

    private ArchitectureDocumentation() {}

    static void checkInventory(String document, Set<String> modules) {
        Set<String> actual = new TreeSet<>();
        var matcher = ROW.matcher(document);
        while (matcher.find()) {
            require(actual.add(matcher.group("module")), "Duplicate module row: " + matcher.group("module"));
        }
        equal("Reactor module inventory", modules, actual);
    }

    static void checkFeatureGraph(String document, Set<String> modules, Set<String> dependencies) {
        document = document.replace("\r\n", "\n");
        int start = document.indexOf(START);
        int end = document.indexOf(END);
        require(start >= 0 && end > start, "Missing or reversed feature-graph markers");
        require(document.indexOf(START, start + START.length()) < 0
                && document.indexOf(END, end + END.length()) < 0, "Duplicate feature-graph markers");
        String block = document.substring(start + START.length(), end).strip();
        require(block.startsWith("```mermaid\n") && block.endsWith("\n```"), "Expected one Mermaid code block");
        String graph = block.substring("```mermaid\n".length(), block.length() - "\n```".length());
        var lines = graph.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        require(!lines.isEmpty() && lines.getFirst().equals("flowchart TB"), "Expected flowchart TB");
        Set<String> actualModules = new TreeSet<>();
        Set<String> actualDependencies = new TreeSet<>();
        for (String line : lines.subList(1, lines.size())) {
            var node = NODE.matcher(line);
            var edge = EDGE.matcher(line);
            if (node.matches()) {
                require(node.group(1).equals(node.group(2)), "Module label differs from its ID: " + line);
                require(actualModules.add(node.group(1)), "Duplicate graph node: " + line);
            } else if (edge.matches()) {
                require(actualDependencies.add(edge.group(1) + " -> " + edge.group(2)), "Duplicate graph edge: " + line);
            } else {
                throw new AssertionError("Unrecognized feature-graph line: " + line);
            }
        }
        equal("Feature graph nodes", modules, actualModules);
        for (String edge : actualDependencies) {
            String[] ends = edge.split(" -> ");
            require(actualModules.contains(ends[0]) && actualModules.contains(ends[1]), "Undeclared edge endpoint: " + edge);
        }
        equal("Feature graph dependencies", dependencies, actualDependencies);
    }

    private static void equal(String subject, Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        require(missing.isEmpty() && extra.isEmpty(), subject + ": missing=" + missing + ", extra=" + extra);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
