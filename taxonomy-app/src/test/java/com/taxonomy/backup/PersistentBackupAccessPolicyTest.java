package com.taxonomy.backup;

import com.taxonomy.composition.backup.PersistentBackupAccessPolicy;

import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentBackupAccessPolicyTest {
    PrincipalIdentityService identities;
    PrincipalId alice;
    JdbcTemplate jdbc;
    SystemRepositoryRepository repositories;
    UserWorkspaceRepository workspaces;
    RepositoryMembershipRepository memberships;
    PersistentBackupAccessPolicy policy;
    BackupAuthorizationService authorization;
    final BackupRepositoryKey selected = new BackupRepositoryKey("repo", "private");

    @BeforeEach void fixture() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:access-" + UUID.randomUUID()); database.setUser("sa");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.update("insert into app_user values (1, 'alice', true)");
        PrincipalSchemaMigration.migrate(database);
        identities = new PrincipalIdentityService(database); alice = identities.local(1L).id();
        repositories = mock(SystemRepositoryRepository.class);
        workspaces = mock(UserWorkspaceRepository.class);
        memberships = mock(RepositoryMembershipRepository.class);
        var repository = new SystemRepository(); repository.setRepositoryId("repo"); repository.setPrimaryRepo(true);
        when(repositories.findByRepositoryId("repo")).thenReturn(Optional.of(repository));
        var workspace = new UserWorkspace(); workspace.setWorkspaceId("private"); workspace.setUsername("alice"); workspace.setSourceRepositoryId("repo");
        when(workspaces.findByWorkspaceId("private")).thenReturn(Optional.of(workspace));
        policy = new PersistentBackupAccessPolicy(identities, repositories, workspaces,
                new RepositoryMembershipService(memberships));
        authorization = new BackupAuthorizationService(policy, Clock.systemUTC());
    }

    @Test void sameNamedExternalIdentityCannotReadLocalPrivateWorkspace() {
        assertThat(policy.canRead(alice, selected)).isTrue();
        var external = identities.oidc("https://another-idp.example", "alice");
        assertThat(policy.canRead(external.id(), new BackupRepositoryKey("repo", null))).isTrue();
        assertThat(policy.canRead(external.id(), selected)).isFalse();
    }

    @Test void currentExportAndDownloadAreIndependentOfHistoryAndAdministration() {
        var request = request(BackupProfile.CURRENT_STATE);
        var created = authorization.authorize(alice, request);
        assertThatCode(() -> authorization.requireDownload(alice, created)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorization.authorize(alice, request(BackupProfile.REPOSITORY_HISTORY)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(policy.hasCapability(alice, BackupCapability.INCLUDE_SECRETS, request.scope())).isFalse();
        assertThat(policy.hasCapability(alice, BackupCapability.EXPORT_INSTALLATION, new BackupScope.Installation())).isFalse();
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='DOWNLOAD_BACKUP'", alice.value().toString());
        assertThatThrownBy(() -> authorization.requireDownload(alice, created)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='EXPORT_CURRENT'", alice.value().toString());
        assertThatThrownBy(() -> authorization.requireJobAccess(alice, created)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void archivedOrReassignedWorkspaceInvalidatesAnExistingJob() {
        var created = authorization.authorize(alice, request(BackupProfile.CURRENT_STATE));
        var workspace = workspaces.findByWorkspaceId("private").orElseThrow();
        workspace.setSourceRepositoryId("other-repository");
        assertThatThrownBy(() -> authorization.requireDownload(alice, created)).isInstanceOf(AccessDeniedException.class);
        workspace.setSourceRepositoryId("repo"); workspace.setArchived(true);
        assertThatThrownBy(() -> authorization.requireDownload(alice, created)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void selectedVersionRequiresAnExactScopeAndCommitGrant() {
        String commit = "a".repeat(40);
        assertThat(policy.canReadVersion(alice, selected, commit)).isFalse();
        jdbc.update("insert into backup_version_grant(principal_id, repository_id, workspace_key, commit_id) values (?, 'repo', 'W:private', ?)", alice.value().toString(), commit);
        assertThat(policy.canReadVersion(alice, selected, commit)).isTrue();
        assertThat(policy.canReadVersion(alice, selected, "b".repeat(40))).isFalse();
        assertThat(policy.canReadVersion(alice, new BackupRepositoryKey("repo", null), commit)).isFalse();
    }

    private BackupRequest request(BackupProfile profile) {
        return new BackupRequest(profile, new BackupScope.Workspace("repo", "private"),
                profile.includesHistory() ? new BackupTime.History() : new BackupTime.Current(),
                profile.includesHistory() ? GitRepresentation.BUNDLE : GitRepresentation.NONE, SecretsSelection.EXCLUDE);
    }
}
