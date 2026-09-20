package com.taxonomy.interop.publication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationPublicationRestartTest {

    @TempDir
    Path directory;

    @Test
    void unknownPublicationAndTypedLocalCheckpointSurviveFullApplicationRestart() throws Exception {
        for (String phase : List.of("unknown", "recover")) {
            Path log = directory.resolve("publication-" + phase + ".log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx768m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PublicationRestartDriver.class.getName(), directory.toString(), phase).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(150, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            String output = Files.readString(log);
            String evidenceDirectory = System.getProperty("publication.restart.evidence");
            if (evidenceDirectory != null) {
                Path evidence = Path.of(evidenceDirectory);
                Files.createDirectories(evidence);
                Files.copy(log, evidence.resolve("publication-" + phase + ".log"), StandardCopyOption.REPLACE_EXISTING);
            }
            assertTrue(finished, "Publication restart process timed out: " + phase + "\n" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PUBLICATION_PROCESS_RESTART_OK " + phase), output);
            System.out.println("PUBLICATION_PROCESS_RESTART_OK " + phase + " log=" + log);
        }
    }
}
