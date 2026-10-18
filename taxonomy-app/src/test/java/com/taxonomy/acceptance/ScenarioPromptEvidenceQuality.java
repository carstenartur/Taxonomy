package com.taxonomy.acceptance;

import com.taxonomy.analysis.relations.RelationSearchProtocol;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks actual provider prompts against the catalogue read from the running application. */
public final class ScenarioPromptEvidenceQuality {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern KEYS = Pattern.compile("EXACTLY these keys: ([^\\r\\n]+)");
    private static final String ROOT_PROMPT = "You assess the independent relevance of the offered C3 taxonomy root";
    private static final String FULL_CONTEXT = "\nSource hierarchy context (ancestors, not additional candidates):\n";
    private static final String COMPACT_CONTEXT = "\nAssessed source hierarchy (relation-source-context-v1; ancestor IDs and names are context, not additional candidates):\n";
    private static final int NORMAL_CALLS = 24, NORMAL_BATCH = 3, NORMAL_INPUT = 16_000;

    private record CatalogueNode(String code, String root, String name, String description,
                                 String parent, boolean container, String role) { }
    private record SourcePart(String node, String text, String quote, String condition) { }
    public record Cycle(String snapshotId, int maxCalls, int totalCalls, String snapshotSha256) {
        public Cycle {
            assertThat(snapshotId).isNotBlank();
            assertThat(maxCalls).as("Unchanged normal 24-call budget for %s", snapshotId).isEqualTo(NORMAL_CALLS);
            assertThat(totalCalls).as("Bounded actual call count for %s", snapshotId).isBetween(0, NORMAL_CALLS);
            assertThat(snapshotSha256).matches("[0-9a-f]{64}");
        }
    }
    private final Map<String, CatalogueNode> nodes = new TreeMap<>();
    private final Set<String> overlayParents;
    private final String original;
    private final Set<SourcePart> assessedParts = new HashSet<>();
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private int classificationCalls, classificationCandidates, sourceCandidates, targetCandidates, compactSources;
    private int maximumBatch, maximumInput;

    private ScenarioPromptEvidenceQuality(JsonNode catalogue, Set<String> overlayParents, String original) {
        assertThat(original).isNotBlank();
        this.original = original;
        this.overlayParents = Set.copyOf(overlayParents);
        index(catalogue, "");
        assertThat(nodes).as("Actual catalogue evidence").isNotEmpty();
        assertThat(nodes.keySet()).containsAll(overlayParents);
        for (CatalogueNode node : nodes.values()) path(node);
    }

    public static Map<String, Object> verify(JsonNode catalogue, Set<String> overlayParents, String original,
                                             List<ScenarioLlmPlayback.Call> calls, List<JsonNode> snapshots) {
        return verifyObservedCycles(catalogue, overlayParents, original, calls,
                snapshots.stream().map(ScenarioPromptEvidenceQuality::verifyCycle).toList());
    }

    /** Keeps only checked scalar cycle evidence in memory while a long scenario continues. */
    public static Map<String, Object> verifyObservedCycles(JsonNode catalogue, Set<String> overlayParents, String original,
                                                            List<ScenarioLlmPlayback.Call> calls, Collection<Cycle> cycles) {
        var quality = new ScenarioPromptEvidenceQuality(catalogue, overlayParents, original);
        assertThat(calls).as("Actual provider request journal").isNotEmpty();
        for (var call : calls) quality.request(call);
        assertThat(quality.classificationCalls).as("Observed real classification requests").isPositive();
        assertThat(quality.sourceCandidates).as("Observed full source assessments").isPositive();
        assertThat(quality.compactSources).as("Observed navigation or verification of assessed sources").isPositive();
        assertThat(cycles).as("Every observed initial and continued analysis snapshot").isNotEmpty();
        assertThat(cycles.stream().map(Cycle::snapshotId).distinct().count()).as("One budget check per immutable snapshot").isEqualTo(cycles.size());
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", "PASS");
        result.put("evidenceKind", "ACTUAL_PROVIDER_PROMPTS_AGAINST_RUNNING_CATALOGUE");
        result.put("catalogueSha256", ScenarioLlmPlayback.sha256(JSON.writeValueAsString(quality.nodes.values())));
        result.put("overlayParentCodes", new TreeSet<>(overlayParents));
        result.put("originalSha256", ScenarioLlmPlayback.sha256(original));
        result.put("classificationRequests", quality.classificationCalls);
        result.put("classificationCandidateAssessments", quality.classificationCandidates);
        result.put("fullSourceCandidateAssessments", quality.sourceCandidates);
        result.put("fullTargetCandidateAssessments", quality.targetCandidates);
        result.put("compactSourceContexts", quality.compactSources);
        result.put("normalRelationCallBudget", NORMAL_CALLS);
        result.put("normalRelationBatchSize", NORMAL_BATCH);
        result.put("normalRelationInputLimit", NORMAL_INPUT);
        result.put("maximumObservedRelationBatch", quality.maximumBatch);
        result.put("maximumObservedRelationInput", quality.maximumInput);
        result.put("requests", quality.requests);
        result.put("analysisCycles", List.copyOf(cycles));
        return result;
    }

    private void request(ScenarioLlmPlayback.Call call) {
        assertThat(call.promptSha256()).as("Actual prompt fingerprint").isEqualTo(ScenarioLlmPlayback.sha256(call.prompt()));
        assertThat(call.responseSha256()).as("Actual response fingerprint").isEqualTo(ScenarioLlmPlayback.sha256(call.response()));
        if (call.prompt().startsWith(ScenarioRelationPlayback.PREFIX)) relation(call);
        else classification(call);
    }

    private void relation(ScenarioLlmPlayback.Call call) {
        String prompt = call.prompt();
        int marker = prompt.indexOf("INPUT\n");
        assertThat(marker).as("Relation input boundary").isGreaterThanOrEqualTo(0);
        String instructions = prompt.substring(0, marker);
        JsonNode input = JSON.readTree(prompt.substring(marker + 6));
        assertThat(input.path("original").asString()).as("Unchanged original relation requirement").isEqualTo(original);
        boolean extraction = input.has("nodes");
        JsonNode candidates = input.path(extraction ? "nodes" : "candidates");
        assertThat(candidates.isArray()).as("Offered relation candidates").isTrue();
        assertThat(candidates.size()).as("Relation batch exceeds normal limit of three candidates").isBetween(1, NORMAL_BATCH);
        int size = RelationSearchProtocol.requestCharacters(prompt);
        assertThat(size).as("Relation request exceeds normal limit including schema and UTF-8 bytes").isLessThanOrEqualTo(NORMAL_INPUT);
        maximumBatch = Math.max(maximumBatch, candidates.size());
        maximumInput = Math.max(maximumInput, size);
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode candidate : candidates) {
            assertThat(ids.add(text(candidate, "id"))).as("Distinct offered relation identities").isTrue();
            checkNode(candidate, false);
        }
        String phase;
        String source = "";
        if (extraction) {
            assertThat(instructions).as("EXTRACT phase instruction").contains("Extract ONLY each source element's contribution");
            sourceCandidates += candidates.size();
            phase = "EXTRACT";
            JsonNode selections = JSON.readTree(JSON.readTree(call.response()).at("/choices/0/message/content").asString()).path("selections");
            assertThat(selections.isArray()).isTrue();
            assertThat(selections).hasSize(ids.size());
            Set<String> answered = new HashSet<>();
            for (JsonNode selection : selections) {
                String id = text(selection, "nodeId");
                assertThat(ids).contains(id);
                assertThat(answered.add(id)).as("Each source answer belongs to its actual offered node").isTrue();
                for (JsonNode part : selection.path("contributions")) {
                    String quote = text(part, "requirementQuote");
                    assertThat(original).contains(quote);
                    assessedParts.add(new SourcePart(id, text(part, "text"), quote, string(part, "condition")));
                }
            }
        } else {
            phase = text(input, "phase");
            assertThat(Set.of("NAVIGATE", "VERIFY")).contains(phase);
            assertThat(instructions).as("Relation phase instruction").contains(phase + ":");
            assertThat(instructions).as("Typed relation scope instruction")
                    .contains("Repository relation meaning (relation-semantics-v2): " + text(input, "type") + ".");
            assertThat(Set.of("OUTGOING", "INCOMING")).contains(text(input, "direction"));
            assertThat(instructions).contains("Source context policy: relation-source-context-v1.");
            if (phase.equals("VERIFY")) assertThat(candidates).as("Exactly one verified endpoint").hasSize(1);
            JsonNode contribution = input.path("contribution");
            checkNode(contribution.path("source"), true);
            source = text(contribution.path("source"), "id");
            SourcePart part = new SourcePart(source, text(contribution, "text"), text(contribution, "quote"), string(contribution, "condition"));
            assertThat(assessedParts).as("Compact source must retain its previous full source assessment and contribution").contains(part);
            targetCandidates += candidates.size();
            compactSources++;
        }
        requests.add(Map.of("promptSha256", call.promptSha256(), "phase", phase, "sourceId", source,
                "candidateIds", List.copyOf(ids), "budgetedInputSize", size));
    }

    private void checkNode(JsonNode offered, boolean assessedSource) {
        assertThat(offered.isObject()).as("Offered catalogue node").isTrue();
        assertThat(offered.propertyNames()).as("Provider node fields").containsExactlyInAnyOrder("id", "root", "name", "description", "container");
        CatalogueNode actual = node(text(offered, "id"));
        assertThat(text(offered, "root")).as("Actual catalogue root for %s", actual.code()).isEqualTo(actual.root());
        assertThat(text(offered, "name")).as("Actual catalogue name for %s", actual.code()).isEqualTo(actual.name());
        assertThat(offered.path("container").isBoolean()).isTrue();
        assertThat(offered.path("container").asBoolean()).as("Actual catalogue container for %s", actual.code()).isEqualTo(actual.container());
        assertThat(string(offered, "description"))
                .as(assessedSource ? "Complete assessed source description for %s" : "Complete full catalogue description for %s", actual.code())
                .isEqualTo(actual.description() + context(actual, assessedSource));
    }

    private void classification(ScenarioLlmPlayback.Call call) {
        String prompt = call.prompt();
        boolean root = prompt.startsWith(ROOT_PROMPT);
        boolean product = prompt.startsWith("You are evaluating concrete Information Products against a business requirement.");
        assertThat(root || product || prompt.startsWith("You are an expert in C3 taxonomy classification"))
                .as("Known complete classification prompt").isTrue();
        var matcher = KEYS.matcher(prompt);
        assertThat(matcher.find()).as("Actual classification scope").isTrue();
        List<String> ids = Arrays.stream(matcher.group(1).split(",")).map(String::strip).toList();
        assertThat(matcher.find()).as("One classification scope").isFalse();
        assertThat(new HashSet<>(ids)).as("Unique classification identities").hasSize(ids.size());
        List<CatalogueNode> candidates = ids.stream().map(this::node).toList();
        assertThat(candidates).isNotEmpty();
        CatalogueNode first = candidates.getFirst();
        for (CatalogueNode candidate : candidates) {
            assertThat(candidate.root()).as("One classification taxonomy scope").isEqualTo(first.root());
            if (root) assertThat(candidate.container()).as("Independent root candidate").isTrue();
            else {
                assertThat(candidate.container()).as("Concrete classification candidate").isFalse();
                assertThat(candidate.role().equals("PRODUCT")).as("Classification category/product phase").isEqualTo(product);
                assertThat(candidate.parent()).as("One classification sibling scope").isEqualTo(first.parent());
            }
        }
        if (root) assertThat(candidates).hasSize(1);
        String requirement = "Business Requirement: " + original + "\n\n";
        int requirementAt = prompt.indexOf(requirement);
        assertThat(requirementAt).as("Unchanged complete classification requirement").isGreaterThanOrEqualTo(0);
        String offered = prompt.substring(requirementAt + requirement.length());
        String heading = root ? "Taxonomy root: " + node(first.root()).name() + "\n"
                : product ? "Concrete Information Product candidates:\n" : node(first.root()).name() + " Categories:\n";
        assertThat(offered).as("Classification taxonomy scope heading").startsWith(heading);
        offered = offered.substring(heading.length());
        String boundary = root ? "\n\nRespond ONLY" : product ? "\nIf parent hierarchy context is shown"
                : "\nNote: If \"Parent hierarchy\" context is provided";
        int end = offered.indexOf(boundary);
        assertThat(end).as("Classification candidate/instruction boundary").isGreaterThanOrEqualTo(0);
        assertThat(offered.substring(0, end)).as("Complete classification candidate block and per-group source contexts")
                .isEqualTo(classificationBlock(candidates));
        classificationCalls++;
        classificationCandidates += candidates.size();
        requests.add(Map.of("promptSha256", call.promptSha256(), "phase", root ? "ROOT" : product ? "PRODUCT" : "CATEGORIES", "candidateIds", ids));
    }

    private String classificationBlock(List<CatalogueNode> candidates) {
        var groups = new LinkedHashMap<String, List<CatalogueNode>>();
        for (CatalogueNode candidate : candidates) groups.computeIfAbsent(context(candidate, false), ignored -> new ArrayList<>()).add(candidate);
        var result = new StringBuilder();
        for (var group : groups.entrySet()) {
            if (!group.getKey().isBlank()) result.append(group.getKey()).append("\nNodes to evaluate (only the following candidates):\n");
            for (CatalogueNode candidate : group.getValue()) {
                result.append(candidate.code()).append(": ").append(candidate.name());
                if (!candidate.description().isBlank()) result.append(" - ").append(candidate.description());
                result.append('\n');
            }
        }
        return result.toString();
    }

    private String context(CatalogueNode node, boolean compact) {
        List<CatalogueNode> path = path(node);
        var text = new StringBuilder();
        if (compact) text.append(COMPACT_CONTEXT);
        else if (path.size() > 1) text.append(FULL_CONTEXT);
        for (CatalogueNode ancestor : path.subList(0, path.size() - 1)) {
            text.append("  ").append(ancestor.code()).append(": ").append(ancestor.name());
            if (!compact && !ancestor.description().isBlank()) text.append(" - ").append(ancestor.description());
            text.append('\n');
        }
        if (overlayParents.contains(path.getFirst().code())) text.append("\nClassification/navigation only: the parent assignment above ")
                .append(path.getFirst().code()).append(" comes from an overlay, not established source hierarchy. ")
                .append("Do not inherit restrictions or infer irrelevance from that assignment.");
        return text.toString();
    }

    private List<CatalogueNode> path(CatalogueNode leaf) {
        var result = new LinkedList<CatalogueNode>();
        Set<String> visited = new HashSet<>();
        CatalogueNode current = leaf;
        while (true) {
            assertThat(visited.add(current.code())).as("Acyclic actual source hierarchy").isTrue();
            assertThat(current.root()).as("Source hierarchy remains in its taxonomy").isEqualTo(leaf.root());
            result.addFirst(current);
            if (current.parent().isBlank() || overlayParents.contains(current.code())) return List.copyOf(result);
            current = node(current.parent());
        }
    }

    private void index(JsonNode tree, String parent) {
        assertThat(tree.isArray()).as("Catalogue tree").isTrue();
        for (JsonNode entry : tree) {
            String actualParent = entry.path("parentCode").asString("");
            assertThat(actualParent).as("Actual catalogue parent").isEqualTo(parent);
            var node = new CatalogueNode(text(entry, "code"), text(entry, "taxonomyRoot"), text(entry, "nameEn"),
                    entry.path("descriptionEn").asString(""), actualParent, entry.path("taxonomyContainer").asBoolean(), text(entry, "analysisRole"));
            assertThat(nodes.put(node.code(), node)).as("One actual catalogue identity").isNull();
            index(entry.path("children"), node.code());
        }
    }

    private CatalogueNode node(String code) {
        assertThat(nodes).as("Known actual catalogue identity %s", code).containsKey(code);
        return nodes.get(code);
    }

    public static Cycle verifyCycle(JsonNode snapshot) {
        String id = text(snapshot.path("summary"), "id");
        JsonNode report = snapshot.at("/analysis/relationSearchReport");
        assertThat(report.path("maxCalls").isIntegralNumber()).as("Explicit normal 24-call budget for %s", id).isTrue();
        assertThat(report.path("totalCalls").isIntegralNumber()).as("Actual call count for %s", id).isTrue();
        return new Cycle(id, report.path("maxCalls").asInt(), report.path("totalCalls").asInt(), ScenarioLlmPlayback.sha256(snapshot.toString()));
    }

    private static String string(JsonNode object, String key) {
        assertThat(object.path(key).isString()).as("Explicit string field %s", key).isTrue();
        return object.path(key).asString();
    }
    private static String text(JsonNode object, String key) {
        String value = string(object, key); assertThat(value).as("Nonempty %s", key).isNotBlank(); return value;
    }
}
