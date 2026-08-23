package com.taxonomy.security.webdav;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebDavApplicationCredentialCoverageTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void rejectsInvalidCreationRequestsAndUnauthenticatedOwners() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        WebDavApplicationCredentialService service = service(repository);
        Authentication admin = owner("admin", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.create(
                admin, "Word", false, false, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> service.create(
                admin, "Word", true, false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 90");
        assertThatThrownBy(() -> service.create(
                admin, "Word", true, false, 91))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 90");
        assertThatThrownBy(() -> service.create(
                admin, "invalid" + (char) 1 + "description", true, false, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid character");
        assertThatThrownBy(() -> service.create(
                admin, "x".repeat(161), true, false, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("160");

        assertThatThrownBy(() -> service.list(null))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.list(
                UsernamePasswordAuthenticationToken.unauthenticated("reader", "n/a")))
                .isInstanceOf(BadCredentialsException.class);

        Authentication missingName = mock(Authentication.class);
        when(missingName.isAuthenticated()).thenReturn(true);
        when(missingName.getName()).thenReturn(null);
        assertThatThrownBy(() -> service.list(missingName))
                .isInstanceOf(BadCredentialsException.class);

        Authentication blankName = mock(Authentication.class);
        when(blankName.isAuthenticated()).thenReturn(true);
        when(blankName.getName()).thenReturn("   ");
        assertThatThrownBy(() -> service.list(blankName))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void normalizesDefaultsPersistsScopesAndListsMetadata() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        List<WebDavApplicationCredential> stored = new ArrayList<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            WebDavApplicationCredential credential = invocation.getArgument(0);
            stored.add(credential);
            return credential;
        });
        WebDavApplicationCredentialService service = service(repository);

        Authentication readerWithoutRetainedRole = mock(Authentication.class);
        when(readerWithoutRetainedRole.isAuthenticated()).thenReturn(true);
        when(readerWithoutRetainedRole.getName()).thenReturn("reader");
        doReturn(null).when(readerWithoutRetainedRole).getAuthorities();

        var readOnly = service.create(
                readerWithoutRetainedRole, null, true, false, null);
        assertThat(readOnly.credential().description())
                .isEqualTo("Microsoft Word WebDAV");
        assertThat(readOnly.credential().scopes())
                .containsExactly("template:read");
        assertThat(readOnly.credential().expiresAt())
                .isEqualTo(NOW.plus(WebDavApplicationCredentialService.DEFAULT_LIFETIME));
        assertThat(stored.get(0).getAuthorities()).isEqualTo("ROLE_USER");

        var writeOnly = service.create(
                owner("admin", "ROLE_ADMIN"),
                "Desk\tWord", false, true, 1);
        assertThat(writeOnly.credential().description()).isEqualTo("Desk Word");
        assertThat(writeOnly.credential().scopes())
                .containsExactly("template:read", "template:write");
        assertThat(stored.get(1).isReadAllowed()).isTrue();
        assertThat(stored.get(1).isWriteAllowed()).isTrue();

        var blankDescription = service.create(
                owner("architect", "ROLE_ARCHITECT"),
                "   ", true, false, 2);
        assertThat(blankDescription.credential().description())
                .isEqualTo("Microsoft Word WebDAV");

        WebDavApplicationCredential noScopes = metadataCredential(
                "c".repeat(24), "reader", false, false);
        when(repository.findAllByUsernameOrderByCreatedAtDesc("reader"))
                .thenReturn(List.of(stored.get(0), noScopes));

        assertThat(service.list(readerWithoutRetainedRole))
                .extracting(WebDavApplicationCredentialService.CredentialMetadata::scopes)
                .containsExactly(List.of("template:read"), List.of());
    }

    @Test
    void revocationIsUserBoundIdempotentAndMetadataKnowsWhetherItIsActive() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        WebDavApplicationCredentialService service = service(repository);
        Authentication owner = owner("admin", "ROLE_ADMIN");
        String id = "a".repeat(24);
        WebDavApplicationCredential credential = metadataCredential(
                id, "admin", true, false);
        when(repository.findByCredentialIdAndUsername(id, "admin"))
                .thenReturn(Optional.of(credential));

        service.revoke(owner, id);
        assertThat(credential.getRevokedAt()).isEqualTo(NOW);
        verify(repository).save(credential);

        clearInvocations(repository);
        service.revoke(owner, id);
        verify(repository, never()).save(any());

        when(repository.findByCredentialIdAndUsername("b".repeat(24), "admin"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(owner, "b".repeat(24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");

        var active = new WebDavApplicationCredentialService.CredentialMetadata(
                id, "Word", List.of("template:read"),
                NOW.minusSeconds(60), NOW.plusSeconds(60), null, null);
        var revoked = new WebDavApplicationCredentialService.CredentialMetadata(
                id, "Word", List.of("template:read"),
                NOW.minusSeconds(60), NOW.plusSeconds(60), null, NOW);
        var expired = new WebDavApplicationCredentialService.CredentialMetadata(
                id, "Word", List.of("template:read"),
                NOW.minusSeconds(60), NOW, null, null);

        assertThat(active.active(NOW)).isTrue();
        assertThat(revoked.active(NOW)).isFalse();
        assertThat(expired.active(NOW)).isFalse();
    }

    @Test
    void authenticationRejectsIncompleteMalformedUnknownAndMismatchedSecrets() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        WebDavApplicationCredentialService service = service(repository, encoder);
        String id = "a".repeat(24);
        String token = token(id, 'A');

        assertThat(service.authenticate(null, token)).isEmpty();
        assertThat(service.authenticate("   ", token)).isEmpty();
        assertThat(service.authenticate("admin", null)).isEmpty();
        assertThat(service.authenticate("admin", "not-a-webdav-token")).isEmpty();

        when(repository.findByCredentialIdAndUsername(id, "admin"))
                .thenReturn(Optional.empty());
        assertThat(service.authenticate("admin", token)).isEmpty();

        WebDavApplicationCredential mismatched = credential(
                id, token(id, 'B'), encoder, true, true,
                "ROLE_ADMIN", null);
        when(repository.findByCredentialIdAndUsername(id, "admin"))
                .thenReturn(Optional.of(mismatched));
        assertThat(service.authenticate("admin", token)).isEmpty();

        assertThat(WebDavApplicationCredentialService.isApplicationSecret(null)).isFalse();
        assertThat(WebDavApplicationCredentialService.isApplicationSecret("ordinary-password"))
                .isFalse();
        assertThat(WebDavApplicationCredentialService.isApplicationSecret(token)).isTrue();
    }

    @Test
    void authenticationHonorsScopesThrottlesWritesAndSurvivesLastUsedRaces() {
        WebDavApplicationCredentialRepository repository =
                mock(WebDavApplicationCredentialRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        WebDavApplicationCredentialService service = service(repository, encoder);

        String readId = "a".repeat(24);
        String readToken = token(readId, 'A');
        WebDavApplicationCredential readOnly = credential(
                readId, readToken, encoder, true, false,
                null, NOW.minusSeconds(60));
        when(repository.findByCredentialIdAndUsername(readId, "admin"))
                .thenReturn(Optional.of(readOnly));

        var reader = service.authenticate("admin", readToken).orElseThrow();
        assertThat(reader.readAllowed()).isTrue();
        assertThat(reader.writeAllowed()).isFalse();
        assertThat(reader.authorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder(
                        "ROLE_WEBDAV_APPLICATION", "SCOPE_template:read");
        verify(repository, never()).save(any());

        String unscopedId = "b".repeat(24);
        String unscopedToken = token(unscopedId, 'B');
        WebDavApplicationCredential unscoped = credential(
                unscopedId, unscopedToken, encoder, false, false,
                "ROLE_ADMIN,IGNORED,ROLE_ADMIN", null);
        when(repository.findByCredentialIdAndUsername(unscopedId, "admin"))
                .thenReturn(Optional.of(unscoped));
        when(repository.save(unscoped)).thenThrow(
                new OptimisticLockingFailureException("concurrent use"));

        var principal = service.authenticate("admin", unscopedToken).orElseThrow();
        assertThat(principal.readAllowed()).isFalse();
        assertThat(principal.writeAllowed()).isFalse();
        assertThat(principal.authorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_WEBDAV_APPLICATION");
        verify(repository).save(unscoped);
    }

    private static WebDavApplicationCredentialService service(
            WebDavApplicationCredentialRepository repository) {
        return service(repository, new BCryptPasswordEncoder(4));
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

    private static String token(String id, char secretCharacter) {
        return "taxdav_" + id + "_" + String.valueOf(secretCharacter).repeat(39);
    }

    private static WebDavApplicationCredential credential(
            String id,
            String token,
            BCryptPasswordEncoder encoder,
            boolean readAllowed,
            boolean writeAllowed,
            String authorities,
            Instant lastUsedAt) {
        WebDavApplicationCredential credential = metadataCredential(
                id, "admin", readAllowed, writeAllowed);
        credential.setSecretHash(encoder.encode(token));
        credential.setAuthorities(authorities);
        credential.setLastUsedAt(lastUsedAt);
        return credential;
    }

    private static WebDavApplicationCredential metadataCredential(
            String id,
            String username,
            boolean readAllowed,
            boolean writeAllowed) {
        WebDavApplicationCredential credential = new WebDavApplicationCredential();
        credential.setCredentialId(id);
        credential.setUsername(username);
        credential.setDescription("Word");
        credential.setSecretHash("hash");
        credential.setReadAllowed(readAllowed);
        credential.setWriteAllowed(writeAllowed);
        credential.setAuthorities("ROLE_USER");
        credential.setCreatedAt(NOW.minusSeconds(60));
        credential.setExpiresAt(NOW.plusSeconds(3600));
        return credential;
    }
}
