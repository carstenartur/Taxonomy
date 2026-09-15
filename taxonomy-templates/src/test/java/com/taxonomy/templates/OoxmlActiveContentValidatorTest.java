package com.taxonomy.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OoxmlActiveContentValidatorTest {

    private final OoxmlTemplatePackageCodec codec = new OoxmlTemplatePackageCodec();
    private final OoxmlActiveContentValidator validator =
            new OoxmlActiveContentValidator();
    private Map<String, byte[]> parts;

    @BeforeEach
    void loadTemplate() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            parts = new LinkedHashMap<>(codec.unpack(input).parts());
        }
    }

    @Test
    void rejectsDdeAutoSplitAcrossInstructionRunsWithoutEchoingPayload() {
        String xml = text("word/document.xml");
        String fields = "<w:p><w:r><w:instrText>D</w:instrText></w:r>"
                + "<w:r><w:instrText>DEAUTO private-command</w:instrText></w:r></w:p>";
        parts.put("word/document.xml", insertBefore(xml, "</w:body>", fields));

        assertThatThrownBy(() -> validator.validate(parts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DDEAUTO")
                .hasMessageContaining("word/document.xml")
                .hasMessageNotContaining("private-command");
    }

    @Test
    void rejectsIncludeTextAndAlternativeFormatRelationships() {
        String xml = text("word/document.xml");
        parts.put("word/document.xml", insertBefore(xml, "</w:body>",
                "<w:p><w:fldSimple w:instr=\" INCLUDETEXT https://secret.invalid/x \"/></w:p>"));
        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("INCLUDETEXT")
                .hasMessageNotContaining("secret.invalid");

        loadFreshParts();
        String rels = text("word/_rels/document.xml.rels");
        parts.put("word/_rels/document.xml.rels", insertBefore(rels, "</Relationships>",
                "<Relationship Id=\"unsafe\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/aFChunk\" Target=\"chunk.html\"/>"));
        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("alternative-format relationship");
    }

    @Test
    void rejectsFileAndCustomProtocolHyperlinks() {
        for (String target : new String[]{"file:///etc/passwd", "smb://host/share",
                "ms-msdt:/id PCWDiagnostic"}) {
            loadFreshParts();
            String rels = text("word/_rels/document.xml.rels");
            String relation = "<Relationship Id=\"unsafe\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\""
                    + target + "\" TargetMode=\"External\"/>";
            parts.put("word/_rels/document.xml.rels",
                    insertBefore(rels, "</Relationships>", relation));
            assertThatThrownBy(() -> validator.validate(parts))
                    .as(target)
                    .hasMessageContaining("hyperlink scheme");
        }
    }

    @Test
    void acceptsOrdinaryFieldsAndHttpsOrMailtoHyperlinks() {
        String xml = text("word/document.xml");
        parts.put("word/document.xml", insertBefore(xml, "</w:body>",
                "<w:p><w:fldSimple w:instr=\" PAGE \"/><w:fldSimple w:instr=\" NUMPAGES \"/>"
                        + "<w:fldSimple w:instr=\" DATE \\@ yyyy-MM-dd \"/>"
                        + "<w:fldSimple w:instr=\" REF internalBookmark \"/></w:p>"));
        String rels = text("word/_rels/document.xml.rels");
        String links = "<Relationship Id=\"https\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://example.org/help\" TargetMode=\"External\"/>"
                + "<Relationship Id=\"mail\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"mailto:office@example.org\" TargetMode=\"External\"/>";
        parts.put("word/_rels/document.xml.rels",
                insertBefore(rels, "</Relationships>", links));

        validator.validate(parts);
    }

    private void loadFreshParts() {
        try (InputStream input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            parts = new LinkedHashMap<>(codec.unpack(input).parts());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private String text(String path) {
        assertThat(parts).containsKey(path);
        return new String(parts.get(path), StandardCharsets.UTF_8);
    }

    private static byte[] insertBefore(String xml, String closing, String addition) {
        int position = xml.lastIndexOf(closing);
        assertThat(position).as(closing).isGreaterThanOrEqualTo(0);
        return (xml.substring(0, position) + addition + xml.substring(position))
                .getBytes(StandardCharsets.UTF_8);
    }
}
