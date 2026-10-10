package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.preferences.backup.ApplicationConfigurationBackupContributor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InterruptedIOException;

import static com.taxonomy.backup.runtime.CurrentStateExportIT.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationConfigurationExportIT {
    private static final String PATH = "configuration/application.json";
    private static final JsonMapper JSON = new JsonMapper();

    @ParameterizedTest
    @EnumSource(value = BackupProfile.class, names = {"INSTALLATION_CURRENT", "INSTALLATION_FULL"})
    void resolvesExplicitBusinessSettingsAndDeclaresOperatorPrerequisites(BackupProfile profile) throws Exception {
        var environment = new MockEnvironment()
                .withProperty("TAXONOMY_LLM_RPM", "13")
                .withProperty("taxonomy.dsl.project-name", "Configured project")
                .withProperty("taxonomy.limits.document.max-upload-bytes", "4294967296")
                .withProperty("taxonomy.limits.document.min-docx-inflate-ratio", "1E-2")
                .withProperty("taxonomy.features.multi-repository-api.enabled", "true")
                .withProperty("taxonomy.document-templates.direct-word-enabled", "false")
                .withProperty("taxonomy.catalogue.overlay.enabled", "false")
                .withProperty("spring.datasource.url", "jdbc:vendor://DB-PATH-SECRET?password=DB-SECRET")
                .withProperty("taxonomy.dsl.remote-url", "https://user:URL-SECRET@private.test/PATH-SECRET")
                .withProperty("taxonomy.dsl.remote-token", "TOKEN-SECRET")
                .withProperty("taxonomy.backup.keyset-file", "/KEY-PATH-SECRET")
                .withProperty("taxonomy.catalogue.resource", "/CATALOGUE-PATH-SECRET")
                .withProperty("spring.security.oauth2.client.registration.keycloak.client-secret", "IDP-SECRET")
                .withProperty("arbitrary.future-setting", "UNCLASSIFIED-SECRET");
        environment.getPropertySources().addLast(new PropertiesPropertySource("application-defaults",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        var output = export(environment, profile);
        JsonNode document = JSON.readTree(output.entries.get(PATH));
        assertThat(document.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(document.path("profile").asText()).isEqualTo(profile.name());
        assertThat(document.path("selection").asText()).isEqualTo("INSTALLATION_CONFIGURATION");
        JsonNode settings = document.path("settings");
        assertThat(settings.path("preferenceDefaults").path("llmRequestsPerMinute").asInt()).isEqualTo(13);
        assertThat(settings.path("preferenceDefaults").path("projectName").asText()).isEqualTo("Configured project");
        assertThat(settings.path("productAnalysis").path("batchSize").asInt()).isEqualTo(10);
        assertThat(settings.path("portfolio").path("maximumImportCharacters").asInt()).isEqualTo(500000);
        assertThat(settings.path("documents").path("maximumUploadBytes").asLong()).isEqualTo(4294967296L);
        assertThat(settings.path("documents").path("minimumDocxInflateRatio").asText()).isEqualTo("0.01");
        assertThat(settings.path("features").path("multiRepositoryApi").asBoolean()).isTrue();
        assertThat(settings.path("features").path("directWordEditing").asBoolean(true)).isFalse();
        assertThat(settings.path("features").path("catalogueOverlay").asBoolean(true)).isFalse();
        assertThat(settings.path("reportTimeZone").asText()).isEqualTo("Europe/Berlin");
        assertThat(document.path("restorePolicy").asText()).isEqualTo("MANUAL_TARGET_REVIEW");
        assertThat(document.path("automaticExecution").asText()).isEqualTo("DISABLED_UNTIL_APPROVED");
        assertThat(output.text()).contains("DEPLOYMENT", "IDENTITY_PROVIDERS", "SECRET_STORES", "OBJECT_STORAGE",
                        "NOT_CAPTURED_OPERATOR_VERIFICATION_REQUIRED")
                .doesNotContain("DB-SECRET", "DB-PATH-SECRET", "URL-SECRET", "PATH-SECRET", "TOKEN-SECRET", "KEY-PATH-SECRET",
                        "CATALOGUE-PATH-SECRET", "IDP-SECRET", "UNCLASSIFIED-SECRET");
        assertThat(environment.getProperty("taxonomy.dsl.remote-token")).isEqualTo("TOKEN-SECRET");
        assertThat(environment.getProperty("taxonomy.features.multi-repository-api.enabled")).isEqualTo("true");
    }

    @ParameterizedTest
    @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "SELECTED_VERSION", "REPOSITORY_HISTORY"})
    void scopedCaptureDoesNotInspectAdministrativeDeploymentValues(BackupProfile profile) throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new PropertySource<Object>("scope-guard") {
            @Override public Object getProperty(String key) { throw new AssertionError("Scoped capture inspected deployment"); }
        });
        JsonNode document = JSON.readTree(export(environment, profile).entries.get(PATH));
        assertThat(document.path("selection").asText()).isEqualTo("OUTSIDE_SCOPE");
        assertThat(document.path("settings").isNull()).isTrue();
        assertThat(document.path("externalPrerequisites").isEmpty()).isTrue();
    }

    @Test void absentValuesRemainUnresolvedAndNoRuntimeDefaultsAreInvented() throws Exception {
        JsonNode document = JSON.readTree(export(new MockEnvironment(), BackupProfile.INSTALLATION_CURRENT).entries.get(PATH));
        JsonNode settings = document.path("settings");
        assertThat(settings.path("preferenceDefaults").path("llmRequestsPerMinute").isNull()).isTrue();
        assertThat(settings.path("documents").path("maximumUploadBytes").isNull()).isTrue();
        assertThat(settings.path("documents").path("minimumDocxInflateRatio").isNull()).isTrue();
        assertThat(settings.path("features").path("multiRepositoryApi").isNull()).isTrue();
        assertThat(settings.path("reportTimeZone").isNull()).isTrue();
        assertThat(document.path("externalPrerequisites").size()).isEqualTo(4);
    }

    @ParameterizedTest
    @CsvSource({
            "taxonomy.llm.rpm,INTERNAL-SECRET", "taxonomy.llm.rpm,2147483648",
            "taxonomy.limits.document.max-upload-bytes,9223372036854775808",
            "taxonomy.features.multi-repository-api.enabled,INTERNAL-SECRET",
            "taxonomy.features.multi-repository-api.enabled,' '",
            "taxonomy.limits.document.min-docx-inflate-ratio,NaN",
            "taxonomy.limits.document.min-docx-inflate-ratio,1E-100000",
            "taxonomy.llm.rpm,${MISSING_SECRET}"
    })
    void rejectsMalformedValuesWithoutLeakingProviderOrParserDiagnostics(String key, String value) {
        var output = new Contents();
        assertThatThrownBy(() -> new ApplicationConfigurationBackupContributor(new MockEnvironment().withProperty(key, value))
                .write(snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output))
                .isInstanceOf(IOException.class).hasMessage("Application configuration cannot be exported safely").hasNoCause();
        assertThat(output.entries).isEmpty();
    }

    @Test void boundsTextValuesAndRedactsPropertyProviderFailures() {
        var providerFailure = new MockEnvironment();
        providerFailure.getPropertySources().addFirst(new PropertySource<Object>("broken-provider") {
            @Override public Object getProperty(String key) {
                throw new IllegalStateException("INTERNAL-SECRET", new IOException("CAUSE-SECRET"));
            }
        });
        for (MockEnvironment environment : new MockEnvironment[]{
                new MockEnvironment().withProperty("taxonomy.dsl.project-name", "x".repeat(4097)), providerFailure
        }) {
            var output = new Contents();
            assertThatThrownBy(() -> new ApplicationConfigurationBackupContributor(environment).write(
                    snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output))
                    .isInstanceOf(IOException.class).hasMessage("Application configuration cannot be exported safely").hasNoCause();
            assertThat(output.entries).isEmpty();
        }
    }

    @Test void neverReadsSecretOrEndpointPropertiesEvenToDecideWhetherTheyArePresent() throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new PropertySource<Object>("secret-guard") {
            @Override public Object getProperty(String key) {
                assertThat(key.toLowerCase(java.util.Locale.ROOT)).doesNotContain("remote", "keyset", "password", "secret", "datasource", "oauth", "resource");
                return null;
            }
        });
        export(environment, BackupProfile.INSTALLATION_FULL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"${taxonomy.dsl.remote-token}", "${spring.datasource.password}",
            "${PRIVATE_OVERRIDE:public-default}", "${TAXONOMY_DSL_PROJECT_NAME}",
            "${TAXONOMY_DSL_PROJECT_NAME:${spring.datasource.password}}"})
    void nestedPlaceholdersCannotExposeUnreviewedPropertiesAsBusinessText(String expression) {
        var environment = new MockEnvironment().withProperty("taxonomy.dsl.project-name", expression)
                .withProperty("taxonomy.dsl.remote-token", "TOKEN-SECRET")
                .withProperty("spring.datasource.password", "DB-SECRET")
                .withProperty("PRIVATE_OVERRIDE", "PRIVATE-SECRET");
        if (expression.equals("${TAXONOMY_DSL_PROJECT_NAME}"))
            environment.setProperty("TAXONOMY_DSL_PROJECT_NAME", "${taxonomy.dsl.remote-token}");
        environment.getPropertySources().addFirst(new PropertySource<Object>("unreviewed-reference-guard") {
            @Override public Object getProperty(String key) {
                assertThat(key).isNotIn("taxonomy.dsl.remote-token", "spring.datasource.password", "PRIVATE_OVERRIDE");
                return null;
            }
        });
        var output = new Contents();
        assertThatThrownBy(() -> new ApplicationConfigurationBackupContributor(environment).write(
                snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output))
                .isInstanceOf(IOException.class).hasMessage("Application configuration cannot be exported safely").hasNoCause();
        assertThat(output.entries).isEmpty();
    }

    @Test void cancellationRemainsInterruptedIoWithoutLeakingDiagnostics() {
        var output = new Contents();
        ComponentSink sink = new ComponentSink() {
            int checkpoints;
            @Override public BackupEntry write(String path, java.io.InputStream input) throws IOException { return output.write(path, input); }
            @Override public void checkpoint() throws IOException {
                if (++checkpoints == 3) {
                    var failure = new InterruptedIOException("INTERNAL-SECRET");
                    failure.initCause(new IOException("CAUSE-SECRET")); failure.addSuppressed(new IOException("SUPPRESSED-SECRET"));
                    throw failure;
                }
            }
        };
        assertThatThrownBy(() -> new ApplicationConfigurationBackupContributor(new MockEnvironment()).write(
                snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), sink))
                .isInstanceOf(InterruptedIOException.class).hasMessage("Application configuration capture interrupted")
                .hasNoCause().satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
        assertThat(output.entries).isEmpty();
    }

    @Test void laterCaptureUsesNewPropertyValuesWithoutChangingAnEarlierExport() throws Exception {
        var environment = new MockEnvironment().withProperty("taxonomy.dsl.project-name", "first");
        var first = export(environment, BackupProfile.INSTALLATION_CURRENT);
        environment.setProperty("taxonomy.dsl.project-name", "second");
        assertThat(export(environment, BackupProfile.INSTALLATION_CURRENT).text()).contains("second").doesNotContain("first");
        assertThat(first.text()).contains("first").doesNotContain("second");
    }

    @Test void documentedAliasFallbackDoesNotReadPropertiesNamedAfterTheEntireExpression() throws Exception {
        var environment = new MockEnvironment().withProperty("taxonomy.dsl.project-name", "${TAXONOMY_DSL_PROJECT_NAME:Public default}")
                .withProperty("TAXONOMY_DSL_PROJECT_NAME:Public default", "PRIVATE-SECRET");
        environment.getPropertySources().addFirst(new PropertySource<Object>("fallback-guard") {
            @Override public Object getProperty(String key) {
                assertThat(key).doesNotContain(":");
                return null;
            }
        });
        JsonNode document = JSON.readTree(export(environment, BackupProfile.INSTALLATION_CURRENT).entries.get(PATH));
        assertThat(document.path("settings").path("preferenceDefaults").path("projectName").asText()).isEqualTo("Public default");
        environment.setProperty("TAXONOMY_DSL_PROJECT_NAME", "Explicit value");
        JsonNode overridden = JSON.readTree(export(environment, BackupProfile.INSTALLATION_CURRENT).entries.get(PATH));
        assertThat(overridden.path("settings").path("preferenceDefaults").path("projectName").asText()).isEqualTo("Explicit value");
    }

    @Test void usesSpringConversionForValidDeployedScalarValues() throws Exception {
        for (String flag : new String[]{"on", "off", "yes", "no", "1", "0"}) {
            var environment = new MockEnvironment().withProperty("taxonomy.llm.rpm", " 13 ")
                    .withProperty("taxonomy.llm.timeout-seconds", "0x20")
                    .withProperty("taxonomy.limits.document.max-upload-bytes", " 4294967296 ")
                    .withProperty("taxonomy.limits.document.max-docx-entry-bytes", "0x100000000")
                    .withProperty("taxonomy.features.multi-repository-api.enabled", flag)
                    .withProperty("taxonomy.limits.document.min-docx-inflate-ratio", " 0.0100 ");
            JsonNode settings = JSON.readTree(export(environment, BackupProfile.INSTALLATION_CURRENT).entries.get(PATH)).path("settings");
            assertThat(settings.path("preferenceDefaults").path("llmRequestsPerMinute").asInt()).isEqualTo(13);
            assertThat(settings.path("preferenceDefaults").path("llmTimeoutSeconds").asInt()).isEqualTo(32);
            assertThat(settings.path("documents").path("maximumUploadBytes").asLong()).isEqualTo(4294967296L);
            assertThat(settings.path("documents").path("maximumDocxEntryBytes").asLong()).isEqualTo(4294967296L);
            assertThat(settings.path("documents").path("minimumDocxInflateRatio").asText()).isEqualTo("0.01");
            assertThat(settings.path("features").path("multiRepositoryApi").booleanValue())
                    .isEqualTo(environment.getProperty("taxonomy.features.multi-repository-api.enabled", Boolean.class));
        }
    }

    @ParameterizedTest
    @CsvSource({"application.properties,true", "application-keycloak.properties,false"})
    void packagedDefaultsAndDocumentedWordOverrideWorkWithoutCanonicalOverrides(String profile, boolean defaultWordEditing) throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("base-defaults",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        if (!profile.equals("application.properties")) environment.getPropertySources().addFirst(new PropertiesPropertySource("profile-defaults",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource(profile))));
        JsonNode defaults = JSON.readTree(export(environment, BackupProfile.INSTALLATION_CURRENT).entries.get(PATH));
        assertThat(defaults.path("settings").path("features").path("directWordEditing").booleanValue()).isEqualTo(defaultWordEditing);
        environment.setProperty("TAXONOMY_DIRECT_WORD_ENABLED", Boolean.toString(!defaultWordEditing));
        JsonNode overridden = JSON.readTree(export(environment, BackupProfile.INSTALLATION_CURRENT).entries.get(PATH));
        assertThat(overridden.path("settings").path("features").path("directWordEditing").booleanValue()).isEqualTo(!defaultWordEditing);
    }

    private static Contents export(MockEnvironment environment, BackupProfile profile) throws IOException {
        var output = new Contents();
        new ApplicationConfigurationBackupContributor(environment).write(snapshot(profile,
                profile.isInstallation() ? new BackupScope.Installation() : new BackupScope.Workspace("repo", "workspace")), output);
        return output;
    }
}
