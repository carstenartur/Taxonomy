# Third-Party Notices

Taxonomy Architecture Analyzer incorporates third-party software. This document
contains the curated notices that cannot be derived reliably from the Maven
runtime graph alone, especially browser assets, optional containers, AI models
and special distribution terms.

## Authoritative evidence for a concrete build

The About dialog and `/api/about` expose four complementary artefacts:

1. **`LICENSE`** — the MIT license of Taxonomy itself.
2. **`NOTICE`** — product-level attribution notices that must accompany the
   distribution.
3. **`THIRD-PARTY-RUNTIME.txt`** — generated from the packaged Maven runtime
   dependency graph during `generate-resources`. The build fails when a runtime
   dependency has no usable license metadata and no repository-reviewed
   override.
4. **CycloneDX SBOM (`taxonomy-sbom.json` and `taxonomy-sbom.xml`)** — exact
   component names, versions, package URLs, checksums and dependency relations
   for the build.

The generated runtime report and SBOM are authoritative for the versions in a
specific artefact. This checked-in document provides context and notices; it is
not a manually maintained replacement for the generated inventory.

The optional OpenTelemetry Java agent is copied into the container image rather
than added as a Maven runtime dependency. The optional Collector and Jaeger run
as separate containers. A container-level SBOM and vulnerability/license scan
must therefore cover those images and the Java runtime base image in addition
to the Maven SBOM.

---

## License overview

| License or terms | Representative components | Distribution note |
|---|---|---|
| Apache License 2.0 | Spring Boot, Apache POI, Apache PDFBox, Lucene, Hibernate, springdoc, DJL, Micrometer, OpenTelemetry | Permissive; preserve required notices and license text |
| MIT License | Taxonomy, Bootstrap, jsPDF, svg2pdf.js, ONNX Runtime, CodeMirror, Microsoft JDBC Driver | Permissive; preserve copyright and permission notice |
| ISC License | D3.js | Permissive; preserve copyright, permission and disclaimer text |
| BSD 2-Clause / BSD 3-Clause | HSQLDB, PostgreSQL JDBC, XStream, Flexmark-java | Permissive; preserve copyright, conditions and disclaimers |
| Eclipse Distribution License 1.0 | JGit | BSD-style terms; preserve the required license material |
| Eclipse Public License 2.0 | JUnit and JaCoCo | Build/test tooling; not part of the application runtime report |
| Oracle Free Use Terms and Conditions (FUTC) | Oracle JDBC Driver (`ojdbc11`) | Runtime driver distributed under FUTC; preserve Oracle terms and notices |

Do not infer licence compliance from this summary alone. The generated report is
the release-specific source of truth and the applicable upstream terms remain
controlling.

---

## Apache License 2.0 components

Full license text: https://www.apache.org/licenses/LICENSE-2.0

Representative projects include:

- Spring Boot, Spring Framework and Spring Security — https://spring.io/
- Apache POI — https://poi.apache.org/
- Apache PDFBox — https://pdfbox.apache.org/
- Apache Lucene — https://lucene.apache.org/
- Hibernate ORM and Hibernate Search — https://hibernate.org/
- springdoc-openapi — https://springdoc.org/
- Deep Java Library (DJL) — https://djl.ai/
- Micrometer — https://micrometer.io/
- OpenTelemetry Java instrumentation and Collector — https://opentelemetry.io/
- Jaeger — https://www.jaegertracing.io/

The OpenTelemetry Java agent is present only in the container distribution and
is disabled by default. Collector and Jaeger are optional deployment services,
not Taxonomy application-classpath dependencies.

Testcontainers is Apache-2.0-licensed test infrastructure and is excluded from
the generated runtime report.

---

## MIT-licensed components

Full license text: https://opensource.org/licenses/MIT

Representative projects include:

- Bootstrap — https://getbootstrap.com/
- jsPDF — https://github.com/parallax/jsPDF
- svg2pdf.js — https://github.com/yWorks/svg2pdf.js
- ONNX Runtime — https://onnxruntime.ai/
- CodeMirror — https://codemirror.net/
- Microsoft JDBC Driver for SQL Server — https://github.com/microsoft/mssql-jdbc

The Microsoft JDBC driver is an MIT-licensed runtime dependency. Licensing of a
Microsoft SQL Server installation or hosted service is a separate operator
responsibility and is not determined by the driver license.

---

## ISC and BSD components

ISC license text: https://opensource.org/licenses/ISC

- D3.js — https://d3js.org/

BSD 2-Clause license text: https://opensource.org/licenses/BSD-2-Clause

- PostgreSQL JDBC Driver — https://jdbc.postgresql.org/
- Flexmark-java — https://github.com/vsch/flexmark-java

BSD 3-Clause license text: https://opensource.org/licenses/BSD-3-Clause

- XStream — https://x-stream.github.io/

HSQLDB uses its BSD-style license. See https://hsqldb.org/ and the exact entry in
`THIRD-PARTY-RUNTIME.txt` for the packaged version.

---

## Eclipse licenses

Eclipse Distribution License 1.0:
https://www.eclipse.org/org/documents/edl-v10.php

- JGit — https://www.eclipse.org/jgit/

Eclipse Public License 2.0:
https://www.eclipse.org/legal/epl-2.0/

- JUnit 5 — test only; not included in the application runtime distribution
- JaCoCo — build-time coverage tooling; not included in the application runtime
  distribution

---

## Oracle JDBC Driver

`com.oracle.database.jdbc:ojdbc11` is a packaged runtime dependency. Its Maven
metadata declares the **Oracle Free Use Terms and Conditions (FUTC)**:

https://www.oracle.com/downloads/licenses/oracle-free-license.html

The FUTC governs use and redistribution of the unmodified JDBC driver and
requires preservation of Oracle notices and the applicable terms. It must not
be described as a development/test-only dependency.

The driver licence does **not** grant a licence for an Oracle Database server.
Operators remain responsible for the separate licence or service terms of the
Oracle Database instance to which Taxonomy connects.

---

## AI model licence

### BAAI/bge-small-en-v1.5

- License: MIT
- Copyright: Beijing Academy of Artificial Intelligence (BAAI)
- Model page: https://huggingface.co/BAAI/bge-small-en-v1.5

The model is not a Maven dependency. Taxonomy pins a reviewed model revision and
can use either a pre-provisioned local directory or the controlled model-download
step. Container and air-gapped distributions that include the model must retain
its licence and attribution and include it in container-level inventory.

---

## Build and audit commands

Generate and verify the runtime licence report together with the complete
release-grade application test suite:

```bash
./mvnw -B clean verify -Pci
```

The report is packaged as `THIRD-PARTY-RUNTIME.txt`. A missing runtime licence
causes the Maven build to fail. Verified metadata overrides belong in
`taxonomy-app/src/license/override-THIRD-PARTY.properties`. The build reads this
file but never generates or deletes it. Every override must cite the canonical
upstream licence in its reviewing pull request.

Generate the CycloneDX SBOM through the normal Maven lifecycle:

```bash
./mvnw package
```

Outputs:

- `target/taxonomy-sbom.json`
- `target/taxonomy-sbom.xml`

The Maven SBOM covers direct and transitive Maven components. Additionally
inspect a container-level SBOM for:

- the Java runtime base image and operating-system packages;
- the bundled OpenTelemetry Java agent;
- optional OpenTelemetry Collector and Jaeger images;
- a bundled local embedding model.

Use the generated artefacts for vulnerability scanning, Dependency-Track or an
equivalent inventory system, procurement evidence and release review.
