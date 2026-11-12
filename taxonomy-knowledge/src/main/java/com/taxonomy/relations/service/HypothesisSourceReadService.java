package com.taxonomy.relations.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Current Git facts with scope-checked review identities. Retained working rows are a separate view. */
@Service
public class HypothesisSourceReadService {
    private final WorkspaceDslReadPort reads;
    private final HypothesisService hypotheses;

    public HypothesisSourceReadService(WorkspaceDslReadPort reads, HypothesisService hypotheses) {
        this.reads = reads;
        this.hypotheses = hypotheses;
    }

    public record Item(long id, String sourceNodeId, RelationType relationType, String targetNodeId,
                       HypothesisStatus status, Double confidence) {}
    public enum State { READY, MISSING, STALE, FAILED }
    public enum Problem { BRANCH_MISSING, SOURCE_MOVED, DSL_MISSING, BINDING_MISSING, BINDING_AMBIGUOUS, SOURCE_UNAVAILABLE }
    public record Result(State state, String commit, List<Item> rows, String entityTag, Problem problem) {}
    private record Identity(String source, RelationType type, String target) {
        static Identity from(RelationHypothesis row) {
            return new Identity(row.getSourceNodeId(), row.getRelationType(), row.getTargetNodeId());
        }
    }

    @Transactional(readOnly = true)
    public Result read(RepositoryContext context, String expectedCommit, HypothesisStatus filter) {
        String head = null;
        try {
            var repository = reads.openRead(context);
            head = repository.currentHead();
            if (head == null) return unavailable(State.MISSING, null, Problem.BRANCH_MISSING);
            if (expectedCommit != null && !expectedCommit.equals(head)) return unavailable(State.STALE, head, Problem.SOURCE_MOVED);
            var dsl = repository.dslAtCommit(head);
            if (dsl.isEmpty()) return unavailable(State.MISSING, head, Problem.DSL_MISSING);
            var candidates = hypotheses.findAll(context).stream().collect(Collectors.groupingBy(Identity::from));
            var items = items(dsl.get(), candidates, filter);
            // All properties except legacy review IDs are immutable Git content. Include the selected
            // scope, representation version, filter and ordered IDs so a restored binding changes the ETag.
            String tag = StableIdentityHash.sha256(java.util.stream.Stream.of("hypotheses-v1",
                            context.repositoryId(), context.scope().name(), java.util.Objects.toString(context.workspaceId(), ""),
                            context.branch(), head, String.valueOf(filter), items.stream().map(Item::id).toList().toString())
                    .map(value -> value.length() + ":" + value).collect(Collectors.joining()));
            return new Result(State.READY, head, items, tag, null);
        } catch (UnknownBinding missing) {
            return unavailable(missing.problem == Problem.BINDING_AMBIGUOUS ? State.STALE : State.MISSING, head, missing.problem);
        } catch (IOException | RuntimeException invalidSource) {
            return unavailable(State.FAILED, head, Problem.SOURCE_UNAVAILABLE);
        }
    }

    private static Result unavailable(State state, String commit, Problem problem) {
        return new Result(state, commit, List.of(), null, problem);
    }

    private static List<Item> items(String dsl, Map<Identity, List<RelationHypothesis>> candidates, HypothesisStatus filter) {
        requireCompleteBlocks(dsl);
        List<Item> result = new ArrayList<>();
        var represented = new HashSet<Long>();
        for (BlockAst block : ArchitectureSemanticPatch.index(dsl).values()) {
            if (!"relation".equals(block.getKind())) continue;
            String status = unique(block, "status");
            String marker = unique(block, "x-hypothesis-id");
            String provenance = unique(block, "provenance");
            boolean analyzed = provenance != null && provenance.startsWith("analysis-session:");
            boolean pending = "provisional".equalsIgnoreCase(status) || "proposed".equalsIgnoreCase(status);
            if (marker == null && !analyzed && !pending && !"hypothesis-review".equals(provenance)) continue;
            if (status == null || block.getHeaderTokens().size() != 3) throw new IllegalArgumentException();
            var header = block.getHeaderTokens();
            var key = new Identity(header.get(0), RelationType.valueOf(header.get(1).toUpperCase(Locale.ROOT)), header.get(2));
            var decision = HypothesisStatus.valueOf(status.toUpperCase(Locale.ROOT));
            var matches = candidates.getOrDefault(key, List.of()).stream()
                    .filter(row -> binds(row, marker, provenance, decision)).toList();
            if (matches.isEmpty()) throw new UnknownBinding(Problem.BINDING_MISSING);
            if (matches.size() > 1) throw new UnknownBinding(Problem.BINDING_AMBIGUOUS);
            long id = matches.getFirst().getId();
            if (!represented.add(id)) throw new IllegalArgumentException();
            String value = unique(block, "confidence");
            Double confidence = value == null ? null : Double.valueOf(value);
            if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
                throw new IllegalArgumentException();
            }
            if (filter == null || filter == decision) {
                result.add(new Item(id, key.source(), key.type(), key.target(), decision, confidence));
            }
        }
        return List.copyOf(result);
    }

    private static boolean binds(RelationHypothesis row, String marker, String provenance, HypothesisStatus decision) {
        if (marker != null && !marker.equals(row.getId().toString())) return false;
        if (provenance == null || !provenance.startsWith("analysis-session:")) return marker != null;
        String origin = provenance.substring("analysis-session:".length());
        if (origin.equals(row.getAnalysisSessionId())) return true;
        // The historical direct review publisher records its action and review ID as the session.
        return marker != null && ((decision == HypothesisStatus.ACCEPTED && origin.equals("accepted-" + marker))
                || (decision == HypothesisStatus.REJECTED && origin.equals("rejected-" + marker)));
    }

    private static String unique(BlockAst block, String field) {
        var values = block.propertyValues(field);
        if (values.size() > 1) throw new IllegalArgumentException();
        return values.isEmpty() ? null : values.getFirst();
    }

    /** The import parser tolerates incomplete input. An authoritative read cannot turn it into an empty list. */
    private static void requireCompleteBlocks(String dsl) {
        int depth = 0;
        for (String raw : dsl.lines().toList()) {
            String line = TaxDslParser.withoutComment(raw).strip();
            if (line.isEmpty()) continue;
            int delta = TaxDslParser.blockDelimiter(line);
            if (depth == 0 && delta != 1) throw new IllegalArgumentException();
            depth += delta;
            if (depth > 1) throw new IllegalArgumentException();
        }
        if (depth != 0) throw new IllegalArgumentException();
    }

    private static final class UnknownBinding extends RuntimeException {
        private final Problem problem;
        UnknownBinding(Problem problem) { this.problem = problem; }
    }
}
