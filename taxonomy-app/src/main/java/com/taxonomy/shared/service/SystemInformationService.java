package com.taxonomy.shared.service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.tool.schema.Action;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** On-demand, allowlisted diagnostics. No paths, URLs, credentials or exception text leave this service. */
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
        String indexStorage = environment.getProperty(
                "spring.jpa.properties.hibernate.search.backend.directory.type", "unknown");
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
                var registry = factory.unwrap(SessionFactoryImplementor.class)
                        .getQueryEngine().getSqmFunctionRegistry();
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
                storage, storageSource, lifetime, schemaAction, List.copyOf(warnings));
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

    private List<DiskInfo> disks(String indexStorage) {
        Map<FileStore, DiskInfo> stores = new LinkedHashMap<>();
        List<DiskInfo> unavailable = new ArrayList<>();
        addDisk("TEMPORARY_FILES", System.getProperty("java.io.tmpdir"), stores, unavailable);
        if ("local-filesystem".equals(indexStorage)) {
            addDisk("SEARCH_INDEX", environment.getProperty(
                    "spring.jpa.properties.hibernate.search.backend.directory.root"), stores, unavailable);
        }
        List<DiskInfo> result = new ArrayList<>(stores.values());
        result.addAll(unavailable);
        return List.copyOf(result);
    }

    private static void addDisk(String purpose, String path, Map<FileStore, DiskInfo> stores,
                                List<DiskInfo> unavailable) {
        try {
            if (path == null || path.isBlank()) {
                unavailable.add(new DiskInfo(List.of(purpose), null, null, "UNAVAILABLE"));
                return;
            }
            FileStore store = Files.getFileStore(Path.of(path));
            DiskInfo previous = stores.get(store);
            List<String> purposes = new ArrayList<>();
            if (previous != null) {
                purposes.addAll(previous.purposes());
            }
            purposes.add(purpose);
            stores.put(store, new DiskInfo(List.copyOf(purposes), store.getTotalSpace(),
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
                               String lifetime, String schemaAction, List<String> warnings) { }
    public record DiskInfo(List<String> purposes, Long totalBytes, Long usableBytes, String status) { }
}
