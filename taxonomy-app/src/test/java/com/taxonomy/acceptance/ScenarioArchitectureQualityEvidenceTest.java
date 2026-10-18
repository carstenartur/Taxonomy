package com.taxonomy.acceptance;

import com.taxonomy.archimate.exchange.ArchiMateRelationProvenance;
import com.taxonomy.diagram.*;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Small handwritten declarations exercise the complete evidence chain independently of playback. */
class ScenarioArchitectureQualityEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode REFERENCE = reference();
    private static final String ORIGINAL = REFERENCE.path("requirement").path("text").asText();
    private static final String SNAPSHOT = "independent-snapshot";
    private static final String A1 = "Within test design assumption A1.";
    private static final String CHANNEL = "A delivery uses this channel only when selected by the subscriber; channel support is required.";
    // This hand-maintained list is test data, not generated from the reference's expected relation table.
    private static final List<Spec> RELATIONS = List.of(
            new Spec("R01", "UA-1580", "CONSUMES", "IP-1116", "F1", "F1"),
            new Spec("R02", "UA-1580", "CONSUMES", "IP-2130", "F1", "F1"),
            new Spec("R03", "UA-1580", "SUPPORTS", "BP-1535", "F1", "F1"),
            new Spec("R04", "BP-1017", "CONSUMES", "IP-2130", "F1", "F1"),
            new Spec("R05", "BP-1535", "CONSUMES", "IP-2130", "F3", "F1"),
            new Spec("R06", "BP-1535", "USES", "CR-1098", "F3", "F3"),
            new Spec("R07", "BP-1535", "USES", "CR-1097", "A1", "A1"),
            new Spec("R08", "CR-1060", "SUPPORTS", "BP-1535", "F3", "F3"),
            new Spec("R09", "CO-1048", "SUPPORTS", "BP-1535", "F3", "F3"),
            new Spec("R10", "CO-1066", "SUPPORTS", "BP-1535", "F3", "F3"),
            new Spec("R11", "CR-1037", "SUPPORTS", "BP-1535", "F3", "F3"),
            new Spec("R12", "BP-1521", "USES", "CR-1022", "F4", "F4"),
            new Spec("R13", "BP-1530", "USES", "CR-1022", "F4", "F4"),
            new Spec("R14", "CR-1007", "SUPPORTS", "BP-1140", "A1", "A1"),
            new Spec("R15", "CR-1006", "SUPPORTS", "BP-1140", "F4", "A1"),
            new Spec("R16", "CR-1007", "SUPPORTS", "BP-1536", "A1", "A1"),
            new Spec("R17", "BP-1535", "USES", "CR-1198", "A1", "A1"),
            new Spec("R18", "UA-1522", "USES", "CI-1060", "F2", "F2"),
            new Spec("R19", "CI-1060", "SUPPORTS", "BP-2009", "F2", "F5"));

    @Test void independentlyWrittenModelSatisfiesAllBoundObligationsAndRelations() {
        var result = verify(fixture(""));
        assertEquals("PASS", result.get("verdict"));
        assertEquals(20, result.get("satisfiedConstraintCount"));
        assertEquals(19, result.get("requiredRelationCount"));
        assertEquals(Boolean.TRUE, result.get("sourceAndSearchEvidenceComplete"));
        assertEquals("DECLARED_FUNCTIONAL_ARCHITECTURE", result.get("evaluationScope"));
    }

    @Test void incomingSearchEvidenceIsNormalizedWithoutReversingTheArchitectureMeaning() {
        assertEquals("PASS", verify(fixture("incoming-evidence")).get("verdict"));
    }

    @ParameterizedTest @ValueSource(strings = {"missing-phone-relation", "reversed-phone-relation", "wrong-decision-endpoint", "wrong-queue-endpoint-quote", "wrong-queue-contribution"})
    void rejectsDirectedRelationAndEvidenceMisbindings(String mutation) {
        var failure = assertThrows(AssertionError.class, () -> verify(fixture(mutation)));
        assertTrue(failure.getMessage().contains(mutation.contains("queue") ? "R07" : "R10"), failure::getMessage);
    }

    @ParameterizedTest @ValueSource(strings = {"queue-label-only", "no-account-deletion", "no-source-time", "no-stale", "no-unavailable", "unscoped-assumption"})
    void matchingTransportCannotHideAMissingFunctionalDeclaration(String mutation) {
        String expected = switch (mutation) {
            case "queue-label-only" -> "C16";
            case "unscoped-assumption" -> "R07";
            case "no-account-deletion" -> "C10";
            case "no-source-time" -> "C02";
            case "no-stale" -> "C13";
            default -> "C14";
        };
        var failure = assertThrows(AssertionError.class, () -> verify(fixture(mutation)));
        assertTrue(failure.getMessage().contains(expected), failure::getMessage);
    }

    @Test void globalOriginalTextCannotReplaceEveryLocalContribution() {
        var value = fixture("global-text-only");
        assertEquals(ORIGINAL, value.document().metadata().get("taxonomy.requirementText"));
        var failure = assertThrows(AssertionError.class, () -> verify(value));
        assertTrue(failure.getMessage().contains("R01") && failure.getMessage().contains("bound endpoint"), failure::getMessage);
    }

    @Test void requirementTextMustMatchTheIndependentOriginal() {
        var value = fixture(""); value.projection().put("requirementText", ORIGINAL + " Additional live requirement.");
        assertFailure(value, "requirementText");
    }

    @ParameterizedTest @ValueSource(strings = {"taxonomy.requirementSha256", "taxonomy.snapshotId", "taxonomy.requirementVersionId", "taxonomy.canonicalGraphSha256"})
    void modelProvenanceCannotBeForgedInTheDeliveredModel(String field) {
        var value = fixture(""); var metadata = new LinkedHashMap<>(value.document().metadata());
        metadata.put(field, field.endsWith("Sha256") ? "0".repeat(64) : "different-snapshot-value");
        var changed = withMetadata(value.document(), metadata);
        byte[] xmi = field.equals("taxonomy.requirementSha256") ? value.xmi() : new SparxXmiCodec("3").write(changed);
        assertFailure(new Fixture(value.snapshot(), value.projection(), changed, xmi),
                field.endsWith("Sha256") ? "SHA" : field);
    }

    @Test void emptyUnfinishedListDoesNotExcuseAnUnassessedSource() {
        var value = fixture(""); ObjectNode progress = (ObjectNode) value.snapshot().path("analysis").path("relationSearchReport").path("progress");
        progress.put("totalSources", progress.path("totalSources").asInt() + 1);
        assertFailure(value, "source");
    }

    @ParameterizedTest @ValueSource(strings = {"pendingSearches", "unresolvedSearches"})
    void pendingOrUnresolvedSearchWorkCannotCertifyTheArchitecture(String field) {
        var value = fixture(""); ((ObjectNode) value.snapshot().path("analysis").path("relationSearchReport").path("progress")).put(field, 1);
        assertFailure(value, "search");
    }

    @Test void anExportedElementMustRetainItsSpecificContributionAndReadableNotes() {
        var value = fixture(""); Artifact original = value.document().artifacts().getFirst();
        var attributes = new LinkedHashMap<>(original.attributes()); attributes.remove("tag:taxonomy.presenceReason");
        Artifact missing = new Artifact(original.id(), original.kind(), original.type(), original.title(), original.text(), attributes, original.extensions());
        assertFailure(replace(value, original, missing), "presenceReason");
        Artifact blankNotes = new Artifact(original.id(), original.kind(), original.type(), original.title(), "", original.attributes(), original.extensions());
        assertFailure(replace(value, original, blankNotes), "Notes");
    }

    @Test void retainingOnlyTypeAndIdentityIsInsufficientRelationshipEvidence() {
        var value = fixture(""); var relations = new ArrayList<>(value.document().relations()); Relation before = relations.get(6);
        var attributes = new LinkedHashMap<>(before.attributes()); attributes.remove("tag:taxonomy.provenance.evidence.0.decision.quote");
        relations.set(6, new Relation(before.id(), before.type(), before.source(), before.target(), attributes, before.extensions()));
        ExchangeDocument changed = document(value.document(), value.document().artifacts(), relations, value.document().metadata());
        assertFailure(new Fixture(value.snapshot(), value.projection(), changed, new SparxXmiCodec("3").write(changed)), "provenance");
    }

    @Test void aFalseProductionFactCannotHideBesideAnAccuratelyScopedA1Witness() {
        assertFailure(fixture("false-A1-production-fact"), "R14");
    }

    @Test void anUnrecordedReviewerCannotBeAddedToTheExportedEvidence() {
        var value = fixture(""); Artifact original = value.document().artifacts().getFirst();
        var attributes = new LinkedHashMap<>(original.attributes()); attributes.put("tag:taxonomy.decisionBy", "invented-reviewer");
        Artifact changed = new Artifact(original.id(), original.kind(), original.type(), original.title(), original.text(), attributes, original.extensions());
        assertFailure(replace(value, original, changed), "decisionBy");
    }

    @Test void xmiBytesMustBeTheSameConcreteDeliveredModel() {
        var value = fixture(""); var other = fixture("queue-label-only");
        assertFailure(new Fixture(value.snapshot(), value.projection(), value.document(), other.xmi()), "XMI");
    }

    static Fixture fixture(String mutation, Spec... additionalRelations) {
        Map<String, Map<String, String>> declarations = declarations();
        Set<String> additionalEndpoints = new LinkedHashSet<>();
        for (Spec spec : additionalRelations) { additionalEndpoints.add(spec.source()); additionalEndpoints.add(spec.target()); }
        if (additionalEndpoints.contains("CP-1041")) add(declarations, "CP-1041", "F3", "Disseminate notifications for subscribed areas with required text, phone and email channel capabilities; individual deliveries follow the subscriber's choice.");
        if (additionalEndpoints.contains("CP-1123")) add(declarations, "CP-1123", "A1", "Protect subscriber details and restrict account access under test design assumption A1.");
        if (additionalEndpoints.contains("CI-1096")) add(declarations, "CI-1096", "F1", "Compile the understandable environmental view from existing published observations, preserving source and observation time.");
        declarations.forEach((id, byClause) -> byClause.replaceAll((clause, text) -> mutate(mutation, id, clause, text)));
        Map<String, Node> nodes = new LinkedHashMap<>(); Map<String, Map<String, Contribution>> contributions = new LinkedHashMap<>();
        declarations.forEach((id, byClause) -> {
            Node node = new Node(id, id.substring(0, 2), "Handwritten " + id, "Small independent test witness.", false); nodes.put(id, node);
            Map<String, Contribution> scoped = new LinkedHashMap<>();
            byClause.forEach((clause, text) -> scoped.put(clause, new Contribution(node, text, quote(clause),
                    clause.equals("A1") && !mutation.equals("unscoped-assumption") ? A1 : "")));
            contributions.put(id, scoped);
        });
        List<DiagramNode> diagramNodes = new ArrayList<>(); List<Artifact> artifacts = new ArrayList<>();
        Map<String, Object> elementMetadata = new LinkedHashMap<>(); List<Map<String, Object>> elementMappings = new ArrayList<>();
        nodes.forEach((id, node) -> {
            String presence = "Requirement-scoped contribution: " + String.join(" ", declarations.get(id).values());
            diagramNodes.add(new DiagramNode(id, node.name(), "Component", 1.0, false, 0));
            elementMetadata.put(id, Map.of("presenceReason", presence, "reviewStatus", "PROPOSED"));
            elementMappings.add(Map.of("snapshotId", SNAPSHOT, "nodeCode", id, "presenceReason", presence, "reviewStatus", "PROPOSED"));
            artifacts.add(new Artifact(guid(id), ArtifactKind.ELEMENT, "Component", node.name(), presence,
                    Map.of("tag:taxonomy.sourceId", id, "tag:taxonomy.presenceReason", presence, "tag:taxonomy.reviewStatus", "PROPOSED"), Map.of("canonicalType", "Component")));
        });
        List<DiagramEdge> diagramEdges = new ArrayList<>(); List<Relation> relations = new ArrayList<>(); List<Edge> evidence = new ArrayList<>();
        List<Map<String, Object>> included = new ArrayList<>(); Map<String, Object> relationMetadata = new LinkedHashMap<>(); List<Map<String, Object>> relationMappings = new ArrayList<>();
        List<Spec> relationSpecs = new ArrayList<>(RELATIONS); relationSpecs.addAll(List.of(additionalRelations));
        for (Spec spec : relationSpecs) {
            if (spec.id().equals("R10") && mutation.equals("missing-phone-relation")) continue;
            boolean reverseGraph = spec.id().equals("R10") && mutation.equals("reversed-phone-relation");
            String source = reverseGraph ? spec.target() : spec.source(), target = reverseGraph ? spec.source() : spec.target();
            Contribution from = contributions.get(spec.source()).get(spec.sourceClause()), to = contributions.get(spec.target()).get(spec.targetClause());
            boolean incoming = mutation.equals("incoming-evidence") && spec.id().equals("R01");
            if (incoming) { Contribution swap = from; from = to; to = swap; }
            String condition = !spec.condition().isBlank() ? spec.condition()
                    : spec.id().matches("R0[89]|R1[01]") ? CHANNEL : to.condition();
            if (mutation.equals("false-A1-production-fact") && spec.id().equals("R14")) condition = "A1 is a verified production fact rather than an assumption.";
            String decisionTarget = spec.id().equals("R10") && mutation.equals("wrong-decision-endpoint") ? "CR-1037" : to.source().id();
            String decisionQuote = spec.id().equals("R07") && mutation.equals("wrong-queue-endpoint-quote") ? quote("F3") : to.quote();
            String decisionText = spec.id().equals("R07") && mutation.equals("wrong-queue-contribution") ? "Provide selected email notification delivery." : to.text();
            Edge edge = new Edge(from, to.source(), spec.type(), incoming ? Direction.INCOMING : Direction.OUTGOING,
                    new com.taxonomy.dto.RelationSearchModel.Decision(decisionTarget, Outcome.VERIFIED, decisionText, decisionQuote, spec.necessity(), condition, "", "The scoped capability contributes to this directed relation.", ""));
            evidence.add(edge);
            DiagramRelationMetadata metadata = new DiagramRelationMetadata(null, RelationOrigin.LLM_SUPPORTED, .8,
                    "Requirement-scoped relation", "", "These scoped contributions require this relation.", null, List.of(edge));
            diagramEdges.add(new DiagramEdge(spec.id(), source, target, spec.type(), 1.0, "requirement", metadata));
            included.add(Map.of("sourceCode", source, "targetCode", target, "relationType", spec.type(), "requirementEvidence", List.of(edge)));
            relationMetadata.put(source + "|" + target + "|" + spec.type(), Map.of("reviewStatus", "PROPOSED"));
            relationMappings.add(Map.of("snapshotId", SNAPSHOT, "sourceCode", source, "targetCode", target, "relationType", spec.type(), "reviewStatus", "PROPOSED"));
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("tag:taxonomy.sourceId", spec.id()); attributes.put("tag:taxonomy.mappingProfile", "sparx-xmi-2.1@3");
            attributes.put("tag:taxonomy.relationType", spec.type()); attributes.put("tag:taxonomy.relationDirection", "Source -> Destination");
            attributes.put("name", spec.type()); attributes.put("description", "Scoped relationship evidence is retained in its named tags.");
            attributes.put("tag:taxonomy.reviewStatus", "PROPOSED"); attributes.put("tag:taxonomy.status", "proposed");
            ArchiMateRelationProvenance.write(metadata).forEach((key, property) -> attributes.put("tag:" + key, property.value()));
            relations.add(new Relation(guid(spec.id()), SparxMappingProfile.eaRelation(spec.type(), "3"), guid(source), guid(target), attributes,
                    Map.of("canonicalType", spec.type(), "direction", "Source -> Destination", "stereotype", "Taxonomy::" + spec.type(), "taxonomy:status", "proposed")));
        }
        DiagramModel graph = new DiagramModel("Independent architecture", diagramNodes, diagramEdges, new DiagramLayout("LR", false));
        List<SourceAssessment> sources = nodes.values().stream().map(n -> new SourceAssessment(n, new ArrayList<>(contributions.get(n.id()).values()), "Scoped reading.", "")).toList();
        List<Map<String, String>> tasks = nodes.keySet().stream().map(id -> Map.of("sourceId", id, "targetRoot", "BP", "state", "COMPLETED")).toList();
        Map<String, Object> summary = new LinkedHashMap<>(Map.of("id", SNAPSHOT, "requirementKey", "FLOOD-001", "requirementId", 7,
                "requirementVersionId", 11, "projectId", 5, "status", "SUCCESS", "taxonomyFingerprint", "b".repeat(64), "workspaceId", "test-workspace", "branchName", "draft", "commitSha", "a".repeat(40)));
        Map<String, Object> search = Map.of("originalSha256", sha256(ORIGINAL), "sources", sources, "searchExhausted", true,
                "warnings", List.of(), "stopReason", "", "tasks", tasks, "progress", Map.of("totalSources", sources.size(), "assessedSources", sources.size(),
                        "totalSearches", tasks.size(), "completedSearches", tasks.size(), "pendingSearches", 0, "unresolvedSearches", 0),
                "result", Map.of("edges", evidence, "unfinished", List.of()));
        ObjectNode snapshot = JSON.valueToTree(Map.of("summary", summary, "analysis", Map.of("status", "SUCCESS", "warnings", List.of(),
                "relationSearchReport", search, "architectureView", Map.of("includedRelationships", included)),
                "elementMappings", elementMappings, "relationMappings", relationMappings));
        ObjectNode projection = JSON.valueToTree(Map.ofEntries(Map.entry("snapshotId", SNAPSHOT), Map.entry("snapshotStatus", "SUCCESS"),
                Map.entry("requirementKey", "FLOOD-001"), Map.entry("requirementText", ORIGINAL), Map.entry("requirementId", 7), Map.entry("projectId", 5),
                Map.entry("workspaceId", "test-workspace"), Map.entry("branchName", "draft"), Map.entry("commitSha", "a".repeat(40)),
                Map.entry("exportProvenance", Map.of("requirementVersionId", 11, "taxonomyFingerprint", "b".repeat(64))),
                Map.entry("diagram", graph), Map.entry("elements", elementMetadata), Map.entry("relations", relationMetadata)));
        Map<String, String> model = new LinkedHashMap<>(Map.of("identifier", guid("model"), "title", "Independent architecture",
                "taxonomy.requirementText", ORIGINAL, "taxonomy.requirementSha256", sha256(ORIGINAL), "taxonomy.snapshotId", SNAPSHOT,
                "taxonomy.requirementId", "7", "taxonomy.requirementVersionId", "11", "taxonomy.canonicalGraphSha256", graphSha256(graph),
                "taxonomy.taxonomyFingerprint", "b".repeat(64), "taxonomy.snapshotStatus", "SUCCESS"));
        model.put("taxonomy.projectId", "5"); model.put("taxonomy.workspaceId", "test-workspace"); model.put("taxonomy.branch", "draft"); model.put("taxonomy.authoritativeCommit", "a".repeat(40));
        ExchangeDocument document = new ExchangeDocument("sparx-xmi-2.1", "3", "handwritten", true, "", artifacts, relations, List.of(), model, List.of());
        return new Fixture(snapshot, projection, document, new SparxXmiCodec("3").write(document));
    }

    private static Map<String, Map<String, String>> declarations() {
        var values = new LinkedHashMap<String, Map<String, String>>();
        ScenarioArchitectureQualityTest.declaredObligations().forEach(e -> add(values, e.element(), e.clause(), e.text()));
        add(values, "IP-2130", "F1", "Represent each published flood warning with its affected area, source and observation time.");
        add(values, "BP-1535", "F1", "Retrieve and present the published warning information to the public.");
        add(values, "BP-1535", "F3", "Disseminate notifications for subscribed areas using the user's chosen channels.");
        add(values, "BP-1535", "A1", "Use the delivery services under test design assumption A1.");
        add(values, "BP-1017", "F1", "Acquire the published warning inputs with their source and observation time.");
        add(values, "BP-1521", "F3", "Maintain subscriptions to areas and retain user-selected channels.");
        add(values, "CR-1060", "F3", "Provide text notifications for the subscriber's selected text channel.");
        add(values, "CR-1022", "F4", "Read and update contact, area subscription and account records for the entitled user.");
        add(values, "CR-1006", "F4", "Authenticate entitled users before protected contact and account actions.");
        add(values, "BP-1140", "A1", "Protect subscriber details and limit protected account actions to entitled identities.");
        add(values, "BP-1536", "A1", "Authorize entitled identities for protected subscriber account actions.");
        add(values, "CI-1060", "F2", "Support administrative oversight and operational monitoring of this information service.");
        return values;
    }

    private static void add(Map<String, Map<String, String>> values, String node, String clause, String text) {
        values.computeIfAbsent(node, ignored -> new LinkedHashMap<>()).merge(clause, text, (a, b) -> a + " " + b);
    }
    private static String mutate(String mutation, String id, String clause, String text) {
        if (mutation.equals("global-text-only")) return ORIGINAL;
        if (mutation.startsWith("open-identity") && id.equals("BP-1017") && clause.equals("F5"))
            return text + " The incoming observation batch has an unspecified product identity; it is not assumed to be an assembled Recognized Environmental Picture."
                    + (mutation.equals("open-identity-conflict") ? " The incoming observation batch is an already recognized environmental picture." : "");
        if (mutation.equals("queue-label-only") && id.equals("CR-1097")) return "Message Queueing Service queues delivery work.";
        if (mutation.equals("no-account-deletion") && id.equals("BP-1530")) return "Remove subscribed areas and stop treating them as active; suspend the account.";
        if (mutation.equals("no-source-time") && clause.equals("F1")) return text.replace("source and observation time", "content").replace("observation's source and observation time", "observation's content");
        if (mutation.equals("no-stale")) return text.replace("Show stale observations as stale, preserving their source and observation time.", "Show observations with their source and observation time.");
        if (mutation.equals("no-unavailable") && id.equals("BP-2009")) return "Display the observations.";
        if (mutation.equals("unscoped-assumption")) return text.replace("under test design assumption A1", "as a capability");
        return text;
    }

    record Spec(String id, String source, String type, String target, String sourceClause, String targetClause,
                Necessity necessity, String condition) {
        Spec(String id, String source, String type, String target, String sourceClause, String targetClause) {
            this(id, source, type, target, sourceClause, targetClause, Necessity.REQUIRED, "");
        }
    }
    record Fixture(ObjectNode snapshot, ObjectNode projection, ExchangeDocument document, byte[] xmi) { }
    static Map<String, Object> verify(Fixture value) { return ScenarioArchitectureQuality.verify(value.snapshot(), value.projection(), value.document(), value.xmi()); }
    private static void assertFailure(Fixture value, String part) {
        AssertionError failure = assertThrows(AssertionError.class, () -> verify(value));
        assertTrue(failure.getMessage().contains(part), failure::getMessage);
    }
    private static Fixture replace(Fixture value, Artifact before, Artifact after) {
        ExchangeDocument changed = document(value.document(), value.document().artifacts().stream().map(a -> a == before ? after : a).toList(), value.document().relations(), value.document().metadata());
        return new Fixture(value.snapshot(), value.projection(), changed, new SparxXmiCodec("3").write(changed));
    }
    private static ExchangeDocument withMetadata(ExchangeDocument before, Map<String, String> metadata) { return document(before, before.artifacts(), before.relations(), metadata); }
    private static ExchangeDocument document(ExchangeDocument before, List<Artifact> artifacts, List<Relation> relations, Map<String, String> metadata) {
        return new ExchangeDocument(before.profile(), before.profileVersion(), before.externalVersion(), before.completeScope(), before.source(), artifacts, relations, before.placements(), metadata, before.losses());
    }
    private static String quote(String clause) { return REFERENCE.path("requirement").path("clauses").path(clause).asText(); }
    private static String guid(String id) { return SparxMappingProfile.guid(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString()); }
    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static String graphSha256(DiagramModel graph) {
        try {
            var method = com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.class.getDeclaredMethod("fingerprint", DiagramModel.class);
            method.setAccessible(true); return (String) method.invoke(null, graph);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static JsonNode reference() {
        try (var input = ScenarioArchitectureQualityEvidenceTest.class.getResourceAsStream("/scenarios/flood-quality-reference.json")) { return JSON.readTree(input); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
