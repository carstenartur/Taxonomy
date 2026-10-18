# Authored scenario exclusion storage

`flood-relation-exclusions.json` is a test-only, authored decision corpus. It is not a recording from a live LLM. Its 383 rejection rules and 14 explicit open scopes stay individually inspectable.

Schema 2 stores repeated explanation text once under a named `rationales` entry. A rule chooses either `rationaleRef` or its own complete `rationale`, never both. Scope lists stay next to each decision; a shared explanation never adds sources, candidates or descendants. Small scalar lists use one line.

The three explicit defaults are `phases`, `sourceContributionIds` and `candidateIds`. Each is copied before the rule's explicit fields are applied. Sources and candidate subtrees cannot be defaults. A `candidateIds` default must be an empty array, so it cannot add exact targets outside an individual rule's authored subtree. For this corpus every original rule used both NAVIGATE and VERIFY; the default does not infer a new phase.

`ScenarioExclusionResource.expand` reconstructs schema 1 for the existing semantic validator. Unknown rationale references, conflicting inline/shared reasons and unsupported defaults fail. The active scenario starts using this resource in a separate PR.

The migration independently expanded every entry and compared the full JSON structure with the recovered version, preserving order, all scope members and exact wording. Its canonical SHA-256 was `3df6cebc4301db5b6e92d95c4194f4f0c3f9cd8d4ce7fa8852a2615a2f1eaa9e`. Subsequently, three explanations received neutral public-service wording to satisfy the existing maintained-surface naming contract. The regression test pins the complete post-edit expanded corpus, not a weaker list of positive edges. No scope or decision changed.

To edit a shared reason, inspect every referencing rule and run:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test -Ptest-local \
  -Dtest=ScenarioExclusionResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

A deliberate judgment change requires reviewing and updating the canonical digest. The digest proves storage/content stability; it does not certify the architectural correctness of an authored judgment or complete prompt-path coverage.
