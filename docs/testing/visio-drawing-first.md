# Visio: drawings before text appendices

The export opens a native drawing window on the first graphical page. Its initial center and zoom are calculated from native shapes and connector captions, excluding presentation-only context text. All diagram pages precede the complete requirement/evidence appendices. Page IDs, names, native shapes, masters, point-glue and authorized requirement links remain unchanged.

The 55-page scenario artifact from run 40506486163 previously placed 42 evidence pages and one requirement page between the overview/impact views and ten relationship drawings. The corrected order is twelve graphical pages followed by those same 43 text pages. This diagnoses an exported-document navigation defect; it does not establish that the user's exact Microsoft Visio file had no additional rendering defect.

Optional relationship-detail pages are still the only pages eligible for byte-budget pruning. Because they are no longer trailing pages, pruning finds them explicitly and caches retained page XML by stable page ID rather than the old positional part name. Complete overview data and mandatory text are retained.

## Verification

The existing Maven owner runs the new tests:

```sh
./mvnw -B -ntp -pl taxonomy-export -am test \
  -Dtest=VisioDrawingFirstTest,VisioHandoffContractTest,VisioGraphicalQualityTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The normal repository CI command remains unchanged:

```sh
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Local supplemental verification used the real exporter sources and pinned dependencies recovered from CI, Java 21, and direct invocation of the five new assertion methods. Four behavior assertions failed before the change; all five passed after it. The temporary runner removes only the JUnit annotation/import, not assertions or production behavior. Maven/Java 25 and the complete repository suite were not available locally; these results are not a full CI pass.

A new scenario VSDX was produced through the corrected builder from the original artifact's native page model. Apache POI reopened all 55 pages; LibreOffice Draw rendered the file and the overview/first relationship drawing were visually inspected. POI's previews have known text-transform/font limitations. Neither reader certifies Microsoft Visio desktop behavior. Desktop opening, moving/resizing shapes, save/reopen and following authorized requirement links remain explicit acceptance steps.

The original representative ZIP stays as the independent golden reference. The fixture test compares every native part byte-for-byte, allowing only an explicitly specified new `windows.xml`; the bundle manifest may change only its VSDX checksum. Existing same-source ZIP reproducibility tests remain active. See the fixture README for the distinction between the historical reference and a newly generated candidate.
