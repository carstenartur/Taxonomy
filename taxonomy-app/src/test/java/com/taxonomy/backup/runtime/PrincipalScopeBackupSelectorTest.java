package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.security.backup.PrincipalScopeBackupSelector;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrincipalScopeBackupSelectorTest {
    @Test void onlyExplicitPersistedScopesResolveIncludingDisabledHistoricalOwners() throws Exception {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:principal-scope-" + UUID.randomUUID()); database.setUser("SA");
        var jdbc = new JdbcTemplate(database); jdbc.execute("create table app_principal(principal_id varchar(36),scope_key varchar(255),enabled integer)");
        var owner = PrincipalId.create(); var retired = PrincipalId.create(); var foreign = PrincipalId.create();
        jdbc.update("insert into app_principal values (?,?,1)", owner.value().toString(), "owner");
        jdbc.update("insert into app_principal values (?,?,0)", retired.value().toString(), "retired");
        jdbc.update("insert into app_principal values (?,?,1)", foreign.value().toString(), "foreign");
        var selector = new PrincipalScopeBackupSelector(database, List.of((snapshot, checkpoint) -> Set.of("owner", "retired")));
        var result = selector.select(snapshot(), () -> { }); assertThat(result).containsExactlyInAnyOrder(owner, retired);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(jdbc.queryForList("select principal_id,scope_key,enabled from app_principal")).hasSize(3);
    }

    @Test void aSameNamedUnregisteredLocalAccountIsNeverAnOwnershipMapping() throws Exception {
        var f = new Fixture(false); f.jdbc.execute("create table app_user(username varchar(255),principal_id varchar(36))");
        f.jdbc.update("insert into app_user values (?,?)", "PRIVATE-NAME", PrincipalId.create().value().toString());
        assertFailure(f.selector(Set.of("PRIVATE-NAME")));
        assertThat(f.jdbc.queryForObject("select count(*) from app_principal", Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);
    }

    @Test void sourceScopesRemainExactEvenOnACaseInsensitiveDatabase() throws Exception {
        var f = new Fixture(true); f.add("Owner", PrincipalId.create().value().toString());
        assertFailure(f.selector(Set.of("owner")));
        assertThat(f.selector(Set.of("Owner")).select(snapshot(), () -> { })).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"INVALID-PRIVATE-ID", "1-1-1-1-1", "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB"})
    void sourceIdsMustAlreadyBeCanonicalInsteadOfSilentlyCollapsingOwners(String id) throws Exception {
        var f = new Fixture(false); f.add("owner", id); assertFailure(f.selector(Set.of("owner")));
    }

    @ParameterizedTest @ValueSource(strings = {"scope", "principal"})
    void ambiguousPersistedMappingsCannotPickAnArbitraryOwner(String duplicated) throws Exception {
        var f = new Fixture(false); String id = PrincipalId.create().value().toString(); f.add("first", id);
        f.add(duplicated.equals("scope") ? "first" : "second", duplicated.equals("principal") ? id : PrincipalId.create().value().toString());
        assertFailure(f.selector(duplicated.equals("scope") ? Set.of("first") : Set.of("first", "second")));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "PRIVATE\nSCOPE"})
    void invalidScopesDoNotReachIdentityQueries(String scope) throws Exception {
        var database = mock(DataSource.class); assertFailure(new PrincipalScopeBackupSelector(database, List.of((snapshot, checkpoint) -> Set.of(scope))));
        verifyNoInteractions(database);
    }

    @Test void nullAndOversizedScopeInputsAreRejectedBeforeLookup() throws Exception {
        var database = mock(DataSource.class);
        for (var scopes : List.of(new HashSet<>(Arrays.asList((String) null)), Set.of("x".repeat(256)))) {
            assertFailure(new PrincipalScopeBackupSelector(database, List.of((snapshot, checkpoint) -> scopes)));
        }
        verifyNoInteractions(database);
    }

    @Test void duplicateReferencesShareOneMappingButIndependentSelectorsShareOneBudget() throws Exception {
        var f = new Fixture(false); var a = PrincipalId.create(); var b = PrincipalId.create(); f.add("a", a.value().toString()); f.add("b", b.value().toString());
        var sources = new ArrayList<BackupPrincipalScopeSelector>(); sources.add((snapshot, checkpoint) -> Set.of("a")); sources.add((snapshot, checkpoint) -> Set.of("a", "b"));
        var selector = new PrincipalScopeBackupSelector(f.database, sources, 2); sources.clear();
        assertThat(selector.select(snapshot(), () -> { })).containsExactlyInAnyOrder(a, b);
        assertFailure(new PrincipalScopeBackupSelector(f.database, List.of((snapshot, checkpoint) -> Set.of("a"), (snapshot, checkpoint) -> Set.of("b")), 1));
        assertThatThrownBy(() -> new PrincipalScopeBackupSelector(f.database, List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrincipalScopeBackupSelector(f.database, List.of(), 100_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrincipalScopeBackupSelector(f.database, Collections.nCopies(10_001, (snapshot, checkpoint) -> Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void largerSelectionsCrossSqlBatchBoundariesWithoutLosingIdentity() throws Exception {
        var f = new Fixture(false); var scopes = new HashSet<String>(); var expected = new HashSet<PrincipalId>();
        for (int i = 0; i < 401; i++) { var id = PrincipalId.create(); expected.add(id); scopes.add("scope-" + i); f.add("scope-" + i, id.value().toString()); }
        assertThat(f.selector(scopes).select(snapshot(), () -> { })).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test void anEmptyClosureStillChecksTheFenceWithoutOpeningIdentityTables() throws Exception {
        var database = mock(DataSource.class); var checks = new AtomicInteger();
        assertThat(new PrincipalScopeBackupSelector(database, List.of()).select(snapshot(), checks::incrementAndGet)).isEmpty();
        assertThat(checks.get()).isPositive(); verifyNoInteractions(database);
    }

    @Test void selectorCleanupCannotHideCancellationOrLeakPrivateDiagnostics() throws Exception {
        var database = mock(DataSource.class); var active = new AtomicBoolean();
        var selector = new PrincipalScopeBackupSelector(database, List.of((snapshot, checkpoint) -> {
            active.set(true);
            try { checkpoint.check(); return Set.of("owner"); }
            finally { throw new IOException("PRIVATE cleanup"); }
        }));
        try {
            assertThatThrownBy(() -> selector.select(snapshot(), () -> { if (active.get()) throw new InterruptedIOException("PRIVATE cancel"); }))
                    .isInstanceOf(InterruptedIOException.class).hasMessageNotContaining("PRIVATE").hasNoCause().hasNoSuppressedExceptions();
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); verifyNoInteractions(database);
        } finally { Thread.interrupted(); }
    }

    @Test void anExistingInterruptAndSqlFailuresRemainSafeAtTheSelectionBoundary() throws Exception {
        var database = mock(DataSource.class); var selector = new PrincipalScopeBackupSelector(database, List.of((snapshot, checkpoint) -> Set.of("owner")));
        try {
            Thread.currentThread().interrupt(); assertThatThrownBy(() -> selector.select(snapshot(), () -> { })).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); verifyNoInteractions(database);
        } finally { Thread.interrupted(); }
        when(database.getConnection()).thenThrow(new SQLException("PRIVATE SQL")); assertFailure(selector);
    }

    private static void assertFailure(PrincipalScopeBackupSelector selector) {
        assertThatThrownBy(() -> selector.select(snapshot(), () -> { })).isInstanceOf(IOException.class)
                .hasMessageNotContaining("PRIVATE").hasNoCause().hasNoSuppressedExceptions();
    }

    private static final class Fixture {
        final JDBCDataSource database = new JDBCDataSource(); final JdbcTemplate jdbc;
        Fixture(boolean insensitive) {
            database.setUrl("jdbc:hsqldb:mem:principal-scope-" + UUID.randomUUID()); database.setUser("SA"); jdbc = new JdbcTemplate(database);
            jdbc.execute("create table app_principal(principal_id varchar(36),scope_key " + (insensitive ? "varchar_ignorecase(255)" : "varchar(255)") + ",enabled integer)");
        }
        void add(String scope, String id) { jdbc.update("insert into app_principal values (?,?,0)", id, scope); }
        PrincipalScopeBackupSelector selector(Set<String> scopes) { return new PrincipalScopeBackupSelector(database, List.of((snapshot, checkpoint) -> scopes)); }
    }
    private static SnapshotContext snapshot() {
        var request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo", "private"), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var auth = new AuthorizedBackupRequest(request, PrincipalId.create(), "checked", Instant.EPOCH, Set.of(BackupCapability.EXPORT_CURRENT));
        return new SnapshotContext(BackupId.create(), auth, Instant.EPOCH, Instant.EPOCH, 1,
                Map.of(new BackupRepositoryKey("repo", "private"), new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of())), Map.of());
    }
}
