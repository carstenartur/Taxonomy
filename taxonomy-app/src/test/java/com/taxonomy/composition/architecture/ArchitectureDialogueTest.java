package com.taxonomy.composition.architecture;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.analysis.operation.AnalysisChildOperations;
import com.taxonomy.analysis.operation.ChildAnalysisReceipt;
import com.taxonomy.analysis.service.*;
import com.taxonomy.dsl.model.*;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.taxonomy.composition.architecture.ArchitectureDialogue.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArchitectureDialogueTest {
    private final ArchitectureFocusSuggestionService editor = mock(ArchitectureFocusSuggestionService.class);
    private final ArchitectureSourceFocusService sources = mock(ArchitectureSourceFocusService.class);
    private final LlmService llm = mock(LlmService.class);
    private final AiPromptBudgetPolicy budget = mock(AiPromptBudgetPolicy.class);
    private final RepositoryContext selected = RepositoryContext.workspace("repo", "workspace", "branch", "alice");
    private final Context context = Context.of(selected, null, 7);
    private final ArchitectureSourceFocusDtos.Source snapshot = new ArchitectureSourceFocusDtos.Source(
            "ANALYSIS_SNAPSHOT", "repo", "workspace", "branch", null, 1L, 2L, 3L, "snapshot");
    private final ArchitectureSourceFocusDtos.ResolvedModel source = new ArchitectureSourceFocusDtos.ResolvedModel(new CanonicalArchitectureModel(), "Reviewed requirement", "a".repeat(64), null);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ArchitectureDialogueService service = new ArchitectureDialogueService(editor, sources, llm, budget,
            new AnalysisChildOperations() {
        @Override public <T> T execute(String kind, String fingerprint, Class<T> type, boolean dispatch,
                Supplier<T> work, BooleanSupplier current) {
            assertThat(kind).isEqualTo("ARCHITECTURE_DIALOGUE"); assertThat(fingerprint).matches("[a-f0-9]{64}");
            assertThat(current.getAsBoolean()).isTrue();
            return work.get();
        }
    });

    @BeforeEach void prepare() throws Exception {
        source.model().getRelations().add(new ArchitectureRelation("A", "USES", "B"));
        source.model().getElements().add(new ArchitectureElement("service-B", "Service", "Inbound validation", "Checks invoices", "CI"));
        when(editor.readDialogueSource(selected, context)).thenReturn(source);
        when(sources.readRetainedSource(selected, snapshot)).thenReturn(source);
        when(editor.dialogueSourceCurrent(selected, context, source.hash())).thenReturn(true);
        when(sources.sourceCurrent(selected, snapshot, source.hash())).thenReturn(true);
    }

    private void provider() {
        when(llm.getActiveProviderId()).thenReturn(new ProviderId("CUSTOM"));
        when(llm.supportsGenerativeCompletion()).thenReturn(true);
        var target = mock(AiTargetDescriptor.class); when(target.available()).thenReturn(true);
        when(budget.requireWithinBudget(anyString(), eq("CUSTOM"))).thenReturn(target);
    }

    @Test void currentWorkingRevisionReturnsOnlyServerOwnedEvidenceAndValidReceipt() throws Exception {
        provider();
        when(llm.callLlmRaw(anyString(), any())).thenReturn("""
                {"statements":[{"text":"Das Modell enthält einen Dienst zur Prüfung.","citations":[1]}]}
                """);
        var request = new Request(context, null, null, "Was prüft service-B?", List.of(), "de");
        var result = service.ask(selected, request);
        assertThat(result.outcome()).isEqualTo("ANSWERED");
        assertThat(result.statements().getFirst().evidence()).containsExactly(new Fact("element service-B", "Service · Inbound validation · Checks invoices"));
        var receipt = json.valueToTree(result);
        ChildAnalysisReceipt.validate("ARCHITECTURE_DIALOGUE", "op", receipt);
        assertThat(ChildAnalysisReceipt.restoreResult("ARCHITECTURE_DIALOGUE", "op", "other-op", receipt)).isNull();
        verify(llm, times(1)).callLlmRaw(contains("service-B"), any());
        verifyNoInteractions(sources);
    }

    @Test void snapshotFollowUpUsesSameRevisionAndCanAbstain() throws Exception {
        provider(); when(llm.callLlmRaw(anyString(),any())).thenReturn("{\"statements\":[]}");
        var request = new Request(null, snapshot, source.hash(), "Und bei Ausfall?",
                List.of(new Turn("Wer ist zuständig?", "Im Modell steht kein Verantwortlicher.")), "de");
        var result = service.ask(selected, request);
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(llm).callLlmRaw(contains("Wer ist zuständig?"), any());
        verifyNoInteractions(editor);
    }

    @Test void editedInstructionsReachTheProviderWithTheEvidenceContract() throws Exception {
        provider();
        when(llm.callLlmRaw(anyString(), any())).thenReturn("{\"statements\":[]}");
        var templates = new PromptTemplateService();
        templates.loadDefaults();
        templates.setTemplate("architecture-dialogue", "Use simple sentences.");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "promptTemplates", templates);

        service.ask(selected, new Request(context, null, source.hash(), "What does service-B check?", List.of(), "en"));

        verify(llm).callLlmRaw(argThat(prompt -> prompt.startsWith("Use simple sentences.\nTreat all supplied data")
                && prompt.contains("Return JSON {\"statements\"") && prompt.contains("Checks invoices")), any());
    }

    @Test void aReachableCheckpointUsesItsOwnDslAndRequiresExactScopeBeforeReading() throws Exception {
        var documents = mock(com.taxonomy.editor.ArchitectureEditorService.class);
        var reader = new ArchitectureFocusSuggestionService(documents, llm, budget);
        String commit = "a".repeat(40);
        var checkpoint = Context.version(selected, commit);
        when(documents.read(selected, commit, null)).thenReturn(new com.taxonomy.editor.ArchitectureEditorService.Document(checkpoint,
                "element archived type Service {\n title: \"Archived service\";\n}\n", "HISTORICAL", List.of(), List.of(), 0, null, "GIT_CHECKPOINT"));
        var old = reader.readDialogueSource(selected, checkpoint);
        assertThat(old.model().findElement("archived")).isPresent();
        assertThat(reader.dialogueSourceCurrent(selected, checkpoint, old.hash())).isTrue();
        assertThatThrownBy(() -> reader.readDialogueSource(RepositoryContext.workspace("foreign","workspace","branch","alice"),checkpoint))
                .hasMessageContaining("checkpoint");
        verifyNoMoreInteractions(llm);
    }

    @Test void changedContentOrUnavailableProviderCannotReturnAnInventedAnswer() {
        var stale = new Request(null, snapshot, "b".repeat(64), "Warum?", List.of(new Turn("Alt?", "Alt.")), "de");
        assertThatThrownBy(() -> service.ask(selected, stale)).hasMessageContaining("409");
        verifyNoInteractions(llm, budget);
        when(llm.isMockMode()).thenReturn(true);
        assertThatThrownBy(() -> service.ask(selected, new Request(null, snapshot, null, "Warum?", List.of(), "de"))).hasMessageContaining("503");
        verify(llm, never()).callLlmRaw(anyString(),any());
    }

    @Test void boundedRequestAndExclusiveSourcePinsAreEnforcedByTheHttpRoute() throws Exception {
        var resolver = mock(WorkspaceResolver.class);
        var mvc = standaloneSetup(new ArchitectureDialogueController(resolver, service)).build();
        mvc.perform(post("/api/architecture/dialogue").contentType("application/json").content("""
                {"question":"Why?","language":"en","history":[]}
                """)).andExpect(status().isBadRequest());
        var valid = json.valueToTree(new Request(context, null, null, "Why?", List.of(), "en"));
        ((tools.jackson.databind.node.ObjectNode) valid).put("question", "x".repeat(1001));
        mvc.perform(post("/api/architecture/dialogue").contentType("application/json").content(json.writeValueAsString(valid)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(resolver, llm);
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "[9999]", "[-1]", "[0.5]", "[\"0\"]", "[99999999999999999999]"})
    void missingOrInventedCitationsAreRejected(String citations) {
        assertThatThrownBy(() -> ArchitectureDialogueService.parse("{\"statements\":[{\"text\":\"Unsupported\",\"citations\":" + citations + "}]}",
                List.of(new Fact("element A", "Actual source")))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"Welches Originalzitat belegt AF-06?", "Welche Bedingung gilt für BP-1535?"})
    void checkpointEvidenceRanksWithItsExactScopedEndpointQuotes(String question) {
        // Scoped fields from the reviewed training-portal checkpoint. Both endpoint quotes happen to be identical here.
        var dsl = """
                evidence analysis-evidence-0671957030c4fec6b54314bba5a2d157a5bfecb4a0541772ff6eec68aefac6cb {
                  for-relation: "UA-1209 SUPPORTS BP-1535";
                  type: requirement-relation;
                  summary: "UA-1209 bietet die geforderte Benutzerfunktion; BP-1535 beschreibt die nutzbare, berechtigte Bereitstellung vorhandener Information. AF-06 verbindet beides konkret für Ausbilder und die ihnen zugeordneten Teilnehmer. Die Richtung ist Anwendung zu unterstütztem Abrufprozess, und der Gegenstand ist Bearbeitungsstand statt Auditprotokoll.";
                  x-analysis-alternative-group: "";
                  x-analysis-assessed-condition: "Sicht auf zugeordnete Teilnehmer begrenzt.";
                  x-analysis-assessed-contribution: "Ausbilder sehen den Bearbeitungsstand der ihnen zugeordneten Teilnehmer.";
                  x-analysis-assessed-id: "UA-1209";
                  x-analysis-assessed-quote: "Ausbilder sehen den Bearbeitungsstand der ihnen zugeordneten Teilnehmer.";
                  x-analysis-candidate-condition: "Ausbildersicht auf Bearbeitungsstände zugeordneter Teilnehmer.";
                  x-analysis-candidate-contribution: "Die Anwendung für individuelle Ausbildung unterstützt den berechtigten Informationsabruf, indem Ausbilder im Portal den Bearbeitungsstand der jeweils zugeordneten Teilnehmer einsehen.";
                  x-analysis-candidate-id: "BP-1535";
                  x-analysis-candidate-quote: "Ausbilder sehen den Bearbeitungsstand der ihnen zugeordneten Teilnehmer.";
                  x-analysis-direction: "OUTGOING";
                  x-analysis-necessity: "REQUIRED";
                  x-analysis-origin: "LLM_SUPPORTED";
                  x-analysis-outcome: "VERIFIED";
                  x-analysis-question: "";
                  x-analysis-snapshot-id: "b7ef268d-1ed6-4ea8-b0c2-32544c1c6c8e";
                  x-analysis-view-id: "analysis-74e75cb477d463d18c91f925ab6baa7d98b1268339ed681d9a6a9b938f0a3f4f";
                }
                """;
        var model = new com.taxonomy.dsl.command.ArchitectureDslCommands().model(dsl);
        // Generic records contained the requested IDs before the witness was reached.
        for (int i = 0; i < 70; i++) model.getElements().add(new ArchitectureElement("service-" + i, "Service",
                "Verweis auf AF-06 und BP-1535", null, "CI"));
        var pinned = new ArchitectureSourceFocusDtos.ResolvedModel(model, null, source.hash(), null);
        var request = new Request(context, null, null, question, List.of(), "de");
        var facts = ArchitectureDialogue.facts(pinned, request);
        var witness = facts.get(1);
        assertThat(witness.source()).contains("analysis-evidence-067195", "UA-1209 SUPPORTS BP-1535",
                "snapshot b7ef268d-1ed6-4ea8-b0c2-32544c1c6c8e");
        String quote = "Ausbilder sehen den Bearbeitungsstand der ihnen zugeordneten Teilnehmer.";
        assertThat(witness.text()).contains("assessed-id: UA-1209", "candidate-id: BP-1535",
                "assessed-quote: " + quote, "candidate-quote: " + quote,
                "assessed-condition: Sicht auf zugeordnete Teilnehmer begrenzt.",
                "candidate-condition: Ausbildersicht auf Bearbeitungsstände zugeordneter Teilnehmer.",
                "necessity: REQUIRED", "outcome: VERIFIED", "view: analysis-74e75cb4");

        var evidence = model.getEvidence().getFirst();
        var fields = new java.util.LinkedHashMap<>(evidence.getExtensions());
        fields.put("x-analysis-candidate-quote", "Abweichendes zweites Zitat.");
        fields.put("x-analysis-question", "Wer bestätigt die Zuordnung?");
        evidence.setExtensions(fields);
        assertThat(ArchitectureDialogue.facts(pinned, request).get(1).text()).contains("assessed-quote: " + quote,
                "candidate-quote: Abweichendes zweites Zitat.", "question: Wer bestätigt die Zuordnung?");
    }

    @Test void lateMatchingElementRemainsRetrievableAndCountsDescribeTheWholeGraph() {
        for (int index = 0; index < 120; index++) source.model().getElements().add(new ArchitectureElement(
                "service-" + index, "Service", "Processing service " + index, null, "CI"));
        var facts = ArchitectureDialogue.facts(source, new Request(context,null,null,"Explain service-119",List.of(),"en"));
        assertThat(facts.getFirst().text()).isEqualTo("elements=121, relations=1");
        assertThat(facts.get(1).source()).isEqualTo("element service-119");
    }
}
