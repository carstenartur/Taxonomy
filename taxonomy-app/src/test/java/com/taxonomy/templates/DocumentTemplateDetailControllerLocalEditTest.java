package com.taxonomy.templates;

import com.taxonomy.templates.api.DocumentTemplateReportPreview;

import com.taxonomy.reporting.templates.DecisionRationaleTemplateContract;

import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.templates.api.TemplateDescriptor;
import com.taxonomy.templates.api.TemplateManifest;
import com.taxonomy.templates.api.TemplateNotFoundException;
import com.taxonomy.templates.api.TemplateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateDetailControllerLocalEditTest {
    private static final String REVISION = "b".repeat(40);
    @Mock DocumentTemplateService templates;
    @Mock DocumentTemplateReportPreview preview;

    @Test
    void reloadKeepsTheOriginalRevisionWithoutReadingCurrentHead() throws Exception {
        String id = DecisionRationaleTemplateContract.TEMPLATE_ID;
        when(templates.download(id, REVISION)).thenReturn(file(id));
        var controller = new DocumentTemplateDetailController(templates, contributions());
        for (int reload = 0; reload < 2; reload++) {
            var model = new ConcurrentModel();
            assertThat(controller.localEdit(id, REVISION, model))
                    .isEqualTo("document-template-local-edit");
            var descriptor = (TemplateDescriptor) model.getAttribute("template");
            assertThat(descriptor.headCommit()).isEqualTo(REVISION);
            assertThat(descriptor.templateId()).isEqualTo(id);
            assertThat(model.getAttribute("templatePreviewAvailable")).isEqualTo(true);
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
        new DocumentTemplateDetailController(templates, contributions())
                .localEdit("organisation", REVISION, model);
        assertThat(model.getAttribute("templatePreviewAvailable")).isEqualTo(false);
    }

    @Test
    void missingStartingRevisionIsNotFoundAndNeverFallsBackToHead() throws Exception {
        var missing = new TemplateNotFoundException("organisation", REVISION);
        when(templates.download("organisation", REVISION)).thenThrow(missing);
        var controller = new DocumentTemplateDetailController(templates, contributions());
        assertThatThrownBy(() -> controller.localEdit("organisation", REVISION, new ConcurrentModel()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getCause()).isSameAs(missing);
                    assertThat(error.getReason())
                            .isEqualTo("The requested template starting revision does not exist");
                });
        verify(templates).download("organisation", REVISION);
        verifyNoMoreInteractions(templates);
        verifyNoInteractions(preview);
    }

    @Test
    void missingStartingRevisionRetainsCauseWithoutExposingItInMvcResponse() throws Exception {
        var missing = new TemplateNotFoundException("private-storage-marker", REVISION);
        when(templates.download("organisation", REVISION)).thenThrow(missing);
        var controller = new DocumentTemplateDetailController(templates, contributions());
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new DocumentTemplateExceptionHandler(),
                        new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        var result = mvc.perform(get("/admin/document-templates/organisation/local-edit")
                        .param("revision", REVISION).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getResolvedException())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getCause()).isSameAs(missing));
        assertThat(result.getResponse().getContentAsString())
                .contains("The requested template starting revision does not exist")
                .doesNotContain("private-storage-marker", TemplateNotFoundException.class.getName());
        verify(templates).download("organisation", REVISION);
        verifyNoMoreInteractions(templates);
        verifyNoInteractions(preview);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"main", "HEAD", "bbbbbbb", "../main", "*", "W/\"revision\""})
    void rejectsMissingMutableOrAbbreviatedRevisionsBeforeStorage(String revision) {
        var controller = new DocumentTemplateDetailController(templates, contributions());
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
    private java.util.List<com.taxonomy.templates.api.TemplateContribution> contributions() {
        return java.util.List.of(new com.taxonomy.templates.api.TemplateContribution(
                DecisionRationaleTemplateContract.TEMPLATE_ID, DecisionRationaleTemplateContract.DISPLAY_NAME,
                () -> getClass().getResourceAsStream("/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE),
                new DecisionRationaleTemplateContract(), true, preview, "decision-rationale-template-test.docx"));
    }

}
