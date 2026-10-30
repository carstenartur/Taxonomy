package com.taxonomy.versioning.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.requirements.RequirementRealization;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.signing.CommitSignature;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Bounded, branch-reachable history from authoritative Git, independent of search-index freshness. */
@Service
public final class RequirementHistoryService {
    private static final int MAX_OFFSET = 100_000;
    private static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    private static final Set<String> CONTENT_KEYS = Set.of("text", "x-version", "x-content-hash");
    private static final TaxDslSerializer SERIALIZER = new TaxDslSerializer();
    private final DslGitRepositoryFactory repositories;

    public RequirementHistoryService(DslGitRepositoryFactory repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    public record Query(String projectKey, String requirementKey, String text, String expectedHead,
                        int offset, int limit, int scanLimit) {
        public Query {
            projectKey = input(projectKey, "projectKey", 200);
            requirementKey = input(requirementKey, "requirementKey", 200);
            text = input(text, "text", 500);
            if ((projectKey == null) != (requirementKey == null))
                throw new IllegalArgumentException("projectKey and requirementKey must be supplied together");
            if (expectedHead != null) expectedHead = WorkspaceDslReadPort.normalizeCommitId(expectedHead);
            if (offset < 0 || offset > MAX_OFFSET) throw new IllegalArgumentException("offset must be between 0 and 100000");
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
            if (scanLimit < 1 || scanLimit > 500) throw new IllegalArgumentException("scanLimit must be between 1 and 500");
            if (offset > 0 && expectedHead == null) throw new IllegalArgumentException("Continuation requires expectedHead");
        }
    }
    public enum Coverage { COMPLETE, PARTIAL, LIMIT_REACHED }
    public enum ChangeKind { MERGE, GIT_CHANGE, ADDED, REMOVED, REQUIREMENT_CHANGED,
        DEPENDENCIES_CHANGED, REQUIREMENT_METADATA_CHANGED, REALIZATION_CHANGED }
    public record Change(String projectKey, String requirementKey, String requirementId,
                         List<ChangeKind> kinds, String beforeVersion, String afterVersion,
                         String beforeStatus, String afterStatus) {
        public Change { kinds = List.copyOf(kinds); }
    }
    public record Entry(String commitId, List<String> parents, String author, Instant timestamp,
                        String message, CommitSignature signature, List<Change> changes) {
        public Entry { parents = List.copyOf(parents); changes = List.copyOf(changes); }
    }
    public record Page(String head, List<Entry> entries, int offset, Integer nextOffset,
                       int scanned, Coverage coverage, String source) {
        public Page { entries = List.copyOf(entries); }
    }

    /** Resolve one authorized context; never search detached objects, another branch or another workspace. */
    public Page search(RepositoryContext context, Query query) throws IOException {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(query, "query");
        if (!Repository.isValidRefName(Constants.R_HEADS + context.branch())) throw new IllegalArgumentException("Invalid branch");
        DslGitRepository repository = repositories.resolveRepository(context);
        String head = repository.getHeadCommit(context.branch());
        if (query.expectedHead() != null && !query.expectedHead().equals(head))
            throw new BranchHeadConflictException(context.branch(), query.expectedHead(), head,
                    "Branch changed; restart history from the current architecture version");
        List<Entry> entries = new ArrayList<>();
        int scanned = 0;
        boolean more = false;
        if (head != null) {
            try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
                walk.sort(RevSort.TOPO);
                walk.sort(RevSort.COMMIT_TIME_DESC, true);
                walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
                boolean exhausted = false;
                for (int skipped = 0; skipped < query.offset(); skipped++) {
                    if (walk.next() == null) { exhausted = true; break; }
                }
                while (!exhausted && scanned < query.scanLimit() && entries.size() < query.limit()
                        && query.offset() + scanned < MAX_OFFSET) {
                    RevCommit commit = walk.next();
                    if (commit == null) { exhausted = true; break; }
                    scanned++;
                    Entry entry = entry(repository, commit, query);
                    if (entry != null) entries.add(entry);
                }
                more = !exhausted && walk.next() != null;
            }
        }
        // No-change CAS ensures a page never silently mixes two moving branch selections.
        synchronized (repository.getGitRepository()) {
            repository.verifyExpectedHead(context.branch(), head);
        }
        int next = query.offset() + scanned;
        Integer nextOffset = more && next < MAX_OFFSET ? next : null;
        Coverage coverage = !more ? Coverage.COMPLETE : nextOffset == null ? Coverage.LIMIT_REACHED : Coverage.PARTIAL;
        return new Page(head, entries, query.offset(), nextOffset, scanned, coverage, "LIVE_GIT");
    }

    private Entry entry(DslGitRepository repository, RevCommit commit, Query query) throws IOException {
        String afterDsl = repository.getDslAtCommit(commit.name(), MAX_DOCUMENT_BYTES);
        String beforeDsl = commit.getParentCount() == 0 ? ""
                : repository.getDslAtCommit(commit.getParent(0).name(), MAX_DOCUMENT_BYTES);
        Snapshot before = snapshot(beforeDsl), after = snapshot(afterDsl);
        List<Change> changes = changes(before, after, query, commit.getParentCount() > 1);
        if (query.projectKey() != null && changes.isEmpty()) return null;
        if (changes.isEmpty()) changes = List.of(new Change(null, null, null,
                List.of(commit.getParentCount() > 1 ? ChangeKind.MERGE : ChangeKind.GIT_CHANGE), null, null, null, null));
        if (query.text() != null) {
            String needle = query.text().toLowerCase(Locale.ROOT);
            String content = query.projectKey() == null ? beforeDsl + "\n" + afterDsl
                    : selectedText(before, query) + "\n" + selectedText(after, query);
            if (!(commit.getFullMessage() + "\n" + commit.getAuthorIdent().getName() + "\n" + content)
                    .toLowerCase(Locale.ROOT).contains(needle)) return null;
        }
        return new Entry(commit.name(), Arrays.stream(commit.getParents()).map(RevCommit::name).toList(),
                commit.getAuthorIdent().getName(), Instant.ofEpochSecond(commit.getCommitTime()),
                commit.getFullMessage(), repository.verifyCommit(commit.name()), changes);
    }

    private static List<Change> changes(Snapshot before, Snapshot after, Query query, boolean merge) {
        Set<String> identities = new LinkedHashSet<>(before.requirements().keySet());
        identities.addAll(after.requirements().keySet());
        List<Change> changes = new ArrayList<>();
        for (String identity : identities) {
            BlockAst a = before.requirements().get(identity), b = after.requirements().get(identity);
            BlockAst selected = b == null ? a : b;
            if (!matches(selected, query)) continue;
            List<ChangeKind> kinds = new ArrayList<>();
            if (a == null) kinds.add(ChangeKind.ADDED);
            else if (b == null) kinds.add(ChangeKind.REMOVED);
            else {
                if (!properties(a, PropertyGroup.CONTENT).equals(properties(b, PropertyGroup.CONTENT))) kinds.add(ChangeKind.REQUIREMENT_CHANGED);
                if (!a.getHeaderTokens().equals(b.getHeaderTokens())
                        || !a.getChildren().stream().map(RequirementHistoryService::serialized).toList()
                        .equals(b.getChildren().stream().map(RequirementHistoryService::serialized).toList())
                        || !properties(a, PropertyGroup.METADATA).equals(properties(b, PropertyGroup.METADATA))
                        || !portable(before, a).equals(portable(after, b))) kinds.add(ChangeKind.REQUIREMENT_METADATA_CHANGED);
                if (!properties(a, PropertyGroup.REALIZATION).equals(properties(b, PropertyGroup.REALIZATION))) kinds.add(ChangeKind.REALIZATION_CHANGED);
                Set<String> dependencies = new LinkedHashSet<>(dependencyIds(before, a));
                dependencies.addAll(dependencyIds(after, b));
                if (dependencies.stream().anyMatch(id -> !serialized(before.blocks().get(id)).equals(serialized(after.blocks().get(id)))))
                    kinds.add(ChangeKind.DEPENDENCIES_CHANGED);
            }
            if (kinds.isEmpty()) continue;
            if (merge) kinds.addFirst(ChangeKind.MERGE);
            changes.add(new Change(selected.property("x-project-key"), selected.property("x-requirement-key"),
                    selected.getHeaderTokens().getFirst(), kinds, property(a, "x-version"), property(b, "x-version"),
                    status(before, a), status(after, b)));
        }
        return List.copyOf(changes);
    }

    private enum PropertyGroup { CONTENT, METADATA, REALIZATION }
    private static Map<String, List<String>> properties(BlockAst block, PropertyGroup group) {
        Map<String, List<String>> result = new TreeMap<>();
        block.getProperties().forEach(property -> {
            boolean realization = property.key().startsWith(RequirementRealization.PREFIX);
            boolean content = CONTENT_KEYS.contains(property.key());
            if (group == PropertyGroup.CONTENT && content || group == PropertyGroup.REALIZATION && realization
                    || group == PropertyGroup.METADATA && !content && !realization)
                result.computeIfAbsent(property.key(), ignored -> new ArrayList<>()).add(property.value());
        });
        return result;
    }
    private static Snapshot snapshot(String dsl) throws IOException {
        try {
            Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(dsl);
            Map<String, BlockAst> requirements = new LinkedHashMap<>();
            for (BlockAst block : blocks.values()) {
                if (!"requirement".equals(block.getKind())) continue;
                if (block.propertyValues("x-project-key").size() > 1 || block.propertyValues("x-requirement-key").size() > 1)
                    throw new IOException("Ambiguous historical requirement identity");
                String project = block.property("x-project-key"), requirement = block.property("x-requirement-key");
                String identity = project != null && requirement != null ? project + '\0' + requirement : "unbound\0" + block.getHeaderTokens().getFirst();
                if (requirements.putIfAbsent(identity, block) != null) throw new IOException("Ambiguous historical requirement identity");
            }
            return new Snapshot(blocks, requirements);
        } catch (RuntimeException malformed) {
            throw new IOException("Cannot interpret the selected historical DSL", malformed);
        }
    }
    private record Snapshot(Map<String, BlockAst> blocks, Map<String, BlockAst> requirements) {}
    private static boolean matches(BlockAst requirement, Query query) {
        return query.projectKey() == null || query.projectKey().equals(requirement.property("x-project-key"))
                && query.requirementKey().equals(requirement.property("x-requirement-key"));
    }
    private static String selectedText(Snapshot snapshot, Query query) {
        StringBuilder text = new StringBuilder();
        snapshot.requirements().values().stream().filter(block -> matches(block, query)).forEach(block -> {
            text.append(serialized(block)).append(portable(snapshot, block));
            for (String id : dependencyIds(snapshot, block)) text.append(serialized(snapshot.blocks().get(id)));
        });
        return text.toString();
    }
    private static String portable(Snapshot snapshot, BlockAst requirement) {
        String project = requirement.property("x-project-key"), key = requirement.property("x-requirement-key");
        if (project == null || key == null) return "";
        StringBuilder result = new StringBuilder();
        snapshot.blocks().values().stream().filter(block -> Set.of("projectRequirement", "requirementVersion").contains(block.getKind())
                        && block.getHeaderTokens().size() >= 2 && project.equals(block.getHeaderTokens().get(0))
                        && key.equals(block.getHeaderTokens().get(1)))
                .map(RequirementHistoryService::serialized).sorted().forEach(result::append);
        return result.toString();
    }
    private static List<String> dependencyIds(Snapshot snapshot, BlockAst requirement) {
        var assessment = RequirementRealization.evaluate(snapshot.blocks(), requirement.getHeaderTokens().getFirst());
        return assessment.reviewed() == null ? List.of()
                : assessment.reviewed().dependencies().stream().map(RequirementRealization.Dependency::id).toList();
    }
    private static String status(Snapshot snapshot, BlockAst requirement) {
        return requirement == null ? null : RequirementRealization.evaluate(snapshot.blocks(), requirement.getHeaderTokens().getFirst()).state().name();
    }
    private static String property(BlockAst block, String key) { return block == null ? null : block.property(key); }
    private static String serialized(BlockAst block) {
        return block == null ? "" : SERIALIZER.serialize(new DocumentAst(null, List.of(block)));
    }
    private static String input(String value, String field, int max) {
        if (value == null) return null;
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid " + field);
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
