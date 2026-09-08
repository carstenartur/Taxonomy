# 1.4.0 release notes — unreleased

## Experimental Visio visual handoff (#965)

The Architecture Workbench now downloads the selected immutable snapshot as a bundle containing `diagram.vsdx`, a versioned mapping profile and a checksum-bound loss manifest. Export performs no new analysis. Native VSDX downloads carry the same profile and handoff data embedded in the file.

Stable Taxonomy element/relationship identities, original types, normalized scores, selection flags and authorized persisted reviews survive as typed Shape Data. Document properties and the manifest carry snapshot, requirement/version and repository/workspace/branch/commit authority when retained by the source snapshot. Missing metadata and deliberate layout/semantic losses are explicit.

Every generated package undergoes OPC/reference and pinned Visio schema validation. The supported profile uses masterless connectors, explicit styles and one or two snapshot pages. Repeated exports are deterministic. Automated coverage includes independent Apache POI loading/rendering and canonical graph comparison.

**Microsoft Visio desktop open/edit/save/reopen certification remains pending.** The format remains an experimental bounded visual handoff; no production-ready editing or universal Visio compatibility claim is made. External edits do not update Taxonomy. For semantic architecture interchange, use ArchiMate Exchange with authorized JSON evidence, subject to its separate #967 acceptance.

See the [user guide](USER_GUIDE.md) for download instructions and [profile/limits and desktop acceptance procedure](../dev/VISIO_HANDOFF_PROFILE.md) for exact scope and remaining evidence. The POI preview is supplementary and has observed glyph/arrowhead rendering limitations.
