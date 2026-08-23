package com.taxonomy.security.webdav;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebDavApplicationCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

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

        assertThat(created.secret()).startsWith("taxdav_");
        assertThat(stored.get().getSecretHash()).doesNotContain(created.secret());
        assertThat(encoder.matches(created.secret(), stored.get().getSecretHash())).isTrue();
        assertThat(created.credential().scopes())
                .containsExactly("template:read", "template:write");

        var principal = service.authenticate("admin", created.secret()).orElseThrow();
        assertThat(principal.username()).isEqualTo("admin");
        assertThat(principal.writeAllowed()).isTrue();
        assertThat(principal.authorities()).extracting(
                authority -> authority.getAuthority())
                .contains("ROLE_ADMIN", "ROLE_WEBDAV_APPLICATION",
                        "SCOPE_template:read", "SCOPE_template:write");
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
        String token = "taxdav_" + "a".repeat(24) + "_" + "A".repeat(43);
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
