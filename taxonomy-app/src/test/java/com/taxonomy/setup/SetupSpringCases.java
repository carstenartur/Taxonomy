package com.taxonomy.setup;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Executable Spring ConfigData contracts; reused by JUnit and artifact-based diagnostics. */
public final class SetupSpringCases {
    private static int checks;

    public static void main(String[] args) throws Exception {
        checks = 0;
        Path parent = Files.createTempDirectory("taxonomy spring setup ");
        Path config = parent.resolve("settings");
        String oldNative = System.getProperty("taxonomy.native");
        String oldLocation = System.getProperty("taxonomy.config-directory");
        try {
            System.clearProperty("taxonomy.native");
            check(SetupCommand.execute(new String[0], System.out) == -1, "normal mode unchanged");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes);
            check(SetupCommand.execute(new String[] {"--setup-help"}, out) == 0, "help does not bootstrap");
            check(SetupCommand.execute(new String[] {"--check-configuration=invalid"}, out) == 2, "invalid operation");
            LocalSetup.write(config, LocalSetup.localProperties(config.resolve("data")), Map.of("taxonomy.admin-password", "never-echo-this-password"));
            String location = "--spring.config.additional-location=" + config.resolve("application.properties").toUri();
            var environment = SetupCommand.loadEnvironment(new String[] {location, "--server.port=8181"});
            check(environment.getProperty("server.port").equals("8181"), "command line precedence");
            check(environment.getProperty("taxonomy.admin-password").equals("never-echo-this-password"), "configtree loaded");
            check(java.util.Arrays.asList(environment.getActiveProfiles()).contains("local-user-management"), "profile loaded");
            var json = SetupCommand.loadEnvironment(new String[] {location, "--spring.application.json={\"server\":{\"port\":9191}}"});
            check(json.getProperty("server.port").equals("9191"), "JSON above config file");
            json = SetupCommand.loadEnvironment(new String[] {location, "--spring.application.json={\"server\":{\"port\":9191}}", "--server.port=8282"});
            check(json.getProperty("server.port").equals("8282"), "CLI above JSON");
            check(SetupCommand.execute(new String[] {"--check-configuration", location}, out) == 0, "static check uses actual configuration");
            check(!bytes.toString().contains("never-echo-this-password"), "no secret output");
            try (var files = Files.list(config.resolve("data"))) { check(files.count() == 0, "preflight creates no DB"); }
            System.setProperty("taxonomy.native", "true");
            System.setProperty("taxonomy.config-directory", parent.resolve("missing").toString());
            check(SetupCommand.execute(new String[0], out) == 2, "native startup fails closed without config");
            System.setProperty("taxonomy.config-directory", config.toString());
            check(SetupCommand.execute(new String[0], out) == -1, "native startup uses generated config");
            check(SetupCommand.execute(new String[] {"--spring.jpa.hibernate.ddl-auto=create"}, out) == 2, "native startup blocks destructive override");
            check(SetupCommand.execute(new String[] {"--configure", "--check-configuration"}, out) == 2, "multiple operations rejected");
        } finally {
            restore("taxonomy.native", oldNative);
            restore("taxonomy.config-directory", oldLocation);
            try (var paths = Files.walk(parent)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
        System.out.println("Spring setup contracts: " + checks + " passed");
    }

    private static void restore(String key, String value) {
        if (value == null) { System.clearProperty(key); } else { System.setProperty(key, value); }
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) { throw new AssertionError(message); }
    }
}
