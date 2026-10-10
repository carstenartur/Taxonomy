import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;

/** Dependency-free command construction checks; real package smoke runs against the supplied Boot JAR. */
public final class PackageTaxonomyCases {
    public static void main(String[] args) throws Exception {
        Path input = Path.of("workspace with spaces", "input");
        List<String> linux = PackageTaxonomy.command(input, Path.of("output"), "1.4.0", "deb", false);
        check(linux.contains(input.toString()), "input remains one argument");
        check(linux.contains("-Dtaxonomy.native=true"), "native marker is a JVM option, not replaceable default app arguments");
        check(linux.contains("org.springframework.boot.loader.launch.PropertiesLauncher"), "feature-aware launcher");
        check(linux.contains("-Dloader.path=$APPDIR/features"), "features are relative to the installed app, not cwd");
        check(linux.contains("-Dtaxonomy.plugins.directory=$APPDIR/plugins"), "plugins are relative to the installed app");
        check(linux.contains("ALL-MODULE-PATH"), "runtime includes dynamic JDBC/TLS modules");
        check(linux.contains("Taxonomy-Configure=workspace with spaces" + java.io.File.separator + "configure.properties"), "configure launcher");
        List<String> windows = PackageTaxonomy.command(input, Path.of("output"), "1.4.0", "msi", true);
        check(windows.contains("--win-per-user-install"), "no administrator installation required");
        check(windows.contains("--win-upgrade-uuid"), "stable upgrade identity");
        reject("1.4.0-SNAPSHOT", "app-image", false);
        reject("1.4.0", "msi", false);
        reject("1.4.0", "rpm", true);
        reject("256.1.0", "msi", true);
        distributionInputs();
        System.out.println("Native packaging contracts: 22 passed");
    }
    private static void distributionInputs() throws Exception {
        Path root=Files.createTempDirectory("taxonomy-native-contract-");
        try {
            Path distribution=Files.createDirectory(root.resolve("distribution"));
            Path features=Files.createDirectory(distribution.resolve("features"));
            Path plugins=Files.createDirectory(distribution.resolve("plugins"));
            for(String id:List.of("templates","architecture","reporting","analysis","portfolio","interop"))
                Files.writeString(features.resolve("taxonomy-"+id+"-1.4.1.jar"),id);
            Files.writeString(plugins.resolve("mermaid.jar"),"plugin");
            String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("plugin".getBytes(java.nio.charset.StandardCharsets.UTF_8))) + " *mermaid.jar\n";
            Path sidecar = plugins.resolve("mermaid.jar.sha256");
            Files.writeString(sidecar, checksum);
            Path complete=Files.createDirectory(root.resolve("complete"));
            PackageTaxonomy.stageExtensions(distribution,complete);
            check(Files.readString(complete.resolve("plugins/mermaid.jar")).equals("plugin"),"plugin bytes copied");
            try(var entries=Files.list(complete.resolve("features"))) {check(entries.count()==6,"all startup features copied");}
            check(Files.readString(complete.resolve("plugins/mermaid.jar.sha256")).equals(checksum), "checksum bytes copied");
            Files.writeString(sidecar, "0".repeat(64) + " *mermaid.jar\n");
            rejectDistribution(distribution,Files.createDirectory(root.resolve("corrupt-checksum")));
            Files.writeString(sidecar, checksum.replace("mermaid.jar", "different.jar"));
            rejectDistribution(distribution,Files.createDirectory(root.resolve("misnamed-checksum")));
            Files.writeString(sidecar, checksum);
            Path extra=features.resolve("taxonomy-analysis-1.4.0.jar");Files.writeString(extra,"old");
            rejectDistribution(distribution,Files.createDirectory(root.resolve("duplicate")));Files.delete(extra);
            Files.writeString(features.resolve("unexpected.txt"),"unexpected");
            rejectDistribution(distribution,Files.createDirectory(root.resolve("unexpected")));Files.delete(features.resolve("unexpected.txt"));
            Files.delete(plugins.resolve("mermaid.jar"));
            rejectDistribution(distribution,Files.createDirectory(root.resolve("orphan-checksum")));
            Files.delete(sidecar);
            rejectDistribution(distribution,Files.createDirectory(root.resolve("missing")));
        } finally {
            try(var paths=Files.walk(root)) {for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);}
        }
    }
    private static void rejectDistribution(Path distribution,Path input) throws Exception {
        try {PackageTaxonomy.stageExtensions(distribution,input);} catch(IOException expected) {return;}
        throw new AssertionError("Incomplete or ambiguous native distribution accepted");
    }
    private static void reject(String version, String type, boolean windows) {
        try { PackageTaxonomy.command(Path.of("work", "input"), Path.of("output"), version, type, windows); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid package options accepted");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
