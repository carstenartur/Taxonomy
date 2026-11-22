package com.taxonomy.mail;

import com.taxonomy.analysis.service.LlmCompletionOptions;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmResponseParser;
import com.taxonomy.analysis.service.OpenAiCompatibleGateway;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.reporting.api.decision.DecisionRationaleReportPlugin;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.taxonomy.mail.MailAnalysisCommand.Format;
import static com.taxonomy.mail.MailAnalysisCommand.Mode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MailAnalysisCommandClassifierTest {

    private final LlmService llm = mock(LlmService.class);
    private final TaxonomyService taxonomy = mock(TaxonomyService.class);

    private final ExportFormatExtensionRegistry diagrams = mock(ExportFormatExtensionRegistry.class);
    private final ReportRendererRegistry reports = mock(ReportRendererRegistry.class);

    @Test
    void classifiesSubjectAgainstTheActualCatalogueWithAStructuredCompletion() {
        var service = classifier("""
                {"mode":"DECISION_TREE","taxonomyCodes":["CI-1000","BP-1000"],"exports":["DOCX","HTML"]}
                """, false);
        assertThat(service.classify("Entscheidungsbaum CI und BP als Word und HTML"))
                .isEqualTo(new MailAnalysisCommand(Mode.DECISION_TREE, List.of("CI-1000", "BP-1000"),
                        List.of(Format.DOCX, Format.HTML)));
        var prompt = ArgumentCaptor.forClass(String.class);
        var options = ArgumentCaptor.forClass(LlmCompletionOptions.class);
        verify(llm).callLlmRaw(prompt.capture(), options.capture());
        assertThat(prompt.getValue()).contains("\"Entscheidungsbaum CI und BP als Word und HTML\"", "Business Processes", "Geschäftsprozesse");
        assertThat(options.getValue().schemaName()).isEqualTo("mail_analysis_command_v1");
        assertThat(options.getValue().schema()).containsEntry("additionalProperties", false);
    }

    @Test
    void keepsArchitectureUnscopedAndEnablesOptionalDecisionPdf() {
        var architecture = classifier("""
                {"mode":"ARCHITECTURE","taxonomyCodes":[],"exports":["PDF","SVG"]}
                """, false);
        assertThat(architecture.classify("Vollständige Architektur als PDF und SVG"))
                .isEqualTo(new MailAnalysisCommand(Mode.ARCHITECTURE, List.of(), List.of(Format.PDF, Format.SVG)));
        var decision = classifier("""
                {"mode":"DECISION_TREE","taxonomyCodes":["BP-1000"],"exports":["PDF"]}
                """, true);
        assertThat(decision.classify("Entscheidungsbaum BP als PDF").exports()).containsExactly(Format.PDF);
    }

    @Test
    void sendsGermanAndEnglishSubjectsThroughTheStructuredProviderProtocol() {
        var service = classifier("", false);
        var json = JsonMapper.builder().build();
        var client = new RestTemplate();
        var server = MockRestServiceServer.bindTo(client).build();
        String endpoint = "https://mail-provider.example.test/v1/chat/completions";
        var gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, endpoint, "fixture-model", 0,
                client, json, new LlmResponseParser(json), (key, fallback) -> 0, null, null);
        when(llm.callLlmRaw(anyString(), any(LlmCompletionOptions.class))).thenAnswer(call ->
                gateway.extractResponseText(gateway.sendHttpRequest(call.getArgument(0), "", call.getArgument(1))));
        var subjects = List.of("Entscheidungsbaum für Geschäftsprozesse und Kerndienste als JSON",
                "Build the complete architecture and return HTML and Markdown reports");
        var commands = List.of(new MailAnalysisCommand(Mode.DECISION_TREE, List.of("BP-1000", "CI-1000"), List.of(Format.JSON)),
                new MailAnalysisCommand(Mode.ARCHITECTURE, List.of(), List.of(Format.HTML, Format.MARKDOWN)));
        for (int i = 0; i < subjects.size(); i++) {
            var choice = Map.of("finish_reason", "stop", "message", Map.of("content", json.writeValueAsString(commands.get(i))));
            server.expect(requestTo(endpoint))
                    .andExpect(jsonPath("$.messages[0].content", containsString(subjects.get(i))))
                    .andExpect(jsonPath("$.response_format.json_schema.name").value("mail_analysis_command_v1"))
                    .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                    .andRespond(withSuccess(json.writeValueAsString(Map.of("choices", List.of(choice))), MediaType.APPLICATION_JSON));
        }
        assertThat(subjects.stream().map(service::classify).toList()).isEqualTo(commands);
        server.verify();
    }

    @Test
    void rechecksInstalledExportersForEveryRequest() {
        var service = classifier("""
                {"mode":"ARCHITECTURE","taxonomyCodes":[],"exports":["MERMAID","SPARX"]}
                """, false);
        String subject = "Architecture as Mermaid and Sparx EA";
        assertThatIllegalArgumentException().isThrownBy(() -> service.classify(subject));
        when(diagrams.listDescriptors()).thenReturn(diagramFormats("mermaid", "sparx"));
        assertThat(service.classify(subject).exports()).containsExactly(Format.MERMAID, Format.SPARX);
        when(diagrams.listDescriptors()).thenReturn(diagramFormats("mermaid"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.classify(subject));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"mode\":\"ARCHITECTURE\",\"taxonomyCodes\":[\"BP-1000\"],\"exports\":[\"PDF\"]}",
            "{\"mode\":\"DECISION_TREE\",\"taxonomyCodes\":[],\"exports\":[\"DOCX\"]}",
            "{\"mode\":\"DECISION_TREE\",\"taxonomyCodes\":[\"WRONG\"],\"exports\":[\"DOCX\"]}",
            "{\"mode\":\"DECISION_TREE\",\"taxonomyCodes\":[\"BP-1000\"],\"exports\":[\"PDF\"]}",
            "{\"mode\":\"ARCHITECTURE\",\"taxonomyCodes\":[],\"exports\":[\"PDF\",\"PDF\"]}",
            "{\"mode\":\"DECISION_TREE\",\"taxonomyCodes\":[\"BP-1000\"],\"exports\":[\"SVG\"]}",
            "{\"mode\":\"ARCHITECTURE\",\"taxonomyCodes\":[],\"exports\":[\"PDF\"],\"recipient\":\"other\"}",
            "{\"mode\":\"ARCHITECTURE\",\"mode\":\"DECISION_TREE\",\"taxonomyCodes\":[],\"exports\":[\"PDF\"]}",
            "{\"mode\":\"ARCHITECTURE\",\"taxonomyCodes\":[],\"exports\":[\"PDF\"]} {}"
    })
    void rejectsUnsupportedOrAmbiguousOutput(String response) {
        var service = classifier(response, false);
        assertThatIllegalArgumentException().isThrownBy(() -> service.classify("Testauftrag"));
    }

    private MailAnalysisCommandClassifier classifier(String output, boolean decisionPdf) {
        when(taxonomy.getRootNodes()).thenReturn(List.of(root("BP-1000", "Business Processes", "Geschäftsprozesse"),
                root("CI-1000", "Core Services", "Kerndienste")));
        when(llm.callLlmRaw(anyString(), any(LlmCompletionOptions.class))).thenReturn(output);
        when(diagrams.listDescriptors()).thenReturn(diagramFormats("svg", "visio", "archimate", "structurizr"));
        when(reports.listDescriptors()).thenReturn(reportFormats("docx", "html", "markdown", "json"));
        when(reports.listDescriptors(DecisionRationaleReportPlugin.REPORT_TYPE_ID)).thenReturn(decisionPdf
                ? reportFormats("docx", "html", "json", "visio", "visio-pdf") : reportFormats("docx", "html", "json", "visio"));
        return new MailAnalysisCommandClassifier(llm, taxonomy, new MailAnalysisRenderers(diagrams, reports));
    }

    private static List<ReportFormatDescriptor> reportFormats(String... ids) {
        return Stream.of(ids).map(id -> new ReportFormatDescriptor(id, id, id, "application/octet-stream", false)).toList();
    }

    private static List<ExportFormatDescriptor> diagramFormats(String... ids) {
        return Stream.of(ids).map(id -> new ExportFormatDescriptor(id, id, id, "application/octet-stream", false)).toList();
    }

    private TaxonomyNode root(String code, String title, String germanTitle) {
        var node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(title);
        node.setNameDe(germanTitle);
        return node;
    }
}
