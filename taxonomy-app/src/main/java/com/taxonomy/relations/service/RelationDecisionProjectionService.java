package com.taxonomy.relations.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RelationCommand;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.UpsertRelation;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verifies one successful Git-authoritative relation command and hands its exact
 * post-command state to the transactional projection writer.
 *
 * <p>Git and TaxDSL validation deliberately happen before the writer opens a
 * relational transaction. The command payload is not trusted as the resulting
 * state: the exact authoritative commit is re-read, partial upserts therefore
 * preserve unspecified properties, and removals become explicit tombstones.
 * User-facing reads must not use this incremental projection until a later
 * full-branch rebuild has marked it complete.</p>
 */
@Service
public class RelationDecisionProjectionService {

    private static final String RELATION_KIND = "relation";

    private final RelationDecisionProjectionWriter projectionWriter;
    private final DslGitRepositoryFactory gitRepositoryFactory;
    private final TaxDslParser parser;

    @Autowired
    public RelationDecisionProjectionService(
            RelationDecisionProjectionWriter projectionWriter,
            DslGitRepositoryFactory gitRepositoryFactory) {
        this(projectionWriter, gitRepositoryFactory, new TaxDslParser());
    }

    RelationDecisionProjectionService(
            RelationDecisionProjectionWriter projectionWriter,
            DslGitRepositoryFactory gitRepositoryFactory,
            TaxDslParser parser) {
        this.projectionWriter = Objects.requireNonNull(
                projectionWriter, "projectionWriter");
        this.gitRepositoryFactory = Objects.requireNonNull(
                gitRepositoryFactory, "gitRepositoryFactory");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /**
     * Re-reads the exact authority token and projects only the state found in
     * that commit.
     */
    public ProjectionResult project(
            RepositoryContext context,
            CommandResult commandResult,
            RelationCommand command) {
        ValidatedCommand validated = validate(context, commandResult, command);
        AuthoritativeState state = readAuthoritativeState(
                context,
                validated.authoritativeCommitId(),
                command);
        RelationIdentity identity = command.identity();
        ProjectionRequest request = new ProjectionRequest(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                identity.sourceId(),
                validated.relationType(),
                identity.targetId(),
                state.relationPresent(),
                state.status(),
                state.confidence(),
                state.provenance(),
                validated.authoritativeCommitId(),
                command.metadata().causationId());
        return projectionWriter.write(request);
    }

    private ValidatedCommand validate(
            RepositoryContext context,
            CommandResult result,
            RelationCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(result, "commandResult");
        Objects.requireNonNull(command, "command");

        if (context.scope() == RepositoryScope.CENTRAL_READ) {
            throw new ProjectionContextMismatchException(
                    "Relation projections require CENTRAL_WRITE, WORKSPACE or FORK scope");
        }
        if (!context.repositoryId().equals(result.repositoryId())
                || !Objects.equals(context.workspaceId(), result.workspaceId())
                || !context.branch().equals(result.branch())
                || context.scope() != result.scope()) {
            throw new ProjectionContextMismatchException(
                    "Command result does not match the exact repository context");
        }
        if (!command.metadata().causationId().equals(result.causationId())) {
            throw new ProjectionContextMismatchException(
                    "Command result causation ID does not match the command");
        }

        String authoritativeCommitId = requireCommitId(
                result.authoritativeCommitId());
        boolean changed = result.changeKind() != ChangeKind.UNCHANGED;
        if (result.commitCreated() != changed) {
            throw new ProjectionContextMismatchException(
                    "Command result commit-created flag contradicts its change kind");
        }
        if (command instanceof UpsertRelation
                && result.changeKind() == ChangeKind.REMOVED) {
            throw new ProjectionContextMismatchException(
                    "Upsert command cannot produce a removed result");
        }
        if (command instanceof RemoveRelation
                && result.changeKind() != ChangeKind.REMOVED
                && result.changeKind() != ChangeKind.UNCHANGED) {
            throw new ProjectionContextMismatchException(
                    "Remove command cannot produce an add or update result");
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(
                    command.identity().relationType());
        } catch (IllegalArgumentException error) {
            throw new ProjectionContextMismatchException(
                    "Unsupported projected relation type: "
                            + command.identity().relationType(),
                    error);
        }
        return new ValidatedCommand(authoritativeCommitId, relationType);
    }

    private AuthoritativeState readAuthoritativeState(
            RepositoryContext context,
            String authoritativeCommitId,
            RelationCommand command) {
        DslGitRepository repository = gitRepositoryFactory.resolveRepository(context);
        String dsl;
        try {
            dsl = repository.getDslAtCommit(authoritativeCommitId);
        } catch (IOException error) {
            throw new ProjectionSourceException(
                    "Unable to read authoritative relation commit "
                            + authoritativeCommitId,
                    error);
        }
        if (dsl == null) {
            throw new ProjectionSourceException(
                    "Authoritative relation commit is missing: "
                            + authoritativeCommitId);
        }

        DocumentAst document;
        try {
            document = parser.parse(dsl, "architecture.taxdsl");
        } catch (RuntimeException error) {
            throw new ProjectionSourceException(
                    "Authoritative relation commit contains invalid TaxDSL: "
                            + authoritativeCommitId,
                    error);
        }

        RelationIdentity identity = command.identity();
        List<BlockAst> matches = matchingRelationBlocks(document, identity);
        if (matches.size() > 1) {
            throw new ProjectionSourceException(
                    "Authoritative commit contains duplicate relation "
                            + display(identity));
        }

        if (command instanceof UpsertRelation) {
            if (matches.isEmpty()) {
                throw new ProjectionSourceException(
                        "Authoritative commit does not contain upserted relation "
                                + display(identity));
            }
            BlockAst relation = matches.getFirst();
            if (relation.getHeaderTokens().size() != 3) {
                throw new ProjectionSourceException(
                        "Authoritative relation has a malformed header: "
                                + display(identity));
            }
            return new AuthoritativeState(
                    true,
                    uniqueProperty(relation, "status", identity),
                    confidence(relation, identity),
                    uniqueProperty(relation, "provenance", identity));
        }

        if (!matches.isEmpty()) {
            throw new ProjectionSourceException(
                    "Authoritative commit still contains removed relation "
                            + display(identity));
        }
        return new AuthoritativeState(false, null, null, null);
    }

    private static List<BlockAst> matchingRelationBlocks(
            DocumentAst document,
            RelationIdentity identity) {
        List<BlockAst> matches = new ArrayList<>();
        for (BlockAst block : document.getBlocks()) {
            List<String> tokens = block.getHeaderTokens();
            if (RELATION_KIND.equals(block.getKind())
                    && tokens.size() >= 3
                    && identity.sourceId().equals(tokens.get(0))
                    && identity.relationType().equals(tokens.get(1))
                    && identity.targetId().equals(tokens.get(2))) {
                matches.add(block);
            }
        }
        return matches;
    }

    private static String uniqueProperty(
            BlockAst relation,
            String property,
            RelationIdentity identity) {
        List<String> values = relation.propertyValues(property);
        if (values.size() > 1) {
            throw new ProjectionSourceException(
                    "Authoritative relation has duplicate " + property
                            + " properties: " + display(identity));
        }
        return values.isEmpty() ? null : normalizeOptional(values.getFirst());
    }

    private static Double confidence(
            BlockAst relation,
            RelationIdentity identity) {
        String value = uniqueProperty(relation, "confidence", identity);
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
            throw new ProjectionSourceException(
                    "Authoritative relation has invalid confidence: "
                            + display(identity),
                    error);
        }
    }

    private static String requireCommitId(String value) {
        if (value == null) {
            throw new ProjectionContextMismatchException(
                    "authoritativeCommitId must not be null");
        }
        try {
            return ObjectId.fromString(value).name();
        } catch (IllegalArgumentException error) {
            throw new ProjectionContextMismatchException(
                    "authoritativeCommitId must be a full Git object ID",
                    error);
        }
    }

    private static String display(RelationIdentity identity) {
        return identity.sourceId() + " "
                + identity.relationType() + " "
                + identity.targetId();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public enum ProjectionOutcome {
        CREATED,
        UPDATED,
        REPLAYED
    }

    public record ProjectionResult(
            ProjectionOutcome outcome,
            String authoritativeCommitId,
            boolean relationPresent) {
        public ProjectionResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            authoritativeCommitId = Objects.requireNonNull(
                    authoritativeCommitId, "authoritativeCommitId");
        }
    }

    record ProjectionRequest(
            String repositoryId,
            String workspaceId,
            String branch,
            String sourceCode,
            RelationType relationType,
            String targetCode,
            boolean relationPresent,
            String status,
            Double confidence,
            String provenance,
            String authoritativeCommitId,
            String causationId) {
    }

    private record ValidatedCommand(
            String authoritativeCommitId,
            RelationType relationType) {
    }

    private record AuthoritativeState(
            boolean relationPresent,
            String status,
            Double confidence,
            String provenance) {
    }

    public static final class ProjectionContextMismatchException
            extends IllegalArgumentException {
        public ProjectionContextMismatchException(String message) {
            super(message);
        }

        public ProjectionContextMismatchException(
                String message,
                Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ProjectionSourceException
            extends IllegalStateException {
        public ProjectionSourceException(String message) {
            super(message);
        }

        public ProjectionSourceException(
                String message,
                Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ProjectionConflictException
            extends IllegalStateException {
        public ProjectionConflictException(String message) {
            super(message);
        }
    }
}
