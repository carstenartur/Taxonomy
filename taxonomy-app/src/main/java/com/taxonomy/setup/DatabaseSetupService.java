package com.taxonomy.setup;

import com.taxonomy.preferences.PreferencesService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prepares deployment instructions only; never reconfigures the running datasource. */
@Service
public class DatabaseSetupService {
    static final String DISMISSED = "setup.database.dismissed";
    private static final String LEGACY_JPA_SCHEMA_PROPERTY = "spring.jpa.properties.javax.persistence.schema-generation.database.action";
    static final String SCHEMA_CONFLICT_MESSAGE = "Configuration export is blocked by existing schema overrides. "
            + "Set spring.jpa.properties.hibernate.hbm2ddl.auto, spring.jpa.properties.jakarta.persistence.schema-generation.database.action "
            + "and legacy " + LEGACY_JPA_SCHEMA_PROPERTY + " "
            + "to validate or remove them; also remove any non-validate spring.jpa.hibernate.ddl-auto from command-line, JVM or "
            + "SPRING_APPLICATION_JSON overrides. Then retry.";
    private static final Set<String> DATABASE_PROFILES = Set.of("hsqldb", "hsqldb-file", "postgres", "mssql", "oracle");
    private static final List<String> SCHEMA_PROPERTIES = List.of("spring.jpa.hibernate.ddl-auto",
            "spring.jpa.properties.hibernate.hbm2ddl.auto",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action");
    private final Environment environment;
    private final PreferencesService preferences;

    public DatabaseSetupService(Environment environment, PreferencesService preferences) {
        this.environment = environment;
        this.preferences = preferences;
    }

    /** Password-bearing input deliberately has a fixed toString, including on invalid requests. */
    @Schema(requiredProperties = {"type", "host", "port", "database", "username"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ConnectionSettings(String type, String host, Integer port, String database,
                                     String username,
                                     @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
                                     Boolean tls, String caCertificate) {
        @Override public String toString() { return "Database connection settings [redacted]"; }
    }
    public record State(String database, boolean inMemory, boolean promptRequired, boolean deploymentManaged) { }
    public record Preview(String jdbcUrl, String profiles, String properties, String helmValues,
                          List<SetupChecks.Finding> findings) { }
    public record CheckResult(List<SetupChecks.Finding> findings) { }

    public State status() {
        String url = environment.getProperty("spring.datasource.url", "");
        boolean memory = url.startsWith("jdbc:hsqldb:mem:");
        boolean file = url.startsWith("jdbc:hsqldb:file:");
        String database = url.startsWith("jdbc:postgresql:") ? "postgres"
                : url.startsWith("jdbc:sqlserver:") ? "mssql"
                : url.startsWith("jdbc:oracle:") ? "oracle"
                : file ? "hsqldb-file" : url.startsWith("jdbc:hsqldb:") ? "hsqldb" : "unknown";
        boolean managed = currentProfiles().contains("kubernetes")
                || !environment.getProperty("KUBERNETES_SERVICE_HOST", "").isBlank();
        return new State(database, memory, (memory || file) && !preferences.getBoolean(DISMISSED, false), managed);
    }

    public void dismiss(String actor) throws IOException {
        preferences.update(Map.of(DISMISSED, true), actor);
    }

    public Preview preview(ConnectionSettings input) {
        validate(input);
        if (hasSchemaConflict()) { throw new SchemaConflictException(); }
        return prepare(input);
    }

    private Preview prepare(ConnectionSettings input) {
        boolean tls = !Boolean.FALSE.equals(input.tls());
        String url = jdbcUrl(input, tls);
        Set<String> profiles = selectedProfiles(input.type());
        Map<String, String> proposed = new LinkedHashMap<>();
        proposed.put("spring.profiles.active", String.join(",", profiles));
        proposed.put("spring.datasource.url", url);
        proposed.put("spring.datasource.username", input.username());
        // Native Hibernate and JPA actions can otherwise override ddl-auto.
        SCHEMA_PROPERTIES.forEach(key -> proposed.put(key, "validate"));
        StringBuilder properties = new StringBuilder("# Review deployment settings and restart manually; no existing data is transferred.\n");
        proposed.forEach((key, value) -> properties.append(key).append('=').append(propertyValue(value)).append('\n'));
        properties.append("# Supply the datasource password through your deployment secret mechanism.\n");
        if (input.type().equals("oracle") && tls) {
            properties.append("# Verified TCPS uses the JVM trust store; provision trusted database CA certificates there.\n");
        }
        Set<String> helmProfiles = new LinkedHashSet<>(profiles);
        helmProfiles.add("kubernetes");
        String helm = "# Merge into existing values; retain existingSecret and its secretEnv mappings.\n"
                + "database:\n  type: existing\n  url: \"\"\n  username: \"\"\n"
                + "config:\n  SPRING_PROFILES_ACTIVE: \"" + String.join(",", helmProfiles) + "\"\n"
                + "  TAXONOMY_DDL_AUTO: \"validate\"\n"
                // These names survive the image's sh launcher. Native JPA overrides cannot be safely
                // replaced by an underscore alias, so preview blocks conflicts instead of exporting it.
                + "  SPRING_JPA_HIBERNATE_DDL_AUTO: \"validate\"\n"
                + "  SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO: \"validate\"\n"
                + "# Update these keys in the existing Secret separately:\n"
                + "# SPRING_DATASOURCE_URL=" + url + "\n"
                + "# SPRING_DATASOURCE_USERNAME=" + input.username() + "\n"
                + "# Supply the datasource password through your deployment secret mechanism.\n"
                + "# Review other schema/driver overrides before applying and restarting manually.\n";
        // Check the proposed connection/schema only; the running installation's auth and AI settings
        // are neither copied nor presented as database readiness findings.
        List<SetupChecks.Finding> findings = new ArrayList<>(SetupChecks.check(proposed::get, Set.of(input.type()))
                .stream().filter(f -> f.key().startsWith("database.") || f.key().startsWith("spring.datasource.")
                        || f.key().equals("spring.profiles.active") || SCHEMA_PROPERTIES.contains(f.key())).toList());
        findings.add(new SetupChecks.Finding(tls ? SetupChecks.Status.OK : SetupChecks.Status.WARNING, "database.tls",
                tls ? "TLS with server identity verification is selected; trusted CA certificates must be provisioned in the target environment."
                        : "TLS is disabled explicitly; credentials and data may cross the network without transport protection."));
        if (!input.type().equals("postgres")) {
            findings.add(new SetupChecks.Finding(SetupChecks.Status.WARNING, "database.support",
                    "SQL Server and Oracle remain evaluation paths; production migration, persistence, restart and recovery acceptance is not established."));
        }
        findings.add(new SetupChecks.Finding(SetupChecks.Status.NOT_CHECKED, "database.deployment",
                "Deployment settings remain authoritative. Review driver, dialect, profile includes and schema overrides before applying configuration and restarting manually."));
        findings.add(new SetupChecks.Finding(SetupChecks.Status.NOT_CHECKED, "database.transfer",
                "No existing records are copied. A connection check does not establish target schema readiness or transfer existing data."));
        return new Preview(url, String.join(",", profiles), properties.toString(), helm, List.copyOf(findings));
    }

    /** Checks only an independent JDBC connection, never OIDC discovery or the active datasource. */
    public CheckResult check(ConnectionSettings input) {
        validate(input);
        Preview preview = prepare(input);
        Map<String, String> connection = new LinkedHashMap<>();
        connection.put("spring.datasource.url", preview.jdbcUrl());
        connection.put("spring.datasource.username", input.username());
        connection.put("spring.datasource.password", input.password() == null ? "" : input.password());
        List<SetupChecks.Finding> findings = new ArrayList<>(preview.findings());
        if (hasSchemaConflict()) {
            findings.add(new SetupChecks.Finding(SetupChecks.Status.WARNING, "database.schema-overrides", SCHEMA_CONFLICT_MESSAGE));
        }
        findings.add(SetupProbe.checkDatabase(connection::get));
        return new CheckResult(List.copyOf(findings));
    }

    /** Reads only the schema actions; never rewrites JSON, JVM options or other deployment settings. */
    private boolean hasSchemaConflict() {
        try {
            // Use Boot's actual map binding so bracket notation cannot hide an effective native action.
            // Inspect only these fixed native actions; no other settings are copied, emitted or logged.
            Map<String, String> nativeSettings = Binder.get(environment)
                    .bind("spring.jpa.properties", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
            for (String key : List.of(SCHEMA_PROPERTIES.get(1), SCHEMA_PROPERTIES.get(2), LEGACY_JPA_SCHEMA_PROPERTY)) {
                String value = environment.getProperty(key);
                String bound = nativeSettings.get(key.substring("spring.jpa.properties.".length()));
                // Blank/none also suppress validation when supplied as native Hibernate/JPA settings.
                if ((value != null && !isValidate(value)) || (bound != null && !isValidate(bound))) { return true; }
            }
            if (environment instanceof ConfigurableEnvironment configurable) {
                for (var source : configurable.getPropertySources()) {
                    // Spring Boot's aggregate reflects lower-precedence sources too; inspect real sources.
                    if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) { continue; }
                    // Generated shell-safe aliases replace this source, but cannot outrank sources above it.
                    if (source instanceof SystemEnvironmentPropertySource) { break; }
                    // Match Binder's relaxed names too, such as command-line ddlAuto.
                    for (var configurationSource : ConfigurationPropertySources.from(source)) {
                        var setting = configurationSource.getConfigurationProperty(ConfigurationPropertyName.of(SCHEMA_PROPERTIES.getFirst()));
                        if (setting != null) {
                            return !isValidate(environment.resolveRequiredPlaceholders(setting.getValue().toString()));
                        }
                    }
                }
            }
            return false;
        } catch (RuntimeException unresolvedSetting) {
            // Unresolved schema placeholders cannot establish safe export; never retain their values/causes.
            return true;
        }
    }

    private static boolean isValidate(String value) { return value != null && value.trim().equalsIgnoreCase("validate"); }

    static final class SchemaConflictException extends RuntimeException {
        private SchemaConflictException() { super(SCHEMA_CONFLICT_MESSAGE); }
    }

    private Set<String> currentProfiles() {
        String[] active = environment.getActiveProfiles();
        return new LinkedHashSet<>(Arrays.asList(active.length == 0 ? environment.getDefaultProfiles() : active));
    }

    private Set<String> selectedProfiles(String database) {
        Set<String> result = new LinkedHashSet<>();
        result.add(database);
        for (String profile : currentProfiles()) {
            if (!profile.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) { throw invalid(); }
            if (!DATABASE_PROFILES.contains(profile)) { result.add(profile); }
        }
        return result;
    }

    private static void validate(ConnectionSettings input) {
        if (input == null || input.type() == null || !Set.of("postgres", "mssql", "oracle").contains(input.type())
                || input.host() == null || input.host().length() > 253
                || !(input.host().matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
                    || input.host().matches("\\[[A-Fa-f0-9:]{2,45}\\]"))
                || input.port() == null || input.port() < 1 || input.port() > 65535
                || input.database() == null || !input.database().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || input.username() == null || !input.username().matches("[A-Za-z0-9_@.$\\\\-]{1,128}")
                || (input.password() != null && input.password().length() > 4096)) { throw invalid(); }
        String ca = input.caCertificate();
        if (ca != null && !ca.isEmpty() && (!input.type().equals("postgres") || Boolean.FALSE.equals(input.tls())
                || ca.length() > 256 || !ca.matches("/[A-Za-z0-9_./-]+") || Arrays.asList(ca.split("/")).contains(".."))) {
            throw invalid();
        }
    }

    private static String jdbcUrl(ConnectionSettings input, boolean tls) {
        String address = input.host() + ":" + input.port();
        return switch (input.type()) {
            case "postgres" -> "jdbc:postgresql://" + address + "/" + input.database()
                    + "?sslmode=" + (tls ? "verify-full" : "disable")
                    + ((input.caCertificate() == null || input.caCertificate().isEmpty()) ? ""
                        : "&sslrootcert=" + URLEncoder.encode(input.caCertificate(), StandardCharsets.UTF_8));
            // Microsoft's authority parser splits on the first colon, including inside IPv6 brackets.
            case "mssql" -> (input.host().startsWith("[")
                    ? "jdbc:sqlserver://;serverName=" + input.host().substring(1, input.host().length() - 1) + ";portNumber=" + input.port()
                    : "jdbc:sqlserver://" + address) + ";databaseName=" + input.database()
                    + ";encrypt=" + tls + ";trustServerCertificate=false";
            case "oracle" -> "jdbc:oracle:thin:@(DESCRIPTION="
                    + "(ADDRESS=(PROTOCOL=" + (tls ? "TCPS" : "TCP") + ")(HOST=" + input.host() + ")(PORT=" + input.port() + "))"
                    + "(CONNECT_DATA=(SERVICE_NAME=" + input.database() + "))" + (tls ? "(SECURITY=(SSL_SERVER_DN_MATCH=YES))" : "") + ")";
            default -> throw invalid();
        };
    }

    private static String propertyValue(String value) { return value.replace("\\", "\\\\"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid database connection settings."); }
}
