# Relation source context

Policy `relation-downwalk-v7` includes `relation-source-context-v1` and
`relation-semantics-v2`. The procedure
keeps its normal call, candidate-count, and request-size limits. A large request
is never made acceptable by cutting a catalogue description or raising a limit.

## Evidence by phase

Source extraction receives the original requirement and each offered node's
complete own description plus its established semantic ancestor descriptions.
The model must extract the particular function requested by the requirement;
matching a catalogue category does not request all its features.

Once a source contribution has been extracted, navigation and verification carry
the same original requirement, exact contribution, quote and condition. The source
retains its complete own description and the IDs and names of its established
semantic ancestors. The previously assessed ancestors' prose is not repeated for
every counterpart. Each newly offered candidate still receives its complete own
description and the complete established ancestor context. Verification also keeps
the exact proposed claim, necessity and conditions.

This distinction matters for the flood scenario. In its recorded requests,
`BP-1140` has a 178-byte own description but 7,245 bytes with its ancestors;
`CR-1007` has 923 bytes of own description and 4,294 bytes with its ancestors.
The old singleton navigation request between them consumed 17,179 bytes including
the response schema, exceeding the normal 16,000 limit even after batching down
to one candidate. Reusing the already assessed source contribution makes room for
the new candidate without removing either endpoint's own description.

The hierarchy remains context. Its names cannot introduce additional requested
features. An uncertain function or product identity still requires `UNRESOLVED`
with a concrete question. The source-context change does not turn uncertainty into
a negative finding or make generic catalogue features into project requirements.

## Relationship semantics

Navigation and verification define the function of each relationship using its
canonical endpoints. `SUPPORTS`, `USES` and `DEPENDS_ON` do not narrow those
endpoints to services or business processes: the compatibility matrix also permits
support for business roles, role use of information products, system use, and
dependencies between systems or components. The complete offered node descriptions
determine the functions being connected. Generic technical usefulness and a
possible implementation decomposition still do not establish a required dependency.

The repository's passive `REALIZES` convention remains Capability → Service.
Both phases retain the same direction rule: `OUTGOING` places the assessed source
at the canonical source; `INCOMING` places the offered candidate there. The meaning
version and recovery fingerprint change together, so answers obtained with the
earlier, contradictory endpoint restrictions cannot be replayed as current answers.

## Capture and continuation

`TaxonomyService.getRelationAssessmentDescriptions` creates both representations
from structured catalogue nodes and the semantic path in one capture. It never
parses a marker out of free-form description text. An overlay parent remains a
classification/navigation assignment, not inherited source meaning; the existing
explicit overlay notice is present in both representations.

`RelationSearchModel.Node.description` keeps the full description. The optional
`assessedSourceDescription` is retained with source assessments and distributed
plans, so a resumed run reconstructs the same provider prompt without reading a
new source catalogue. This additional internal field is not sent to the provider:
offered nodes retain the established five-field protocol representation.

The five-argument node constructor and JSON records without the new field remain
readable. Such legacy nodes retain their entire original description; there is no
text-parsing fallback. The changed recovery-policy fingerprint prevents existing
journals created under the previous prompt strategy from being reused as if their
questions had not changed.

The typed ArchiMate relationship provenance preserves the captured source form
through the optional node property `assessedSourceDescription`. Evidence with
this property uses provenance version 2; the reader accepts versions 1 and 2.
Legacy nodes without it continue to produce the unchanged version 1 shape. Full
catalogue descriptions, the optional captured form at either endpoint, original
quotes, scoped contributions and conditions survive repeated XML round trips.
The same typed properties are retained by the Sparx profile 3 transport.

Sparx profile 3 also reconciles the model-level evidence referenced by the current
native objects on every export, including exports with a previous integration
checkpoint. Earlier model values are retained only while uncontradicted. Different
requirement texts, hashes or snapshot identities are removed from the common
model metadata and reported as explicit `EXCHANGE_MODEL_EVIDENCE_CONFLICT` losses;
neither source is silently chosen. Previous model identity, title, external version
and nonconflicting metadata remain intact. Stored imports without referenced native
objects cannot introduce their requirement text into an unrelated export.
The same reconciled metadata is applied to an already bound `METADATA:package`
item as well as to the export template. A previous file delivery therefore cannot
overwrite new source provenance or reintroduce rejected conflicting values when
the exchange document is expanded. Accepted identities and their stored evidence
remain unchanged; unknown, nonconflicting model metadata is retained.

`RelationSearchProtocol.requestCharacters` counts both prompt and schema envelope,
using the larger of code-point count and UTF-8 byte count. A source whose complete
own description plus a complete candidate still exceeds the normal request limit
remains explicitly unassessed. This policy is not a guarantee that every possible
catalogue/requirement combination fits one request.

## Regression evidence

`RelationMeaningMatrixTest` sends actual navigation and verification requests for
every allowed root pair of `SUPPORTS`, `USES`, `DEPENDS_ON` and `REALIZES`, in both
orientations. It checks functional meanings, canonical endpoints, exact context and
proposal preservation, and the prompt/recovery versions. Its endpoint restrictions
and version checks fail against the earlier implementation. The scenario prompt
evidence gate also rejects a request carrying the earlier meaning version.

`RelationSourceContextBudgetTest` uses the real workbook and overlay. It covers
the recorded oversized authorization, information-security, service-operation and
notification endpoints, both navigation and verification, full extraction context,
unchanged candidate descriptions, exact contribution/quote/condition/proposal,
semantic ancestor identities, overlay boundaries, description text containing the
old context marker, JSON reload, legacy records, and the versioned policy identity.
The initially failing budget/context tests passed after the phase separation.
`ArchiMateProvenanceRoundtripTest` covers versioned exchange of source-only,
target-only and both captured contexts as well as legacy evidence.
`SparxV3ProjectionTest` covers conflicting current imports with and without a
previous exchange, stable model identity and unrelated stored import evidence.
Its retained-mapping cases write and read the first actual Sparx file, obtain the
public export bindings, and reload their identity records through JSON before
adding further native evidence. They verify both an earlier unacknowledged file
delivery and a retained integration checkpoint.
