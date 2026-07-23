# Document Import Resource Limits

Document import uses one configuration-backed policy for the servlet request, PDF/DOCX parsing, extracted text, requirement candidates, and LLM input. The defaults are selected for the production container's constrained JVM heap and can be overridden through Spring Boot environment-variable binding.

| Environment variable | Property | Default | Purpose |
|---|---|---:|---|
| `TAXONOMY_LIMITS_DOCUMENT_MAX_UPLOAD_BYTES` | `taxonomy.limits.document.max-upload-bytes` | `52428800` | Maximum file size. The servlet and controller use the same value. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_PDF_PAGES` | `taxonomy.limits.document.max-pdf-pages` | `500` | Maximum number of PDF pages before text extraction. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_EXTRACTED_CHARACTERS` | `taxonomy.limits.document.max-extracted-characters` | `1000000` | Maximum extracted characters retained in memory. Excess text is truncated with a warning. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_CANDIDATES` | `taxonomy.limits.document.max-candidates` | `2000` | Maximum requirement candidates returned or confirmed. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_LLM_CHARACTERS` | `taxonomy.limits.document.max-llm-characters` | `200000` | Maximum document characters sent to an external or local LLM. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_ENTRY_BYTES` | `taxonomy.limits.document.max-docx-entry-bytes` | `67108864` | Maximum expanded size of one OOXML ZIP entry. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_TEXT_BYTES` | `taxonomy.limits.document.max-docx-text-bytes` | `134217728` | Maximum total expanded size of the DOCX archive. |
| `TAXONOMY_LIMITS_DOCUMENT_MIN_DOCX_INFLATE_RATIO` | `taxonomy.limits.document.min-docx-inflate-ratio` | `0.01` | Minimum compressed-to-expanded ratio for each DOCX entry. Lower values are rejected as suspicious compression. |

## Error contract

- HTTP `400` — empty or malformed multipart request.
- HTTP `413` — the file exceeds the shared servlet/application upload limit.
- HTTP `422` — the file is syntactically parseable as a request but violates a parser resource limit, contains suspicious DOCX compression, exceeds the PDF page limit, or cannot be processed as PDF/DOCX.

Error responses contain stable `error` and `message` fields. Clients should branch on `error`, not on human-readable text.

## Memory and privacy behavior

- Uploads are copied once to a temporary file and deleted in a `finally` block.
- PDF and DOCX parsing does not call `MultipartFile.getBytes()`.
- SHA-256 is calculated with a streaming digest.
- DOCX ZIP entries are inspected before Apache POI expands them.
- Extracted text and LLM input are bounded independently.
- Responses explicitly report when LLM input was truncated.

## Verification

The focused document-import workflow runs controller, parser, and policy regression tests and archives complete Surefire reports. Normal pull-request verification additionally covers the full Maven reactor, 81% reactor coverage gate, CodeQL, Trivy, immutable supply-chain references, database compatibility, container restart, UI acceptance, accessibility, and documentation links.
