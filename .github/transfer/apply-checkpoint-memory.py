import hashlib, pathlib, subprocess
BASE = 'dfec054b53204565e5add4d0c7c0e29c60af22e8'
SOURCE = '47e38c57a1bac4182a2a3868963cd03c0adf751c'
FILES = {
 '.github/package.json': ('dd68ac0d968bd9782dda736142f8450d897a7147','e4b592474959589420d1f46bb71dd3651cc1a224'),
 '.github/scripts/ui-primary-session-workflow.mjs': ('9ac5304be7b1202b03e89bf7326ebe2036eff50d','7fecb9f437663e96660562104e2d09c15c495cd0'),
 'taxonomy-analysis/pom.xml': ('7c36e32aa36c743880a09e04f1d8df28573dc8bc','3b8a82f63b21379ff5d1f393421d43a8710debaa'),
 'taxonomy-analysis/src/main/java/com/taxonomy/analysis/recovery/AnalysisContinuationRun.java': ('a62783c9bcc3fcce8e79d1b2248fc05e275f0560','dbf2a74456ee7aa1b77524283dfca2c43bdfbe4d'),
 'taxonomy-analysis/src/main/java/com/taxonomy/analysis/recovery/AnalysisContinuationStore.java': ('98f6d9d1026402895753cd39ef4a0d72a7b77d3a','0f4413a84a4cd5d46dd10204417cb78e85f2a915'),
 '.github/scripts/ui-copilot-completion.test.mjs': (None,'c268457b1bcb1f8878e5cb8bbdcafe3b0f11a6d5'),
 'taxonomy-analysis/src/test/java/com/taxonomy/analysis/recovery/RecoveryCheckpointPersistenceProbe.java': (None,'018b0321b274aee9af27b0d4465df235047c3788'),
 'taxonomy-analysis/src/test/java/com/taxonomy/analysis/recovery/RecoveryCheckpointPersistenceTest.java': (None,'518676ca3841a55b3eb89ca878c6f14e05f008e9')
}
def blob(data): return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
def apply(read_source):
 for name,(before,after) in FILES.items():
  p=pathlib.Path(name)
  assert (not p.exists()) if before is None else blob(p.read_bytes())==before, name
 def replace(name,before,after):
  p=pathlib.Path(name);s=p.read_text();assert s.count(before)==1,name;p.write_text(s.replace(before,after))
 replace('taxonomy-analysis/src/main/java/com/taxonomy/analysis/recovery/AnalysisContinuationRun.java',
  'import org.hibernate.annotations.JdbcTypeCode;', 'import org.hibernate.annotations.JdbcTypeCode;\nimport org.hibernate.annotations.DynamicUpdate;')
 replace('taxonomy-analysis/src/main/java/com/taxonomy/analysis/recovery/AnalysisContinuationRun.java',
  '@Entity\n', '@Entity\n// Checkpoint bookkeeping must not rewrite the multi-megabyte frozen result LOB.\n// Only explicit result changes should allocate a new frozen-result LOB.\n@DynamicUpdate\n')
 replace('taxonomy-analysis/src/main/java/com/taxonomy/analysis/recovery/AnalysisContinuationStore.java',
 '''        var run = em.find(AnalysisContinuationRun.class, claim.id());
        if (run == null || !Objects.equals(run.claimToken, claim.token())) return "CANCELLED";
        return run.state;''',
 '''        // Called at every cooperative checkpoint, often several times per question.
        // Read only the current authority, never hydrate the frozen result/catalogue
        // or reuse an entity cached before another transaction cancelled the run.
        return em.createQuery("select r.state from AnalysisContinuationRun r where r.id=:id and r.claimToken=:token", String.class)
                .setParameter("id", claim.id()).setParameter("token", claim.token())
                .getResultList().stream().findFirst().orElse("CANCELLED");''')
 replace('taxonomy-analysis/pom.xml','    </dependencies>', '''        <dependency>
            <groupId>org.hsqldb</groupId>
            <artifactId>hsqldb</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>''')
 replace('.github/package.json',
  '"test:analysis-workflow-regression": "node --test scripts/analysis-workflow-regression.test.mjs"',
  '"test:analysis-workflow-regression": "node --test scripts/analysis-workflow-regression.test.mjs scripts/ui-copilot-completion.test.mjs"')
 replace('.github/scripts/ui-primary-session-workflow.mjs',
 '''    const summary = document.querySelector('#copilotContent .alert-success');
    return state?.lastAnalyzedText === text
''',
 '''    const summary = document.querySelector('#copilotContent .alert-success');
    const recovery = state?.analysisRecovery?.state;
    const followup = state?.recoveryContext?.followupState;
    const settled = button && !button.disabled && spinner?.classList.contains('d-none');
    // A terminal partial result cannot turn into SUCCESS without a user decision.
    // Fail with the actual application reason, not a misleading 180-second timeout.
    // Never accept a partial result or silently retry it in this success scenario.
    if (state?.lastAnalyzedText === text && settled
        && (['PARTIAL', 'ERROR', 'CANCELLED'].includes(state.lastAnalysisStatus)
          || ['PAUSED', 'STOPPED', 'CANCELLED', 'COMPLETED_WITH_GAPS'].includes(recovery)
          || ['PAUSED', 'CANCELLED', 'COMPLETED_WITH_GAPS'].includes(followup))) {
      const reason = [state.recoveryContext?.followupError,
        document.getElementById('statusArea')?.textContent].filter(Boolean).join(' ').slice(0, 1500);
      throw new Error(`Copilot did not complete: ${state.lastAnalysisStatus} / ${recovery || '-'} / ${followup || '-'}: ${reason}`);
    }
    return state?.lastAnalyzedText === text
''')
 for name,(before,after) in FILES.items():
  p=pathlib.Path(name)
  if before is None:
   p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(read_source(name))
  assert blob(p.read_bytes())==after,name
 print('Verified all eight before/after file identities')
if __name__=='__main__':
 import os
 def git(*args):return subprocess.check_output(['git',*args],text=True).strip()
 assert git('rev-parse','HEAD')==BASE
 assert git('ls-remote','origin','refs/heads/feat/resumable-copilot-evaluation').split()[0]==BASE
 subprocess.run(['git','fetch','origin',SOURCE],check=True)
 apply(lambda name:subprocess.check_output(['git','show',SOURCE+':'+name]))
 subprocess.run(['git','add','--',*FILES],check=True)
 subprocess.run(['git','diff','--cached','--check'],check=True)
 assert set(git('diff','--cached','--name-only').splitlines())==set(FILES)
 git('config','user.name','ChatGPT');git('config','user.email','chatgpt@users.noreply.github.com')
 git('commit','-m','fix(analysis): avoid checkpoint LOB churn and surface terminal CI failures\n\nUpdate only dirty continuation columns and read claim state without hydrating the frozen result. Keep durable snapshots, optimistic revisions and cancellation authority. Add real Hibernate/HSQL persistence regressions and immediate failure diagnostics to the full Copilot browser scenario, without weakening memory guards or success assertions.')
 output=pathlib.Path(os.environ['RUNNER_TEMP'])/'checkpoint-memory';output.mkdir(exist_ok=True)
 (output/'candidate.txt').write_text(git('rev-parse','HEAD')+'\n')
 (output/'tree.txt').write_text(git('rev-parse','HEAD^{tree}')+'\n')
 (output/'diff.patch').write_bytes(subprocess.check_output(['git','diff',BASE,'HEAD']))
