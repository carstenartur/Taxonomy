package com.taxonomy.security.access;

import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VariantDeletionAuthorityTest {
    private final WorkspaceFetchAuthority identities = mock(WorkspaceFetchAuthority.class);
    private final VariantDeletionAuthority authority = new VariantDeletionAuthority(identities);

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private void bearer(Instant expiry, String scope) {
        var jwt = Jwt.withTokenValue("fixture").header("alg","RS256").subject("controlled")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority("ROLE_ARCHITECT"), new SimpleGrantedAuthority("SCOPE_" + scope)), "controlled"));
    }

    @Test void deletePermissionRequiresCurrentLifetimeAndDedicatedScope() {
        bearer(Instant.now().minusSeconds(1), "architecture:delete");
        assertThatThrownBy(() -> authority.require("controlled")).isInstanceOf(AccessDeniedException.class);
        bearer(Instant.now().plusSeconds(60), "architecture:write");
        assertThatThrownBy(() -> authority.require("controlled")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(identities);
        bearer(Instant.now().plusSeconds(60), "architecture:delete");
        when(identities.currentActor("controlled")).thenReturn("verified-actor");
        assertThat(authority.require("controlled")).isEqualTo("verified-actor");
    }
}
