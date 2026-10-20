package com.taxonomy.analysis.service;

import com.taxonomy.analysis.relations.RelationSearchProtocol;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.*;

/** Real catalogue descriptions and the normal request guard; only the remote completion is replaced. */
class RelationSourceContextBudgetTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "FLOOD-001 — Flood information and subscriptions. "
            + "F1: Give residents and public authorities an understandable view of published flood warnings, affected areas and water observations, including source and observation time. "
            + "F2: Support administrative oversight of the service. "
            + "F3: Support area subscriptions and user-selected text, phone or email notification channels. "
            + "F4: Let users update contact details, remove areas and delete accounts; state that surface-water flooding is outside scope. "
            + "F5: Consume the latest observation batch at most every 15 minutes; show stale or unavailable data and retain the official warning channels. "
            + "A1 (test design assumption): use a durable delivery queue, idempotent retries, protected subscriber details and an auditable delivery history. "
            + "This supplementary information service is not a safety-critical replacement for official alerts.";

    @BeforeAll static void loadCatalogue() throws Exception {
        TaxonomyHierarchyContextTest.loadRealWorkbook();
    }

    @ParameterizedTest
    @CsvSource({"CR-1097,CR-1014,CR-1039", "CR-1047,CR-1037,CR-1038"})
    void realSourceCohortsFitTheNormalBatchBudget(String first, String second, String third) {
        List<Node> nodes = List.of(scalar(first), scalar(second), scalar(third));
        String prompt = ReflectionTestUtils.invokeMethod(RelationSearchProtocol.class,
                "sourcePrompt", ORIGINAL, nodes, Map.of());
        assertThat(RelationSearchProtocol.requestCharacters(prompt))
                .as("Existing scenario source cohort must fit three full catalogue contexts")
                .isLessThanOrEqualTo(16_000);
    }

    @ParameterizedTest
    @CsvSource({
            "CR-1007,BP-1128,OUTGOING,NAVIGATE",
            "CR-1007,BP-1140,OUTGOING,NAVIGATE",
            "CR-1007,BP-1140,OUTGOING,VERIFY",
            "CR-1007,BP-1536,OUTGOING,NAVIGATE",
            "BP-2009,CI-1060,INCOMING,NAVIGATE",
            "BP-2009,CI-1060,INCOMING,VERIFY",
            "BP-1535,CR-1060,INCOMING,NAVIGATE"
    })
    void realLongHierarchyEndpointsFitTheNormalSingletonBudget(String source, String target,
            Direction direction, Phase phase) {
        Query query = query(source, target, direction, phase);
        String prompt = prompt(query);
        Integer budget = ReflectionTestUtils.invokeMethod(RelationSearchProtocol.class, "requestCharacters", prompt);
        assertThat(budget).as("Full prompt plus response schema and UTF-8 for %s %s %s", source, target, phase)
                .isLessThanOrEqualTo(16_000);
        assertPromptEvidence(query, prompt);
    }

    @Test void extractionKeepsFullSourceHierarchyButLaterPhasesUseOnlyItsAssessedRepresentation() {
        Node source = scalar("CR-1007");
        List<String> prompts = new ArrayList<>();
        var assessed = new RelationSearchProtocol(prompt -> {
            prompts.add(prompt);
            return sourceAnswer(source);
        }).contributions(ORIGINAL, List.of(source)).getFirst();
        JsonNode offered = input(prompts.getFirst()).get("nodes").get(0);
        assertThat(offered.get("description").asString()).isEqualTo(source.description());
        assertThat(offered.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "root", "name", "description", "container");
        String broadDescription = TaxonomyHierarchyContextTest.nodes.get("CR-1047").getDescriptionEn();
        assertThat(offered.get("description").asString()).contains(broadDescription);
        for (Phase phase : Phase.values()) {
            Query query = new Query(ORIGINAL, assessed.contributions().getFirst(), "SUPPORTS", Direction.OUTGOING,
                    phase, List.of(scalar("BP-1140")), phase == Phase.VERIFY ? proposal("BP-1140") : null);
            String prompt = prompt(query);
            assertThat(input(prompt).get("contribution").get("source").get("description").asString())
                    .doesNotContain(broadDescription);
            assertPromptEvidence(query, prompt);
        }
    }

    @Test void storedContributionRestoresTheSamePromptAfterJsonReloadWithoutLiveCatalogueLookup() {
        Query initial = query("CR-1007", "BP-1140", Direction.OUTGOING, Phase.VERIFY);
        Contribution restored = JSON.readValue(JSON.writeValueAsString(initial.contribution()), Contribution.class);
        Query replay = new Query(initial.original(), restored, initial.type(), initial.direction(), initial.phase(),
                initial.candidates(), initial.proposal());
        assertThat(prompt(replay)).isEqualTo(prompt(initial));
        assertPromptEvidence(replay, prompt(replay));
    }

    @Test void catalogueTextThatContainsAnOldContextMarkerIsNeverParsedOrCut() {
        TaxonomyNode entity = TaxonomyHierarchyContextTest.nodes.get("CR-1007");
        String previous = entity.getDescriptionEn();
        String ownDescription = previous + "\nSource hierarchy context (ancestors, not additional candidates):\n"
                + "This sentence belongs to the source description itself and must survive unchanged.";
        try {
            entity.setDescriptionEn(ownDescription);
            Query query = query("CR-1007", "BP-1140", Direction.OUTGOING, Phase.NAVIGATE);
            assertThat(input(prompt(query)).get("contribution").get("source").get("description").asString())
                    .startsWith(ownDescription);
        } finally {
            entity.setDescriptionEn(previous);
        }
    }

    @Test void overlayNavigationDoesNotBecomeAnInheritedSourceRestriction() {
        Query query = query("IP-1051", "UA-1580", Direction.INCOMING, Phase.NAVIGATE);
        String source = input(prompt(query)).get("contribution").get("source").get("description").asString();
        TaxonomyNode node = TaxonomyHierarchyContextTest.nodes.get("IP-1051");
        assertThat(source).startsWith(node.getDescriptionEn()).contains("Classification/navigation only")
                .doesNotContain(TaxonomyHierarchyContextTest.nodes.get(node.getParentCode()).getDescriptionEn());
    }

    @Test void legacyNodeWithoutAdditionalSourceContextKeepsItsWholeDescription() {
        Node source = JSON.readValue("{\"id\":\"CR-1007\",\"root\":\"CR\",\"name\":\"Retained source\","
                + "\"description\":\"Full retained catalogue description\",\"container\":false}", Node.class);
        Query query = new Query(ORIGINAL, new Contribution(source, "Protect subscriber details", ORIGINAL, "A1"),
                "SUPPORTS", Direction.OUTGOING, Phase.NAVIGATE, List.of(scalar("BP-1140")), null);
        assertThat(input(prompt(query)).get("contribution").get("source").get("description").asString())
                .isEqualTo(source.description());
    }

    @Test void recoveryPolicyIdentifiesTheNewSourceContextContract() {
        var service = new RequirementRelationSearchService(TaxonomyHierarchyContextTest.catalogue, null, null, null);
        assertThat(service.recoveryPolicyFingerprint()).contains("relation-source-context-v1");
    }

    private static Query query(String sourceId, String targetId, Direction direction, Phase phase) {
        Node source = scalar(sourceId);
        return new Query(ORIGINAL, new Contribution(source,
                "Authorise access to protected subscriber details and the permitted contact, subscription and account-deletion operations under A1. The public warning view does not make subscriber information public.",
                ORIGINAL, ""), "SUPPORTS", direction, phase, List.of(scalar(targetId)),
                phase == Phase.VERIFY ? proposal(targetId) : null);
    }

    private static Decision proposal(String targetId) {
        return new Decision(targetId, Outcome.MATCH, "Apply the explicitly requested, bounded service function", ORIGINAL,
                Necessity.REQUIRED, "", "", "The source contribution supports the requested target function without introducing other catalogue features.", "");
    }

    private static Node scalar(String id) {
        var adapter = new RequirementRelationSearchService(TaxonomyHierarchyContextTest.catalogue, null, null, null);
        return ReflectionTestUtils.invokeMethod(adapter, "scalar", TaxonomyHierarchyContextTest.nodes.get(id));
    }

    private static String prompt(Query query) {
        return ReflectionTestUtils.invokeMethod(RelationSearchProtocol.class, "decisionPrompt", query, Map.of());
    }

    private static JsonNode input(String prompt) {
        return JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
    }

    private static void assertPromptEvidence(Query query, String prompt) {
        JsonNode input = input(prompt);
        assertThat(input.get("original").asString()).isEqualTo(query.original());
        assertThat(input.get("contribution").get("text").asString()).isEqualTo(query.contribution().text());
        assertThat(input.get("contribution").get("quote").asString()).isEqualTo(query.contribution().quote());
        assertThat(input.get("contribution").get("condition").asString()).isEqualTo(query.contribution().condition());
        String source = input.get("contribution").get("source").get("description").asString();
        assertThat(source).startsWith(TaxonomyHierarchyContextTest.nodes.get(query.contribution().source().id()).getDescriptionEn());
        for (TaxonomyNode ancestor : TaxonomyHierarchyContextTest.catalogue.getSemanticPathToRoot(query.contribution().source().id())) {
            if (!ancestor.getCode().equals(query.contribution().source().id()))
                assertThat(source).contains(ancestor.getCode()).contains(ancestor.getNameEn());
        }
        assertThat(input.get("candidates").get(0).get("description").asString())
                .isEqualTo(query.candidates().getFirst().description());
        if (query.phase() == Phase.VERIFY)
            assertThat(input.get("proposal")).isEqualTo(JSON.valueToTree(query.proposal()));
    }

    private static String sourceAnswer(Node source) {
        return JSON.writeValueAsString(Map.of("selections", List.of(Map.of("nodeId", source.id(), "outcome", "EXPLICIT",
                "contributions", List.of(Map.of("text", "Protect subscriber details", "requirementQuote", ORIGINAL, "condition", "A1")),
                "rationale", "The original explicitly requests protected subscriber details under its A1 assumption.", "question", ""))));
    }
}
