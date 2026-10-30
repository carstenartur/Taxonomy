package com.taxonomy.dsl.requirements;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Portable, append-only project contributions and exact related requirement versions. */
public final class RequirementTrace {
    public static final String PREFIX = "x-trace-";
    private static final int MAX_ENTRIES = 256;

    private RequirementTrace() {}

    public record Contribution(String projectKey, int requirementVersion, String contentHash) {}
    public record Link(String projectKey, String requirementKey, int requirementVersion, String contentHash) {}

    public static String contribute(String source, String requirementId, String projectKey) {
        BlockAst requirement = require(source, requirementId);
        key(projectKey);
        int version = positiveVersion(requirement.property("x-version"));
        String hash = requirement.property("x-content-hash");
        hash(hash);
        if (!hash.equals(RequirementRealization.textHash(requirement.property("text"))))
            throw problem("Requirement content does not match its frozen version hash");
        Contribution contribution = new Contribution(projectKey, version, hash);
        List<Contribution> current = contributions(requirement);
        if (current.contains(contribution)) return source;
        if (current.size() >= MAX_ENTRIES) throw problem("Too many project contributions");
        String identity = identity(projectKey, Integer.toString(version), hash);
        return append(source, requirement, Map.of(
                PREFIX + "contribution." + identity + ".project", projectKey,
                PREFIX + "contribution." + identity + ".version", Integer.toString(version),
                PREFIX + "contribution." + identity + ".hash", hash));
    }

    public static String link(String source, String requirementId, String projectKey, String requirementKey,
                              int version, String contentHash) {
        BlockAst requirement = require(source, requirementId);
        key(projectKey); key(requirementKey); hash(contentHash);
        if (version < 1) throw problem("Related requirement version must be positive");
        Link link = new Link(projectKey, requirementKey, version, contentHash);
        List<Link> current = links(requirement);
        if (current.contains(link)) return source;
        if (current.size() >= MAX_ENTRIES) throw problem("Too many related requirement versions");
        String identity = identity(projectKey, requirementKey, Integer.toString(version), contentHash);
        return append(source, requirement, Map.of(
                PREFIX + "link." + identity + ".project", projectKey,
                PREFIX + "link." + identity + ".requirement", requirementKey,
                PREFIX + "link." + identity + ".version", Integer.toString(version),
                PREFIX + "link." + identity + ".hash", contentHash));
    }

    public static List<Contribution> contributions(BlockAst requirement) {
        List<Contribution> result = new ArrayList<>();
        for (var entry : groups(requirement, "contribution").entrySet()) {
            Map<String, String> values = entry.getValue();
            if (values.size() != 3) throw problem("Malformed project contribution");
            String project = values.get("project"), hash = values.get("hash");
            key(project); hash(hash);
            int version = positiveVersion(values.get("version"));
            if (!entry.getKey().equals(identity(project, Integer.toString(version), hash)))
                throw problem("Project contribution identity does not match its source");
            result.add(new Contribution(project, version, hash));
        }
        return List.copyOf(result);
    }

    public static List<Link> links(BlockAst requirement) {
        List<Link> result = new ArrayList<>();
        for (var entry : groups(requirement, "link").entrySet()) {
            Map<String, String> values = entry.getValue();
            if (values.size() != 4) throw problem("Malformed related requirement link");
            String project = values.get("project"), key = values.get("requirement"), hash = values.get("hash");
            key(project); key(key); hash(hash);
            int version = positiveVersion(values.get("version"));
            if (!entry.getKey().equals(identity(project, key, Integer.toString(version), hash)))
                throw problem("Related requirement identity does not match its source");
            result.add(new Link(project, key, version, hash));
        }
        return List.copyOf(result);
    }

    public static BlockAst retain(BlockAst generated, BlockAst existing) {
        if (existing == null) return generated;
        List<PropertyAst> properties = new ArrayList<>(generated.getProperties());
        properties.removeIf(p -> p.key().startsWith(PREFIX));
        existing.getProperties().stream().filter(p -> p.key().startsWith(PREFIX)).forEach(properties::add);
        return copy(generated, properties);
    }

    private static String append(String source, BlockAst requirement, Map<String, String> values) {
        List<PropertyAst> properties = new ArrayList<>(requirement.getProperties());
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> properties.add(new PropertyAst(entry.getKey(), entry.getValue(), null)));
        return ArchitectureSemanticPatch.replace(source, requirement, copy(requirement, properties));
    }

    private static Map<String, Map<String, String>> groups(BlockAst requirement, String kind) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        String prefix = PREFIX + kind + ".";
        for (PropertyAst property : requirement.getProperties()) if (property.key().startsWith(prefix)) {
            String suffix = property.key().substring(prefix.length());
            int separator = suffix.indexOf('.');
            if (separator != 64) throw problem("Malformed trace identity");
            String id = suffix.substring(0, separator); hash(id);
            Map<String, String> values = result.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
            if (values.putIfAbsent(suffix.substring(separator + 1), property.value()) != null)
                throw problem("Duplicate trace field");
            if (result.size() > MAX_ENTRIES) throw problem("Too many trace entries");
        }
        return result;
    }
    private static BlockAst require(String source, String id) {
        BlockAst requirement = ArchitectureSemanticPatch.index(source).get("requirement:" + id);
        if (requirement == null) throw problem("Canonical requirement does not exist");
        return requirement;
    }
    private static BlockAst copy(BlockAst original, List<PropertyAst> properties) {
        Map<String, String> extensions = new LinkedHashMap<>(original.getExtensions());
        properties.stream().filter(p -> p.key().startsWith(PREFIX)).forEach(p -> extensions.put(p.key(), p.value()));
        return new BlockAst(original.getKind(), original.getHeaderTokens(), properties, original.getChildren(), extensions, null);
    }
    private static String identity(String... values) { return RequirementRealization.textHash(String.join("\0", values)); }
    private static int positiveVersion(String value) {
        try { int version = Integer.parseInt(value); if (version > 0) return version; }
        catch (NumberFormatException ignored) { }
        throw problem("Invalid trace requirement version");
    }
    private static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw problem("Invalid business key in requirement trace");
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw problem("Invalid content hash in requirement trace");
    }
    private static CommandProblem problem(String message) { return new CommandProblem("INVALID_TRACE", "trace", message, List.of()); }
}
