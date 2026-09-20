# Copilot review corrections and stacked merge

The user authorizes correcting the open reviews and merging PRs #1084–#1087.
Keep #1075 open: synthetic contracts do not constitute Sparx product evidence.

1. On #1084, reproduce broken package endpoint references and inconsistent
   element/relation mappings. Preserve the documented EA transport types and
   require their declared canonical meaning to agree before serialization.
2. Carry the correction forward into #1085. Restrict Sparx relation remaps in
   the UI and server; handle missing profile metadata without an exception.
3. On #1086, retain localized browser titles while exports get readable titles;
   clarify the separate browser/Docker acceptance requirements.
4. Carry all corrections into #1087, including its extracted shared validator.
   Run focused regressions, then the mandatory full Maven gate and relevant CI.
5. Reply to and resolve corrected review threads. Merge in dependency order,
   retaining ancestry and checking each expected head SHA and CI status.
6. Record an honest Word feature/quality assessment and distinguish remaining
   implementable #1075 contracts from actual EA/PCS compatibility execution.

Validation is evidence based: reproducing tests must fail before fixes; no
test exclusions, weakened baselines or synthetic product compatibility claims.

## Execution evidence

- #1084: reproduced three codec failures (unresolved package reference and two
  semantic mismatches). All 15 codec tests passed after the correction; published
  as `eea2bd807d5bcc814a2c3af7290ceb85d7847328`. All three threads answered/resolved.
- #1085: both UI regressions reproduced. The real Spring test additionally exposed
  persistence translation of the generic remapping exception. After correction,
  21 focused Java tests and two UI tests passed; published as
  `de8b15d355b5a22540a5e47fcf3ba22eef27186c`. All three threads answered/resolved.
- #1086: reproduced missing policy-title metadata in the actual serialized
  projection. Preserved browser localization separately from readable export
  titles and clarified profile defaults. All 31 selected Java tests and ten UI
  contracts passed; published as `39b42d95ab9ed269ef3a7c66abb20b4f7022cbab`.
  Added a real civilian browser assertion for the rendered English policy title.
- Retargeted #1085–#1087 to `main` before publishing their corrections so the main
  CI, browser and database workflows execute before merging. Commit ancestry is
  retained; no force push or squashing of the stacked dependency commits.
- #1087 merge: kept the extracted transport-independent validator and transferred
  both semantic checks into it. Retained the package-reference correction and
  both new and existing translation messages. Updated the UI test DOM fixture to
  include the authority options and selection now consumed by the PCS controls;
  both UI regressions pass on the combined source.
- Word assessment: inspected the actual previous CI DOCX ZIP contents (43 decision
  panels, zero legacy-report images), renderer code and existing visual evidence.
  No new Word capability is claimed. See `docs/qa/word-and-sparx-completion-review.md`.
