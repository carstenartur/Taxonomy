package com.taxonomy.composition.dsl.service;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Service
@ConditionalOnFeature({"architecture"})
public class ProjectDslSource {
    private final WorkspaceResolver resolver;
    private final ArchitectureEditorService editor;

    public ProjectDslSource(WorkspaceResolver resolver, ArchitectureEditorService editor) {
        this.resolver = resolver;
        this.editor = editor;
    }

    public ArchitectureEditorService.Document read(String repositoryId, String branch, String commit, Long revision)
            throws IOException {
        var context = resolver.resolveCurrentRepositoryContextForRead();
        if ((repositoryId != null && !repositoryId.equals(context.repositoryId()))
                || (branch != null && !branch.equals(context.branch()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected project context has changed");
        }
        try {
            return editor.read(context, commit, revision);
        } catch (CommandProblem error) {
            var status = "NOT_FOUND".equals(error.code()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, error.getMessage(), error);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid requested architecture revision", invalid);
        }
    }
}
