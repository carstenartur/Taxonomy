package com.taxonomy.backup.recovery;

import com.taxonomy.tooling.NativeInstallationSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Same-version, complete configured native data root; no portable importer or mocked application. */
@Tag("ci-app-shard-recovery")
class NativeInstallationRecoveryTest {
    @TempDir Path temporary;

    @Test void coldInstallationRecoversActualSavedDataIntoAnIndependentTargetAcrossTwoRestarts() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path snapshot = temporary.resolve("snapshot"), target = temporary.resolve("restored"), oracle = temporary.resolve("oracle.properties");
        Path evidence = Path.of("target/native-installation-recovery"); Files.createDirectories(evidence);
        Files.deleteIfExists(evidence.resolve("result.json"));
        process(source, oracle, "seed", evidence);
        Properties expected = new Properties(); try (var input = Files.newInputStream(oracle)) { expected.load(input); }
        String version = expected.getProperty("applicationVersion");
        assertThat(version).isNotBlank(); assertThat(source.resolve("taxonomydb.lck")).doesNotExist();
        Map<String, String> original = digests(source);
        assertThat(original.keySet()).anyMatch(path -> path.startsWith("lucene-index/")).contains("source-content/requirements.txt");
        var receipt = NativeInstallationSnapshot.capture(source, snapshot, version, () -> {});
        NativeInstallationSnapshot.restore(snapshot, source, target, version, () -> {});
        assertThat(digests(target)).isEqualTo(original);
        process(target, oracle, "undo", evidence);
        assertThat(target.resolve("taxonomydb.lck")).doesNotExist();
        process(target, oracle, "redo", evidence);
        assertThat(digests(source)).as("Every stopped source file is unchanged after both restored JVMs and target edits").isEqualTo(original);
        assertThat(digests(target)).isNotEqualTo(original);
        var report = new LinkedHashMap<String, Object>();
        report.put("status", "PASSED"); report.put("applicationVersion", version); report.put("databaseFamily", "HSQLDB");
        report.put("recovery", "same-version native cold installation; complete configured data root");
        report.put("sourceUnchanged", true); report.put("targetRestartCount", 2); report.put("manifestSha256", receipt.sha256());
        report.put("fileCount", receipt.fileCount()); report.put("bytes", receipt.bytes());
        report.put("coveredData", List.of("project and two requirement versions", "immutable saved analysis and source references", "curated solution and selected product", "retained source bytes", "original SQL-backed Git commits and branches", "editor checkpoint, draft, journal, undo and redo", "Git-backed preferences and uploaded template", "filesystem Lucene catalogue search"));
        report.put("limits", List.of("Test-authored saved analysis, no model-quality claim", "No portable owner imports or activation", "No cross-database or cross-version conversion", "No running-writer or power-loss snapshot", "No container-volume or Windows certification", "Remote providers and brokers disabled; no unfinished external jobs seeded"));
        Files.writeString(evidence.resolve("result.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        Files.copy(snapshot.resolve("manifest.json"), evidence.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void process(Path data, Path oracle, String phase, Path evidence) throws Exception {
        Path log = evidence.resolve(phase + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx768m", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), NativeInstallationRecoveryDriver.class.getName(),
                data.toAbsolutePath().toString(), oracle.toAbsolutePath().toString(), phase)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(150, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(20, TimeUnit.SECONDS); }
        assertThat(finished).as("Application process completed: %s; %s", phase, log).isTrue();
        assertThat(process.exitValue()).as("Application process %s\n%s", phase, Files.readString(log)).isZero();
        assertThat(Files.readString(log)).contains("NATIVE_INSTALLATION_RECOVERY_OK " + phase);
    }

    private static Map<String, String> digests(Path root) throws Exception {
        var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                var hash = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[64 * 1024]; int count; while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count); }
                hashes.put(root.relativize(path).toString(), HexFormat.of().formatHex(hash.digest()));
            }
        }
        return hashes;
    }
}
