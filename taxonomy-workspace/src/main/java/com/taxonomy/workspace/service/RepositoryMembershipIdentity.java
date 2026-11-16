package com.taxonomy.workspace.service;

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

    Optional<UUID> currentActor(String scope);
    Optional<UUID> activeHolder(String scope);
}
