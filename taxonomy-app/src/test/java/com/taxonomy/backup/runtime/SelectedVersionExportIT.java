package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.analysis.backup.AnalysisBackupContributor;
import com.taxonomy.workspace.backup.WorkspaceBackupContributor;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectedVersionExportIT {
    @Test void historicalSelectionCannotPickUpTodaysWorkingCopyDraftOrAuditPayload() throws Exception {
        try (var fixture = new CurrentStateExportIT.Fixture()) {
            fixture.editor("a", "repo-a", "private-a", "TODAYS-WORKING-COPY");
            fixture.operation("a", "EARLIER-ANCESTOR-SECRET");
            fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"TODAYS-DRAFT\"}");
            fixture.requirement("repo-a", "private-a", "OLDER-VERSION", "TODAYS-REQUIREMENT");
            var snapshot = CurrentStateExportIT.snapshot(BackupProfile.SELECTED_VERSION,
                    new BackupScope.Workspace("repo-a", "private-a"));
            var output = new CurrentStateExportIT.Contents();
            new WorkspaceBackupContributor(fixture.database, java.util.function.UnaryOperator.<String>identity()::apply).write(snapshot, output);
            new AnalysisBackupContributor(fixture.database, "alice").write(snapshot, output);
            new PortfolioBackupContributor(fixture.database).write(snapshot, output);
            assertThat(output.text()).doesNotContain("TODAYS-WORKING-COPY", "TODAYS-DRAFT", "EARLIER-ANCESTOR-SECRET", "OLDER-VERSION", "TODAYS-REQUIREMENT");
        }
    }
}
