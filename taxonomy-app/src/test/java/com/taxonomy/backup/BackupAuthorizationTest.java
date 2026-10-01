package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BackupAuthorizationTest {
    private final PrincipalId alice = PrincipalId.create();
    private final PrincipalId bob = PrincipalId.create();
    private final BackupRepositoryKey own = new BackupRepositoryKey("repo", "alice-workspace");
    private final BackupScope scope = new BackupScope.Workspace(own.repositoryId(), own.workspaceId());
    private final Access access = new Access();
    private final BackupAuthorizationService service = new BackupAuthorizationService(access, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test void normalReaderCanExportCurrentButDoesNotAcquireHistoryInstallationOrSecrets() {
        access.enabled.add(alice); access.readable.add(own);
        access.granted.addAll(Set.of(BackupCapability.EXPORT_CURRENT, BackupCapability.DOWNLOAD_BACKUP));
        var authorization = service.authorize(alice, request(BackupProfile.CURRENT_STATE, scope));
        assertThat(authorization.authorizedAt()).isEqualTo(Instant.EPOCH);
        assertThatCode(() -> service.requireDownload(alice, authorization)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.authorize(alice, request(BackupProfile.REPOSITORY_HISTORY, scope))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.authorize(alice, request(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.require(alice, BackupCapability.INCLUDE_SECRETS, scope)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void repositoryReadDoesNotIncludeAnotherUsersWorkspace() {
        access.enabled.add(alice); access.granted.addAll(EnumSet.allOf(BackupCapability.class));
        access.readable.add(new BackupRepositoryKey("repo", null)); access.readable.add(own);
        var foreign = new BackupScope.Repositories(Map.of("repo", Set.of("alice-workspace", "bob-private")));
        assertThatThrownBy(() -> service.authorize(alice, request(BackupProfile.CURRENT_STATE, foreign))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void revokedHistoryPermissionRejectsAnAlreadyCreatedDownload() {
        allowHistory();
        var authorization = service.authorize(alice, request(BackupProfile.REPOSITORY_HISTORY, scope));
        access.granted.remove(BackupCapability.EXPORT_HISTORY);
        assertThatThrownBy(() -> service.requireDownload(alice, authorization)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void scopeRevocationDisabledUserAndAnotherDownloaderAreRejected() {
        allowHistory();
        var authorization = service.authorize(alice, request(BackupProfile.CURRENT_STATE, scope));
        access.readable.clear();
        assertThatThrownBy(() -> service.requireDownload(alice, authorization)).isInstanceOf(AccessDeniedException.class);
        access.readable.add(own); access.enabled.clear();
        assertThatThrownBy(() -> service.requireDownload(alice, authorization)).isInstanceOf(AccessDeniedException.class);
        access.enabled.addAll(Set.of(alice, bob));
        assertThatThrownBy(() -> service.requireDownload(bob, authorization)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void selectedVersionNeedsAccessToThatExactContent() {
        allowHistory(); access.granted.remove(BackupCapability.EXPORT_HISTORY);
        var version = new BackupRequest(BackupProfile.SELECTED_VERSION, scope,
                new BackupTime.SelectedVersion(Map.of(own, "a".repeat(40))), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        assertThatThrownBy(() -> service.authorize(alice, version)).isInstanceOf(AccessDeniedException.class);
        access.versions.put(own, "a".repeat(40));
        var authorization = service.authorize(alice, version);
        access.versions.put(own, "b".repeat(40));
        assertThatThrownBy(() -> service.requireDownload(alice, authorization)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void backupCreationGrantsNeitherRestoreNorDownload() {
        allowHistory();
        access.granted.remove(BackupCapability.DOWNLOAD_BACKUP);
        var authorization = service.authorize(alice, request(BackupProfile.CURRENT_STATE, scope));
        assertThatThrownBy(() -> service.requireDownload(alice, authorization)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.require(alice, BackupCapability.RESTORE_INSTALLATION, new BackupScope.Installation())).isInstanceOf(AccessDeniedException.class);
    }
    private void allowHistory() {
        access.enabled.add(alice); access.readable.add(own);
        access.granted.addAll(Set.of(BackupCapability.EXPORT_CURRENT, BackupCapability.EXPORT_HISTORY, BackupCapability.DOWNLOAD_BACKUP));
    }
    private BackupRequest request(BackupProfile profile, BackupScope selection) {
        return new BackupRequest(profile, selection, profile.includesHistory() ? new BackupTime.History() : new BackupTime.Current(),
                profile.includesHistory() ? GitRepresentation.BUNDLE : GitRepresentation.NONE, SecretsSelection.EXCLUDE);
    }
    /** Mutable policy models independent authoritative changes between start and download. */
    private static final class Access implements BackupAccessPolicy {
        final Set<PrincipalId> enabled = new HashSet<>();
        final Set<BackupCapability> granted = EnumSet.noneOf(BackupCapability.class);
        final Set<BackupRepositoryKey> readable = new HashSet<>();
        final Map<BackupRepositoryKey, String> versions = new HashMap<>();
        public boolean isEnabled(PrincipalId id) { return enabled.contains(id); }
        public boolean hasCapability(PrincipalId id, BackupCapability capability, BackupScope scope) { return granted.contains(capability); }
        public boolean canRead(PrincipalId id, BackupRepositoryKey repository) { return readable.contains(repository); }
        public boolean canReadVersion(PrincipalId id, BackupRepositoryKey repository, String commit) { return Objects.equals(versions.get(repository), commit); }
    }
}
