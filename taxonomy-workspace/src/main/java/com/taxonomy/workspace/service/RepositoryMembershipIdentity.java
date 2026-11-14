package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;

import java.util.Optional;
import java.util.UUID;

/** Stable identities available for a newly authorized direct repository grant. */
public interface RepositoryMembershipIdentity {
    /** Bound only inside a journal callback, after the journal validates its original login and lease. */
    record RecordedActor(String scope, WorkspaceFetchAuthority.Grant grant) {
        private static final ScopedValue<RecordedActor> CURRENT = ScopedValue.newInstance();

        public static <T, E extends Exception> T during(String scope, WorkspaceFetchAuthority.Grant grant,
                                                        ScopedValue.CallableOp<T, E> action) throws E {
            return ScopedValue.where(CURRENT, new RecordedActor(scope, grant)).call(action);
        }

        static Optional<String> currentPrincipal(String scope) {
            if (!CURRENT.isBound()) return Optional.empty();
            var actor = CURRENT.get();
            return actor.scope.equals(scope) && ("LOCAL".equals(actor.grant.loginKind()) || "OIDC".equals(actor.grant.loginKind()))
                    ? Optional.of(actor.grant.principalId()) : Optional.empty();
        }
    }

    record State(boolean active, RepositoryRole role, long version) {
        static State of(RepositoryMembership membership) {
            return membership == null ? new State(false, null, -1)
                    : new State(membership.isActive(), membership.getRole(), membership.getVersion());
        }
    }

    record RoleDecision(String repositoryId, long membershipId, String holderPrincipalId, State before,
                        State after, String rationale, String outcome, String subject) { }

    void recordRoleDecision(String actor, RoleDecision decision);

    Optional<UUID> currentActor(String scope);
    Optional<UUID> activeHolder(String scope);
}
