package com.taxonomy.security.webdav;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebDavApplicationCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    private final Logger logger =
            (Logger) LoggerFactory.getLogger(WebDavApplicationCredentialService.class);
    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    private Level previousLogLevel;

    @BeforeEach
    void captureAudit() {
        previousLogLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        audit.start();
        logger.addAppender(audit);
    }

    @AfterEach
    void releaseAudit() {
        logger.detachAppender(audit);
        audit.stop();
        logger.setLevel(previousLogLevel);
    }

    @Test
    void createsAOneTimeSecretStoresOnlyItsHashAndAuthenticatesIt() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        AtomicReference<WebDavApplicationCredential> stored = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            WebDavApplicationCredential value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(repository.findByCredentialIdAndUsername(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        WebDavApplicationCredentialService service = service(repository, encoder);
        UsernamePasswordAuthenticationToken owner = owner("admin", "ROLE_ADMIN");

        var created = service.create(owner, "Word laptop", true, true, 30);

        assertThat(created.secret()).startsWith("taxdav_").hasSizeLessThanOrEqualTo(72);
        assertThat(stored.get().getSecretHash()).doesNotContain(created.secret());
        assertThat(encoder.matches(created.secret(), stored.get().getSecretHash())).isTrue();
        assertThat(created.credential().scopes())
                .containsExactly("template:read", "template:write");
        assertCreatedAudit(created);

        var principal = service.authenticate("admin", created.secret()).orElseThrow();
        assertThat(principal.username()).isEqualTo("admin");
        assertThat(principal.writeAllowed()).isTrue();
        assertThat(principal.authorities()).extracting(
                authority -> authority.getAuthority())
                .contains("ROLE_ADMIN", "ROLE_WEBDAV_APPLICATION",
                        "SCOPE_template:read", "SCOPE_template:write");
    }

    @Test
    void createdAuditWaitsForSuccessfulCommitAndRetainsOnlyItsSafeMetadata() throws SQLException {
        CreationTransaction fixture = new CreationTransaction();
        AtomicInteger eventsBeforeCommit = new AtomicInteger();

        var created = fixture.transaction.execute(status -> {
            var result = fixture.create();
            eventsBeforeCommit.set(createdAudits().size());
            return result;
        });

        verify(fixture.repository).save(fixture.stored.get());
        verify(fixture.connection).commit();
        verify(fixture.connection, never()).rollback();
        assertThat(eventsBeforeCommit.get()).as("created audit before commit").isZero();
        assertCreatedAudit(created);
    }

    @Test
    void successfulSaveFollowedByRollbackDoesNotAuditCreated() throws SQLException {
        CreationTransaction fixture = new CreationTransaction();

        fixture.transaction.executeWithoutResult(status -> {
            fixture.create();
            status.setRollbackOnly();
        });

        verify(fixture.repository).save(fixture.stored.get());
        verify(fixture.connection).rollback();
        verify(fixture.connection, never()).commit();
        assertThat(createdAudits()).as("created audit for a rolled-back credential").isEmpty();
    }

    @Test
    void successfulSaveFollowedByCommitFailureDoesNotAuditCreated() throws SQLException {
        CreationTransaction fixture = new CreationTransaction();
        doThrow(new SQLException("synthetic credential-store commit failure"))
                .when(fixture.connection).commit();

        assertThatThrownBy(() -> fixture.transaction.execute(status -> fixture.create()))
                .isInstanceOf(TransactionSystemException.class)
                .hasCauseInstanceOf(SQLException.class);

        verify(fixture.repository).save(fixture.stored.get());
        verify(fixture.connection).commit();
        assertThat(createdAudits()).as("created audit for an unsuccessful commit").isEmpty();
    }

    @Test
    void writeCredentialRequiresTheAdministratorRole() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        WebDavApplicationCredentialService service = service(
                repository, new BCryptPasswordEncoder(4));

        assertThatThrownBy(() -> service.create(
                owner("architect", "ROLE_ARCHITECT"),
                "Word", true, true, 30))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Administrator");
    }

    @Test
    void revokedAndExpiredSecretsCannotBeReplayed() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String token = "taxdav_" + "a".repeat(24) + "_" + "A".repeat(39);
        WebDavApplicationCredential credential = credential(token, encoder);
        when(repository.findByCredentialIdAndUsername("a".repeat(24), "admin"))
                .thenReturn(Optional.of(credential));
        WebDavApplicationCredentialService service = service(repository, encoder);

        credential.setRevokedAt(NOW);
        assertThat(service.authenticate("admin", token)).isEmpty();
        credential.setRevokedAt(null);
        credential.setExpiresAt(NOW);
        assertThat(service.authenticate("admin", token)).isEmpty();
    }

    @Test
    void revokeIsUserBoundAndNeverReturnsTheStoredHash() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        WebDavApplicationCredential credential = new WebDavApplicationCredential();
        credential.setCredentialId("a".repeat(24));
        credential.setUsername("admin");
        credential.setDescription("Word");
        credential.setSecretHash("hash");
        credential.setReadAllowed(true);
        credential.setWriteAllowed(false);
        credential.setAuthorities("ROLE_ADMIN");
        credential.setCreatedAt(NOW);
        credential.setExpiresAt(NOW.plusSeconds(3600));
        when(repository.findByCredentialIdAndUsername("a".repeat(24), "admin"))
                .thenReturn(Optional.of(credential));
        WebDavApplicationCredentialService service = service(
                repository, new BCryptPasswordEncoder(4));

        service.revoke(owner("admin", "ROLE_ADMIN"), "a".repeat(24));

        assertThat(credential.getRevokedAt()).isEqualTo(NOW);
        verify(repository).save(credential);
    }

    private List<ILoggingEvent> createdAudits() {
        return audit.list.stream()
                .filter(event -> event.getMessage().startsWith("audit.webdav_credential.created"))
                .toList();
    }

    private void assertCreatedAudit(WebDavApplicationCredentialService.CreatedCredential created) {
        assertThat(createdAudits()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "audit.webdav_credential.created id=" + created.credential().id()
                            + " user=admin read=true write=true expires="
                            + created.credential().expiresAt());
            assertThat(event.getArgumentArray()).containsExactly(
                    created.credential().id(), "admin", true, true,
                    created.credential().expiresAt());
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    /** Exercise Spring's completion callbacks while controlling the JDBC commit outcome. */
    private static final class CreationTransaction {
        private final WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        private final AtomicReference<WebDavApplicationCredential> stored = new AtomicReference<>();
        private final Connection connection = mock(Connection.class);
        private final TransactionTemplate transaction;
        private final WebDavApplicationCredentialService service;

        private CreationTransaction() throws SQLException {
            when(repository.save(any())).thenAnswer(invocation -> {
                WebDavApplicationCredential credential = invocation.getArgument(0);
                stored.set(credential);
                return credential;
            });
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            service = service(repository, new BCryptPasswordEncoder(4));
        }

        private WebDavApplicationCredentialService.CreatedCredential create() {
            return service.create(owner("admin", "ROLE_ADMIN"), "Word laptop", false, true, 30);
        }
    }

    private static WebDavApplicationCredentialService service(
            WebDavApplicationCredentialRepository repository,
            BCryptPasswordEncoder encoder) {
        SecureRandom random = new SecureRandom(new byte[]{1, 2, 3, 4});
        return new WebDavApplicationCredentialService(
                repository, encoder, random, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UsernamePasswordAuthenticationToken owner(
            String username,
            String authority) {
        return UsernamePasswordAuthenticationToken.authenticated(
                username, "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static WebDavApplicationCredential credential(
            String token,
            BCryptPasswordEncoder encoder) {
        WebDavApplicationCredential credential = new WebDavApplicationCredential();
        credential.setCredentialId("a".repeat(24));
        credential.setUsername("admin");
        credential.setDescription("Word");
        credential.setSecretHash(encoder.encode(token));
        credential.setReadAllowed(true);
        credential.setWriteAllowed(true);
        credential.setAuthorities("ROLE_ADMIN");
        credential.setCreatedAt(NOW.minusSeconds(60));
        credential.setExpiresAt(NOW.plusSeconds(3600));
        return credential;
    }
}
