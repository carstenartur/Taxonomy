package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateLocalEditControllerTest {
    private static final String REVISION = "b".repeat(40);
    @Mock DocumentTemplateService templates;
    @Mock DocumentTemplateReportPreview preview;

    @Test
    void reloadKeepsTheOriginalRevisionWithoutReadingCurrentHead() throws Exception {
        String id = DecisionRationaleTemplateContract.TEMPLATE_ID;
        when(templates.download(id, REVISION)).thenReturn(file(id));
        var controller = new DocumentTemplateDetailController(templates, preview);
        for (int reload = 0; reload < 2; reload++) {
            var model = new ConcurrentModel();
            assertThat(controller.localEdit(id, REVISION, model))
                    .isEqualTo("document-template-local-edit");
            var descriptor = (TemplateDescriptor) model.getAttribute("template");
            assertThat(descriptor.headCommit()).isEqualTo(REVISION);
            assertThat(descriptor.templateId()).isEqualTo(id);
            assertThat(model.getAttribute("decisionReportTemplate")).isEqualTo(true);
            assertThat(model.getAttribute("maxArchiveBytes"))
                    .isEqualTo(OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES);
        }
        verify(templates, times(2)).download(id, REVISION);
        verifyNoMoreInteractions(templates);
        verifyNoInteractions(preview);
    }

    @Test
    void genericTemplateDoesNotAdvertiseADecisionReportPreview() throws Exception {
        when(templates.download("organisation", REVISION)).thenReturn(file("organisation"));
        var model = new ConcurrentModel();
        new DocumentTemplateDetailController(templates, preview)
                .localEdit("organisation", REVISION, model);
        assertThat(model.getAttribute("decisionReportTemplate")).isEqualTo(false);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"main", "HEAD", "bbbbbbb", "../main", "*", "W/\"revision\""})
    void rejectsMissingMutableOrAbbreviatedRevisionsBeforeStorage(String revision) {
        var controller = new DocumentTemplateDetailController(templates, preview);
        assertThatThrownBy(() -> controller.localEdit("organisation", revision, new ConcurrentModel()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(templates, preview);
    }

    private static TemplateFile file(String id) {
        var manifest = new TemplateManifest(1, id, "Organisation", id + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE, "2026-09-05T00:00:00Z",
                "admin", 10, 3, "f".repeat(64));
        return new TemplateFile(manifest, REVISION, new byte[]{1}, Instant.EPOCH);
    }
}
