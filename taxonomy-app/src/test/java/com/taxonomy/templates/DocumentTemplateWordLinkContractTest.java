package com.taxonomy.templates;

import com.taxonomy.shared.config.I18nConfig;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateWordLinkContractTest {

    private static final String COMMIT_ID =
            "0123456789abcdef0123456789abcdef01234567";

    @Mock
    private DocumentTemplateService templates;

    @Mock
    private DocumentTemplateWebDavLockManager locks;

    @Test
    void managementPageLoadsTheOfficeUriHelperBeforeItsWorkspaceController()
            throws Exception {
        String page = resource("/templates/document-templates.html");

        assertThat(page)
                .contains("data-webdav-base=@{/dav/templates/}")
                .contains("application/vnd.openxmlformats-officedocument.wordprocessingml.template")
                .contains("id=\"documentTemplateRows\"")
                .contains("id=\"documentTemplateHistoryModal\"");
        assertThat(page.indexOf("/js/document-templates/word-template-links.js"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(page.indexOf("/js/document-templates/document-templates.js"));
    }

    @Test
    void officeUriHelperUsesTheMicrosoftEditAndNewFromTemplateSchemes() throws Exception {
        String helper = resource(
                "/static/js/document-templates/word-template-links.js");

        assertThat(helper)
                .contains("const WORD_EDIT_PREFIX = 'ms-word:ofe|u|';")
                .contains("const WORD_NEW_FROM_TEMPLATE_PREFIX = 'ms-word:nft|u|';")
                .contains("anchor.setAttribute('href', officeUri);")
                .contains("anchor.setAttribute('target', '_blank');")
                .contains("anchor.setAttribute('rel', 'noopener noreferrer');")
                .contains("anchor.setAttribute('type', DOTX_MEDIA_TYPE);")
                .contains(OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE);
    }

    @Test
    void workspaceBuildsAbsoluteWordLinksFromTheWebDavResourceAndKeepsFallbacks()
            throws Exception {
        String workspace = resource(
                "/static/js/document-templates/document-templates.js");

        assertThat(workspace)
                .contains("new URL(webDavBase, window.location.href)")
                .contains("new URL(encodeURIComponent(template.fileName), base).href")
                .contains("links.configureWordLink(editLink, webDavUrl, 'edit');")
                .contains("links.configureWordLink(newLink, webDavUrl, 'new');")
                .contains("download.href = templateDownloadUrl(template.templateId);")
                .contains("copyAddress(webDavUrl)")
                .doesNotContain("access_token")
                .doesNotContain("?token=")
                .doesNotContain("Authorization");
    }

    @Test
    void springSelectsTheProductionRepositoryConstructor() throws Exception {
        var constructor = DocumentTemplateGitRepository.class
                .getConstructor(HibernateRepositoryFactory.class);

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void webDavGetReturnsTheRegisteredDotxMediaTypeAndInlineFileName()
            throws Exception {
        TemplateFile file = templateFile();
        when(templates.downloadCurrent("decision-report")).thenReturn(file);

        DocumentTemplateWebDavServlet servlet =
                new DocumentTemplateWebDavServlet(templates, locks);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/dav/templates/decision-report.dotx");
        request.setPathInfo("/decision-report.dotx");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType())
                .isEqualTo(OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE);
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("inline; filename=\"decision-report.dotx\"");
        assertThat(response.getHeader("ETag"))
                .isEqualTo("\"" + COMMIT_ID + "\"");
        assertThat(response.getContentAsByteArray()).containsExactly(file.content());
    }

    @Test
    void adminDownloadReturnsQuotedGitEtagAndDotxMediaType() throws Exception {
        TemplateFile file = templateFile();
        when(templates.downloadCurrent("decision-report")).thenReturn(file);

        var response = new DocumentTemplateAdminController(templates)
                .download("decision-report", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("ETag"))
                .isEqualTo("\"" + COMMIT_ID + "\"");
        assertThat(response.getHeaders().getContentType())
                .hasToString(OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE);
        assertThat(response.getBody()).containsExactly(file.content());
    }

    @Test
    void webDavRejectsTemplateWritesWithoutArchitectOrAdministratorRole()
            throws Exception {
        DocumentTemplateWebDavServlet servlet =
                new DocumentTemplateWebDavServlet(
                        templates,
                        new DocumentTemplateWebDavLockManager());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "LOCK", "/dav/templates/decision-report.dotx");
        request.setPathInfo("/decision-report.dotx");
        request.setUserPrincipal(principal("reader"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void architectCanAcquireAWebDavTemplateWriteLock() throws Exception {
        DocumentTemplateWebDavServlet servlet =
                new DocumentTemplateWebDavServlet(
                        templates,
                        new DocumentTemplateWebDavLockManager());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "LOCK", "/dav/templates/decision-report.dotx");
        request.setPathInfo("/decision-report.dotx");
        request.setUserPrincipal(principal("architect"));
        request.addUserRole("ARCHITECT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Lock-Token"))
                .startsWith("<opaquelocktoken:")
                .endsWith(">");
        assertThat(response.getContentType()).isEqualTo("application/xml;charset=UTF-8");
    }

    @Test
    void pageControllerAndMessageSourceExposeTheWorkspace() throws Exception {
        assertThat(new DocumentTemplatePageController().documentTemplates())
                .isEqualTo("document-templates");
        assertThat(I18nConfig.MESSAGE_BASENAMES)
                .contains("messages_document_templates");
        assertThat(resource("/i18n/messages_document_templates.properties"))
                .contains("document.templates.action.edit=");
        assertThat(resource("/i18n/messages_document_templates_de.properties"))
                .contains("document.templates.action.edit=");
    }

    private static TemplateFile templateFile() {
        TemplateManifest manifest = new TemplateManifest(
                1,
                "decision-report",
                "Decision report",
                "decision-report.dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                "2026-08-22T12:00:00Z",
                "alice",
                4,
                3,
                "package-sha");
        return new TemplateFile(
                manifest,
                COMMIT_ID,
                new byte[]{1, 2, 3, 4},
                Instant.parse("2026-08-22T12:00:00Z"));
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private static String resource(String path) throws IOException {
        assertThatCode(() -> DocumentTemplateWordLinkContractTest.class
                .getResourceAsStream(path)).doesNotThrowAnyException();
        try (InputStream input = DocumentTemplateWordLinkContractTest.class
                .getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
