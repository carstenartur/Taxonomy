# Third-Party Notices

Taxonomy Architecture Analyzer incorporates components from the following third-party projects.

> **SBOM:** A machine-readable Software Bill of Materials (CycloneDX format) is generated
> at build time via `mvn package`. The SBOM files are located at
> `target/taxonomy-sbom.json` and `target/taxonomy-sbom.xml`.

---

## License Summary

| License | Components | Government Use |
|---|---|---|
| Apache License 2.0 | Spring Boot, Apache POI, Lucene, Hibernate, springdoc, DJL, Micrometer, JaCoCo | ✅ Permissive, no restrictions |
| MIT License | Bootstrap, jsPDF, svg2pdf.js, ONNX Runtime, CodeMirror | ✅ Permissive, no restrictions |
| ISC License | D3.js | ✅ Permissive, no restrictions |
| BSD Licenses | HSQLDB, PostgreSQL JDBC, XStream, Flexmark-java | ✅ Permissive, no restrictions |
| Eclipse Distribution License 1.0 | JGit | ✅ BSD-style, no restrictions |
| Eclipse Public License 2.0 | JUnit, JaCoCo | ✅ Test-only; not included in runtime |
| Oracle FUTC | Oracle JDBC Driver | ⚠️ Dev/test only; production requires Oracle license |
| Microsoft MSSQL JDBC | Microsoft JDBC Driver | ⚠️ Check terms for government use |

**All runtime dependencies use permissive open-source licenses** (Apache 2.0, MIT, BSD, ISC, EDL 1.0) that are compatible with government procurement requirements. No copyleft (GPL/LGPL) dependencies are included in the runtime classpath.

---

## Apache License 2.0

Full license text: https://www.apache.org/licenses/LICENSE-2.0

### Spring Boot / Spring Framework
Copyright © Pivotal Software, Inc. / VMware, Inc.
https://spring.io/

### Apache POI
Copyright © The Apache Software Foundation
https://poi.apache.org/

### Apache Lucene
Copyright © The Apache Software Foundation
https://lucene.apache.org/

### Hibernate ORM / Hibernate Search
Copyright © Red Hat, Inc.
https://hibernate.org/

### springdoc-openapi
Copyright © springdoc
https://springdoc.org/

### DJL (Deep Java Library)
Copyright © Amazon.com, Inc. or its affiliates
https://djl.ai/

### Micrometer
Copyright © VMware, Inc.
https://micrometer.io/

### Spring Security
Copyright © Pivotal Software, Inc. / VMware, Inc.
https://spring.io/projects/spring-security

### Testcontainers
Copyright © Testcontainers Contributors
https://testcontainers.com/

---

## MIT License

Full license text: https://opensource.org/licenses/MIT

### Bootstrap 5.3
Copyright © The Bootstrap Authors
https://getbootstrap.com/

### jsPDF
Copyright © James Hall, yWorks GmbH
https://github.com/parallax/jsPDF

### svg2pdf.js
Copyright © yWorks GmbH
https://github.com/yWorks/svg2pdf.js

### ONNX Runtime
Copyright © Microsoft Corporation
https://onnxruntime.ai/

### CodeMirror 6
Copyright © Marijn Haverbeke
https://codemirror.net/

---

## ISC License

Full license text: https://opensource.org/licenses/ISC

### D3.js
Copyright © Mike Bostock
https://d3js.org/

---

## BSD Licenses

### HSQLDB (BSD-style)
Copyright © The HSQL Development Group
https://hsqldb.org/

### PostgreSQL JDBC Driver (BSD 2-Clause)
Copyright © PostgreSQL Global Development Group
https://jdbc.postgresql.org/

Full license text: https://opensource.org/licenses/BSD-2-Clause

### XStream (BSD 3-Clause)
Copyright © Joe Walnes and XStream Committers
https://x-stream.github.io/

Full license text: https://opensource.org/licenses/BSD-3-Clause

### Flexmark-java (BSD 2-Clause)
Copyright © Vladimir Schneider
https://github.com/vsch/flexmark-java

Full license text: https://opensource.org/licenses/BSD-2-Clause

---

## Eclipse Distribution License 1.0 (BSD-style)

Full license text: https://www.eclipse.org/org/documents/edl-v10.php

### JGit
Copyright © Eclipse Foundation
https://www.eclipse.org/jgit/

---

## Eclipse Public License 2.0 (Test-Only)

Full license text: https://www.eclipse.org/legal/epl-2.0/

### JUnit 5
Copyright © Eclipse Foundation
https://junit.org/junit5/

> **Note:** JUnit is used only for testing and is not included in the runtime distribution.

### JaCoCo
Copyright © Mountainminds GmbH & Co. KG and Contributors
https://www.jacoco.org/

> **Note:** JaCoCo is used only for code coverage analysis and is not included in the runtime distribution.

---

## Other Licenses

### Oracle JDBC Driver (ojdbc)
Oracle Free Use Terms and Conditions (FUTC)
https://www.oracle.com/downloads/licenses/oracle-free-license.html
Use is permitted for development, testing, prototyping, and demonstrations.

> **Note:** For production use with Oracle databases, ensure a valid Oracle database license is in place.

### Microsoft JDBC Driver for SQL Server
MIT License
https://github.com/microsoft/mssql-jdbc

---

## AI Model Licenses

### BAAI/bge-small-en-v1.5 (Local Embedding Model)
MIT License
Copyright © Beijing Academy of Artificial Intelligence (BAAI)
https://huggingface.co/BAAI/bge-small-en-v1.5

> **Note:** The embedding model is downloaded on first use. For air-gapped deployments, pre-download via `TAXONOMY_EMBEDDING_MODEL_DIR`.

---

## Generating the SBOM

A CycloneDX Software Bill of Materials is generated during the Maven build:

```bash
mvn package
```

Output files:
- `target/taxonomy-sbom.json` — JSON format (machine-readable)
- `target/taxonomy-sbom.xml` — XML format (CycloneDX standard)

The SBOM includes:
- All direct and transitive dependencies
- Package names, versions, and checksums
- License information per dependency
- Dependency tree structure

Use the SBOM for:
- **BSI IT-Grundschutz** software supply chain requirements
- **Vulnerability scanning** (import into tools like Dependency-Track)
- **License compliance** audits for government procurement
