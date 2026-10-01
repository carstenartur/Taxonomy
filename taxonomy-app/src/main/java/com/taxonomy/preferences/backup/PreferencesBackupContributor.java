package com.taxonomy.preferences.backup;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Installation-owned current preferences; full history is supplied by the Git adapter. */
public final class PreferencesBackupContributor implements BackupDataContributor {
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final int MAX_GIT_METADATA_BYTES = 65_536;
    private static final String ENTRY = "data/application/preferences.json";
    private static final Set<String> KEYS = Set.of("llm.rpm", "llm.timeout.seconds", "rate-limit.per-minute",
            "analysis.min-relevance-score", "dsl.default-branch", "dsl.project-name", "dsl.auto-save.interval-seconds",
            "limits.max-business-text", "limits.max-architecture-nodes", "limits.max-export-nodes", "diagram.policy",
            "dsl.remote.url", "dsl.remote.token", "dsl.remote.push-on-commit");
    private static final List<String> EXCLUDED_FIELDS = List.of("dsl.remote.url", "dsl.remote.token");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static { JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(4).maxStringLength(MAX_DOCUMENT_BYTES).maxNumberLength(100).build()); }
    private final PreferencesGitRepository repository;

    public PreferencesBackupContributor(PreferencesGitRepository repository) { this.repository = Objects.requireNonNull(repository); }
    @Override public BackupComponentId componentId() { return new BackupComponentId("application"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("storage.git.preferences"); }
    @Override public List<String> omissions(BackupProfile profile) {
        if (!profile.isInstallation()) return List.of("preferences: installation-owned administrative settings are outside the selected workspace scope");
        return List.of("preferences: history, commit authors and messages are excluded from current state",
                "preferences: remote URLs and credentials are target-owned and excluded; outgoing execution requires manual review",
                "preferences: absent persisted values remain null and require source deployment defaults; no runtime defaults are invented");
    }

    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var profile = snapshot.authorization().request().profile();
        if (profile == BackupProfile.INSTALLATION_FULL) throw new IOException("Preferences repository history capture is required for INSTALLATION_FULL");
        if (!profile.isInstallation()) {
            PortableRows.document(sink, ENTRY, inventory(profile, Selection.OUTSIDE_SCOPE, null, null, null));
            return;
        }
        Inventory captured;
        try { captured = capture(profile, sink); }
        catch (InterruptedIOException cancelled) { throw new InterruptedIOException("Preferences capture interrupted"); }
        catch (IOException | RuntimeException invalid) {
            // Neither parser diagnostics nor provider errors may disclose raw source settings or secrets.
            throw new IOException("Stored preferences cannot be exported safely");
        }
        PortableRows.document(sink, ENTRY, captured);
    }

    private Inventory capture(BackupProfile profile, ComponentSink sink) throws IOException {
        sink.checkpoint();
        Repository git = repository.getGitRepository();
        Ref ref = git.getRefDatabase().exactRef("refs/heads/main");
        if (ref == null) return inventory(profile, Selection.UNINITIALIZED, null, null, null);
        if (ref.isSymbolic() || ref.getObjectId() == null) throw new IOException("Invalid preferences ref");
        ObjectId head = ref.getObjectId().copy();
        sink.checkpoint();
        if (git.open(head, Constants.OBJ_COMMIT).getSize() > MAX_GIT_METADATA_BYTES) throw new IOException("Preferences commit limit exceeded");
        try (var walk = new RevWalk(git); var tree = new TreeWalk(git)) {
            walk.setRetainBody(false);
            var commit = walk.parseCommit(head);
            if (git.open(commit.getTree(), Constants.OBJ_TREE).getSize() > MAX_GIT_METADATA_BYTES) throw new IOException("Preferences tree limit exceeded");
            tree.addTree(commit.getTree());
            if (!tree.next() || !tree.getPathString().equals("preferences.json") || !FileMode.REGULAR_FILE.equals(tree.getFileMode(0)))
                throw new IOException("Unsupported preferences tree");
            ObjectId blob = tree.getObjectId(0).copy();
            if (tree.next()) throw new IOException("Unclassified preferences entry");
            ObjectLoader loader = git.open(blob, Constants.OBJ_BLOB);
            if (loader.getSize() > MAX_DOCUMENT_BYTES) throw new IOException("Preferences document limit exceeded");
            var bytes = new ByteArrayOutputStream();
            try (var input = loader.openStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1;) {
                    sink.checkpoint();
                    if (read > MAX_DOCUMENT_BYTES - bytes.size()) throw new IOException("Preferences document limit exceeded");
                    bytes.write(buffer, 0, read);
                }
            }
            if (bytes.size() != loader.getSize()) throw new IOException("Truncated preferences document");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            JsonNode document = JSON.readTree(text);
            if (document == null || !document.isObject()) throw new IOException("Invalid preferences object");
            for (var names = document.fieldNames(); names.hasNext();) {
                if (!KEYS.contains(names.next())) throw new IOException("Unclassified preference key");
            }
            var settings = new Settings(integer(document, "llm.rpm"), integer(document, "llm.timeout.seconds"),
                    integer(document, "rate-limit.per-minute"), integer(document, "analysis.min-relevance-score"),
                    text(document, "dsl.default-branch"), text(document, "dsl.project-name"), integer(document, "dsl.auto-save.interval-seconds"),
                    integer(document, "limits.max-business-text"), integer(document, "limits.max-architecture-nodes"),
                    integer(document, "limits.max-export-nodes"), text(document, "diagram.policy"));
            var remote = new RemoteConfiguration(present(document, "dsl.remote.url"), present(document, "dsl.remote.token"),
                    flag(document, "dsl.remote.push-on-commit"));
            sink.checkpoint();
            return inventory(profile, Selection.CURRENT_STATE, head.name(), settings, remote);
        }
    }

    private static Integer integer(JsonNode document, String key) throws IOException {
        JsonNode value = document.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (value.isIntegralNumber() && value.canConvertToInt()) return value.intValue();
        if (value.isTextual()) return Integer.valueOf(value.textValue());
        throw new IOException("Invalid integer preference");
    }
    private static String text(JsonNode document, String key) throws IOException {
        JsonNode value = document.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().length() > 4096) throw new IOException("Invalid text preference");
        return value.textValue();
    }
    private static Boolean flag(JsonNode document, String key) throws IOException {
        JsonNode value = document.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isTextual() && (value.textValue().equalsIgnoreCase("true") || value.textValue().equalsIgnoreCase("false")))
            return Boolean.valueOf(value.textValue());
        throw new IOException("Invalid boolean preference");
    }
    private static boolean present(JsonNode document, String key) throws IOException {
        JsonNode value = document.path(key);
        if (value.isMissingNode() || value.isNull()) return false;
        if (!value.isTextual()) throw new IOException("Invalid remote preference");
        return !value.textValue().isBlank();
    }
    private static Inventory inventory(BackupProfile profile, Selection selection, String commit, Settings settings, RemoteConfiguration remote) {
        return new Inventory(1, profile, selection, commit, settings, remote, RestorePolicy.MANUAL_REVIEW_REQUIRED, EXCLUDED_FIELDS);
    }

    public enum Selection { CURRENT_STATE, UNINITIALIZED, OUTSIDE_SCOPE }
    public enum RestorePolicy { MANUAL_REVIEW_REQUIRED }
    public record Settings(Integer llmRequestsPerMinute, Integer llmTimeoutSeconds, Integer rateLimitPerMinute,
                           Integer analysisMinimumRelevanceScore, String defaultBranch, String projectName,
                           Integer autoSaveIntervalSeconds, Integer maximumBusinessText, Integer maximumArchitectureNodes,
                           Integer maximumExportNodes, String diagramPolicy) { }
    public record RemoteConfiguration(boolean configured, boolean credentialPresent, Boolean sourcePushOnCommit) { }
    public record Inventory(int schemaVersion, BackupProfile profile, Selection selection, String sourceCommit,
                            Settings settings, RemoteConfiguration remote, RestorePolicy restorePolicy, List<String> excludedFields) {
        public Inventory { excludedFields = List.copyOf(excludedFields); }
    }
}
