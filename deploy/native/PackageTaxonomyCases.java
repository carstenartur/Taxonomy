import java.nio.file.Path;
import java.util.List;

/** Dependency-free command construction checks; real package smoke runs against the supplied Boot JAR. */
public final class PackageTaxonomyCases {
    public static void main(String[] args) {
        Path input = Path.of("workspace with spaces", "input");
        List<String> linux = PackageTaxonomy.command(input, Path.of("output"), "1.4.0", "deb", false);
        check(linux.contains(input.toString()), "input remains one argument");
        check(linux.contains("-Dtaxonomy.native=true"), "native marker is a JVM option, not replaceable default app arguments");
        check(linux.contains("ALL-MODULE-PATH"), "runtime includes dynamic JDBC/TLS modules");
        check(linux.contains("Taxonomy-Configure=workspace with spaces" + java.io.File.separator + "configure.properties"), "configure launcher");
        List<String> windows = PackageTaxonomy.command(input, Path.of("output"), "1.4.0", "msi", true);
        check(windows.contains("--win-per-user-install"), "no administrator installation required");
        check(windows.contains("--win-upgrade-uuid"), "stable upgrade identity");
        reject("1.4.0-SNAPSHOT", "app-image", false);
        reject("1.4.0", "msi", false);
        reject("1.4.0", "rpm", true);
        reject("256.1.0", "msi", true);
        System.out.println("Native packaging command contracts: 10 passed");
    }
    private static void reject(String version, String type, boolean windows) {
        try { PackageTaxonomy.command(Path.of("work", "input"), Path.of("output"), version, type, windows); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid package options accepted");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
