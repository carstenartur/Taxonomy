package com.taxonomy.relations.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds one complete branch relation projection from authoritative TaxDSL.
 *
 * <p>Repository resolution, branch-head capture, DSL parsing and semantic
 * validation happen before the transactional writer replaces any database rows.
 * The branch head is verified again immediately before the writer is invoked.
 * A later read boundary still compares the persisted checkpoint with the live
 * branch head, so a commit that arrives after the rebuild cannot expose stale
 * rows as current.</p>
 */
@Service
public class RelationBranchProjectionRebuildService {

    private static final String RELATION_KIND = "relation";

    private final DslGitRepositoryFactory gitRepositoryFactory;
    private final RelationBranchProjectionRebuildWriter rebuildWriter;
    private final TaxDslParser parser;
    private final ExpectedHeadDslCommitter expectedHeadVerifier;

    @Autowired
    public RelationBranchProjectionRebuildService(
            DslGitRepositoryFactory gitRepositoryFactory,
            RelationBranchProjectionRebuildWriter rebuildWriter) {
        this(
                gitRepositoryFactory,
                rebuildWriter,
                new TaxDslParser(),
                new ExpectedHeadDslCommitter());
    }

    RelationBranchProjectionRebuildService(
            DslGitRepositoryFactory gitRepositoryFactory,
            RelationBranchProjectionRebuildWriter rebuildWriter,
            TaxDslParser parser,
            ExpectedHeadDslCommitter expectedHeadVerifier) {
        this.gitRepositoryFactory = Objects.requireNonNull(
                gitRepositoryFactory, "gitRepositoryFactory");
        this.rebuildWriter = Objects.requireNonNull(
                rebuildWriter, "rebuildWriter");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.expectedHeadVerifier = Objects.requireNonNull(
                expectedHeadVerifier, "expectedHeadVerifier");
    }

    /** Replaces the exact branch projection and writes its completion checkpoint atomically. */
    public RebuildResult rebuild(RepositoryContext context) {
        Objects.requireNonNull(context, "context");
        requireMutable(context);
        DslGitRepository repository = gitRepositoryFactory.resolveRepository(context);
        String head = readHead(repository, context.branch());
        String dsl = readDsl(repository, head);
        List<RelationSnapshot> relations = parseRelations(dsl, head);
        verifyHead(repository, context.branch(), head);
        return rebuildWriter.replace(context, head, relations);
    }

    private String readHead(DslGitRepository repository, String branch) {
        try {
            Ref ref = repository.getGitRepository().getRefDatabase()
                    .exactRef(Constants.R_HEADS + branch);
            if (ref == null || ref.getObjectId() == null) {
                throw new BranchProjectionSourceException(
                        "Cannot rebuild relation projection: branch '"
                                + branch + "' does not exist");
            }
            return ref.getObjectId().name();
        } catch (IOException error) {
            throw new BranchProjectionSourceException(
                    "Unable to read relation projection branch head '"
                            + branch + "'",
                    error);
        }
    }

    private String readDsl(DslGitRepository repository, String commitId) {
        try {
            String dsl = repository.getDslAtCommit(commitId);
            if (dsl == null) {
                throw new BranchProjectionSourceException(
                        "Authoritative commit has no architecture.taxdsl: "
                                + commitId);
            }
            return dsl;
        } catch (IOException error) {
            throw new BranchProjectionSourceException(
                    "Unable to read authoritative relation projection commit "
                            + commitId,
                    error);
        }
    }

    private void verifyHead(
            DslGitRepository repository,
            String branch,
            String expectedHead) {
        try {
            String verified = expectedHeadVerifier.verifyExpectedHead(
                    repository, branch, expectedHead);
            if (!expectedHead.equals(verified)) {
                throw new BranchProjectionSourceException(
                        "Selected branch no longer has the captured projection head");
            }
        } catch (IOException error) {
            throw new BranchProjectionSourceException(
                    "Selected branch moved during relation projection rebuild",
                    error);
        }
    }

    private List<RelationSnapshot> parseRelations(String dsl, String commitId) {
        DocumentAst document;
        try {
            document = parser.parse(dsl, "architecture.taxdsl");
        } catch (RuntimeException error) {
            throw new BranchProjectionSourceException(
                    "Cannot rebuild relation projection from invalid TaxDSL commit "
                            + commitId,
                    error);
        }

        Map<RelationIdentity, RelationSnapshot> relations = new LinkedHashMap<>();
        for (BlockAst block : document.getBlocks()) {
            if (!RELATION_KIND.equals(block.getKind())) {
                continue;
            }
            List<String> tokens = block.getHeaderTokens();
            if (tokens.size() != 3) {
                throw new BranchProjectionSourceException(
                        "Relation block must contain exactly source, type and target at commit "
                                + commitId);
            }
            RelationType relationType;
            try {
                relationType = RelationType.valueOf(
                        tokens.get(1).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new BranchProjectionSourceException(
                        "Unsupported relation type '" + tokens.get(1)
                                + "' at commit " + commitId,
                        error);
            }
            RelationIdentity identity = new RelationIdentity(
                    requireToken(tokens.get(0), "source code"),
                    relationType,
                    requireToken(tokens.get(2), "target code"));
            RelationSnapshot snapshot = new RelationSnapshot(
                    identity.sourceCode(),
                    identity.relationType(),
                    identity.targetCode(),
                    uniqueProperty(block, "status", identity),
                    confidence(block, identity),
                    uniqueProperty(block, "provenance", identity));
            if (relations.putIfAbsent(identity, snapshot) != null) {
                throw new BranchProjectionSourceException(
                        "Duplicate relation in authoritative branch projection: "
                                + identity.display());
            }
        }
        return List.copyOf(relations.values());
    }

    private static String uniqueProperty(
            BlockAst block,
            String property,
            RelationIdentity identity) {
        List<String> values = block.propertyValues(property);
        if (values.size() > 1) {
            throw new BranchProjectionSourceException(
                    "Duplicate " + property + " property on relation "
                            + identity.display());
        }
        return values.isEmpty() ? null : normalizeOptional(values.getFirst());
    }

    private static Double confidence(
            BlockAst block,
            RelationIdentity identity) {
        String value = uniqueProperty(block, "confidence", identity);
        if (value == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0.0 || parsed > 1.0) {
                throw new NumberFormatException("outside [0,1]");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new BranchProjectionSourceException(
                    "Invalid confidence on relation " + identity.display(),
                    error);
        }
    }

    private static void requireMutable(RepositoryContext context) {
        if (context.scope() == RepositoryScope.CENTRAL_READ) {
            throw new BranchProjectionContextException(
                    "Relation projection rebuild requires a writable repository context");
        }
    }

    private static String requireToken(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BranchProjectionSourceException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new BranchProjectionSourceException(
                    field + " must be one TaxDSL token: " + normalized);
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record RelationSnapshot(
            String sourceCode,
            RelationType relationType,
            String targetCode,
            String status,
            Double confidence,
            String provenance) {
        public RelationSnapshot {
            sourceCode = requireToken(sourceCode, "sourceCode");
            relationType = Objects.requireNonNull(relationType, "relationType");
            targetCode = requireToken(targetCode, "targetCode");
            status = normalizeOptional(status);
            provenance = normalizeOptional(provenance);
            if (confidence != null
                    && (!Double.isFinite(confidence)
                    || confidence < 0.0
                    || confidence > 1.0)) {
                throw new IllegalArgumentException(
                        "confidence must be finite and between 0.0 and 1.0");
            }
        }
    }

    public record RebuildResult(
            String repositoryId,
            String workspaceId,
            String branch,
            String authoritativeCommitId,
            int relationCount) {
    }

    private record RelationIdentity(
            String sourceCode,
            RelationType relationType,
            String targetCode) {
        private String display() {
            return sourceCode + " " + relationType + " " + targetCode;
        }
    }

    public static final class BranchProjectionContextException
            extends IllegalArgumentException {
        public BranchProjectionContextException(String message) {
            super(message);
        }
    }

    public static final class BranchProjectionSourceException
            extends IllegalStateException {
        public BranchProjectionSourceException(String message) {
            super(message);
        }

        public BranchProjectionSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
