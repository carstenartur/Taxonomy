package com.taxonomy.controller;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the in-app Help tab: table of contents, rendered Markdown documents,
 * and documentation images.
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

    static final List<DocEntry> DOCS = List.of(
        new DocEntry("USER_GUIDE",               "User Guide",               "📖", "Everyone"),
        new DocEntry("CONCEPTS",                  "Concepts",                 "💡", "Everyone"),
        new DocEntry("EXAMPLES",                  "Examples",                 "📝", "Everyone"),
        new DocEntry("FRAMEWORK_IMPORT",          "Framework Import",         "📥", "Everyone"),
        new DocEntry("GIT_INTEGRATION",           "Git Integration",          "🔀", "Everyone"),
        new DocEntry("PREFERENCES",               "Preferences",              "⚙️", "Everyone"),
        new DocEntry("AI_PROVIDERS",              "AI Providers",             "🤖", "Everyone"),
        new DocEntry("CONFIGURATION_REFERENCE",   "Configuration Reference",  "⚙️", "Admins"),
        new DocEntry("API_REFERENCE",             "API Reference",            "🔌", "Integrators"),
        new DocEntry("CURL_EXAMPLES",             "cURL Examples",            "💻", "Integrators"),
        new DocEntry("ARCHITECTURE",              "Architecture",             "🏗️", "Developers"),
        new DocEntry("DEVELOPER_GUIDE",           "Developer Guide",          "🛠️", "Developers"),
        new DocEntry("DEPLOYMENT_GUIDE",          "Deployment Guide",         "🚀", "DevOps"),
        new DocEntry("SECURITY",                  "Security",                 "🔒", "Admins"),
        new DocEntry("DATABASE_SETUP",            "Database Setup",           "🗄️", "DevOps")
    );

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final ConcurrentHashMap<String, String> htmlCache = new ConcurrentHashMap<>();

    public HelpController() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
            TablesExtension.create(),
            AutolinkExtension.create(),
            StrikethroughExtension.create()
        ));
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    /** Returns the ordered table of contents as JSON. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<DocEntry> getToc() {
        return DOCS;
    }

    /** Renders a Markdown document to HTML. */
    @GetMapping(value = "/{docName}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> getDoc(@PathVariable String docName) {
        if (!SAFE_NAME.matcher(docName).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid document name");
        }
        // Only allow documents that exist in the known list
        boolean knownDoc = DOCS.stream().anyMatch(d -> d.filename().equals(docName));
        if (!knownDoc) {
            return ResponseEntity.notFound().build();
        }
        String html = htmlCache.computeIfAbsent(docName, this::renderDoc);
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

    private String renderDoc(String docName) {
        String path = "docs/" + docName + ".md";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            String markdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Rewrite relative image paths to absolute /help/images/... URLs
            markdown = markdown.replaceAll("\\(images/([^)]+)\\)", "(/help/images/$1)");
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
