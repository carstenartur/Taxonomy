# VSDX handoff profile and acceptance

Status: **experimental, Microsoft Visio desktop certification pending** ([#965](https://github.com/carstenartur/Taxonomy/issues/965)). The format is intended for an editable visual handoff. It does not replace canonical snapshots or semantic interchange through ArchiMate Exchange and authorized JSON evidence. No supported Microsoft Visio desktop version has been certified yet.

## Download and authority

In the Architecture Workbench, select the persisted snapshot, verify its displayed snapshot/commit identity and export profile, then choose **Download Visio + manifest**. The authenticated endpoint is `GET /api/projects/{projectId}/architecture-workbench/{snapshotId}.visio.zip`. It loads the authorized immutable projection and performs no new analysis. The existing `.vsdx` endpoint returns the same native diagram bytes.

The bundle contains:

| File | Contract |
| --- | --- |
| `diagram.vsdx` | Validated OPC package with literal typed Shape Data, core/extended/custom document properties and embedded handoff/profile JSON |
| `mapping-profile.json` | Exact executable profile `visio-2012-opc-supported-subset-v2` |
| `manifest.json` | SHA-256 of the VSDX and profile, the same embedded handoff manifest, page/object mapping and explicit losses |

The VSDX embeds `taxonomy/manifest.json` and `taxonomy/mapping-profile.json` through declared OPC relationships and content types. The inner manifest does not hash its enclosing VSDX, avoiding a recursive checksum. Keep the original outer bundle when saving edits in another application: preservation of custom parts by third-party writers is not certified, and its checksum describes the original file only.

Repository, workspace, branch, authoritative commit, requirement/version, snapshot identity/status/creation time and taxonomy fingerprint come from the authorized persisted projection. Missing historical fields are reported as omitted; current repository values are not substituted. A selection-profile fingerprint is currently unavailable and explicitly omitted. Relevance is a normalized selection score in `[0,1]`, not a probability. `generatedAt` is reproducibly normalized to `snapshotCreatedAt`, with `timestampPolicy=SOURCE_SNAPSHOT_CREATED_AT`; it is not the wall-clock download time.

## Identity and mapping

Display names never determine identity. Sorted original element IDs allocate positive numeric Visio IDs. Sorted relationship IDs allocate connector IDs above the greatest element ID **on each page**. Numeric IDs can change when the selected graph changes; original `taxonomy.id` values are the persistent identities. A relationship occurring on two pages may have different numeric IDs, with one original Taxonomy ID in both occurrences.

| Source | Native/manifest representation | Meaning and limits |
| --- | --- | --- |
| Element ID, type, score, anchor and impact selection | `taxonomy.id`, `type`, `relevance`, `anchor`, `selectedForImpact` Shape Data | String, number and Boolean types are retained |
| Depth, layer, parent ID, container flag | Typed Shape Data | Containers become flat rectangles explicitly marked non-semantic; no native grouping fidelity is claimed |
| Relationship ID, type, source/target ID, score, category | Connector Shape Data and page mapping | Exact semantics survive independently of visual arrow geometry |
| Authorized persisted reviews and decisions | Review/action status, confidence, actor/time, human rationale/action evidence | Only allowlisted values; absent review metadata is declared |
| Browser layout and routing | Generated layer layout in inches | Explicitly mapped; typography, browser geometry and routing are not copied |
| Prompts, credentials, provider payloads, requirement text, unreviewed source evidence and warnings | Excluded from the handoff | Use separately authorized canonical evidence where appropriate |
| Snapshot history, operations and changes made in Visio | No write-back | VSDX editing does not update Taxonomy |

The primary page contains the complete selected diagram. A second page is generated for a nonempty proper anchor/impact subset with its induced relationships. The canonical graph hash uses the shared semantic graph contract; explicitly visual container rectangles do not change that hash. The manifest records page membership and layout/container transformations.

Unknown nonblank business types remain exact literal properties on generic rectangles/connectors. They do not acquire invented Visio architecture metamodel semantics. Shape Data rows use collision-safe UTF-8 hex names and retain the original property key in `Label`. String/number/Boolean `Type` values are `0`/`2`/`3`; user values never become ShapeSheet formulas.

## Validation and bounds

Validation runs before a download is returned, including every generated package:

- XML 1.0 text, property keys, canonical unsigned page/shape IDs, uniqueness, finite geometry and scores.
- Every OPC relationship target, source, content type, XML relationship ID, page index, shape endpoint, style reference and connector glue record; orphan parts and external relationships are rejected.
- Full compiled Visio 2012 schema bindings for the document, page index and every page; standard custom/extended property schemas; the core-property namespace/structure is checked by the OPC contract.
- Masterless connector geometry with explicit standard styles, `Shapes`/`Connects` collections and begin/end glue. Master references are rejected by this profile.
- Deterministic part ordering, ZIP timestamps, sorted properties and losses; bundle hashes bind the exact bytes. Determinism is tested with the pinned runtime/dependencies, not promised across arbitrary ZIP/JDK versions.

All limits apply together: 10,000 input elements, 30,000 input relationships, 32 pages, 20,000 rendered node occurrences, 60,000 connectors, 64 properties per owner, 32,767 UTF-16 code units per text and 32 MiB total uncompressed package content. The workbench normally emits one or two pages. Empty graphs still emit one valid empty page. Self-loops, coincident endpoints, dangling references, invalid/control text, oversized values and unsupported package structures fail without a partial download. Long labels and parallel relationships retain their data; the fixed visual layout can need manual adjustment.

Schema dependencies are Maven-pinned Apache POI `5.5.1` (`poi-ooxml` and `poi-ooxml-full`) and XMLBeans `5.3.0`. The `lite` schema JAR is excluded because it lacks the complete FaceNames bindings. Upstream JARs carry their license/notice information; no third-party schema source is copied into this repository. The full schema JAR used for the supplementary run had SHA-256 `dbc7c6e6108ceb9f151c2fc866a2b287d48af3a10b05425da21315eec16ce022`.

## Reproducible automated evidence

The synthetic [acceptance fixture](../../taxonomy-export/src/test/resources/visio-handoff-v2/README.md) contains duplicate Unicode labels, three elements, two directed relationships, typed selection/review values and two pages. Its all-zero authority commit is deliberately synthetic, not the Taxonomy source revision. The fixture regression test binds the committed bundle bytes to the generator at the tested source revision. CI's commit-bound reports identify that revision separately.

With Java 21 and the repository's Maven dependencies available:

```sh
mvn -B -pl taxonomy-export -am -Dtest=VisioHandoffContractTest,VisioPackageBuilderTest,VisioPackageContractTest,VisioPackagePoiCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl taxonomy-app -am -Dtest=ArchitectureVisioHandoffAcceptanceTest,ArchitectureSnapshotExportServiceTest,ArchitectureSnapshotExportSemanticFingerprintTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The tests cover native metadata reconstruction and cross-format canonical hashes, immutable authority/privacy boundaries, deterministic repeated and reordered input, malformed OPC/XML, style/glue references, two pages, empty diagrams, duplicate/long Unicode labels, 150 parallel connectors and a 1,000-element graph. Apache POI XDGF independently loads and renders both representative pages. It is supplementary evidence only: the observed Linux POI preview lacks Japanese/emoji glyphs and arrowheads, so it does not establish typography or directional rendering fidelity in Microsoft Visio. Endpoint direction is independently checked in native data and glue.

The fixture test class also exposes a generator for intentional profile changes. After Maven test compilation, build a test dependency classpath with `dependency:build-classpath`, then invoke `com.taxonomy.export.VisioHandoffContractTest` with the desired output ZIP path, including `target/test-classes`, `target/classes` and test dependencies on the Java classpath. Review changes to both the fixture and profile; update the fixture checksums and rerun the byte-equality test. CI verification remains required for the final source SHA.

## Microsoft Visio desktop acceptance — pending

Run this procedure on a supported licensed Windows/Microsoft Visio desktop installation for the **exact candidate release SHA**. Record its full edition/version/build and the Windows version. Linux schema/POI runs do not fill in these results.

1. Check out the candidate SHA and require a clean worktree. Run the fixture test and record the source SHA, bundle SHA-256, profile version/hash and extracted `diagram.vsdx` SHA-256.
2. Open the extracted VSDX normally in Visio. Record whether any repair warning, import dialog or discarded-content notification appears. A repair is a failure.
3. Inspect both pages. Verify all labels (including Unicode), duplicate-name identities, page bounds and the source-to-target direction of each connector. Inspect element and connector Shape Data plus document authority properties.
4. Move `CP-01` and `BP-02`, change a display label, and edit connector routing. Verify attached endpoints follow the correct shapes and that `taxonomy.id` remains unchanged. Record screenshots before and after.
5. Save to a new VSDX, close the document and Visio, reopen the saved file and repeat label, identity, metadata, direction, attachment and page checks. Record repairs, lost data or geometry explicitly.
6. Retain the original bundle, edited VSDX, screenshots, a version screenshot and the result record below, including SHA-256 for each file. Document whether Visio preserves the two custom JSON parts; if it drops them, retain the outer manifest and report that loss.
7. Review the evidence for this exact SHA before changing experimental wording or closing #965. Repeat certification when the supported profile changes. A successful secondary consumer does not replace these steps.

Example result record (unexecuted fields must remain `null`, never assumed successful):

```json
{
  "status": "PENDING_MICROSOFT_VISIO_DESKTOP",
  "taxonomyCommit": null,
  "fixtureSha256": null,
  "profile": "visio-2012-opc-supported-subset-v2",
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
