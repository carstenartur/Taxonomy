# Third-party provenance and notices

## SQLite JDBC

The plugin includes `org.xerial:sqlite-jdbc:3.54.1.0`, including its native SQLite
libraries. The version was verified on 2026-11-06 against the project's official
[release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0), published
2026-10-29, and the [versioned POM](https://github.com/xerial/sqlite-jdbc/blob/3.54.1.0/pom.xml).
No dynamic dependency version is used.

The upstream distribution carries Apache-2.0 and the David Crawshaw BSD notice.
The unmodified upstream texts are retained in both source and the shaded JAR:

- `src/main/resources/META-INF/third-party/sqlite-jdbc/LICENSE`
- `src/main/resources/META-INF/third-party/sqlite-jdbc/LICENSE.zentus`
- `src/main/resources/META-INF/third-party/sqlite-jdbc/NOTICE`

The dependency's own `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE*` resources
are also retained by shading. Native resources and `org.sqlite` class names are
not relocated because they are used by JNI and the native-library loader. The
plugin therefore requires STARTUP installation, not unload/reload behavior.

SLF4J and the Taxonomy extension API are provided by the host and are not shaded.
JUnit is test-only. The source project does not relicense third-party components.

## Examined EA schema and relationship rules

The reader is an independent, bounded implementation for Taxonomy's public
exchange contracts. It does not embed AAroN, its graph store, Neo4j, the Python
EAM application, a Sparx SDK, or a proprietary MDG file.

The following primary source was examined for EA's storage conventions:

- AAroN, commit `490c8538c6bf94bd78a9c90cc53c0163f23dd1a4`, Apache-2.0:
  [SparxSQLiteConverter](https://github.com/schmitze87/AAroN/blob/490c8538c6bf94bd78a9c90cc53c0163f23dd1a4/AAroN-Core/src/main/java/aaron/sparx/SparxSQLiteConverter.java),
  [ObjectProcessor](https://github.com/schmitze87/AAroN/blob/490c8538c6bf94bd78a9c90cc53c0163f23dd1a4/AAroN-Core/src/main/java/aaron/sparx/processors/ObjectProcessor.java),
  [SystemProcessor](https://github.com/schmitze87/AAroN/blob/490c8538c6bf94bd78a9c90cc53c0163f23dd1a4/AAroN-Core/src/main/java/aaron/sparx/processors/SystemProcessor.java),
  [XRefProcessor](https://github.com/schmitze87/AAroN/blob/490c8538c6bf94bd78a9c90cc53c0163f23dd1a4/AAroN-Core/src/main/java/aaron/sparx/processors/XRefProcessor.java),
  and [DiagramObjectsProcessor](https://github.com/schmitze87/AAroN/blob/490c8538c6bf94bd78a9c90cc53c0163f23dd1a4/AAroN-Core/src/main/java/aaron/sparx/processors/DiagramObjectsProcessor.java).
- EAM-Toolfamilie, commit `b5b97b876550d3d9ccfd260fe290428e5ffcaf06`:
  [QEAAnalyzer](https://github.com/Mitchel85/EAM-Toolfamilie/blob/b5b97b876550d3d9ccfd260fe290428e5ffcaf06/apps/mcp/eam_qea_tool.py)
  independently documents its use of those AAroN rules.

The selected implicit-reference mappings are credited to the examined AAroN
rules. Its Apache license is retained at
`src/main/resources/META-INF/third-party/aaron/LICENSE`, with attribution in
`META-INF/third-party/aaron/NOTICE`. Taxonomy changes include stable derived
reference identities, explicit mapping losses, selection validation, no
automatic canonical projection for implicit edges, and one parent-field
relationship rather than redundant inverse graph aliases.

These references document provenance of the implementation decisions. They do
not constitute product compatibility evidence or grant a license to Sparx
Enterprise Architect or any separately distributed model/profile content.
