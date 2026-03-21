# Funktionsvollständigkeits-Matrix

Diese Matrix verfolgt den Lieferstatus aller wesentlichen Produktfunktionen.
Eine Funktion gilt nur als abgeschlossen, wenn alle erforderlichen Spalten ✅ zeigen.

> Siehe [Definition of Done](DEVELOPER_GUIDE.md#definition-of-done--benutzer-sichtbare-funktionen)
> für die Produktregeln.

## Endbenutzer-Funktionen (GUI-first)

| Funktion | GUI | REST | Benutzerhandbuch | Screenshot | Hilfe/Tooltip | DE/EN i18n | Status |
|---|:---:|:---:|:---:|:---:|:---:|:---:|---|
| Anforderungsanalyse | ✅ | ✅ | ✅ §4 | ✅ #15–17 | ✅ | ✅ | ✅ Vollständig |
| Bewerteter Baum-Exploration | ✅ | ✅ | ✅ §5 | ✅ #15, 35 | ✅ | ✅ | ✅ Vollständig |
| Ansichtsmodi (6 Modi) | ✅ | ✅ | ✅ §5 | ✅ #5–9, 39, 69 | ✅ | ✅ | ✅ Vollständig |
| Architekturansicht | ✅ | ✅ | ✅ §7 | ✅ #20, 38 | ✅ | ✅ | ✅ Vollständig |
| Relationsvorschläge (annehmen/ablehnen) | ✅ | ✅ | ✅ §9 | ✅ #12, 13, 36 | ✅ | ✅ | ✅ Vollständig |
| Export (ArchiMate/Visio/Mermaid/JSON) | ✅ | ✅ | ✅ §10 | ✅ #23, 33 | ✅ | ✅ | ✅ Vollständig |
| Volltextsuche | ✅ | ✅ | ✅ §11a | ✅ #29 | ✅ | ✅ | ✅ Vollständig |
| Semantische/Hybridsuche | ✅ | ✅ | ✅ §11b, §11c | ✅ #30, 31 | ✅ | ✅ | ✅ Vollständig |
| Graphexploration (Upstream/Downstream) | ✅ | ✅ | ✅ §8 | ✅ #11, 21, 37 | ✅ | ✅ | ✅ Vollständig |
| Ausfallauswirkungsanalyse | ✅ | ✅ | ✅ §8 | ✅ #22 | ✅ | ✅ | ✅ Vollständig |
| Lückenanalyse | ✅ | ✅ | ✅ §11e | ✅ #26, 27 | ✅ | ✅ | ✅ Vollständig |
| Mustererkennung | ✅ | ✅ | ✅ §11f | ✅ | ✅ | ✅ | ✅ Vollständig |
| Empfehlungen (Copilot) | ✅ | ✅ | ✅ §4 | ✅ | ✅ | ✅ | ✅ Vollständig |
| Berichte (MD/HTML/DOCX) | ✅ | ✅ | ✅ §10a | ✅ #23 | ✅ | ✅ | ✅ Vollständig |
| Workspace-Verwaltung | ✅ | ✅ | ✅ §13 | ✅ #43–45 | ✅ | ✅ | ✅ Vollständig |
| Branch-Vergleich | ✅ | ✅ | ✅ §12 | ✅ #48 | ✅ | ✅ | ✅ Vollständig |
| Kontexttransfer (zurückkopieren) | ✅ | ✅ | ✅ §12 | ✅ #49 | ✅ | ✅ | ✅ Vollständig |
| Varianten-Erstellung | ✅ | ✅ | ✅ §12 | ✅ #46, 47 | ✅ | ✅ | ✅ Vollständig |
| Merge (mit Konfliktlösung) | ✅ | ✅ | ✅ §12 | ✅ #52, 53, 58, 60, 61 | ✅ | ✅ | ✅ Vollständig |
| Cherry-Pick (mit Konfliktlösung) | ✅ | ✅ | ✅ §12 | ✅ #54, 59, 62 | ✅ | ✅ | ✅ Vollständig |
| Branch löschen | ✅ | ✅ | ✅ §12 | ✅ #57 | ✅ | ✅ | ✅ Vollständig |
| DSL-Editor (Syntax-Highlighting, Autovervollständigung) | ✅ | ✅ | ✅ §11g | ✅ #34, 40 | ✅ | ✅ | ✅ Vollständig |
| Versionsverlauf (Commits) | ✅ | ✅ | ✅ §12 | ✅ #41, 66, 67, 68 | ✅ | ✅ | ✅ Vollständig |
| Sync vom Shared / Veröffentlichen | ✅ | ✅ | ✅ §12 | ✅ #55, 56, 63–65 | ✅ | ✅ | ✅ Vollständig |
| Blattknoten-Begründung | ✅ | ✅ | ✅ §6 | ✅ #18 | ✅ | ✅ | ✅ Vollständig |
| Dokumentimport (PDF/DOCX) | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Vollständig |
| Quell-Provenienz-Tracking | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Vollständig |

## Admin-/Automatisierungsfunktionen (API-first — keine GUI erforderlich)

| Funktion | REST | API-Doku | Status |
|---|:---:|:---:|---|
| Benutzerverwaltung (CRUD) | ✅ | ✅ | ✅ Vollständig |
| LLM-Diagnose | ✅ | ✅ | ✅ Vollständig |
| Embedding-Status | ✅ | ✅ | ✅ Vollständig |
| Start-Status | ✅ | ✅ | ✅ Vollständig |
| Workspace-Eviction (Admin) | ✅ | ✅ | ✅ Vollständig |

## Legende

| Symbol | Bedeutung |
|---|---|
| ✅ | Vollständig implementiert und dokumentiert |
| ⚠️ | Teilweise — Verifizierung oder Vervollständigung erforderlich |
| 🔴 | Signifikante Lücke — GUI existiert möglicherweise, aber Doku/Hilfe/Screenshot fehlt, oder nur REST |
| ❓ | Unbekannt — Audit erforderlich |

## Screenshot-Index

Screenshots werden automatisch von `ScreenshotGeneratorIT` generiert und in `docs/images/` gespeichert.
Referenzformat: `#NN` = `docs/images/NN-*.png`. Benutzerhandbuch-Abschnittsreferenzen: `§N` = USER_GUIDE.md Abschnitt N.
