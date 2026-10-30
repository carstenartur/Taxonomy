package com.taxonomy.dsl.requirements;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.serializer.TaxDslSerializer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Version-bound human realization evidence on the canonical requirement itself. */
public final class RequirementRealization {
    public static final String PREFIX = "x-realization-";
    private static final int MAX_DEPENDENCIES = 128;
    private static final Set<String> STATUSES = Set.of("OPEN", "PARTIAL", "COMPLETE");
    private static final TaxDslSerializer SERIALIZER = new TaxDslSerializer();

    private RequirementRealization() {}

    public enum State { OPEN, PARTIAL, COMPLETE, REVIEW_REQUIRED, INVALID_EVIDENCE }
    public record Review(int requirementVersion, String contentHash, String status,
                         String contributorProject, String actor, Instant occurredAt,
                         String rationale, List<String> dependencies) {
        public Review { dependencies = dependencies == null ? List.of() : List.copyOf(dependencies); }
    }
    public record Dependency(String id, String hash) {}
    public record Reviewed(int requirementVersion, String contentHash, String status,
                           String contributorProject, String actor, Instant occurredAt,
                           String rationale, List<Dependency> dependencies) {
        public Reviewed { dependencies = List.copyOf(dependencies); }
    }
    public record Assessment(State state, Reviewed reviewed, List<String> changedDependencies) {
        public Assessment { changedDependencies = List.copyOf(changedDependencies); }
    }

    public static String record(String source, String requirementId, Review review) {
        Objects.requireNonNull(review, "review");
        Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(source);
        BlockAst requirement = require(blocks, requirementId);
        validate(review);
        if (review.requirementVersion() != version(requirement)
                || !review.contentHash().equals(requirement.property("x-content-hash"))
                || !review.contentHash().equals(textHash(requirement.property("text"))))
            throw problem("REQUIREMENT_VERSION_MOVED", "The reviewed requirement version or exact text changed");
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PREFIX + "schema", "1");
        values.put(PREFIX + "version", Integer.toString(review.requirementVersion()));
        values.put(PREFIX + "content-hash", review.contentHash());
        values.put(PREFIX + "status", review.status());
        values.put(PREFIX + "contributor-project", review.contributorProject());
        values.put(PREFIX + "actor", review.actor());
        values.put(PREFIX + "occurred-at", review.occurredAt().toString());
        values.put(PREFIX + "rationale", review.rationale());
        List<String> dependencies = review.dependencies().stream().distinct().sorted().toList();
        values.put(PREFIX + "dependency-count", Integer.toString(dependencies.size()));
        for (int i = 0; i < dependencies.size(); i++) {
            String id = dependencies.get(i);
            BlockAst dependency = blocks.get(id);
            if (dependency == null || id.equals("requirement:" + requirementId))
                throw new CommandProblem("INVALID_REFERENCE", "dependencies", "Realization dependency is absent or refers to itself", List.of(id));
            values.put(PREFIX + "dependency." + i + ".id", id);
            values.put(PREFIX + "dependency." + i + ".hash", fingerprint(dependency));
        }
        String result = ArchitectureSemanticPatch.replace(source, requirement, replaceNamespace(requirement, values));
        return RequirementTrace.contribute(result, requirementId, review.contributorProject());
    }

    public static Assessment evaluate(String source, String requirementId) {
        return evaluate(ArchitectureSemanticPatch.index(source), requirementId);
    }

    public static Assessment evaluate(Map<String, BlockAst> blocks, String requirementId) {
        BlockAst requirement = require(blocks, requirementId);
        if (requirement.getProperties().stream().noneMatch(p -> p.key().startsWith(PREFIX)))
            return new Assessment(State.OPEN, null, List.of());
        Reviewed reviewed;
        try { reviewed = decode(requirement); }
        catch (IllegalArgumentException | java.time.DateTimeException | NullPointerException invalid) {
            return new Assessment(State.INVALID_EVIDENCE, null, List.of());
        }
        List<String> changed = new ArrayList<>();
        for (Dependency dependency : reviewed.dependencies()) {
            BlockAst current = blocks.get(dependency.id());
            if (current == null || !dependency.hash().equals(fingerprint(current))) changed.add(dependency.id());
        }
        boolean sameVersion;
        try {
            sameVersion = reviewed.requirementVersion() == version(requirement)
                    && reviewed.contentHash().equals(requirement.property("x-content-hash"))
                    && reviewed.contentHash().equals(textHash(requirement.property("text")));
        } catch (IllegalArgumentException | NullPointerException invalid) { sameVersion = false; }
        return new Assessment(sameVersion && changed.isEmpty() ? State.valueOf(reviewed.status()) : State.REVIEW_REQUIRED,
                reviewed, changed);
    }

    /** Portfolio regeneration keeps review evidence, whose freshness is evaluated against its frozen inputs. */
    public static BlockAst retain(BlockAst generated, BlockAst existing) {
        if (existing == null) return generated;
        List<PropertyAst> properties = new ArrayList<>(generated.getProperties());
        properties.removeIf(p -> p.key().startsWith(PREFIX));
        existing.getProperties().stream().filter(p -> p.key().startsWith(PREFIX)).forEach(properties::add);
        return copy(generated, properties);
    }

    /** An inverse may restore evidence without restoring a newer business text or version. */
    public static boolean reviewOnlyChange(String before, String after, String id) {
        if (!id.startsWith("requirement:")) return false;
        BlockAst previous = ArchitectureSemanticPatch.index(before).get(id), next = ArchitectureSemanticPatch.index(after).get(id);
        if (previous == null || next == null) return false;
        List<PropertyAst> oldValues = previous.getProperties().stream().filter(p -> !reviewField(p.key())).toList();
        List<PropertyAst> newValues = next.getProperties().stream().filter(p -> !reviewField(p.key())).toList();
        return new TaxDslSerializer().serialize(new DocumentAst(null, List.of(copy(previous, oldValues))))
                .equals(new TaxDslSerializer().serialize(new DocumentAst(null, List.of(copy(next, newValues)))));
    }

    private static boolean reviewField(String key) { return key.startsWith(PREFIX) || key.startsWith(RequirementTrace.PREFIX); }

    /** Exact text identity; whitespace is part of the immutable requirement version. */
    public static String textHash(String text) {
        Objects.requireNonNull(text, "text");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Reviewed decode(BlockAst requirement) {
        Map<String, String> values = new LinkedHashMap<>();
        for (PropertyAst property : requirement.getProperties()) if (property.key().startsWith(PREFIX)) {
            if (values.putIfAbsent(property.key(), property.value()) != null) throw new IllegalArgumentException("Duplicate realization field");
        }
        if (!"1".equals(values.get(PREFIX + "schema"))) throw new IllegalArgumentException("Unknown realization schema");
        int count = Integer.parseInt(values.get(PREFIX + "dependency-count"));
        if (count < 0 || count > MAX_DEPENDENCIES || values.size() != 9 + 2 * count)
            throw new IllegalArgumentException("Invalid realization envelope");
        List<Dependency> dependencies = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = values.get(PREFIX + "dependency." + i + ".id");
            String hash = values.get(PREFIX + "dependency." + i + ".hash");
            bounded(id, "dependency", 1024); hash(hash);
            if (id.equals(ArchitectureSemanticPatch.key(requirement)) || dependencies.stream().anyMatch(d -> d.id().equals(id)))
                throw new IllegalArgumentException("Duplicate or self realization dependency");
            dependencies.add(new Dependency(id, hash));
        }
        Review review = new Review(Integer.parseInt(values.get(PREFIX + "version")), values.get(PREFIX + "content-hash"),
                values.get(PREFIX + "status"), values.get(PREFIX + "contributor-project"), values.get(PREFIX + "actor"),
                Instant.parse(values.get(PREFIX + "occurred-at")), values.get(PREFIX + "rationale"),
                dependencies.stream().map(Dependency::id).toList());
        validate(review);
        return new Reviewed(review.requirementVersion(), review.contentHash(), review.status(), review.contributorProject(),
                review.actor(), review.occurredAt(), review.rationale(), dependencies);
    }

    private static void validate(Review review) {
        if (review.requirementVersion() < 1 || review.status() == null || !STATUSES.contains(review.status()))
            throw problem("INVALID_VALUE", "Unknown realization version or status");
        hash(review.contentHash());
        bounded(review.contributorProject(), "contributorProject", 64);
        bounded(review.actor(), "actor", 160);
        bounded(review.rationale(), "rationale", 8000);
        if (review.occurredAt() == null || review.dependencies().size() > MAX_DEPENDENCIES
                || !"OPEN".equals(review.status()) && review.dependencies().isEmpty())
            throw problem("INVALID_VALUE", "A realization decision requires its time and supporting dependencies");
        review.dependencies().forEach(id -> bounded(id, "dependency", 1024));
    }

    private static String fingerprint(BlockAst block) {
        return textHash(SERIALIZER.serialize(new DocumentAst(null, List.of(block))));
    }
    private static int version(BlockAst requirement) {
        int version = Integer.parseInt(requirement.property("x-version"));
        if (version < 1) throw problem("INVALID_VALUE", "Requirement version must be positive");
        return version;
    }
    private static BlockAst require(Map<String, BlockAst> blocks, String id) {
        BlockAst requirement = blocks.get("requirement:" + id);
        if (requirement == null) throw problem("NOT_FOUND", "Canonical requirement does not exist");
        return requirement;
    }
    private static BlockAst replaceNamespace(BlockAst original, Map<String, String> values) {
        List<PropertyAst> properties = new ArrayList<>(original.getProperties());
        properties.removeIf(p -> p.key().startsWith(PREFIX));
        values.forEach((key, value) -> properties.add(new PropertyAst(key, value, null)));
        return copy(original, properties);
    }
    private static BlockAst copy(BlockAst original, List<PropertyAst> properties) {
        Map<String, String> extensions = new LinkedHashMap<>(original.getExtensions());
        extensions.keySet().removeIf(key -> key.startsWith(PREFIX));
        properties.stream().filter(p -> p.key().startsWith(PREFIX)).forEach(p -> extensions.put(p.key(), p.value()));
        return new BlockAst(original.getKind(), original.getHeaderTokens(), properties, original.getChildren(), extensions, null);
    }
    private static void bounded(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.indexOf('\0') >= 0)
            throw problem("INVALID_VALUE", "Invalid realization " + field);
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw problem("INVALID_VALUE", "Invalid SHA-256 evidence hash");
    }
    private static CommandProblem problem(String code, String message) {
        return new CommandProblem(code, "realization", message, List.of());
    }
}
