package com.taxonomy.preferences.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Explicit non-secret configuration values and operator-owned deployment prerequisites. */
public final class ApplicationConfigurationBackupContributor implements BackupDataContributor {
    private static final String ENTRY = "configuration/application.json";
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final List<ExternalPrerequisite> PREREQUISITES = List.of(
            prerequisite(DependencyKind.DEPLOYMENT, ExternalResource.DATABASE_ENDPOINTS_AND_CREDENTIALS,
                    ExternalResource.NETWORK_AND_TLS, ExternalResource.FILESYSTEM_PATHS,
                    ExternalResource.RUNTIME_LIMITS_AND_SECURITY_POLICY, ExternalResource.CATALOGUE_SOURCE_LOCATIONS,
                    ExternalResource.LLM_AND_INTEGRATION_TARGETS),
            prerequisite(DependencyKind.IDENTITY_PROVIDERS, ExternalResource.PROVIDER_CONFIGURATION_AND_BACKUP,
                    ExternalResource.VERIFIED_PRINCIPAL_BINDINGS),
            prerequisite(DependencyKind.SECRET_STORES, ExternalResource.ENCRYPTION_KEY_CUSTODY, ExternalResource.REMOTE_CREDENTIALS),
            prerequisite(DependencyKind.OBJECT_STORAGE, ExternalResource.REFERENCED_CONTENT_CLOSURE));
    private final ConfigurableEnvironment environment;

    /** Composition supplies property sources that remain stable during the capture interval. */
    public ApplicationConfigurationBackupContributor(ConfigurableEnvironment source) { this.environment = Objects.requireNonNull(source); }
    @Override public BackupComponentId componentId() { return new BackupComponentId("application"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("configuration.application", "configuration.deployment",
            "external.identity-providers", "external.secret-stores", "external.object-storage"); }
    @Override public List<String> omissions(BackupProfile profile) {
        if (!profile.isInstallation()) return List.of("configuration: administrative deployment values are outside the selected workspace scope");
        return List.of("configuration: only explicitly typed non-secret business values are captured; other deployment properties remain operator-owned",
                "configuration: resolved deployment values precede persisted preferences; absent values remain null and require target review",
                "configuration: endpoints, paths, credentials, provider backups and external content availability are not inspected or captured",
                "configuration: configuration history is not retained; outgoing actions and changed target policy require explicit approval");
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        BackupProfile profile = snapshot.authorization().request().profile();
        if (!profile.isInstallation()) {
            PortableRows.document(sink, ENTRY, inventory(profile, Selection.OUTSIDE_SCOPE, null, List.of()));
            return;
        }
        Settings captured;
        try {
            var read = new Reader(sink);
            var preferences = new PreferencesBackupContributor.Settings(
                    read.integer("taxonomy.llm.rpm"), read.integer("taxonomy.llm.timeout-seconds"),
                    read.integer("taxonomy.rate-limit.per-minute"), read.integer("taxonomy.analysis.min-score"),
                    read.text("taxonomy.dsl.default-branch"), read.text("taxonomy.dsl.project-name"),
                    read.integer("taxonomy.dsl.auto-save-interval"), read.integer("taxonomy.limits.max-business-text"),
                    read.integer("taxonomy.limits.max-architecture-nodes"), read.integer("taxonomy.limits.max-export-nodes"),
                    read.text("taxonomy.diagram.policy"));
            var products = new ProductAnalysis(read.integer("taxonomy.analysis.product.batch-size"),
                    read.integer("taxonomy.analysis.product.min-score"));
            var portfolio = new Portfolio(read.integer("taxonomy.portfolio.max-import-requirements"),
                    read.integer("taxonomy.portfolio.max-import-characters"), read.integer("taxonomy.portfolio.max-analysis-batch"),
                    read.integer("taxonomy.portfolio.snapshot-stale-after-days"));
            var documents = new Documents(read.longNumber("taxonomy.limits.document.max-upload-bytes"),
                    read.integer("taxonomy.limits.document.max-pdf-pages"), read.integer("taxonomy.limits.document.max-extracted-characters"),
                    read.integer("taxonomy.limits.document.max-candidates"), read.integer("taxonomy.limits.document.max-llm-characters"),
                    read.longNumber("taxonomy.limits.document.max-docx-entry-bytes"), read.longNumber("taxonomy.limits.document.max-docx-text-bytes"),
                    read.decimal("taxonomy.limits.document.min-docx-inflate-ratio"));
            var features = new Features(read.flag("taxonomy.features.multi-repository-api.enabled"),
                    read.flag("taxonomy.document-templates.direct-word-enabled"), read.flag("taxonomy.catalogue.overlay.enabled"));
            captured = new Settings(preferences, products, portfolio, documents, features,
                    read.integer("taxonomy.context.max-history"), read.integer("taxonomy.analysis-draft.max-characters"),
                    read.text("taxonomy.report.time-zone"));
            sink.checkpoint();
        } catch (InterruptedIOException cancelled) {
            throw new InterruptedIOException("Application configuration capture interrupted");
        } catch (IOException | RuntimeException invalid) {
            // Placeholder/conversion/provider diagnostics can contain secrets or private deployment paths.
            throw new IOException("Application configuration cannot be exported safely");
        }
        PortableRows.document(sink, ENTRY, inventory(profile, Selection.INSTALLATION_CONFIGURATION, captured, PREREQUISITES));
    }

    private final class Reader {
        private final ComponentSink sink;
        private Reader(ComponentSink sink) { this.sink = sink; }
        private String text(String key) throws IOException {
            sink.checkpoint();
            String environmentAlias = key.equals("taxonomy.document-templates.direct-word-enabled")
                    ? "TAXONOMY_DIRECT_WORD_ENABLED"
                    : key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
            var filtered = new MutablePropertySources();
            filtered.addLast(new PropertySource<Object>("backup-business-value") {
                @Override public Object getProperty(String name) {
                    // Spring first probes the entire placeholder text before its key. Decline
                    // these probes without reading a property named after a fallback expression.
                    if (name.startsWith(key + ":") || name.startsWith(environmentAlias + ":")) return null;
                    // Restrict nested placeholders too, before touching the underlying source. Returning
                    // null here would incorrectly permit an unreviewed placeholder's fallback value.
                    if (!name.equals(key) && !name.equals(environmentAlias)) throw new IllegalArgumentException("Unreviewed configuration reference");
                    for (PropertySource<?> properties : environment.getPropertySources()) {
                        Object value = properties.getProperty(name);
                        if (value instanceof CharSequence text && text.length() > MAX_VALUE_LENGTH)
                            throw new IllegalArgumentException("Configuration value limit exceeded");
                        if (value != null) return value;
                    }
                    return null;
                }
            });
            String value = new PropertySourcesPropertyResolver(filtered).getProperty(key);
            if (value != null && value.length() > MAX_VALUE_LENGTH) throw new IOException("Configuration value limit exceeded");
            return value;
        }
        private <T> T converted(String key, Class<T> type) throws IOException {
            String value = text(key);
            if (value == null) return null;
            T result = DefaultConversionService.getSharedInstance().convert(value, type);
            if (result == null) throw new IOException("Empty typed configuration value");
            return result;
        }
        private Integer integer(String key) throws IOException { return converted(key, Integer.class); }
        private Long longNumber(String key) throws IOException { return converted(key, Long.class); }
        private Boolean flag(String key) throws IOException { return converted(key, Boolean.class); }
        private String decimal(String key) throws IOException {
            BigDecimal number = converted(key, BigDecimal.class);
            if (number == null) return null;
            if (Math.abs((long) number.scale()) > 32 || number.precision() > 32)
                throw new IOException("Configuration decimal limit exceeded");
            return number.stripTrailingZeros().toPlainString();
        }
    }

    private static ExternalPrerequisite prerequisite(DependencyKind kind, ExternalResource... resources) {
        return new ExternalPrerequisite(kind, ExternalStatus.NOT_CAPTURED_OPERATOR_VERIFICATION_REQUIRED, List.of(resources));
    }
    private static Inventory inventory(BackupProfile profile, Selection selection, Settings settings, List<ExternalPrerequisite> prerequisites) {
        return new Inventory(1, profile, selection, SettingsBasis.DEPLOYMENT_VALUES_BEFORE_PREFERENCES, settings,
                prerequisites, RestorePolicy.MANUAL_TARGET_REVIEW, AutomaticExecution.DISABLED_UNTIL_APPROVED);
    }

    public enum Selection { INSTALLATION_CONFIGURATION, OUTSIDE_SCOPE }
    public enum SettingsBasis { DEPLOYMENT_VALUES_BEFORE_PREFERENCES }
    public enum RestorePolicy { MANUAL_TARGET_REVIEW }
    public enum AutomaticExecution { DISABLED_UNTIL_APPROVED }
    public enum DependencyKind { DEPLOYMENT, IDENTITY_PROVIDERS, SECRET_STORES, OBJECT_STORAGE }
    public enum ExternalStatus { NOT_CAPTURED_OPERATOR_VERIFICATION_REQUIRED }
    public enum ExternalResource {
        DATABASE_ENDPOINTS_AND_CREDENTIALS, NETWORK_AND_TLS, FILESYSTEM_PATHS, RUNTIME_LIMITS_AND_SECURITY_POLICY,
        CATALOGUE_SOURCE_LOCATIONS, LLM_AND_INTEGRATION_TARGETS, PROVIDER_CONFIGURATION_AND_BACKUP,
        VERIFIED_PRINCIPAL_BINDINGS, ENCRYPTION_KEY_CUSTODY, REMOTE_CREDENTIALS, REFERENCED_CONTENT_CLOSURE
    }
    public record ProductAnalysis(Integer batchSize, Integer minimumScore) { }
    public record Portfolio(Integer maximumImportRequirements, Integer maximumImportCharacters,
                            Integer maximumAnalysisBatch, Integer snapshotStaleAfterDays) { }
    public record Documents(Long maximumUploadBytes, Integer maximumPdfPages, Integer maximumExtractedCharacters,
                            Integer maximumCandidates, Integer maximumLlmCharacters, Long maximumDocxEntryBytes,
                            Long maximumDocxTextBytes, String minimumDocxInflateRatio) { }
    public record Features(Boolean multiRepositoryApi, Boolean directWordEditing, Boolean catalogueOverlay) { }
    public record Settings(PreferencesBackupContributor.Settings preferenceDefaults, ProductAnalysis productAnalysis,
                           Portfolio portfolio, Documents documents, Features features, Integer maximumContextHistory,
                           Integer maximumAnalysisDraftCharacters, String reportTimeZone) { }
    public record ExternalPrerequisite(DependencyKind kind, ExternalStatus status, List<ExternalResource> resources) {
        public ExternalPrerequisite { resources = List.copyOf(resources); }
    }
    public record Inventory(int schemaVersion, BackupProfile profile, Selection selection, SettingsBasis settingsBasis,
                            Settings settings, List<ExternalPrerequisite> externalPrerequisites,
                            RestorePolicy restorePolicy, AutomaticExecution automaticExecution) {
        public Inventory { externalPrerequisites = List.copyOf(externalPrerequisites); }
    }
}
