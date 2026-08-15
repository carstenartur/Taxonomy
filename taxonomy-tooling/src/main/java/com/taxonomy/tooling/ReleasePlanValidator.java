package com.taxonomy.tooling;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the declared Maven reactor and release transition without SCM writes. */
public final class ReleasePlanValidator {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final Set<String> STATES = Set.of(
            "development", "release", "advanced");

    private ReleasePlanValidator() {
    }

    public static Result validate(
            Path root,
            String currentVersion,
            String releaseVersion,
            String nextDevelopmentVersion,
            String state,
            boolean requireClean) throws IOException {
        Path repository = root.toRealPath();
        String release = VersionNumbers.normalizeRelease(
                releaseVersion, "releaseVersion");
        String next = VersionNumbers.normalizeDevelopment(
                nextDevelopmentVersion,
                "nextDevelopmentVersion",
                false);
        String current = Objects.requireNonNull(
                currentVersion, "currentVersion").strip();
        String normalizedState = Objects.requireNonNull(
                state, "state").strip();
        if (!STATES.contains(normalizedState)) {
            throw new IllegalArgumentException(
                    "releaseCheckCurrentState must be one of "
                            + STATES.stream().sorted().toList());
        }
        VersionNumbers.requireNewer(release, next);

        String expected = expectedCurrentVersion(
                release, next, normalizedState);
        if (!current.equals(expected)) {
            throw new IllegalArgumentException(
                    "release state " + normalizedState
                            + " expects project version " + expected
                            + ", but Maven resolved " + current);
        }

        List<Path> paths = reactorPomPaths(repository);
        List<PomModel> models = new ArrayList<>();
        for (Path path : paths) {
            models.add(modelFor(path));
        }
        if (!models.getFirst().path().equals(repository.resolve("pom.xml"))) {
            throw new IllegalArgumentException(
                    "reactor discovery did not start at the root pom.xml");
        }

        Map<Coordinate, PomModel> byCoordinate = modelsByCoordinate(models);
        Set<Coordinate> internalCoordinates = byCoordinate.keySet();
        Map<Path, Map<String, String>> propertyCache = new HashMap<>();
        List<String> failures = new ArrayList<>();

        for (PomModel model : models) {
            String relative = relative(repository, model.path());
            Map<String, String> properties = effectiveProperties(
                    model, byCoordinate, propertyCache, new HashSet<>());
            String resolvedModelVersion = resolveProperties(
                    model.version(), properties);
            if (PROPERTY.matcher(resolvedModelVersion).find()) {
                failures.add(relative + ": reactor version '" + model.version()
                        + "' contains an unresolved version property (resolved as '"
                        + resolvedModelVersion + "')");
            } else if (!resolvedModelVersion.equals(current)) {
                failures.add(relative + ": reactor version '" + resolvedModelVersion
                        + "' differs from " + current);
            }

            for (VersionedElement versioned : versionedElements(model)) {
                if ("forbidden-plugin".equals(versioned.kind())) {
                    failures.add(relative
                            + ": maven-release-plugin would create a second SCM release authority");
                    continue;
                }
                String resolved = resolveProperties(
                        versioned.rawVersion(), properties);
                String coordinateText = versioned.coordinate() == null
                        ? ""
                        : " " + versioned.coordinate().groupId() + ":"
                                + versioned.coordinate().artifactId();
                if (PROPERTY.matcher(resolved).find()) {
                    failures.add(relative + ": " + versioned.kind()
                            + coordinateText + " uses unresolved version property '"
                            + versioned.rawVersion() + "' (resolved as '"
                            + resolved + "')");
                    continue;
                }
                if (!resolved.toUpperCase().contains("SNAPSHOT")) {
                    continue;
                }
                boolean internalSnapshot = ("dependency".equals(versioned.kind())
                        || "parent".equals(versioned.kind()))
                        && internalCoordinates.contains(versioned.coordinate())
                        && resolved.equals(current)
                        && ("development".equals(normalizedState)
                                || "advanced".equals(normalizedState));
                if (!internalSnapshot) {
                    failures.add(relative + ": external " + versioned.kind()
                            + coordinateText + " uses snapshot version '"
                            + versioned.rawVersion() + "' (resolved as '"
                            + resolved + "')");
                }
            }
        }

        if (Files.exists(repository.resolve("release.properties"))) {
            failures.add(
                    "release.properties: stale Maven Release Plugin state is present");
        }
        collectReleaseBackups(repository, failures);

        if (!failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "release plan validation failed:\n- "
                            + String.join("\n- ", failures));
        }
        if (requireClean) {
            checkGitClean(repository);
        }
        return new Result(
                current, release, next, normalizedState, models.size());
    }

    public static String expectedCurrentVersion(
            String releaseVersion,
            String nextDevelopmentVersion,
            String state) {
        String normalizedState = Objects.requireNonNull(
                state, "state").strip();
        return switch (normalizedState) {
            case "development" -> releaseVersion + "-SNAPSHOT";
            case "release" -> releaseVersion;
            case "advanced" -> nextDevelopmentVersion;
            default -> throw new IllegalArgumentException(
                    "releaseCheckCurrentState must be one of "
                            + STATES.stream().sorted().toList());
        };
    }

    static List<Path> reactorPomPaths(Path root) throws IOException {
        Path repository = root.toRealPath();
        Path rootPom = repository.resolve("pom.xml").toAbsolutePath().normalize();
        if (!Files.isRegularFile(rootPom)) {
            throw new IllegalArgumentException(
                    "root pom.xml does not exist below " + repository);
        }
        Path realRootPom = rootPom.toRealPath();
        if (!realRootPom.equals(rootPom) || !realRootPom.startsWith(repository)) {
            throw new IllegalArgumentException(
                    "root pom.xml must be a real file inside " + repository);
        }

        Deque<Path> pending = new ArrayDeque<>();
        pending.add(realRootPom);
        List<Path> discovered = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Path declaredPom = pending.removeFirst()
                    .toAbsolutePath().normalize();
            if (!declaredPom.startsWith(repository)) {
                throw new IllegalArgumentException(
                        "declared Maven module escapes repository root: "
                                + declaredPom);
            }
            if (!Files.isRegularFile(declaredPom)) {
                throw new IllegalArgumentException(
                        "declared Maven module POM does not exist: "
                                + declaredPom);
            }
            Path pom = declaredPom.toRealPath();
            if (!pom.startsWith(repository) || !pom.equals(declaredPom)) {
                throw new IllegalArgumentException(
                        "declared Maven module must not traverse a symbolic link "
                                + "or escape the repository: " + declaredPom);
            }
            if (!seen.add(pom)) {
                continue;
            }

            Element project = XmlSupport.parse(pom).getDocumentElement();
            discovered.add(pom);
            Element modules = XmlSupport.child(project, "modules");
            for (Element moduleElement : XmlSupport.children(modules, "module")) {
                String module = XmlSupport.text(moduleElement);
                if (module.isBlank()) {
                    throw new IllegalArgumentException(relative(repository, pom)
                            + " contains an empty Maven module declaration");
                }
                Path modulePath = pom.getParent().resolve(module)
                        .toAbsolutePath().normalize();
                Path modulePom = "pom.xml".equals(
                        modulePath.getFileName().toString())
                                ? modulePath
                                : modulePath.resolve("pom.xml")
                                        .toAbsolutePath().normalize();
                if (!modulePom.startsWith(repository)) {
                    throw new IllegalArgumentException(relative(repository, pom)
                            + " declares module '" + module
                            + "' outside the repository");
                }
                if (!Files.isRegularFile(modulePom)) {
                    throw new IllegalArgumentException(relative(repository, pom)
                            + " declares module '" + module + "', but "
                            + relative(repository, modulePom) + " does not exist");
                }
                Path realModulePom = modulePom.toRealPath();
                if (!realModulePom.startsWith(repository)
                        || !realModulePom.equals(modulePom)) {
                    throw new IllegalArgumentException(relative(repository, pom)
                            + " declares module '" + module
                            + "' through a symbolic link or outside the repository");
                }
                pending.add(realModulePom);
            }
        }
        return discovered;
    }

    private static PomModel modelFor(Path path) throws IOException {
        Element project = XmlSupport.parse(path).getDocumentElement();
        Element parent = XmlSupport.child(project, "parent");
        Coordinate parentCoordinate = null;
        String groupId = XmlSupport.childText(project, "groupId");
        String version = XmlSupport.childText(project, "version");
        if (parent != null) {
            parentCoordinate = new Coordinate(
                    XmlSupport.childText(parent, "groupId"),
                    XmlSupport.childText(parent, "artifactId"));
            if (groupId.isBlank()) {
                groupId = parentCoordinate.groupId();
            }
            if (version.isBlank()) {
                version = XmlSupport.childText(parent, "version");
            }
        }
        String artifactId = XmlSupport.childText(project, "artifactId");
        if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException(path
                    + ": Maven coordinates must provide effective groupId, "
                    + "artifactId and version");
        }

        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        Element propertyRoot = XmlSupport.child(project, "properties");
        if (propertyRoot != null) {
            for (int index = 0;
                    index < propertyRoot.getChildNodes().getLength();
                    index++) {
                if (propertyRoot.getChildNodes().item(index)
                        instanceof Element property) {
                    properties.put(
                            property.getLocalName(), XmlSupport.text(property));
                }
            }
        }
        properties.put("project.groupId", groupId);
        properties.put("pom.groupId", groupId);
        properties.put("project.artifactId", artifactId);
        properties.put("pom.artifactId", artifactId);
        properties.put("project.version", version);
        properties.put("pom.version", version);
        if (parent != null) {
            properties.put("project.parent.groupId", parentCoordinate.groupId());
            properties.put("project.parent.artifactId", parentCoordinate.artifactId());
            properties.put("project.parent.version",
                    XmlSupport.childText(parent, "version"));
        }
        return new PomModel(
                path, project, groupId, artifactId, version,
                Map.copyOf(properties), parentCoordinate);
    }

    private static Map<Coordinate, PomModel> modelsByCoordinate(
            List<PomModel> models) {
        LinkedHashMap<Coordinate, PomModel> result = new LinkedHashMap<>();
        for (PomModel model : models) {
            Coordinate coordinate = new Coordinate(
                    model.groupId(), model.artifactId());
            PomModel previous = result.putIfAbsent(coordinate, model);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate Maven reactor coordinate "
                                + coordinate.groupId() + ":" + coordinate.artifactId()
                                + ": " + previous.path() + " and " + model.path());
            }
        }
        return result;
    }

    private static Map<String, String> effectiveProperties(
            PomModel model,
            Map<Coordinate, PomModel> models,
            Map<Path, Map<String, String>> cache,
            Set<Path> visiting) {
        Map<String, String> cached = cache.get(model.path());
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(model.path())) {
            throw new IllegalArgumentException(
                    "cyclic Maven parent relationship involving " + model.path());
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (model.parentCoordinate() != null) {
            PomModel parent = models.get(model.parentCoordinate());
            if (parent != null) {
                result.putAll(effectiveProperties(
                        parent, models, cache, visiting));
            }
        }
        result.putAll(model.properties());
        visiting.remove(model.path());
        Map<String, String> immutable = Map.copyOf(result);
        cache.put(model.path(), immutable);
        return immutable;
    }

    private static String resolveProperties(
            String value,
            Map<String, String> properties) {
        String result = value.strip();
        for (int round = 0; round < 12; round++) {
            Matcher matcher = PROPERTY.matcher(result);
            StringBuffer buffer = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) {
                    matcher.appendReplacement(buffer,
                            Matcher.quoteReplacement(matcher.group()));
                } else {
                    matcher.appendReplacement(buffer,
                            Matcher.quoteReplacement(replacement));
                    changed = true;
                }
            }
            matcher.appendTail(buffer);
            result = buffer.toString();
            if (!changed) {
                break;
            }
        }
        return result;
    }

    private static List<VersionedElement> versionedElements(PomModel model) {
        List<VersionedElement> result = new ArrayList<>();
        Element parent = XmlSupport.child(model.project(), "parent");
        if (parent != null) {
            String version = XmlSupport.childText(parent, "version");
            if (!version.isBlank()) {
                result.add(new VersionedElement(
                        "parent", model.parentCoordinate(), version));
            }
        }
        for (Element dependency : XmlSupport.descendants(
                model.project(), "dependency")) {
            String version = XmlSupport.childText(dependency, "version");
            if (!version.isBlank()) {
                result.add(new VersionedElement(
                        "dependency",
                        new Coordinate(
                                XmlSupport.childText(dependency, "groupId"),
                                XmlSupport.childText(dependency, "artifactId")),
                        version));
            }
        }
        for (Element plugin : XmlSupport.descendants(model.project(), "plugin")) {
            String artifactId = XmlSupport.childText(plugin, "artifactId");
            String version = XmlSupport.childText(plugin, "version");
            if ("maven-release-plugin".equals(artifactId)) {
                result.add(new VersionedElement(
                        "forbidden-plugin", null,
                        version.isBlank() ? "<managed>" : version));
            } else if (!version.isBlank()) {
                String groupId = XmlSupport.childText(plugin, "groupId");
                result.add(new VersionedElement(
                        "plugin",
                        new Coordinate(
                                groupId.isBlank()
                                        ? "org.apache.maven.plugins"
                                        : groupId,
                                artifactId),
                        version));
            }
        }
        for (Element extension : XmlSupport.descendants(
                model.project(), "extension")) {
            String version = XmlSupport.childText(extension, "version");
            if (!version.isBlank()) {
                result.add(new VersionedElement(
                        "build extension",
                        new Coordinate(
                                XmlSupport.childText(extension, "groupId"),
                                XmlSupport.childText(extension, "artifactId")),
                        version));
            }
        }
        return result;
    }

    private static void collectReleaseBackups(
            Path repository,
            List<String> failures) throws IOException {
        Files.walkFileTree(repository, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) {
                if (!directory.equals(repository)
                        && ignoredDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) {
                if (attributes.isRegularFile()
                        && "pom.xml.releaseBackup".equals(
                                file.getFileName().toString())) {
                    failures.add(relative(repository, file)
                            + ": stale Maven Release Plugin backup is present");
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean ignoredDirectory(Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return ".git".equals(name) || "target".equals(name);
    }

    private static void checkGitClean(Path root) {
        if (!Files.exists(root.resolve(".git"))) {
            return;
        }
        GitSupport.Result result = GitSupport.run(root, "status", "--porcelain");
        if (result.exitCode() != 0) {
            throw new IllegalArgumentException(
                    "git status failed: " + result.stderr().strip());
        }
        if (!result.stdout().strip().isEmpty()) {
            throw new IllegalArgumentException(
                    "release verification requires a clean checkout; "
                            + "commit or stash local changes");
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    public record Result(
            String currentVersion,
            String releaseVersion,
            String nextDevelopmentVersion,
            String state,
            int pomCount) {
    }

    private record Coordinate(String groupId, String artifactId) {
    }

    private record PomModel(
            Path path,
            Element project,
            String groupId,
            String artifactId,
            String version,
            Map<String, String> properties,
            Coordinate parentCoordinate) {
    }

    private record VersionedElement(
            String kind,
            Coordinate coordinate,
            String rawVersion) {
    }
}
