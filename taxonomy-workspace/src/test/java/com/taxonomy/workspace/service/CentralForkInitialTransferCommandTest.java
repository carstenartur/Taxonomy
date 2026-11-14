package com.taxonomy.workspace.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CentralForkInitialTransferCommandTest {
    enum IncompatibleCommand {
        OTHER_OPERATION, OTHER_COMMIT, PRIOR_TRACKING_HEAD, SELECTED_TAG, OTHER_SOURCE_BRANCH
    }

    @ParameterizedTest
    @EnumSource(IncompatibleCommand.class)
    void incompatiblePersistedCommandCannotEnterTheInitialForkPublisher(IncompatibleCommand command) {
        var row = new CentralFetchOperation();
        row.targetRepositoryId = "retained-target";
        row.id = CentralFetchOperation.initialId(row.targetRepositoryId);
        row.requested = row.forkPoint = "a".repeat(40);
        row.branch = row.sourceBranch = "main";
        switch (command) {
            case OTHER_OPERATION -> row.id = CentralFetchOperation.initialId("other-target");
            case OTHER_COMMIT -> row.requested = "b".repeat(40);
            case PRIOR_TRACKING_HEAD -> row.expected = row.forkPoint;
            case SELECTED_TAG -> row.selectedTag = "release";
            case OTHER_SOURCE_BRANCH -> row.sourceBranch = "other-branch";
        }

        var publisher = new CentralForkInitialTransfer(null, null, null, null, null);
        assertThrows(AccessDeniedException.class, () -> publisher.apply(row, () -> {
            throw new AssertionError("An incompatible command reached the publication callback");
        }));
    }
}
