package com.taxonomy.templates;

import com.taxonomy.templates.api.DocumentTemplateContract;
import com.taxonomy.templates.api.DocumentTemplateReportPreview;
import com.taxonomy.templates.api.TemplateContribution;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Frozen OOXML fixture and minimal contributed semantics, independent of any report implementation. */
final class TemplateTestFixture implements DocumentTemplateContract {
    static final String TEMPLATE_ID = "fixture-report";
    static final String DISPLAY_NAME = "Fixture report";
    static final String DEFAULT_RESOURCE = "template-fixtures/report.dotx";
    static final String BODY_MARKER = "{{taxonomy.report.body}}";

    public String templateId() { return TEMPLATE_ID; }

    public void validate(Map<String, byte[]> parts) {
        if (!new String(parts.get("word/document.xml"), StandardCharsets.UTF_8).contains(BODY_MARKER)) {
            throw new IllegalArgumentException("Fixture requires " + BODY_MARKER);
        }
    }

    static List<TemplateContribution> contributions() { return contributions(null); }

    static List<TemplateContribution> contributions(DocumentTemplateReportPreview preview) {
        return List.of(new TemplateContribution(TEMPLATE_ID, DISPLAY_NAME,
                () -> TemplateTestFixture.class.getResourceAsStream("/" + DEFAULT_RESOURCE),
                new TemplateTestFixture(), true, preview, preview == null ? null : "fixture-test.docx"));
    }
}
