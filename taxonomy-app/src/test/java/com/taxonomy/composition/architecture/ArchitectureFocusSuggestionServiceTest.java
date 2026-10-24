package com.taxonomy.composition.architecture;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetHealth;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetMode;
import com.taxonomy.analysis.dto.AiTargetDtos.PromptBudget;
import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.LlmCompletionOptions;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchitectureFocusSuggestionServiceTest {
    private final RepositoryContext scope = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
    private final Context context = Context.of(scope, "ab".repeat(20), 7);
    private final ArchitectureEditorService editor = mock(ArchitectureEditorService.class);
    private final LlmService llm = mock(LlmService.class);
    private final AiPromptBudgetPolicy budget = mock(AiPromptBudgetPolicy.class);
    private final ArchitectureFocusSuggestionService service = new ArchitectureFocusSuggestionService(editor, llm, budget);
    private static final String DSL = """
            element arch-service type CoreService {
              title: "Order processing service";
              description: "Processes and validates customer orders";
            }
            element arch-app type UserApplication {
              title: "Order processing application";
              description: "Submits customer orders for validation";
            }
            element arch-product type InformationProduct {
              title: "Order information";
              description: "Customer order records";
            }
            """;

    @BeforeEach void document() throws Exception {
        when(editor.read(scope, null, null)).thenReturn(doc(DSL));
    }
    private ArchitectureEditorService.Document doc(String dsl) {
        return new ArchitectureEditorService.Document(context, dsl, "READY", List.of(), List.of(), 0, null, "WORKSPACE_REVISION");
    }
    private ArchitectureFocusSuggestionService.Request request(boolean ai) {
        return new ArchitectureFocusSuggestionService.Request(context, "arch-service", 8, ai, "en");
    }

    @Test void localCandidatesUseCustomUncheckpointedElementsWithoutProviderAccess() throws Exception {
        var response = service.investigate(scope, request(false));
        assertThat(response.outcome()).isEqualTo("LOCAL_CANDIDATES");
        assertThat(response.method()).isEqualTo("LOCAL_TEXT_UNASSESSED");
        assertThat(response.modelCalls()).isZero();
        assertThat(response.context()).isEqualTo(context);
        assertThat(response.sourceHash()).matches("[a-f0-9]{64}");
        assertThat(response.suggestions()).anySatisfy(suggestion -> {
            assertThat(suggestion.sourceId()).isEqualTo("arch-app");
            assertThat(suggestion.targetId()).isEqualTo("arch-service");
            assertThat(suggestion.relationType()).isEqualTo("USES");
            assertThat(suggestion.properties()).containsEntry("x-focus-revision", "7")
                    .containsEntry("x-focus-method", "LOCAL_TEXT_UNASSESSED");
            assertThat(suggestion.evidence()).allSatisfy(evidence -> assertThat(DSL).contains(evidence.quote()));
        });
        verifyNoInteractions(llm, budget);
    }

    @Test void staleRevisionAndForeignScopeFailBeforeProviderAccess() {
        var stale = new ArchitectureFocusSuggestionService.Request(Context.of(scope, context.commit(), 6), "arch-service", 8, true, "en");
        assertThatThrownBy(() -> service.investigate(scope, stale)).hasMessageContaining("revision");
        var foreign = new ArchitectureFocusSuggestionService.Request(Context.of(RepositoryContext.workspace("repo", "other", "draft", "alice"), context.commit(), 7), "arch-service", 8, true, "en");
        assertThatThrownBy(() -> service.investigate(scope, foreign)).hasMessageContaining("context");
        verifyNoInteractions(llm, budget);
    }

    @Test void excludesExistingIncompatibleContainmentAndSelfTriples() throws Exception {
        when(editor.read(scope, null, null)).thenReturn(doc(DSL + "relation arch-app USES arch-service {\n  status: accepted;\n}\n"));
        var response = service.investigate(scope, request(false));
        assertThat(response.suggestions()).noneSatisfy(s -> {
            assertThat(s.sourceId()).isEqualTo("arch-app");
            assertThat(s.targetId()).isEqualTo("arch-service");
            assertThat(s.relationType()).isEqualTo("USES");
        }).allSatisfy(s -> {
            assertThat(s.sourceId()).isNotEqualTo(s.targetId());
            assertThat(s.relationType()).isNotEqualTo("CONTAINS");
            assertThat(s.sourceId() + " " + s.relationType() + " " + s.targetId())
                    .isNotEqualTo("arch-service USES arch-app");
        });
    }

    @Test void unsupportedGenerationRetainsHonestUnassessedLocalCandidates() throws Exception {
        when(llm.supportsGenerativeCompletion()).thenReturn(false);
        var response = service.investigate(scope, request(true));
        assertThat(response.outcome()).isEqualTo("UNASSESSED");
        assertThat(response.modelCalls()).isZero();
        assertThat(response.suggestions()).isNotEmpty();
        verify(llm, never()).callLlmRaw(anyString(), any());
    }

    @Test void validatesOfferedTriplesAndExactQuotesAndPersistsFullProvenance() throws Exception {
        generative(validReply("Processes and validates customer orders"));
        var response = service.investigate(scope, request(true));
        assertThat(response.outcome()).isEqualTo("ASSESSED");
        assertThat(response.modelCalls()).isEqualTo(1);
        assertThat(response.method()).isEqualTo("AI:test-provider");
        assertThat(response.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.rationale()).isEqualTo("The application sends orders to the validation service.");
            assertThat(s.properties()).containsEntry("x-focus-rationale", s.rationale())
                    .containsEntry("x-focus-source-hash", response.sourceHash());
            assertThat(s.properties().get("x-focus-evidence")).contains("arch-service", "description");
        });
        verify(budget).requireWithinBudget(anyString(), eq("TEST_PROVIDER"));
        verify(llm, times(1)).callLlmRaw(anyString(), any(LlmCompletionOptions.class));
    }

    @Test void malformedModelAndFabricatedQuotesAreFailuresNotAssessedNegatives() throws Exception {
        generative("not json");
        assertThat(service.investigate(scope, request(true)).outcome()).isEqualTo("FAILED");
        when(llm.callLlmRaw(anyString(), any())).thenReturn(validReply("Invented deployed connection"));
        var unsupported = service.investigate(scope, request(true));
        assertThat(unsupported.outcome()).isEqualTo("FAILED");
        assertThat(unsupported.method()).startsWith("LOCAL_TEXT_UNASSESSED");
        assertThat(unsupported.suggestions()).isNotEmpty();
    }

    @Test void emptySuccessfulAssessmentDiffersFromProviderFailure() throws Exception {
        generative("{\"suggestions\":[]}");
        assertThat(service.investigate(scope, request(true)).outcome()).isEqualTo("ASSESSED");
        when(llm.callLlmRaw(anyString(), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThat(service.investigate(scope, request(true)).outcome()).isEqualTo("FAILED");
    }

    @Test void unknownSourceAndInvalidBoundsNeverSpendModelCalls() {
        assertThatThrownBy(() -> service.investigate(scope,
                new ArchitectureFocusSuggestionService.Request(context, "guessed", 8, true, "en"))).hasMessageContaining("element");
        assertThatThrownBy(() -> new ArchitectureFocusSuggestionService.Request(context, "arch-service", 25, true, "en"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchitectureFocusSuggestionService.Request(context, "arch-service", 8, true, "xx"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(llm, budget);
    }

    @Test void providerCannotIntroduceAnUnofferedTripleOrDuplicateEvidenceKeys() throws Exception {
        generative(validReply("Processes and validates customer orders").replace("arch-app", "guessed-id"));
        assertThat(service.investigate(scope, request(true)).outcome()).isEqualTo("FAILED");
        when(llm.callLlmRaw(anyString(), any())).thenReturn("{\"suggestions\":[],\"suggestions\":[]}");
        assertThat(service.investigate(scope, request(true)).outcome()).isEqualTo("FAILED");
    }

    @Test void promptIncludesOnlyActualMappedRequirementContextAndEscapesSourceAsData() throws Exception {
        when(editor.read(scope, null, null)).thenReturn(doc(DSL + """
                requirement req-order {
                  title: "Order requirement";
                  text: "Orders shall be validated before fulfillment";
                }
                mapping req-order -> arch-service {
                  source: analysis;
                }
                requirement req-unrelated {
                  title: "Unrelated";
                  text: "Do not disclose unrelated context";
                }
                """));
        generative("{\"suggestions\":[]}");
        service.investigate(scope, request(true));
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).callLlmRaw(prompt.capture(), any());
        assertThat(prompt.getValue()).contains("Orders shall be validated before fulfillment")
                .doesNotContain("Do not disclose unrelated context");
    }

    @Test void candidateLimitBoundsAiOfferAndResultAndHashChangesWithDurableSource() throws Exception {
        var bounded = new ArchitectureFocusSuggestionService.Request(context, "arch-service", 1, false, "de");
        var first = service.investigate(scope, bounded);
        assertThat(first.candidateCount()).isEqualTo(1);
        assertThat(first.suggestions()).hasSize(1);
        when(editor.read(scope, null, null)).thenReturn(doc(DSL.replace("Order information", "Revised order information")));
        assertThat(service.investigate(scope, bounded).sourceHash()).isNotEqualTo(first.sourceHash());
    }

    @Test void allScopePinsAndCheckpointIdentityMustMatchBeforeGeneration() throws Exception {
        var variants = List.of(
                new Context("other", context.workspaceScopeKey(), context.branch(), context.commit(), 7, "alice", "PRIVATE_WORKSPACE"),
                new Context("repo", context.workspaceScopeKey(), "main", context.commit(), 7, "alice", "PRIVATE_WORKSPACE"),
                new Context("repo", context.workspaceScopeKey(), "draft", context.commit(), 7, "bob", "PRIVATE_WORKSPACE"),
                new Context("repo", context.workspaceScopeKey(), "draft", context.commit(), 7, "alice", "READ_ONLY"),
                new Context("repo", context.workspaceScopeKey(), "draft", "cd".repeat(20), 7, "alice", "PRIVATE_WORKSPACE"));
        for (var changed : variants) assertThatThrownBy(() -> service.investigate(scope,
                new ArchitectureFocusSuggestionService.Request(changed, "arch-service", 8, true, "en")))
                .isInstanceOf(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class);
        when(editor.read(scope, null, null)).thenReturn(new ArchitectureEditorService.Document(context, DSL,
                "VERSION_CHANGED", List.of(), List.of(), 0, null, "WORKSPACE_REVISION"));
        assertThatThrownBy(() -> service.investigate(scope, request(true))).hasMessageContaining("not ready");
        verifyNoInteractions(llm, budget);
    }

    @Test void emptyArchitectureNeighborhoodDoesNotCallAnyProvider() throws Exception {
        when(editor.read(scope, null, null)).thenReturn(doc("element arch-service type CoreService {\n  title: \"Orders\";\n}\n"));
        var response = service.investigate(scope, request(true));
        assertThat(response.outcome()).isEqualTo("NO_CANDIDATES");
        assertThat(response.candidateCount()).isZero();
        assertThat(response.suggestions()).isEmpty();
        verifyNoInteractions(llm, budget);
    }

    @Test void promptAdmissionFailureDoesNotSpendACompletionOrBecomeAnAssessedNegative() throws Exception {
        generative("{\"suggestions\":[]}");
        when(budget.requireWithinBudget(anyString(), eq("TEST_PROVIDER"))).thenThrow(new IllegalArgumentException("too large"));
        var response = service.investigate(scope, request(true));
        assertThat(response.outcome()).isEqualTo("UNASSESSED");
        assertThat(response.modelCalls()).isZero();
        assertThat(response.suggestions()).isNotEmpty();
        verify(llm, never()).callLlmRaw(anyString(), any());
    }

    @Test void oversizedOutputAndControlBearingRationaleFailWithoutLosingLocalCandidates() throws Exception {
        generative("x".repeat(65_537));
        assertThat(service.investigate(scope, request(true)).outcome()).isEqualTo("FAILED");
        when(llm.callLlmRaw(anyString(), any())).thenReturn(validReply("Processes and validates customer orders")
                .replace("The application sends orders", "The application\\n sends orders"));
        assertThat(service.investigate(scope, request(true)).outcome()).isEqualTo("FAILED");
    }

    @Test void saturatedTopRankedPeerDoesNotHideAvailableCandidatesElsewhereInArchitecture() throws Exception {
        when(editor.read(scope, null, null)).thenReturn(doc(DSL + """
                relation arch-app USES arch-service {
                  status: accepted;
                }
                relation arch-app RELATED_TO arch-service {
                  status: accepted;
                }
                relation arch-service RELATED_TO arch-app {
                  status: accepted;
                }
                """));
        var response = service.investigate(scope,
                new ArchitectureFocusSuggestionService.Request(context, "arch-service", 1, false, "en"));
        assertThat(response.suggestions()).hasSize(1);
        assertThat(response.suggestions().getFirst().sourceId() + " " + response.suggestions().getFirst().targetId())
                .contains("arch-product");
    }

    @Test void actualSourceControlCharactersAreEscapedInPersistableEvidenceWithoutChangingTheQuote() throws Exception {
        String actual = "Processes " + (char) 0x85 + " and validates customer orders";
        when(editor.read(scope, null, null)).thenReturn(doc(DSL.replace("Processes and validates customer orders", actual)));
        var response = service.investigate(scope, request(false));
        assertThat(response.suggestions()).isNotEmpty();
        assertThat(response.suggestions()).allSatisfy(s -> assertThat(s.properties().values())
                .allSatisfy(v -> assertThat(v.chars().anyMatch(Character::isISOControl)).isFalse()));
        assertThat(response.suggestions()).anySatisfy(s -> assertThat(s.evidence())
                .anySatisfy(e -> assertThat(e.quote()).isEqualTo(actual)));
    }

    @Test void mockProviderStaysExplicitlyUnassessedWithoutPretendingToSpendAModelCall() throws Exception {
        when(llm.isMockMode()).thenReturn(true);
        var response = service.investigate(scope, request(true));
        assertThat(response.outcome()).isEqualTo("UNASSESSED");
        assertThat(response.method()).isEqualTo("LOCAL_TEXT_UNASSESSED:MOCK_PROVIDER");
        assertThat(response.modelCalls()).isZero();
        assertThat(response.suggestions()).isNotEmpty();
        verify(llm, never()).callLlmRaw(anyString(), any());
    }

    @Test void unavailableAdmittedProviderReturnsUnassessedWithoutAttemptingCompletion() throws Exception {
        generative(null);
        when(budget.requireWithinBudget(anyString(), eq("TEST_PROVIDER"))).thenReturn(target(false));
        var response = service.investigate(scope, request(true));
        assertThat(response.outcome()).isEqualTo("UNASSESSED");
        assertThat(response.method()).isEqualTo("LOCAL_TEXT_UNASSESSED:AI_UNAVAILABLE");
        assertThat(response.modelCalls()).isZero();
        assertThat(response.suggestions()).isNotEmpty();
        verify(llm, never()).callLlmRaw(anyString(), any());
    }

    @Test void availableProviderEmptyResponseStillCountsTheAttemptAndFails() throws Exception {
        generative(null);
        var response = service.investigate(scope, request(true));
        assertThat(response.outcome()).isEqualTo("FAILED");
        assertThat(response.modelCalls()).isEqualTo(1);
        verify(llm, times(1)).callLlmRaw(anyString(), any());
    }

    @Test void collidingMappedRequirementIdsCannotOverwriteEitherArchitectureEndpointTitle() throws Exception {
        when(editor.read(scope, null, null)).thenReturn(doc(DSL + collidingRequirements()));
        generative("{\"suggestions\":[]}");
        service.investigate(scope, request(true));
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).callLlmRaw(prompt.capture(), any());
        assertThat(prompt.getValue()).contains("Order processing service", "Order processing application")
                .doesNotContain("Colliding requirement service title", "Colliding requirement application title",
                        "Requirement-only service claim", "Requirement-only application claim");
    }

    @Test void collidingRequirementTextCannotSatisfyEitherEndpointEvidence() throws Exception {
        when(editor.read(scope, null, null)).thenReturn(doc(DSL + collidingRequirements()));
        generative("{\"suggestions\":[]}");
        String original = validReply("Processes and validates customer orders");
        for (String reply : List.of(
                original.replace("\"field\":\"description\",\"quote\":\"Processes and validates customer orders\"",
                        "\"field\":\"text\",\"quote\":\"Requirement-only service claim\""),
                original.replace("\"field\":\"description\",\"quote\":\"Submits customer orders for validation\"",
                        "\"field\":\"text\",\"quote\":\"Requirement-only application claim\""))) {
            when(llm.callLlmRaw(anyString(), any())).thenReturn(reply);
            var response = service.investigate(scope, request(true));
            assertThat(response.outcome()).isEqualTo("FAILED");
            assertThat(response.modelCalls()).isEqualTo(1);
            assertThat(response.suggestions()).allSatisfy(s -> assertThat(s.evidence())
                    .noneSatisfy(e -> assertThat(e.field()).isEqualTo("text")));
        }
    }

    private static String collidingRequirements() {
        return """
                requirement arch-service {
                  title: "Colliding requirement service title";
                  text: "Requirement-only service claim";
                }
                mapping arch-service -> arch-service {
                  source: analysis;
                }
                requirement arch-app {
                  title: "Colliding requirement application title";
                  text: "Requirement-only application claim";
                }
                mapping arch-app -> arch-service {
                  source: analysis;
                }
                """;
    }

    private static AiTargetDescriptor target(boolean available) {
        return new AiTargetDescriptor("test-target", "Test provider", "TEST_PROVIDER", "test-model", AiTargetMode.REMOTE,
                available ? AiTargetHealth.READY : AiTargetHealth.UNAVAILABLE, available, false, false,
                new PromptBudget(120_000, 120_000, 30_000), "test-configuration", available ? null : "Missing credentials");
    }

    private void generative(String reply) {
        when(llm.supportsGenerativeCompletion()).thenReturn(true);
        when(llm.getActiveProviderName()).thenReturn("test-provider");
        when(llm.getActiveProviderId()).thenReturn(new ProviderId("TEST_PROVIDER"));
        when(budget.requireWithinBudget(anyString(), eq("TEST_PROVIDER"))).thenReturn(target(true));
        when(llm.callLlmRaw(anyString(), any())).thenReturn(reply);
    }
    private static String validReply(String quote) {
        return """
                {"suggestions":[{"sourceId":"arch-app","targetId":"arch-service","relationType":"USES",
                "rationale":"The application sends orders to the validation service.","evidence":[
                {"elementId":"arch-app","field":"description","quote":"Submits customer orders for validation"},
                {"elementId":"arch-service","field":"description","quote":"%s"}]}]}
                """.formatted(quote);
    }
}
