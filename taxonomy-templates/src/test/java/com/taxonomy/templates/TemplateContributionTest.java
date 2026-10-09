package com.taxonomy.templates;

import com.taxonomy.templates.api.DocumentTemplates;
import com.taxonomy.templates.api.DocumentTemplateContract;
import com.taxonomy.templates.api.TemplateContribution;
import com.taxonomy.templates.api.TemplateConflictException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateContributionTest {
    @Test
    void independentFamiliesSeedOnceAndPreserveEditedAndUnknownTemplates() throws Exception {
        try (var git = new InMemoryRepository(new DfsRepositoryDescription("contributions"))) {
            var families = List.of(contribution("first-report", true, false),
                    contribution("second-report", true, false));
            var templates = new DocumentTemplateService(new DocumentTemplateGitRepository(git),
                    new OoxmlTemplatePackageCodec(), List.of(), families,
                    new OoxmlActiveContentValidator(), new DocumentTemplateMaterializationCache());
            seed(templates, families);
            assertEquals(2, templates.list().size());
            String head = templates.describeCurrent("first-report").headCommit();
            String edited;
            try (var input = fixture()) {
                edited = templates.upload("first-report", "Organisation branding", input,
                        head, "editor", "Organisation edit").headCommit();
            }
            try (var input = fixture()) {
                templates.upload("uninstalled-family", "Retained data", input, null, "editor", "Upload");
            }
            seed(templates, families);
            assertEquals(edited, templates.describeCurrent("first-report").headCommit());
            assertEquals(2, templates.history("first-report").size());
            assertTrue(templates.exists("uninstalled-family"));
        }
    }

    @Test
    void duplicateIdsFailBeforeAnyRepositoryAccess() {
        DocumentTemplates templates = mock(DocumentTemplates.class);
        var first = contribution("duplicate", true, false);
        var second = contribution("duplicate", false, false);
        assertThrows(IllegalStateException.class, () -> seed(templates, List.of(first, second)));
        verifyNoInteractions(templates);
    }

    @Test
    void missingRequiredSeedPreventsSuccessfulStartup() {
        DocumentTemplates templates = mock(DocumentTemplates.class);
        var missing = contribution("required", true, true);
        assertThrows(IllegalStateException.class, () -> seed(templates, List.of(missing)));
    }

    @Test
    void unavailableOptionalSeedDoesNotPreventStartup() {
        DocumentTemplates templates = mock(DocumentTemplates.class);
        var optional = contribution("optional", false, true);
        assertDoesNotThrow(() -> seed(templates, List.of(optional)));
    }

    private static InputStream fixture() {
        return java.util.Objects.requireNonNull(TemplateContributionTest.class.getResourceAsStream(
                "/template-fixtures/report.dotx"));
    }

    private static TemplateContribution contribution(String id, boolean required, boolean missing) {
        DocumentTemplateContract contract = new DocumentTemplateContract() {
            public String templateId() { return id; }
            public void validate(java.util.Map<String, byte[]> parts) {
                if (!new String(parts.get("word/document.xml"), java.nio.charset.StandardCharsets.UTF_8)
                        .contains(TemplateTestFixture.BODY_MARKER)) {
                    throw new IllegalArgumentException("Contributed marker missing");
                }
            }
        };
        return new TemplateContribution(id, id, () -> {
            if (missing) throw new IOException("Missing fixture");
            return fixture();
        }, contract, required);
    }

    private static void seed(DocumentTemplates templates, List<TemplateContribution> contributions) throws Exception {
        new DefaultDocumentTemplateBootstrap(templates, contributions).run(null);
    }

    @Test
    void creationRaceAcceptsWinnerWithoutRetryingTheMutation() throws Exception {
        DocumentTemplates templates = mock(DocumentTemplates.class);
        when(templates.exists("raced")).thenReturn(false, true);
        when(templates.upload(eq("raced"), any(), any(), isNull(), any(), any()))
                .thenThrow(new TemplateConflictException(null, "winner"));
        seed(templates, List.of(contribution("raced", true, false)));
        verify(templates, times(1)).upload(eq("raced"), any(), any(), isNull(), any(), any());
        verify(templates, times(2)).exists("raced");
        verifyNoMoreInteractions(templates);
    }

    @Test
    void contributedSemanticContractIsEnforcedOnUploads() throws Exception {
        try (var git = new InMemoryRepository(new DfsRepositoryDescription("rules"))) {
            var codec = new OoxmlTemplatePackageCodec();
            var templates = new DocumentTemplateService(new DocumentTemplateGitRepository(git), codec,
                    List.of(), List.of(contribution("rules", true, false)),
                    new OoxmlActiveContentValidator(), new DocumentTemplateMaterializationCache());
            java.util.Map<String, byte[]> parts;
            try (var input = fixture()) { parts = new java.util.LinkedHashMap<>(codec.unpack(input).parts()); }
            var charset = java.nio.charset.StandardCharsets.UTF_8;
            parts.put("word/document.xml", new String(parts.get("word/document.xml"), charset)
                    .replace(TemplateTestFixture.BODY_MARKER, "removed").getBytes(charset));
            try (var input = new java.io.ByteArrayInputStream(codec.pack(parts))) {
                assertThrows(IllegalArgumentException.class,
                        () -> templates.upload("rules", "Rules", input, null, "editor", "Invalid upload"));
            }
            assertFalse(templates.exists("rules"));
        }
    }

    @Test
    void previewsAreSelectedByFamilyAndTemplatesWorkWithoutAnyPreview() {
        var first = contribution("first", true, false);
        var second = contribution("second", true, false);
        var firstPreview = new TemplateContribution(first.templateId(), first.displayName(), first.content(),
                first.contract(), first.required(), () -> new byte[]{1}, "first.docx");
        var secondPreview = new TemplateContribution(second.templateId(), second.displayName(), second.content(),
                second.contract(), second.required(), () -> new byte[]{2}, "second.docx");
        var templates = mock(DocumentTemplates.class);
        var controller = new DocumentTemplateDetailController(templates, List.of(firstPreview, secondPreview));
        assertArrayEquals(new byte[]{1}, controller.testReport("first").getBody());
        assertArrayEquals(new byte[]{2}, controller.testReport("second").getBody());
        assertTrue(controller.testReport("second").getHeaders().getContentDisposition().getFilename().equals("second.docx"));
        var withoutPreview = new DocumentTemplateDetailController(templates, List.of());
        assertThrows(IllegalArgumentException.class, () -> withoutPreview.testReport("first"));
        verifyNoInteractions(templates);
    }
}
