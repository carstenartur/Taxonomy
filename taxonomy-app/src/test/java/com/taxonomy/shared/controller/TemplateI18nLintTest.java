package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Linting tests that ensure i18n best practices are followed in templates
 * and documentation files.
 */
class TemplateI18nLintTest {

    /**
     * Every {@code card-header} div in the main template must use {@code th:text}
     * or {@code th:utext} so the text is translatable.
     */
    @Test
    void indexTemplateCardHeadersHaveThText() throws IOException {
        Path templatePath = Path.of("src/main/resources/templates/index.html");
        if (!Files.exists(templatePath)) {
            templatePath = Path.of("taxonomy-app/src/main/resources/templates/index.html");
        }
        String template = Files.readString(templatePath);

        // Match card-header divs that contain direct visible text (non-empty content
        // between tags). This intentionally only matches simple <div class="card-header">Text</div>
        // patterns — card-headers with nested <span> elements should use th:text on the spans.
        Pattern pattern = Pattern.compile(
                "<div[^>]*class=\"[^\"]*card-header[^\"]*\"[^>]*>[^<]+</div>");
        Matcher m = pattern.matcher(template);
        while (m.find()) {
            String match = m.group();
            if (!match.contains("th:text") && !match.contains("th:utext")) {
                fail("Card header without th:text found: " + match);
            }
        }
    }

    /**
     * English documentation in {@code docs/en/} must not contain known German
     * block-quote text that was accidentally left in.
     */
    @Test
    void englishDocsContainNoGermanBlockquotes() throws IOException {
        // docs/ is in the root project, not inside the taxonomy-app module
        Path enDir = Path.of("docs/en");
        if (!Files.isDirectory(enDir)) {
            enDir = Path.of("../docs/en");
        }
        assertThat(enDir).as("docs/en directory must exist").matches(Files::isDirectory);
        try (Stream<Path> files = Files.walk(enDir)) {
            files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    assertThat(content)
                            .as("File %s should not contain German text in docs/en/",
                                    p.getFileName())
                            .doesNotContain("Dieser Leitfaden")
                            .doesNotContain("Für Automatisierung und Skripting");
                } catch (IOException e) {
                    fail("Cannot read " + p, e);
                }
            });
        }
    }
}
