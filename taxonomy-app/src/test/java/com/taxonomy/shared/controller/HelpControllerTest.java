package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link HelpController}.
 *
 * <p>Includes structural-sync tests that catch the root cause of docs/code
 * drift: every {@code docs/en/*.md} file must be registered in
 * {@link HelpController#KNOWN_FILENAMES} and every registered name must have
 * a corresponding file and i18n keys.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.ai.gemini.api-key=",
    "spring.ai.openai.api-key=",
    "spring.ai.deepseek.api-key=",
    "spring.ai.qwen.api-key=",
    "spring.ai.llama.api-key=",
    "spring.ai.mistral.api-key="
})
@WithMockUser
class HelpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MessageSource messageSource;

    // ── Structural-sync tests ────────────────────────────────────────────────

    /** Sentinel default that MessageSource will never return for a real key. */
    private static final String MISSING_KEY_SENTINEL = "\0MISSING";

    /**
     * Every {@code .md} file in {@code docs/en/} must be registered in
     * {@link HelpController#KNOWN_FILENAMES}. Catches "new doc file added but
     * not registered in HelpController" -- the root cause of the 15-vs-31 mismatch.
     */
    @Test
    void everyEnglishDocFileIsRegistered() throws IOException {
        Set<String> filesOnDisk = discoverDocFiles();
        Set<String> registered = new TreeSet<>(HelpController.KNOWN_FILENAMES);
        assertEquals(registered, filesOnDisk,
                "docs/en/*.md files must match HelpController.KNOWN_FILENAMES exactly. "
                + "If you add a new doc, register it in DOC_METADATA and add help.toc.* keys.");
    }

    /**
     * Every registered doc must have a matching {@code help.toc.<name>} key in
     * both the English and German message bundles.  Prevents
     * "doc registered but i18n key forgotten" drift.
     */
    @Test
    void everyRegisteredDocHasI18nKeys() {
        for (String filename : HelpController.KNOWN_FILENAMES) {
            String key = "help.toc." + filename;
            String en = messageSource.getMessage(key, null, MISSING_KEY_SENTINEL, Locale.ENGLISH);
            String de = messageSource.getMessage(key, null, MISSING_KEY_SENTINEL, Locale.GERMAN);
            assertNotEquals(MISSING_KEY_SENTINEL, en,
                    "Missing English i18n key: " + key);
            assertNotEquals(MISSING_KEY_SENTINEL, de,
                    "Missing German i18n key: " + key);
        }
    }

    /**
     * Ensures that localized Markdown files in {@code docs/en/} and {@code docs/de/}
     * do not contain broken {@code images/...} references. Since the shared image
     * directory is at {@code docs/images/}, locale-folder docs must use
     * {@code ../images/...} instead.
     */
    @Test
    void noLocalizedDocContainsBrokenImagePaths() throws IOException {
        Pattern brokenMarkdownImg = Pattern.compile("\\(images/[^)]+\\)");
        Pattern brokenHtmlImg = Pattern.compile("src=\"images/[^\"]+\"");

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> violations = new ArrayList<>();

        for (String lang : List.of("en", "de")) {
            Resource[] resources = resolver.getResources("classpath:docs/" + lang + "/*.md");
            for (Resource r : resources) {
                String content;
                try (InputStream in = r.getInputStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String filename = r.getFilename();

                Matcher m1 = brokenMarkdownImg.matcher(content);
                while (m1.find()) {
                    violations.add("docs/" + lang + "/" + filename + ": broken markdown image ref " + m1.group());
                }

                Matcher m2 = brokenHtmlImg.matcher(content);
                while (m2.find()) {
                    violations.add("docs/" + lang + "/" + filename + ": broken HTML image ref " + m2.group());
                }
            }
        }

        assertEquals(List.of(), violations,
                "Localized docs must use ../images/... instead of images/... — "
                + "the shared image folder is at docs/images/, not docs/{lang}/images/.");
    }

    // ── Endpoint tests ───────────────────────────────────────────────────────

    @Test
    void tocReturnsJsonArray() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()", greaterThan(0)));
    }

    @Test
    void tocContainsUserGuide() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.filename == 'USER_GUIDE')].title").value("User Guide"));
    }

    @Test
    void tocContainsExpectedFields() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").exists())
                .andExpect(jsonPath("$[0].title").exists())
                .andExpect(jsonPath("$[0].icon").exists())
                .andExpect(jsonPath("$[0].audience").exists());
    }

    @Test
    void tocContainsAllKnownEntries() throws Exception {
        mockMvc.perform(get("/help").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(HelpController.KNOWN_FILENAMES.size()));
    }

    @Test
    void docRenderingReturnsHtml() throws Exception {
        mockMvc.perform(get("/help/USER_GUIDE").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<div class=\"help-doc-content\">")));
    }

    @Test
    void docRenderingContainsMarkdownContent() throws Exception {
        mockMvc.perform(get("/help/USER_GUIDE").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h")));
    }

    @Test
    void docRenderingRewritesImagePaths() throws Exception {
        // Verify that ../images/ references in Markdown are rewritten to /help/images/ in rendered HTML
        mockMvc.perform(get("/help/USER_GUIDE").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("src=\"/help/images/")))
                .andExpect(content().string(not(containsString("src=\"../images/"))));
    }

    @Test
    void unknownDocReturns404() throws Exception {
        mockMvc.perform(get("/help/NONEXISTENT_DOC").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void pathTraversalReturns400() throws Exception {
        mockMvc.perform(get("/help/..%2F..%2Fetc%2Fpasswd").accept(MediaType.TEXT_HTML))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void invalidDocNameWithSlashReturns400() throws Exception {
        mockMvc.perform(get("/help/foo%2Fbar"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void imageEndpointReturnsNotFoundForMissing() throws Exception {
        mockMvc.perform(get("/help/images/nonexistent-image.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void imagePathTraversalReturns400() throws Exception {
        // path segment with ".." should be rejected
        mockMvc.perform(get("/help/images/..%2Fsecret.txt"))
                .andExpect(status().is4xxClientError());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Scans the classpath for {@code docs/en/*.md} and returns the base names. */
    private Set<String> discoverDocFiles() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:docs/en/*.md");
        Set<String> names = new TreeSet<>();
        for (Resource r : resources) {
            String filename = r.getFilename();
            if (filename != null && filename.endsWith(".md")) {
                names.add(filename.substring(0, filename.length() - 3));
            }
        }
        return names;
    }
}
