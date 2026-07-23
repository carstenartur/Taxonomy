# Ressourcengrenzen für Dokumentimporte

Der Dokumentimport verwendet eine einheitliche, konfigurierbare Richtlinie für Servlet-Requests, PDF-/DOCX-Verarbeitung, extrahierten Text, Anforderungskandidaten und LLM-Eingaben. Die Standardwerte sind auf den begrenzten JVM-Heap des Produktionscontainers abgestimmt und können über das Spring-Boot-Environment-Binding überschrieben werden.

| Umgebungsvariable | Property | Standard | Zweck |
|---|---|---:|---|
| `TAXONOMY_LIMITS_DOCUMENT_MAX_UPLOAD_BYTES` | `taxonomy.limits.document.max-upload-bytes` | `52428800` | Maximale Dateigröße. Servlet und Controller verwenden denselben Wert. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_PDF_PAGES` | `taxonomy.limits.document.max-pdf-pages` | `500` | Maximale Zahl von PDF-Seiten vor der Textextraktion. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_EXTRACTED_CHARACTERS` | `taxonomy.limits.document.max-extracted-characters` | `1000000` | Maximale Zahl extrahierter Zeichen im Speicher. Überschüssiger Text wird mit Warnung abgeschnitten. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_CANDIDATES` | `taxonomy.limits.document.max-candidates` | `2000` | Maximale Zahl zurückgegebener oder bestätigter Anforderungskandidaten. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_LLM_CHARACTERS` | `taxonomy.limits.document.max-llm-characters` | `200000` | Maximale Dokumentlänge für externe oder lokale LLMs. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_ENTRY_BYTES` | `taxonomy.limits.document.max-docx-entry-bytes` | `67108864` | Maximale entpackte Größe eines OOXML-ZIP-Eintrags. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_TEXT_BYTES` | `taxonomy.limits.document.max-docx-text-bytes` | `134217728` | Maximale gesamte entpackte Größe des DOCX-Archivs. |
| `TAXONOMY_LIMITS_DOCUMENT_MIN_DOCX_INFLATE_RATIO` | `taxonomy.limits.document.min-docx-inflate-ratio` | `0.01` | Minimales Verhältnis von komprimierter zu entpackter Größe. Niedrigere Werte gelten als verdächtige Kompression. |

## Fehlervertrag

- HTTP `400` — leere Datei oder ungültiger Multipart-Request.
- HTTP `413` — Datei überschreitet die gemeinsame Servlet-/Anwendungsgrenze.
- HTTP `422` — Parser-Ressourcengrenze, verdächtige DOCX-Kompression, zu viele PDF-Seiten oder nicht verarbeitbares PDF/DOCX.

Fehlerantworten enthalten stabile Felder `error` und `message`. Clients sollten das Feld `error` auswerten und nicht den frei formulierten Text.

## Speicher- und Datenschutzverhalten

- Uploads werden einmal in eine temporäre Datei kopiert und in einem `finally`-Block gelöscht.
- Die PDF-/DOCX-Verarbeitung ruft nicht `MultipartFile.getBytes()` auf.
- SHA-256 wird per Streaming berechnet.
- DOCX-ZIP-Einträge werden geprüft, bevor Apache POI sie entpackt.
- Extrahierter Text und LLM-Eingaben besitzen unabhängige Grenzen.
- Antworten weisen explizit darauf hin, wenn die LLM-Eingabe gekürzt wurde.
