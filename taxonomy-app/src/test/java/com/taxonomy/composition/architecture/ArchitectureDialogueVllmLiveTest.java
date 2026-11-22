package com.taxonomy.composition.architecture;

import com.taxonomy.analysis.operation.AnalysisChildOperations;
import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.AiTargetCatalogService;
import com.taxonomy.analysis.service.LlmGatewayRegistry;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Two opt-in completions against an operator's vLLM server, using a synthetic editor checkpoint. */
@Tag("real-llm")
@EnabledIfSystemProperty(named = "taxonomy.dialogue.evaluateLive", matches = "true")
class ArchitectureDialogueVllmLiveTest {
    private static final String DSL = """
            requirement PORTAL-SICHT {
              title: "Sicht auf Lernstände";
              text: "Ausbilder sehen ausschließlich den Lernstand der ihnen zugeordneten Teilnehmerkonten.";
            }
            element zuordnung type Service {
              title: "Zuordnung im Ausbildungsportal";
              description: "Konto NOVA-42 ist Ausbilder Süd zugeordnet. Konto KOI-17 ist Ausbilder Nord zugeordnet.";
            }
            requirement PORTAL-FREIGABE {
              title: "Freigabe von Lehrinhalten";
              text: "Welche Rolle neue Lehrinhalte verbindlich freigibt, ist noch offen.";
            }
            """;

    @Test
    void identifiesTheVisibleParticipantAndLeavesTheApprovalRoleUndecided() throws Exception {
        try (var settings = new AnnotationConfigApplicationContext()) {
            settings.getEnvironment().getPropertySources().addFirst(new MapPropertySource("live-dialogue",
                    Map.of("llm.mock", false, "llm.provider", "CUSTOM_OPENAI")));
            settings.registerBean(LlmProviderConfig.class, () -> new LlmProviderConfig(null));
            settings.refresh();
            var config = settings.getBean(LlmProviderConfig.class);
            var provider = LlmProvider.CUSTOM_OPENAI.id();
            assertThat(config.getActiveProviderId()).isEqualTo(provider);
            assertThat(config.getProviderConfigurationError(provider)).as("CUSTOM_OPENAI configuration error").isNull();
            var endpoint = URI.create(config.getOpenAiCompatibleUrl(provider));
            assertThat(endpoint.getRawQuery() == null && endpoint.getRawFragment() == null)
                    .as("Use CUSTOM_LLM_API_KEY for credentials and a plain chat/completions URL").isTrue();

            var budget = new AiPromptBudgetPolicy(new AiTargetCatalogService(config));
            var http = new SimpleClientHttpRequestFactory();
            http.setConnectTimeout(Duration.ofSeconds(10));
            http.setReadTimeout(Duration.ofSeconds(90));
            var json = JsonMapper.builder().build();
            var gateways = new LlmGatewayRegistry(config, new RestTemplate(http), json, null, http, null, budget);
            var llm = new LlmService(config, gateways, json, null, null, null, null);
            var scope = RepositoryContext.workspace("portal", "synthetic", "draft", "reviewer");
            var checkpoint = Context.version(scope, "a".repeat(40));
            var documents = mock(ArchitectureEditorService.class);
            when(documents.read(scope, checkpoint.commit(), null)).thenReturn(new ArchitectureEditorService.Document(
                    checkpoint, DSL, "HISTORICAL", List.of(), List.of(), 0, null, "GIT_CHECKPOINT"));
            var editor = new ArchitectureFocusSuggestionService(documents, llm, budget);
            var dialogue = new ArchitectureDialogueService(editor, null, llm, budget, new AnalysisChildOperations() {
                @Override public <T> T execute(String kind, String fingerprint, Class<T> type, boolean dispatch,
                        Supplier<T> work, BooleanSupplier current) {
                    assertThat(dispatch && current.getAsBoolean()).as("Live provider and exact checkpoint").isTrue();
                    return work.get();
                }
            });

            String question = "Ausbilder Nord möchte die Lernstände von NOVA-42 und KOI-17 sehen. "
                    + "Welches Konto fällt laut Sichtregel und Zuordnung in seinen Bereich? "
                    + "Antworte nur mit der Kontokennung und belege sowohl die Regel als auch die Zuordnung.";
            var visible = dialogue.ask(scope, new ArchitectureDialogue.Request(checkpoint, null, null, question, List.of(), "de"));
            assertThat(visible.provider()).isEqualTo(provider.value());
            assertThat(visible.outcome()).isEqualTo("ANSWERED");
            assertThat(visible.statements()).singleElement().satisfies(statement -> {
                assertThat(statement.text().strip()).matches("(?i)KOI-17[.!]?");
                assertThat(statement.evidence()).extracting(ArchitectureDialogue.Fact::source)
                        .contains("requirement PORTAL-SICHT", "element zuordnung");
            });

            var role = dialogue.ask(scope, new ArchitectureDialogue.Request(checkpoint, null, visible.sourceHash(),
                    "Welche Rolle ist für die verbindliche Freigabe neuer Lehrinhalte zuständig? "
                    + "Antworte ausschließlich mit dem belegten Rollennamen oder dem Wort offen.",
                    List.of(new ArchitectureDialogue.Turn(question, visible.statements().getFirst().text())), "de"));
            assertThat(role.sourceHash()).isEqualTo(visible.sourceHash());
            assertThat(role.provider()).isEqualTo(provider.value());
            if (role.outcome().equals("INSUFFICIENT_EVIDENCE")) {
                assertThat(role.statements()).isEmpty();
            } else {
                assertThat(role.outcome()).isEqualTo("ANSWERED");
                assertThat(role.statements()).singleElement().satisfies(statement -> {
                    assertThat(statement.text().strip()).matches("(?i)offen[.!]?");
                    assertThat(statement.evidence()).extracting(ArchitectureDialogue.Fact::source)
                            .contains("requirement PORTAL-FREIGABE");
                });
            }
        }
    }
}
