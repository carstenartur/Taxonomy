# Implement and verify bounded Ubuntu release recovery

Spec: docs/superpowers/specs/2026-11-23-ubuntu-archive-recovery-design.md.

## Task1 — narrow helper and fixture

Worktree: existing packed-snapshots, base7049dac32b08de02cfc8604a003a597d4dfe95b1, tree5412ef379cc2d2ee5b54c730d099d718e5af6b7e. Only deploy/container/apt/ubuntu-archive.sh and .github/scripts/docker-apt-sources.test.mjs are implementation-owned. Root owns this spec/plan, QA, publication and merge.

1. Read the exact retained diagnosis and these contracts. Confirm the helper/test interface and existing script ownership; do not rerun broad root-cause investigation or perform live archive requests.
2. Extend only the existing fakeAPT fixture, prove the new bounded recovery case fails on the baseline, and preserve the real RED output.
3. Implement atmostone second candidate round with existing trust and per-probe limits, preserving fresh probe isolation and early exit. No other product/runtime behavior changes.
4. Run focused Node tests and bash -n; demonstrate all contract branches, no skipped failure witness. Inspect small diff, commit only implementation files and report commands/results/commit/remaininglimits in this task ledger. No Maven, Docker, app or provider process. No subagents/push.
5. Root commissions fresh scoped specification/quality review. Review findings go to this original implementer using the scoped fix loop. After approval, root publishes the exact reviewed tree, verifies current-head CI and merges with authorized expectedhead. No fullCI/jobrerun substitution for a source correction.

Only one source implementer/local build/app/timed benchmark owns the workspace at a time. Automatic measurement is paused, with all valid rows preserved; it resumes only after this implementation releases CPU. Root must not edit implementation findings manually. User already authorizes fixes, publication and merge; no new permission question is needed.
