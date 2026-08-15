# Multi-repository preview boundary

Taxonomy contains an integrated technical foundation for a repository catalogue, personal working copies, central forks and repository membership. This foundation is needed for continued development of the multi-repository programme, but it is **not a supported production capability in Taxonomy 1.4.0**.

## Default behavior

The public repository catalogue API under `/api/repositories/**` is disabled unless an operator explicitly opts in:

```text
TAXONOMY_FEATURES_MULTI_REPOSITORY_API_ENABLED=true
```

The equivalent Spring property is:

```properties
taxonomy.features.multi-repository-api.enabled=true
```

When the property is absent or `false`:

- `ArchitectureRepositoryController` is not created;
- repository catalogue, central-repository creation, working-copy creation, fork creation and membership endpoints are not mapped;
- those endpoints are absent from generated OpenAPI documentation;
- the internal primary-repository catalogue and existing supported single-repository/workspace infrastructure continue to operate.

## Supported 1.4.0 boundary

Taxonomy 1.4.0 supports the established primary-repository and personal-workspace behavior. The integrated multi-repository code is retained as a development foundation, but the public API remains opt-in until the completion programme in #609 and blocker groups #741–#749 are finished.

In particular, production enablement still requires complete evidence for:

- readiness-checked repository-owned reads;
- durable retry and projection recovery;
- requirement, portfolio, decision and audit tenancy;
- cache, search, embedding and index-lifecycle isolation;
- exact branch, commit and stale-state identity;
- repository selector and context UX;
- ancestry-preserving clone, fork and incremental transfer;
- organization visibility and membership enforcement;
- one complete cross-repository end-to-end acceptance package.

## Evaluation use only

Explicit activation is intended only for isolated development and evaluation environments containing no confidential or cross-organization production data. The feature flag does not replace tenant-isolation, authorization, recovery or readiness controls. Operators who enable it must treat all affected endpoints as experimental and must not describe the resulting topology as a supported 1.4.0 production configuration.

The release notes must describe this boundary accurately: the technical foundation is integrated, while the public multi-repository product capability is disabled by default and remains unfinished.
