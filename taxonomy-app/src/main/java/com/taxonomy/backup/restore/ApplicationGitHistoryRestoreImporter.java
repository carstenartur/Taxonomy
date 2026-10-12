package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.backup.runtime.BackupAuthorizationService;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.backup.*;
import com.taxonomy.workspace.storage.StagedJgitSchemaMigration;
import io.github.carstenartur.jgit.storage.hibernate.*;
import org.eclipse.jgit.lib.PersonIdent;
import org.hibernate.SessionFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.security.access.AccessDeniedException;
import javax.sql.DataSource;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** Durable original-history import into an explicitly approved isolated generation. */
public final class ApplicationGitHistoryRestoreImporter implements GitRestoreImporter {
    private static final int MAX_PROOF_BYTES = 16 * 1_024 * 1_024;
    private static final long MAX_TOTAL_PROOF_BYTES = 64L * 1_024 * 1_024;
    private static final BackupScope INSTALLATION = new BackupScope.Installation();
    private final Supplier<PrincipalId> actor;
    private final BackupAuthorizationService authority;
    private final Path privateSpool;
    private final GitHistoryCapture.Limits limits;

    public ApplicationGitHistoryRestoreImporter(Supplier<PrincipalId> actor, BackupAuthorizationService authority,
            Path privateSpool, GitHistoryCapture.Limits limits) {
        this.actor = Objects.requireNonNull(actor); this.authority = Objects.requireNonNull(authority);
        this.privateSpool = Objects.requireNonNull(privateSpool); this.limits = Objects.requireNonNull(limits);
    }

    @Override public Result importGit(VerifiedBackup verified, Map<BackupRepositoryKey, RestoreRepositoryAllocation> approved,
            RestoreTargetGeneration target, BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(verified); Objects.requireNonNull(target); Objects.requireNonNull(checkpoint);
            PrincipalId principal = Objects.requireNonNull(actor.get());
            BackupCheckpoint check = () -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Git restore cancelled");
                checkpoint.check();
                if (!principal.equals(actor.get())) throw new AccessDeniedException("Restore actor changed");
                authority.require(principal, BackupCapability.RESTORE_INSTALLATION, INSTALLATION);
            };
            check.check(); var allocations = Map.copyOf(approved);
            requireAllocations(verified, allocations, target); target.requireUnchanged(check);
            // Validate every native source and the bounded future proof before target Git allocation.
            try (var sources = prepare(verified, allocations, check)) {
                DataSource fenced = new FencedDataSource(target, check);
                try (var sessions = factory(fenced)) {
                    var binding = new Owner(target.generationId().toString(), verified.archiveSha256(),
                            allocationDigest(sources.plans), principal.value().toString(), sources.plans.size());
                    var existing = existingOnly(sessions);
                    var rows = claim(fenced, existing, sources.plans, binding, check);
                    var repositories = new DefaultHibernateRepositoryFactory(sessions);
                    var provenance = new GitReflogProvenanceStore(fenced);
                    var result = new LinkedHashMap<BackupRepositoryKey, RepositoryProof>();
                    var audit = new PersonIdent("Restore operator " + principal.value(), "restore@invalid");
                    for (var plan : sources.plans) {
                        check.check(); var row = rows.get(plan.key()); GitHistoryRestore.Proof imported;
                        var name = new RepositoryName(plan.allocation().physicalStorageName());
                        if (row.complete()) {
                            // Completed repositories must exist: this facade cannot recreate their metadata.
                            try (var repository = existing.open(name, name)) {
                                imported = GitHistoryRestore.verify(plan.archive().prepared(), repository.repository(), check);
                            }
                        } else {
                            // The stable PREPARED operation committed before this provisioning facade is used.
                            try (var repository = repositories.open(name)) {
                                imported = GitHistoryRestore.restore(plan.archive().prepared(), repository.repository(),
                                        row.operation(), audit, check);
                            }
                        }
                        var proof = new RepositoryProof(plan.allocation().targetKey(), name.value(), imported.state(),
                                imported.commitMappings(), imported.repositorySha256());
                        String body = proofBody(proof);
                        if (!plan.proofBody().equals(body)) throw invalid();
                        complete(fenced, provenance, sources.plans, binding, plan, row, imported, body, check);
                        result.put(plan.key(), proof);
                    }
                    target.requireUnchanged(check); check.check(); return new Result(result);
                }
            }
        } catch (IOException | SQLException | RuntimeException failure) { throw sanitized(failure); }
    }

    private static void requireAllocations(VerifiedBackup archive,
            Map<BackupRepositoryKey, RestoreRepositoryAllocation> allocations, RestoreTargetGeneration target) throws IOException {
        if (!archive.manifest().request().profile().includesHistory() || !allocations.equals(target.allocations())) throw invalid();
        var keys = new HashSet<BackupRepositoryKey>(); var physical = new HashSet<String>();
        for (var repository : archive.manifest().repositories()) {
            var allocation = allocations.get(repository.id());
            if (!keys.add(repository.id()) || allocation == null || repository.representation() == GitRepresentation.NONE
                    || repository.sourceRepositoryId() != null || repository.sourceCommit() != null
                    || (repository.id().workspaceId() == null) != (allocation.targetKey().workspaceId() == null)
                    || !physical.add(allocation.physicalStorageName().toLowerCase(Locale.ROOT))) throw invalid();
            requireKey(repository.id()); requireKey(allocation.targetKey());
        }
        if (!keys.equals(allocations.keySet())) throw invalid();
    }
    private static void requireKey(BackupRepositoryKey key) throws IOException {
        if (key.repositoryId().length() > 512 || key.workspaceId() != null && key.workspaceId().length() > 512) throw invalid();
    }
    private PreparedArchives prepare(VerifiedBackup verified, Map<BackupRepositoryKey, RestoreRepositoryAllocation> allocations,
            BackupCheckpoint check) throws IOException {
        var prepared = new PreparedArchives();
        try {
            long remaining = limits.maxTotalBytes(), proofBytes = 0;
            var repositories = verified.manifest().repositories().stream().sorted(Comparator
                    .comparing((BackupManifest.Repository repository) -> repository.id().repositoryId())
                    .thenComparing(repository -> repository.id().workspaceId(), Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
            for (var repository : repositories) {
                check.check();
                var bounded = new GitHistoryCapture.Limits(limits.maxRefs(), limits.maxObjects(), limits.maxObjectBytes(),
                        remaining, limits.maxReflogEntries());
                var archive = GitHistoryArchive.open(verified, repository, privateSpool, bounded, check);
                prepared.archives.add(archive);
                remaining = Math.subtractExact(remaining, archive.prepared().expandedBytes());
                if (remaining < 0) throw invalid();
                var allocation = allocations.get(repository.id());
                var refs = new TreeMap<>(archive.source().state().refs()); refs.putAll(archive.source().backupRefs());
                var commits = new TreeMap<String, String>(); archive.source().state().requiredCommits().forEach(id -> commits.put(id, id));
                var expected = new RepositoryProof(allocation.targetKey(), allocation.physicalStorageName(),
                        new SnapshotContext.RepositoryState(refs, archive.source().state().symbolicHead(), Map.of(), commits.keySet()),
                        commits, archive.prepared().inputSha256());
                String body = proofBody(expected);
                proofBytes = Math.addExact(proofBytes, body.getBytes(StandardCharsets.UTF_8).length);
                if (proofBytes > MAX_TOTAL_PROOF_BYTES) throw invalid();
                prepared.plans.add(new Plan(repository.id(), digest(repository.id()), repository.archiveId(), allocation, archive, body));
            }
            check.check(); return prepared;
        } catch (IOException | RuntimeException | Error failure) {
            try { prepared.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure;
        }
    }
    private static SessionFactory factory(DataSource fenced) {
        var configuration = new org.hibernate.cfg.Configuration();
        configuration.getProperties().put("hibernate.connection.datasource", fenced);
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.search.enabled", "false");
        configuration.setProperty("hibernate.jpa_callbacks.enabled", "false");
        StagedJgitSchemaMigration.configureModel(configuration); return configuration.buildSessionFactory();
    }
    private static SecuredHibernateRepositoryFactory<RepositoryName> existingOnly(SessionFactory sessions) {
        return new SecuredHibernateRepositoryFactory<>(sessions, (name, request) -> {
            if (!name.equals(request.repositoryName())
                    || request.operation() != RepositoryAccessOperation.DISCOVER && request.operation() != RepositoryAccessOperation.READ)
                throw new RepositoryAccessDeniedException(request, "RESTORE_READ_ONLY", "original-history-restore", 1);
        });
    }

    private static Map<BackupRepositoryKey, JournalRow> claim(DataSource database,
            SecuredHibernateRepositoryFactory<RepositoryName> repositories, List<Plan> plans, Owner expected,
            BackupCheckpoint check) throws SQLException, IOException {
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try (var rollback = new Rollback(connection)) {
                var owner = owner(connection, check);
                if (owner != null) {
                    if (!owner.equals(expected)) throw invalid(); return rows(connection, plans, check);
                }
                try (var statement = statement(connection, "select source_key from restore_git_repository", 1);
                     var row = statement.executeQuery()) { if (row.next()) throw invalid(); }
                for (var plan : plans) {
                    check.check(); var name = new RepositoryName(plan.allocation().physicalStorageName());
                    try (var ignored = repositories.open(name, name)) { throw invalid(); }
                    catch (RepositoryDoesNotExistException absent) {
                        if (!name.equals(absent.repositoryName())) throw invalid();
                    }
                }
                update(connection, "insert into restore_git_owner(owner_id,generation_id,archive_sha256,allocation_sha256,actor_id,repository_count) values(1,?,?,?,?,?)",
                        expected.generation(), expected.archive(), expected.allocations(), expected.actor(), expected.count());
                for (var plan : plans) {
                    check.check();
                    update(connection, "insert into restore_git_repository(source_key,source_repository_id,source_workspace_id,archive_id,target_repository_id,target_workspace_id,storage_repository_name,operation_id,input_sha256,owner_state,proof_sha256,proof_body) values(?,?,?,?,?,?,?,?,?,'PREPARED',null,null)",
                            plan.sourceHash(), plan.key().repositoryId(), plan.key().workspaceId(), plan.archiveId(),
                            plan.allocation().targetKey().repositoryId(), plan.allocation().targetKey().workspaceId(),
                            plan.allocation().physicalStorageName(), UUID.randomUUID().toString(), plan.archive().prepared().inputSha256());
                }
                if (!expected.equals(owner(connection, check))) throw invalid();
                var result = rows(connection, plans, check);
                check.check(); connection.commit(); rollback.committed = true; check.check(); return result;
            }
        }
    }
    private static void complete(DataSource database, GitReflogProvenanceStore provenance, List<Plan> plans,
            Owner expected, Plan plan, JournalRow prepared, GitHistoryRestore.Proof proof, String body,
            BackupCheckpoint check) throws SQLException, IOException {
        try (var connection = database.getConnection()) {
            connection.setReadOnly(prepared.complete()); connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try (var rollback = new Rollback(connection)) {
                if (!expected.equals(owner(connection, check))) throw invalid();
                var actual = rows(connection, plans, check).get(plan.key());
                if (!actual.operation().equals(prepared.operation()) || prepared.complete() && !actual.complete()) throw invalid();
                if (actual.complete()) {
                    provenance.requireStored(connection, plan.allocation().targetKey(), plan.allocation().physicalStorageName(),
                            proof.originalEvents(), check);
                    check.check(); return;
                }
                provenance.retain(connection, plan.allocation().targetKey(), plan.allocation().physicalStorageName(), proof.originalEvents(), check);
                try (var statement = statement(connection, "update restore_git_repository set owner_state='COMPLETE',proof_sha256=?,proof_body=? where source_key=? and operation_id=? and owner_state='PREPARED'", 0)) {
                    statement.setString(1, sha256(body.getBytes(StandardCharsets.UTF_8)));
                    statement.setCharacterStream(2, new StringReader(body)); statement.setString(3, plan.sourceHash());
                    statement.setString(4, prepared.operation().toString()); check.check();
                    if (statement.executeUpdate() != 1) throw invalid();
                }
                if (!rows(connection, plans, check).get(plan.key()).complete()) throw invalid();
                provenance.requireStored(connection, plan.allocation().targetKey(), plan.allocation().physicalStorageName(), proof.originalEvents(), check);
                check.check(); connection.commit(); rollback.committed = true; check.check();
            }
        }
    }
    private static Owner owner(Connection connection, BackupCheckpoint check) throws SQLException, IOException {
        try (var statement = statement(connection, "select owner_id,generation_id,archive_sha256,allocation_sha256,actor_id,repository_count from restore_git_owner", 2);
             var rows = statement.executeQuery()) {
            check.check(); if (!rows.next()) return null;
            if (rows.getInt(1) != 1) throw invalid();
            var result = new Owner(rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getInt(6));
            if (rows.wasNull() || rows.next()) throw invalid(); check.check(); return result;
        }
    }
    private static Map<BackupRepositoryKey, JournalRow> rows(Connection connection, List<Plan> plans,
            BackupCheckpoint check) throws SQLException, IOException {
        var expected = new HashMap<String, Plan>(); plans.forEach(plan -> expected.put(plan.sourceHash(), plan));
        var result = new LinkedHashMap<BackupRepositoryKey, JournalRow>(); var operations = new HashSet<UUID>();
        try (var statement = statement(connection, "select source_key,source_repository_id,source_workspace_id,archive_id,target_repository_id,target_workspace_id,storage_repository_name,operation_id,input_sha256,owner_state,proof_sha256,proof_body from restore_git_repository order by source_key", plans.size() + 1);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                check.check(); var plan = expected.get(rows.getString(1));
                if (plan == null || result.containsKey(plan.key()) || !plan.key().repositoryId().equals(rows.getString(2))
                        || !Objects.equals(plan.key().workspaceId(), rows.getString(3)) || !plan.archiveId().equals(rows.getString(4))
                        || !plan.allocation().targetKey().repositoryId().equals(rows.getString(5))
                        || !Objects.equals(plan.allocation().targetKey().workspaceId(), rows.getString(6))
                        || !plan.allocation().physicalStorageName().equals(rows.getString(7))
                        || !plan.archive().prepared().inputSha256().equals(rows.getString(9))) throw invalid();
                String rawOperation = rows.getString(8); var operation = UUID.fromString(rawOperation);
                if (!operation.toString().equals(rawOperation) || !operations.add(operation)) throw invalid();
                String state = rows.getString(10), digest = rows.getString(11);
                try (var content = rows.getCharacterStream(12)) {
                    if ("PREPARED".equals(state)) { if (digest != null || content != null) throw invalid(); }
                    else if ("COMPLETE".equals(state)) {
                        String body = readBody(content, plan.proofBody().length(), check);
                        if (!plan.proofBody().equals(body) || !sha256(body.getBytes(StandardCharsets.UTF_8)).equals(digest)) throw invalid();
                    } else throw invalid();
                }
                result.put(plan.key(), new JournalRow(operation, "COMPLETE".equals(state)));
            }
        }
        if (result.size() != plans.size()) throw invalid(); check.check(); return Map.copyOf(result);
    }
    private static PreparedStatement statement(Connection connection, String sql, int maximum) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(60); if (maximum > 0) statement.setMaxRows(maximum); return statement; }
        catch (SQLException failure) { try { statement.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
    }
    private static void update(Connection connection, String sql, Object... parameters) throws SQLException, IOException {
        try (var statement = statement(connection, sql, 0)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            if (statement.executeUpdate() != 1) throw invalid();
        }
    }
    private static String readBody(Reader source, int expectedLength, BackupCheckpoint check) throws IOException {
        if (source == null || expectedLength > MAX_PROOF_BYTES) throw invalid();
        var result = new StringBuilder(); var buffer = new char[4_096];
        for (;;) {
            check.check(); int count = source.read(buffer); if (count < 0) break;
            if (count == 0) { int one = source.read(); if (one < 0) break; buffer[0] = (char) one; count = 1; }
            if (count > expectedLength - result.length()) throw invalid(); result.append(buffer, 0, count);
        }
        check.check(); return result.toString();
    }
    private static String proofBody(RepositoryProof proof) throws IOException {
        if (!proof.restoredState().workingStates().isEmpty()) throw invalid();
        var canonical = new CanonicalProof(1, proof.targetKey(), proof.physicalStorageName(),
                new TreeMap<>(proof.restoredState().refs()), proof.restoredState().symbolicHead(),
                new TreeSet<>(proof.restoredState().requiredCommits()), new TreeMap<>(proof.commitMappings()), proof.repositoryDigest());
        var bytes = new ByteArrayOutputStream();
        try (var bounded = new FilterOutputStream(bytes) {
            private int count;
            @Override public void write(int value) throws IOException { require(1); out.write(value); }
            @Override public void write(byte[] buffer, int offset, int length) throws IOException { require(length); out.write(buffer, offset, length); }
            private void require(int length) throws IOException { if (length > MAX_PROOF_BYTES - count) throw invalid(); count += length; }
        }) { PortableRows.json().writeValue(bounded, canonical); }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private static String allocationDigest(List<Plan> plans) throws IOException {
        return digest(plans.stream().map(plan -> new AllocationBinding(plan.key(), plan.archiveId(), plan.allocation(),
                plan.archive().prepared().inputSha256())).toList());
    }
    private static String digest(Object value) throws IOException {
        MessageDigest digest = sha256();
        try (var stream = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) { PortableRows.json().writeValue(stream, value); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String sha256(byte[] value) { return HexFormat.of().formatHex(sha256().digest(value)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Guards standard JDBC boundaries without reflecting into library implementation objects. */
    static final class FencedDataSource extends AbstractDataSource {
        private final RestoreTargetGeneration target;
        private final BackupCheckpoint check;
        FencedDataSource(RestoreTargetGeneration target, BackupCheckpoint check) { this.target = target; this.check = check; }
        @Override public Connection getConnection() throws SQLException {
            Connection actual = target.dataSource().getConnection();
            try {
                require(actual);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> connection(actual, (Connection) proxy, method, args));
            } catch (SQLException | RuntimeException failure) {
                try { actual.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); } throw failure;
            }
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Restore connections require the verified data source");
        }
        private Object connection(Connection actual, Connection proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("unwrap")) return unwrap(proxy, (Class<?>) args[0]);
            if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
            boolean prepare = name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement");
            if (prepare || name.equals("commit") || name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) require(actual);
            Object result = invoke(actual, method, args);
            if (prepare) {
                var statement = (Statement) result;
                try {
                    require(actual); statement.setQueryTimeout(60);
                    return statement(actual, proxy, statement);
                } catch (SQLException | RuntimeException failure) {
                    try { statement.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); } throw failure;
                }
            }
            if (result instanceof DatabaseMetaData metadata) {
                return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                        (wrapped, operation, values) -> {
                            if (operation.getName().equals("getConnection")) return proxy;
                            if (operation.getName().equals("unwrap")) return unwrap(wrapped, (Class<?>) values[0]);
                            if (operation.getName().equals("isWrapperFor")) return ((Class<?>) values[0]).isInstance(wrapped);
                            Object value = invoke(metadata, operation, values);
                            return value instanceof ResultSet rows ? resultSet(actual, proxy, null, rows) : value;
                        });
            }
            return result;
        }
        private Statement statement(Connection actual, Connection connection, Statement statement) {
            Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (wrapped, operation, values) -> {
                String called = operation.getName();
                if (called.equals("getConnection")) return connection;
                if (called.equals("unwrap")) return unwrap(wrapped, (Class<?>) values[0]);
                if (called.equals("isWrapperFor")) return ((Class<?>) values[0]).isInstance(wrapped);
                if (called.startsWith("execute") || called.equals("addBatch")) require(actual);
                Object value = invoke(statement, operation, values);
                return value instanceof ResultSet rows ? resultSet(actual, connection, (Statement) wrapped, rows) : value;
            });
        }
        private ResultSet resultSet(Connection actual, Connection connection, Statement owner, ResultSet rows) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (wrapped, operation, values) -> {
                        String called = operation.getName();
                        if (called.equals("unwrap")) return unwrap(wrapped, (Class<?>) values[0]);
                        if (called.equals("isWrapperFor")) return ((Class<?>) values[0]).isInstance(wrapped);
                        if (called.equals("getStatement")) {
                            if (owner != null) return owner;
                            Statement statement = rows.getStatement();
                            return statement == null ? null : statement(actual, connection, statement);
                        }
                        if (called.startsWith("update") || called.equals("insertRow") || called.equals("deleteRow")
                                || called.equals("refreshRow") || called.equals("next") || called.equals("previous")
                                || called.equals("first") || called.equals("last") || called.equals("absolute") || called.equals("relative"))
                            require(actual);
                        return invoke(rows, operation, values);
                    });
        }
        private void require(Connection actual) throws SQLException {
            try { target.requireConnection(actual, check); }
            catch (InterruptedIOException cancelled) { Thread.currentThread().interrupt(); throw new SQLException("Git restore cancelled", cancelled); }
            catch (IOException failure) { throw new SQLException("Restore database binding is unavailable or changed", failure); }
        }
        private static Object unwrap(Object wrapper, Class<?> type) throws SQLException {
            if (type.isInstance(wrapper)) return wrapper; throw new SQLException("Raw restore connections are unavailable");
        }
        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    private record Owner(String generation, String archive, String allocations, String actor, int count) { }
    private record Plan(BackupRepositoryKey key, String sourceHash, String archiveId, RestoreRepositoryAllocation allocation,
                        GitHistoryArchive archive, String proofBody) { }
    private record JournalRow(UUID operation, boolean complete) { }
    private record AllocationBinding(BackupRepositoryKey source, String archiveId, RestoreRepositoryAllocation allocation, String inputSha256) { }
    private record CanonicalProof(int version, BackupRepositoryKey target, String storage, SortedMap<String, String> refs,
                                  String symbolicHead, SortedSet<String> requiredCommits, SortedMap<String, String> commitMappings,
                                  String repositorySha256) { }
    private static final class PreparedArchives implements AutoCloseable {
        private final List<GitHistoryArchive> archives = new ArrayList<>();
        private final List<Plan> plans = new ArrayList<>();
        @Override public void close() throws IOException {
            IOException failure = null;
            for (var archive : archives.reversed()) try { archive.close(); }
            catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            if (failure != null) throw failure;
        }
    }
    private static final class Rollback implements AutoCloseable {
        private final Connection connection; private boolean committed;
        Rollback(Connection connection) { this.connection = connection; }
        @Override public void close() throws SQLException { if (!committed) connection.rollback(); }
    }
    private static IOException invalid() { return new IOException("Original Git restore plan or completed evidence is invalid"); }
    private static IOException sanitized(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        boolean cancelled = Thread.currentThread().isInterrupted();
        for (Throwable cause = failure; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException) throw new AccessDeniedException("Original Git restore access denied");
            if (cause instanceof InterruptedIOException) cancelled = true;
        }
        IOException result;
        if (cancelled) { Thread.currentThread().interrupt(); result = new InterruptedIOException("Original Git restore cancelled"); }
        else result = invalid();
        for (var cleanup : failure.getSuppressed()) result.addSuppressed(cleanup); return result;
    }
}
