package com.taxonomy.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OoxmlTemplatePrivacyValidatorTest {

    private final OoxmlTemplatePackageCodec codec = new OoxmlTemplatePackageCodec();
    private final OoxmlActiveContentValidator validator =
            new OoxmlActiveContentValidator();
    private Map<String, byte[]> parts;

    @BeforeEach
    void loadTemplate() {
        loadFreshParts();
    }

    @Test
    void acceptsTheControlledBundledTemplate() {
        validator.validate(parts);
    }

    @Test
    void rejectsCommentsAndTrackedChangesWithoutEchoingPrivateContent() {
        parts.put(
                "word/comments.xml",
                ("<w:comments xmlns:w=\""
                        + "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        + "\"><w:comment w:author=\"Private Person\">"
                        + "<w:p><w:r><w:t>private-review-note</w:t></w:r></w:p>"
                        + "</w:comment></w:comments>")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("comments")
                .hasMessageNotContaining("Private Person")
                .hasMessageNotContaining("private-review-note");

        loadFreshParts();
        String document = text("word/document.xml");
        String revision = "<w:p><w:ins w:author=\"Private Person\">"
                + "<w:r><w:t>private-revision-text</w:t></w:r></w:ins></w:p>";
        parts.put(
                "word/document.xml",
                insertBeforeBodyMarker(document, revision));

        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("ins")
                .hasMessageContaining("word/document.xml")
                .hasMessageNotContaining("Private Person")
                .hasMessageNotContaining("private-revision-text");
    }

    @Test
    void rejectsHiddenTextAndTrackedRevisionMode() {
        String document = text("word/document.xml");
        String hidden = "<w:p><w:r><w:rPr><w:vanish/></w:rPr>"
                + "<w:t>private-hidden-text</w:t></w:r></w:p>";
        parts.put(
                "word/document.xml",
                insertBeforeBodyMarker(document, hidden));

        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("hidden Word text")
                .hasMessageNotContaining("private-hidden-text");

        loadFreshParts();
        String settings = text("word/settings.xml");
        parts.put(
                "word/settings.xml",
                insertBefore(settings, "</w:settings>", "<w:trackRevisions/>"));

        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("tracked-revision mode")
                .hasMessageContaining("word/settings.xml");
    }

    @Test
    void rejectsUnsupportedPassivePackageParts() {
        for (String path : new String[] {
                "customXml/item1.xml",
                "docProps/custom.xml",
                "word/printerSettings/printerSettings1.bin",
                "docProps/thumbnail.jpeg"
        }) {
            loadFreshParts();
            parts.put(path, "private-metadata".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> validator.validate(parts))
                    .as(path)
                    .hasMessageContaining(path)
                    .hasMessageNotContaining("private-metadata");
        }
    }

    @Test
    void rejectsPersonalCoreAndExtendedProperties() {
        String core = text("docProps/core.xml").replace(
                "Taxonomy Architecture Analyzer",
                "Private Person");
        parts.put("docProps/core.xml", core.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("personal creator metadata")
                .hasMessageNotContaining("Private Person");

        loadFreshParts();
        String application = text("docProps/app.xml").replace(
                "<Company>Organisation</Company>",
                "<Company>Private Organisation</Company>");
        parts.put("docProps/app.xml", application.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(parts))
                .hasMessageContaining("company metadata")
                .hasMessageNotContaining("Private Organisation");
    }

    private void loadFreshParts() {
        try (InputStream input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            parts = new LinkedHashMap<>(codec.unpack(input).parts());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private String text(String path) {
        assertThat(parts).containsKey(path);
        return new String(parts.get(path), StandardCharsets.UTF_8);
    }

    private static byte[] insertBeforeBodyMarker(String xml, String addition) {
        int marker = xml.indexOf(DecisionRationaleTemplateContract.BODY_MARKER);
        assertThat(marker).isGreaterThanOrEqualTo(0);
        int paragraph = xml.lastIndexOf("<w:p>", marker);
        assertThat(paragraph).isGreaterThanOrEqualTo(0);
        return (xml.substring(0, paragraph) + addition + xml.substring(paragraph))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] insertBefore(String xml, String closing, String addition) {
        int position = xml.lastIndexOf(closing);
        assertThat(position).as(closing).isGreaterThanOrEqualTo(0);
        return (xml.substring(0, position) + addition + xml.substring(position))
                .getBytes(StandardCharsets.UTF_8);
    }
}
