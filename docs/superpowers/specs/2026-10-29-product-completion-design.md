# Complete the retained architecture decision workflow

## Goal and authorization

The user asked to execute the remaining work identified on 29 October 2026: repair the integrated main build and dependency updates, complete relationship/variant evaluation, qualify the actual user journey, and finish the supported recovery/export paths before release. Existing implementation and review decisions remain authoritative; this document records implementation choices made to carry out that instruction.

## Architecture

Keep the existing single application and module ownership. Immutable requirement analyses, inventory comparisons and reviews remain evidence; existing project solutions are domain variants, and the architecture journal/Git checkpoints remain the editable model history. Add explicit relationship evaluation and frozen project-solution assessments to the existing inventory journal. Do not create a parallel portfolio, variant repository, history store or application.

Three approaches were considered: a new independent variant subsystem; extending generic Git branch comparison with business metadata; and extending retained inventory comparison using existing project solutions. The third is selected because it preserves the actual requirement/snapshot/decision identities, current authorization and established cost/risk/action fields. The other two duplicate ownership or conflate architecture versions with solution decisions. This selection is an implementation judgment under the user's execution instruction.

## Required behavior

- An explicit relation requirement evaluates exact endpoint identities, direction, relation kind, required interface/constraint, context and time. Absent or unmapped evidence stays unknown; an unrequested check stays unexamined. Dependencies, interfaces, alternatives and exclusions do not imply one another.
- Source observations retain relation identities and provenance. Only explicit configured source projections and reviewed mappings produce semantic relation evidence. A missing source edge never proves incompatibility.
- Compare existing project solutions side by side, with retained action, capability/availability findings, relationship findings, prerequisites, risks, cited cost assumptions, evidence and open questions. Unknown cost stays absent and unlike currencies/bases are not added.
- Review is deliberate and reasoned. Hard contradictions/exclusions prevent selection. Human acceptance does not become automatic fulfillment or procurement.
- Retained comparison/review identity survives reopen, JSON export, existing backup capture and Git decision materialization. Older JSON remains readable without recalculating its historical fingerprint.
- Revalidate inputs under the same authoritative lock order as writers. Concurrent requirement, solution, coverage, link, candidate and evidence changes cannot commit unnoticed between validation and application. Identical retries apply once; changed reuse conflicts.
- Use existing English/German project/requirement views, accessible controls, escaped text, and existing Maven-owned browser tests.
- Export/recovery changes have their own bounded profiles and explicit losses. Do not silently weaken the strict interchange reader or enable incomplete restore owners.

## Verification and acceptance

Keep Java 25, the Maven wrapper, current selectors, timeouts, coverage thresholds and security gates. Reuse verified RED/GREEN repair evidence, then require current published-head CI. New domain behavior requires semantic negative tests, real HSQL/JPA concurrency checks where mutable decisions are applied, and native browser acceptance of the integrated workflow.

Configured provider runs, authorized LeanIX/Alfabet installations and licensed Microsoft Visio must supply their actual evidence. Missing access is recorded as unexecuted, never a quality pass. A human-quality verdict must identify an actual human reviewer. No credentials are created or disclosed by this work.

## Release boundary

Merge only reviewed source with successful applicable checks, recheck integrated main and delivery, then use the repository's existing release workflow. A source commit, locally passing selection, downloadable artifact and published release are different states and are reported separately.
