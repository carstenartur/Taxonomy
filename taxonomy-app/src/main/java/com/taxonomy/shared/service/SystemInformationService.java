package com.taxonomy.shared.service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.tool.schema.Action;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** On-demand, allowlisted diagnostics. No paths, URLs, passwords or exception text leave this service. */
@Service
public class SystemInformationService {
    private static final String INSTANCE_ID = java.util.UUID.randomUUID().toString();
    private final EntityManagerFactory entityManagerFactory;
    private final Environment environment;

    public SystemInformationService(EntityManagerFactory entityManagerFactory, Environment environment) {
        this.entityManagerFactory = entityManagerFactory;
        this.environment = environment;
    }

    public Snapshot snapshot() {
        Runtime runtime = Runtime.getRuntime();
        var jvm = ManagementFactory.getRuntimeMXBean();
        String indexStorage = normalizeIndexStorage(environment.getProperty(
                "spring.jpa.properties.hibernate.search.backend.directory.type", "unknown"));
        return new Snapshot(Instant.now(), INSTANCE_ID, environment.getProperty("app.display-version", "unknown"),
                new RuntimeInfo(runtime.availableProcessors(), runtime.totalMemory() - runtime.freeMemory(),
                        runtime.maxMemory(), jvm.getUptime(), Instant.ofEpochMilli(jvm.getStartTime()),
                        System.getProperty("java.version"), System.getProperty("java.vendor"),
                        System.getProperty("os.name"), System.getProperty("os.arch")),
                database(), indexStorage, disks(indexStorage));
    }

    DatabaseInfo database() {
        List<String> warnings = new ArrayList<>();
        String schemaAction = "UNKNOWN";
        String product = null;
        String version = null;
        String driver = null;
        String driverVersion = null;
        String versionSource = "UNAVAILABLE";
        String storageSource = "UNAVAILABLE";
        String storage = "UNKNOWN";
        String lifetime = "UNKNOWN";
        String status = "UNAVAILABLE";
        DatabaseIdentity identity = unavailableIdentity("UNAVAILABLE");
        EngineStartInfo engineStart = unavailableStart("UNAVAILABLE");
        try {
            SessionFactory factory = entityManagerFactory.unwrap(SessionFactory.class);
            schemaAction = schemaAction(factory.getProperties());
            if (destructive(schemaAction)) {
                warnings.add("DESTRUCTIVE_SCHEMA_ACTION");
            } else if ("UNKNOWN".equals(schemaAction)) {
                warnings.add("SCHEMA_ACTION_UNKNOWN");
            }
            try (Session session = factory.openSession()) {
                session.setDefaultReadOnly(true);
                session.setHibernateFlushMode(FlushMode.MANUAL);
                Metadata metadata = session.doReturningWork(connection -> {
                    var md = connection.getMetaData();
                    return new Metadata(md.getDatabaseProductName(), md.getDatabaseProductVersion(),
                            md.getDriverName(), md.getDriverVersion(), md.getURL());
                });
                product = metadata.product();
                version = metadata.version();
                driver = metadata.driver();
                driverVersion = metadata.driverVersion();
                versionSource = version == null ? "UNAVAILABLE" : "JDBC_METADATA_FALLBACK";
                var internal = factory.unwrap(SessionFactoryImplementor.class);
                var registry = internal.getQueryEngine().getSqmFunctionRegistry();
                if (registry.findFunctionDescriptor(DatabaseDiagnosticFunctions.VERSION) != null) {
                    String queriedVersion = query(session, DatabaseDiagnosticFunctions.VERSION);
                    if (queriedVersion != null && !queriedVersion.isBlank()) {
                        version = queriedVersion;
                        versionSource = "DATABASE_QUERY";
                    }
                    String queriedStorage = registry.findFunctionDescriptor(DatabaseDiagnosticFunctions.STORAGE) == null
                            ? null : query(session, DatabaseDiagnosticFunctions.STORAGE);
                    if (List.of("IN_MEMORY", "FILE_BACKED", "READ_ONLY_RESOURCE", "SERVER_MANAGED")
                            .contains(queriedStorage == null ? "" : queriedStorage)) {
                        storage = queriedStorage;
                        storageSource = "DATABASE_QUERY";
                    }
                }
                if ("UNKNOWN".equals(storage)) {
                    storage = connectionStorage(metadata.url());
                    if (!"UNKNOWN".equals(storage)) {
                        storageSource = "JDBC_CONNECTION";
                    }
                }
                if ("IN_MEMORY".equals(storage)) {
                    // A remote HSQL server may itself run an in-memory database.
                    // Restarting Taxonomy is not the same as restarting that server.
                    lifetime = "IN_MEMORY".equals(connectionStorage(metadata.url()))
                            ? "APPLICATION_PROCESS" : "DATABASE_PROCESS";
                    warnings.add("IN_MEMORY_" + lifetime);
                } else if ("FILE_BACKED".equals(storage) || "SERVER_MANAGED".equals(storage)) {
                    lifetime = "STORAGE_DEPENDENT";
                    warnings.add("STORAGE_DURABILITY_UNVERIFIED");
                }
                status = "DATABASE_QUERY".equals(versionSource) && !"UNKNOWN".equals(storage)
                        ? "AVAILABLE" : "PARTIAL";
                // Optional probes follow the core diagnostics. A denied engine
                // start query must not discard measured identity/version/storage.
                Dialect dialect = internal.getJdbcServices().getDialect();
                identity = identity(session, registry, dialect);
                engineStart = engineStart(session, registry, dialect);
            }
        } catch (RuntimeException exception) {
            // A failed diagnostic must not hide the other system information.
            // Exception text may include a connection URL or secret: never return it.
            warnings.add("DATABASE_DIAGNOSTICS_UNAVAILABLE");
        }
        if ("UNKNOWN".equals(storage)) {
            warnings.add("PERSISTENCE_UNKNOWN");
        }
        if (environment.getProperty("taxonomy.init.reload-existing", Boolean.class, false)) {
            warnings.add("CATALOGUE_RELOAD_ENABLED");
        }
        return new DatabaseInfo(status, product, version, versionSource, driver, driverVersion,
                storage, storageSource, lifetime, schemaAction, List.copyOf(warnings), identity, engineStart);
    }

    private static DatabaseIdentity identity(Session session, SqmFunctionRegistry registry, Dialect dialect) {
        if (List.of(DatabaseDiagnosticFunctions.CURRENT_USER, DatabaseDiagnosticFunctions.SESSION_USER,
                DatabaseDiagnosticFunctions.CURRENT_DATABASE,
                DatabaseDiagnosticFunctions.CURRENT_SCHEMA).stream()
                .anyMatch(function -> registry.findFunctionDescriptor(function) == null)) {
            return unavailableIdentity("NOT_SUPPORTED");
        }
        try {
            Object[] values = session.createSelectionQuery("select " + DatabaseDiagnosticFunctions.CURRENT_USER
                            + "(), " + DatabaseDiagnosticFunctions.SESSION_USER + "(), "
                            + DatabaseDiagnosticFunctions.CURRENT_DATABASE + "(), "
                            + DatabaseDiagnosticFunctions.CURRENT_SCHEMA + "()", Object[].class)
                    .setTimeout(2).setCacheable(false).getSingleResult();
            // Preserve quoted identifiers, including case and surrounding spaces.
            String user = (String) values[0];
            String sessionUser = (String) values[1];
            String database = (String) values[2];
            String schema = (String) values[3];
            if (user == null && sessionUser == null && database == null && schema == null) {
                return unavailableIdentity("UNAVAILABLE");
            }
            return new DatabaseIdentity(user != null && sessionUser != null && database != null && schema != null
                    ? "AVAILABLE" : "PARTIAL", user, sessionUser, database, schema, "DATABASE_QUERY");
        } catch (RuntimeException exception) {
            return unavailableIdentity(diagnosticFailure(exception, dialect));
        }
    }

    private static EngineStartInfo engineStart(Session session, SqmFunctionRegistry registry, Dialect dialect) {
        if (registry.findFunctionDescriptor(DatabaseDiagnosticFunctions.ENGINE_START) == null) {
            return unavailableStart("NOT_SUPPORTED");
        }
        try {
            String value = session.createSelectionQuery("select " + DatabaseDiagnosticFunctions.ENGINE_START + "()", String.class)
                    .setTimeout(2).setCacheable(false).getSingleResult();
            if (value == null || value.isBlank()) {
                return unavailableStart("UNAVAILABLE");
            }
            try {
                return new EngineStartInfo("AVAILABLE", OffsetDateTime.parse(value.strip()).toInstant().toString(),
                        "DATABASE_QUERY", "UTC");
            } catch (DateTimeParseException noOffset) {
                // SQL Server datetime and Oracle DATE do not carry a timezone.
                // Keep their clock reading explicit instead of inventing UTC.
                String local = LocalDateTime.parse(value.strip()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return new EngineStartInfo("AVAILABLE", local, "DATABASE_QUERY", "SERVER_LOCAL_TIME");
            }
        } catch (RuntimeException exception) {
            return unavailableStart(diagnosticFailure(exception, dialect));
        }
    }

    private static DatabaseIdentity unavailableIdentity(String status) {
        return new DatabaseIdentity(status, null, null, null, null, "UNAVAILABLE");
    }

    private static EngineStartInfo unavailableStart(String status) {
        return new EngineStartInfo(status, null, "UNAVAILABLE", null);
    }

    static String diagnosticFailure(Throwable failure, Dialect dialect) {
        // Classify only explicit permission errors. Oracle ORA-00942 and HSQL's
        // combined missing-object/permission error are deliberately ambiguous.
        for (int depth = 0; failure != null && depth < 10; depth++, failure = failure.getCause()) {
            if (failure instanceof SQLException sql) {
                boolean denied = dialect instanceof PostgreSQLDialect && "42501".equals(sql.getSQLState())
                        || dialect instanceof SQLServerDialect && List.of(229, 297, 300).contains(sql.getErrorCode())
                        || dialect instanceof OracleDialect && sql.getErrorCode() == 1031;
                if (denied) return "NOT_PERMITTED";
            }
        }
        return "UNAVAILABLE";
    }

    private static String query(Session session, String function) {
        try {
            String value = session.createSelectionQuery("select " + function + "()", String.class)
                    .setTimeout(2).setCacheable(false).getSingleResult();
            return value == null ? null : value.strip();
        } catch (RuntimeException exception) {
            return null; // Visible source flags identify the metadata/unknown fallback.
        }
    }

    static String normalizeIndexStorage(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "local-heap", "local-filesystem" -> normalized;
            default -> "unknown";
        };
    }

    static String connectionStorage(String url) {
        String value = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (value.startsWith("jdbc:hsqldb:mem:") || value.startsWith("jdbc:h2:mem:")) {
            return "IN_MEMORY";
        }
        if (value.startsWith("jdbc:hsqldb:file:") || value.startsWith("jdbc:h2:file:")) {
            return "FILE_BACKED";
        }
        if (value.startsWith("jdbc:hsqldb:res:")) {
            return "READ_ONLY_RESOURCE";
        }
        if (value.startsWith("jdbc:postgresql:") || value.startsWith("jdbc:sqlserver:")
                || value.startsWith("jdbc:oracle:")) {
            return "SERVER_MANAGED";
        }
        return "UNKNOWN";
    }

    static String schemaAction(Map<String, Object> settings) {
        try {
            Object jpaAction = settings.get("jakarta.persistence.schema-generation.database.action");
            Action action = jpaAction != null
                    ? Action.interpretJpaSetting(jpaAction)
                    : Action.interpretHbm2ddlSetting(settings.get("hibernate.hbm2ddl.auto"));
            return action.name();
        } catch (IllegalArgumentException exception) {
            return "UNKNOWN";
        }
    }

    static boolean destructive(String action) {
        return List.of("CREATE", "CREATE_DROP", "DROP", "TRUNCATE").contains(action);
    }

    List<DiskInfo> disks(String indexStorage) {
        Map<Path, DiskInfo> locations = new LinkedHashMap<>();
        List<DiskInfo> unavailable = new ArrayList<>();
        addDisk("TEMPORARY_FILES", System.getProperty("java.io.tmpdir"), locations, unavailable);
        if ("local-filesystem".equals(indexStorage)) {
            addDisk("SEARCH_INDEX", environment.getProperty(
                    "spring.jpa.properties.hibernate.search.backend.directory.root"), locations, unavailable);
        }
        List<DiskInfo> result = new ArrayList<>(locations.values());
        result.addAll(unavailable);
        return List.copyOf(result);
    }

    private static void addDisk(String purpose, String path, Map<Path, DiskInfo> locations,
                                List<DiskInfo> unavailable) {
        try {
            if (path == null || path.isBlank()) {
                unavailable.add(new DiskInfo(List.of(purpose), null, null, "UNAVAILABLE"));
                return;
            }
            // FileStore equality/name/type do not portably identify a volume.
            // Merge only the same resolved directory. Other rows may share space:
            // the UI states this explicitly and never computes an aggregate total.
            Path location = Path.of(path).toRealPath();
            DiskInfo previous = locations.get(location);
            List<String> purposes = new ArrayList<>();
            if (previous != null) {
                purposes.addAll(previous.purposes());
                purposes.add(purpose);
                locations.put(location, new DiskInfo(List.copyOf(purposes),
                        previous.totalBytes(), previous.usableBytes(), previous.status()));
                return;
            }
            FileStore store = Files.getFileStore(location);
            purposes.add(purpose);
            locations.put(location, new DiskInfo(List.copyOf(purposes), store.getTotalSpace(),
                    store.getUsableSpace(), "AVAILABLE"));
        } catch (java.io.IOException | RuntimeException exception) {
            unavailable.add(new DiskInfo(List.of(purpose), null, null, "UNAVAILABLE"));
        }
    }

    private record Metadata(String product, String version, String driver, String driverVersion, String url) { }
    public record Snapshot(Instant timestamp, String instanceId, String applicationVersion, RuntimeInfo runtime,
                           DatabaseInfo database, String indexStorage, List<DiskInfo> disks) { }
    public record RuntimeInfo(int availableProcessors, long heapUsedBytes, long heapMaxBytes,
                              long uptimeMillis, Instant startTime, String javaVersion, String javaVendor,
                              String osName, String osArchitecture) { }
    public record DatabaseInfo(String status, String product, String version, String versionSource,
                               String driver, String driverVersion, String storage, String storageSource,
                               String lifetime, String schemaAction, List<String> warnings,
                               DatabaseIdentity identity, EngineStartInfo engineStart) { }
    public record DatabaseIdentity(String status, String user, String sessionUser, String database,
                                   String schema, String source) { }
    public record EngineStartInfo(String status, String value, String source, String timeBasis) { }
    public record DiskInfo(List<String> purposes, Long totalBytes, Long usableBytes, String status) { }
}
