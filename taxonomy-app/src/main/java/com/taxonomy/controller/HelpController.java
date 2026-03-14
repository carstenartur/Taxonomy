package com.taxonomy.controller;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller that serves in-app documentation rendered from Markdown.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /help}              — returns TOC list as JSON</li>
 *   <li>{@code GET /help/{docName}}    — returns rendered HTML for a doc</li>
 *   <li>{@code GET /help/images/{imageName}} — serves images from classpath docs/images/</li>
 * </ul>
 */
@RestController
@RequestMapping("/help")
@Tag(name = "Help")
public class HelpController {

    private static final Logger log = LoggerFactory.getLogger(HelpController.class);

    /** Pattern that valid doc names must match — alphanumeric, hyphens, underscores only. */
    private static final java.util.regex.Pattern SAFE_DOC_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Pattern for safe image names — alphanumeric, hyphens, underscores, dots. */
    private static final java.util.regex.Pattern SAFE_IMAGE_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.\\-]+$");

    record DocEntry(String filename, String title, String icon, String audience) {}

    static final List<DocEntry> DOCS = List.of(
        new DocEntry("USER_GUIDE",              "User Guide",              "📖", "Everyone"),
        new DocEntry("CONCEPTS",                "Concepts",                "💡", "Everyone"),
        new DocEntry("EXAMPLES",                "Examples",                "📝", "Everyone"),
        new DocEntry("CONFIGURATION_REFERENCE", "Configuration Reference", "⚙️", "Admins"),
        new DocEntry("API_REFERENCE",           "API Reference",           "🔌", "Integrators"),
        new DocEntry("CURL_EXAMPLES",           "cURL Examples",           "💻", "Integrators"),
        new DocEntry("ARCHITECTURE",            "Architecture",            "🏗️", "Developers"),
        new DocEntry("DEVELOPER_GUIDE",         "Developer Guide",         "🛠️", "Developers"),
        new DocEntry("DEPLOYMENT_GUIDE",        "Deployment Guide",        "🚀", "DevOps"),
        new DocEntry("SECURITY",                "Security",                "🔒", "Admins"),
        new DocEntry("POSTGRESQL-SETUP",        "PostgreSQL Setup",        "🐘", "DevOps"),
        new DocEntry("MSSQL-SETUP",             "MSSQL Setup",             "🗄️", "DevOps")
    );

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public HelpController() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                AutolinkExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create()
        ));
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List available documentation files")
    public ResponseEntity<List<Map<String, String>>> listDocs() {
        List<Map<String, String>> result = DOCS.stream()
                .filter(d -> new ClassPathResource("docs/" + d.filename() + ".md").exists())
                .map(d -> Map.of(
                        "filename", d.filename(),
                        "title", d.title(),
                        "icon", d.icon(),
                        "audience", d.audience()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/{docName}", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Get rendered HTML for a documentation file")
    public ResponseEntity<String> getDoc(@PathVariable String docName) {
        if (!SAFE_DOC_NAME.matcher(docName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        // Only serve known docs
        boolean known = DOCS.stream().anyMatch(d -> d.filename().equals(docName));
        if (!known) {
            return ResponseEntity.notFound().build();
        }

        String html = cache.computeIfAbsent(docName, this::renderDoc);
        if (html == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok("<div class=\"help-doc-content\">" + html + "</div>");
    }

    @GetMapping(value = "/images/{imageName}")
    @Operation(summary = "Serve documentation images")
    public ResponseEntity<byte[]> getImage(@PathVariable String imageName) {
        if (!SAFE_IMAGE_NAME.matcher(imageName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        ClassPathResource resource = new ClassPathResource("docs/images/" + imageName);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            MediaType mediaType = guessMediaType(imageName);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(bytes);
        } catch (IOException e) {
            log.warn("Failed to read image {}: {}", imageName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String renderDoc(String docName) {
        ClassPathResource resource = new ClassPathResource("docs/" + docName + ".md");
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            String markdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Rewrite relative image paths to the /help/images/ endpoint
            markdown = markdown.replace("](images/", "](/help/images/");
            Node document = parser.parse(markdown);
            return renderer.render(document);
        } catch (IOException e) {
            log.warn("Failed to read doc {}: {}", docName, e.getMessage());
            return null;
        }
    }

    private static MediaType guessMediaType(String imageName) {
        String lower = imageName.toLowerCase();
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        if (lower.endsWith(".webp")) return MediaType.valueOf("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
