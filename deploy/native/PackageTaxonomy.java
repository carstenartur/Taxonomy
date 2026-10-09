import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;

/** Run with Java 21: java deploy/native/PackageTaxonomy.java app.jar 1.4.0 app-image output */
public final class PackageTaxonomy {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: PackageTaxonomy.java <executable-jar> <numeric-version> <app-image|deb|rpm|msi|exe> <output-directory>");
        }
        Path jar = Path.of(args[0]).toAbsolutePath();
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path output = Path.of(args[3]).toAbsolutePath();
        // Validate options before creating any package workspace.
        command(Path.of("workspace", "input"), output, args[1], args[2], windows);
        try (JarFile archive = new JarFile(jar.toFile())) {
            var manifest = archive.getManifest();
            if (manifest == null || !"org.springframework.boot.loader.launch.PropertiesLauncher".equals(manifest.getMainAttributes().getValue("Main-Class"))
                    || !"com.taxonomy.TaxonomyApplication".equals(manifest.getMainAttributes().getValue("Start-Class"))) {
                throw new IllegalArgumentException("Use the repackaged Taxonomy application JAR, not an original or library JAR.");
            }
        }
        Path workspace = Files.createTempDirectory("taxonomy-native-");
        try {
            Path input = Files.createDirectory(workspace.resolve("input"));
            Files.copy(jar, input.resolve("taxonomy.jar"));
            stageExtensions(jar.getParent(), input);
            Files.writeString(workspace.resolve("configure.properties"), "arguments=--configure\nwin-console=true\n");
            Files.writeString(workspace.resolve("check.properties"), "arguments=--check-configuration=static\nwin-console=true\n");
            Files.createDirectories(output);
            List<String> command = command(input, output, args[1], args[2], windows);
            run(command);
            if (args[2].equals("app-image")) {
                Path launcher = windows ? output.resolve("Taxonomy/Taxonomy.exe") : output.resolve("Taxonomy/bin/Taxonomy");
                run(List.of(launcher.toString(), "--setup-help"));
            }
            // Every executable input participates in the installation evidence.
            var evidence = new StringBuilder();
            try (var files = Files.walk(input)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (var in = new java.security.DigestInputStream(Files.newInputStream(file), digest)) { in.transferTo(java.io.OutputStream.nullOutputStream()); }
                    evidence.append(HexFormat.of().formatHex(digest.digest())).append("  ")
                            .append(input.relativize(file).toString().replace('\\','/')).append('\n');
                }
            }
            Files.writeString(output.resolve("application-input.sha256"), evidence);
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }

    static void stageExtensions(Path distribution, Path input) throws IOException {
        for (String kind : List.of("features", "plugins")) {
            Path source = distribution.resolve(kind);
            if (Files.isSymbolicLink(source) || !Files.isDirectory(source)) throw new IOException("Missing distribution directory: " + kind);
            Path target = Files.createDirectory(input.resolve(kind));
            var features = new java.util.HashSet<String>();
            int count = 0;
            try (var files = Files.list(source)) {
                for (Path file : files.sorted().toList()) {
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".jar"))
                        throw new IOException("Unexpected distribution entry: " + kind);
                    if (kind.equals("features")) {
                        var match = java.util.regex.Pattern.compile("taxonomy-(templates|architecture|reporting|analysis|portfolio|interop)-[0-9].*\\.jar").matcher(file.getFileName().toString());
                        if (!match.matches() || !features.add(match.group(1))) throw new IOException("Unknown or duplicated startup feature");
                    }
                    Files.copy(file, target.resolve(file.getFileName())); count++;
                }
            }
            if (count == 0 || (kind.equals("features") && features.size() != 6)) throw new IOException("Incomplete standard distribution: " + kind);
        }
    }

    static List<String> command(Path input, Path output, String version, String type, boolean windows) {
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) { throw new IllegalArgumentException("Use a numeric major.minor.patch package version."); }
        String[] parts = version.split("\\.");
        if (Integer.parseInt(parts[0]) > 255 || Integer.parseInt(parts[1]) > 255 || Integer.parseInt(parts[2]) > 65535) {
            throw new IllegalArgumentException("Version exceeds Windows Installer version limits.");
        }
        if (!(windows ? Set.of("app-image", "msi", "exe") : Set.of("app-image", "deb", "rpm")).contains(type)) {
            throw new IllegalArgumentException("Build each package format on its target operating system.");
        }
        Path jpackage = Path.of(System.getProperty("java.home"), "bin", windows ? "jpackage.exe" : "jpackage");
        List<String> result = new ArrayList<>(List.of(jpackage.toString(), "--type", type, "--name", "Taxonomy",
                "--app-version", version, "--vendor", "Carsten Hammer", "--description", "Taxonomy Architecture Analyzer",
                "--input", input.toString(), "--dest", output.toString(), "--main-jar", "taxonomy.jar",
                "--main-class", "org.springframework.boot.loader.launch.PropertiesLauncher", "--add-modules", "ALL-MODULE-PATH",
                "--java-options", "-Dtaxonomy.native=true",
                "--java-options", "-Dloader.path=$APPDIR/features",
                "--java-options", "-Dtaxonomy.plugins.directory=$APPDIR/plugins",
                "--java-options", "-cp", "--java-options", "$APPDIR/taxonomy.jar",
                "--add-launcher", "Taxonomy-Configure=" + input.getParent().resolve("configure.properties"),
                "--add-launcher", "Taxonomy-Check=" + input.getParent().resolve("check.properties")));
        if (windows) {
            result.add("--win-console");
            if (!type.equals("app-image")) {
                result.addAll(List.of("--win-per-user-install", "--win-menu", "--win-shortcut", "--win-upgrade-uuid", "c6ca5bea-b981-4e9f-adef-8d6a1caf4640"));
            }
        } else if (!type.equals("app-image")) {
            result.addAll(List.of("--linux-package-name", "taxonomy", "--linux-shortcut"));
        }
        return result;
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        int status = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (status != 0) { throw new IOException("Native packaging or launcher verification failed with exit code " + status); }
    }
}
