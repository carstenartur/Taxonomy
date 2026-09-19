# Sparx integration implementation plan

> Execution: superpowers:executing-plans, with one independent final review.

**Goal:** Implement the safe, reviewed Sparx round-trip requested by #1075.
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

- [ ] Add `SparxMappingProfile` and `SparxXmiCodec` under `taxonomy-export`.
- [ ] First write `SparxXmiCodecTest`: deterministic IDs, fixture round-trip,
  rename/move, relationships, requirements, losses, duplicate identity and XML attacks.
- [ ] Observe failing tests, implement and run the same cases again.
- [ ] Commit the tested mapping/codec and provenance-labeled fixture.

### Task 2: Reviewed application and outbound binding

- [ ] Add `SparxIntegrationDescriptor` and `SparxXmiConnector` under `taxonomy-interop`.
- [ ] Extend shared domain adaptation for the profile without changing authoring types.
- [ ] Add projection/diff and application tests for re-import, local edits, stale
  preview, UUID binding, package hierarchy and required project context.
- [ ] Register file selection and explicit DE/EN capability/compatibility guidance.
- [ ] Commit the tested application integration.

### Task 3: OSLC contract and compatibility boundary

- [ ] Inspect official PCS discovery, authentication, resource and conditional-write contracts.
- [ ] Implement only capabilities supported by the documented provider contract,
  using the existing scoped transport and canonical snapshot/diff types.
- [ ] Exercise partial responses, stale remote state, transport failure and recovery.
- [ ] Record any unavailable product/conditional-write evidence as an explicit
  delivery gap; never advertise an unimplemented or unverified write capability.

### Task 4: Evidence and delivery

- [ ] Add EN/DE mapping, operations, loss, deletion, layout and recovery guides.
- [ ] Run targeted regressions and `./mvnw verify -DexcludedGroups="real-llm"`.
- [ ] Have an independent reviewer inspect the branch and fix material findings.
- [ ] Push reviewable commits and open PRs without prematurely closing #1075.
