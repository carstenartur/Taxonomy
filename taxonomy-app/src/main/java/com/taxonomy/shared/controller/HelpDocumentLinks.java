package com.taxonomy.shared.controller;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** Resolves rendered documentation links relative to their packaged Markdown resource.
 *
 * <p>Rewriting HTML attributes after Markdown rendering leaves fenced examples and
 * inline code untouched and also covers reference links and authored HTML. Known
 * same-language documents remain in the help reader; supplementary files retain
 * their repository path. Application context prefixes belong to the client so
 * cached documents cannot leak the mount point of a previous request.
 */
final class HelpDocumentLinks {
    private static final String REPOSITORY = "https://github.com/carstenartur/Taxonomy/blob/main/";
    private static final String RAW_REPOSITORY = "https://raw.githubusercontent.com/carstenartur/Taxonomy/main/";
    private static final Pattern DOCUMENT = Pattern.compile("docs/(en|de)/([A-Za-z0-9_-]+)\\.md");
    private static final Pattern BUNDLED_IMAGE = Pattern.compile("docs/images/([A-Za-z0-9_.-]+)");

    private HelpDocumentLinks() { }

    static String rewrite(String html, String resourcePath, List<String> knownDocuments) {
        var document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        URI base = URI.create(REPOSITORY + resourcePath);
        for (var element : document.select("a[href],img[src],source[src]")) {
            String attribute = element.hasAttr("href") ? "href" : "src";
            String authored = element.attr(attribute);
            element.attr(attribute, resolve(authored, base, resourcePath, knownDocuments, attribute.equals("src")));
        }
        return document.body().html();
    }

    private static String resolve(String authored, URI base, String resourcePath,
            List<String> knownDocuments, boolean image) {
        if (authored.isBlank() || authored.startsWith("#") || authored.startsWith("/")) return authored;
        try {
            URI reference = URI.create(authored);
            if (reference.isAbsolute()) return authored;
            // URI.resolve treats a query-only reference as the containing directory;
            // such links must retain the current document, as browser navigation does.
            URI destination = reference.getRawPath().isEmpty() && reference.getRawQuery() != null
                    ? URI.create(base.toASCIIString() + authored).normalize()
                    : base.resolve(reference).normalize();
            String resolved = destination.toASCIIString();
            if (!resolved.startsWith(REPOSITORY)) return authored;
            String path = destination.getRawPath().substring(URI.create(REPOSITORY).getRawPath().length());
            String suffix = (destination.getRawQuery() == null ? "" : "?" + destination.getRawQuery())
                    + (destination.getRawFragment() == null ? "" : "#" + destination.getRawFragment());
            var asset = BUNDLED_IMAGE.matcher(path);
            if (asset.matches()) return "/help/images/" + asset.group(1) + suffix;
            var help = DOCUMENT.matcher(path);
            if (!image && help.matches() && knownDocuments.contains(help.group(2))) {
                String language = help.group(1);
                String name = help.group(2);
                if (resourcePath.startsWith("docs/" + language + "/") && destination.getRawQuery() == null) {
                    return name + ".md" + suffix;
                }
                String query = destination.getRawQuery();
                if (query == null || query.isEmpty()) query = "lang=" + language;
                else if (!Pattern.compile("(?:^|&)lang=").matcher(query).find()) query += "&lang=" + language;
                String fragment = destination.getFragment();
                String section = fragment == null || fragment.isEmpty() ? ""
                        : "/" + URLEncoder.encode(fragment, StandardCharsets.UTF_8).replace("+", "%20");
                return "/?" + query + "#help/" + name + section;
            }
            return image ? RAW_REPOSITORY + path + suffix : resolved;
        } catch (IllegalArgumentException invalidReference) {
            // A malformed authored URI is not a license to invent another destination.
            return authored;
        }
    }
}
