package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import com.taxonomy.backup.runtime.BackupFeaturePrerequisites;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import javax.sql.DataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionHistoryAdmissionTest {
    @ParameterizedTest @EnumSource(value=GitRepresentation.class, names={"BUNDLE","BARE","WORKTREE"})
    void originalHistoryLayoutsReachTheExistingCapturePrerequisite(GitRepresentation representation) {
        var prerequisites=mock(BackupFeaturePrerequisites.class);
        var source=source(prerequisites);
        var request=new BackupRequest(BackupProfile.REPOSITORY_HISTORY,
                new BackupScope.Workspace("source-repository","source-workspace"),
                new BackupTime.History(),representation,SecretsSelection.EXCLUDE);
        assertThatCode(()->source.validateRequest(request)).doesNotThrowAnyException();
        verify(prerequisites).requireCapture();
    }

    @Test void repositoryHistoryCannotSkipMissingDataOwnerPrerequisites() {
        var prerequisites=mock(BackupFeaturePrerequisites.class);
        doThrow(new IllegalStateException("Required backup data features are unavailable"))
                .when(prerequisites).requireCapture();
        var source=source(prerequisites);
        var request=new BackupRequest(BackupProfile.REPOSITORY_HISTORY,
                new BackupScope.Workspace("source-repository","source-workspace"),
                new BackupTime.History(),GitRepresentation.BARE,SecretsSelection.EXCLUDE);
        assertThatThrownBy(()->source.validateRequest(request)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Required backup data features are unavailable");
    }

    @ParameterizedTest @EnumSource(value=GitRepresentation.class, names={"BARE","WORKTREE"})
    void currentNativeLayoutsStillRequireTheirSeparateImplementedOwner(GitRepresentation representation) {
        var prerequisites=mock(BackupFeaturePrerequisites.class);
        var source=source(prerequisites);
        var request=new BackupRequest(BackupProfile.CURRENT_STATE,
                new BackupScope.Workspace("source-repository","source-workspace"),
                new BackupTime.Current(),representation,SecretsSelection.EXCLUDE);
        assertThatThrownBy(()->source.validateRequest(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(prerequisites);
    }

    @Test void fullInstallationNeedsItsActualAncillaryAndPrivateHistoryComposition() {
        var prerequisites=mock(BackupFeaturePrerequisites.class);
        var source=source(prerequisites);
        var request=new BackupRequest(BackupProfile.INSTALLATION_FULL,new BackupScope.Installation(),
                new BackupTime.History(),GitRepresentation.BARE,SecretsSelection.EXCLUDE);
        assertThatThrownBy(()->source.validateRequest(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(prerequisites);
    }

    private static ProductionCurrentBackupSource source(BackupFeaturePrerequisites prerequisites) {
        return new ProductionCurrentBackupSource(mock(DataSource.class),null,null,null,null,null,
                null,null,null,prerequisites,List.of(),"fixture-build");
    }
}
