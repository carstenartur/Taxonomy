package com.taxonomy.shared.controller;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

/** Executes the actual help controller against small packaged-document fixtures. */
public final class HelpHeadingAssertions {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String render(String markdown) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var previousLocale = LocaleContextHolder.getLocaleContext();
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        URL url = URL.of(URI.create("test-resource:/USER_GUIDE.md"), new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL source) {
                return new URLConnection(source) {
                    @Override public void connect() { }
                    @Override public long getContentLengthLong() { return bytes.length; }
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                };
            }
        });
        ClassLoader loader = new ClassLoader(previous) {
            @Override public URL getResource(String name) {
                return name.equals("docs/en/USER_GUIDE.md") ? url : super.getResource(name);
            }
        };
        try {
            Thread.currentThread().setContextClassLoader(loader);
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            var response = new HelpController(new StaticMessageSource()).getDoc("USER_GUIDE");
            require(response.getStatusCode().value() == 200, "Fixture must render through the real controller");
            return response.getBody();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            LocaleContextHolder.setLocaleContext(previousLocale);
        }
    }

    public static void englishFragments() throws Exception {
        String html = render("[Jump](#getting-started)\n\n## Getting Started\n\nKeep this paragraph.\n");
        require(html.contains("href=\"#getting-started\""), "Authored link must remain intact");
        require(html.contains("<h2 id=\"getting-started\">Getting Started</h2>"), "Missing English heading target");
        require(html.contains("<p>Keep this paragraph.</p>"), "Body content changed");
    }
    public static void unicodeFragments() throws Exception {
        String html = render("[Sprung](#überblick)\n\n## Überblick\n");
        require(html.contains("id=\"überblick\""), "Missing lowercase Unicode heading target");
    }
    public static void repeatedHeadings() throws Exception {
        String html = render("## Overview\n\n## Overview\n\n## Overview\n");
        for (String id : List.of("overview", "overview-1", "overview-2")) {
            require(Pattern.compile("id=\"" + Pattern.quote(id) + "\"").matcher(html).results().count() == 1,
                    "Repeated headings need unique target: " + id);
        }
    }
    public static void explicitAnchorsAndCode() throws Exception {
        String html = render("<a id=\"stable-contract\"></a>\n\n## A heading\n\n```text\n## Not a heading\n```\n");
        require(html.contains("<a id=\"stable-contract\"></a>"), "Explicit compatibility anchor changed");
        require(html.contains("<code class=\"language-text\">## Not a heading\n</code>"), "Fenced code changed");
        require(!html.contains("id=\"not-a-heading\""), "Fenced text must not acquire heading IDs");
    }
    public static void perDocumentIdentity() throws Exception {
        String source = "## Overview\n\n## Overview\n";
        require(render(source).equals(render(source)), "Heading counters must be local to each rendering");
    }
    public static void main(String[] args) throws Exception {
        int failed = 0;
        for (String name : List.of("englishFragments", "unicodeFragments", "repeatedHeadings", "explicitAnchorsAndCode", "perDocumentIdentity")) {
            try { HelpHeadingAssertions.class.getMethod(name).invoke(null); System.out.println("PASS " + name); }
            catch (java.lang.reflect.InvocationTargetException error) {
                failed++; System.out.println("FAIL " + name + ": " + error.getCause());
            }
        }
        System.out.println("RESULT tests=5 failed=" + failed);
        if (failed != 0) System.exit(1);
    }
}
