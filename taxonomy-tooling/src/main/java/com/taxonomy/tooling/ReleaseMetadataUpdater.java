package com.taxonomy.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies one coherent release or development version to repository metadata. */
public final class ReleaseMetadataUpdater {

    static final String ORCID_ID = "0009-0005-1047-6381";
    static final String ORCID_URL = "https://orcid.org/" + ORCID_ID;

    private static final Pattern CFF_VERSION = Pattern.compile(
            "(?m)^version: .*$");
    private static final Pattern CFF_RELEASE_DATE = Pattern.compile(
            "(?m)^date-released: .*\\R?");
    private static final Pattern CITATION_PREFERRED = Pattern.compile(
            "(Carsten Hammer\\. \\*\\*Taxonomy Architecture Analyzer\\*\\*\\. "
                    + "Version )[0-9A-Za-z.-]+(\\. [0-9]{4}\\.)");
    private static final Pattern CITATION_BIBTEX_VERSION = Pattern.compile(
            "(?m)^(  version\\s+= \\{)[^}]+(},)$");
    private static final Pattern CITATION_BIBTEX_DATE = Pattern.compile(
            "(?m)^  date\\s+= \\{[^}]+},\\R?");
    private static final Pattern CITATION_AUTHOR = Pattern.compile(
            "(?m)^(  author\\s+= \\{Hammer, Carsten},)$");
    private static final Pattern CITATION_ORCID = Pattern.compile(
            "(?m)^  orcid\\s+= \\{[^}]+},$");
    private static final Pattern CHART_APP_VERSION = Pattern.compile(
            "(?m)^appVersion:\\s*.*$");

    private ReleaseMetadataUpdater() {
    }

    public static Result update(
            Path root,
            String requestedVersion,
            boolean release,
            LocalDate releaseDate) throws IOException {
        Path repository = root.toRealPath();
        String version = release
                ? VersionNumbers.normalizeRelease(requestedVersion, "version")
                : VersionNumbers.normalizeDevelopment(
                        requestedVersion, "version", false);
        LocalDate effectiveDate = release
                ? Objects.requireNonNull(releaseDate,
                        "releaseDate is required in release mode")
                : null;

        LinkedHashMap<Path, String> planned = new LinkedHashMap<>();
        Path citationCff = repository.resolve("CITATION.cff");
        Path citationMarkdown = repository.resolve("CITATION.md");
        Path zenodo = repository.resolve(".zenodo.json");
        Path codemeta = repository.resolve("codemeta.json");
        Path chart = repository.resolve("deploy/helm/taxonomy/Chart.yaml");

        planned.put(citationCff, transformCitationCff(
                readRequired(citationCff), version, effectiveDate));
        planned.put(zenodo, transformJsonMetadata(
                readRequired(zenodo), version, effectiveDate,
                "publication_date"));
        planned.put(codemeta, transformJsonMetadata(
                readRequired(codemeta), version, effectiveDate,
                "datePublished"));
        planned.put(citationMarkdown, transformCitationMarkdown(
                readRequired(citationMarkdown), version, effectiveDate));
        planned.put(chart, transformChart(
                readRequired(chart), version, chart));

        for (Map.Entry<Path, String> entry : planned.entrySet()) {
            writeAtomically(entry.getKey(), entry.getValue());
        }
        return new Result(
                version,
                release,
                effectiveDate,
                planned.keySet().stream()
                        .map(repository::relativize)
                        .map(path -> path.toString().replace('\\', '/'))
                        .toList());
    }

    private static String transformCitationCff(
            String text,
            String version,
            LocalDate releaseDate) {
        String updated = replaceRequired(
                CFF_VERSION,
                text,
                "version: \"" + version + "\"",
                "CITATION.cff has no version field");
        updated = CFF_RELEASE_DATE.matcher(updated).replaceAll("");
        if (releaseDate != null) {
            updated = ensureTerminalNewline(updated)
                    + "date-released: \"" + releaseDate + "\"\n";
        }
        return updated;
    }

    private static String transformJsonMetadata(
            String text,
            String version,
            LocalDate releaseDate,
            String dateKey) {
        Map<String, Object> parsed = FlatJson.parseObject(text);
        parsed.put("version", version);
        if (releaseDate == null) {
            parsed.remove(dateKey);
        } else {
            parsed.put(dateKey, releaseDate.toString());
        }
        return FlatJson.pretty(parsed) + "\n";
    }

    private static String transformCitationMarkdown(
            String text,
            String version,
            LocalDate releaseDate) {
        String updated = replaceRequired(
                CITATION_PREFERRED,
                text,
                "$1" + Matcher.quoteReplacement(version) + "$2",
                "CITATION.md has no preferred citation version");
        updated = replaceRequired(
                CITATION_BIBTEX_VERSION,
                updated,
                "$1" + Matcher.quoteReplacement(version) + "$2",
                "CITATION.md has no BibTeX version");
        updated = CITATION_BIBTEX_DATE.matcher(updated).replaceAll("");
        if (releaseDate != null) {
            updated = replaceRequired(
                    CITATION_BIBTEX_VERSION,
                    updated,
                    "$1" + Matcher.quoteReplacement(version)
                            + "$2\n  date         = {" + releaseDate + "},",
                    "CITATION.md has no BibTeX version for the release date");
        }

        if (!updated.contains("ORCID")) {
            String marker = "## What to cite\n";
            if (!updated.contains(marker)) {
                throw new IllegalArgumentException(
                        "CITATION.md has no 'What to cite' marker for ORCID insertion");
            }
            updated = updated.replace(
                    marker,
                    "## Author identifier\n\nCarsten Hammer's ORCID iD is ["
                            + ORCID_URL + "](" + ORCID_URL + ").\n\n"
                            + marker);
        }
        if (!CITATION_ORCID.matcher(updated).find()) {
            updated = replaceRequired(
                    CITATION_AUTHOR,
                    updated,
                    "$1\n  orcid        = {" + ORCID_URL + "},",
                    "CITATION.md has no BibTeX author for ORCID insertion");
        }
        return updated;
    }

    private static String transformChart(
            String text,
            String version,
            Path path) {
        return replaceRequired(
                CHART_APP_VERSION,
                text,
                "appVersion: \"" + version + "\"",
                path + " has no appVersion field");
    }

    private static String replaceRequired(
            Pattern pattern,
            String text,
            String replacement,
            String failure) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException(failure);
        }
        return matcher.replaceAll(replacement);
    }

    private static String ensureTerminalNewline(String text) {
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static String readRequired(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "Required release metadata file is missing: " + path);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void writeAtomically(Path path, String content)
            throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Release metadata path has no parent: " + path);
        }
        Path temporary = Files.createTempFile(
                parent, "." + path.getFileName(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public record Result(
            String version,
            boolean release,
            LocalDate releaseDate,
            java.util.List<String> updatedFiles) {
    }
}
