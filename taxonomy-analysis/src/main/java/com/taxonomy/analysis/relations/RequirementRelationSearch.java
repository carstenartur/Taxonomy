package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.analysis.assessment.ChildAssessmentContract;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import static com.taxonomy.dto.RelationSearchModel.*;

/** One bounded analysis session; no writes, catalogue entities or cross-user caches. */
public final class RequirementRelationSearch {
    public interface InputCatalogue extends Catalogue {
        Node find(String id);
        List<Node> roots();
    }
    public record Options(Limits limits, int maxSources) {
        public Options {
            Objects.requireNonNull(limits);
            if (maxSources < 1 || maxSources > 256) throw new IllegalArgumentException("maxSources must be 1..256");
        }
    }
    private final InputCatalogue catalogue;
    private final RelationCompatibilityMatrix rules;
    private final RelationSearchProtocol protocol;
    private final Runnable checkpoint;

    public RequirementRelationSearch(InputCatalogue catalogue, RelationCompatibilityMatrix rules,
                                     Function<String, String> complete, Runnable checkpoint) {
        this.catalogue = Objects.requireNonNull(catalogue);
        this.rules = Objects.requireNonNull(rules);
        this.protocol = new RelationSearchProtocol(complete);
        this.checkpoint = Objects.requireNonNull(checkpoint);
    }

    public RelationSearchReport search(String original, Map<String, Integer> scores, Options options) {
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Missing original requirement");
        Objects.requireNonNull(scores); Objects.requireNonNull(options);
        long start = System.nanoTime();
        int extractionCalls = 0;
        List<SourceAssessment> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Result result = new Result(List.of(), List.of(), List.of(), 0, 0, 0);
        String stop = "";
        try {
            checkpoint.run();
            // Catalogue validity is a precondition for extraction, not a late
            // engine concern after the caller has already paid for model calls.
            List<Node> offeredRoots = List.copyOf(catalogue.roots());
            ChildAssessmentContract.validateCandidates(offeredRoots.stream().map(Node::id).toList());
            List<Node> roots = offeredRoots.stream().sorted(Comparator.comparing(Node::id)).toList();
            List<Node> nodes = sourceNodes(scores, options.maxSources(), warnings);
            int size = options.limits().batchSize();
            for (int i = 0; i < nodes.size(); i += size) {
                checkpoint.run();
                if (extractionCalls >= options.limits().maxCalls()) {
                    warnings.add("EXTRACTION_CALL_BUDGET: " + (nodes.size() - i) + " sources remain unassessed.");
                    break;
                }
                var batch = nodes.subList(i, Math.min(i + size, nodes.size()));
                extractionCalls++;
                try {
                    var assessments = protocol.contributions(original, batch);
                    sources.addAll(assessments);
                    for (var assessment : assessments) if (!assessment.question().isBlank()) {
                        warnings.add("SOURCE_UNRESOLVED " + assessment.node().id() + ": " + assessment.question());
                    }
                } catch (RelationSearchEngine.InvalidResponseException invalid) {
                    warnings.add("INVALID_SOURCE_RESPONSE " + batch.stream().map(Node::id).toList() + ": " + invalid.getMessage());
                }
            }
            List<Intent> intents = new ArrayList<>();
            for (SourceAssessment assessment : sources) for (Contribution contribution : assessment.contributions()) {
                int before = intents.size();
                for (RelationType type : RelationType.values()) {
                    Set<String> allowed = rules.allowedTargetRoots(contribution.source().root(), type);
                    List<Node> outgoing = roots.stream().filter(n -> allowed.contains(n.root())).toList();
                    if (!outgoing.isEmpty()) intents.add(new Intent(contribution, type.name(), Direction.OUTGOING, outgoing));
                    List<Node> incoming = roots.stream().filter(n -> rules.allowedTargetRoots(n.root(), type)
                            .contains(contribution.source().root())).toList();
                    if (!incoming.isEmpty()) intents.add(new Intent(contribution, type.name(), Direction.INCOMING, incoming));
                }
                if (intents.size() == before) warnings.add("NO_STRUCTURAL_ROUTE " + contribution.source().id()
                        + ": the current root profile cannot route this contribution; not evidence of absence.");
            }
            Limits l = options.limits();
            var engine = new RelationSearchEngine(node -> {
                List<Node> children = catalogue.children(node);
                if (children.stream().anyMatch(child -> !child.root().equals(node.root()))) {
                    throw new IllegalStateException("CATALOGUE_ROOT_MISMATCH: child belongs to another taxonomy");
                }
                return children;
            }, protocol::evaluate, checkpoint);
            result = engine.search(original, intents, new Limits(l.maxCalls() - extractionCalls,
                    l.maxDepth(), l.batchSize(), l.maxWorkItems()));
        } catch (RelationSearchEngine.InterruptedSearchException interrupted) {
            result = interrupted.partialResult();
            stop = failureReason(interrupted.getCause());
        } catch (RuntimeException failure) {
            stop = failureReason(failure);
        }
        return new RelationSearchReport(1, sha256(original), "relation-downwalk-v1/root-compatibility-profile",
                sources, result, extractionCalls + result.calls(), options.limits().maxCalls(),
                (System.nanoTime() - start) / 1_000_000, warnings, stop);
    }

    private List<Node> sourceNodes(Map<String, Integer> scores, int maxSources, List<String> warnings) {
        List<Map.Entry<String,Integer>> positive = scores.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .toList();
        List<Node> nodes = new ArrayList<>();
        Set<String> concreteRoots = new HashSet<>();
        Set<String> containerRoots = new TreeSet<>();
        int missing = 0, skipped = 0;
        for (var entry : positive) {
            checkpoint.run();
            if (nodes.size() >= maxSources) { skipped++; continue; }
            Node node = catalogue.find(entry.getKey());
            if (node == null) { missing++; continue; }
            if (node.container()) { containerRoots.add(node.root()); continue; }
            concreteRoots.add(node.root()); nodes.add(node);
        }
        if (missing > 0) warnings.add("MISSING_SOURCE: " + missing + " scored IDs are absent from the catalogue.");
        if (skipped > 0) warnings.add("SOURCE_LIMIT: " + skipped + " scored IDs remain unassessed.");
        containerRoots.removeAll(concreteRoots);
        for (String root : containerRoots) warnings.add("ROOT_SOURCE_UNRESOLVED " + root
                + ": identify a concrete required contribution below this catalogue container.");
        return List.copyOf(nodes);
    }

    private static String failureReason(Throwable failure) {
        // Do not copy provider exception messages which can contain credentials or request bodies.
        if (failure instanceof com.taxonomy.analysis.service.AnalysisStoppedException stopped) {
            return stopped.reason().name() + ": completed evidence retained; no further model evaluations.";
        }
        return "RELATION_SEARCH_STOPPED: " + failure.getClass().getSimpleName();
    }
    private static String sha256(String original) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
