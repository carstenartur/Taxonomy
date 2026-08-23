package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDocumentTemplateBootstrapTest {

    @Mock
    private DocumentTemplateService templates;

    @Mock
    private Resource resource;

    @Test
    void seedsTheBundledTemplateWhenTheRepositoryDoesNotContainIt() throws Exception {
        when(templates.exists(DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(false);
        when(resource.exists()).thenReturn(true);
        when(resource.getInputStream())
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(templates.upload(
                eq(DecisionRationaleTemplateContract.TEMPLATE_ID),
                eq(DecisionRationaleTemplateContract.DISPLAY_NAME),
                any(InputStream.class),
                isNull(),
                eq("taxonomy-bootstrap"),
                eq("Seed bundled decision rationale report template")))
                .thenReturn(descriptor());

        new DefaultDocumentTemplateBootstrap(templates, resource).seedIfMissing();

        verify(templates).upload(
                eq(DecisionRationaleTemplateContract.TEMPLATE_ID),
                eq(DecisionRationaleTemplateContract.DISPLAY_NAME),
                any(InputStream.class),
                isNull(),
                eq("taxonomy-bootstrap"),
                eq("Seed bundled decision rationale report template"));
    }

    @Test
    void neverOverwritesAnExistingOrganisationTemplate() throws Exception {
        when(templates.exists(DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(true);

        new DefaultDocumentTemplateBootstrap(templates, resource).seedIfMissing();

        verify(resource, never()).getInputStream();
        verify(templates, never()).upload(
                any(), any(), any(), any(), any(), any());
    }

    private static TemplateDescriptor descriptor() {
        return new TemplateDescriptor(
                DecisionRationaleTemplateContract.TEMPLATE_ID,
                DecisionRationaleTemplateContract.DISPLAY_NAME,
                DecisionRationaleTemplateContract.TEMPLATE_ID + ".dotx",
                "0123456789abcdef0123456789abcdef01234567",
                "2026-08-22T16:00:00Z",
                "taxonomy-bootstrap",
                1_024,
                10,
                "package-sha");
    }
}
