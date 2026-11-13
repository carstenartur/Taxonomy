package com.taxonomy.templates;

import com.taxonomy.templates.api.DocumentTemplateReportPreview;
import com.taxonomy.templates.api.DocumentTemplates;
import com.taxonomy.templates.api.TemplateContribution;

import com.taxonomy.reporting.render.decision.DecisionChapterDiagramRenderer;
import com.taxonomy.reporting.render.decision.DecisionRationaleDocxRenderer;
import com.taxonomy.reporting.render.decision.DecisionRationaleTemplatePreviewService;
import com.taxonomy.reporting.render.decision.DecisionRationaleTemplateRenderer;
import com.taxonomy.reporting.templates.DecisionRationaleTemplateContract;
import com.taxonomy.reporting.templates.DecisionTemplateConfiguration;

import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.shared.config.I18nConfig;
import com.taxonomy.templates.api.TemplateDescriptor;
import com.taxonomy.templates.api.TemplateManifest;
import com.taxonomy.templates.api.TemplateNotFoundException;
import com.taxonomy.templates.api.TemplateFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

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
            assertThat(model.getAttribute("templatePreviewPinned")).isEqualTo(false);
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
    void savedDecisionSamplesRenderTheSelectedTemplateBytesAndProvenance(@TempDir Path directory) throws Exception {
        String id = DecisionRationaleTemplateContract.TEMPLATE_ID;
        String path = "/admin/document-templates/" + id + "/test.docx";
        try (var git = new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build();
             var context = new AnnotationConfigApplicationContext()) {
            git.create(true);
            var codec = new OoxmlTemplatePackageCodec();
            var contract = new DecisionRationaleTemplateContract();
            var stored = new DocumentTemplateService(new DocumentTemplateGitRepository(git), codec, List.of(contract));
            context.registerBean(DocumentTemplates.class, () -> stored);
            context.registerBean(DecisionRationaleTemplateContract.class, () -> contract);
            context.register(DecisionTemplateConfiguration.class, DecisionRationaleTemplatePreviewService.class,
                    DecisionRationaleTemplateRenderer.class, DecisionRationaleDocxRenderer.class, DecisionChapterDiagramRenderer.class);
            context.refresh();

            var original = uploadBranded(stored, codec, "Historical branding", null);
            var current = uploadBranded(stored, codec, "Current branding", original.headCommit());
            var history = stored.history(id);
            var controller = new DocumentTemplateDetailController(stored, List.of(context.getBean(TemplateContribution.class)));
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            var localModel = new ConcurrentModel();
            controller.localEdit(id, original.headCommit(), localModel);
            assertThat(renderPage("document-template-local-edit", localModel))
                    .contains("href=\"/taxonomy" + path + "?revision=" + original.headCommit() + "\"");
            var detailModel = new ConcurrentModel();
            controller.detail(id, null, null, null, null, detailModel);
            assertThat(renderPage("document-template-detail", detailModel))
                    .contains("href=\"/taxonomy" + path + "?revision=" + current.headCommit() + "\"",
                            "href=\"/taxonomy" + path + "?revision=" + original.headCommit() + "\"");

            var historical = mvc.perform(get(path).param("revision", original.headCommit().toUpperCase(Locale.ROOT)))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertSample(historical.getContentAsByteArray(), original, "Historical branding", "Current branding");
            var unpinned = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
            assertSample(unpinned.getContentAsByteArray(), current, "Current branding", "Historical branding");
            for (String invalidRevision : List.of("", " ", "HEAD")) {
                mvc.perform(get(path).param("revision", invalidRevision)).andExpect(status().isBadRequest());
            }
            mvc.perform(get(path).param("revision", "0".repeat(40))).andExpect(status().isNotFound());
            assertThat(stored.history(id)).isEqualTo(history);
            assertThat(stored.describeCurrent(id)).isEqualTo(current);
        }
    }

    @Test
    void preflightPageAndBinaryAttachmentsAreBoundToOneCandidateInBothLanguages(@TempDir Path directory) throws Exception {
        String id = DecisionRationaleTemplateContract.TEMPLATE_ID;
        try (var git = new FileRepositoryBuilder().setGitDir(directory.resolve("active").toFile()).setBare().build();
             var stage = new FileRepositoryBuilder().setGitDir(directory.resolve("staged").toFile()).setBare().build();
             var context = new AnnotationConfigApplicationContext()) {
            git.create(true);
            stage.create(true);
            var codec = new OoxmlTemplatePackageCodec();
            var contract = new DecisionRationaleTemplateContract();
            var stored = new DocumentTemplateService(new DocumentTemplateGitRepository(git), codec, List.of(contract));
            context.registerBean(DocumentTemplates.class, () -> stored);
            context.registerBean(DecisionRationaleTemplateContract.class, () -> contract);
            context.register(DecisionTemplateConfiguration.class, DecisionRationaleTemplatePreviewService.class,
                    DecisionRationaleTemplateRenderer.class, DecisionRationaleDocxRenderer.class, DecisionChapterDiagramRenderer.class);
            context.refresh();
            var base = uploadBranded(stored, codec, "Candidate branding", null);
            byte[] submitted = stored.downloadCurrent(id).content();
            var service = new DocumentTemplateCandidateService(new DocumentTemplateCandidateStore(stage), stored,
                    List.of(context.getBean(TemplateContribution.class)));
            var controller = new DocumentTemplateCandidateController(service, stored);
            var model = new ConcurrentModel();
            String path = controller.stage(id, "Reviewed", base.headCommit(),
                    new org.springframework.mock.web.MockMultipartFile("file", submitted), () -> "reviewer",
                    new MockHttpServletResponse(), model);
            String version = path.substring(path.lastIndexOf('/') + 1);
            assertThat(version).matches("[0-9a-f]{40}");
            for (var locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                String rendered = renderPage(controller.detail(id, version, model), model, locale);
                assertThat(rendered).contains(base.headCommit(), version, "sample.docx", "original.dotx", "validation.json",
                        locale.equals(Locale.ENGLISH) ? "Activate this candidate" : "Diesen Kandidaten aktivieren");
            }
            assertThat(controller.original(id, version).getBody()).isEqualTo(submitted);
            try (var doc = new XWPFDocument(new ByteArrayInputStream(controller.sample(id, version).getBody()))) {
                assertThat(doc.getHeaderList()).singleElement().satisfies(h -> assertThat(h.getText()).contains("Candidate branding"));
                assertThat(doc.getDocument().xmlText()).doesNotContain("{{taxonomy.", "w:dataBinding");
                assertThat(doc.getProperties().getCustomProperties().getProperty("Taxonomy.Template.Commit").getLpwstr())
                        .isEqualTo(version);
            }
            var validation = controller.validation(id, version);
            assertThat(validation.getHeaders().getCacheControl()).contains("no-store");
            assertThat(validation.getBody()).containsEntry("candidateVersion", version).containsEntry("sample", "PASSED")
                    .doesNotContainKey("displayName");
            assertThat(stored.describeCurrent(id).headCommit()).isEqualTo(base.headCommit());
            controller.activate(id, version, () -> "reviewer", new MockHttpServletResponse(), new ConcurrentModel());
            assertThat(stored.describeCurrent(id).headCommit()).isNotEqualTo(base.headCommit());
        }
    }

    private static String renderPage(String page, ConcurrentModel model) {
        return renderPage(page, model, Locale.ENGLISH);
    }

    private static String renderPage(String page, ConcurrentModel model, Locale locale) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageSource(new I18nConfig().messageSource());
        var servletContext = new MockServletContext();
        servletContext.setContextPath("/taxonomy");
        var request = new MockHttpServletRequest(servletContext);
        request.setContextPath("/taxonomy");
        request.setRequestURI("/taxonomy/admin/document-templates/decision-rationale-report");
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(request, new MockHttpServletResponse());
        var context = new WebContext(exchange, locale);
        model.forEach(context::setVariable);
        return engine.process(page, context);
    }

    private static TemplateDescriptor uploadBranded(DocumentTemplateService stored, OoxmlTemplatePackageCodec codec,
            String branding, String expectedVersion) throws Exception {
        try (var seed = DocumentTemplateDetailControllerLocalEditTest.class.getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(seed).isNotNull();
            var parts = new LinkedHashMap<>(codec.unpack(seed).parts());
            String header = new String(parts.get("word/header1.xml"), StandardCharsets.UTF_8);
            parts.put("word/header1.xml", header.replace("<w:t>", "<w:t>" + branding + " · ")
                    .getBytes(StandardCharsets.UTF_8));
            return stored.upload(DecisionRationaleTemplateContract.TEMPLATE_ID, DecisionRationaleTemplateContract.DISPLAY_NAME,
                    new ByteArrayInputStream(codec.pack(parts)), expectedVersion, "admin", branding);
        }
    }

    private static void assertSample(byte[] bytes, TemplateDescriptor revision, String branding, String otherBranding) throws Exception {
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(doc.getPackagePart().getContentType()).isEqualTo(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
            var properties = doc.getProperties().getCustomProperties();
            assertThat(properties.getProperty("Taxonomy.Template.Commit").getLpwstr()).isEqualTo(revision.headCommit());
            assertThat(properties.getProperty("Taxonomy.Template.PackageSha256").getLpwstr()).isEqualTo(revision.packageSha256());
            assertThat(doc.getHeaderList()).singleElement().satisfies(header ->
                    assertThat(header.getText()).contains(branding).doesNotContain(otherBranding));
        }
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
                com.taxonomy.templates.api.OoxmlTemplateMediaTypes.DOTX_MEDIA_TYPE, "2026-09-05T00:00:00Z",
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
