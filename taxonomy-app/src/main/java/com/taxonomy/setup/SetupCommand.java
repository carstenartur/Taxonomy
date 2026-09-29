package com.taxonomy.setup;

import java.io.Console;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Explicit setup/check entry points; the normal application is not bootstrapped here. */
public final class SetupCommand {
    private SetupCommand() { }

    /** -1 means normal application startup; all other results are process exit codes. */
    public static int execute(String[] args, PrintStream output) {
        boolean requested = Arrays.stream(args).anyMatch(SetupCommand::setupArgument);
        if (!requested && !Boolean.getBoolean("taxonomy.native")) { return -1; }
        try {
            if (!requested) {
                int status = check(applicationArguments(args), false, output);
                return status == 0 ? -1 : status;
            }
            if (Arrays.asList(args).contains("--setup-help")) {
                output.println("--check-configuration[=static|connections]  Check effective configuration without starting Taxonomy.");
                output.println("--configure[=directory]                    Create local configuration using a hidden console password.");
                output.println("--configure-local[=directory]              Unattended local setup; reads TAXONOMY_ADMIN_PASSWORD from the environment.");
                output.println("Native packages use the required per-user .taxonomy/application.properties; no public setup server exists.");
                return 0;
            }
            List<String> modes = Arrays.stream(args).filter(SetupCommand::setupArgument).toList();
            if (modes.size() != 1) { throw new IllegalArgumentException("Select one setup operation."); }
            String option = modes.getFirst();
            if (option.equals("--configure") || option.startsWith("--configure=")
                    || option.equals("--configure-local") || option.startsWith("--configure-local=")) {
                boolean unattended = option.startsWith("--configure-local");
                Path directory = option.contains("=") ? Path.of(option.substring(option.indexOf('=') + 1)) : configurationDirectory();
                String password;
                if (unattended) {
                    password = System.getenv("TAXONOMY_ADMIN_PASSWORD");
                } else {
                    Console console = System.console();
                    if (console == null) { throw new IllegalArgumentException("An interactive console is required."); }
                    output.println("Create local Taxonomy configuration: loopback only, durable HSQLDB, local user management.");
                    char[] secret = console.readPassword("Initial administrator password (12 characters minimum): ");
                    password = secret == null ? null : new String(secret);
                    if (secret != null) { Arrays.fill(secret, '\0'); }
                }
                if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                    output.println("ERROR authentication.bootstrap: Supply a password of at least 12 characters and at most 72 UTF-8 bytes.");
                    return 2;
                }
                Properties settings = LocalSetup.localProperties(directory.resolve("data"));
                LocalSetup.write(directory, settings, Map.of("taxonomy.admin-password", password));
                output.println("OK configuration.created: Local configuration and private secrets created. Run the static check before starting Taxonomy.");
                return 0;
            }
            String mode = option.contains("=") ? option.substring(option.indexOf('=') + 1) : "static";
            if (!Set.of("static", "connections").contains(mode)) { throw new IllegalArgumentException("Unknown check mode."); }
            return check(applicationArguments(args), mode.equals("connections"), output);
        } catch (Exception failure) {
            // ConfigData/parser exceptions may echo credentials. Do not print the exception.
            output.println("ERROR configuration.load: Check operation, required config files, permissions and syntax; existing files are never overwritten.");
            return 2;
        }
    }

    private static int check(String[] args, boolean connections, PrintStream output) {
        StandardEnvironment environment = loadEnvironment(args);
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.isEmpty()) { profiles.addAll(Arrays.asList(environment.getDefaultProfiles())); }
        List<SetupChecks.Finding> findings = new ArrayList<>(SetupChecks.check(environment::getProperty, profiles));
        if (Boolean.getBoolean("taxonomy.native")
                && SetupChecks.value(environment::getProperty, "spring.datasource.url").startsWith("jdbc:hsqldb:mem:")) {
            findings.add(new SetupChecks.Finding(SetupChecks.Status.ERROR, "spring.datasource.url",
                    "Native installations require persistent data; in-memory development defaults are not allowed."));
        }
        if (connections && findings.stream().noneMatch(f -> f.status() == SetupChecks.Status.ERROR)) {
            findings.addAll(SetupProbe.check(environment::getProperty, profiles));
        } else {
            findings.add(new SetupChecks.Finding(SetupChecks.Status.NOT_CHECKED, "connections",
                    "Connection probes were not requested or static configuration contains errors."));
        }
        findings.forEach(f -> output.println(f.status() + " " + f.key() + ": " + f.message()));
        return findings.stream().anyMatch(f -> f.status() == SetupChecks.Status.ERROR) ? 2 : 0;
    }

    static StandardEnvironment loadEnvironment(String[] args) {
        StandardEnvironment environment = initialEnvironment(args);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    private static StandardEnvironment initialEnvironment(String[] args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(SetupCommand.class));
        return environment;
    }

    /** Native JVM option is retained even when jpackage default arguments are replaced. */
    public static String[] applicationArguments(String[] args) {
        List<String> result = new ArrayList<>(Arrays.stream(args).filter(arg -> !setupArgument(arg)).toList());
        if (Boolean.getBoolean("taxonomy.native")) {
            Path config = configurationDirectory().resolve("application.properties");
            if (!Files.isRegularFile(config) || !Files.isReadable(config)) {
                throw new IllegalStateException("Native configuration is missing. Run Taxonomy-Configure first; no development defaults are used.");
            }
            // Keep the installation mandatory, even when CLI, JSON, JVM options or environment
            // supply optional overlays. Use Spring's precedence before loading ConfigData.
            String additional = initialEnvironment(result.toArray(String[]::new))
                    .getProperty("spring.config.additional-location", "");
            result.removeIf(arg -> arg.equals("--spring.config.additional-location")
                    || arg.startsWith("--spring.config.additional-location="));
            result.add("--spring.config.additional-location=" + config.toUri()
                    + (additional.isBlank() ? "" : "," + additional));
        }
        return result.toArray(String[]::new);
    }

    private static Path configurationDirectory() {
        String explicit = System.getProperty("taxonomy.config-directory");
        return explicit == null ? Path.of(System.getProperty("user.home"), ".taxonomy") : Path.of(explicit);
    }

    private static boolean setupArgument(String arg) {
        return arg.equals("--setup-help") || arg.equals("--check-configuration") || arg.startsWith("--check-configuration=")
                || arg.equals("--configure") || arg.startsWith("--configure=")
                || arg.equals("--configure-local") || arg.startsWith("--configure-local=");
    }
}
