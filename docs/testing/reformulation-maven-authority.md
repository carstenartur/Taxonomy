# Maven-owned reformulation verification

PR #1101 at d6c4720 passed its focused usage lane, but canonical CI and the
PostgreSQL lane both stopped at
`WorkflowTestAuthorityPolicyTest.repositoryWorkflowsDelegateExecutableTestsToMaven`.
The added workflow was not classified in the verification catalogue and supplied
`-Dtest` itself. The PostgreSQL run stopped before its migration tests: it did not
establish a migration failure.

## Correction

The supplemental workflow now invokes `-Preformulation-usage-tests`. The parent
POM owns the original six persistence/recovery/encoding/architecture suite names;
the verification catalogue documents the identical selection and classifies the
workflow. The workflow still first runs every analysis/upstream unit test.

The profile additionally runs `WorkflowTestAuthorityPolicyTest`,
`PythonSourceRatchetRepositoryTest` and `ReformulationVerificationProfileTest`.
All previous positive-count report checks remain; positive counts are also
required for these three guards. The last test checks POM/catalogue agreement,
retained original suites, workflow delegation and executed-report requirements.
Parent-POM, catalogue and domain changes now trigger the supplemental workflow.

No policy implementation, allow-list exception, existing test, migration,
coverage threshold or canonical CI command changes. This repairs build ownership,
not requirement wording, architecture, proposal adoption or runtime data.

## Evidence and limitations

Source was extracted from the exact d6c4720 CI archive; its complete Git tree is
86a3059c205f2ef5681f14dfbcfc40964768a941. The unchanged workflow-authority policy was
compiled with Java 21 and reproduced both original violations. The new profile
contract also failed before configuration was added. Both executable checks pass
after correction. `git diff --check` passes.

The actual failing core reports are artifact 10668290994, workflow 35659314030;
PostgreSQL log is artifact 10667218324, workflow 35659314010. These are the source
of the diagnosis, not fabricated test outcomes.

Local `./mvnw -B verify -Pci` cannot obtain the pinned Maven distribution because
outbound DNS is unavailable. Local checks are not a full Maven/JUnit or database
result. Current-head CI, coverage, database and independent review remain required.
