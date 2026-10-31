# VSDX handoff profile and acceptance

Status: **experimental, Microsoft Visio desktop certification pending**
([external TODO EXT-04](../internal/TODO-EXTERNAL-INPUT.md#ext-04), historical
[#965](https://github.com/carstenartur/Taxonomy/issues/965)). The format is intended
for an editable visual handoff. It does not replace canonical snapshots or
semantic interchange through ArchiMate Exchange and authorized JSON evidence.
No supported Microsoft Visio desktop version has been certified yet.

## Download and authority

In the Architecture Workbench, select the persisted snapshot, verify its displayed snapshot/commit identity and export profile, then choose **Download Visio + manifest**. The authenticated endpoint is `GET /api/projects/{projectId}/architecture-workbench/{snapshotId}.visio.zip`. It loads the authorized immutable projection and performs no new analysis. The existing `.vsdx` endpoint returns the same native diagram bytes.

The bundle contains:

| File | Contract |
| --- | --- |
| `diagram.vsdx` | Validated OPC package with literal typed Shape Data, core/extended/custom document properties and embedded handoff/profile JSON |
| `mapping-profile.json` | Exact executable profile `visio-2012-opc-supported-subset-v3` |
| `manifest.json` | SHA-256 of the VSDX and profile, the same embedded handoff manifest, page/object mapping and explicit losses |

The VSDX embeds `taxonomy/manifest.json` and `taxonomy/mapping-profile.json` through declared OPC relationships and content types. The inner manifest does not hash its enclosing VSDX, avoiding a recursive checksum. Keep the original outer bundle when saving edits in another application: preservation of custom parts by third-party writers is not certified, and its checksum describes the original file only.

Repository, workspace, branch, authoritative commit, requirement/version, snapshot identity/status/creation time and taxonomy fingerprint come from the authorized persisted projection. The original requirement text, its author and its creation date come from the **exact requirement version referenced by that snapshot**, even if a newer requirement version exists. Missing historical fields are reported as omitted; current repository values are not substituted. A selection-profile fingerprint is currently unavailable and explicitly omitted. Relevance is a normalized selection score in `[0,1]`, not a probability.

The diagram shows the requirement identity, original text excerpt, version author/date, analysis date, export actor/date, software name/version and source workspace/branch in an editable context block. Mandatory **Requirement** pages contain the complete original text as native editable text. The complete source also remains in `taxonomy.requirementText`. Long source text is paginated; exceeding the supported capacity fails explicitly instead of discarding text. All diagram pages, including optional relationship details, carry the same context. The build version comes from the application's build metadata.

`exportedAt` records the actual creation time captured once per export, and `exportedBy` records the authenticated exporting user. These are distinct from historical `requirementVersionCreatedAt` / `requirementVersionCreatedBy` and `snapshotCreatedAt`. For compatibility, `generatedAt` remains normalized to `snapshotCreatedAt`, with `timestampPolicy=SOURCE_SNAPSHOT_CREATED_AT`; it is not the wall-clock download time. Serialization is reproducible for an identical graph, authority and export context. Independently requested downloads can have different timestamps and checksums.

Working-view exports also carry application identity and export actor/time. Their original text is captured from the analysis input associated with that view; a later edit to the input box is not substituted. These working views explicitly identify client-provided source text, have no authoritative saved requirement version, and do not invent a persistent editing link.

## Safe return to the requirement

Set the deployment's trusted public origin, optionally with its deployment prefix:

```properties
taxonomy.export.public-base-url=https://taxonomy.example.org/taxonomy
```

The environment equivalent is `TAXONOMY_EXPORT_PUBLIC_BASE_URL`. A saved requirement link then has the existing route `/projects/{projectId}/requirements/{requirementId}` beneath that base. It opens the current editing page for that requirement; the visible version ID identifies the historical text represented by the export. It is stored in `taxonomy.requirementUrl` and a native Visio Hyperlink row, alongside the internal links to full source/evidence pages.

The base must be an absolute HTTP(S) URL. Credentials, query strings, fragments, control characters, backslashes, encoded/ambiguous paths and dot segments are rejected. The exporter never derives this URL from request `Host` or `Forwarded` headers, never puts a session/access token in the file, and never evaluates the address as a ShapeSheet formula. Opening the link still requires normal authentication and authorization to the project/requirement. Users may need to select the source workspace and branch shown in the file; the link does not silently switch their repository scope. An absent base URL leaves the export usable and prints why no return link is available. An invalid configured base fails application startup without echoing the value.

## Identity and mapping

Display names never determine identity. Sorted original element IDs allocate positive numeric Visio IDs. Sorted relationship IDs allocate connector IDs above the greatest element ID **on each page**. Numeric IDs can change when the selected graph changes; original `taxonomy.id` values are the persistent identities. A relationship occurring on two pages may have different numeric IDs, with one original Taxonomy ID in both occurrences.

| Source | Native/manifest representation | Meaning and limits |
| --- | --- | --- |
| Element ID, type, score, anchor and impact selection | `taxonomy.id`, `type`, `relevance`, `anchor`, `selectedForImpact` Shape Data | String, number and Boolean types are retained |
| Depth, layer, parent ID, container flag | Typed Shape Data and native container membership | Explicit container flags and parent IDs establish membership; page coordinates remain flat and containers remain non-semantic |
| Relationship ID, type, source/target ID, score, category | Connector Shape Data and page mapping | Exact semantics survive independently of visual arrow geometry |
| Recorded relationship origin, confidence, reasons and scoped evidence | Typed connector Shape Data | `taxonomy.provenanceConfidence` retains neutral confidence; supplied snapshot `taxonomy.confidence` remains authoritative |
| Authorized persisted reviews and decisions | Review/action status, confidence, actor/time, human rationale/action evidence | Only allowlisted values; absent review metadata is declared |
| Browser layout and routing | Generated layer layout in inches | Explicitly mapped; typography, browser geometry and routing are not copied |
| Original requirement and creation context | Native text, typed properties and literal hyperlinks | Frozen source for saved exports; explicit working-source provenance otherwise |
| Prompts, credentials, provider payloads, raw unscoped source evidence and warnings | Excluded from the handoff | Original requirement and recorded scoped relationship evidence are retained separately; no raw model-level provider payload is added |
| Snapshot history, operations and changes made in Visio | No write-back | VSDX editing does not update Taxonomy |

The primary page contains the complete selected diagram. A second page is generated for a nonempty proper anchor/impact subset with its induced relationships. The canonical graph hash uses the shared semantic graph contract; explicitly visual container rectangles do not change that hash. The manifest records page membership and layout/container transformations.

Dense overviews use compact `R1`, `R2`, … relationship keys; detail nodes use `N1`, `N2`, … keys. These are presentation keys in `taxonomy.displayKey`, assigned by sorted canonical IDs for the selected graph, not new model identities. Relationship detail pages contain at most six unique endpoints and six native 1-D relationships. Their separate caption area gives the relationship key, source → target keys and readable type, keeping coincident/multiple/reverse edges distinguishable. Endpoints may reappear on other pages; native `taxonomy.id`, type, direction, score, provenance and point-glue dependencies remain intact.

Eight original reusable masters are embedded in the document: block, process, role, application, information, service, container and connector. They use familiar diagram forms and require no separately installed stencil. These are original shapes, not redistributed Microsoft stencil assets. Known catalogue types select the appropriate form; unknown types use the generic block. A service is not guessed to be a server or database. Exact Taxonomy types remain in Shape Data regardless of appearance.

Connectors are native 1-D shapes with documented boundary connection points. Their begin/end formulas depend on the referenced shape's connection point through `PAR(PNT(...))`, with matching native `Connect` records and cached boundary positions. This is **static point glue**: the endpoint is attached to a selected side point. Automatic switching to another side is not promised. Native geometry, labels, point connections and document masters can be edited in Visio. Containers carry `User.msvStructureType="Container"` and reciprocal `Relationships` memberships; visual overlap alone does not establish membership. Desktop move/resize/save acceptance is still required as described below.

Connector text has a complete upright text transform with finite cached values and formulas; strokes are behind opaque node rectangles. Overview caption placement tries a fixed bounded set of candidates against node/caption boxes. An unplaceable caption receives `taxonomy.captionDisposition=DETAIL_REQUIRED` and an explicit overview loss instead of overlapping text. Full node labels remain in `taxonomy.label`; shortened labels/types end with an ellipsis and carry `TRUNCATED` losses. Text uses a fixed readable size rather than shrinking to fit.

Complete recorded reasons and shortened labels are printed on mandatory evidence pages, linked from native graph objects by N/R keys. Evidence and original-requirement pages share a separate budget of 128 pages and do not consume the 32-page diagram budget. Their complete text is never shortened: an export that cannot retain it within the supported bounds fails explicitly. These pages reserve capacity before optional relationship details are added.

Optional graphical details share the diagram-page budget with the complete overview and impact pages. All pages still share the existing shape/connector/package-byte limits. `relationshipDetailCoverage` records retained counts and the covered key range. At capacity, remaining canonical relationships stay in overview Shape Data; inspect `taxonomy.id/type/sourceId/targetId` or the complete report inventory. Optional detail growth never rejects an otherwise valid base package. The exporter does not promise visually exhaustive pages for arbitrarily large graphs.

Unknown nonblank business types remain exact literal properties on generic blocks/connectors. They do not acquire invented Visio architecture metamodel semantics. Shape Data rows use collision-safe UTF-8 hex names and retain the original property key in `Label`. String/number/Boolean `Type` values are `0`/`2`/`3`; user values never become ShapeSheet formulas.

## Validation and bounds

Validation runs before a download is returned, including every generated package:

- XML 1.0 text, property keys, canonical unsigned page/shape IDs, uniqueness, finite geometry and scores.
- Every OPC relationship target, source, content type, XML relationship ID, page index, shape endpoint, style reference and connector glue record; orphan parts and external relationships are rejected.
- Full compiled Visio 2012 schema bindings for the document, page index, every page, master index and all eight masters; standard custom/extended property schemas; the core-property namespace/structure is checked by the OPC contract.
- Native master references, page-to-master relationships, one-dimensional connector geometry, boundary connection points, matching endpoint formulas and `Connect` records, and reciprocal native container membership. External OPC relationships remain forbidden; the separately validated requirement hyperlink is a literal native Hyperlink row.
- Deterministic part ordering, ZIP timestamps, sorted properties and losses; bundle hashes bind the exact bytes. Determinism is tested with the pinned runtime/dependencies, not promised across arbitrary ZIP/JDK versions.

All limits apply together: 10,000 input elements, 30,000 input relationships, 32 diagram pages plus 128 shared evidence/requirement pages (160 total and at most 214 OPC parts), 20,000 rendered shape occurrences including evidence, requirement context and legends, 60,000 connectors, 64 properties per owner, 32,767 UTF-16 code units per text and 32 MiB total uncompressed package content. Only the derived OPC-part allowance increases to accommodate the master parts and diagram-to-master relationships; the other limits are unchanged. The workbench preserves the overview and optional impact page, reserves complete evidence and original-requirement text, and adds bounded relationship detail pages when needed. Empty graphs still emit one valid empty page. Self-loops, coincident endpoints, dangling references, invalid/control text, oversized values and unsupported package structures fail without a partial download. Full labels and relationship types remain in native shape data; bounded diagram captions and source excerpts can wrap or be explicitly shortened, with complete values on their mandatory text pages. Input-count limits do not guarantee that an unusually large model also fits the independent package-byte limit.

Schema dependencies are Maven-pinned Apache POI `5.5.1` (`poi-ooxml` and `poi-ooxml-full`) and XMLBeans `5.3.0`. The `lite` schema JAR is excluded because it lacks the complete FaceNames bindings. Upstream JARs carry their license/notice information; no third-party schema source is copied into this repository. The full schema JAR used for the supplementary run had SHA-256 `dbc7c6e6108ceb9f151c2fc866a2b287d48af3a10b05425da21315eec16ce022`.

## Reproducible automated evidence

The synthetic [acceptance fixture](../../taxonomy-export/src/test/resources/visio-handoff-v3/README.md) contains duplicate Unicode labels, three elements, two directed relationships, typed selection/review values and four pages: overview, impact selection, complete evidence and complete original requirement. Its 16 rendered shapes include editable context and presentation text; its six endpoint records attach the three rendered connector occurrences. The original requirement author/date, snapshot date and actual export actor/date are distinct fixed synthetic values, and its editing link uses the reserved example.org domain. Its all-zero authority commit is deliberately synthetic, not the Taxonomy source revision. The fixture regression test binds the committed bundle bytes to the generator at the tested source revision. CI's commit-bound reports identify that revision separately.

With Java 25 and the repository's Maven dependencies available, the authoritative
verification is `./mvnw -B verify -Pci -DrunOnnxTests=true`. The following focused
commands provide supplementary export evidence:

```sh
./mvnw -B -pl taxonomy-export -am test
./mvnw -B -pl taxonomy-portfolio -am -Dtest=ArchitectureWorkbenchServiceTest,ArchitectureVisioHandoffAcceptanceTest,VisioExportContextServiceTest,ArchitectureSnapshotExportServiceTest,ArchitectureSnapshotExportHeaderSafetyTest,ArchitectureSnapshotExportSemanticFingerprintTest,ArchitectureWorkbenchControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -B -pl taxonomy-app -am -Dtest=VisioWorkingViewMetadataTest,VisioMetadataAssemblyTest,RequirementTenantIsolationTest,PortfolioRepositoryBranchIsolationTest,SavedScopeExchangeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The tests cover native metadata reconstruction and cross-format canonical hashes, immutable authority/privacy boundaries, safe configured base URLs and existing requirement/workspace isolation, deterministic repeated and reordered input, malformed OPC/XML, native masters, style/glue references, reciprocal container membership, full Unicode source pagination, source-page name collisions and independent diagram/evidence budgets. They also cover complete explanations beyond 32 pages, empty diagrams, duplicate/long Unicode labels, 150 parallel connectors and a 1,000-element graph. Working-view tests verify original-text retention across successful, partial, stopped and cancelled analysis paths, plus accepted format aliases. Apache POI XDGF independently loads and renders all four representative pages. It is supplementary evidence only: the observed Linux POI preview lacks Japanese/emoji glyphs and arrowheads, so it does not establish typography or directional rendering fidelity in Microsoft Visio. A supplementary LibreOffice Draw import displays arrowheads, but does not establish Microsoft Visio edit/save behavior. Endpoint direction is independently checked in native data and glue.

The fixture test class also exposes a generator for intentional profile changes. After Maven test compilation, build a test dependency classpath with `dependency:build-classpath`, then invoke `com.taxonomy.export.VisioHandoffContractTest` with the desired output ZIP path, including `target/test-classes`, `target/classes` and test dependencies on the Java classpath. Review changes to both the fixture and profile; update the fixture checksums and rerun the byte-equality test. CI verification remains required for the final source SHA.

## Microsoft Visio desktop acceptance — pending

Run this procedure on a supported licensed Windows/Microsoft Visio desktop installation for the **exact candidate release SHA**. Record its full edition/version/build and the Windows version. Linux schema/POI runs do not fill in these results.

1. Check out the candidate SHA and require a clean worktree. Run the fixture test and record the source SHA, bundle SHA-256, profile version/hash and extracted `diagram.vsdx` SHA-256.
2. Open the extracted VSDX normally in Visio. Record whether any repair warning, import dialog or discarded-content notification appears. A repair is a failure.
3. Inspect all four pages, including complete evidence and original requirement text and their links. Verify all labels (including Unicode), duplicate-name identities, page bounds, distinct historical/export metadata and the source-to-target direction of each connector. Inspect element and connector Shape Data plus document authority properties. For a real authorized deployment, verify the requirement link opens the exact current editing page and still requires normal authentication and scope checks; the synthetic example.org URL is not a live editing target.
4. Move and resize `CP-01` and `BP-02`, change a display label, and edit connector routing. Verify attached endpoints follow their selected connection points and that `taxonomy.id` remains unchanged. Add an instance from the document stencil and inspect its editability. Separately exercise a container fixture: move the container and a member and inspect reciprocal membership. Record screenshots before and after.
5. Save to a new VSDX, close the document and Visio, reopen the saved file and repeat label, identity, metadata, direction, attachment and page checks. Record repairs, lost data or geometry explicitly.
6. Retain the original bundle, edited VSDX, screenshots, a version screenshot and the result record below, including SHA-256 for each file. Document whether Visio preserves the two custom JSON parts; if it drops them, retain the outer manifest and report that loss.
7. Review the evidence for this exact SHA before changing experimental wording or completing [EXT-04](../internal/TODO-EXTERNAL-INPUT.md#ext-04). Record any reproduced implementation defect in a linked issue. Repeat certification when the supported profile changes. A successful secondary consumer does not replace these steps.

Example result record (unexecuted fields must remain `null`, never assumed successful):

```json
{
  "status": "PENDING_MICROSOFT_VISIO_DESKTOP",
  "taxonomyCommit": null,
  "fixtureSha256": null,
  "profile": "visio-2012-opc-supported-subset-v3",
  "visioEditionVersionBuild": null,
  "windowsVersion": null,
  "openedWithoutRepair": null,
  "labelsAndUnicode": null,
  "directionAndEndpointAttachment": null,
  "pageBoundsAndEditability": null,
  "savedAndReopenedWithoutRepair": null,
  "typedIdentityAndMetadataRetained": null,
  "customPartsRetained": null,
  "editedFileSha256": null,
  "evidenceFiles": [],
  "observedLosses": []
}
```

## Primary format references

- [Microsoft Visio Shape Data row](https://learn.microsoft.com/en-us/office/client-developer/visio/row-element-shape-data-sectionvisio-xml) and [property type values](https://learn.microsoft.com/en-us/office/client-developer/visio/type-cell-shape-data-section).
- [DocumentSettings](https://learn.microsoft.com/en-us/office/client-developer/visio/documentsettings-element-visiodocument_type-complextypevisio-xml), [FaceName](https://learn.microsoft.com/en-us/office/client-developer/visio/facename-element-facenames_type-complextypevisio-xml) and [StyleSheet](https://learn.microsoft.com/en-us/office/client-developer/visio/stylesheet-element-stylesheets_type-complextypevisio-xml).
- [Apache POI diagram support](https://poi.apache.org/components/diagram/index.html) and [XDGF reader API](https://poi.apache.org/apidocs/dev/org/apache/poi/xdgf/usermodel/XmlVisioDocument.html).
