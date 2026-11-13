package com.taxonomy.security.access;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryIndexGrantAdapterTest {
    final WorkspaceFetchAuthority originator = mock(WorkspaceFetchAuthority.class);
    final PrincipalIdentityService identities = mock(PrincipalIdentityService.class);
    final HistoryIndexGrantAdapter grants = new HistoryIndexGrantAdapter(originator, identities);
    final PrincipalId principal = new PrincipalId(UUID.randomUUID());
    final String actor = principal.value().toString();

    @AfterEach void clearLogin() { SecurityContextHolder.clearContext(); }

    @Test void localArchitectureRoleAndExactBindingAreRequiredForUnattendedPurge() {
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ARCHITECT"));
        var user = new PrincipalUserDetails(principal, "owner", "unused", true, roles);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, roles));
        when(originator.currentActor("owner")).thenReturn(actor);
        when(identities.currentLocalAuthority(principal, "owner")).thenReturn(new PrincipalIdentityService.LocalAuthority("original", List.of("ROLE_ARCHITECT")));
        var recorded = grants.authorize("owner", true);
        assertThat(recorded).isEqualTo(new Grant(actor, "LOCAL", "original"));
        grants.verify(recorded, "owner", true, false);
        SecurityContextHolder.clearContext();
        grants.verify(recorded, "owner", true, true);

        when(identities.currentLocalAuthority(principal, "owner")).thenReturn(new PrincipalIdentityService.LocalAuthority("different", List.of("ROLE_ARCHITECT")));
        assertThatThrownBy(() -> grants.verify(recorded, "owner", true, true)).isInstanceOf(AccessDeniedException.class);
        when(identities.currentLocalAuthority(principal, "owner")).thenReturn(new PrincipalIdentityService.LocalAuthority("original", List.of("ROLE_USER")));
        assertThatThrownBy(() -> grants.verify(recorded, "owner", false, true)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void externalOriginsNeedFreshVerifiedLoginAndCannotFallBackToAnotherEnabledIdentity() {
        var legacy = new Grant("issuer-key", "LEGACY_OIDC", "issuer-key");
        assertThatThrownBy(() -> grants.verify(legacy, "owner", true, true)).isInstanceOf(WorkspaceFetchAuthority.FreshLoginRequired.class);
        var stable = new Grant(actor, "OIDC", "issuer-key");
        when(identities.isEnabled(principal)).thenReturn(false);
        assertThatThrownBy(() -> grants.verify(stable, "owner", false, true)).isInstanceOf(AccessDeniedException.class);
        when(identities.isEnabled(principal)).thenReturn(true);
        assertThatThrownBy(() -> grants.verify(stable, "owner", false, true)).isInstanceOf(WorkspaceFetchAuthority.FreshLoginRequired.class);
    }

    @Test void aReadOnlyLoginCannotSubmitAnIndexMutation() {
        var roles = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var user = new PrincipalUserDetails(principal, "owner", "unused", true, roles);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, roles));
        when(originator.currentActor("owner")).thenReturn(actor);
        assertThatThrownBy(() -> grants.authorize("owner", false)).isInstanceOf(AccessDeniedException.class);
    }
}
