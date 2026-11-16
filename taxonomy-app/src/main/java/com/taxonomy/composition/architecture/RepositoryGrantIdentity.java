package com.taxonomy.composition.architecture;

import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.RepositoryMembershipIdentity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Records authorized role decisions without assigning registry IDs to legacy identities. */
@Component
public final class RepositoryGrantIdentity implements RepositoryMembershipIdentity {
    private final PrincipalIdentityService identities;
    private final Optional<KeycloakPrincipalMode> mode;
    private final WorkspaceFetchAuthority authority;
    private final JdbcTemplate jdbc;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public RepositoryGrantIdentity(PrincipalIdentityService identities, Optional<KeycloakPrincipalMode> mode,
                                   WorkspaceFetchAuthority authority, JdbcTemplate jdbc) {
        this.authority = authority;
        this.jdbc = jdbc;
        this.identities = identities;
        this.mode = mode;
    }

    @Override public Optional<UUID> currentActor(String scope) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth instanceof StablePrincipal || auth.getPrincipal() instanceof StablePrincipal)) {
            return Optional.empty();
        }
        if (!scope.equals(auth.getName())) return Optional.empty();
        return Optional.of(identities.requireCurrent(auth).value());
    }

    @Override public void recordRoleDecision(String scope, RoleDecision decision) {
        var principal = currentActor(scope);
        String detail = JSON.writeValueAsString(decision);
        String id = UUID.randomUUID().toString();
        long time = Instant.now().toEpochMilli();
        if (principal.isPresent()) {
            String authorizer = principal.orElseThrow().toString();
            jdbc.update("insert into principal_access_audit(audit_id,actor_principal,target_principal,decision_kind,detail,occurred_at) values (?,?,?,'REPOSITORY_MEMBERSHIP',?,?)",
                    id, authorizer, authorizer, detail, time);
        } else {
            String binding = authority.currentActor(scope);
            if (binding == null || !binding.matches("[0-9a-f]{64}")) throw new AccessDeniedException("Verified OIDC binding required");
            jdbc.update("insert into legacy_repository_role_audit(audit_id,actor_binding,detail,occurred_at) values (?,?,?,?)",
                    id, binding, detail, time);
        }
    }

    @Override public Optional<UUID> activeHolder(String scope) {
        if (mode.orElse(null) == KeycloakPrincipalMode.LEGACY) return Optional.empty();
        return identities.findActiveForScope(scope).map(principal -> principal.id().value());
    }
}
