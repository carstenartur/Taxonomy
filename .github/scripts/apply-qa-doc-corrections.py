#!/usr/bin/env python3
"""Apply idempotent documentation corrections for PR #406.

This script is intentionally limited to text/documentation drift that is hard to
update safely through partial GitHub file responses. It may be run repeatedly;
subsequent runs produce no changes.
"""

from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def replace(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    if old in text:
        file.write_text(text.replace(old, new), encoding="utf-8")


def regex_replace(path: str, pattern: str, replacement: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    updated = re.sub(pattern, replacement, text, flags=re.MULTILINE)
    if updated != text:
        file.write_text(updated, encoding="utf-8")


def replace_in_markdown_tree(old: str, new: str) -> None:
    for file in [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]:
        text = file.read_text(encoding="utf-8")
        if old in text:
            file.write_text(text.replace(old, new), encoding="utf-8")


# README accuracy.
replace(
    "README.md",
    "| **[Accessibility / BITV 2.0](docs/en/ACCESSIBILITY.md)** | BITV 2.0 / WCAG 2.1 accessibility concept and action plan |",
    "| **[Accessibility / BITV 2.0](docs/en/ACCESSIBILITY.md)** | Accessibility evidence matrix, automated axe gate, manual release checks, and known limitations |",
)
replace(
    "README.md",
    "- 🔒 **Air-gapped operation** — `LLM_PROVIDER=LOCAL_ONNX` for fully offline deployment",
    "- 🔒 **Local AI execution** — `LLM_PROVIDER=LOCAL_ONNX`; a fully network-isolated deployment additionally requires preloaded, checksummed models, local browser assets, disabled runtime downloads, and an outbound-network test",
)
replace(
    "README.md",
    "When a Zenodo DOI is minted for a release, cite the DOI for stable archival reference and use the GitHub repository for the active development version.",
    "For stable archival reference, cite the Zenodo DOI linked by the badge at the top of this README; use the GitHub repository for the active development version.",
)

# Package/module ownership after eliminating split packages.
replacements = {
    "com.taxonomy.export.service.ExportFormatExtension": "com.taxonomy.export.spi.ExportFormatExtension",
    "com.taxonomy.export.service.ExportFormatDescriptor": "com.taxonomy.export.spi.ExportFormatDescriptor",
    "com.taxonomy.export.service.ExportContext": "com.taxonomy.export.spi.ExportContext",
    "com.taxonomy.export.service.ExportResult": "com.taxonomy.export.spi.ExportResult",
    "com.taxonomy.architecture.report.ReportRendererExtension": "com.taxonomy.extension.api.report.ReportRendererExtension",
    "com.taxonomy.architecture.report.ReportFormatDescriptor": "com.taxonomy.extension.api.report.ReportFormatDescriptor",
    "com.taxonomy.architecture.report.ReportRenderContext": "com.taxonomy.extension.api.report.ReportRenderContext",
    "com.taxonomy.architecture.report.ReportRenderResult": "com.taxonomy.extension.api.report.ReportRenderResult",
    "com.taxonomy.catalog.service.importer.ImportProfileExtension": "com.taxonomy.extension.api.importer.ImportProfileExtension",
    "com.taxonomy.catalog.service.importer.ImportProfileDescriptor": "com.taxonomy.extension.api.importer.ImportProfileDescriptor",
    "com.taxonomy.catalog.service.importer.ImportInput": "com.taxonomy.extension.api.importer.ImportInput",
    "com.taxonomy.analysis.service.LlmProviderExtension": "com.taxonomy.extension.api.llm.LlmProviderExtension",
    "com.taxonomy.analysis.service.LlmProviderDescriptor": "com.taxonomy.extension.api.llm.LlmProviderDescriptor",
    "com.taxonomy.shared.extension.ExtensionRegistry": "com.taxonomy.shared.extension.runtime.ExtensionRegistry",
}
for old, new in replacements.items():
    replace_in_markdown_tree(old, new)

# Architecture module graph and counts.
replace(
    "docs/en/ARCHITECTURE.md",
    "taxonomy-extension-api/ Internal extension SPI contracts and metadata — no framework dependencies",
    "taxonomy-extension-api/ Common extension metadata plus report/import/LLM contracts — no framework dependencies",
)
replace(
    "docs/en/ARCHITECTURE.md",
    "taxonomy-export  →  taxonomy-domain\ntaxonomy-extension-api  →  taxonomy-domain\ntaxonomy-extension-api  →  taxonomy-export",
    "taxonomy-extension-api  →  taxonomy-domain\ntaxonomy-export  →  taxonomy-domain\ntaxonomy-export  →  taxonomy-extension-api",
)
replace(
    "docs/en/ARCHITECTURE.md",
    "`taxonomy-domain`, `taxonomy-dsl`, `taxonomy-export`, and `taxonomy-extension-api` have **no Spring dependencies** and can be tested and used independently.",
    "`taxonomy-domain`, `taxonomy-dsl`, `taxonomy-export`, and `taxonomy-extension-api` have **no Spring dependencies**. The common extension API does not depend on export, DSL, or application modules; feature-specific contracts are owned by their lowest appropriate domain module.",
)
replace_in_markdown_tree("4 Maven modules", "5 Maven modules")
replace_in_markdown_tree("3 modules are Spring-free", "4 modules are Spring-free")

# Password policy fallback text in both bundles (keys remain stable).
for messages in (
    "taxonomy-app/src/main/resources/i18n/messages.properties",
    "taxonomy-app/src/main/resources/i18n/messages_de.properties",
):
    regex_replace(messages, r"^(password\.min\.length=.*?)(?:8)(.*)$", r"\g<1>12\2")

# Maintainability/quality evidence language.
replace(
    "docs/internal/MAINTAINABILITY_MATRIX.md",
    "| Reports | `com.taxonomy.architecture.service` | `js/core/taxonomy-views.js` | `ReportApiController` | `ArchitectureReportService` | `ArchitectureReport` | No |",
    "| Reports | `com.taxonomy.architecture.service` | `js/core/taxonomy-views.js` | `ReportApiController` | `ArchitectureReportService`, `ReportRendererRegistry` | `ArchitectureReport`, `com.taxonomy.extension.api.report.*` | Yes — implement `ReportRendererExtension` |",
)
replace(
    "docs/internal/MAINTAINABILITY_MATRIX.md",
    "| Document import | `com.taxonomy.catalog.service` (import path), `com.taxonomy.shared` |",
    "| Document import | `com.taxonomy.catalog.service` (import path), `com.taxonomy.extension.api.importer` |",
)

# German deployment documentation: concise, current, and linked to the full EN evidence.
(ROOT / "docs/de/DEPLOYMENT_GUIDE.md").write_text("""# Deployment-Leitfaden

Dieses Dokument beschreibt die sicherheits- und persistenzrelevanten Mindestanforderungen. Die vollständigen, technisch maßgeblichen Details stehen im [englischen Deployment Guide](../en/DEPLOYMENT_GUIDE.md).

## Unterstützte Betriebsarten

| Modus | Datenhaltung | Zweck |
|---|---|---|
| Lokaler Maven-/Docker-Start | standardmäßig flüchtig | Entwicklung und Evaluation |
| `docker-compose.prod.yml` | dateibasierte HSQLDB und Lucene unter `/app/data` | kleine kontrollierte Produktivinstallation |
| `production,postgres` / `production,mssql` / `production,oracle` | externe Datenbank plus persistenter Lucene-Pfad | empfohlener Mehrbenutzerbetrieb |
| Render Free | flüchtig | öffentliche Demonstration, nicht kollaborative Produktion |

## Produktivstart mit Docker und Caddy

```bash
cp .env.example .env
# DOMAIN und ein eindeutiges langes TAXONOMY_ADMIN_PASSWORD eintragen
docker compose -f docker-compose.prod.yml up -d --build
```

Der Stack aktiviert `production,hsqldb`, veröffentlicht Port 8080 nicht am Host und konfiguriert:

```text
TAXONOMY_DATASOURCE_URL=jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;shutdown=true
TAXONOMY_DDL_AUTO=update
TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem
TAXONOMY_SEARCH_DIRECTORY_ROOT=/app/data/lucene-index
```

Ein Produktionsstart wird verweigert, wenn das Administratorpasswort fehlt, einem dokumentierten Platzhalter entspricht oder kürzer als 16 Zeichen ist.

## Sicherheitsanforderungen

- TLS über Caddy, Ingress oder einen freigegebenen Reverse Proxy terminieren.
- `TAXONOMY_SWAGGER_PUBLIC=false`, Audit-Logging und Login-Limitierung aktiv lassen.
- USER, ARCHITECT und ADMIN nach dem Minimalprinzip vergeben.
- Browser-Sitzungen benötigen für schreibende API-Aufrufe einen CSRF-Token.
- Diagnose, Prompts, Logs und Einstellungen sind ausschließlich über `ROLE_ADMIN` zugänglich.
- Container über Image-Digest statt nur über `latest` festlegen.

## Lokale KI und Netzisolation

`LLM_PROVIDER=LOCAL_ONNX` bedeutet lokale KI-Ausführung, aber nicht automatisch vollständige Netzisolation. Dafür zusätzlich:

- Modellrevision und SHA-256 festhalten;
- Modelle vorab bereitstellen;
- `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false` setzen;
- lokale WebJars verwenden;
- Betrieb mit gesperrtem ausgehendem Netzwerk testen.

## Abnahme

Vor dem Go-live die [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) vollständig mit Nachweisen ausfüllen. Zwingend sind Neustart-Persistenztest, Backup-Restore, Rollen-/CSRF-Tests, Accessibility-Gate, reale Analyse, Workspace-/TaxDSL-Prüfung und Exporttests.
""", encoding="utf-8")

(ROOT / "docs/de/DEPLOYMENT_CHECKLIST.md").write_text("""# Deployment-Checkliste

Diese Checkliste ist ein Freigabe-Gate. Ein Haken erfordert einen beobachteten Test, eine Konfigurationsaufzeichnung oder ein Prüfprotokoll.

## Datenhaltung und Wiederherstellung

- [ ] Keine In-Memory-Datenbank für persistente Produktion.
- [ ] Dateibasierte HSQLDB oder externe Produktionsdatenbank konfiguriert.
- [ ] Lucene-Verzeichnis liegt auf persistentem Speicher.
- [ ] Benutzer, Workspace und Architekturänderung über Container-Neuerstellung erhalten.
- [ ] Backup auf separater Instanz erfolgreich wiederhergestellt.

## Sicherheit

- [ ] Eindeutiges Administratorpasswort mit mindestens 16 Zeichen gesetzt.
- [ ] Passwortänderung, Login-Limitierung und Audit-Logging aktiv.
- [ ] Swagger nicht öffentlich.
- [ ] USER-/ARCHITECT-/ADMIN-Berechtigungen geprüft.
- [ ] Prompts, Diagnose, Logs und Einstellungen nur für `ROLE_ADMIN`.
- [ ] Browser-POST ohne CSRF schlägt fehl; Basic-/Bearer-Client ist explizit authentifiziert.
- [ ] Import-, Dokument-, Relations-, DSL-, Git-, Kontext- und Workspace-Mutationen besitzen die vorgesehene Rolle.

## KI, Datenschutz und Supply Chain

- [ ] Provider, Modell, Region, Aufbewahrung und AV-Vertrag dokumentiert.
- [ ] Secrets liegen im Secret Store, nicht im Repository.
- [ ] Lokale Modellrevision und SHA-256 dokumentiert; Runtime-Download deaktiviert.
- [ ] SBOM und echter Vulnerability-Scan geprüft.
- [ ] `taxonomy-vex.json` nicht als Schwachstellenbewertung interpretiert (`not-assessed`).
- [ ] Container-Digest und Release-Commit dokumentiert.

## Accessibility und Ergonomie

- [ ] Authentifizierter axe-Workflow grün.
- [ ] Hauptworkflow vollständig per Tastatur geprüft.
- [ ] NVDA/JAWS und VoiceOver geprüft.
- [ ] 200-%-/400-%-Zoom, 320 CSS-Pixel und Touchbedienung geprüft.
- [ ] Tabellen-/Textalternative für Diagramme und Graphen bestätigt.
- [ ] Accessibility-Erklärung und Feedbackkontakt veröffentlicht.

## Fachliche Abnahme

- [ ] Analyse liefert nachvollziehbare Scores und Begründungen.
- [ ] Hypothesen werden im aktiven Workspace gespeichert und als parsebares TaxDSL versioniert.
- [ ] Import verwendet ohne explizite Angabe den aktiven Workspace-Branch.
- [ ] Compare, Merge, Cherry-Pick, Restore und Konfliktauflösung mit echten Daten geprüft.
- [ ] ArchiMate-, Visio-, Mermaid-, JSON- und Berichtsexporte geöffnet und validiert.

Vollständige Erläuterungen: [englische Checkliste](../en/DEPLOYMENT_CHECKLIST.md) und [Deployment-Leitfaden](DEPLOYMENT_GUIDE.md).
""", encoding="utf-8")

print("QA documentation corrections applied")
