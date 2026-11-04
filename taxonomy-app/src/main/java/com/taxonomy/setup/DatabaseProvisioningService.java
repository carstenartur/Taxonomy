package com.taxonomy.setup;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared, bounded orchestration for explicitly reviewed database resource preparation. */
@Service
public class DatabaseProvisioningService {
    private static final Semaphore OPERATIONS = new Semaphore(2);
    private final Map<String, Provider> providers;

    @Autowired
    public DatabaseProvisioningService(List<Provider> providers) {
        var selected = new HashMap<String, Provider>();
        for (Provider provider : providers) {
            if (selected.put(provider.type(), provider) != null) throw new IllegalArgumentException("Duplicate database provider");
        }
        this.providers = Map.copyOf(selected);
    }
    DatabaseProvisioningService(Provider... providers) { this(List.of(providers)); }

    interface Provider {
        String type();
        void validate(ProvisionSettings input);
        ProvisionResult execute(ProvisionSettings input, boolean create, Progress progress) throws Exception;
    }
    @FunctionalInterface
    interface Connector { Connection connect(String url, Properties properties) throws SQLException; }
    public enum Outcome { READY, COMPLETE, CONFLICT, FAILED, PARTIAL, UNKNOWN, BUSY }
    public enum ResourceState { CREATE, REUSE, CREATED, REUSED, NOT_CREATED, UNKNOWN }
    @Schema(requiredProperties = {"target", "maintenanceDatabase", "adminUsername", "adminPassword"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ProvisionSettings(DatabaseSetupService.ConnectionSettings target, String maintenanceDatabase,
                                    String adminUsername,
                                    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String adminPassword,
                                    boolean confirmed,
                                    @Schema(allowableValues = {"CREATE", "REUSE"}) ResourceState expectedRole,
                                    @Schema(allowableValues = {"CREATE", "REUSE"}) ResourceState expectedDatabase,
                                    @Schema(description = "Oracle only: existing permanent tablespace", maxLength = 30) String tablespace,
                                    @Schema(description = "Oracle only: finite quota in MiB", minimum = "1", maximum = "1048576") Integer quotaMiB) {
        public ProvisionSettings(DatabaseSetupService.ConnectionSettings target, String maintenanceDatabase,
                                 String adminUsername, String adminPassword, boolean confirmed,
                                 ResourceState expectedRole, ResourceState expectedDatabase) {
            this(target, maintenanceDatabase, adminUsername, adminPassword, confirmed, expectedRole, expectedDatabase, null, null);
        }
        @Override public String toString() { return "Database preparation settings [redacted]"; }
    }
    public record ProvisionResult(Outcome outcome, ResourceState role, ResourceState database, SetupChecks.Finding finding) { }


    public ProvisionResult inspect(ProvisionSettings input) {
        Provider provider = validate(input);
        return bounded(provider, input, false);
    }
    public ProvisionResult provision(ProvisionSettings input) {
        Provider provider = validate(input);
        if (!input.confirmed()) return result(Outcome.FAILED, ResourceState.NOT_CREATED, ResourceState.NOT_CREATED,
                "confirmation", "Review the inspection and explicitly confirm the displayed resource creation or reuse.");
        if (!expectedAction(input.expectedRole()) || !expectedAction(input.expectedDatabase())) throw invalid();
        return bounded(provider, input, true);
    }
    private Provider validate(ProvisionSettings input) {
        if (input == null || input.target() == null) throw invalid();
        DatabaseSetupService.validate(input.target());
        if ((input.expectedRole() != null && !expectedAction(input.expectedRole()))
                || (input.expectedDatabase() != null && !expectedAction(input.expectedDatabase()))) throw invalid();
        if (input.target().password() == null || input.target().password().isBlank()
                || input.adminUsername() == null || !input.adminUsername().matches("[A-Za-z0-9_@.$\\\\-]{1,128}")
                || input.adminUsername().equalsIgnoreCase(input.target().username())
                || input.adminPassword() == null || input.adminPassword().isBlank()
                || input.adminPassword().length() > 4096) throw invalid();
        Provider provider = providers.get(input.target().type());
        if (provider == null) throw invalid();
        provider.validate(input);
        return provider;
    }
    private ProvisionResult bounded(Provider provider, ProvisionSettings input, boolean create) {
        if (!OPERATIONS.tryAcquire()) return result(Outcome.BUSY, ResourceState.UNKNOWN, ResourceState.UNKNOWN,
                "busy", "Other database preparation operations are running. Wait, then inspect again.");
        Progress progress = new Progress();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) OPERATIONS.release(); };
        FutureTask<ProvisionResult> task = new FutureTask<>(() -> {
            try { return provider.execute(input, create, progress); }
            catch (Exception problem) { return failure(progress, problem); }
            // JDBC has exited here. Release before FutureTask publishes a result,
            // so a completed operation cannot leave a subsequent request BUSY.
            finally { release.run(); }
        });
        Thread.ofVirtual().name("taxonomy-database-preparation").start(() -> {
            // Also handles cancellation before Callable starts. A running JDBC
            // operation still retains its permit until its actual completion.
            try { task.run(); } finally { release.run(); }
        });
        try {
            return task.get(create ? 60 : 20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            progress.cancelled = true;
            return uncertain(progress, create);
        } catch (TimeoutException timeout) {
            progress.cancelled = true;
            return uncertain(progress, create);
        } catch (ExecutionException failure) {
            return failure(progress, failure.getCause());
        } finally {
            // Cancellation does not release capacity while JDBC is still running.
            task.cancel(true);
        }
    }


    static Connection connect(Connector connector, ProvisionSettings input, String database, String user, String password) throws SQLException {
        var target = input.target();
        var settings = new DatabaseSetupService.ConnectionSettings(target.type(), target.host(), target.port(), database,
                user, password, target.tls(), target.caCertificate());
        String url = DatabaseSetupService.jdbcUrl(settings, !Boolean.FALSE.equals(target.tls()));
        Properties properties = SetupProbe.connectionProperties(url, user, password);
        switch (target.type()) {
            case "postgres" -> { properties.setProperty("socketTimeout", "20"); properties.setProperty("ApplicationName", "Taxonomy database preparation"); }
            case "mssql" -> { properties.setProperty("socketTimeout", "20000"); properties.setProperty("applicationName", "Taxonomy database preparation"); }
            case "oracle" -> properties.setProperty("oracle.jdbc.ReadTimeout", "20000");
            default -> throw invalid();
        }
        return connector.connect(url, properties);
    }
    static boolean expectedAction(ResourceState state) {
        return state == ResourceState.CREATE || state == ResourceState.REUSE;
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid database preparation settings."); }
    static Statement statement(Connection connection) throws SQLException {
        Statement statement = connection.createStatement(); statement.setQueryTimeout(15); return statement;
    }
    static void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement statement = statement(connection)) { statement.execute(sql); }
    }
    static boolean scalarBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = statement(connection); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("", "08000");
            return rows.getBoolean(1);
        }
    }
    static ProvisionResult planChanged(Progress progress) {
        return result(Outcome.CONFLICT, progress.role, progress.database, "plan-changed",
                "The resource creation or reuse plan changed after inspection. Inspect again and review the new plan before confirming.");
    }
    static ProvisionResult denied(Progress progress) {
        return result(Outcome.FAILED, progress.role, progress.database, "permission",
                "Administrator permissions are insufficient to establish resource creation and application privileges before writing. Existing resources will not be changed.");
    }
    static ProvisionResult failure(Progress progress, Throwable problem) {
        String state = problem instanceof SQLException sql ? sql.getSQLState() : null;
        if (progress.writing && uncertainWrite(problem))
            return uncertain(progress, true);
        if (progress.role == ResourceState.UNKNOWN) progress.role = ResourceState.NOT_CREATED;
        if (progress.database == ResourceState.UNKNOWN) progress.database = ResourceState.NOT_CREATED;
        boolean changed = progress.role == ResourceState.CREATED || progress.database == ResourceState.CREATED;
        if (problem instanceof SQLException sql && Set.of(15116, 15118, 15128, 28003).contains(sql.getErrorCode()))
            return result(changed ? Outcome.PARTIAL : Outcome.FAILED, progress.role, progress.database,
                    "password-policy", "The database server rejected the application password under its password policy. Choose a compliant password, then inspect again. No password or server exception is returned.");
        if ("42710".equals(state) || "42P04".equals(state)
                || problem instanceof SQLException sql && Set.of(15025, 1801, 1920, 1921).contains(sql.getErrorCode())) return result(changed ? Outcome.PARTIAL : Outcome.CONFLICT,
                progress.role, progress.database, "conflict", "A resource was created concurrently. No existing resource was changed. Inspect again before continuing.");
        if (changed) return new ProvisionResult(Outcome.PARTIAL, progress.role, progress.database, SetupProbe.databaseFailure(problem));
        return new ProvisionResult(Outcome.FAILED, progress.role, progress.database, SetupProbe.databaseFailure(problem));
    }
    /** Drivers can report unknown completion without using connection-error class 08.
     * Inspect both JDBC chains and Java causes: wrapper states must not turn uncertainty into absence.
     */
    static boolean uncertainWrite(Throwable problem) {
        if (!(problem instanceof SQLException)) return true;
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(problem);
        for (int count = 0; count < 32 && !pending.isEmpty(); count++) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLTimeoutException || current instanceof SQLRecoverableException
                    || current instanceof TimeoutException
                    || current instanceof InterruptedException || current instanceof java.io.IOException) return true;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state == null || state.startsWith("08") || Set.of("40003", "57P01", "57P02", "HYT00", "HYT01", "HY008").contains(state)
                        || Set.of(1013, 3113, 3114, 17002, 17410).contains(sql.getErrorCode())) return true;
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        // An exceptionally deep chain cannot establish definite non-creation.
        return !pending.isEmpty();
    }
    static ProvisionResult uncertain(Progress progress, boolean create) {
        if (!create) return new ProvisionResult(Outcome.FAILED, progress.role, progress.database,
                SetupProbe.databaseFailure(Thread.currentThread().isInterrupted()
                        ? new InterruptedException() : new TimeoutException()));
        return result(Outcome.UNKNOWN, progress.role, progress.database, "unknown",
                "Preparation was interrupted or its outcome could not be confirmed. Resources may exist. Inspect before another creation attempt; nothing is automatically removed or retried.");
    }
    static ProvisionResult result(Outcome outcome, ResourceState role, ResourceState database, String key, String message) {
        SetupChecks.Status status = switch (outcome) {
            case COMPLETE -> SetupChecks.Status.OK;
            case READY -> SetupChecks.Status.NOT_CHECKED;
            case BUSY -> SetupChecks.Status.WARNING;
            default -> SetupChecks.Status.ERROR;
        };
        return new ProvisionResult(outcome, role, database, new SetupChecks.Finding(status, "database.provision." + key, message));
    }
    static final class Progress {
        volatile ResourceState role = ResourceState.UNKNOWN, database = ResourceState.UNKNOWN;
        volatile boolean writing, cancelled;
    }
}
