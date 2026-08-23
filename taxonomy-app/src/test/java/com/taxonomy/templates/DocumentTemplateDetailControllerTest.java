package com.taxonomy.templates;

import com.taxonomy.architecture.decision.DecisionRationaleTemplatePreviewService;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateRevision;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateDetailControllerTest {

    private static final String CURRENT = "a".repeat(40);
    private static final String OLD = "b".repeat(40);

    @Mock DocumentTemplateService templates;
    @Mock DecisionRationaleTemplatePreviewService preview;

    @Test
    void detailExposesHistoryAndCurrentPerTemplateVersion() throws Exception {
        when(templates.downloadCurrent(DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(file());
        when(templates.history(DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(List.of(new TemplateRevision(
                        CURRENT, "admin", "2026-08-23T00:00:00Z", "Current")));
        ConcurrentModel model = new ConcurrentModel();

        String view = controller().detail(
                DecisionRationaleTemplateContract.TEMPLATE_ID,
                null, null, null, null, model);

        assertThat(view).isEqualTo("document-template-detail");
        assertThat(model.getAttribute("decisionReportTemplate")).isEqualTo(true);
        TemplateDescriptor descriptor = (TemplateDescriptor) model.getAttribute("template");
        assertThat(descriptor.headCommit()).isEqualTo(CURRENT);
    }

    @Test
    void restoreUsesTheDisplayedCurrentVersionAsOptimisticPrecondition() throws Exception {
        when(templates.restore(
                DecisionRationaleTemplateContract.TEMPLATE_ID,
                OLD,
                CURRENT,
                "admin"))
                .thenReturn(new TemplateDescriptor(
                        DecisionRationaleTemplateContract.TEMPLATE_ID,
                        "Decision report",
                        DecisionRationaleTemplateContract.TEMPLATE_ID + ".dotx",
                        "c".repeat(40),
                        "2026-08-23T00:01:00Z",
                        "admin",
                        10,
                        3,
                        "f".repeat(64)));
        Principal principal = () -> "admin";

        String redirect = controller().restore(
                DecisionRationaleTemplateContract.TEMPLATE_ID,
                OLD,
                CURRENT,
                principal,
                new RedirectAttributesModelMap());

        assertThat(redirect).isEqualTo("redirect:/admin/document-templates/"
                + DecisionRationaleTemplateContract.TEMPLATE_ID);
        verify(templates).restore(
                DecisionRationaleTemplateContract.TEMPLATE_ID, OLD, CURRENT, "admin");
    }

    @Test
    void testReportReturnsAGenuineDocxDownload() {
        when(preview.renderPreview()).thenReturn(new byte[]{1, 2, 3});

        var response = controller().testReport(
                DecisionRationaleTemplateContract.TEMPLATE_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    private DocumentTemplateDetailController controller() {
        return new DocumentTemplateDetailController(templates, preview);
    }

    private static TemplateFile file() {
        TemplateManifest manifest = new TemplateManifest(
                1,
                DecisionRationaleTemplateContract.TEMPLATE_ID,
                "Decision report",
                DecisionRationaleTemplateContract.TEMPLATE_ID + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                "2026-08-23T00:00:00Z",
                "admin",
                10,
                3,
                "f".repeat(64));
        return new TemplateFile(manifest, CURRENT, new byte[]{1}, Instant.EPOCH);
    }
}
