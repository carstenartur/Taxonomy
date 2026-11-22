package com.taxonomy.mail;

import com.taxonomy.analysis.service.LlmCompletionOptions;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.taxonomy.mail.MailAnalysisCommand.Format;
import static com.taxonomy.mail.MailAnalysisCommand.Mode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailAnalysisCommandClassifierTest {

    private final LlmService llm = mock(LlmService.class);
    private final TaxonomyService taxonomy = mock(TaxonomyService.class);

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
        assertThat(prompt.getValue()).contains("\"Entscheidungsbaum CI und BP als Word und HTML\"", "Business Processes");
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
        when(taxonomy.getRootNodes()).thenReturn(List.of(root("BP-1000", "Business Processes"),
                root("CI-1000", "Core Services")));
        when(llm.callLlmRaw(anyString(), any(LlmCompletionOptions.class))).thenReturn(output);
        return new MailAnalysisCommandClassifier(llm, taxonomy, decisionPdf);
    }

    private TaxonomyNode root(String code, String title) {
        var node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(title);
        return node;
    }
}
