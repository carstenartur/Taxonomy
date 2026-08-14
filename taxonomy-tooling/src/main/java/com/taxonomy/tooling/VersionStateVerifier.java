package com.taxonomy.tooling;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Verifies Maven, citation, archive and Helm metadata as one version state. */
public final class VersionStateVerifier {

    private static final Pattern CFF_VERSION = Pattern.compile(
            "(?m)^version: \\\"([^\\\"]+)\\\"$");
    private static final Pattern CFF_DATE = Pattern.compile(
            "(?m)^date-released: ");
    private static final Pattern CITATION_PREFERRED = Pattern.compile(
            "Taxonomy Architecture Analyzer\\*\\*\\. Version ([0-9A-Za-z.-]+)\\.");
    private static final Pattern CITATION_BIBTEX = Pattern.compile(
            "(?m)^\\s*version\\s+= \\{([^}]+)},$");
    private static final Pattern CITATION_DATE = Pattern.compile(
            "(?m)^\\s*date\\s+= \\{[^}]+},$");
    private static final Pattern CHART_VERSION = Pattern.compile(
            "(?m)^appVersion:\\s*[\\\"']?([^\\\"'\\s]+)[\\\"']?\\s*$");

    private VersionStateVerifier() {
    }

    public static Verification verify(
            Path root,
            String mode,
            String expectedVersion,
            String tag) throws IOException {
        Path repository = root.toAbsolutePath().normalize();
        String actual = XmlSupport.rootProjectVersion(repository.resolve("pom.xml"));
        String expected = expectedVersion == null || expectedVersion.isBlank()
                ? actual
                : expectedVersion.strip();
        List<String> failures = new ArrayList<>();

        if (!actual.equals(expected)) {
            failures.add("Root Maven version '" + actual
                    + "' != expected '" + expected + "'");
        }
        boolean releaseMode;
        if ("release".equals(mode)) {
            releaseMode = true;
            if (!VersionNumbers.RELEASE.matcher(actual).matches()) {
                failures.add("Version '" + actual
                        + "' is not a valid release version");
            }
        } else if ("development".equals(mode)) {
            releaseMode = false;
            if (!VersionNumbers.DEVELOPMENT.matcher(actual).matches()) {
                failures.add("Version '" + actual
                        + "' is not a valid development version");
            }
        } else {
            throw new IllegalArgumentException(
                    "mode must be development or release");
        }

        if (tag != null && !tag.isBlank()) {
            String expectedTag = "v" + actual;
            if (!releaseMode) {
                failures.add("A release tag is only valid in release mode");
            } else if (!tag.equals(expectedTag)) {
                failures.add("Tag '" + tag + "' != expected '" + expectedTag + "'");
            }
        }

        failures.addAll(pomFailures(repository, actual));
        failures.addAll(metadataFailures(repository, actual, releaseMode));
        return new Verification(actual, mode, List.copyOf(failures));
    }

    private static List<String> pomFailures(Path root, String expected)
            throws IOException {
        List<String> failures = new ArrayList<>();
        List<Path> poms;
        try (Stream<Path> paths = Files.walk(root)) {
            poms = paths.filter(Files::isRegularFile)
                    .filter(path -> "pom.xml".equals(path.getFileName().toString()))
                    .filter(path -> !contains(path, "target"))
                    .filter(path -> !contains(path, ".git"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        for (Path pom : poms) {
            Element project = XmlSupport.parse(pom).getDocumentElement();
            Element parent = XmlSupport.child(project, "parent");
            String parentGroup = XmlSupport.childText(parent, "groupId");
            String parentArtifact = XmlSupport.childText(parent, "artifactId");
            String parentVersion = XmlSupport.childText(parent, "version");
            if ("com.taxonomy".equals(parentGroup)
                    && "taxonomy".equals(parentArtifact)
                    && !expected.equals(parentVersion)) {
                failures.add(relative(root, pom) + " parent version '"
                        + emptyToNull(parentVersion) + "' != '" + expected + "'");
            }
            String group = XmlSupport.childText(project, "groupId");
            if (group.isBlank()) {
                group = parentGroup;
            }
            String projectVersion = XmlSupport.childText(project, "version");
            if ("com.taxonomy".equals(group)
                    && !projectVersion.isBlank()
                    && !expected.equals(projectVersion)) {
                failures.add(relative(root, pom) + " project version '"
                        + projectVersion + "' != '" + expected + "'");
            }
        }
        return failures;
    }

    private static List<String> metadataFailures(
            Path root,
            String expected,
            boolean releaseMode) throws IOException {
        List<String> failures = new ArrayList<>();

        String citation = read(root.resolve("CITATION.cff"));
        Matcher cffVersion = CFF_VERSION.matcher(citation);
        if (!cffVersion.find() || !expected.equals(cffVersion.group(1))) {
            failures.add("CITATION.cff version does not match the Maven version");
        }
        if (CFF_DATE.matcher(citation).find() != releaseMode) {
            failures.add(
                    "CITATION.cff release-date state does not match the requested mode");
        }

        String citationMarkdown = read(root.resolve("CITATION.md"));
        Matcher preferred = CITATION_PREFERRED.matcher(citationMarkdown);
        Matcher bibtex = CITATION_BIBTEX.matcher(citationMarkdown);
        if (!preferred.find() || !expected.equals(preferred.group(1))) {
            failures.add("CITATION.md preferred citation version does not match");
        }
        if (!bibtex.find() || !expected.equals(bibtex.group(1))) {
            failures.add("CITATION.md BibTeX version does not match");
        }
        if (CITATION_DATE.matcher(citationMarkdown).find() != releaseMode) {
            failures.add(
                    "CITATION.md release-date state does not match the requested mode");
        }

        checkJsonMetadata(
                root.resolve(".zenodo.json"),
                "version",
                "publication_date",
                expected,
                releaseMode,
                failures);
        checkJsonMetadata(
                root.resolve("codemeta.json"),
                "version",
                "datePublished",
                expected,
                releaseMode,
                failures);

        Path chart = root.resolve("deploy/helm/taxonomy/Chart.yaml");
        if (Files.isRegularFile(chart)) {
            Matcher chartVersion = CHART_VERSION.matcher(read(chart));
            if (!chartVersion.find() || !expected.equals(chartVersion.group(1))) {
                failures.add(
                        "Helm Chart.yaml appVersion does not match the Maven version");
            }
        }
        return failures;
    }

    private static void checkJsonMetadata(
            Path path,
            String versionKey,
            String dateKey,
            String expected,
            boolean releaseMode,
            List<String> failures) throws IOException {
        Map<String, Object> data = FlatJson.parseObject(read(path));
        if (!expected.equals(data.get(versionKey))) {
            failures.add(path.getFileName() + " version does not match");
        }
        if (data.containsKey(dateKey) != releaseMode) {
            failures.add(path.getFileName()
                    + " release-date state does not match the requested mode");
        }
    }

    private static boolean contains(Path path, String name) {
        for (Path component : path) {
            if (name.equals(component.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String emptyToNull(String value) {
        return value.isBlank() ? "null" : value;
    }

    public record Verification(
            String version,
            String mode,
            List<String> failures) {
        public boolean successful() {
            return failures.isEmpty();
        }
    }
}
