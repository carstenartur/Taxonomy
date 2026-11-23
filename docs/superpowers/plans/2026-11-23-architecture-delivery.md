# Architecture delivery implementation plan

Implement the [approved workflow design](../specs/2026-11-23-architecture-delivery-design.md) in four independently testable steps.

- [x] Extend ArchitectureBrief and ArchitectureLifecycleService with atomic scope replacement. Test exact quote and portable version round-trips, rejection of stale and foreign requirements, and historical reads. Provide selection and review in the existing editor.
- [x] Extend the journal-backed ADMBw service and browser preparation panel with scoped operation recovery. Test response loss, reload, inaccessible operations and basis replacement.
- [x] Route supported assessment findings to existing forms for the exact affected object. Invalidate previews and reviews after changes. Test current and historical states and unknown codes.
- [x] Share native preflight/export validation through the existing plugin boundary. Test identical failures for duplicate targets, invalid root or owner, and changed basis. Add bilingual user help and run the repository's focused, browser and integration gates when available.

Use Java 25, the Maven wrapper and existing browser fixtures. Record verification and unavailable external prerequisites in the local QA report.

## Verification status

The integrated local reactor passed 155 focused tests covering the DSL, application services, export controller and API permissions. The Node state suite passed 86 checks. The real-browser regression for a late transport response and journal recovery requires the hosted browser shard. External database CI, independent architecture review, native Enterprise Architect inspection and live model quality are separate acceptance activities.
