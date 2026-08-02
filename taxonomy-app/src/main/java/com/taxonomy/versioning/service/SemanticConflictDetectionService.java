package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Merge preview that recognises semantically mergeable DSL block changes. */
@Service
@Primary
public class SemanticConflictDetectionService extends ConflictDetectionService {

    private final DslGitRepositoryFactory repositoryFactory;
    private final SemanticGitMergeService semanticMergeService;

    public SemanticConflictDetectionService(DslGitRepositoryFactory repositoryFactory,
                                            SemanticGitMergeService semanticMergeService) {
        super(repositoryFactory);
        this.repositoryFactory = repositoryFactory;
        this.semanticMergeService = semanticMergeService;
    }

    @Override
    public MergePreview previewMerge(String fromBranch,
                                     String intoBranch,
                                     WorkspaceContext context) {
        MergePreview ordinary = super.previewMerge(fromBranch, intoBranch, context);
        if (ordinary.canMerge() || ordinary.fromCommit() == null || ordinary.intoCommit() == null) {
            return ordinary;
        }
        try {
            SemanticGitMergeService.MergeOutcome semantic = semanticMergeService.preview(
                    repositoryFactory.resolveRepository(context), fromBranch, intoBranch);
            if (semantic.success()) {
                return new MergePreview(
                        true,
                        fromBranch,
                        intoBranch,
                        ordinary.fromCommit(),
                        ordinary.intoCommit(),
                        ordinary.alreadyMerged(),
                        ordinary.fastForwardable(),
                        List.of("The textual conflict is block-semantically mergeable"));
            }
            List<String> warnings = new ArrayList<>();
            warnings.add("Semantic conflicts require review");
            semantic.conflicts().forEach(conflict -> warnings.add("Conflict: " + conflict));
            return new MergePreview(
                    false,
                    fromBranch,
                    intoBranch,
                    ordinary.fromCommit(),
                    ordinary.intoCommit(),
                    false,
                    false,
                    warnings);
        } catch (IOException | RuntimeException exception) {
            List<String> warnings = new ArrayList<>(ordinary.warnings());
            warnings.add("Semantic preview failed: " + exception.getMessage());
            return new MergePreview(
                    false,
                    fromBranch,
                    intoBranch,
                    ordinary.fromCommit(),
                    ordinary.intoCommit(),
                    false,
                    false,
                    warnings);
        }
    }
}
