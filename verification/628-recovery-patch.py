"""Apply the two reproduced review fixes to an immutable source checkout only."""
from pathlib import Path
import json
import os
import subprocess

BASE = '37a6eedfb64067667985650bddd50102b21ddad3'
ROOT = 'taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/'
MANAGER = Path(ROOT + 'WorkspaceManager.java')
SYNC = Path(ROOT + 'GitNativeSyncIntegrationService.java')
CLAIM = Path('taxonomy-app/src/test/java/com/taxonomy/workspace/service/WorkspaceProvisioningClaimIT.java')
TESTS = [Path('taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/' + name + '.java')
         for name in ('WorkspaceFactoryRetryRecoveryTest', 'WorkspaceDivergedStrategyScopeTest')]

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def replace(text, old, new, count=1):
    assert text.count(old) == count, (old, text.count(old))
    return text.replace(old, new)

assert git('rev-parse', 'HEAD') == BASE
assert git('hash-object', str(MANAGER)) == '61f8a6bb353427c700f4586e3dc912acaf548302'
assert git('hash-object', str(SYNC)) == '70f87591796aa76f043ab34a5d4997f194bd176c'
text = MANAGER.read_text()
text = replace(text,
'''        String targetBranch = workspace != null && workspace.getSourceBranch() != null
                && !workspace.getSourceBranch().isBlank() ? workspace.getCurrentBranch() : "main";''',
'''        boolean allocated = workspace != null && workspace.getCurrentCommit() != null;
        String targetBranch = workspace != null && (allocated
                || (workspace.getSourceBranch() != null && !workspace.getSourceBranch().isBlank()))
                ? workspace.getCurrentBranch() : "main";''')
text = replace(text,
'''            String baseCommit = existingLegacyHead != null && recordedBase != null
                    ? recordedBase : systemGit.getHeadCommit(baseBranch);''',
'''            String baseCommit = recordedBase != null && !recordedBase.isBlank()
                    ? recordedBase : systemGit.getHeadCommit(baseBranch);''')
text = replace(text,
'''                workspaceGit.commitDsl(
                        targetBranch,
                        systemDsl,
                        username,
                        "Fork from shared/" + baseBranch);''',
'''                String existingHead = workspaceGit.getHeadCommit(targetBranch);
                String allocatedCommit;
                if (existingHead == null) {
                    allocatedCommit = workspaceGit.commitDsl(
                            targetBranch, systemDsl, username, "Fork from shared/" + baseBranch);
                } else if (recordedBase != null
                        && existingHead.equals(workspace.getCurrentCommit())
                        && systemDsl.equals(workspaceGit.getDslAtCommit(existingHead))) {
                    // A failed READY save may leave the original Git seed intact.
                    // Adopt only that recorded head AND payload; never reseed user edits.
                    workspaceGit.verifyExpectedHead(targetBranch, existingHead);
                    allocatedCommit = existingHead;
                } else {
                    throw new IllegalStateException(
                            "Existing workspace content does not match its recorded allocation");
                }''')
text = replace(text, 'workspace.setCurrentCommit(workspaceGit.getHeadCommit(targetBranch));',
               'workspace.setCurrentCommit(allocatedCommit);')
MANAGER.write_text(text)

text = SYNC.read_text()
text = replace(text,
'''        return super.resolveDiverged(username, userBranch, strategy);''',
'''        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        boolean keepMine = strategy == DivergedStrategy.KEEP_MINE;
        String commit = context.workspaceId() == null
                ? chooseWithinRepository(username, context, context.branch(), keepMine)
                : keepMine ? publishAcrossRepositories(username, context, context.branch(), true)
                : pullAcrossRepositories(username, context, context.branch(), true);
        return (keepMine ? "Published your version to the selected source: "
                : "Adopted the selected source version: ") + abbreviate(commit);''')
for direction, flag, rationale in (
        ('pull', 'takeSource', 'Integrate architecture versions from source'),
        ('publish', 'keepWorkspace', 'Publish architecture workspace')):
    # Retain the existing entry point; forced choices reuse all checked writes and projections.
    start = text.index('    private String ' + direction + 'AcrossRepositories(String username,')
    end = text.index('    private String ' + direction + 'AcrossRepositoriesVersion(', start)
    text = text[:start] + f'''    private String {direction}AcrossRepositories(String username,
                                            WorkspaceContext context,
                                            String userBranch) throws IOException {{
        return {direction}AcrossRepositories(username, context, userBranch, false);
    }}

    private String {direction}AcrossRepositories(String username,
                                            WorkspaceContext context,
                                            String userBranch,
                                            boolean {flag}) throws IOException {{
        RepositoryContext selected = RepositoryContext.workspace(
                context.repositoryId(), context.workspaceId(), userBranch, username);
        return editorVersions.version(selected, "{rationale}",
                () -> {direction}AcrossRepositoriesVersion(username, context, userBranch, {flag}));
    }}

''' + text[end:]
    start = text.index('    private String ' + direction + 'AcrossRepositoriesVersion(')
    end = text.index('\n    private String ', start + 10)
    section = text[start:end]
    section = replace(section, 'String userBranch) throws IOException {',
                      f'String userBranch,\n                                          boolean {flag}) throws IOException {{')
    section = replace(section, 'WorkspaceMergeState state = initialiseWorkspaceMergeBase(',
                      f'WorkspaceMergeState state = {flag} ? null : initialiseWorkspaceMergeBase(')
    if direction == 'pull':
        section = replace(section,
'''        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), ours, theirs);''',
'''        TaxDslMergeResult merge = takeSource
                ? new TaxDslMergeResult(theirs, java.util.List.of())
                : semanticMergeService.mergeContent(state.baseDsl(), ours, theirs);''')
    else:
        section = replace(section,
'''        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), sourceDsl, workspaceDsl);''',
'''        TaxDslMergeResult merge = keepWorkspace
                ? new TaxDslMergeResult(workspaceDsl, java.util.List.of())
                : semanticMergeService.mergeContent(state.baseDsl(), sourceDsl, workspaceDsl);''')
    text = text[:start] + section + text[end:]
insert = text.index('    private String mergeWithinRepository(')
text = text[:insert] + '''    /** Explicit conflict choices retain the selected central repository and checked snapshots. */
    private String chooseWithinRepository(String username, WorkspaceContext context,
                                          String userBranch, boolean keepMine) throws IOException {
        SystemRepository metadata = systemRepositoryService.getRepository(context.repositoryId());
        requireMatchingRepository(context, metadata);
        String centralBranch = metadata.getDefaultBranch();
        if (centralBranch == null || centralBranch.isBlank()) {
            throw new IllegalStateException("Selected repository has no default branch");
        }
        DslGitRepository repository = repositoryFactory.getCentralRepository(context.repositoryId());
        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before explicit conflict choice", username, context);
        String from = keepMine ? userBranch : centralBranch;
        String into = keepMine ? centralBranch : userBranch;
        BranchSnapshot chosen = snapshot(repository, from);
        BranchSnapshot destination = snapshot(repository, into);
        String content = requiredSnapshot(chosen, "Selected conflict side has no content: " + from);
        repository.verifyExpectedHead(from, chosen.head());
        String commit = commitIfChanged(repository, into, content, destination.dsl(),
                destination.head(), username, "Resolve conflict using " + from);
        WorkspaceContext target = new WorkspaceContext(username, null, into, context.repositoryId());
        portfolioGitPort.materializePortfolio(content, username, target);
        if (keepMine) {
            updateAfterPublish(username, commit);
        } else {
            updateAfterSync(username, chosen.head());
        }
        return commit;
    }

''' + text[insert:]
SYNC.write_text(text)

# The real JPA claim test starts with an absent destination, and retains its exact
# single-commit/200-versus-409 assertions. Bound worker cleanup as requested in #1063.
text = CLAIM.read_text()
text = replace(text, 'when(destination.getHeadCommit("main")).thenReturn(base);',
               'when(destination.getHeadCommit("main")).thenReturn(null);')
text = replace(text, '            try (var executor = Executors.newFixedThreadPool(2)) {',
'''            var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
            try {''')
text = replace(text, '                assertEquals(List.of(200, 409), statuses);\n            }',
'''                assertEquals(List.of(200, 409), statuses);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                        "Provisioning test workers did not terminate");
            }''')
CLAIM.write_text(text)
paths = [MANAGER, SYNC, CLAIM, *TESTS]
git('add', '--', *(str(p) for p in paths))
actual = git('diff', '--cached', '--name-only').splitlines()
assert sorted(actual) == sorted(str(p) for p in paths), actual
git('diff', '--cached', '--check')
out = Path(os.environ['RUNNER_TEMP']) / 'recovery-evidence'
out.mkdir(exist_ok=True)
(out / 'source.patch').write_text(subprocess.check_output(['git', 'diff', '--cached', 'HEAD'], text=True))
(out / 'tree.txt').write_text(git('write-tree'))
print('CANDIDATE TREE', (out / 'tree.txt').read_text())
print(git('diff', '--cached', '--stat'))
