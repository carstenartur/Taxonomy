package com.taxonomy.security.service;

import com.taxonomy.security.access.ApiAccessPolicy;
import com.taxonomy.security.access.ApiPermission;
import com.taxonomy.workspace.model.RepositoryRefRule;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryRefPolicy;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.persistence.EntityManager;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.hibernate.Session;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** The desired policy is read directly in the native ref transaction, without a permissive projection. */
@Service
public class VersionedRepositoryRefPolicy implements RepositoryRefPolicy {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EntityManager db;
    private final SystemRepositoryRepository repositories;
    private final UserWorkspaceRepository workspaces;
    private final RepositoryMembershipService memberships;
    private final PrincipalIdentityService identities;
    private final JdbcTemplate jdbc;

    public VersionedRepositoryRefPolicy(EntityManager db, SystemRepositoryRepository repositories,
            UserWorkspaceRepository workspaces, RepositoryMembershipService memberships,
            PrincipalIdentityService identities, JdbcTemplate jdbc) {
        this.db = db;
        this.repositories = repositories;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.identities = identities;
        this.jdbc = jdbc;
    }

    @Override
    public void require(Session storageTransaction, String storageName, String branch, Operation operation) {
        lockStorage(storageTransaction, storageName);
        // A scalar query refreshes the decision even if this session performed an earlier advisory check.
        var rows = storageTransaction.createQuery("""
                select r.preventUpdates, r.preventForce, r.preventDelete
                from RepositoryRefRule r where r.id=:id
                """, Object[].class).setParameter("id", RepositoryRefRule.key(storageName, branch)).getResultList();
        if (rows.isEmpty()) return;
        Object[] policy = rows.getFirst();
        boolean denied = switch (operation) {
            case UPDATE -> (Boolean) policy[0];
            case FORCE -> (Boolean) policy[0] || (Boolean) policy[1];
            case DELETE -> (Boolean) policy[2];
        };
        if (denied) throw new AccessDeniedException("The selected branch policy prevents this ref operation");
    }

    @Transactional(readOnly = true)
    public RepositoryRefRule.View inspect(Authentication login, String repositoryId, String workspaceId, String branch) {
        var repository = visible(repositoryId, login.getName());
        String storage = storage(repository, workspaceId, login.getName());
        validateBranch(branch);
        return current(storage, repositoryId, workspaceId, branch).view();
    }

    public record Change(RepositoryRefRule.View policy, UUID auditId) { }
    private record Audit(RepositoryRefRule.View before, RepositoryRefRule.View after,
                         String rationale, boolean adminOverride, String outcome) { }

    @Transactional
    public Change change(Authentication login, String repositoryId, String workspaceId, String branch,
            long expectedRevision, boolean preventUpdates, boolean preventForce, boolean preventDelete,
            boolean adminOverride, String rationale) {
        validateBranch(branch);
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE || rationale == null || rationale.isBlank() || rationale.length() > 256
                || rationale.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A policy revision and bounded rationale are required");
        }
        var actor = currentActor(login);
        boolean overrideAllowed = adminOverride && ApiAccessPolicy.hasPermission(login, ApiPermission.ADMIN_CONFIGURATION);
        if (adminOverride && !overrideAllowed) throw denied();
        if (repositories.lockForMutation(repositoryId) != 1) throw missing();
        memberships.lockForRefMutation(repositoryId);
        var repository = repositories.findByRepositoryId(repositoryId).orElseThrow(VersionedRepositoryRefPolicy::missing);
        db.refresh(repository);
        if (repository.getLifecycleState() != RepositoryLifecycleState.ACTIVE) throw denied();
        if (!memberships.canRead(repository, login.getName()) && !overrideAllowed) throw missing();
        String physical = storage(repository, workspaceId, login.getName());
        boolean owner = memberships.effectiveRole(repository, login.getName()).filter(role -> role == RepositoryRole.OWNER).isPresent();
        if (workspaceId != null && !workspaceId.isBlank()) {
            var workspace = workspaces.findByWorkspaceIdForUpdate(workspaceId).orElseThrow(VersionedRepositoryRefPolicy::missing);
            owner = workspace.matchesOwner(login.getName(), actor.value().toString())
                    && memberships.canContribute(repository, login.getName());
        }
        if (!owner && !overrideAllowed) throw denied();
        // The native publisher holds this same row through the actual conditional ref write.
        lockStorage(db.unwrap(Session.class), physical);
        String key = RepositoryRefRule.key(physical, branch);
        var rule = db.find(RepositoryRefRule.class, key);
        boolean first = rule == null;
        if (first) rule = new RepositoryRefRule(physical, repositoryId, workspaceId, branch);
        var before = rule.view();
        if (before.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The reviewed branch policy changed");
        }
        if (!actor.equals(currentActor(login))) throw denied();
        rule.change(preventUpdates, preventForce, preventDelete);
        if (first) db.persist(rule);
        db.flush();
        var audit = UUID.randomUUID();
        jdbc.update("""
                insert into principal_access_audit(audit_id,actor_principal,target_principal,decision_kind,detail,occurred_at)
                values (?, ?, ?, 'CHANGE_REPOSITORY_REF_POLICY', ?, ?)
                """, audit.toString(), actor.value().toString(), actor.value().toString(),
                JSON.writeValueAsString(new Audit(before, rule.view(), rationale.strip(), adminOverride, "APPLIED")),
                Instant.now().toEpochMilli());
        return new Change(rule.view(), audit);
    }

    private com.taxonomy.backup.PrincipalId currentActor(Authentication login) {
        var actor = identities.requireCurrent(login);
        if (!ApiAccessPolicy.hasPermission(login, ApiPermission.REPOSITORY_MANAGE)) throw denied();
        Instant expiry = null;
        if (login instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            expiry = jwt.getToken().getExpiresAt();
            if (expiry == null) throw denied();
        } else if (login.getPrincipal() instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser oidc) {
            expiry = oidc.getIdToken().getExpiresAt();
            if (expiry == null) throw denied();
        }
        if (expiry != null && !expiry.isAfter(Instant.now())) throw denied();
        return actor;
    }

    private static void lockStorage(Session transaction, String storageName) {
        // A no-op write retains the mutex on HSQL MVCC as well as databases with locking reads.
        int existing = transaction.createMutationQuery("""
                update GitRepositoryLockEntity l set l.createdAt=l.createdAt
                where l.repositoryName=:storage
                """).setParameter("storage", storageName).executeUpdate();
        if (existing != 1) throw missing();
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void removeWorkspace(String repositoryId, String workspaceId, String actorScope) {
        String physical = DslGitRepositoryFactory.workspaceRepositoryName(workspaceId);
        var rules = db.createQuery("""
                select r from RepositoryRefRule r where r.repositoryId=:repository
                  and r.workspaceId=:workspace and r.storageName=:physical
                """, RepositoryRefRule.class)
                .setParameter("repository", repositoryId).setParameter("workspace", workspaceId)
                .setParameter("physical", physical).getResultList();
        if (rules.isEmpty()) return;
        var login = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (login == null || !actorScope.equals(login.getName())) throw denied();
        var actor = identities.requireCurrent(login);
        var workspace = workspaces.findByWorkspaceIdForUpdate(workspaceId).orElseThrow(VersionedRepositoryRefPolicy::missing);
        if (!repositoryId.equals(workspace.getSourceRepositoryId())
                || !workspace.matchesOwner(actorScope, actor.value().toString())
                || workspace.isShared() || workspace.isDefault()) throw denied();
        for (var rule : rules) {
            jdbc.update("""
                    insert into principal_access_audit(audit_id,actor_principal,target_principal,decision_kind,detail,occurred_at)
                    values (?, ?, ?, 'REMOVE_WORKSPACE_REF_POLICY', ?, ?)
                    """, UUID.randomUUID().toString(), actor.value().toString(), actor.value().toString(),
                    JSON.writeValueAsString(new Audit(rule.view(), null, "OWNER_REMOVED_WORKSPACE", false, "APPLIED")),
                    Instant.now().toEpochMilli());
            db.remove(rule);
        }
    }

    private RepositoryRefRule current(String physical, String repository, String workspace, String branch) {
        var found = db.find(RepositoryRefRule.class, RepositoryRefRule.key(physical, branch));
        return found == null ? new RepositoryRefRule(physical, repository, workspace, branch) : found;
    }

    private SystemRepository visible(String id, String scope) {
        var repository = repositories.findByRepositoryId(id).orElseThrow(VersionedRepositoryRefPolicy::missing);
        if (!memberships.canRead(repository, scope)) throw missing();
        return repository;
    }

    private String storage(SystemRepository repository, String workspaceId, String scope) {
        if (workspaceId == null || workspaceId.isBlank()) {
            String physical = repository.getStorageRepositoryName();
            if (physical == null || physical.isBlank()) throw missing();
            return physical;
        }
        var workspace = workspaces.findByWorkspaceId(workspaceId).orElseThrow(VersionedRepositoryRefPolicy::missing);
        if (!repository.getRepositoryId().equals(workspace.getSourceRepositoryId())
                || !memberships.canUseWorkspace(repository, workspace, scope)) throw missing();
        return DslGitRepositoryFactory.workspaceRepositoryName(workspaceId);
    }

    private static void validateBranch(String branch) {
        if (branch == null || branch.length() > 255 || !Repository.isValidRefName(Constants.R_HEADS + branch)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An exact branch name is required");
        }
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static AccessDeniedException denied() { return new AccessDeniedException("The current owner must authorize branch policy changes"); }
}
