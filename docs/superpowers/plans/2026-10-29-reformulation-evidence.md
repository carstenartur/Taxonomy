# Implementation plan

1. Reproduce the false-completion branch with ordinary JUnit; introduce a file-backed
   comparison ledger and test incomplete, failed, absent-provider and stale evidence.
2. Persist UUID/source/corpus/context/output identities and terminal case outcomes with
   ordered atomic writes; integrate with the existing real transport comparison.
3. Test and implement exact-output human review validation, including identity, score,
   timestamp and citation failures; expose validation through the existing Maven profile.
4. Document review/retention commands, run focused Maven tests, inspect changes, commit,
   and report exact evidence without invoking a provider or assigning human scores.
