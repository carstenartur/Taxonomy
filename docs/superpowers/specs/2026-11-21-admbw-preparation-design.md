# Guided ADMBw preparation

## Goal and scope

Carsten requested better architecture quality, an ADMBw-compatible outcome and a verified Architecture Focus workflow. This change makes the supported C1–S1 / R7 authoring step usable in the editor. It does not claim independent expert acceptance or complete ADMBw method coverage.

The existing source catalogue, frozen analysis views, requirement text/version, accepted relations and whole-model coverage remain authoritative. In particular, a C3 capability is not the same object as a similarly named element in a QEA basis. SUPPORTS is not converted into FULFILLS.

## Decisions

- The Sparx extension inspects a supplied, closed QEA basis read-only, using the same strict native identity and qualified stereotype checks as its writer. It returns the SHA-256, actual model roots and bounded reference choices. Names alone never establish identity. The public MASC basis is identified as an example, not an official catalogue/crosswalk.
- A guided form selects an existing canonical service and requirement, a real native capability and operational subject, and an explicit requirement classification. A proposed operational constraint retains its exact requirement source; the preview discloses when this is a restatement rather than an independent upstream driver.
- A deterministic planner builds typed additive commands and a dedicated delivery view. It does not mutate frozen source views or hide other missing assignments. Existing reviewed assignments cannot silently be replaced.
- Preview includes the exact semantic changes, requirement version/hash, basis hash, selected source identities and the resulting supported-profile assessment/whole-model coverage.
- Applying requires explicit review of both the service/capability relationship and requirement derivation, with a rationale. It revalidates the preview under the workspace lock. Existing integration journal/idempotency and checkpoint recovery are reused; a transport retry does not apply twice. Stale source, changed basis or altered choices require a new preview.
- The UI exposes real labels and paths, not raw GUID entry. Mobile controls wrap. Review choices reset when their inputs/context change. Existing native-export checks still decide whether a QEA copy can be produced.

## Boundaries and verification

The extension API owns transport-neutral inspection records; SQLite remains inside the Sparx plugin. The DSL layer owns pure source-to-command planning. Spring composition owns authentication, exact context/ETag, extension leases and journal/checkpoint orchestration. Frontend code owns selection and preview/review states, never canonical trust decisions.

Targeted meaningful tests cover hostile/mismatched basis identities, source version binding, no fake C3 crosswalk, preservation of analysis views/relations, failed review and stale preview, atomicity/idempotent retry, whole-model coverage and the real browser workflow. Maven retains verification ownership. A selected suite is not a full CI gate. Native EA visual inspection and independent architecture acceptance remain separate evidence.
