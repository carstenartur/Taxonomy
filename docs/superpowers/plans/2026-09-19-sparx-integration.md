# Sparx integration implementation plan

> Execution: superpowers:executing-plans, with one independent final review.

**Goal:** Implement the safe, reviewed Sparx round-trip requested by #1075.
**Delivery status:** Experimental XMI slice implemented. OSLC AM and real-product
acceptance remain open; this delivery must not close #1075.
**Architecture:** Extend the existing exchange SPI and reconciliation authority.
**Tech stack:** Java 21, bounded DOM parsing, Spring integration services, JUnit.
**Spec:** `docs/adr/0009-sparx-integration.md` and GitHub issue #1075.

## Global constraints

No proprietary types in the domain; no name-based identity; no last-write-wins;
no deletion from incomplete discovery; no invented real-product evidence;
no weakened repository verification or coverage gates.

## Review focus

GUID aliases and reused taxonomy UUIDs must fail before mutation.
Partial reviews must not leave orphaned relations or package occurrences.
An exported native element must bind to itself when its file returns.
Layout-only changes must not appear as semantic operations.
Unsupported EA metadata must never be silently interpreted or discarded.

### Task 1: Pure mapping and XMI codec

- [x] Add `SparxMappingProfile` and `SparxXmiCodec` under `taxonomy-export`.
- [x] First write `SparxXmiCodecTest`: deterministic IDs, fixture round-trip,
  rename/move, relationships, requirements, losses, duplicate identity and XML attacks.
- [x] Observe failing tests, implement and run the same cases again.
- [x] Commit the tested mapping/codec and provenance-labeled fixture (PR #1084).

### Task 2: Reviewed application and outbound binding

- [x] Add `SparxIntegrationDescriptor` and `SparxXmiConnector` under `taxonomy-interop`.
- [x] Extend shared domain adaptation for the profile without changing authoring types.
- [x] Add projection/diff and application tests for re-import, local edits, stale
  preview, UUID binding, package hierarchy and required project context.
- [x] Register file selection and explicit DE/EN capability/compatibility guidance.
- [x] Commit the tested application integration.

### Task 3: OSLC contract and compatibility boundary

- [x] Inspect official PCS discovery, authentication, resource and conditional-write contracts.
- [ ] Implement only capabilities supported by the documented provider contract,
  using the existing scoped transport and canonical snapshot/diff types.
- [ ] Exercise partial responses, stale remote state, transport failure and recovery.
- [x] Record any unavailable product/conditional-write evidence as an explicit
  delivery gap; never advertise an unimplemented or unverified write capability.

Blocked: the reviewed PCS documentation describes model-token authentication and
proprietary POST updates, but does not establish atomic expected-version writes or
idempotent creation. No EA/PCS instance was available to prove these guarantees.
The generic RM connector cannot substitute for the AM contract. See the feature
guides and `docs/qa/sparx-compatibility.json` for the remaining acceptance work.

### Task 4: Evidence and delivery

- [x] Add EN/DE mapping, operations, loss, deletion, layout and recovery guides.
- [x] Run targeted regressions and `./mvnw verify -DexcludedGroups="real-llm"`.
- [x] Have an independent reviewer inspect the branch and fix material findings.
- [x] Prepare and publish reviewable commits for stacked draft PRs without closing #1075.

Validation: 56 focused tests passed. The complete gate ran 4,778 tests and failed
only in 9 existing ONNX cases because the required local model is absent and
downloads are disabled. Later integration/quality phases were not reached.
See `docs/qa/sparx-implementation-validation.md`; the full issue remains open.
