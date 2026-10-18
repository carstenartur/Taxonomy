package com.taxonomy.acceptance;

import com.taxonomy.archimate.exchange.ArchiMateRelationProvenance;
import com.taxonomy.diagram.DiagramRelationMetadata;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.Artifact;
import com.taxonomy.extension.api.integration.IntegrationContracts.ArtifactKind;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import com.taxonomy.extension.api.integration.IntegrationContracts.Relation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent declared-function and evidence gate, not a deployment or EA-desktop certification. */
public final class ScenarioArchitectureQuality {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE = "/scenarios/flood-quality-reference.json";
    private ScenarioArchitectureQuality() { }

    /** Each original pass must satisfy the same search and recovery gate before export acceptance. */
    public static void verifyPassCompletion(JsonNode snapshot) {
        JsonNode summary = object(snapshot, "summary"), analysis = object(snapshot, "analysis");
        same(text(summary, "status"), value(analysis, "status"), "snapshot analysis completion status");
        verifySearch(object(analysis, "relationSearchReport"), analysis, reference());
    }

    public static Map<String, Object> verify(JsonNode snapshot, JsonNode projection,
                                              ExchangeDocument deliveredSparx, byte[] xmi) {
        Reference reference = reference();
        check(snapshot != null && projection != null && deliveredSparx != null && xmi != null, "Architecture quality inputs must be present");
        check(deliveredSparx.profile().equals("sparx-xmi-2.1") && deliveredSparx.profileVersion().equals("3"),
                "Functional architecture delivery requires the explicit Sparx profile 3");
        check(deliveredSparx.completeScope(), "The delivered Sparx model is not a complete scope");
        JsonNode summary = object(snapshot, "summary"), analysis = object(snapshot, "analysis");
        JsonNode search = object(analysis, "relationSearchReport"), graph = object(projection, "diagram");
        verifyProvenance(reference, summary, analysis, search, projection, graph, deliveredSparx.metadata());
        SearchEvidence searchEvidence = verifySearch(search, analysis, reference);
        Map<String, SourceAssessment> sources = searchEvidence.sources();
        verifyXmi(deliveredSparx, xmi);

        Map<String, JsonNode> graphNodes = index(array(graph, "nodes"), "id", true);
        Map<String, JsonNode> frozenElements = index(array(snapshot, "elementMappings"), "nodeCode", false);
        Map<String, Artifact> elements = new LinkedHashMap<>(); Map<String, String> codes = new LinkedHashMap<>();
        for (Artifact artifact : deliveredSparx.artifacts()) {
            if (artifact.kind() != ArtifactKind.ELEMENT) continue;
            String code = artifact.attributes().get("tag:taxonomy.sourceId");
            check(code != null && graphNodes.containsKey(code), "Sparx element sourceId does not identify a projected element: " + artifact.id());
            check(elements.put(code, artifact) == null, "Duplicate Sparx element sourceId: " + code);
            codes.put(artifact.id(), code);
        }
        check(elements.keySet().equals(graphNodes.keySet()), "The delivered element sourceId set differs from the frozen graph");
        var witnesses = new ArrayList<Witness>();
        for (var entry : elements.entrySet()) {
            String code = entry.getKey(); Artifact artifact = entry.getValue();
            JsonNode projected = object(object(projection, "elements"), code), frozen = frozenElements.get(code);
            check(frozen != null, "Missing frozen element mapping for " + code);
            same(text(summary, "id"), value(frozen, "snapshotId"), code + " snapshotId");
            String presence = text(projected, "presenceReason");
            same(presence, value(frozen, "presenceReason"), code + " frozen presenceReason");
            same(presence, artifact.attributes().get("tag:taxonomy.presenceReason"), code + " Sparx presenceReason");
            check(artifact.text().contains(presence), code + " readable Sparx Notes omit the bound presenceReason");
            retainedReview(projected, frozen, artifact.attributes(), code);
            SourceAssessment assessed = sources.get(code);
            if (assessed != null) for (Contribution contribution : assessed.contributions()) {
                // A frozen source interpretation counts only when that exact interpretation was delivered locally.
                if (presence.contains(contribution.text())) witnesses.add(new Witness("ELEMENT", code, code,
                        contribution.text(), contribution.quote(), presence.contains(contribution.condition()) ? contribution.condition() : ""));
            }
        }

        Map<Triple, JsonNode> projectedEdges = edges(array(graph, "edges"), "sourceId", "relationType", "targetId");
        Map<Triple, JsonNode> includedEdges = edges(array(object(analysis, "architectureView"), "includedRelationships"), "sourceCode", "relationType", "targetCode");
        Map<Triple, JsonNode> frozenRelations = edges(array(snapshot, "relationMappings"), "sourceCode", "relationType", "targetCode");
        for (var entry : reference.required().entrySet()) {
            check(projectedEdges.containsKey(entry.getKey()) && includedEdges.containsKey(entry.getKey()),
                    text(entry.getValue(), "id") + ": required directed relation is missing or reversed: " + entry.getKey());
        }
        check(projectedEdges.keySet().equals(includedEdges.keySet()), "Projected relationships differ from the frozen architecture selection");
        var deliveredRelations = new LinkedHashMap<Triple, Relation>();
        for (Relation relation : deliveredSparx.relations()) {
            String source = codes.get(relation.source()), target = codes.get(relation.target());
            check(source != null && target != null, "Sparx relationship has an unbound endpoint: " + relation.id());
            if ("Destination -> Source".equals(relation.attributes().get("tag:taxonomy.relationDirection"))) {
                String temporary = source; source = target; target = temporary;
            }
            Triple triple = new Triple(source, relation.attributes().get("tag:taxonomy.relationType"), target);
            check(deliveredRelations.put(triple, relation) == null, "Duplicate directed Sparx relation: " + triple);
        }
        check(deliveredRelations.keySet().equals(projectedEdges.keySet()), "Directed Sparx relations differ from the frozen graph");
        List<Edge> searchEdges = new ArrayList<>();
        for (JsonNode node : array(object(search, "result"), "edges")) searchEdges.add(convert(node, Edge.class, "retained search evidence"));
        var relationReports = new ArrayList<Map<String, Object>>();
        for (var entry : projectedEdges.entrySet()) {
            Triple triple = entry.getKey(); JsonNode edge = entry.getValue(); Relation delivered = deliveredRelations.get(triple);
            JsonNode expected = reference.required().get(triple);
            boolean required = expected != null;
            if (expected == null) expected = reference.allowed().get(triple);
            check(expected != null, "Unexpected directed relation outside the independent reference: " + triple);
            String id = text(expected, "id");
            same(text(edge, "id"), delivered.attributes().get("tag:taxonomy.sourceId"), id + " relationship sourceId");
            DiagramRelationMetadata metadata = convert(object(edge, "metadata"), DiagramRelationMetadata.class, id + " projected provenance");
            List<Edge> frozenEvidence = new ArrayList<>();
            for (JsonNode item : array(includedEdges.get(triple), "requirementEvidence")) frozenEvidence.add(convert(item, Edge.class, id + " frozen evidence"));
            check(metadata.requirementEvidence().equals(frozenEvidence), id + ": projected requirementEvidence differs from the frozen relationship");
            Map<String, String> expectedTags = new LinkedHashMap<>();
            ArchiMateRelationProvenance.write(metadata).forEach((key, property) -> expectedTags.put("tag:" + key, property.value()));
            Map<String, String> actualTags = new LinkedHashMap<>();
            delivered.attributes().forEach((key, value) -> { if (key.startsWith("tag:taxonomy.provenance.")) actualTags.put(key, value); });
            check(expectedTags.equals(actualTags), id + ": Sparx provenance tags differ from the complete frozen relationship evidence");
            JsonNode projectedReview = object(object(projection, "relations"), triple.source() + "|" + triple.target() + "|" + triple.type());
            JsonNode frozenReview = frozenRelations.get(triple);
            check(frozenReview != null, id + ": missing frozen relationship review mapping");
            same(text(summary, "id"), value(frozenReview, "snapshotId"), id + " snapshotId");
            retainedReview(projectedReview, frozenReview, delivered.attributes(), id);
            if ("PROPOSED".equals(value(projectedReview, "reviewStatus"))) {
                same("proposed", delivered.attributes().get("tag:taxonomy.status"), id + " proposed status");
                same("proposed", delivered.extensions().get("taxonomy:status"), id + " proposed extension status");
            }
            List<Witness> local = verifyRelationship(id, triple, expected, metadata.requirementEvidence(), searchEdges,
                    sources, reference, required);
            witnesses.addAll(local);
            relationReports.add(Map.of("id", id, "sourceId", triple.source(), "type", triple.type(), "targetId", triple.target(),
                    "required", required, "expectedNecessity", Objects.toString(value(expected, "necessity"), "UNSPECIFIED"),
                    "observedNecessities", metadata.requirementEvidence().stream().map(e -> e.evidence().necessity().name()).distinct().toList(),
                    "evidenceCount", metadata.requirementEvidence().size(), "verdict", "PASS"));
        }
        List<Map<String, Object>> openPoints = verifyOpenIdentityWitnesses(searchEvidence, witnesses, reference, projectedEdges.keySet(), searchEdges);
        var constraints = new ArrayList<Map<String, Object>>();
        for (JsonNode constraint : array(reference.tree(), "coverageConstraints")) constraints.add(verifyConstraint(constraint, witnesses, reference.clauses()));
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1); result.put("verdict", searchEvidence.complete() ? "PASS" : "PASS_WITH_OPEN_POINTS");
        result.put("evaluationScope", "DECLARED_FUNCTIONAL_ARCHITECTURE");
        result.put("evidenceKind", "INDEPENDENT_REFERENCE_AND_AUTHORED_PLAYBACK_EXPORT");
        result.put("referenceResource", RESOURCE); result.put("referenceVersion", text(reference.tree(), "referenceVersion"));
        result.put("referenceSha256", reference.sha256()); result.put("requirementSha256", sha256(reference.original().getBytes(StandardCharsets.UTF_8)));
        result.put("snapshotId", text(summary, "id")); result.put("canonicalGraphSha256", graphSha256(graph)); result.put("xmiSha256", sha256(xmi));
        result.put("functionalCoverageComplete", true); result.put("sourceAndSearchEvidenceComplete", searchEvidence.complete());
        result.put("snapshotStatus", text(summary, "status")); result.put("openPoints", openPoints); result.put("assessedSourceCount", sources.size());
        result.put("completedSearchCount", object(search, "progress").path("completedSearches").asLong());
        result.put("requiredRelationCount", reference.required().size()); result.put("deliveredRelationCount", deliveredRelations.size());
        result.put("satisfiedConstraintCount", constraints.size()); result.put("constraints", constraints); result.put("relations", relationReports);
        result.put("limits", List.of("Checks declared functions and frozen evidence in this bounded English reference.",
                "Does not prove a physical implementation, timing performance, safety certification or EA desktop import."));
        return result;
    }

    private record Triple(String source, String type, String target) { }
    private record SearchKey(String source, String type, Direction direction, String target) { }
    private record OpenIdentity(JsonNode expected, List<Unfinished> observations) { }
    private record SearchEvidence(Map<String, SourceAssessment> sources, List<OpenIdentity> openPoints) {
        boolean complete() { return openPoints.isEmpty(); }
    }
    private record Reference(JsonNode tree, String sha256, String original, Map<String, String> clauses,
                             Map<Triple, JsonNode> required, Map<Triple, JsonNode> allowed, Map<Triple, JsonNode> openIdentities,
                             Map<String, Set<String>> endpointClauses) { }

    private static Reference reference() {
        try (var input = ScenarioArchitectureQuality.class.getResourceAsStream(RESOURCE)) {
            check(input != null, "Independent architecture quality reference is missing");
            byte[] bytes = input.readNBytes(256 * 1024 + 1); check(bytes.length <= 256 * 1024, "Independent reference is oversized");
            JsonNode tree = JSON.readTree(bytes);
            check(tree.path("schemaVersion").asInt() == 1 && "flood-information-v1".equals(value(tree, "caseId")), "Unsupported independent reference");
            JsonNode requirement = object(tree, "requirement"); String original = text(requirement, "text");
            var clauses = new LinkedHashMap<String, String>();
            object(requirement, "clauses").properties().forEach(e -> clauses.put(e.getKey(), e.getValue().asText()));
            var endpointClauses = new LinkedHashMap<String, Set<String>>(); var ids = new LinkedHashSet<String>();
            for (String section : List.of("coverageConstraints", "requiredRelations", "allowedRelations", "openIdentityQuestions")) for (JsonNode entry : array(tree, section)) {
                String id = text(entry, "id"); check(ids.add(id), "Duplicate independent reference binding " + id);
                Set<String> quoteIds = strings(array(entry, "quoteIds")); List<JsonNode> quotes = nodes(array(entry, "quotes"));
                check(quoteIds.size() == quotes.size() && !quotes.isEmpty(), id + ": missing independent source bindings");
                int index = 0;
                for (String quoteId : quoteIds) {
                    String quote = quotes.get(index++).asText();
                    check(quote.equals(clauses.get(quoteId)) && original.contains(quote), id + ": reference quote is not an exact original clause");
                }
                if (section.equals("coverageConstraints")) for (String endpoint : strings(object(entry, "binding").path("elementIds")))
                    endpointClauses.computeIfAbsent(endpoint, ignored -> new LinkedHashSet<>()).addAll(quoteIds);
                if (section.endsWith("Relations")) {
                    for (String role : List.of("source", "target")) {
                        Set<String> roleQuotes = strings(array(object(entry, "endpointQuoteIds"), role));
                        check(!roleQuotes.isEmpty() && quoteIds.containsAll(roleQuotes), id + ": invalid directed " + role + " quote role");
                        for (JsonNode term : array(object(entry, "endpointTerms"), role)) Pattern.compile(term.asText());
                    }
                    if (section.equals("allowedRelations")) {
                        check(!array(entry, "conditionTerms").isEmpty(), id + ": missing permitted selection/function boundary");
                        for (String field : List.of("conditionTerms", "excludedClaims"))
                            for (JsonNode term : array(entry, field)) Pattern.compile(term.asText());
                    }
                }
                if (section.endsWith("Relations") && entry.has("necessity")) {
                    check(Set.of("REQUIRED", "OPTIONAL", "ALTERNATIVE").contains(text(entry, "necessity")), id + ": unknown reference necessity");
                    check(!section.equals("requiredRelations") || value(entry, "necessity").equals("REQUIRED"), id + ": a core relation has a conflicting necessity");
                }
            }
            Set<String> constraintIds = new LinkedHashSet<>();
            for (JsonNode entry : array(tree, "coverageConstraints")) constraintIds.add(text(entry, "id"));
            check(constraintIds.equals(OBLIGATIONS.keySet()), "Independent coverage reference and executable obligation set differ");
            // Authentication/access control can also bind the F4 account actions, while A1 remains their design scope.
            for (String id : List.of("CR-1006", "CR-1007", "BP-1140")) endpointClauses.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add("F4");
            // R04 acquires F1 published warning inputs; this does not identify the F5 observation batch as a particular product.
            endpointClauses.computeIfAbsent("BP-1017", ignored -> new LinkedHashSet<>()).add("F1");
            Map<Triple, JsonNode> required = edges(array(tree, "requiredRelations"), "sourceId", "type", "targetId");
            Map<Triple, JsonNode> allowed = edges(array(tree, "allowedRelations"), "sourceId", "type", "targetId");
            Map<Triple, JsonNode> openIdentities = edges(array(tree, "openIdentityQuestions"), "sourceId", "type", "targetId");
            Map<Triple, JsonNode> rejectedRequired = edges(array(tree, "rejectedRequiredClaims"), "sourceId", "type", "targetId");
            openIdentities.forEach((triple, entry) -> {
                check(!required.containsKey(triple) && !allowed.containsKey(triple), text(entry, "id") + ": an open identity cannot also be an accepted relationship");
                JsonNode rejected = rejectedRequired.get(triple);
                check(rejected != null && value(rejected, "id").equals(text(entry, "id"))
                                && value(rejected, "disposition").equals("UNRESOLVED_INPUT_IDENTITY_NOT_REQUIRED"),
                        text(entry, "id") + ": open identity lacks its independent noncore disposition");
                text(entry, "question"); text(entry, "rationale");
            });
            for (String section : List.of("requiredRelations", "allowedRelations")) for (JsonNode entry : array(tree, section))
                    for (String field : List.of("sourceId", "targetId")) {
                String endpoint = text(entry, field);
                endpointClauses.computeIfAbsent(endpoint, ignored -> new LinkedHashSet<>()).addAll(strings(entry.path("quoteIds")));
            }
            return new Reference(tree, sha256(bytes), original, clauses, required, allowed, openIdentities, endpointClauses);
        } catch (IOException failure) { throw new AssertionError("Cannot read independent architecture quality reference", failure); }
    }

    private static void verifyProvenance(Reference reference, JsonNode summary, JsonNode analysis, JsonNode search,
                                         JsonNode projection, JsonNode graph, Map<String, String> metadata) {
        same(text(object(reference.tree(), "requirement"), "id"), value(summary, "requirementKey"), "requirement identity");
        same(value(summary, "requirementKey"), value(projection, "requirementKey"), "projection requirement identity");
        same(reference.original(), value(projection, "requirementText"), "projection requirementText");
        same(reference.original(), metadata.get("taxonomy.requirementText"), "Sparx taxonomy.requirementText");
        String originalHash = sha256(reference.original().getBytes(StandardCharsets.UTF_8));
        same(originalHash, value(search, "originalSha256"), "source original SHA-256");
        same(originalHash, metadata.get("taxonomy.requirementSha256"), "taxonomy.requirementSha256 SHA-256");
        String status = text(summary, "status");
        check(Set.of("SUCCESS", "PARTIAL").contains(status), "Unusable snapshot status");
        same(status, value(analysis, "status"), "snapshot analysis status");
        same(status, value(projection, "snapshotStatus"), "projection snapshot status");
        same(status, metadata.get("taxonomy.snapshotStatus"), "taxonomy.snapshotStatus");
        for (String field : List.of("requirementId", "projectId", "workspaceId", "branchName", "commitSha")) {
            same(text(summary, field), value(projection, field), "projection " + field);
            String tag = switch (field) { case "branchName" -> "branch"; case "commitSha" -> "authoritativeCommit"; default -> field; };
            same(text(summary, field), metadata.get("taxonomy." + tag), "taxonomy." + tag);
        }
        same(text(summary, "id"), value(projection, "snapshotId"), "projection snapshotId");
        same(text(summary, "id"), metadata.get("taxonomy.snapshotId"), "taxonomy.snapshotId");
        JsonNode export = object(projection, "exportProvenance");
        same(text(summary, "requirementVersionId"), value(export, "requirementVersionId"), "projection requirementVersionId");
        same(text(summary, "requirementVersionId"), metadata.get("taxonomy.requirementVersionId"), "taxonomy.requirementVersionId");
        same(text(summary, "taxonomyFingerprint"), value(export, "taxonomyFingerprint"), "projection taxonomyFingerprint");
        same(text(summary, "taxonomyFingerprint"), metadata.get("taxonomy.taxonomyFingerprint"), "taxonomy.taxonomyFingerprint");
        same(graphSha256(graph), metadata.get("taxonomy.canonicalGraphSha256"), "canonical graph SHA-256");
    }

    private static SearchEvidence verifySearch(JsonNode search, JsonNode analysis, Reference reference) {
        JsonNode progress = object(search, "progress"), retained = array(search, "sources");
        long total = progress.path("totalSources").asLong(-1), assessed = progress.path("assessedSources").asLong(-1);
        check(total > 0 && total == assessed && assessed == retained.size(), "Incomplete source assessment: total, assessed and retained source counts differ");
        check(array(search, "warnings").isEmpty() && blank(value(search, "stopReason")), "Search warnings, budget limits or a stop reason remain");
        var sources = new LinkedHashMap<String, SourceAssessment>();
        for (JsonNode item : retained) {
            SourceAssessment source = convert(item, SourceAssessment.class, "source assessment");
            check(sources.put(source.node().id(), source) == null, "Duplicate assessed source: " + source.node().id());
            check(blank(source.question()), "Unresolved source question: " + source.node().id());
            for (Contribution contribution : source.contributions()) {
                same(source.node().id(), contribution.source().id(), "source contribution endpoint identity");
                verifyQuote(contribution.quote(), reference, "source " + source.node().id());
            }
        }
        var observations = new LinkedHashMap<Triple, List<Unfinished>>();
        var observedKeys = new LinkedHashSet<SearchKey>(); var openTasks = new LinkedHashSet<String>();
        for (JsonNode item : array(object(search, "result"), "unfinished")) {
            Unfinished unfinished = convert(item, Unfinished.class, "unfinished search evidence");
            check("UNRESOLVED".equals(unfinished.reason()) && unfinished.targets().size() == 1 && unfinished.direction() != null,
                    "Only a concrete referenced identity question is admissible; budget or other unfinished work remains");
            String target = unfinished.targets().getFirst();
            Triple triple = unfinished.direction() == Direction.OUTGOING
                    ? new Triple(unfinished.sourceId(), unfinished.type(), target) : new Triple(target, unfinished.type(), unfinished.sourceId());
            JsonNode expected = reference.openIdentities().get(triple);
            check(expected != null, "Unreferenced open relation identity: " + triple);
            same(text(expected, "question"), unfinished.question(), text(expected, "id") + " exact identity question");
            check(sources.containsKey(unfinished.sourceId()) && sources.containsKey(target)
                            && !sources.get(unfinished.sourceId()).node().container() && !sources.get(target).node().container(),
                    text(expected, "id") + ": open identity endpoints are not assessed concrete elements");
            check(observedKeys.add(new SearchKey(unfinished.sourceId(), unfinished.type(), unfinished.direction(), target)), "Duplicate open identity search evidence");
            openTasks.add(unfinished.sourceId() + "|" + sources.get(target).node().root());
            observations.computeIfAbsent(triple, ignored -> new ArrayList<>()).add(unfinished);
        }
        if (!observedKeys.isEmpty()) {
            var traceKeys = new LinkedHashSet<SearchKey>();
            for (JsonNode item : array(object(search, "result"), "trace")) {
                Trace trace = convert(item, Trace.class, "identity search trace");
                if (trace.outcome() != Outcome.UNRESOLVED) continue;
                check(trace.phase() != null && !blank(trace.rationale()), "An open identity trace must retain its assessed phase and rationale");
                traceKeys.add(new SearchKey(trace.sourceId(), trace.type(), trace.direction(), trace.targetId()));
            }
            check(traceKeys.equals(observedKeys), "Open identity traces differ from the concrete referenced questions");
        } else if (object(search, "result").has("trace")) {
            for (JsonNode trace : array(object(search, "result"), "trace")) check(!value(trace, "outcome").equals("UNRESOLVED"), "An unresolved trace was omitted from unfinished work");
        }
        long searches = progress.path("totalSearches").asLong(-1), completed = progress.path("completedSearches").asLong(-1);
        check(searches > 0 && completed >= 0 && searches == completed + openTasks.size()
                        && progress.path("pendingSearches").asLong(-1) == 0 && progress.path("unresolvedSearches").asLong(-1) == openTasks.size()
                        && search.path("searchExhausted").isBoolean() && search.path("searchExhausted").asBoolean() == observedKeys.isEmpty(),
                "Incomplete search coverage: planned, completed, pending, unresolved or exhausted states disagree");
        JsonNode tasks = array(search, "tasks"); check(tasks.size() == searches, "Retained search task count differs from the complete plan");
        var keys = new HashSet<String>(); var retainedOpenTasks = new LinkedHashSet<String>(); long completedTasks = 0;
        for (JsonNode task : tasks) {
            String source = text(task, "sourceId"), root = text(task, "targetRoot"), key = source + "|" + root;
            check(sources.containsKey(source) && keys.add(key), "Search task has an unassessed source or duplicate scope");
            if (value(task, "state").equals("COMPLETED")) completedTasks++;
            else {
                same("UNRESOLVED", value(task, "state"), "search task state");
                check(openTasks.contains(key), "Unreferenced unresolved search task " + key); retainedOpenTasks.add(key);
            }
        }
        Set<String> expectedKeys = expectedTaskKeys(retained);
        check(keys.equals(expectedKeys), "Retained search task keys differ from the exact compatible source/root plan; missing="
                + difference(expectedKeys, keys) + "; unexpected=" + difference(keys, expectedKeys));
        check(completedTasks == completed && retainedOpenTasks.equals(openTasks), "Search task counters differ from their retained states");
        var openPoints = observations.entrySet().stream().map(e -> new OpenIdentity(reference.openIdentities().get(e.getKey()), List.copyOf(e.getValue()))).toList();
        same(openPoints.isEmpty() ? "SUCCESS" : "PARTIAL", value(analysis, "status"), "analysis completion status for the retained identity evidence");
        verifyCompletionDiagnostics(search, analysis, openPoints);
        return new SearchEvidence(Map.copyOf(sources), openPoints);
    }

    /** Shared by the standalone saved-snapshot relation gate and the export gate. */
    static Set<String> expectedTaskKeys(JsonNode sources) {
        var rules = new RelationCompatibilityMatrix(); var keys = new LinkedHashSet<String>();
        Set<String> roots = Set.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
        for (JsonNode source : sources) {
            JsonNode node = object(source, "node");
            String id = text(node, "id"), sourceRoot = text(node, "root");
            check(roots.contains(sourceRoot), "Source has an unknown catalogue root: " + id);
            for (String root : roots) for (RelationType type : RelationType.values()) {
                if (rules.allowedTargetRoots(sourceRoot, type).contains(root)
                        || rules.allowedTargetRoots(root, type).contains(sourceRoot)) {
                    keys.add(id + "|" + root); break;
                }
            }
        }
        return keys;
    }

    static boolean hasExactTaskPlan(JsonNode search) {
        if (!search.path("tasks").isArray() || !search.path("sources").isArray()) return false;
        var actual = new HashSet<String>();
        long completed = 0, unresolved = 0, pending = 0;
        for (JsonNode task : search.path("tasks")) {
            if (!task.path("sourceId").isString() || !task.path("targetRoot").isString()
                    || !actual.add(task.path("sourceId").asText() + "|" + task.path("targetRoot").asText())) return false;
            switch (task.path("state").asText()) {
                case "COMPLETED" -> completed++;
                case "UNRESOLVED" -> unresolved++;
                case "PENDING" -> pending++;
                default -> { return false; }
            }
        }
        // Missing root evidence is incomplete, not an excuse to infer a smaller plan.
        for (JsonNode source : search.path("sources")) {
            if (!source.at("/node/root").isString() || !Set.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA")
                    .contains(source.at("/node/root").asText())) return false;
        }
        JsonNode progress = search.path("progress");
        return actual.equals(expectedTaskKeys(search.path("sources")))
                && actual.size() == progress.path("totalSearches").asLong(-1)
                && completed == progress.path("completedSearches").asLong(-1)
                && unresolved == progress.path("unresolvedSearches").asLong(-1)
                && pending == progress.path("pendingSearches").asLong(-1);
    }

    private static Set<String> difference(Set<String> first, Set<String> second) {
        var result = new LinkedHashSet<>(first); result.removeAll(second); return result;
    }

    private static void verifyCompletionDiagnostics(JsonNode search, JsonNode analysis, List<OpenIdentity> openPoints) {
        List<JsonNode> unfinished = nodes(array(object(search, "result"), "unfinished"));
        var expectedWarnings = new ArrayList<String>();
        if (!openPoints.isEmpty()) {
            long calls = search.path("totalCalls").asLong(-1), maximum = search.path("maxCalls").asLong(-1);
            check(calls >= 0 && maximum >= calls, "Missing or conflicting relation call budget evidence");
            String summary = "RELATION_SEARCH_PARTIAL: " + calls + "/" + maximum + " evaluation attempts; " + unfinished.size()
                    + " unfinished search batches. 0 unassessed sources; 0 pending and " + search.path("progress").path("unresolvedSearches").asLong()
                    + " unresolved source/target searches.";
            same(summary, value(analysis, "errorMessage"), "exact partial identity diagnostic"); expectedWarnings.add(summary);
            unfinished.stream().limit(8).forEach(item -> expectedWarnings.add(text(item, "sourceId") + ": " + text(item, "question")));
        } else check(blank(value(analysis, "errorMessage")), "An unexplained analysis error remains");
        check(nodes(array(analysis, "warnings")).stream().map(JsonNode::asText).toList().equals(expectedWarnings), "Analysis warnings contain unreferenced errors or omit the open identity question");
        JsonNode recovery = analysis.get("recovery");
        if (openPoints.isEmpty() && (recovery == null || recovery.isNull())) return;
        check(recovery != null && recovery.isObject(), "Open identity questions require their durable recovery evidence");
        same(openPoints.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_GAPS", value(recovery, "state"), "durable recovery state");
        var expectedErrors = new LinkedHashMap<String, String>();
        for (JsonNode item : unfinished) expectedErrors.put("RELATION_UNRESOLVED: " + item.path("targets").get(0).asText() + ": " + text(item, "question"), item.path("targets").get(0).asText());
        var seenErrors = new LinkedHashSet<String>(); var keys = new HashSet<String>();
        for (JsonNode question : array(recovery, "openQuestions")) {
            String key = text(question, "key"), error = text(question, "error");
            check(key.matches("R[0-9a-f]{63}") && keys.add(key) && expectedErrors.containsKey(error), "Unrelated or uncertain durable question remains");
            check(question.path("skipped").isBoolean() && !question.path("skipped").asBoolean()
                            && question.path("outcomeUncertain").isBoolean() && !question.path("outcomeUncertain").asBoolean()
                            && question.path("attempts").asInt(0) > 0,
                    "A skipped or uncertain transport question cannot use the open identity allowance");
            check(strings(array(question, "nodes")).contains(expectedErrors.get(error)), "Durable identity question lacks its actual candidate endpoint");
            seenErrors.add(error);
        }
        check(seenErrors.equals(expectedErrors.keySet()), "Durable open questions differ from the accepted identity evidence");
    }

    private static List<Map<String, Object>> verifyOpenIdentityWitnesses(SearchEvidence search, List<Witness> witnesses,
            Reference reference, Set<Triple> projected, List<Edge> searchEdges) {
        var result = new ArrayList<Map<String, Object>>();
        for (OpenIdentity open : search.openPoints()) {
            JsonNode expected = open.expected(); String id = text(expected, "id"), source = text(expected, "sourceId");
            Triple triple = new Triple(source, text(expected, "type"), text(expected, "targetId"));
            check(!projected.contains(triple) && searchEdges.stream().noneMatch(e -> triple.equals(new Triple(e.sourceId(), e.type(), e.targetId()))),
                    id + ": the unresolved product identity was asserted as an architecture relation");
            List<Witness> local = witnesses.stream().filter(w -> w.kind().equals("ELEMENT") && w.endpointId().equals(source)
                    && quoteIds(w.quote(), reference.clauses()).containsAll(strings(expected.path("quoteIds")))).toList();
            check(local.stream().noneMatch(w -> assertsRecognizedInput(interpretation(w, reference.clauses()))),
                    id + ": an open input identity cannot coexist with an asserted recognized input product");
            Witness input = local.stream().filter(w -> untypedObservationInput(interpretation(w, reference.clauses()))).findFirst().orElse(null);
            check(input != null, id + ": the delivered acquisition contribution must retain the F5 observation batch and its unspecified REP identity locally");
            var point = new LinkedHashMap<String, Object>(); point.put("id", id); point.put("sourceId", source); point.put("type", triple.type()); point.put("targetId", triple.target());
            point.put("question", text(expected, "question")); point.put("quoteIds", strings(expected.path("quoteIds"))); point.put("quotes", nodes(array(expected, "quotes")).stream().map(JsonNode::asText).toList());
            point.put("sourceContribution", input.text()); point.put("sourceRequirementQuote", input.quote()); point.put("searchObservations", open.observations()); point.put("status", "OPEN_NONCORE_INPUT_IDENTITY"); result.add(point);
        }
        return result;
    }

    private static boolean untypedObservationInput(String value) {
        return matches("observation", value) && matches("batch", value)
                && matches("(?:product|datatype|data.type|input|representation|batch)[^.;]{0,70}(?:untyped|unspecified|unknown|not specified|not established)|(?:untyped|unspecified|unknown)[^.;]{0,50}(?:product|datatype|type|identity|representation)", value)
                && matches("(?:not|never|without)[^.;]{0,45}(?:assum|assert|identif|infer|treat)[^.;]{0,100}(?:environmental picture|\\brep\\b)", value)
                && !assertsRecognizedInput(value);
    }

    private static boolean assertsRecognizedInput(String value) {
        return matches("\\b(?:is|constitutes|has been established as)\\s+(?:an?\\s+)?(?:already )?(?:assembled |formally )?(?:recogni[sz]ed )?environmental picture", value);
    }

    private static List<Witness> verifyRelationship(String id, Triple triple, JsonNode expected, List<Edge> evidence,
                                                    List<Edge> searchEdges, Map<String, SourceAssessment> sources,
                                                    Reference reference, boolean required) {
        check(!evidence.isEmpty(), id + ": no source-bound relationship evidence");
        Set<String> observedQuotes = new LinkedHashSet<>(); var witnesses = new ArrayList<Witness>();
        boolean requiredEvidence = false;
        for (Edge edge : evidence) {
            check(triple.equals(new Triple(edge.sourceId(), edge.type(), edge.targetId())), id + ": evidence direction or endpoints differ from the relation");
            check(searchEdges.contains(edge), id + ": relationship evidence is absent from the retained source search");
            Contribution contribution = edge.contribution(); SourceAssessment source = sources.get(contribution.source().id());
            check(source != null && source.contributions().contains(contribution), id + ": source contribution is not bound to its assessed endpoint");
            var decision = edge.evidence();
            check(decision != null && decision.outcome() == Outcome.VERIFIED && blank(decision.question()), id + ": missing verified relationship decision");
            same(edge.target().id(), decision.targetId(), id + " decision endpoint");
            check(!blank(decision.contribution()), id + ": target contribution is missing");
            if (id.equals("L04")) verifyPicturePreparationCondition(decision.condition());
            for (Witness witness : List.of(new Witness("RELATION", id, contribution.source().id(), contribution.text(), contribution.quote(), contribution.condition()),
                    new Witness("RELATION", id, edge.target().id(), decision.contribution(), decision.quote(), Objects.toString(decision.condition(), "")))) {
                Set<String> quoteIds = verifyQuote(witness.quote(), reference, id + " endpoint " + witness.endpointId());
                Set<String> allowed = reference.endpointClauses().get(witness.endpointId());
                check(allowed == null || allowed.containsAll(quoteIds), id + ": wrong endpoint quote binding for " + witness.endpointId() + ": " + quoteIds);
                check(!quoteIds.contains("A1") || !matches(ASSUMPTION_CONTRADICTION, interpretation(witness, reference.clauses())),
                        id + ": an A1 design assumption was falsely declared a production fact");
                String meaning = ENDPOINT_MEANINGS.get(witness.endpointId());
                check(meaning == null || matches(meaning, interpretation(witness, reference.clauses())),
                        id + ": contribution does not describe the bound endpoint " + witness.endpointId());
                String role = witness.endpointId().equals(triple.source()) ? "source" : "target";
                Set<String> roleQuotes = strings(object(expected, "endpointQuoteIds").path(role));
                check(!roleQuotes.isEmpty() && roleQuotes.containsAll(quoteIds),
                        id + ": wrong " + role + " contribution role/quote binding for " + witness.endpointId());
                for (JsonNode term : object(expected, "endpointTerms").path(role))
                    check(matches(term.asText(), declaration(witness.text(), reference.clauses())),
                            id + ": contribution does not describe its " + role + " role at " + witness.endpointId());
                observedQuotes.addAll(quoteIds); witnesses.add(witness);
            }
            if (!required) verifyPermittedBoundary(id, expected, decision.condition(), witnesses, reference.clauses());
            if (decision.necessity() == Necessity.REQUIRED) requiredEvidence = true;
        }
        check(!required || requiredEvidence, id + ": required relation has no REQUIRED evidence");
        check(observedQuotes.containsAll(strings(expected.path("quoteIds"))), id + ": relationship quotes do not cover the independently required source clauses");
        String condition = value(expected, "condition");
        if (!blank(condition)) {
            check(witnesses.stream().anyMatch(w -> !blank(w.condition())), id + ": required relationship condition is missing");
            if (condition.contains("A1")) check(witnesses.stream().anyMatch(w -> assumption(w.text() + " " + w.condition())), id + ": the A1 design assumption is not scoped locally");
            if (condition.contains("selected by the subscriber") || condition.contains("when text is selected") || condition.contains("when phone is selected") || condition.contains("when email is selected")) {
                check(witnesses.stream().anyMatch(w -> matches(SELECTED, w.condition()) && matches(PERSON, w.condition())),
                        id + ": individual delivery is not bound to subscriber channel choice");
            }
        }
        if (expected.has("necessity")) {
            String necessity = text(expected, "necessity");
            check(evidence.stream().allMatch(e -> e.evidence().necessity() != null && e.evidence().necessity().name().equals(necessity)),
                    id + ": relationship necessity differs from the independently specified " + necessity);
        }
        return witnesses;
    }

    /** Every permitted binding retains its own selection scope and exclusions, independently of global coverage. */
    private static void verifyPermittedBoundary(String id, JsonNode expected, String condition,
            List<Witness> witnesses, Map<String, String> clauses) {
        check(!blank(condition), id + ": permitted relationship has no locally declared boundary");
        for (JsonNode term : array(expected, "conditionTerms"))
            check(matches(term.asText(), condition), id + ": permitted relationship condition omits its selection/function boundary: " + term.asText());
        for (JsonNode forbidden : array(expected, "excludedClaims")) for (Witness witness : witnesses)
            for (String claim : interpretation(witness, clauses).split("[.;]|(?i)\\bbut\\b|(?i)\\bhowever\\b")) {
                // Explicitly negated exclusions are allowed; inspect affirmative clauses separately.
                String affirmative = claim.replaceAll("(?i)\\b(?:no|not|never|without|does not|do not|must not)\\b[^.;]*", "");
                check(!matches(forbidden.asText(), affirmative), id + ": permitted relationship asserts an excluded function: " + claim);
            }
    }

    /** Only the independent L04/F1 selection boundary; not unrestricted English equivalence. */
    private static void verifyPicturePreparationCondition(String condition) {
        check(!blank(condition), "L04: the selected picture-preparation decomposition needs a condition");
        check(matches("\\b(?:selected|chosen)\\b[^.;]{0,100}\\b(?:layer|decomposition)\\b"
                        + "|\\b(?:layer|decomposition)\\b[^.;]{0,100}\\b(?:selected|chosen)\\b", condition),
                "L04: the picture-preparation layer must be an explicitly selected decomposition");
        // Inspect each decision's own clauses: a valid source witness or an
        // additional selection phrase cannot excuse a contradictory duty.
        // Keep comma parentheticals with their subject and duty. A negated
        // contrast ("requires the view, not this ... decomposition") stays separate.
        for (String clause : condition.split("[.;]|(?i),(?=\\s*not\\b)|\\bbut\\b")) {
            String affirmed = clause
                    .replaceAll("(?i)\\b(?:no|not(?: an?)?)\\s+(?:mandatory|unavoidable|required)\\b", "")
                    .replaceAll("(?i)\\b(?:do(?:es)? not|must not|need not)\\s+(?:require|introduce|add)\\b", "");
            boolean deployment = matches("\\b(?:services?|deployments?|deployed|dependenc(?:y|ies)|layers?|decomposition)\\b", affirmed);
            boolean obligation = matches("\\b(?:must|mandatory|unavoidable|requires?|required|unconditionally)\\b", affirmed);
            check(!(deployment && obligation), "L04: the selected view cannot impose an unconditional service or deployment duty");
        }
    }

    private static Set<String> verifyQuote(String quote, Reference reference, String binding) {
        Set<String> ids = quoteIds(quote, reference.clauses());
        check(!ids.isEmpty(), binding + ": no exact requirement-clause quote");
        String remainder = quote;
        for (String clause : reference.clauses().values()) remainder = remainder.replace(clause, "");
        check(reference.original().contains(quote) || remainder.replaceAll("[\\s/;|]+", "").isEmpty(), binding + ": quote contains material absent from the unchanged source");
        return ids;
    }

    private static void retainedReview(JsonNode projected, JsonNode frozen, Map<String, String> attributes, String id) {
        for (String field : List.of("reviewStatus", "decisionBy", "decisionAt", "decisionComment")) {
            String expected = value(projected, field);
            same(expected, value(frozen, field), id + " frozen " + field);
            same(expected, attributes.get("tag:taxonomy." + (field.equals("decisionComment") ? "decisionRationale" : field)), id + " Sparx " + field);
        }
    }

    private static void verifyXmi(ExchangeDocument delivered, byte[] xmi) {
        ExchangeDocument parsed;
        try { parsed = new SparxXmiCodec("3").read(xmi, "quality-check", true); }
        catch (RuntimeException failure) { throw new AssertionError("The delivered XMI fails the explicit semantic profile: " + failure.getMessage(), failure); }
        check(indexArtifacts(delivered.artifacts()).equals(indexArtifacts(parsed.artifacts())), "XMI elements, Notes or evidence tags differ from the delivered model");
        var expected = new LinkedHashMap<String, Relation>(); var actual = new LinkedHashMap<String, Relation>();
        delivered.relations().forEach(r -> expected.put(r.id(), r)); parsed.relations().forEach(r -> actual.put(r.id(), r));
        check(expected.equals(actual), "XMI relationships, direction or evidence differ from the delivered model");
        check(delivered.metadata().equals(parsed.metadata()), "XMI model provenance differs from the delivered model");
    }

    private static Map<String, Artifact> indexArtifacts(List<Artifact> artifacts) {
        var result = new LinkedHashMap<String, Artifact>();
        for (Artifact item : artifacts) check(result.put(item.id(), item) == null, "Duplicate delivered artifact identity");
        return result;
    }

    private static Map<String, JsonNode> index(JsonNode entries, String field, boolean omitContainers) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (JsonNode entry : entries) if (!omitContainers || !entry.path("container").asBoolean())
            check(result.put(text(entry, field), entry) == null, "Duplicate frozen identity " + value(entry, field));
        return result;
    }

    private static Map<Triple, JsonNode> edges(JsonNode entries, String source, String type, String target) {
        var result = new LinkedHashMap<Triple, JsonNode>();
        for (JsonNode entry : entries) {
            Triple triple = new Triple(text(entry, source), text(entry, type), text(entry, target));
            check(result.put(triple, entry) == null, "Duplicate directed relation " + triple);
        }
        return result;
    }

    /** Independently recompute the documented canonical graph v1 byte format; never trust an exported digest. */
    private static String graphSha256(JsonNode graph) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            writeText(output, "taxonomy-canonical-graph-v1");
            List<JsonNode> nodes = nodes(array(graph, "nodes")).stream().filter(n -> !n.path("container").asBoolean())
                    .sorted(Comparator.comparing(n -> text(n, "id"))).toList();
            Set<String> ids = new HashSet<>(); nodes.forEach(n -> check(ids.add(text(n, "id")), "Duplicate canonical graph node"));
            output.writeInt(nodes.size());
            for (JsonNode node : nodes) {
                writeText(output, text(node, "id")); writeText(output, text(node, "label")); writeText(output, text(node, "type"));
                output.writeDouble(node.path("relevance").asDouble()); output.writeBoolean(node.path("anchor").asBoolean());
                output.writeInt(node.path("depth").asInt()); output.writeBoolean(node.path("selectedForImpact").asBoolean());
                String parent = value(node, "parentId"); writeText(output, ids.contains(parent) ? parent : null);
            }
            List<JsonNode> edges = nodes(array(graph, "edges")).stream().filter(e -> ids.contains(value(e, "sourceId")) && ids.contains(value(e, "targetId")))
                    .sorted(Comparator.comparing(e -> text(e, "id"))).toList();
            output.writeInt(edges.size());
            for (JsonNode edge : edges) {
                writeText(output, text(edge, "id")); writeText(output, text(edge, "sourceId")); writeText(output, text(edge, "targetId"));
                writeText(output, text(edge, "relationType")); output.writeDouble(edge.path("relevance").asDouble()); writeText(output, value(edge, "relationCategory"));
            }
            output.flush(); return sha256(bytes.toByteArray());
        } catch (IOException failure) { throw new AssertionError("Cannot fingerprint canonical graph", failure); }
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        if (text == null) { output.writeInt(-1); return; }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static <T> T convert(JsonNode node, Class<T> type, String field) {
        try { return JSON.treeToValue(node, type); } catch (RuntimeException failure) { throw new AssertionError("Invalid " + field, failure); }
    }
    private static List<JsonNode> nodes(JsonNode array) { var result = new ArrayList<JsonNode>(); array.forEach(result::add); return result; }
    private static JsonNode object(JsonNode parent, String field) { JsonNode result = parent.path(field); check(result.isObject(), "Missing object " + field); return result; }
    private static JsonNode array(JsonNode parent, String field) { JsonNode result = parent.path(field); check(result.isArray(), "Missing array " + field); return result; }
    private static String text(JsonNode parent, String field) { String result = value(parent, field); check(!blank(result), "Missing " + field); return result; }
    private static String value(JsonNode parent, String field) { JsonNode result = parent.path(field); return result.isMissingNode() || result.isNull() ? null : result.asText(); }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static void same(String expected, String actual, String field) { check(Objects.equals(expected, actual), field + " differs from its independent or frozen source"); }

    record Witness(String kind, String id, String endpointId, String text, String quote, String condition) { }

    static Map<String, Object> verifyConstraint(JsonNode constraint, List<Witness> local, Map<String, String> clauses) {
        String id = constraint.path("id").asText();
        Obligation obligation = OBLIGATIONS.get(id);
        check(obligation != null, id + ": no independent executable obligation is defined");
        Set<String> elements = strings(constraint.path("binding").path("elementIds"));
        Set<String> relations = strings(constraint.path("binding").path("relationIds"));
        Set<String> requiredQuotes = strings(constraint.path("quoteIds"));
        List<Witness> scoped = local.stream().filter(w -> w.kind().equals("ELEMENT")
                        ? elements.contains(w.id()) : w.kind().equals("RELATION") && relations.contains(w.id()))
                .filter(w -> quoteIds(w.quote(), clauses).stream().anyMatch(requiredQuotes::contains))
                .filter(w -> !obligation.assumption() || assumption(w.text() + " " + w.condition()))
                .toList();
        check(!scoped.isEmpty(), id + ": no local contribution with the required element/relation and clause binding"
                + (obligation.assumption() ? " and explicit A1 assumption scope" : ""));
        var supporting = new LinkedHashSet<Witness>();
        for (String forbidden : obligation.forbidden()) {
            for (Witness witness : scoped) check(!matches(forbidden, interpretation(witness, clauses)),
                    id + ": a bound contribution contradicts the required behavior at " + witness.id() + ": " + witness.text());
        }
        for (Term term : obligation.terms()) {
            List<Witness> found = scoped.stream().filter(w -> matches(term.pattern(), interpretation(w, clauses))).toList();
            check(!found.isEmpty(), id + ": bound contributions do not describe " + term.description()
                    + "; required behavior: " + constraint.path("requiredBehavior").asText());
            supporting.addAll(found);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("title", constraint.path("title").asText()); result.put("verdict", "PASS");
        result.put("requiredBehavior", constraint.path("requiredBehavior").asText());
        result.put("witnesses", supporting.stream().map(w -> Map.of("kind", w.kind(), "bindingId", w.id(),
                "endpointId", w.endpointId(), "contribution", w.text(), "quote", w.quote(), "condition", w.condition())).toList());
        return result;
    }

    /* These rules recognize declared properties in this bounded English scenario. They do not
       prove arbitrary prose, deployed execution, an algorithm, an SLA or a safety certification. */
    private record Term(String description, String pattern) { }
    private record Obligation(List<Term> terms, List<String> forbidden, boolean assumption) { }
    private static final String SELECTED = "select(?:ed|s|ing|ion)?|chosen|chooses?|preferences?";
    private static final String PERSON = "users?|subscribers?";
    private static final String TIME = "observation(?:s|['’]s)?[ -]+(?:time|timestamp)|(?:time|timestamp)(?: of)?(?: the)? observation";
    private static final String NEGATED_CHANNEL = "(?:do(?:es)? not|cannot|need not|will not|not required to)[^.;]{0,60}(?:support|provide|deliver|send)[^.;]{0,60}(?:text|phone|voice|email)|(?:text|phone|voice|email)[^.;]{0,65}(?:support|capability)[^.;]{0,25}(?:optional|not required|unnecessary)";
    private static final String ASSUMPTION_CONTRADICTION = "a1 (?:is|as) (?:a )?(?:verified|observed|confirmed|established)[^.;]{0,40}fact|rather than an assumption|not (?:an? )?(?:test design )?assumption";
    // Minimal independent endpoint meanings catch an email claim attached to a queue, or a phone claim attached to an email service.
    private static final Map<String, String> ENDPOINT_MEANINGS = Map.ofEntries(
            Map.entry("CR-1097", "queue"), Map.entry("CO-1066", "phone|voice"), Map.entry("CO-1048", "text|sms|short.message"),
            Map.entry("CR-1060", "text|sms|short.message"), Map.entry("CR-1037", "e-?mail"),
            Map.entry("CR-1098", "rout|selected[^.;]{0,50}channel|channel[^.;]{0,50}preferences?"),
            Map.entry("IP-1116", "observation|environmental|water"), Map.entry("IP-2130", "warning|alert"),
            Map.entry("CR-1198", "deliver[^.;]{0,80}(?:histor|record|audit|outcome)|(?:histor|record|audit)[^.;]{0,80}deliver"));
    private static final Map<String, Obligation> OBLIGATIONS = obligations();

    private static Map<String, Obligation> obligations() {
        var result = new LinkedHashMap<String, Obligation>();
        add(result, "C01", false, List.of(),
                "presentation", "present|display|view", "published warnings", "published[^.;]{0,70}(?:flood )?warnings?",
                "affected areas", "affected areas?", "water observations", "(?:water|river|hydrographic) observations?|water levels?",
                "understandable information", "understandable|comprehensible|accessible|clear(?:ly)?",
                "residents", "residents?", "public authorities", "public authorit");
        add(result, "C02", false, List.of(), "information origin", "\\bsource\\b|\\borigin\\b", "observation time", TIME);
        add(result, "C03", false, List.of(), "administration", "administ", "service oversight", "oversight|supervis|monitor|operat", "the service", "service");
        add(result, "C04", false, List.of("(?:every|all) (?:warning|message)[^.;]{0,50}(?:every|all) (?:user|subscriber|channel)"),
                "area subscriptions", "area subscriptions?|subscrib(?:ed|es?|ing)[^.;]{0,30}areas?",
                "retained preferences", "retain|persist|sav(?:e|ed|ing)|stor(?:e|ed|ing)|maintain|record|manage",
                "selected channels", "(?:" + SELECTED + ")[^.;]{0,70}channels?|channels?[^.;]{0,70}(?:" + SELECTED + ")",
                "preference-based delivery selection", "rout|applicable deliver|select[^.;]{0,40}deliver|deliver[^.;]{0,60}preferences?");
        for (String id : List.of("C05", "C06", "C07")) {
            String channel = switch (id) { case "C05" -> "\\btext\\b|\\bsms\\b"; case "C06" -> "phone|voice"; default -> "e-?mail"; };
            add(result, id, false, List.of(NEGATED_CHANNEL), "the required channel", channel,
                    "notification support", "support|provid|deliver|notif|calls?", "user choice", SELECTED, "the selecting user", PERSON);
        }
        add(result, "C08", false, List.of("(?:do(?:es)? not|cannot|never|not required to)[^.;]{0,30}updat"),
                "updating contact details", "updat[^.;]{0,40}contact|contact[^.;]{0,40}updat", "the entitled user", "entitl|authoriz|authoris|users?[^.;]{0,30}(?:their|own)");
        add(result, "C09", false, List.of(), "area removal", "remov[^.;]{0,40}areas?|areas?[^.;]{0,40}remov",
                "ending active delivery selection", "stop|no longer|cease|inactive|not[^.;]{0,30}active|exclud[^.;]{0,40}deliver");
        add(result, "C10", false, List.of("delet[^.;]{0,40}(?:not required|optional)|(?:do(?:es)? not|never|cannot)[^.;]{0,30}delet|indefinit[^.;]{0,50}suspend|suspend[^.;]{0,50}indefinit"),
                "account deletion", "account[^.;]{0,40}delet|delet[^.;]{0,40}account|deprovision[^.;]{0,40}account");
        add(result, "C11", false, List.of(), "surface-water flooding", "surface[ -]water",
                "a visible scope statement", "show|display|state|explain|visible|communicat|present", "explicit exclusion", "outside[^.;]{0,25}scope|out of scope|exclud");
        add(result, "C12", false, List.of("guaranteed end.to.end|exactly.once|hard real.time"),
                "consumption of the observation batch", "consum|acquir|ingest", "observations", "observation", "a batch", "batch",
                "the latest available batch", "latest", "the unchanged cadence wording", "at most every 15 minutes");
        add(result, "C13", false, List.of("(?:do(?:es)? not|never)[^.;]{0,30}(?:show|display)[^.;]{0,30}stale"),
                "a visible stale indication", "show|display|indicat|flag|mark|present", "staleness", "stale",
                "source context", "source|origin", "observation-time context", TIME);
        add(result, "C14", false, List.of("(?:do(?:es)? not|never)[^.;]{0,30}(?:show|display)[^.;]{0,30}unavailable"),
                "a visible unavailable indication", "show|display|indicat|flag|mark|present", "unavailability", "unavailable", "observations or data", "observation|data");
        add(result, "C15", false, List.of("(?:do(?:es)? not|cannot|must not|need not|will not|never|no longer)[^.;]{0,40}(?:retain|preserv|keep)[^.;]{0,65}(?:official|warning|alert)|(?:retain|preserv|keep)[^.;]{0,65}(?:official|warning|alert)[^.;]{0,40}(?:not required|optional)"), "retained official channels", "retain|preserv|keep", "official authority", "official", "warning channels", "warning|alert", "channels", "channel");
        add(result, "C16", true, List.of("durab[^.;]{0,30}(?:optional|not required)|(?:not|non)[ -]durab"),
                "durability", "durab", "a delivery queue", "queue", "delivery work", "deliver|notification");
        add(result, "C17", true, List.of("idempoten[^.;]{0,30}(?:optional|not required)|(?:not|non)[ -]idempoten|end.to.end exactly.once"),
                "retries", "retr(?:y|ies)|repeat[^.;]{0,30}work", "idempotence or duplicate-safe processing", "idempoten|(?:no|without|not cause)[^.;]{0,30}duplicate[^.;]{0,40}process", "delivery work", "deliver");
        add(result, "C18", true, List.of(), "subscriber data", "subscriber[^.;]{0,30}(?:data|detail)|(?:data|detail)[^.;]{0,30}subscriber",
                "data protection", "protect|restrict|authoriz|authoris", "entitled access or actions", "entitl|authoriz|authoris|access control");
        add(result, "C19", true, List.of("(?:do(?:es)? not|cannot|must not|need not|will not|never|no longer)[^.;]{0,40}(?:retain|preserv|keep|record|maintain)[^.;]{0,85}(?:audit|history|deliver)|(?:audit|history)[^.;]{0,55}(?:not required|optional)"), "auditability", "audit", "a retained history", "history|record", "delivery work", "deliver", "delivery outcomes", "outcomes?|results?|statuses|status|attempts?");
        add(result, "C20", false, List.of("(?:is|as) a safety[ -]critical replacement"), "a supplementary service", "supplementary",
                "the safety-critical replacement boundary", "not[^.;]{0,35}safety[ -]critical[^.;]{0,35}replacement|not[^.;]{0,35}replacement[^.;]{0,35}safety[ -]critical",
                "official alert authority", "official[^.;]{0,30}(?:alerts?|warnings?)");
        return Map.copyOf(result);
    }

    private static void add(Map<String, Obligation> values, String id, boolean assumption, List<String> forbidden, String... pairs) {
        var terms = new ArrayList<Term>();
        for (int index = 0; index < pairs.length; index += 2) terms.add(new Term(pairs[index], pairs[index + 1]));
        values.put(id, new Obligation(List.copyOf(terms), forbidden, assumption));
    }

    private static String interpretation(Witness witness, Map<String, String> clauses) {
        return declaration(witness.text() + " " + witness.condition(), clauses);
    }

    private static String declaration(String text, Map<String, String> clauses) {
        String result = text;
        // Original quotations are evidence of origin, never witnesses of a component's behavior.
        for (String clause : clauses.values()) result = result.replace(clause, "");
        return result;
    }

    private static boolean assumption(String value) {
        return matches("\\ba1\\b", value) && matches("assumption|test design", value)
                && !matches(ASSUMPTION_CONTRADICTION, value);
    }

    private static Set<String> quoteIds(String quote, Map<String, String> clauses) {
        var result = new LinkedHashSet<String>();
        if (quote == null || quote.strip().length() < 20) return result;
        clauses.forEach((id, clause) -> { if (quote.contains(clause) || clause.contains(quote.strip())) result.add(id); });
        return result;
    }

    private static boolean matches(String regex, String value) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(value).find();
    }

    private static Set<String> strings(JsonNode array) {
        var result = new LinkedHashSet<String>();
        for (JsonNode value : array) result.add(value.asText());
        return result;
    }

    private static void check(boolean condition, String diagnostic) { if (!condition) throw new AssertionError(diagnostic); }
}
