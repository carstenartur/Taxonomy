# QA remediation contract - 17 October 2026

The user requested correction of all findings in **Taxonomy-QA-2026-10-17.pdf** after reviewing the QA result for `d9b911436a0e933f33828d00cecf3abeb2111a77`.
This is the acceptance contract for the corrections; measured outcomes belong in the implementation record.

| ID | Required behavior |
|---|---|
| QA-01 | Pull, publish and conflict resolution recheck current source-repository read/contribute authority before reading new source data or writing source Git, portfolio or metadata. An owned old workspace does not grant source authority. |
| QA-02 | Failed/missing product answers remain unassessed, with diagnostics; no fabricated zero/NOT_RELEVANT evidence in noncheckpoint, interactive, saved or exported results. |
| QA-03 | Completed product batches and reasons survive later rate limits/provider failures as well as cooperative cancellation. |
| QA-04 | Follow-up explanations and interactive expansion retain the selected analysis provider and authorized operation context; replay never silently becomes a live call. |
| QA-05 | Current central editor reads and supported exports return a read-only snapshot without a workspace-only journal key; writes remain denied. |
| QA-06 | Workspace comparisons use the selected repository for semantic/raw diffs and commit resolution. |
| QA-07 | HTML reports escape untrusted prose, labels and table content; literal markup remains text and links use safe schemes. |
| QA-08 | SVG/PNG labels remain inside bounds, including wide glyphs and long unbroken labels; full labels remain recoverable. |
| QA-09 | Current diagram exports preserve relationship identity/origin/reason/confidence, show provisional relationships distinctly and provide truthful counts/legends without inventing human approval. |
| QA-10 | Word retains distinct matched/proposed recommendations and every suggested relation/rationale, consistent with text reports. |
| QA-11 | Main full-diagram PDF exports normalize model bounds independently of live pan/zoom. |
| QA-12 | Explicit zero assessments remain exportable in JSON/CSV, including legacy files without coverage. |
| QA-13 | Canonical taxonomy DSL types, root codes and display-layer names share category colors across SVG/PDF/Visio. |
| QA-14 | Local login works in German/English, including query language, failed login, CSRF, redirect and servlet context path. |
| QA-15 | All twelve orphaned validator regressions have a JUnit/Maven execution owner; additions cannot silently go unselected. |
| QA-16 | Published report counts are checked against actual testcase outcomes, including nested suites; inconsistent evidence fails before publication. |

Additional identified work: retain all existing tests while making JUnit/Maven ownership explicit; reduce the measured core/coverage critical-path overhead without relaxing thresholds or moving failures out of required statuses; add historical Oracle NVARCHAR/interrupted-DDL migration fixtures. Any operational suspension of the public Render service is distinct from code correctness and must not be silently overridden.

Constraints: Java 21; existing Maven reactor and required CI statuses; all new regression coverage is JUnit. No new workflows, blanket exclusions, reduced coverage floors, fake green states, live paid LLM calls or changes to unrelated user work. Keep QA logs/generated evidence out of Git.
