# Source hierarchy, classification and navigation

User-facing rationale and per-taxonomy pages: [English](../en/TAXONOMY_SCORING.md) / [Deutsch](../de/TAXONOMY_SCORING.md). These state the exact title-token grouping rules, current scoring transformations, singleton-root limitation and the still-unimplemented faceted target.

## Approved design and scope

Implementation of the hierarchy review in #1111. A precise child can rely on descriptions defined only at its ancestors. An inferred parent assignment is not proof that those descriptions apply. Do not compensate for a bad classification by silently capping, multiplying or overwriting scores.

The original workbook, the existing overlay, original IDs, historical snapshots and active architecture are not rewritten by this increment. The current allocation/product-score formulas are not endorsed or changed here. In particular, an independent suitability score is not thereby a conditional probability suitable for multiplication. Redesigning its semantics requires a versioned change across scoring, derived values, display and persistence rather than an isolated formula patch.

## Sub-taxonomy containers in the browser

`TaxonomyNodeDto.isTaxonomyContainer()` exposes computed, read-only presentation metadata. A container has a nonblank `code` equal to `taxonomyRoot`, `level == 0`, and no `parentCode`. The browser uses the explicit boolean when present, including `false`, and applies the same metadata predicate to older snapshots without it. Names, code prefixes and a `-1000` suffix do not establish this role. The role is independent of `analysisRole` and does not classify other local navigation groups.

List and Tabs render containers as labelled groups with native expansion buttons. Tree, Canvas, Sunburst and Decision Map build a separate content-root projection; the original child objects, parent references and ordering remain intact. Original top-level catalogue entries remain selectable and eligible for ranking. Area scores appear as separately labelled area relevance, outside the Decision Map ranking. CSV retains the evidence with an empty container rank and an additive `TaxonomyContainer` column; DOT and Mermaid tree exports use groups. JSON evidence retains the full tree and scores.

This presentation change does not migrate persistence, reparent catalogue entries, change score calculations or rewrite saved snapshots. The DTO getter is recomputed on JSON reads; an injected boolean cannot override catalogue metadata. Regression tests cover current and historical snapshot round-trips, both catalogue fingerprint versions and the typed score fingerprint. Browser regressions belong to the existing Maven-owned UI contract suite.

## Runtime context contract

`TaxonomyService.getPathToRoot` remains the ordinary navigation path. `getSemanticPathToRoot` returns the source-hierarchy suffix, stopping at an overlay-assigned parent. `reviewRequired=false` in the existing deterministic overlay is not treated as external semantic approval. A future reviewed semantic extension must carry explicit authority and scope rather than reuse that boolean.

`getAssessmentDescriptions` retains each candidate's own original description and its source-ancestor descriptions. The candidates may have different parents. Above an overlay-assignment boundary, ancestor restrictions are omitted and a classification/navigation-only notice is returned. Missing parents, cross-root paths and cycles fail explicitly; unknown initial IDs return an empty path as before. Resolution is cached only within one scalar batch and never across users, transactions or catalogue revisions.

The existing category/product prompt builder and relationship adapter share this context resolver. `getAssessmentContexts` separates context from the original candidate description, so identical sibling context is transmitted once per group. Different parent scopes remain distinct. Root-only assessments require no ancestor lookup and retain their existing diagnostic behavior. Remote calls start only after scalar catalogue reads return. There are no additional model calls and no new provider. More complete context can increase prompt tokens; the existing prompt-budget guard still applies, and oversized context is not silently summarized away.

This is not a claim that pruning on provisional parent assignments is solved. The bounded alternate access/recall step remains necessary before considering any rewritten IP navigation complete.

## Scoring meanings

For the same branch-relevance measure under genuine semantic containment, child relevance above parent relevance is a consistency finding. Retain original evidence and review the parent/context/assignment; do not silently lower the child. A lexical similarity score is a different measurement. Nor does child <= parent imply that siblings must sum to the parent: several children can be jointly needed.

The existing category path continues its declared parent-budget allocation behavior pending the coordinated scoring migration. The independent-product behavior is not generalized to BP or other categories. This change deliberately makes no unsupported claim that the old sum rule or product multiplication has been validated.

## Reproducible IP audit and local group proposals

The existing `CatalogueOverlayProposalGenerator` now includes `hierarchyAudit` in its review-only JSON and Markdown output. Algorithm version v3 adds the audit; the input overlay remains schema 2. Run the existing CLI after compiling taxonomy-tooling:

```sh
java -cp taxonomy-tooling/target/classes com.taxonomy.tooling.CatalogueOverlayProposalGenerator
```

The audit records raw workbook parent cells separately from resolved source references and active overlay parents. Every overlay assignment retains original description, state, original level, role, justification, secondary classifications and existing review flag. All own assignments require separate semantic review; structural validation is not semantic approval. Unchanged approved source hierarchy is not discarded.

`navigationGroupProposals` adds deterministic local entry points for titles mentioning hazards/warnings, reports, plans, requests or orders, only where actual source products match. Each has a stable `local:ip:navigation:*` identity, explicit `NAVIGATION_GROUP` kind, scope description and original member-code references. The lower-case display code is only a label, not the identity boundary. No original product is cloned, reparented or renumbered. Membership may overlap without duplicating identity.

These are explicitly lexical navigation proposals, not a completed semantic regrouping or 853 domain-expert approvals. Every group is review-required; none carries a relevance score or inferred confidence, inherits semantics or creates architecture elements. They are not yet inserted into the live browser tree. Unknown source references are rejected by the existing input validation. The generator cannot overwrite either authoritative input.

## Executable acceptance

- Real workbook BP ancestor descriptions remain in child prompts and now reach the relationship adapter.
- Real IP overlay assignments do not silently inherit restrictions, even with a deterministic accepted review flag.
- Mixed BP/CR candidate contexts remain separate; malformed semantic paths terminate visibly.
- Audit output is byte-identical for identical inputs, retains every overlay assignment and leaves both input files unchanged.
- All proposed groups have stable namespaced IDs, nonempty valid original references and explicit no-score/no-inheritance metadata.

Focused module tests are development evidence, not a substitute for the canonical `./mvnw verify -DexcludedGroups=real-llm` run. No baseline or test threshold is relaxed.
