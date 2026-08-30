package com.taxonomy.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionRationaleTemplateTokenContractTest {

    private final OoxmlTemplatePackageCodec codec = new OoxmlTemplatePackageCodec();
    private final DecisionRationaleTemplateContract contract =
            new DecisionRationaleTemplateContract();
    private Map<String, byte[]> parts;

    @BeforeEach
    void loadTemplate() {
        try (InputStream input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            parts = new LinkedHashMap<>(codec.unpack(input).parts());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void acceptsTheBundledSupportedTokenStories() {
        contract.validate(parts);
    }

    @Test
    void rejectsUnknownAndMalformedTaxonomyTokens() {
        String document = text("word/document.xml");
        parts.put(
                "word/document.xml",
                insertBeforeBodyMarker(
                        document,
                        paragraph("{{taxonomy.report.unknown}}")));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("unknown Taxonomy template token")
                .hasMessageContaining("{{taxonomy.report.unknown}}");

        loadTemplate();
        document = text("word/document.xml");
        parts.put(
                "word/document.xml",
                insertBeforeBodyMarker(
                        document,
                        paragraph("{{taxonomy.report.broken")));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("malformed Taxonomy token")
                .hasMessageContaining("word/document.xml");
    }

    @Test
    void rejectsSupportedTokensInsideTextBoxesAndContentControls() {
        String document = text("word/document.xml");
        String textBox = "<w:p><w:r><w:drawing><w:txbxContent>"
                + paragraph("{{taxonomy.template.id}}")
                + "</w:txbxContent></w:drawing></w:r></w:p>";
        parts.put(
                "word/document.xml",
                insertBeforeBodyMarker(document, textBox));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("unsupported Word container")
                .hasMessageContaining("{{taxonomy.template.id}}");

        loadTemplate();
        document = text("word/document.xml");
        String contentControl = "<w:sdt><w:sdtContent>"
                + paragraph("{{taxonomy.template.sha256}}")
                + "</w:sdtContent></w:sdt>";
        parts.put(
                "word/document.xml",
                insertBeforeBodyMarker(document, contentControl));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("unsupported Word container")
                .hasMessageContaining("{{taxonomy.template.sha256}}");
    }

    @Test
    void rejectsTokensInUnsupportedWordStoriesAndPackageMetadata() {
        parts.put(
                "word/footnotes.xml",
                ("<w:footnotes xmlns:w=\""
                        + "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        + "\"><w:footnote w:id=\"1\">"
                        + paragraph("{{taxonomy.report.title}}")
                        + "</w:footnote></w:footnotes>")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("not supported in Word story")
                .hasMessageContaining("word/footnotes.xml");

        loadTemplate();
        String core = text("docProps/core.xml").replace(
                "</cp:coreProperties>",
                "<dc:identifier>{{taxonomy.report.commit}}</dc:identifier>"
                        + "</cp:coreProperties>");
        parts.put("docProps/core.xml", core.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("not supported in OOXML part")
                .hasMessageContaining("docProps/core.xml");
    }

    @Test
    void rejectsUtf16EncodedTokensAndAttributeMarkers() {
        String footnotes = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
                + "<w:footnotes xmlns:w=\""
                + "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                + "\"><w:footnote w:id=\"1\">"
                + paragraph("{{taxonomy.report.title}}")
                + "</w:footnote></w:footnotes>";
        parts.put("word/footnotes.xml", footnotes.getBytes(StandardCharsets.UTF_16));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("not supported in Word story")
                .hasMessageContaining("word/footnotes.xml");

        loadTemplate();
        String document = text("word/document.xml");
        parts.put(
                "word/document.xml",
                insertBeforeBodyMarker(
                        document,
                        "<w:p w:rsidR=\"{{taxonomy.report.commit}}\">"
                                + "<w:r><w:t>ordinary text</w:t></w:r></w:p>"));

        assertThatThrownBy(() -> contract.validate(parts))
                .hasMessageContaining("XML attribute")
                .hasMessageContaining("word/document.xml");
    }

    private String text(String path) {
        assertThat(parts).containsKey(path);
        return new String(parts.get(path), StandardCharsets.UTF_8);
    }

    private static String paragraph(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static byte[] insertBeforeBodyMarker(String xml, String addition) {
        int marker = xml.indexOf(DecisionRationaleTemplateContract.BODY_MARKER);
        assertThat(marker).isGreaterThanOrEqualTo(0);
        int paragraph = xml.lastIndexOf("<w:p>", marker);
        assertThat(paragraph).isGreaterThanOrEqualTo(0);
        return (xml.substring(0, paragraph) + addition + xml.substring(paragraph))
                .getBytes(StandardCharsets.UTF_8);
    }
}
