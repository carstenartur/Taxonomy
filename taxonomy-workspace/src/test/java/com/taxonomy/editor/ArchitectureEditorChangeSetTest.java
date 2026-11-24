package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch.BlockChange;
import com.taxonomy.editor.ArchitectureChangeSet.Allocation;
import com.taxonomy.editor.ArchitectureChangeSet.Relation;
import com.taxonomy.editor.ArchitectureChangeSet.Request;
import com.taxonomy.editor.ArchitectureCommandPort.Metadata;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArchitectureEditorChangeSetTest {
    private static final String SOURCE = """
            // Retain the original model and annotations.
            element arch-existing type System {
              title: "Original";
            }
            requirement REQ-1 {
              title: "Record decisions";
              text: "A retained requirement";
            }
            """;
    private final RepositoryContext context = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice");
    private EditorPersistenceFixture fixture;
    private DslGitRepository git;
    private String head;

    @BeforeEach void setUp() throws IOException {
        fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:change-set-" + UUID.randomUUID());
        git = fixture.repositories.resolveRepository(context);
        head = git.commitDsl("draft", SOURCE, "seed", "Initial architecture version");
    }

    @AfterEach void close() { fixture.close(); }

    @Test void reviewShowsAllEffectsAndPublishesOneAuditableCheckpointThroughTheEditorRoutes() throws Exception {
        var request = request("REQ-1");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(context);
        var controller = new ArchitectureEditorController(fixture.service,
                new ArchitectureEditorProjection(new LayeredDiagramLayoutService()), resolver);
        var mvc = standaloneSetup(controller).build();
        var json = new ObjectMapper();
        var endpoint = "/api/architecture/editor/change-sets";
        var etag = "\"workspace-revision-0\"";

        mvc.perform(post(endpoint + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().is(428));
        var preview = mvc.perform(post(endpoint + "/preview").header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.context.commit").value(head))
                .andExpect(jsonPath("$.changes.length()").value(3))
                .andExpect(jsonPath("$.changes[0].after").value(org.hamcrest.Matchers.containsString("Decision store")))
                .andExpect(jsonPath("$.changes[1].after").value(org.hamcrest.Matchers.containsString("arch-existing CONTAINS arch-new")))
                .andExpect(jsonPath("$.changes[2].after").value(org.hamcrest.Matchers.containsString("REQ-1 -> arch-new")))
                .andReturn().getResponse();
        assertThat(fixture.journal.read(context)).isNull();
        assertThat(git.getHeadCommit("draft")).isEqualTo(head);

        var acceptedRequest = withFingerprint(request, json.readTree(preview.getContentAsByteArray()).get("fingerprint").asText());
        var accepted = mvc.perform(post(endpoint + "/accept").header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(acceptedRequest)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"workspace-revision-1\""))
                .andExpect(jsonPath("$.commitCreated").value(true)).andExpect(jsonPath("$.throughRevision").value(1))
                .andReturn().getResponse();
        String commitId = json.readTree(accepted.getContentAsByteArray()).get("commitId").asText();
        assertThat(git.getCommitCount("draft")).isEqualTo(2);
        var document = fixture.service.read(context, null);
        assertThat(document.dsl()).startsWith(SOURCE).contains("element arch-new", "mapping REQ-1 -> arch-new");
        assertThat(document.dsl()).isEqualTo(git.getDslAtCommit(commitId));
        assertThat(document.history()).singleElement().satisfies(entry -> {
            assertThat(entry.commandId()).isEqualTo(request.metadata().commandId());
            assertThat(entry.actor()).isEqualTo("alice");
        });
        assertThat(fixture.journal.checkpoints(context)).singleElement().satisfies(checkpoint -> {
            assertThat(checkpoint.expectedCommit()).isEqualTo(head);
            assertThat(checkpoint.commitId()).isEqualTo(commitId);
        });
        try (var walk = new RevWalk(git.getGitRepository())) {
            var commit = walk.parseCommit(ObjectId.fromString(commitId));
            assertThat(commit.getParents()).extracting(ObjectId::name).containsExactly(head);
            assertThat(commit.getFullMessage()).contains(acceptedRequest.previewFingerprint(), "Reviewed decision store");
        }
        var restarted = new ArchitectureEditorService(fixture.repositories, fixture.journal, new ArchitectureCheckpointWriter());
        var replay = restarted.acceptChangeSet(context, acceptedRequest);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.commitId()).isEqualTo(commitId);
        assertThat(git.getCommitCount("draft")).isEqualTo(2);
    }

    @Test void invalidAllocationRollsBackEvenWhenTheElementAndRelationAreValid() throws Exception {
        var valid = request("REQ-1");
        var invalidAllocation = new Request(valid.context(), valid.metadata(), valid.element(), valid.relation(),
                new Allocation("missing-requirement", "arch-new"), fixture.service.previewChangeSet(context, valid).fingerprint());

        assertThatThrownBy(() -> fixture.service.previewChangeSet(context, invalidAllocation))
                .isInstanceOfSatisfying(CommandProblem.class, problem -> assertThat(problem.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> fixture.service.acceptChangeSet(context, invalidAllocation))
                .isInstanceOfSatisfying(CommandProblem.class, problem -> assertThat(problem.code()).isEqualTo("NOT_FOUND"));
        assertThat(fixture.journal.read(context)).isNull();
        assertThat(fixture.journal.checkpoints(context)).isEmpty();
        assertThat(git.getHeadCommit("draft")).isEqualTo(head);
        assertThat(git.getDslAtHead("draft")).isEqualTo(SOURCE);
    }

    @Test void versionMoveAfterPreviewIsAConflictEvenWithAnUnchangedSemanticRevision() throws Exception {
        var request = request("REQ-1");
        var reviewed = withFingerprint(request, fixture.service.previewChangeSet(context, request).fingerprint());
        String newer = git.commitDsl("draft", SOURCE + "// independently changed\n", "bob", "Concurrent edit");

        assertConflict(fixture.service, reviewed);
        assertThat(fixture.journal.read(context)).isNull();
        assertThat(git.getHeadCommit("draft")).isEqualTo(newer);
        assertThat(git.getCommitCount("draft")).isEqualTo(2);
    }

    @Test void branchCompareAndSetRollsBackTheJournalWhenAWriterWinsDuringPublication() throws Exception {
        var request = request("REQ-1");
        var reviewed = withFingerprint(request, fixture.service.previewChangeSet(context, request).fingerprint());
        var intercepted = spy(git);
        doAnswer(call -> {
            git.commitDsl("draft", SOURCE + "// concurrent publication\n", "bob", "Concurrent edit");
            return call.callRealMethod();
        }).when(intercepted).publishPreparedDslCommit(anyString(), eq("draft"), eq(head), anyString());

        assertConflict(withRepository(intercepted), reviewed);
        assertThat(fixture.journal.read(context)).isNull();
        assertThat(fixture.journal.checkpoints(context)).isEmpty();
        assertThat(git.getDslAtHead("draft")).endsWith("// concurrent publication\n");
        assertThat(git.getCommitCount("draft")).isEqualTo(2);
    }

    @Test void retryAfterPublicationInterruptionRecoversTheExactCommitWithoutDuplicatingIt() throws Exception {
        var request = request("REQ-1");
        var preview = fixture.service.previewChangeSet(context, request);
        assertThat(preview.changes()).extracting(BlockChange::id).containsExactly("element:arch-new",
                "relation:arch-existing CONTAINS arch-new", "mapping:REQ-1 -> arch-new");
        var reviewed = withFingerprint(request, preview.fingerprint());
        var intercepted = spy(git);
        var lostReply = new AtomicBoolean(true);
        doAnswer(call -> {
            Object commit = call.callRealMethod();
            if (lostReply.getAndSet(false)) throw new IOException("Connection interrupted after native publication");
            return commit;
        }).when(intercepted).publishPreparedDslCommit(anyString(), eq("draft"), eq(head), anyString());

        assertThatThrownBy(() -> withRepository(intercepted).acceptChangeSet(context, reviewed)).isInstanceOf(IOException.class);
        var published = git.getHeadCommit("draft");
        assertThat(published).isNotEqualTo(head);
        assertThat(fixture.journal.read(context)).isNull();
        var recovered = fixture.service.acceptChangeSet(context, reviewed);
        assertThat(recovered.commitId()).isEqualTo(published);
        assertThat(fixture.journal.checkpoints(context)).singleElement();
        assertThat(git.getCommitCount("draft")).isEqualTo(2);
    }

    private ArchitectureEditorService withRepository(DslGitRepository repository) {
        var repositories = mock(DslGitRepositoryFactory.class);
        when(repositories.resolveRepository(context)).thenReturn(repository);
        return new ArchitectureEditorService(repositories, fixture.journal, new ArchitectureCheckpointWriter());
    }

    private Request request(String requirementId) throws IOException {
        String id = UUID.randomUUID().toString();
        return new Request(fixture.service.read(context, null).context(), new Metadata(id, id, id, "Reviewed decision store"),
                new CreateArchitectureElement("arch-new", "Component", Map.of("title", "Decision store")),
                new Relation("arch-existing", "CONTAINS", "arch-new", Map.of()),
                new Allocation(requirementId, "arch-new"), null);
    }

    private static Request withFingerprint(Request request, String fingerprint) {
        return new Request(request.context(), request.metadata(), request.element(), request.relation(), request.allocation(), fingerprint);
    }

    private void assertConflict(ArchitectureEditorService service, Request reviewed) {
        assertThatThrownBy(() -> service.acceptChangeSet(context, reviewed))
                .isInstanceOfSatisfying(CommandProblem.class, problem -> assertThat(problem.code()).isEqualTo("CHECKPOINT_CONFLICT"));
    }
}
