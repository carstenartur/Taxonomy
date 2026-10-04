# Contextual decision exports

## Goal and approved direction

Users need a short decision document for one or several taxonomies as well as the
existing complete evidence report. The approved conversation asks for complete
decision trees on landscape pages, a shorter contents list, and an ergonomic,
context-sensitive entry point instead of additional format buttons.

## Evidence and scope

Analysis scope and report selection are different. Preserve the frozen analysis
scope (including TAXONOMIES_ONLY), coverage and source identity. Assess completeness
only inside the actual analysis scope. Selecting a smaller report must not promote
an incomplete analysis to a completed one. Missing, zero and omitted evidence remain
distinct. Legacy scope stays explicitly unknown; it is not retroactively invented.

The report generator resolves taxonomy membership from the supplied frozen hierarchy
or the current hierarchy for explicitly ad-hoc reports, never from code prefixes.
Unknown roots and roots outside a recorded analysis scope are rejected. The report
retains the source fingerprint and separately records its presentation selection.
The export never invokes an LLM and never mutates the analysis or catalogue.

## One shared selection contract

Use a Java record and enums for profile, sections, contents and tree layout. Profiles:
FULL (backward-compatible default), STANDARD and COMPACT. Sections: TITLE_PAGE,
SUMMARY, TREE, CHAPTERS, ARCHITECTURE, EVIDENCE. Mandatory source/scope/status notices
remain visible even when optional sections are omitted. Compact defaults to summary
and complete trees with essential reasons/open findings; standard adds chapters;
full retains the existing evidence sections. JSON retains all selected decision
evidence and records the options; pagination settings do not discard machine data.

The selection is shared by ad-hoc DOCX/HTML/JSON and saved-snapshot report routes.
Old routes and Java constructors keep their full-report behavior. Snapshot format
adapters use the same frozen decision source. Optional architecture sections use
the same saved graph and explicitly retain cross-boundary context; they never
silently present an unfiltered whole graph as the selected result.

## Word and HTML layout

The complete decision tree contains every assessed node, the ancestors needed to
understand it and all direct alternatives at assessed positive decisions, including
zero and missing alternatives. It is independent of browser zoom/collapse and does
not imply that every unvisited catalogue descendant has been assessed.

Each selected root starts on its own landscape page in graphical mode. A4 and A3
are explicit choices; automatic readable pagination is the default for compact
reports. A strict single-page mode fails with an actionable layout message if it
cannot retain legible labels; automatic mode preserves every node across clearly
labelled continuation pages. Do not truncate codes, omit nodes or shrink below 8pt.
The initial summary and later prose retain portrait layout. Native section breaks,
heading styles, bookmarks, field-based contents and inherited template headers and
footers must continue working. Full existing template behavior remains the default;
omitting the cover removes only the document body before the validated body marker.

HTML uses the same selected evidence and profiles, embeds deterministic tree SVGs
and includes visible source/scope/status plus accessible text descriptions.

## Interaction

Replace the three decision-format buttons with one “Decision report…” action.
Use the same accessible dialog in the analysis workbench, saved requirement detail
and snapshot architecture view. Context preselects the saved analysis and, where
available, the current taxonomy; users can see and change the roots explicitly.
The dialog starts with profile, taxonomy selection and format. A disclosure holds
contents, tree layout and optional sections. Unsupported options are hidden or
explained for the chosen format/source. Preserve focus, Escape, responsive layout,
an inline error, a disabled submit while downloading and the selected settings after
failure. Cancellation performs no request. An empty explicit selection is invalid.
No additional permanent export settings are scattered across the export page.

## Other formats

Audit the existing model/image/exchange exports against current analysis scope,
coverage and frozen-source contracts. Preserve interchange schemas and loss reports.
CSV must identify recorded analysis scope/mode and distinguish unknown coverage from
rejection. Document which formats represent decision evidence, architectural models
or a full analysis exchange; presentation profiles are not backup filters.

## Verification

Java 21 and the existing Maven reactor; no new production dependencies. Focused
regressions must fail before implementation. Test scoped completeness, membership
with misleading code prefixes, omitted/failing roots, selected summary and tree
coverage, source identity, all-zero roots, missing children and historic snapshots.
Validate invalid API options and backwards-compatible defaults. Inspect generated
Word XML and render representative compact/full/large documents to verify page
orientation, readable labels, all nodes and working navigation. Exercise the dialog
at desktop and narrow widths, keyboard dismissal, invalid selection, failure and
successful downloads. Run existing report/UI contracts and the module boundary gate.
