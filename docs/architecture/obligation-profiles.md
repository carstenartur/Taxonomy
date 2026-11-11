# Relation legality and scoped obligation profiles

`com.taxonomy.dsl.validation.RelationMetamodel` in the framework-free DSL module
owns relation legality, version `1.0.0`. `TargetDomain` distinguishes
`UNRESTRICTED`, `RESTRICTED` and `FORBIDDEN`. `RELATED_TO` is explicitly
unrestricted. A forbidden source stops candidate retrieval and proposal work.
A permitted relation does not require that a relation exists. Automatic structural
discovery explicitly excludes generic `RELATED_TO` links through its discovery
policy; this is a selection policy, not a restriction on explicit candidate queries.

The editor schema exposes `metamodelVersion` and `relationDomains`. Each relation
has a `defaultDomain` and `sourceDomains`. Each domain has `state` and `roots`;
only restricted domains contain roots. Consumers must inspect the state rather
than interpreting an empty collection as unrestricted. Unknown relation types
are forbidden by the shared contract.

## Authored profiles

`GET /api/gap/profiles` discovers the exact available IDs, versions, descriptions
and rules. Clients send the selected ID and version; an unknown version is a
bad request rather than a silently substituted profile.

- `compatibility`, version `1.0.0`, has no business obligations.
- `service-support`, version `1.0.0`, is an **opt-in authored business assumption**:
  each Core Service in the selected model supports at least one Business Process
  **or** Business Role. It is not an external standard or a universal requirement.

A rule declares its ID, applicable source roots, relation type, alternative target
roots, minimum and optional maximum cardinality, severity and required evidence.
Cardinality counts distinct accepted target elements across the union of target
alternatives, not one requirement per permitted root. Proposed relations and
relevance scores never establish accepted relation evidence.

## HTTP scope and assessment

The gap and recommendation POST endpoints take `repositoryId`, `branch`, full
`commitId` and an explicit `workspaceId` as query parameters. The existing
`X-Taxonomy-Workspace-Id` header can provide workspace selection instead. Empty
workspace selection means central. The application's existing read resolver
checks authorization and the selected tuple. No workspace is provisioned.

`POST /api/gap/analyze` accepts:

```json
{
  "profileId": "service-support",
  "profileVersion": "1.0.0",
  "evidenceCompleteness": "COMPLETE",
  "businessText": "Assessment context"
}
```

`POST /api/recommend` uses the same selection and body fields, plus optional
`scores` and `minScore` for ranking. Membership and relations are always read from
the selected canonical revision and its verified relation projection. Client
supplied scores cannot add accepted members. Recommendation candidates prefer
eligible existing model elements before proposing extra catalogue elements.

`COMPLETE` means a complete assessment of this exact model and selected profile,
never completeness of the real-world enterprise. It is honoured only after the
server proves the projection revision and reads canonical elements at that
revision. Unknown canonical types or unresolved relation endpoints downgrade the
assessment to `INCOMPLETE`. Every incomplete rule judgment is conservatively
`INCONCLUSIVE`, including cases with partial positive evidence. Complete
assessments return `SATISFIED` or `VIOLATED` for each applicable rule. Head movement,
stale projections and unreadable evidence fail the request; they never become an
empty successful assessment.

Results carry profile/metamodel versions, repository/workspace/branch/revision,
evidence authority/completeness and individual judgments. `totalGaps` counts only
`VIOLATED` judgments. Default retained Copilot/report analysis uses the
compatibility profile, has no invented gaps, and identifies its retained evidence
as incomplete. Copilot schema version is now `2`.

Recommendation DTOs distinguish `matchedElements` (relevance ranking),
`acceptedElements` (selected canonical membership) and `proposedElements`.
`meanRelevance` is a ranking statistic, not confidence in architectural quality;
it is null when no candidates were ranked.
The old confirmed-element and confidence fields are removed.

## APQC inventory

`GET /api/gap/apqc-coverage` uses the same selected query scope. It observes only
canonical elements with explicit `x-source-framework: "apqc"` metadata, groups
by `x-apqc-level`, and counts their accepted links inside the selected model.
Names, relation descriptions and unrelated materialization provenance do not
establish APQC origin. With no explicit APQC evidence, the response is
`INCONCLUSIVE` and `linkedPercent` is null. With evidence, `OBSERVED` reports a
bounded inventory, not universal APQC completeness or business violations.

This replaces the previous global-row coverage calculation and its ambiguous
category denominator. No compatibility adapter for the obsolete gap or
recommendation contracts is retained.
