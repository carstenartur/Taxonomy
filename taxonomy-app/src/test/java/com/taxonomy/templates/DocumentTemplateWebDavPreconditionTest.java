package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
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

import java.io.InputStream;
import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTemplateWebDavPreconditionTest {

    private static final String COMMIT_A =
            "0123456789abcdef0123456789abcdef01234567";
    private static final String COMMIT_B =
            "89abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private DocumentTemplateService templates;

    @Test
    void ifMatchCannotCreateAMissingWebDavResource() throws Exception {
        String ifMatch = "\"" + COMMIT_A + "\"";
        when(templates.downloadCurrent("new-template"))
                .thenThrow(new TemplateNotFoundException("new-template", null));
        when(templates.resolveExpectedVersion("new-template", ifMatch))
                .thenThrow(new TemplateConflictException(ifMatch, null));

        MockHttpServletRequest request = request("new-template");
        request.addHeader("If-Match", ifMatch);
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet().service(request, response);

        assertThat(response.getStatus()).isEqualTo(412);
        verify(templates, never()).upload(
                eq("new-template"),
                any(),
                any(InputStream.class),
                any(),
                any(),
                any());
    }

    @Test
    void commaSeparatedIfMatchListCanSelectTheCurrentTemplateVersion()
            throws Exception {
        String ifMatch = "\"stale\", \"" + COMMIT_A + "\"";
        when(templates.downloadCurrent("decision-report"))
                .thenReturn(templateFile(COMMIT_A));
        when(templates.resolveExpectedVersion("decision-report", ifMatch))
                .thenReturn(COMMIT_A);
        when(templates.upload(
                eq("decision-report"),
                eq("Decision report"),
                any(InputStream.class),
                eq(COMMIT_A),
                eq("admin"),
                eq("Update document template through WebDAV")))
                .thenReturn(descriptor(COMMIT_B));

        MockHttpServletRequest request = request("decision-report");
        request.addHeader("If-Match", ifMatch);
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet().service(request, response);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("ETag"))
                .isEqualTo("\"" + COMMIT_B + "\"");
        verify(templates).resolveExpectedVersion("decision-report", ifMatch);
    }

    @Test
    void matchingIfNoneMatchPreventsReplacement() throws Exception {
        when(templates.downloadCurrent("decision-report"))
                .thenReturn(templateFile(COMMIT_A));

        MockHttpServletRequest request = request("decision-report");
        request.addHeader("If-None-Match", "*");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet().service(request, response);

        assertThat(response.getStatus()).isEqualTo(412);
        verify(templates, never()).upload(
                eq("decision-report"),
                any(),
                any(InputStream.class),
                any(),
                any(),
                any());
    }

    private DocumentTemplateWebDavServlet servlet() {
        return new DocumentTemplateWebDavServlet(
                templates,
                new DocumentTemplateWebDavLockManager());
    }

    private static MockHttpServletRequest request(String templateId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/dav/templates/" + templateId + ".dotx");
        request.setPathInfo("/" + templateId + ".dotx");
        request.setUserPrincipal(principal("admin"));
        request.addUserRole("ADMIN");
        request.setContent(new byte[]{1, 2, 3});
        return request;
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
                "admin",
                4,
                3,
                "package-sha");
    }

    private static TemplateFile templateFile(String commit) {
        TemplateManifest manifest = new TemplateManifest(
                1,
                "decision-report",
                "Decision report",
                "decision-report.dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                "2026-08-22T12:00:00Z",
                "admin",
                4,
                3,
                "package-sha");
        return new TemplateFile(
                manifest,
                commit,
                new byte[]{1, 2, 3, 4},
                Instant.parse("2026-08-22T12:00:00Z"));
    }
}
