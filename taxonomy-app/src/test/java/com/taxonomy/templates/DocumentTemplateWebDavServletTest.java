package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.Principal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateWebDavServletTest {

    private static final String COMMIT_A =
            "0123456789abcdef0123456789abcdef01234567";
    private static final String COMMIT_B =
            "89abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private DocumentTemplateService templates;

    @Test
    void propfindAdvertisesThePackedRepresentationLengthAndParsesAsXml()
            throws Exception {
        DocumentTemplateWebDavLockManager locks =
                new DocumentTemplateWebDavLockManager();
        TemplateFile file = templateFile(COMMIT_A, new byte[]{1, 2, 3, 4});
        when(templates.list()).thenReturn(List.of(descriptor(COMMIT_A)));
        when(templates.downloadCurrent("decision-report")).thenReturn(file);

        MockHttpServletRequest request = request(
                "PROPFIND", "/dav/templates/", "/");
        request.addHeader("Depth", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet(locks).service(request, response);

        assertThat(response.getStatus()).isEqualTo(207);
        Document xml = parse(response.getContentAsByteArray());
        assertThat(xml.getElementsByTagNameNS("DAV:", "getcontentlength")
                .item(0).getTextContent()).isEqualTo("4");
        assertThat(xml.getElementsByTagNameNS("DAV:", "getetag")
                .item(0).getTextContent()).isEqualTo("\"" + COMMIT_A + "\"");
        assertThat(xml.getElementsByTagNameNS("DAV:", "href")
                .item(1).getTextContent())
                .isEqualTo("/dav/templates/decision-report.dotx");
    }

    @Test
    void conditionalGetKeepsValidatorsOnNotModifiedResponse() throws Exception {
        TemplateFile file = templateFile(COMMIT_A, new byte[]{1, 2, 3, 4});
        when(templates.downloadCurrent("decision-report")).thenReturn(file);
        MockHttpServletRequest request = request(
                "GET", "/dav/templates/decision-report.dotx",
                "/decision-report.dotx");
        request.addHeader("If-None-Match", "\"" + COMMIT_A + "\"");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet(new DocumentTemplateWebDavLockManager())
                .service(request, response);

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getHeader("ETag"))
                .isEqualTo("\"" + COMMIT_A + "\"");
        assertThat(response.getHeader("Last-Modified")).isNotBlank();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void lockPutAndUnlockAdvanceOneVersionWithoutPostCommitFailure()
            throws Exception {
        DocumentTemplateWebDavLockManager locks =
                new DocumentTemplateWebDavLockManager();
        TemplateFile file = templateFile(COMMIT_A, new byte[]{1, 2, 3, 4});
        when(templates.downloadCurrent("decision-report")).thenReturn(file);
        when(templates.upload(
                eq("decision-report"),
                eq("Decision report"),
                any(InputStream.class),
                eq(COMMIT_A),
                eq("admin"),
                eq("Update document template through WebDAV")))
                .thenReturn(descriptor(COMMIT_B));

        MockHttpServletRequest lockRequest = request(
                "LOCK", "/dav/templates/decision-report.dotx",
                "/decision-report.dotx");
        authenticateAdmin(lockRequest, "admin");
        MockHttpServletResponse lockResponse = new MockHttpServletResponse();
        servlet(locks).service(lockRequest, lockResponse);
        String token = lockResponse.getHeader("Lock-Token");
        assertThat(token).startsWith("<opaquelocktoken:");

        MockHttpServletRequest putRequest = request(
                "PUT", "/dav/templates/decision-report.dotx",
                "/decision-report.dotx");
        authenticateAdmin(putRequest, "admin");
        putRequest.addHeader("If", "(" + token + ")");
        putRequest.setContent(new byte[]{9, 8, 7});
        MockHttpServletResponse putResponse = new MockHttpServletResponse();
        servlet(locks).service(putRequest, putResponse);

        assertThat(putResponse.getStatus()).isEqualTo(204);
        assertThat(putResponse.getHeader("ETag"))
                .isEqualTo("\"" + COMMIT_B + "\"");
        assertThat(locks.require("decision-report", token, "admin").currentCommit())
                .isEqualTo(COMMIT_B);

        MockHttpServletRequest unlockRequest = request(
                "UNLOCK", "/dav/templates/decision-report.dotx",
                "/decision-report.dotx");
        authenticateAdmin(unlockRequest, "admin");
        unlockRequest.addHeader("Lock-Token", token);
        MockHttpServletResponse unlockResponse = new MockHttpServletResponse();
        servlet(locks).service(unlockRequest, unlockResponse);
        assertThat(unlockResponse.getStatus()).isEqualTo(204);
        assertThat(locks.find("decision-report")).isNull();
    }

    @Test
    void createReturnsCanonicalContextRelativeLocation() throws Exception {
        when(templates.downloadCurrent("new-template"))
                .thenThrow(new TemplateNotFoundException("new-template", null));
        when(templates.upload(
                eq("new-template"),
                eq("new-template"),
                any(InputStream.class),
                isNull(),
                eq("admin"),
                eq("Update document template through WebDAV")))
                .thenReturn(new TemplateDescriptor(
                        "new-template",
                        "New template",
                        "new-template.dotx",
                        COMMIT_A,
                        "2026-08-22T12:00:00Z",
                        "admin",
                        10,
                        3,
                        "package-sha"));

        MockHttpServletRequest request = request(
                "PUT", "/taxonomy/dav/templates/new-template.dotx",
                "/new-template.dotx");
        request.setContextPath("/taxonomy");
        authenticateAdmin(request, "admin");
        request.setContent(new byte[]{1, 2, 3});
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet(new DocumentTemplateWebDavLockManager())
                .service(request, response);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Location"))
                .isEqualTo("/taxonomy/dav/templates/new-template.dotx");
    }

    @Test
    void architectCannotBypassTheAdminOnlyTemplateManagementBoundary()
            throws Exception {
        MockHttpServletRequest request = request(
                "LOCK", "/dav/templates/decision-report.dotx",
                "/decision-report.dotx");
        request.setUserPrincipal(principal("architect"));
        request.addUserRole("ARCHITECT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet(new DocumentTemplateWebDavLockManager())
                .service(request, response);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private DocumentTemplateWebDavServlet servlet(
            DocumentTemplateWebDavLockManager locks) {
        return new DocumentTemplateWebDavServlet(templates, locks);
    }

    private static MockHttpServletRequest request(
            String method,
            String requestUri,
            String pathInfo) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setPathInfo(pathInfo);
        return request;
    }

    private static void authenticateAdmin(
            MockHttpServletRequest request,
            String name) {
        request.setUserPrincipal(principal(name));
        request.addUserRole("ADMIN");
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private static TemplateDescriptor descriptor(String commit) {
        return new TemplateDescriptor(
                "decision-report",
                "Decision report",
                "decision-report.dotx",
                commit,
                "2026-08-22T12:00:00Z",
                "alice",
                4,
                3,
                "package-sha");
    }

    private static TemplateFile templateFile(String commit, byte[] content) {
        TemplateManifest manifest = new TemplateManifest(
                1,
                "decision-report",
                "Decision report",
                "decision-report.dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                "2026-08-22T12:00:00Z",
                "alice",
                content.length,
                3,
                "package-sha");
        return new TemplateFile(
                manifest,
                commit,
                content,
                Instant.parse("2026-08-22T12:00:00Z"));
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
