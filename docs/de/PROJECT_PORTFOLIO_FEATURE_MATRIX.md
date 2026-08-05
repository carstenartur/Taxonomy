# Funktionsmatrix des Projektportfolios

Eine Portfoliofunktion gilt erst dann als vollständig, wenn Benutzeroberfläche, API-/Servicevertrag, Browser- beziehungsweise Integrationstest, Dokumentation und Accessibility-Evidenz vorhanden sind. Ein Haken bedeutet nicht, dass jede denkbare spätere Erweiterung bereits umgesetzt ist.

| Funktion | GUI-Route / Oberfläche | API / Service | Automatisierte Evidenz | Benutzerdokumentation | Status |
|---|---|---|---|---|---|
| Projekt anlegen und auswählen | `/projects` | `/api/projects` | Primärer Portfolio-Browserworkflow | Portfolio-Handbuch §1 | Vollständig |
| Anforderungen manuell anlegen | `/projects` | Projektanforderungs-API | Primärer Portfolio-Browserworkflow | §3 | Vollständig |
| Geprüfter PDF-/DOCX-Import | `/projects/{projectId}/import` | Dokumentparser + atomarer `import-review` | Browserworkflow mit echtem PDF und Transaktionstests | §2 | Vollständig |
| Unveränderliche Anforderungsversionen | Anforderungsdetail + Import | Versions-API | Service- und Detailbrowsertests | §5 | Vollständig |
| Getrennte asynchrone Analyse | Projekt und Anforderungsdetail | persistierte `202`-Analysejobs | Browserworkflow mit drei Anforderungen | §3–4 | Vollständig |
| Dauerhaftes Job-Center und Retry | `/projects` | Jobstatus und `retry-failed` | Reload-/Recovery-Browserworkflow | §4 | Vollständig |
| Anforderungsdetail und Deep Link | `/projects/{projectId}/requirements/{requirementId}` | Anforderungs-, Versions- und Snapshot-APIs | Routen- und Browserabnahme | §5 | Vollständig |
| Snapshot-Historie und Baseline | Anforderungsdetail | Snapshot-/Detail-/Diff-APIs | Browser- und Servicetests | §5 | Vollständig |
| Evidenzgestützte Mappingprüfung | Projekt und Anforderungsdetail | Mapping-Review-APIs | Browser-Entscheidungsworkflow | §6 | Vollständig |
| Taxonomie-Picker | Lösungs-/Produktabdeckung | Taxonomiesuche | Geführter Entscheidungs-Browserworkflow | §7 | Vollständig |
| Lösungskatalog und Anforderungsverknüpfung | `/projects` | Lösungs-APIs | Portfolio-Browserworkflow | §7 | Vollständig |
| Produktkatalog und Vergleich | `/projects` | Produkt-APIs | Produktentscheidung und Vergleichsevidenz | §7 | Vollständig |
| Konflikterkennung und geführte Prüfung | `/projects` | Konflikt-APIs | Cloud-/Hosting-Konfliktworkflow | §8 | Vollständig |
| Interaktive Matrizen und Drill-down | `/projects/{projectId}/matrices` | konsolidierte Portfolio-API | Matrixfilter-/Drill-down-Workflow | §9 | Vollständig |
| Gefilterter CSV-/JSON-Matrixexport | Matrixarbeitsplatz | Clientexport mit stabilen IDs | Matrix-Browserworkflow | §9 | Vollständig |
| Portfolio-Git-Vorschau und Commit | `/projects/{projectId}/versioning` | Git-Export/Commit | Git-Browserworkflow | §10 | Vollständig |
| Materialisierungsvorschau und Anwenden | Versionierungsarbeitsplatz | `materialize-preview` und Materialisierung | No-Mutation-Unit-Test und Browserworkflow | §10 | Vollständig |
| Normaler und semantischer Branch-Merge | Versionierungsarbeitsplatz | Portfolio-Merge-API | Branch-/Merge-Browserworkflow | §10 | Vollständig |
| Projekt- und Anforderungsberichte | `/projects/{projectId}/reports` | Bericht-API | Formattests und Browservorschau | §11 | Vollständig |
| HTML, DOCX, Markdown, JSON und CSV | Berichtsarbeitsplatz | Berichtsrenderer | DOCX-Paket- und formatübergreifende Tests | §11 | Vollständig |
| Rollenabhängige Bedienelemente | Portfolio-GUI | Spring-Security-Rollen | Rollen-Browsersuiten und negative API-Tests | Navigation / §12 | Vollständig |
| Fail-closed Workspace-Isolation | alle workspacegebundenen Oberflächen | Resolver-/Interceptor-/Serviceverträge | negative Endpunkt- und Repositorytests | Betriebs-/Security-Dokumentation | Vollständig nach Merge von #584 |
| Technische Release-Blocker | Releaseworkflow | GitHub-Issue-Preflight | Release-Guard-Tests | Release-Dokumentation | Vollständig nach Merge von #584 |
| Eigenes Metrik-Credential | Betrieb | Metrik-SecurityFilterChain | vollständige Security-Chain-Tests | Deploymentdokumentation | Vollständig nach Merge von #585 |
| Atomare Letzter-Admin-Invariante | Administration | pessimistischer Admin-Lock | PostgreSQL-Parallelitätstests | Admin-Dokumentation | Vollständig nach Merge von #585 |
| Portfolio-Querybudgets | alle Portfolioansichten | Batchprojektionen | Querybudget-Tests | Performance-Dokumentation | Vollständig nach Merge von #586 |

## Evidenzvertrag

Die maßgebliche Portfolio-Browsersuite prüft einen vollständigen vertikalen Ablauf und nicht nur DOM-Präsenz:

```text
Projekt
→ getrennte Anforderungen
→ asynchrone Analyse und Wiederherstellung nach Reload
→ Snapshot- und Mappingprüfung
→ Lösungs- und Produktentscheidung
→ Konfliktlösung
→ Matrizen und Deep Links
→ geprüfter Dokumentimport
→ Git-Commit/Merge
→ Berichtsvorschau und Export
```

HTML-, ARIA- und Screenshot-Evidenz werden als CI-Artefakte veröffentlicht. Moderate, ernste und kritische Axe-Befunde lassen die Portfolio-Abnahme fehlschlagen.

## Bewusst API- beziehungsweise betriebsorientierte Funktionen

Folgende Tätigkeiten sind absichtlich keine Endbenutzer-GUI-Workflows:

- Ausführung von Deploymentmigration und Rollback;
- Datenbank-Backup und Restore;
- Rotation des Metrik-Tokens;
- Administration des Release-Blocker-Preflights;
- Konfiguration interner Worker-Leases und Recovery.

Sie stehen in Betriebs-, Deployment- und API-Dokumentation und werden nicht als fehlende GUI-Funktionen geführt.
