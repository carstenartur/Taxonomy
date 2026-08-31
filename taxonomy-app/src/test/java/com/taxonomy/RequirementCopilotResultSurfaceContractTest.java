package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementCopilotResultSurfaceContractTest {

    @Test
    void terminalRunShowsAccuratePhaseAndAddressableSelectedSnapshot() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-copilot.js"));

        assertThat(source)
                .contains("id=\"copilotResultActions\"")
                .contains("id=\"copilotSelectedSnapshot\"")
                .contains("id=\"copilotOpenResult\"")
                .contains("renderResultAction(operation);")
                .contains("resultUrl.searchParams.set('snapshot', snapshotId)")
                .contains("status === 'SUCCESS'")
                .contains("label: t('completedPhase')")
                .contains("status === 'PARTIAL'")
                .contains("label: t('partialPhase')")
                .contains("status === 'FAILED'")
                .contains("label: t('failedPhase')")
                .contains("status === 'CANCELLED'")
                .contains("label: t('cancelledPhase')")
                .doesNotContain(
                        "if (terminal.has(operation.status)) return { key: 'FINAL', "
                                + "label: t('finalizing')");
    }
}
