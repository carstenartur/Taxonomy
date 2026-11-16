package com.taxonomy.security.service;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.AppPrincipal;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** A personal catalogue handoff and its stable-identity audit share the membership transaction. */
@Service
public class RepositoryOwnershipTransfer {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PrincipalIdentityService identities;
    private final SystemRepositoryRepository repositories;
    private final RepositoryMembershipService memberships;
    private final JdbcTemplate jdbc;

    public RepositoryOwnershipTransfer(PrincipalIdentityService identities, SystemRepositoryRepository repositories,
                                       RepositoryMembershipService memberships, JdbcTemplate jdbc) {
        this.identities = identities;
        this.repositories = repositories;
        this.memberships = memberships;
        this.jdbc = jdbc;
    }

    public record Receipt(UUID auditId, UUID previousOwner, UUID newOwner, long version) { }
    private record Decision(String repositoryId, long previousVersion, long newVersion, String rationale, String outcome) { }

    @Transactional
    public Receipt transfer(Authentication authentication, String repositoryId, PrincipalId target,
                            long expectedVersion, String rationale) {
        if (target == null || expectedVersion < 0 || rationale == null || rationale.isBlank()
                || rationale.length() > 256 || rationale.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A target principal, catalogue revision and bounded rationale are required");
        }
        var actor = identities.requireCurrent(authentication);
        var repository = repositories.findByRepositoryId(repositoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String scope = authentication.getName();
        if (!memberships.canRead(repository, scope)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (repository.getLifecycleState() != RepositoryLifecycleState.ACTIVE
                || (repository.getOwnerType() != null && repository.getOwnerType() != RepositoryOwnerType.USER)
                || !scope.equals(repository.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the current personal catalogue owner can transfer ownership");
        }
        if (repository.getVersion() != expectedVersion) throw stale();
        var previous = identities.find(actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!scope.equals(previous.scopeKey())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (actor.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a different owner");
        AppPrincipal successor = identities.find(target).filter(identities::hasCurrentLoginForScope)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "The target requires an active identity"));
        // Hold the authoritative identity rows until the ownership and audit transaction commits. An
        // unlink or account disable either precedes this decision or waits until the handoff is durable.
        for (String principal : java.util.stream.Stream.of(actor.value().toString(), target.value().toString()).sorted().toList()) {
            jdbc.update("update app_principal set enabled=enabled where principal_id=?", principal);
            jdbc.update("update principal_binding set enabled=enabled where principal_id=?", principal);
            jdbc.update("update app_user set enabled=enabled where principal_id=?", principal);
        }
        identities.requireCurrent(authentication);
        if (!identities.hasCurrentLoginForScope(successor)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The target is no longer active");
        try {
            memberships.transferPersonalOwnership(repository, expectedVersion, scope, successor.scopeKey());
            repositories.saveAndFlush(repository);
            // Revalidate the exact initiating login and the successor after waiting for the catalogue fence.
            if (!actor.equals(identities.requireCurrent(authentication)) || !identities.hasCurrentLoginForScope(successor)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A principal is no longer active");
            }
        } catch (OptimisticLockingFailureException conflict) {
            throw stale();
        }
        var audit = UUID.randomUUID();
        var detail = new Decision(repositoryId, expectedVersion, repository.getVersion(), rationale.strip(), "TRANSFERRED");
        jdbc.update("""
                insert into principal_access_audit(audit_id,actor_principal,target_principal,decision_kind,detail,occurred_at)
                values (?, ?, ?, 'TRANSFER_REPOSITORY_OWNER', ?, ?)
                """, audit.toString(), actor.value().toString(), target.value().toString(),
                JSON.writeValueAsString(detail), Instant.now().toEpochMilli());
        return new Receipt(audit, actor.value(), target.value(), repository.getVersion());
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The repository catalogue version changed");
    }
}
