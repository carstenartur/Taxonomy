# Editable Visio handoff with requirement provenance

## Intent and authority

The user requested implementation of the reviewed Visio improvements, plus visible
original requirement text, its editor, creation dates, generating software/name
and version, and a safe link back to the requirement's editing page. The existing
saved-snapshot export remains the source of authority. No new analysis is run.

## Design

- Export native editable shapes and connectors with explicit formula dependencies
  on real connection points at shape boundaries. Use documented static point glue
  rather than assuming that Connect records or undocumented walking formulas
  establish attachment. Moving/resizing a node must preserve its attachment;
  automatic migration to another side is not claimed.
- Include a small set of original native masters for process/block, role,
  application/component, information/document, service and container shapes, plus
  a connector. Select by the recorded semantic type, preserving unknown types as
  generic blocks. Never invent physical server/database meaning for services.
- Use native container membership only for explicitly visual container parents.
  Retain original identifiers and semantic graph fingerprints independently of
  presentation geometry. Include required master parts and valid references.
- Keep the diagram as the first page. Add an editable title/context block to
  diagram pages. Show a bounded requirement excerpt there; include the complete
  frozen requirement text on mandatory paginated context pages, with a clear
  continuation reference. Never silently shorten the complete text.
- Visible context distinguishes the author/date of the frozen requirement version,
  the analysis snapshot date, and the current exporting user/export time. Include
  requirement key/version, project, software name and actual build version.
  Unknown historical values remain explicitly unavailable.
- Store the same allowlisted values as native Shape Data/document properties.
  Preserve existing readable evidence pages and links. The user explicitly
  authorizes including the frozen requirement text in the downloaded artifact;
  prompts, raw provider payloads, warnings and credentials remain excluded.
- Existing unsaved/current-view exports also show server-owned software/user/time
  and the captured source text when available. They explicitly have no saved
  requirement link or immutable-version authority. Never substitute a currently
  edited textarea for the text used to build the view.
- Build the requirement URL solely from operator-configured
  `taxonomy.export.public-base-url` / `TAXONOMY_EXPORT_PUBLIC_BASE_URL` and numeric
  project/requirement IDs. Require an absolute HTTP(S) URL without credentials,
  query, fragment, control characters or ambiguous path syntax; preserve a valid
  deployment context path. Do not trust request Host/Forwarded headers.
- Link to `/projects/{projectId}/requirements/{requirementId}` using native Visio
  Hyperlink data (literal values, not formulas). Missing public base configuration
  yields a visible unavailable-link notice, never a file-relative or guessed URL.
  Authentication, role checks and exact project/requirement/workspace scope remain
  mandatory. The URL conveys identity only and grants no access or automatic
  workspace switch. Record the source workspace/branch beside the link.
- Retain immutable snapshot timestamps. Add a truthful export timestamp supplied
  once through an explicit context/Clock. Serialization is reproducible for the
  same snapshot and export context; separate exports may differ in creator/time.

## Approaches considered

Pure connection-record validation leaves live dependencies unproven. Undocumented
walking-glue formulas are too fragile. Documented connection-point formulas plus
native masters provide a bounded implementation and a clear desktop acceptance
test. A server-side Microsoft Visio dependency is unnecessary. Native document
editing and a requirement deep link meet this request; automatic VSDX write-back
into Taxonomy is outside it.

## Validation and limits

Use Java 25 and the checked-in Maven Wrapper. Extend existing export and portfolio
tests with dependency/mapping assertions, malformed reference rejection, full
text pagination, immutable-version authority and URL/security cases. Preserve
existing page/text/shape/byte limits; adjust only the derived OPC part count for
master and page relationship parts. Run affected module and endpoint/security
tests, then the canonical CI command where prerequisites allow.

Microsoft Visio is unavailable in this environment. Do not claim desktop
certification from XML, Apache POI or LibreOffice evidence. The remaining product
check is opening, moving both ends, resizing, rerouting, editing text, saving,
closing and reopening in a supported Microsoft Visio desktop version without
repair, lost metadata or detached endpoints.
