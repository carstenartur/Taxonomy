package com.taxonomy.build;

import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks repository-owned local Markdown links and image references.
 *
 * <p>External URLs and application runtime routes are deliberately ignored so
 * verification does not depend on remote availability or confuse servlet routes
 * with repository files. Generated and third-party directory trees are pruned
 * before traversal.</p>
 */
final class DocumentationLinkPolicy {

    static final List<String> DEFAULT_INPUTS = List.of(
            "README.md", "CITATION.md", "RESEARCH.md", "docs", ".github");

    static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git",
            ".gradle",
            ".pytest_cache",
            ".venv",
            "__pycache__",
            "build",
            "dist",
            "node_modules",
            "target",
            "venv");

    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "!?\\[[^\\]]*]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile(
            "^\\s*\\[[^\\]]+]:\\s+(\\S+)");
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile(
            "\\b(?:src|href)=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> EXTERNAL_PREFIXES = List.of(
            "http://",
            "https://",
            "mailto:",
            "tel:",
            "data:",
            "javascript:");

    Inspection inspect(Path repositoryRoot) throws IOException {
        return inspect(repositoryRoot, DEFAULT_INPUTS);
    }

    Inspection inspect(Path repositoryRoot, List<String> inputs) throws IOException {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        List<Path> markdownFiles = markdownFiles(root, inputs);
        List<String> errors = new ArrayList<>();

        for (Path markdownFile : markdownFiles) {
            String relativeMarkdown = relativePath(root, markdownFile);
            List<String> lines;
            try {
                lines = Files.readAllLines(markdownFile, StandardCharsets.UTF_8);
            } catch (MalformedInputException exception) {
                errors.add(relativeMarkdown + ": cannot read as UTF-8: "
                        + exception.getMessage());
                continue;
            }

            for (int index = 0; index < lines.size(); index++) {
                for (String rawTarget : collectTargets(lines.get(index))) {
                    String target = normalizeTarget(rawTarget);
                    if (target.isEmpty() || isExternalOrRuntimeRoute(target)) {
                        continue;
                    }

                    Path resolved = resolveLocalTarget(root, markdownFile, target);
                    if (!resolved.startsWith(root)) {
                        errors.add(relativeMarkdown + ":" + (index + 1)
                                + ": target escapes repository: " + rawTarget);
                    } else if (!Files.exists(resolved)) {
                        errors.add(relativeMarkdown + ":" + (index + 1)
                                + ": missing local target: " + rawTarget);
                    }
                }
            }
        }

        return new Inspection(markdownFiles, errors);
    }

    private static List<Path> markdownFiles(Path root, List<String> inputs)
            throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (String input : inputs) {
            Path path = root.resolve(input).toAbsolutePath().normalize();
            if (isIgnoredPath(root, path)) {
                continue;
            }
            if (Files.isRegularFile(path) && isMarkdown(path)) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory,
                            BasicFileAttributes attributes) {
                        if (!directory.equals(path) && isIgnoredPath(root, directory)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attributes) {
                        if (attributes.isRegularFile()
                                && isMarkdown(file)
                                && !isIgnoredPath(root, file)) {
                            files.add(file.toAbsolutePath().normalize());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return files.stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    static List<String> collectTargets(String line) {
        List<String> targets = new ArrayList<>();
        addMatches(MARKDOWN_LINK, line, targets);
        addMatches(HTML_ATTRIBUTE, line, targets);
        Matcher reference = REFERENCE_DEFINITION.matcher(line);
        if (reference.find()) {
            targets.add(reference.group(1));
        }
        return targets;
    }

    static String normalizeTarget(String rawTarget) {
        String target = HtmlUtils.htmlUnescape(rawTarget.strip());
        if (target.startsWith("<") && target.endsWith(">")) {
            target = target.substring(1, target.length() - 1);
        }
        target = before(target, '#');
        target = before(target, '?');
        try {
            // URLDecoder applies form semantics to '+'. Preserve literal plus
            // characters while decoding percent-encoded repository paths.
            return URLDecoder.decode(
                    target.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedPercentEncoding) {
            return target;
        }
    }

    private static boolean isExternalOrRuntimeRoute(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        if (EXTERNAL_PREFIXES.stream().anyMatch(lower::startsWith)) {
            return true;
        }
        if (target.startsWith("#")) {
            return true;
        }
        return target.startsWith("/")
                && !target.startsWith("/docs/")
                && !target.startsWith("/.github/");
    }

    private static Path resolveLocalTarget(
            Path root,
            Path markdownFile,
            String target) {
        Path resolved = target.startsWith("/")
                ? root.resolve(target.substring(1))
                : markdownFile.getParent().resolve(target);
        return resolved.toAbsolutePath().normalize();
    }

    private static boolean isIgnoredPath(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(normalized);
        for (Path part : relative) {
            if (IGNORED_DIRECTORY_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarkdown(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md");
    }

    private static void addMatches(
            Pattern pattern,
            String line,
            List<String> targets) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            targets.add(matcher.group(1));
        }
    }

    private static String before(String value, char delimiter) {
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    record Inspection(List<Path> markdownFiles, List<String> errors) {
        Inspection {
            markdownFiles = List.copyOf(markdownFiles);
            errors = List.copyOf(errors);
        }
    }
}
