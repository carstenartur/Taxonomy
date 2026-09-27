# PR #1135 positive-review guard coverage correction

## Scope and RED evidence

Only `ReformulationPositiveReviewGuardTest` changed; no production guard, coverage policy, or test selection changed. CI/CD run `36334840431`, core job `108663857464`, at `e89ebdf4389558dd977c62fc1eefbf2d72370c59` failed the changed-source branch gate for `ReformulationPositiveReviewGuard.java`: **50/88 branches (56.82%)**, below 60.00%. This is the coverage RED for tests of existing rejection behavior. Evidence is in `attachments/7c577910-a9a7-4e4a-8319-4145f7f5e637/pr1135-core-coverage.zip` relative to the shared workspace (`coverage/jacoco.xml`, `evidence/coverage-gate.txt`).

## Test change and self-review

The real Spring/API/database fixture performs an adoption, then the parameterized matrix damages one persisted preview or receipt field per scenario using JDBC. Cases cover a mismatched preview payload hash; absent revision, current requirement, or current version; and mismatched preview, proposal, project, requirement, source-version, or previous-version identity. For semantic mismatches, the test updates the preview digest to match the damaged bytes, so the deeper consistency check—not just the hash check—rejects them. Every case asserts HTTP 409 and equality of the complete persisted requirement view before and after, including status, review metadata, current version ID, and current-version data. The existing positive, imported-evidence, and scope-isolation controls remain unchanged.

Self-review checked that each case changes one distinct field, the hash exception is deliberate, the receipt case updates the receipt rather than the preview, and the assertions observe API/database behavior instead of mocks or internal counters. `git diff --check` was clean. The test-only change was checkpointed locally at `72520d77358c5662a1dddcc75cca8b00068d971b`; after including the task brief, local `60ff963bf26d5de6b5219929acac759319d90419` had the identical full tree (`d9dcce2fd898686d9c350c8f09c5f00a9d8b6096`) to remote published checkpoint `8ccf2742d9f4b098f9b0b15d817719350e387257` before the focused run.

## Verification

Executed from the repository root on 2026-09-27 with Java 21 via the configured runner:

```sh
python /workspace/scratch/38625e9262ff/toolchain/run-guard-maven.py -pl taxonomy-app -am test -Dtest=ReformulationPositiveReviewGuardTest -Dsurefire.failIfNoSpecifiedTests=false
python /workspace/scratch/38625e9262ff/toolchain/run-guard-maven.py -pl taxonomy-portfolio org.jacoco:jacoco-maven-plugin:0.8.15:report -Djacoco.dataFile=/workspace/scratch/38625e9262ff/Taxonomy-guard-coverage/taxonomy-app/target/jacoco.exec
```

Focused class: **26 tests, 0 failures, 0 errors, 0 skipped**, Maven reactor `BUILD SUCCESS` (finished 2026-09-27 18:27:59 UTC). The second command loaded the app execution data and analyzed the current portfolio classes, `BUILD SUCCESS` (finished 18:28:19 UTC). The source-level JaCoCo counters for `ReformulationPositiveReviewGuard.java` are **60 covered / 88 total BRANCH = 68.18%** and **59 covered / 64 total LINE = 92.19%**. Branch coverage is 7 covered branches above the 53 needed for the 60% minimum (8.18 percentage points), and 10 covered branches above the failing CI artifact.

Local evidence: `taxonomy-app/target/surefire-reports/TEST-com.taxonomy.portfolio.reformulation.ReformulationPositiveReviewGuardTest.xml`, `taxonomy-app/target/jacoco.exec`, and `taxonomy-portfolio/target/site/jacoco/jacoco.xml`. These are generated build artifacts, not committed files.

## Limitations

This measurement is the focused guard class with app execution data against current portfolio classes, not the authoritative full reactor CI coverage gate. No new imported-evidence hash-corruption test was added; existing imported behavior and scope controls remain. The controller will run and assess final CI before merge.
