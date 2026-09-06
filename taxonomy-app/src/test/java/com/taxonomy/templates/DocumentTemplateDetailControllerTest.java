package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateRevision;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateDetailControllerTest {

    private static final String CURRENT = "a".repeat(40);
    private static final String OLD = "b".repeat(40);
    private static final String NEWER = "c".repeat(40);
    private static final String ID = DecisionRationaleTemplateContract.TEMPLATE_ID;

    @Mock DocumentTemplateService templates;
    @Mock DocumentTemplateReportPreview preview;

    @Test
    void detailExposesHistoryAndCurrentPerTemplateVersion() throws Exception {
        when(templates.downloadCurrent(ID)).thenReturn(file(CURRENT));
        when(templates.history(ID)).thenReturn(List.of(new TemplateRevision(
                CURRENT, "admin", "2026-08-23T00:00:00Z", "Current")));
        ConcurrentModel model = new ConcurrentModel();

        String view = controller().detail(ID, null, null, null, null, model);

        assertThat(view).isEqualTo("document-template-detail");
        assertThat(model.getAttribute("decisionReportTemplate")).isEqualTo(true);
        TemplateDescriptor descriptor = (TemplateDescriptor) model.getAttribute("template");
        assertThat(descriptor.headCommit()).isEqualTo(CURRENT);
    }

    @Test
    void restoreUsesTheDisplayedCurrentVersionAsOptimisticPrecondition() throws Exception {
        when(templates.restore(ID, OLD, CURRENT, "admin"))
                .thenReturn(new TemplateDescriptor(ID, "Decision report", ID + ".dotx", NEWER,
                        "2026-08-23T00:01:00Z", "admin", 10, 3, "f".repeat(64)));
        Principal principal = () -> "admin";
        var flash = new RedirectAttributesModelMap();

        String redirect = controller().restore(ID, OLD, CURRENT, true, principal,
                new ConcurrentModel(), new MockHttpServletResponse(), flash);

        assertThat(redirect).isEqualTo("redirect:/admin/document-templates/" + ID);
        assertThat(flash.getFlashAttributes().get("restoredRevision")).isEqualTo(NEWER);
        verify(templates).restore(ID, OLD, CURRENT, "admin");
        verifyNoMoreInteractions(templates);
    }

    @Test
    void confirmationNamesTheSelectedTemplateAndDoesNotWrite() throws Exception {
        when(templates.download(ID, OLD)).thenReturn(file(OLD));
        when(templates.downloadCurrent(ID)).thenReturn(file(CURRENT));
        var model = new ConcurrentModel();

        assertThat(controller().confirmRestore(ID, OLD, CURRENT, model))
                .isEqualTo("document-template-restore");
        assertThat(model.getAttribute("restoreRevision")).isEqualTo(OLD);
        assertThat(model.getAttribute("restoreExpectedHead")).isEqualTo(CURRENT);
        assertThat(model.getAttribute("restoreConflict")).isEqualTo(false);
        assertThat(((TemplateDescriptor) model.getAttribute("restoreTarget")).displayName())
                .isEqualTo("Decision report");
        verify(templates).download(ID, OLD);
        verify(templates).downloadCurrent(ID);
        verifyNoMoreInteractions(templates);
        verifyNoInteractions(preview);
    }

    @Test
    void reloadingAStaleConfirmationDoesNotReplaceItsOriginalPrecondition() throws Exception {
        when(templates.download(ID, OLD)).thenReturn(file(OLD));
        when(templates.downloadCurrent(ID)).thenReturn(file(NEWER));
        var model = new ConcurrentModel();

        controller().confirmRestore(ID, OLD, CURRENT, model);

        assertThat(model.getAttribute("restoreRevision")).isEqualTo(OLD);
        assertThat(model.getAttribute("restoreExpectedHead")).isEqualTo(CURRENT);
        assertThat(model.getAttribute("restoreConflict")).isEqualTo(true);
        assertThat(((TemplateDescriptor) model.getAttribute("template")).headCommit()).isEqualTo(NEWER);
        verify(templates).download(ID, OLD);
        verify(templates).downloadCurrent(ID);
        verifyNoMoreInteractions(templates);
    }

    @Test
    void concurrentRestoreReturns412WithOriginalSelectionAndNeverRetries() throws Exception {
        when(templates.restore(ID, OLD, CURRENT, "admin"))
                .thenThrow(new TemplateConflictException(CURRENT, NEWER));
        when(templates.download(ID, OLD)).thenReturn(file(OLD));
        when(templates.downloadCurrent(ID)).thenReturn(file(NEWER));
        var model = new ConcurrentModel();
        var response = new MockHttpServletResponse();
        var flash = new RedirectAttributesModelMap();

        String view = controller().restore(ID, OLD, CURRENT, true, () -> "admin",
                model, response, flash);

        assertThat(view).isEqualTo("document-template-restore");
        assertThat(response.getStatus()).isEqualTo(412);
        assertThat(model.getAttribute("restoreRevision")).isEqualTo(OLD);
        assertThat(model.getAttribute("restoreExpectedHead")).isEqualTo(CURRENT);
        assertThat(model.getAttribute("restoreConflict")).isEqualTo(true);
        assertThat(flash.getFlashAttributes()).isEmpty();
        verify(templates).restore(ID, OLD, CURRENT, "admin");
        verify(templates).download(ID, OLD);
        verify(templates).downloadCurrent(ID);
        verifyNoMoreInteractions(templates);
        verifyNoInteractions(preview);
    }

    @Test
    void missingExplicitConfirmationFailsBeforeStorage() {
        assertThatThrownBy(() -> controller().restore(ID, OLD, CURRENT, false, () -> "admin",
                new ConcurrentModel(), new MockHttpServletResponse(), new RedirectAttributesModelMap()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(templates, preview);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"main", "HEAD", "aaaaaaa", "*", "../main", "W/\"revision\""})
    void confirmationAndRestoreRejectMutableOrMissingRevisionsBeforeStorage(String invalid) {
        for (String[] pair : List.of(new String[]{invalid, CURRENT}, new String[]{OLD, invalid})) {
            assertThatThrownBy(() -> controller().confirmRestore(ID, pair[0], pair[1], new ConcurrentModel()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThatThrownBy(() -> controller().restore(ID, pair[0], pair[1], true, () -> "admin",
                    new ConcurrentModel(), new MockHttpServletResponse(), new RedirectAttributesModelMap()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(templates, preview);
    }

    @Test
    void missingHistoricalVersionIs404AndDoesNotFallBackToCurrent() throws Exception {
        var missing = new TemplateNotFoundException("internal-storage-marker", OLD);
        when(templates.download(ID, OLD)).thenThrow(missing);
        assertThatThrownBy(() -> controller().confirmRestore(ID, OLD, CURRENT, new ConcurrentModel()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getCause()).isSameAs(missing);
                    assertThat(error.getReason()).doesNotContain("internal-storage-marker");
                });
        verify(templates).download(ID, OLD);
        verifyNoMoreInteractions(templates);
    }

    @Test
    void testReportReturnsAGenuineDocxDownload() {
        when(preview.renderPreview()).thenReturn(new byte[]{1, 2, 3});
        var response = controller().testReport(ID);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    private DocumentTemplateDetailController controller() {
        return new DocumentTemplateDetailController(templates, preview);
    }

    private static TemplateFile file(String revision) {
        TemplateManifest manifest = new TemplateManifest(1, ID, "Decision report", ID + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE, "2026-08-23T00:00:00Z", "admin", 10, 3,
                "f".repeat(64));
        return new TemplateFile(manifest, revision, new byte[]{1}, Instant.EPOCH);
    }
}
