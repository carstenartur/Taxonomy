package com.taxonomy.shared.controller;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the in-app Help tab: table of contents, rendered Markdown documents,
 * and documentation images.
 *
 * <p>Supports locale-aware document resolution:
 * <ol>
 *   <li>Try {@code docs/{lang}/{docName}.md}</li>
 *   <li>Fall back to {@code docs/en/{docName}.md}</li>
 * </ol>
 */
@Controller
@RequestMapping("/help")
public class HelpController {

    private static final Logger log = LoggerFactory.getLogger(HelpController.class);

    /** Only allow safe filename characters to prevent path traversal. */
    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Only allow safe image filenames (letters, digits, hyphens, underscores, dots). */
    private static final java.util.regex.Pattern SAFE_IMAGE = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]+$");

    record DocEntry(String filename, String title, String icon, String audience) {}

    /** Known documentation files with their default (English) metadata. */
    private static final List<String[]> DOC_METADATA = List.of(
        new String[]{"USER_GUIDE",               "📖", "help.toc.USER_GUIDE",                    "help.audience.everyone"},
        new String[]{"CONCEPTS",                  "💡", "help.toc.CONCEPTS",                      "help.audience.everyone"},
        new String[]{"EXAMPLES",                  "📝", "help.toc.EXAMPLES",                      "help.audience.everyone"},
        new String[]{"FRAMEWORK_IMPORT",          "📥", "help.toc.FRAMEWORK_IMPORT",              "help.audience.everyone"},
        new String[]{"GIT_INTEGRATION",           "🔀", "help.toc.GIT_INTEGRATION",               "help.audience.developers"},
        new String[]{"PREFERENCES",               "🎛️", "help.toc.PREFERENCES",                   "help.audience.admins"},
        new String[]{"AI_PROVIDERS",              "🤖", "help.toc.AI_PROVIDERS",                  "help.audience.everyone"},
        new String[]{"CONFIGURATION_REFERENCE",   "⚙️", "help.toc.CONFIGURATION_REFERENCE",       "help.audience.admins"},
        new String[]{"API_REFERENCE",             "🔌", "help.toc.API_REFERENCE",                 "help.audience.integrators"},
        new String[]{"CURL_EXAMPLES",             "💻", "help.toc.CURL_EXAMPLES",                 "help.audience.integrators"},
        new String[]{"ARCHITECTURE",              "🏗️", "help.toc.ARCHITECTURE",                  "help.audience.developers"},
        new String[]{"DEVELOPER_GUIDE",           "🛠️", "help.toc.DEVELOPER_GUIDE",               "help.audience.developers"},
        new String[]{"FEATURE_MATRIX",             "📋", "help.toc.FEATURE_MATRIX",                "help.audience.developers"},
        new String[]{"DEPLOYMENT_GUIDE",          "🚀", "help.toc.DEPLOYMENT_GUIDE",              "help.audience.devops"},
        new String[]{"SECURITY",                  "🔒", "help.toc.SECURITY",                      "help.audience.admins"},
        new String[]{"DATABASE_SETUP",            "🗄️", "help.toc.DATABASE_SETUP",                "help.audience.devops"},
        new String[]{"DEPLOYMENT_CHECKLIST",      "✅", "help.toc.DEPLOYMENT_CHECKLIST",           "help.audience.devops"},
        new String[]{"OPERATIONS_GUIDE",          "📋", "help.toc.OPERATIONS_GUIDE",              "help.audience.devops"},
        new String[]{"KEYCLOAK_SETUP",            "🔑", "help.toc.KEYCLOAK_SETUP",                "help.audience.devops"},
        new String[]{"KEYCLOAK_MIGRATION",        "🔄", "help.toc.KEYCLOAK_MIGRATION",            "help.audience.devops"},
        new String[]{"AI_TRANSPARENCY",           "🔍", "help.toc.AI_TRANSPARENCY",               "help.audience.everyone"},
        new String[]{"AI_LITERACY_CONCEPT",       "🎓", "help.toc.AI_LITERACY_CONCEPT",           "help.audience.everyone"},
        new String[]{"DATA_PROTECTION",           "🛡️", "help.toc.DATA_PROTECTION",               "help.audience.admins"},
        new String[]{"ACCESSIBILITY",             "♿", "help.toc.ACCESSIBILITY",                 "help.audience.everyone"},
        new String[]{"BSI_KI_CHECKLIST",          "📜", "help.toc.BSI_KI_CHECKLIST",              "help.audience.admins"},
        new String[]{"DIGITAL_SOVEREIGNTY",       "🏴", "help.toc.DIGITAL_SOVEREIGNTY",           "help.audience.admins"},
        new String[]{"USE_CASE_WISSENSKONSERVIERUNG", "📚", "help.toc.USE_CASE_WISSENSKONSERVIERUNG","help.audience.everyone"},
        new String[]{"VERWALTUNGSINTEGRATION",    "🏢", "help.toc.VERWALTUNGSINTEGRATION",        "help.audience.admins"},
        new String[]{"DOCUMENT_IMPORT",           "📄", "help.toc.DOCUMENT_IMPORT",               "help.audience.everyone"},
        new String[]{"UI_GAP_ANALYSIS",           "📊", "help.toc.UI_GAP_ANALYSIS",               "help.audience.developers"},
        new String[]{"WORKSPACE_VERSIONING",      "🔄", "help.toc.WORKSPACE_VERSIONING",          "help.audience.everyone"},
        new String[]{"REPOSITORY_TOPOLOGY",       "🔗", "help.toc.REPOSITORY_TOPOLOGY",           "help.audience.developers"},
        new String[]{"RELATION_SEEDS",            "🌱", "help.toc.RELATION_SEEDS",                "help.audience.developers"}
    );

    /** Set of known doc filenames for validation. */
    static final List<String> KNOWN_FILENAMES = DOC_METADATA.stream()
            .map(m -> m[0])
            .toList();

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final MessageSource messageSource;
    /** Cache key format: "locale:docName", e.g. "de:USER_GUIDE". */
    private final ConcurrentHashMap<String, String> htmlCache = new ConcurrentHashMap<>();

    public HelpController(MessageSource messageSource) {
        this.messageSource = messageSource;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
            TablesExtension.create(),
            AutolinkExtension.create(),
            StrikethroughExtension.create()
        ));
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    /** Returns the ordered table of contents as JSON, with locale-resolved titles. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<DocEntry> getToc() {
        Locale locale = LocaleContextHolder.getLocale();
        return DOC_METADATA.stream()
                .map(m -> new DocEntry(
                        m[0],
                        messageSource.getMessage(m[2], null, m[0], locale),
                        m[1],
                        messageSource.getMessage(m[3], null, "Everyone", locale)))
                .toList();
    }

    /** Renders a Markdown document to HTML, with locale-aware resolution. */
    @GetMapping(value = "/{docName}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> getDoc(@PathVariable String docName) {
        if (!SAFE_NAME.matcher(docName).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid document name");
        }
        // Only allow documents that exist in the known list
        boolean knownDoc = KNOWN_FILENAMES.contains(docName);
        if (!knownDoc) {
            return ResponseEntity.notFound().build();
        }
        Locale locale = LocaleContextHolder.getLocale();
        String cacheKey = locale.getLanguage() + ":" + docName;
        String html = htmlCache.computeIfAbsent(cacheKey, k -> renderDoc(docName, locale));
        if (html == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(html);
    }

    /** Serves images from classpath docs/images/. */
    @GetMapping("/images/{imageName}")
    @ResponseBody
    public ResponseEntity<byte[]> getImage(@PathVariable String imageName) {
        // Validate the full filename: only alphanumeric, hyphens, underscores, and dots allowed.
        // This prevents path traversal (no slashes, no "..") while allowing extensions like .png.
        if (!SAFE_IMAGE.matcher(imageName).matches() || imageName.contains("..")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        try {
            ClassPathResource resource = new ClassPathResource("docs/images/" + imageName);
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = resource.getInputStream().readAllBytes();
            MediaType mediaType = guessMediaType(imageName);
            return ResponseEntity.ok().contentType(mediaType).body(bytes);
        } catch (IOException e) {
            log.warn("Could not read image {}: {}", imageName, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves a document by locale, falling back through:
     * docs/{lang}/{docName}.md → docs/en/{docName}.md
     */
    private String renderDoc(String docName, Locale locale) {
        // 1. Try locale-specific path
        String localePath = "docs/" + locale.getLanguage() + "/" + docName + ".md";
        ClassPathResource localeResource = new ClassPathResource(localePath);
        if (localeResource.exists()) {
            return parseResource(localeResource, docName);
        }
        // 2. Fallback to English
        String enPath = "docs/en/" + docName + ".md";
        ClassPathResource enResource = new ClassPathResource(enPath);
        if (enResource.exists()) {
            return parseResource(enResource, docName);
        }
        return null;
    }

    private String parseResource(ClassPathResource resource, String docName) {
        try (InputStream in = resource.getInputStream()) {
            String markdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Rewrite relative image paths to absolute /help/images/... URLs
            markdown = markdown.replaceAll("\\(\\.\\./images/([^)]++)\\)", "(/help/images/$1)");
            // Also rewrite HTML <img src="../images/..."> tags
            markdown = markdown.replaceAll("src=\"\\.\\./images/([^\"]++)\"", "src=\"/help/images/$1\"");
            Node document = parser.parse(markdown);
            String body = renderer.render(document);
            return "<div class=\"help-doc-content\">" + body + "</div>";
        } catch (IOException e) {
            log.error("Failed to render help doc {}: {}", docName, e.getMessage());
            return null;
        }
    }

    private MediaType guessMediaType(String imageName) {
        String lower = imageName.toLowerCase();
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        if (lower.endsWith(".webp")) return MediaType.valueOf("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
