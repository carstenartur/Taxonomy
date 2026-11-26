package com.taxonomy.shared.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises links in rendered production documents, including their actual heading targets. */
class HelpDocumentLinkTest {
    @Test
    void repositoryReferencesResolveFromTheActualDocumentDirectory() throws Exception {
        String html = HelpHeadingAssertions.render("""
                [Nested](../qa/standards-interoperability.md?plain=1#scope)
                [Source](../../pom.xml)
                [Unregistered](SUPPLEMENT.md#part)
                [Reference][reference]

                [reference]: ../dev/MAVEN_VERIFICATION.md#authoritative-verification
                """);
        var links = Jsoup.parseBodyFragment(html).select("a[href]");
        assertEquals("https://github.com/carstenartur/Taxonomy/blob/main/docs/qa/standards-interoperability.md?plain=1#scope", links.get(0).attr("href"));
        assertEquals("https://github.com/carstenartur/Taxonomy/blob/main/pom.xml", links.get(1).attr("href"));
        assertEquals("https://github.com/carstenartur/Taxonomy/blob/main/docs/en/SUPPLEMENT.md#part", links.get(2).attr("href"));
        assertEquals("https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/MAVEN_VERIFICATION.md#authoritative-verification", links.get(3).attr("href"));
    }

    @Test
    void registeredDocumentsStayInHelpAndExplicitLanguagesRemainExplicit() throws Exception {
        String html = HelpHeadingAssertions.render("""
                [Guide](./USER_GUIDE.md#guide-section-4)
                [German](../de/USER_GUIDE.md#guide-section-4)
                [Same document](#local)
                """);
        var links = Jsoup.parseBodyFragment(html).select("a[href]");
        assertEquals("USER_GUIDE.md#guide-section-4", links.get(0).attr("href"));
        assertEquals("/?lang=de#help/USER_GUIDE/guide-section-4", links.get(1).attr("href"));
        assertEquals("#local", links.get(2).attr("href"));
    }

    @Test
    void queryOnlyReferencesKeepTheCurrentDocumentAndRequestedSection() throws Exception {
        String html = HelpHeadingAssertions.render("""
                [German section](?lang=de#guide-section-4)
                [Current language](?view=compact#guide-section-4)
                [Whole document](?lang=de)
                """);
        var links = Jsoup.parseBodyFragment(html).select("a[href]");
        assertEquals("/?lang=de#help/USER_GUIDE/guide-section-4", links.get(0).attr("href"));
        assertEquals("/?view=compact&lang=en#help/USER_GUIDE/guide-section-4", links.get(1).attr("href"));
        assertEquals("/?lang=de#help/USER_GUIDE", links.get(2).attr("href"));
    }

    @Test
    void imageAndProvenanceLinksUseBundledAssetsWithoutRewritingCodeExamples() throws Exception {
        String html = HelpHeadingAssertions.render("""
                [Capture](../images/diagram-print-controls-de.png#detail)
                ![View](../images/diagram-print-controls-de.png)
                <a href="../images/documentation-screenshots.json">Provenance</a>

                ```markdown
                ![Example](../images/example.png)
                [Source](../dev/example.md)
                ```
                """);
        var document = Jsoup.parseBodyFragment(html);
        assertEquals("/help/images/diagram-print-controls-de.png#detail", document.select("a").get(0).attr("href"));
        assertEquals("/help/images/diagram-print-controls-de.png", document.selectFirst("img").attr("src"));
        assertEquals("/help/images/documentation-screenshots.json", document.select("a").get(1).attr("href"));
        assertTrue(document.selectFirst("code").text().contains("../images/example.png"));
        assertTrue(document.selectFirst("code").text().contains("../dev/example.md"));
    }

    @Test
    void externalAndApplicationLinksKeepTheirAuthoredDestinations() throws Exception {
        String html = HelpHeadingAssertions.render("""
                [External](https://example.org/doc.md?x=1#section)
                [Application](/architecture/editor)
                [Mail](mailto:maintainer@example.org)
                """);
        var links = Jsoup.parseBodyFragment(html).select("a[href]");
        assertEquals("https://example.org/doc.md?x=1#section", links.get(0).attr("href"));
        assertEquals("/architecture/editor", links.get(1).attr("href"));
        assertEquals("mailto:maintainer@example.org", links.get(2).attr("href"));
    }

    @Test
    void allRegisteredHelpLinksReachExistingLocalizedFragmentsAndAssets() {
        var previous = LocaleContextHolder.getLocaleContext();
        var controller = new HelpController(new StaticMessageSource());
        List<Executable> checks = new ArrayList<>();
        Pattern localDocument = Pattern.compile("^([A-Za-z0-9_-]+)\\.md(?:#(.*))?$");
        try {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                LocaleContextHolder.setLocale(locale);
                for (String name : HelpController.KNOWN_FILENAMES) {
                    var response = controller.getDoc(name);
                    assertEquals(200, response.getStatusCode().value(), locale + "/" + name);
                    var document = Jsoup.parseBodyFragment(response.getBody());
                    for (var link : document.select("a[href],img[src]")) {
                        String value = link.attr(link.hasAttr("href") ? "href" : "src");
                        String label = locale + "/" + name + " -> " + value;
                        var match = localDocument.matcher(value);
                        String fragment = null;
                        org.jsoup.nodes.Document target = null;
                        if (match.matches()) {
                            String body = controller.getDoc(match.group(1)).getBody();
                            target = Jsoup.parseBodyFragment(body == null ? "" : body);
                            fragment = match.group(2);
                        } else if (value.startsWith("#") && !value.startsWith("#help/")) {
                            target = document;
                            fragment = value.substring(1);
                        }
                        if (fragment != null && !fragment.isEmpty()) {
                            String decoded = java.net.URI.create("https://help.test/#" + fragment).getFragment();
                            boolean found = target.getElementById(decoded) != null;
                            checks.add(() -> assertTrue(found, label));
                        }
                        if (value.startsWith("/help/images/")) {
                            String file = value.substring("/help/images/".length()).split("[?#]", 2)[0];
                            int status = controller.getImage(file).getStatusCode().value();
                            checks.add(() -> assertEquals(200, status, label));
                        }
                        checks.add(() -> assertFalse(value.startsWith("../"), label));
                    }
                }
            }
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
        assertAll(checks);
    }
}
