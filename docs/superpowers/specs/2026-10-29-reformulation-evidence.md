# Trustworthy reformulation comparison evidence

The opt-in comparison must identify one immutable run, source commit, exact five-case corpus,
frozen context and both output hashes. Only five persisted, validated, real-HTTP pairs may
reach `OUTPUTS_REQUIRE_HUMAN_REVIEW`. Unstarted, interrupted and schema-failed runs remain
separate. Artifact writes must precede claims about those artifacts, with atomic manifests
and isolated UUID directories. An exception in baseline preparation or report writing must
never produce a complete manifest.

Human assessment remains manual. Each completed review must identify this run, case and
both exact outputs, a reviewer, timestamp, five integer scores per strategy (0–4), and
verbatim output citations. Validation checks evidence binding and completeness; it does not
infer a semantic pass or a winning strategy. The existing Maven opt-in profile owns execution
and validation, and emits a readable summary of recorded observations. Historical NOT_RUN
status remains unchanged until actual provider execution and human review occur.

Review follow-up: retention packages only the exact validated artifact inventory,
rejects unexpected entries and symlink/noncanonical paths, and freezes validated
bytes before building the archive. Failure during ZIP generation or publication of
any review artifact clears every success marker and restores the unreviewed summary.
