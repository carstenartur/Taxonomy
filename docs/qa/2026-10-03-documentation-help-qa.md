# Documentation and help QA — 3 October 2026

## Current decision and superseded review

The earlier approval of the heavily condensed USER_GUIDEs is withdrawn. Preserved
anchors, pictures and successful rendering were not evidence that the corresponding
instructions remained usable. The owner identified a real regression: instructions
for starting over, cancelling/saving, provider retry versus status refresh, creating
relations and recording current analysis had been reduced to inadequate summaries.

Commit `e03e2c045ff0ee9a3c0874900c38fe79353ad08c` replaces that condensation with
conservative corrections to the complete main guides at
`dc10e7b143ce03b465ae328cb32cf05f8f66c13d`. No old chapter is relocated or removed.
The original procedures, prerequisites, failure handling and examples remain in
place. Valid clarifications about scope, scores, local embeddings, saved states,
explicit adoption and separate reanalysis are retained. Other corrected documents
in this PR, including privacy and quota text, are not rolled back.

## Content-preservation result

| Guide | Main baseline | Rejected condensed version | Corrected version | Diff against main |
|---|---:|---:|---:|---|
| English | 1,881 lines | 705 lines | 1,971 lines | 133 added, 43 removed |
| German | 1,869 lines | 707 lines | 1,982 lines | 151 added, 38 removed |

In each language, all 173 prior headings, all ten fenced examples and all 55
image occurrences remain in their original order. The following four complete
procedure bodies were also compared byte-for-byte, not just by checking anchors:

- New analysis / cancel running analysis / save draft now, including shared drafts
  across tabs and devices, preservation versus deliberate clearing, and stale tabs.
- Paused ad-hoc Copilot recovery, including possible repeated provider billing,
  Refresh status without another question, and its distinction from project jobs.
- Creating a relation through the browser, with endpoint/type selection.
- Recording current analysis in Requirement Coverage, including its actual threshold
  policy; a separate clarification prevents treating that policy as universal truth.

The 81 removed main lines are targeted corrections rather than missing chapters.
The complete before/replacement ledger is included in the accompanying evidence.
Its categories are:

| Previous statement or structure | Replacement reason |
|---|---|
| Administrator bootstrap presented as normal user login; security note splits the first table | Assigned-account login, separate bootstrap/OIDC setup and one complete five-step table |
| Every catalogue node independently assessed; ten roots; no colour or zero implies irrelevance | Selected hierarchical scope, eight roots, explicit assessed/excluded/open states |
| Perfect score proves correctness; ignore low scores; universal 25/50 percent cutoffs | Explain independent relevance/suitability versus shared child weights and evidence limits |
| Fixed word/sentence counts guarantee accuracy; rewrite toward catalogue words | Preserve actual domain meaning, conditions and dependencies within configured limits |
| Displayed relationships or deterministic recommendation labels imply human approval | Distinguish candidates, imported links and accepted decisions |
| Reload means saved scores are necessarily lost | Reopen the saved draft or snapshot before repeating paid model work |
| Every editor change is a Git commit; materialization affects all installation relations | Separate semantic journal, workspace projection, explicit checkpoint and immutable snapshot |
| Branch-head Undo and semantic editor Undo are interchangeable | Retain both procedures and explain their different effects |
| LOCAL_ONNX implies all generative capabilities or complete offline isolation | State supported embedding/search/scoring scope and additional network/model requirements |

Old inaccurate claims are not restored merely to reduce the diff. Pictures are
retained illustrations, not new screenshot acceptance. More specialist documents
still require review; this is not certification of all product documentation.

## Fresh verification and identity

The [input manifest](2026-10-03-documentation-inputs.json) identifies the exact
source blobs, candidate SHA-256 values, runtime and diagnostic/result hashes.
The local preservation check passed after tightening all four German workflow
checks as well as all four English checks. Whitespace validation passed.

The actual packaged HelpController and Flexmark from CI artifact `11267389645`
rendered 55 registered documents in both languages with explicitly overlaid
previous documentation resources and these two corrected guides: 110 pages,
650 same-page fragment occurrences, no missing same-page targets, no duplicate
IDs and no missing referenced local image files. There were 648 relative Markdown
links requiring browser handling; these are occurrences, not measured HTTP errors.

This is a resource-overlay diagnostic, not a new combined release artifact or
full browser/Maven run. The current main release registry contains an additional
release-notes entry, so the 110-page diagnostic is not passed off as exhaustive
coverage of the final main registry. Full CI must verify the final merged inputs.
No paid/live model requests were made.

A one-time maintenance job `37122578025` applied only the two exact hash-bound
Markdown results to the unchanged draft PR branch, repeated the preservation and
whitespace checks, and removed its own maintenance branch. It neither changed main
nor installed a permanent workflow, and is not a substitute for regular CI. The
resulting guide blobs were read back from GitHub and match the locally tested
files. The publication commit contains only the two USER_GUIDE files.

## Previous evidence and remaining release gates

Earlier evidence belongs only to the exact historical candidates named in the
manifest history reference. In particular, previously unbound numerical claims
for the unpublished broad browser/link rewrite remain withdrawn. No new hashes
retroactively identify that candidate. The blocked broad JavaScript/link rewrite
is not part of this correction and has not been published by another route.

The already integrated #1165 independently fixes resource lifetime, duplicate
fallback-cache entries and generated heading IDs. This documentation correction
does not modify its code, any sanitizer, authorization, test selector or threshold.

Before merging this corrected PR, its final head must complete the normal CI and
review process. Before release, the required changes must be integrated and the
native Release Workflow must receive a separate, one-file release request bound
to the exact main parent, with tests enabled. Any failed required gate blocks release.
Actual cross-document browser navigation/context paths, remaining API/installation/
operations assertions, external references and fresh screenshots are follow-up
QA work, not silently reported as completed by these checks.
