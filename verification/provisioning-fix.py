"""Temporary source transform; only the explicit Java changes are deliverables."""
from pathlib import Path
import subprocess

MANAGER = Path('taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceManager.java')

def replace(text, old, new, count=1):
    assert text.count(old) == count, (old, text.count(old))
    return text.replace(old, new)

assert subprocess.check_output(['git', 'hash-object', str(MANAGER)], text=True).strip() == 'a0884de87f40012ed15340c278b2632207e5c0b2'
source = MANAGER.read_text()
source = replace(source, '''            String baseBranch = systemRepository.getDefaultBranch();

            if (repositoryFactory != null) {''', '''            String baseBranch = systemRepository.getDefaultBranch();
            DslGitRepository systemGit = repositoryFactory != null
                    ? repositoryFactory.getSystemRepository() : gitRepository;
            String baseCommit = systemGit.getHeadCommit(baseBranch);
            if (baseCommit == null) {
                throw new IllegalStateException("Source repository has no checkpoint on " + baseBranch);
            }
            String systemDsl = systemGit.getDslAtCommit(baseCommit);
            if (systemDsl == null) {
                throw new IllegalStateException("Source checkpoint has no architecture document");
            }

            if (repositoryFactory != null) {''')
source = replace(source, '''                DslGitRepository systemGit = repositoryFactory.getSystemRepository();

                String systemDsl = systemGit.getDslAtHead(baseBranch);
                if (systemDsl != null) {
                    workspaceGit.commitDsl(
                            targetBranch,
                            systemDsl,
                            username,
                            "Fork from shared/" + baseBranch);
                }
''', '''                String currentCommit = workspaceGit.commitDsl(
                        targetBranch,
                        systemDsl,
                        username,
                        "Fork from shared/" + baseBranch);
''')
source = replace(source, 'workspace.setBaseCommit(systemGit.getHeadCommit(baseBranch));', 'workspace.setBaseCommit(baseCommit);')
source = replace(source, 'workspace.setCurrentCommit(workspaceGit.getHeadCommit(targetBranch));', 'workspace.setCurrentCommit(currentCommit);')
source = replace(source, '''                String baseCommit = gitRepository.getHeadCommit(baseBranch);
                if (baseCommit != null) {
                    gitRepository.createBranch(userBranch, baseBranch);
                }
''', '''                String createdCommit = gitRepository.createBranch(userBranch, baseBranch);
                if (createdCommit == null || gitRepository.getDslAtCommit(createdCommit) == null) {
                    throw new IllegalStateException("Source branch no longer has an architecture checkpoint");
                }
                baseCommit = createdCommit;
''')
MANAGER.write_text(source)
print('SOURCE CHECKPOINT CORRECTION', subprocess.check_output(['git', 'hash-object', str(MANAGER)], text=True).strip())
