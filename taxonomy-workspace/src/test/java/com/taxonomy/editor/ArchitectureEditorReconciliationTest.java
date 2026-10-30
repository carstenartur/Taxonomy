package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact retained sources, explicit conflict choices and the real transactional journal/native Git boundary. */
class ArchitectureEditorReconciliationTest extends ReconciliationTestSupport {
    @Test
    void independentChangesPreviewWithoutWritingAndKeepUnknownSourceAndAnnotations() throws Exception {
        String opaque = "opaque source-evidence {\n  x-note: \"Uninterpreted payload\"; # keep this comment\n}\n";
        seed(BASE + opaque);
        edit("first", "Local first");
        String local = source();
        String incoming = titled(BASE + opaque, "second", "Incoming second").replace("# Original document annotation", "# Incoming document annotation");
        advance(incoming);
        Metadata metadata = metadata("Preview independent sources");
        var before = capture();
        var first = preview(metadata, Map.of());
        var repeated = preview(metadata, Map.of());
        assertThat(first).isEqualTo(repeated);
        assertThat(first.canAccept()).isTrue();
        assertThat(first.conflicts()).isEmpty();
        assertThat(first.localDsl()).isEqualTo(local);
        assertThat(first.incomingDsl()).isEqualTo(incoming);
        assertThat(first.mergedDsl()).contains("Local first", "Incoming second", opaque).startsWith("# Incoming document annotation\n");
        unchanged(before);
    }

    @Test
    void acceptancePublishesTheExactPreviewWithIncomingParentAndRetriesWithoutAnotherOperation() throws Exception {
        edit("first", "Local first");
        String incomingHead = advance(titled(BASE, "second", "Incoming second"));
        Metadata metadata = metadata("Accept independent sources");
        var preview = preview(metadata, Map.of());
        var request = acceptedRequest(preview, metadata, Map.of());
        var result = service.acceptReconciliation(context, request);
        assertThat(result.replayed()).isFalse();
        assertThat(fixture.repositories.resolveRepository(context).getDslAtCommit(result.commitId())).isEqualTo(preview.mergedDsl());
        assertThat(source()).isEqualTo(preview.mergedDsl());
        assertThat(fixture.journal.read(context).operations()).hasSize(2);
        assertThat(checkpointIds()).hasSize(1);
        try (var walk = new RevWalk(fixture.repositories.resolveRepository(context).getGitRepository())) {
            var commit = walk.parseCommit(ObjectId.fromString(result.commitId()));
            assertThat(commit.getParentCount()).isEqualTo(1);
            assertThat(commit.getParent(0).name()).isEqualTo(incomingHead);
        }
        var beforeRetry = capture();
        var replay = service.acceptReconciliation(context, request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.commitId()).isEqualTo(result.commitId());
        unchanged(beforeRetry);
        var changedIntent = new ReconciliationRequest(request.context(), request.metadata(), Map.of("element:first", Resolution.INCOMING), request.headCommit(), request.previewFingerprint());
        rejected("COMMAND_ID_REUSED", () -> service.acceptReconciliation(context, changedIntent));
        unchanged(beforeRetry);
    }

    @Test
    void aSharedBlockExposesBothExactSourcesAndRequiresAnExplicitChoice() throws Exception {
        edit("first", "Local source value");
        String local = source();
        String incoming = titled(BASE, "first", "Incoming source value");
        advance(incoming);
        Metadata metadata = metadata("Choose exact first block source");
        var unresolved = preview(metadata, Map.of());
        assertThat(unresolved.canAccept()).isFalse();
        assertThat(unresolved.mergedDsl()).isNull();
        assertThat(unresolved.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.id()).isEqualTo("element:first");
            assertThat(conflict.base()).isEqualTo(FIRST);
            assertThat(conflict.local()).contains("Local source value", "x-foreign");
            assertThat(conflict.incoming()).contains("Incoming source value", "x-foreign");
            assertThat(conflict.resolution()).isNull();
        });
        var selectedLocal = preview(metadata, Map.of("element:first", Resolution.LOCAL));
        var selectedIncoming = preview(metadata, Map.of("element:first", Resolution.INCOMING));
        assertThat(selectedLocal.mergedDsl()).isEqualTo(local);
        assertThat(selectedIncoming.mergedDsl()).isEqualTo(incoming);
        assertThat(selectedLocal.fingerprint()).isNotEqualTo(selectedIncoming.fingerprint());
        service.acceptReconciliation(context, acceptedRequest(selectedLocal, metadata, Map.of("element:first", Resolution.LOCAL)));
        assertThat(source()).isEqualTo(local);
    }

    @Test
    void changedChoicesCannotReuseThePreviouslyDisplayedFingerprint() throws Exception {
        edit("first", "Local first");
        advance(titled(BASE, "first", "Incoming first"));
        Metadata metadata = metadata("Bind the selected choice");
        var chosen = preview(metadata, Map.of("element:first", Resolution.LOCAL));
        var before = capture();
        rejected("PREVIEW_CHANGED", () -> service.acceptReconciliation(context,
                acceptedRequest(chosen, metadata, Map.of("element:first", Resolution.INCOMING))));
        unchanged(before);
    }

    @Test
    void anIncompleteConflictSelectionCannotAppendAnOperationOrCheckpoint() throws Exception {
        edit("first", "Local first"); edit("second", "Local second");
        advance(titled(titled(BASE, "first", "Incoming first"), "second", "Incoming second"));
        Metadata metadata = metadata("Incomplete conflict choice");
        Map<String, Resolution> incomplete = Map.of("element:first", Resolution.LOCAL);
        var preview = preview(metadata, incomplete);
        assertThat(preview.conflicts()).hasSize(2);
        assertThat(preview.canAccept()).isFalse();
        var before = capture();
        rejected("RECONCILIATION_CONFLICT", () -> service.acceptReconciliation(context, acceptedRequest(preview, metadata, incomplete)));
        rejected("RECONCILIATION_CONFLICT", () -> preview(metadata, Map.of("not-a-preview-conflict", Resolution.LOCAL)));
        unchanged(before);
    }

    @Test
    void anotherAcceptedReconciliationInvalidatesTheOldSemanticRevision() throws Exception {
        edit("first", "Local first"); advance(titled(BASE, "second", "Incoming second"));
        Metadata oldMetadata = metadata("Older proposed reconciliation");
        var old = preview(oldMetadata, Map.of());
        Metadata newerMetadata = metadata("Another accepted reconciliation");
        var newer = preview(newerMetadata, Map.of());
        service.acceptReconciliation(context, acceptedRequest(newer, newerMetadata, Map.of()));
        var before = capture();
        assertThatThrownBy(() -> service.acceptReconciliation(context, acceptedRequest(old, oldMetadata, Map.of())))
                .isInstanceOf(EditorJournal.RevisionConflict.class);
        unchanged(before);
    }

    @Test
    void movingGitAfterPreviewRejectsAcceptanceAndRetainsBothSources() throws Exception {
        edit("first", "Local first");
        String incoming = titled(BASE, "second", "Incoming second"); advance(incoming);
        Metadata metadata = metadata("Pin incoming Git HEAD");
        var preview = preview(metadata, Map.of());
        advance(titled(incoming, "third", "Newer incoming third"));
        var before = capture();
        rejected("PREVIEW_CHANGED", () -> service.acceptReconciliation(context, acceptedRequest(preview, metadata, Map.of())));
        unchanged(before);
        assertThat(source()).contains("Local first").doesNotContain("Newer incoming third");
    }

    @Test
    void contextPinsAndReadOnlyScopesAreRejectedBeforeAnyReconciliationWrite() throws Exception {
        edit("first", "Local first"); advance(titled(BASE, "second", "Incoming second"));
        Metadata metadata = metadata("Exact authorized context");
        var preview = preview(metadata, Map.of());
        Context exact = preview.context();
        var before = capture();
        for (Context forged : List.of(
                new Context("other-repository", exact.workspaceScopeKey(), exact.branch(), exact.commit(), exact.revision(), exact.actor(), exact.writeMode()),
                new Context(exact.repositoryId(), "other-workspace", exact.branch(), exact.commit(), exact.revision(), exact.actor(), exact.writeMode()),
                new Context(exact.repositoryId(), exact.workspaceScopeKey(), "other-branch", exact.commit(), exact.revision(), exact.actor(), exact.writeMode()),
                new Context(exact.repositoryId(), exact.workspaceScopeKey(), exact.branch(), exact.commit(), exact.revision(), "bob", exact.writeMode()),
                new Context(exact.repositoryId(), exact.workspaceScopeKey(), exact.branch(), exact.commit(), exact.revision(), exact.actor(), "READ_ONLY"))) {
            var request = new ReconciliationRequest(forged, metadata, Map.of(), preview.headCommit(), preview.fingerprint());
            rejected("CONTEXT_CHANGED", () -> service.previewReconciliation(context, request));
            rejected("CONTEXT_CHANGED", () -> service.acceptReconciliation(context, request));
        }
        var central = RepositoryContext.centralRead(context.repositoryId(), "draft", "alice");
        rejected("READ_ONLY", () -> service.previewReconciliation(central,
                new ReconciliationRequest(Context.version(central, exact.commit()), metadata, Map.of(), null, null)));
        unchanged(before);
    }

    @Test
    void independentlyValidDeletionAndNewRelationBecomeStableExplicitDependencyConflicts() throws Exception {
        localSource(BASE.replace(FIRST, ""));
        String relation = "relation first DEPENDS_ON second {\n  status: accepted;\n}\n";
        advance(BASE + relation);
        Metadata metadata = metadata("Resolve the relation dependency");
        var unresolved = preview(metadata, Map.of());
        Set<String> ids = Set.of("element:first", "relation:first DEPENDS_ON second");
        assertThat(unresolved.canAccept()).isFalse();
        assertThat(unresolved.conflicts()).extracting(ReconciliationConflict::id).containsExactlyInAnyOrderElementsOf(ids);
        var local = preview(metadata, choices(unresolved, Resolution.LOCAL));
        var incoming = preview(metadata, choices(unresolved, Resolution.INCOMING));
        assertThat(local.conflicts()).extracting(ReconciliationConflict::id).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(incoming.conflicts()).extracting(ReconciliationConflict::id).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(local.mergedDsl()).doesNotContain("element first", "relation first");
        assertThat(incoming.mergedDsl()).contains("element first", relation);
        Map<String, Resolution> invalid = choices(unresolved, Resolution.LOCAL);
        invalid.put("relation:first DEPENDS_ON second", Resolution.INCOMING);
        var before = capture();
        rejected("RECONCILIATION_CONFLICT", () -> preview(metadata, invalid));
        unchanged(before);
        service.acceptReconciliation(context, acceptedRequest(incoming, metadata, choices(unresolved, Resolution.INCOMING)));
        new ArchitectureDslCommands().validateReconciledSource(source());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void separatelyValidContainmentChangesCannotCreateACycleOrMultipleParents(boolean multipleParents) throws Exception {
        String localRelation = multipleParents ? "relation first CONTAINS third" : "relation first CONTAINS second";
        String incomingRelation = multipleParents ? "relation second CONTAINS third" : "relation second CONTAINS first";
        localSource(BASE + localRelation + " {\n  status: accepted;\n}\n");
        advance(BASE + incomingRelation + " {\n  status: accepted;\n}\n");
        Metadata metadata = metadata("Choose compatible containment");
        var unresolved = preview(metadata, Map.of());
        assertThat(unresolved.canAccept()).isFalse();
        assertThat(unresolved.conflicts()).hasSize(2);
        var completeLocal = preview(metadata, choices(unresolved, Resolution.LOCAL));
        var completeIncoming = preview(metadata, choices(unresolved, Resolution.INCOMING));
        assertThat(completeLocal.canAccept()).isTrue(); assertThat(completeIncoming.canAccept()).isTrue();
        Map<String, Resolution> invalid = new LinkedHashMap<>();
        invalid.put(localRelation.replaceFirst("relation ", "relation:"), Resolution.LOCAL);
        invalid.put(incomingRelation.replaceFirst("relation ", "relation:"), Resolution.INCOMING);
        var before = capture();
        rejected("RECONCILIATION_CONFLICT", () -> preview(metadata, invalid));
        unchanged(before);
        assertThat(completeLocal.conflicts()).extracting(ReconciliationConflict::id)
                .containsExactlyInAnyOrderElementsOf(invalid.keySet());
    }

    @Test
    void aNewViewCannotSilentlyRetainAnElementDeletedByTheOtherSource() throws Exception {
        localSource(BASE.replace(FIRST, ""));
        advance(BASE + "view incoming-view {\n  title: \"Incoming view\";\n  include: first;\n}\n");
        Metadata metadata = metadata("Resolve the view membership");
        var preview = preview(metadata, Map.of());
        assertThat(preview.canAccept()).isFalse();
        assertThat(preview.conflicts()).extracting(ReconciliationConflict::id).containsExactlyInAnyOrder("element:first", "view:incoming-view");
        var selected = preview(metadata, choices(preview, Resolution.LOCAL));
        assertThat(selected.canAccept()).isTrue();
        assertThat(selected.mergedDsl()).doesNotContain("element first", "view incoming-view");
        new ArchitectureDslCommands().validateReconciledSource(selected.mergedDsl());
    }

    @Test
    void conflictingDocumentAnnotationsAreAnExplicitChoiceWithTheFullRetainedSurfaces() throws Exception {
        String local = BASE.replace("Original document annotation", "Local document annotation");
        String incoming = BASE.replace("Original document annotation", "Incoming document annotation");
        localSource(local); advance(incoming);
        Metadata metadata = metadata("Choose original annotation source");
        var preview = preview(metadata, Map.of());
        assertThat(preview.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.id()).isEqualTo("$document");
            assertThat(conflict.base()).contains("Original document annotation");
            assertThat(conflict.local()).contains("Local document annotation");
            assertThat(conflict.incoming()).contains("Incoming document annotation");
        });
        var selected = preview(metadata, Map.of("$document", Resolution.LOCAL));
        assertThat(selected.mergedDsl()).isEqualTo(local);
        service.acceptReconciliation(context, acceptedRequest(selected, metadata, Map.of("$document", Resolution.LOCAL)));
        assertThat(source()).isEqualTo(local);
    }

    @Test
    void theDocumentConflictIdentityCannotCollideWithAnUnknownNamedBlock() throws Exception {
        String baseline = BASE + "opaque $document {\n  title: \"Opaque baseline\";\n}\n";
        seed(baseline);
        localSource(baseline.replace("Original document annotation", "Local document annotation").replace("Opaque baseline", "Opaque local"));
        advance(baseline.replace("Original document annotation", "Incoming document annotation").replace("Opaque baseline", "Opaque incoming"));
        Metadata metadata = metadata("Keep distinct annotation and object choices");
        var preview = preview(metadata, Map.of());
        assertThat(preview.conflicts()).extracting(ReconciliationConflict::id).containsExactlyInAnyOrder("$document", "opaque:$document");
        Map<String, Resolution> choices = Map.of("$document", Resolution.INCOMING, "opaque:$document", Resolution.LOCAL);
        var selected = preview(metadata, choices);
        assertThat(selected.mergedDsl()).startsWith("# Incoming document annotation\n").contains("Opaque local").doesNotContain("Opaque incoming");
        service.acceptReconciliation(context, acceptedRequest(selected, metadata, choices));
        assertThat(ArchitectureSemanticPatch.index(source())).containsKey("opaque:$document");
    }

    @Test
    void aSelectedEofBlockKeepsItsMeaningWhenItBecomesAMiddleBlock() throws Exception {
        seed(FIRST.stripTrailing());
        localSource(titled(FIRST, "first", "Local EOF block").stripTrailing());
        advance(FIRST + SECOND);
        Metadata metadata = metadata("Keep a block separator at EOF");
        var preview = preview(metadata, Map.of());
        assertThat(preview.conflicts()).extracting(ReconciliationConflict::id).containsExactly("element:first");
        Map<String, Resolution> choices = Map.of("element:first", Resolution.LOCAL);
        var selected = preview(metadata, choices);
        assertThat(selected.canAccept()).isTrue();
        assertThat(ArchitectureSemanticPatch.index(selected.mergedDsl())).containsOnlyKeys("element:first", "element:second");
        assertThat(selected.mergedDsl()).contains("Local EOF block", "}\nelement second");
        service.acceptReconciliation(context, acceptedRequest(selected, metadata, choices));
        assertThat(service.integrationCheckpointForOperation(context, fixture.journal.read(context).operations().getLast().commandId())).isPresent();
    }

    @Test
    void publicationFailureKeepsOneReconciliationIntentAndExactRetryFinishesIt() throws Exception {
        edit("first", "Local first"); String incomingHead = advance(titled(BASE, "second", "Incoming second"));
        AtomicBoolean fail = new AtomicBoolean(true);
        service = new ArchitectureEditorService(fixture.repositories, fixture.journal, new ArchitectureCheckpointWriter() {
            @Override public Result write(DslGitRepository repository, String scope, String branch, EditorJournal.Checkpoint request) throws IOException {
                if (fail.compareAndSet(true, false)) throw new IOException("Deterministic checkpoint outage");
                return super.write(repository, scope, branch, request);
            }
        });
        Metadata metadata = metadata("Retry the retained source reconciliation");
        var preview = preview(metadata, Map.of());
        var request = acceptedRequest(preview, metadata, Map.of());
        assertThatThrownBy(() -> service.acceptReconciliation(context, request)).isInstanceOf(IOException.class);
        assertThat(head()).isEqualTo(incomingHead);
        assertThat(source()).isEqualTo(preview.mergedDsl());
        assertThat(fixture.journal.read(context).state().pendingCheckpoint()).isNotNull();
        assertThat(fixture.journal.read(context).operations()).hasSize(2);
        assertThat(checkpointIds()).hasSize(1);
        var published = service.acceptReconciliation(context, request);
        assertThat(published.replayed()).isTrue();
        assertThat(fixture.journal.read(context).state().pendingCheckpoint()).isNull();
        assertThat(fixture.journal.read(context).operations()).hasSize(2);
        assertThat(checkpointIds()).hasSize(1);
        assertThat(fixture.repositories.resolveRepository(context).getCommitCount("draft")).isEqualTo(3);
    }

    @Test
    void aGitRaceDuringPublicationCannotBeOverwrittenOrRetriedAsIfItHadSucceeded() throws Exception {
        edit("first", "Local first"); advance(titled(BASE, "second", "Incoming second"));
        AtomicBoolean race = new AtomicBoolean(true);
        service = new ArchitectureEditorService(fixture.repositories, fixture.journal, new ArchitectureCheckpointWriter() {
            @Override public Result write(DslGitRepository repository, String scope, String branch, EditorJournal.Checkpoint request) throws IOException {
                if (race.compareAndSet(true, false)) repository.commitDsl(branch,
                        titled(repository.getDslAtHead(branch), "third", "Racing incoming third"), "third-author", "Concurrent publication");
                return super.write(repository, scope, branch, request);
            }
        });
        Metadata metadata = metadata("Race the final publication boundary");
        var preview = preview(metadata, Map.of());
        var request = acceptedRequest(preview, metadata, Map.of());
        rejected("CHECKPOINT_CONFLICT", () -> service.acceptReconciliation(context, request));
        assertThat(source()).isEqualTo(preview.mergedDsl());
        assertThat(fixture.repositories.resolveRepository(context).getDslAtHead("draft")).contains("Racing incoming third").doesNotContain("Local first");
        assertThat(fixture.journal.read(context).state().pendingCheckpoint()).isNull();
        var afterRace = capture();
        rejected("CHECKPOINT_CONFLICT", () -> service.acceptReconciliation(context, request));
        unchanged(afterRace);
        Metadata recoveryMetadata = metadata("Review the newly moved version");
        var recovery = preview(recoveryMetadata, Map.of());
        assertThat(recovery.canAccept()).isTrue();
        assertThat(recovery.mergedDsl()).contains("Local first", "Racing incoming third");
    }

    @Test
    void checkpointEvidenceForTheOriginalOperationRequiresItsRetainedEffectAndActualGitState() throws Exception {
        var operation = edit("first", "Retained local decision");
        advance(titled(BASE, "second", "Incoming second"));
        Metadata metadata = metadata("Prove the retained local decision");
        var preview = preview(metadata, Map.of());
        var accepted = service.acceptReconciliation(context, acceptedRequest(preview, metadata, Map.of()));
        assertThat(service.integrationCheckpointForOperation(context, operation.operationId())).get()
                .satisfies(proof -> assertThat(proof.commitId()).isEqualTo(accepted.commitId()));
        edit("third", "Unrelated later decision");
        service.checkpoint(context, new CreateCheckpointCommand(retained(), metadata("Checkpoint unrelated decision")));
        assertThat(service.integrationCheckpointForOperation(context, operation.operationId())).isPresent();
        edit("first", "Replacement local decision");
        service.checkpoint(context, new CreateCheckpointCommand(retained(), metadata("Checkpoint replacement decision")));
        assertThat(service.integrationCheckpointForOperation(context, operation.operationId())).isEmpty();
    }

    @Test
    void reconciliationRequiresAnActuallyMovedGitHeadAndDoesNotCreateAnEmptyJournal() throws Exception {
        var before = capture();
        rejected("NO_RECONCILIATION", () -> preview(metadata("No Git movement"), Map.of()));
        unchanged(before);
        assertThat(fixture.journal.read(context)).isNull();
        edit("first", "Only a local edit");
        var dirty = capture();
        rejected("NO_RECONCILIATION", () -> preview(metadata("Still no Git movement"), Map.of()));
        unchanged(dirty);
    }
}
