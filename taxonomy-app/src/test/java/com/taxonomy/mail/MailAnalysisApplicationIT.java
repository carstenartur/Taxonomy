package com.taxonomy.mail;

import com.taxonomy.analysis.service.LlmCompletionOptions;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.portfolio.RetainedPortfolioCatalogue;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

/** Exercises the registered mail classifiers, shared catalogue, analysis, and installed file renderers. */
@SpringBootTest(properties = {"taxonomy.mail.enabled=true", "taxonomy.mail.imap.poll-enabled=false",
        "taxonomy.mail.public-base-url=http://localhost/taxonomy", "taxonomy.mail.allowed-senders[0]=test@example.invalid",
        "taxonomy.mail.smtp.host=localhost", "taxonomy.mail.smtp.username=test", "taxonomy.mail.smtp.password=test",
        "taxonomy.mail.smtp.from=taxonomy@example.invalid", "llm.mock=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailAnalysisApplicationIT extends RetainedPortfolioCatalogue {
    @TempDir static Path queueDirectory;
    @DynamicPropertySource
    static void mailDirectory(DynamicPropertyRegistry properties) {
        properties.add("taxonomy.mail.queue.directory", () -> queueDirectory.toString());
    }

    private static final String REQUIREMENT = "Provide secure voice communications";
    @Autowired MailAnalysisProcessor processor;
    @Autowired ObjectMapper json;
    @MockitoSpyBean LlmService llm;

    private void answerSubject(String command) {
        doReturn(command).when(llm).callLlmRaw(anyString(),
                argThat((LlmCompletionOptions options) -> options != null && "mail_analysis_command_v1".equals(options.schemaName())));
    }

    @Test
    void opensTheRequestedArchitectureFilesFromOneAssessment() throws Exception {
        answerSubject("""
                {"mode":"ARCHITECTURE","taxonomyCodes":[],
                 "exports":["PDF","VISIO","SVG","ARCHIMATE","STRUCTURIZR","SPARX","DOCX","HTML","MARKDOWN","JSON"]}
                """);
        var result = processor.process("Gesamtarchitektur mit Zeichnungen und Berichten", REQUIREMENT);
        assertThat(result.successful()).isTrue();
        var files = attachments(result);
        assertThat(files).containsOnlyKeys("requirement-architecture.pdf", "requirement-architecture.vsdx",
                "requirement-architecture.svg", "requirement-architecture.xml", "requirement-architecture.dsl",
                "requirement-architecture.sparx.zip", "architecture-report.docx", "architecture-report.html",
                "architecture-report.md", "architecture-report.json");
        try (var document = Loader.loadPDF(files.get("requirement-architecture.pdf"))) {
            assertThat(document.getNumberOfPages()).isPositive();
            assertThat(document.getDocumentInformation().getSubject()).isEqualTo("Serverseitige Anforderungsanalyse");
        }
        assertThat(zipText(files.get("requirement-architecture.vsdx"), "visio/pages/pages.xml")).contains("<Page");
        assertThat(text(files.get("requirement-architecture.svg"))).contains("<svg");
        assertThat(text(files.get("requirement-architecture.xml"))).contains("opengroup.org/xsd/archimate", "element");
        assertThat(text(files.get("requirement-architecture.dsl"))).contains("workspace", "model");
        assertThat(zipText(files.get("requirement-architecture.sparx.zip"), "architecture.xmi")).contains("XMI");
        var manifest = json.readTree(zipText(files.get("requirement-architecture.sparx.zip"), "manifest.json"));
        assertThat(manifest.path("sourceAuthority").asString()).isEqualTo("server-side-analysis");
        assertThat(zipText(files.get("architecture-report.docx"), "word/document.xml")).contains(REQUIREMENT);
        assertThat(text(files.get("architecture-report.html"))).contains(REQUIREMENT);
        assertThat(text(files.get("architecture-report.md"))).contains(REQUIREMENT);
        assertThat(json.readTree(files.get("architecture-report.json")).path("businessText").asString()).isEqualTo(REQUIREMENT);
    }

    @Test
    void returnsRealDecisionDrawingsRestrictedToTheTwoRequestedRoots() throws IOException {
        answerSubject("""
                {"mode":"DECISION_TREE","taxonomyCodes":["BP","IP"],"exports":["JSON","DOCX","HTML","VISIO"]}
                """);
        var result = processor.process("Entscheidungsbaum BP und IP als JSON, Word, HTML und Visio", REQUIREMENT);
        assertThat(result.successful()).isTrue();
        var files = attachments(result);
        assertThat(files).containsOnlyKeys("decision-tree.json", "decision-tree.docx", "decision-tree.html", "decision-tree.vsdx");
        var report = json.readTree(files.get("decision-tree.json"));
        assertThat(report.path("requirement").asString()).isEqualTo(REQUIREMENT);
        assertThat(report.path("scope").path("reportRoots").valueStream().map(node -> node.asString()).toList())
                .containsExactlyInAnyOrder("BP", "IP");
        assertThat(report.path("scope").path("decisionTree").path("rows").size()).isPositive();
        assertThat(report.path("scope").path("modelBinding").isObject()).isTrue();
        assertThat(text(files.get("decision-tree.html"))).contains(REQUIREMENT);
        assertThat(zipText(files.get("decision-tree.docx"), "word/document.xml")).contains("BP", "IP");
        assertThat(zipText(files.get("decision-tree.vsdx"), "visio/pages/pages.xml"))
                .contains("BP", "IP").doesNotContain("Entscheidungsbaum – CO");
    }

    private static Map<String, byte[]> attachments(MailAnalysisQueue.Outcome result) {
        var files = new HashMap<String, byte[]>();
        result.attachments().forEach(file -> {
            assertThat(file.content()).isNotEmpty();
            assertThat(files.put(file.filename(), file.content())).isNull();
        });
        return files;
    }

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    private static String zipText(byte[] bytes, String filename) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(filename)) return text(zip.readAllBytes());
            }
        }
        throw new AssertionError("Missing file in archive: " + filename);
    }
}
