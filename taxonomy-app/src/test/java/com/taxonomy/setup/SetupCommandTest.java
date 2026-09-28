package com.taxonomy.setup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class SetupCommandTest {
    @TempDir Path directory;

    @Test void pureContracts() throws Exception { SetupContractCases.main(new String[0]); }

    @Test void springContracts() throws Exception { SetupSpringCases.main(new String[0]); }

    @Test void ordinaryStartupDoesNotRunSetup() {
        assertEquals(-1, SetupCommand.execute(new String[] {"--server.port=8080"}, System.out));
    }

    @Test void invalidOperationDoesNotStartApplication() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(2, SetupCommand.execute(new String[] {"--check-configuration=invalid"}, new PrintStream(bytes)));
        assertTrue(bytes.toString().contains("ERROR"));
    }

    @Test void configurationCheckLoadsProfileConfigtreeAndCommandLinePrecedenceWithoutDatabase() throws Exception {
        Path config = directory.resolve("settings");
        LocalSetup.write(config, LocalSetup.localProperties(config.resolve("data")), Map.of("taxonomy.admin-password", "not-for-diagnostics"));
        String location = "--spring.config.additional-location=" + config.resolve("application.properties").toUri();
        var environment = SetupCommand.loadEnvironment(new String[] {location, "--server.port=8181"});
        assertEquals("8181", environment.getProperty("server.port"));
        assertEquals("not-for-diagnostics", environment.getProperty("taxonomy.admin-password"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(0, SetupCommand.execute(new String[] {"--check-configuration=static", location}, new PrintStream(bytes)));
        assertFalse(bytes.toString().contains("not-for-diagnostics"));
        assertTrue(bytes.toString().contains("NOT_CHECKED"));
        try (var files = Files.list(config.resolve("data"))) { assertEquals(0, files.count()); }
    }

    @Test void springApplicationJsonIsIncludedAndCannotOverrideCommandLine() {
        var environment = SetupCommand.loadEnvironment(new String[] {
            "--spring.application.json={\"server\":{\"port\":9191}}", "--server.port=8181"});
        assertEquals("8181", environment.getProperty("server.port"));
    }

    @Test void missingConfigErrorDoesNotEchoSource() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(2, SetupCommand.execute(new String[] {"--check-configuration", "--spring.config.location=file:/missing/secret-value"}, new PrintStream(bytes)));
        assertFalse(bytes.toString().contains("secret-value"));
    }

    @Test void rejectsSecretsInOrdinaryPropertiesAndTraversalBeforeWriting() throws Exception {
        Properties settings = LocalSetup.localProperties(directory.resolve("data"));
        settings.setProperty("spring.datasource.password", "do-not-write");
        assertThrows(java.io.IOException.class, () -> LocalSetup.write(directory.resolve("bad"), settings, Map.of()));
        assertFalse(Files.exists(directory.resolve("bad")));
        settings.remove("spring.datasource.password");
        assertThrows(java.io.IOException.class, () -> LocalSetup.write(directory.resolve("bad"), settings, Map.of("../escape", "value")));
    }
    @Test void nativeLauncherRequiresConfigurationAndRejectsDestructiveEdits() throws Exception {
        String nativeBefore = System.getProperty("taxonomy.native");
        String locationBefore = System.getProperty("taxonomy.config-directory");
        try {
            System.setProperty("taxonomy.native", "true");
            Path config = directory.resolve("native settings");
            System.setProperty("taxonomy.config-directory", config.toString());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertEquals(2, SetupCommand.execute(new String[0], new PrintStream(bytes)));
            assertFalse(Files.exists(config));
            LocalSetup.write(config, LocalSetup.localProperties(config.resolve("data")), Map.of("taxonomy.admin-password", "not-for-diagnostics"));
            assertEquals(-1, SetupCommand.execute(new String[0], new PrintStream(bytes)));
            assertEquals(2, SetupCommand.execute(new String[] {"--spring.jpa.hibernate.ddl-auto=create"}, new PrintStream(bytes)));
            assertFalse(bytes.toString().contains("not-for-diagnostics"));
            try (var files = Files.list(config.resolve("data"))) { assertEquals(0, files.count()); }
        } finally {
            restoreProperty("taxonomy.native", nativeBefore);
            restoreProperty("taxonomy.config-directory", locationBefore);
        }
    }

    @Test void helpNeedsNeitherDatabaseNorConfigurationAndMultipleOperationsAreRejected() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(0, SetupCommand.execute(new String[] {"--setup-help"}, new PrintStream(bytes)));
        assertEquals(2, SetupCommand.execute(new String[] {"--configure", "--check-configuration"}, new PrintStream(bytes)));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) { System.clearProperty(name); } else { System.setProperty(name, value); }
    }

}
