# Wissenskonservierung — Use Case für Behörden

Dieses Dokument beschreibt den Einsatz des Taxonomy Architecture Analyzer als Werkzeug zur **Wissenskonservierung** in behördlichen IT-Architektur-Umgebungen.

---

## Inhaltsverzeichnis

1. [Problemstellung](#problemstellung)
2. [Lösung: Architektur-Gedächtnis](#lösung-architektur-gedächtnis)
3. [Szenarien](#szenarien)
4. [Funktionen für Wissenskonservierung](#funktionen-für-wissenskonservierung)
5. [Architekturentscheidungen nachvollziehen](#architekturentscheidungen-nachvollziehen)
6. [Integration in behördliche Prozesse](#integration-in-behördliche-prozesse)
7. [Technische Voraussetzungen](#technische-voraussetzungen)

---

## Problemstellung

Behörden stehen vor einer kritischen Herausforderung bei der **Konservierung von Architekturwissen**:

| Problem | Auswirkung |
|---|---|
| **Personalfluktuation** | Erfahrene Architekten verlassen die Behörde; ihr Wissen geht verloren |
| **Projektübergabe** | Neue Teams übernehmen Projekte ohne Kenntnis früherer Entscheidungen |
| **Lange Laufzeiten** | IT-Projekte in Behörden laufen oft 5–10+ Jahre; Entscheidungen aus der Anfangsphase sind nicht mehr nachvollziehbar |
| **Audit-Anforderungen** | Rechnungshöfe und IT-Revision verlangen Nachvollziehbarkeit von Architekturentscheidungen |
| **Wissenssilos** | Architekturwissen steckt in E-Mails, Protokollen und den Köpfen einzelner Personen |

### Typische Situation

> *„Warum wurde 2022 entschieden, den Kommunikationsdienst über die Plattform X statt Y bereitzustellen?"*
>
> → Niemand weiß es mehr. Der verantwortliche Architekt ist in Pension.

---

## Lösung: Architektur-Gedächtnis

Der Taxonomy Architecture Analyzer fungiert als **lebendes Architektur-Gedächtnis**, das:

1. **Architekturentscheidungen versioniert** — Jede Änderung an der Architektur wird in einem Git-Repository erfasst (JGit)
2. **KI-gestützte Analyse protokolliert** — Warum wurde welcher Baustein wie bewertet?
3. **Volltextsuche über History** ermöglicht — „Finde alle Entscheidungen zum Thema Kommunikation"
4. **Vergleiche zwischen Versionen** bietet — Wie hat sich die Architektur über Zeit entwickelt?
5. **Exportierbare Nachweise** generiert — ArchiMate XML, DOCX-Reports, Mermaid-Diagramme

---

## Szenarien

### Szenario 1: Personalwechsel

**Situation:** Ein erfahrener IT-Architekt verlässt die Behörde. Der Nachfolger soll die laufenden Projekte übernehmen.

**Ohne Taxonomy Analyzer:**
- Übergabedokument (PowerPoint, veraltet)
- Mündliche Übergabe (unvollständig)
- Nachfolger muss Entscheidungen rekonstruieren

**Mit Taxonomy Analyzer:**
1. Nachfolger öffnet die Anwendung
2. Sucht nach dem Projektnamen → findet alle zugehörigen Architekturentscheidungen
3. Sieht die History: welche Anforderungen analysiert wurden, wann, mit welchem Ergebnis
4. Versteht die Bewertungsgrundlage durch gespeicherte Explanation-Traces
5. Kann die Architektur weiterentwickeln, ohne frühere Entscheidungen zu widersprechen

### Szenario 2: Projektübergabe an externen Dienstleister

**Situation:** Ein IT-Projekt wird an ein neues Beratungsunternehmen übergeben.

**Ohne Taxonomy Analyzer:**
- Umfangreiche Dokumentation muss erstellt werden
- Architekturentscheidungen sind implizit (nur in Köpfen)
- Missverständnisse bei der Übergabe

**Mit Taxonomy Analyzer:**
1. Export der aktuellen Architektur als ArchiMate XML → Import in Enterprise Architect
2. Export als DOCX-Report → Übergabedokument mit Bewertungen und Begründungen
3. Git-History zeigt die Entwicklung der Architektur über Zeit
4. Dienstleister kann selbst navigieren und suchen

### Szenario 3: Audit / IT-Revision

**Situation:** Der Rechnungshof prüft die IT-Strategie und fragt: „Wie wurden die Architekturentscheidungen für das Kommunikationsprojekt getroffen?"

**Mit Taxonomy Analyzer:**
1. Suche nach „Kommunikation" → alle Analysen und Bewertungen
2. Timeline-Ansicht: wann wurden welche Entscheidungen getroffen
3. KI-Bewertungen mit Begründungstexten (Explanation-Traces)
4. Export als DOCX-Report für die Prüfakte
5. Git-Commit-History als lückenloser Nachweis

### Szenario 4: Strategische Neuausrichtung

**Situation:** Die IT-Strategie wird nach einer Legislaturperiode überarbeitet. Welche bestehenden Architekturentscheidungen sind betroffen?

**Mit Taxonomy Analyzer:**
1. Neue Anforderung eingeben → KI bewertet gegen bestehende Architektur
2. Gap-Analyse: welche Bausteine fehlen, welche sind nicht mehr relevant
3. Vergleich: alte Architektur vs. neue Anforderungen (Diff-Ansicht)
4. Impact-Analyse: welche abhängigen Systeme sind betroffen

---

## Funktionen für Wissenskonservierung

### Bereits verfügbar

| Funktion | Beschreibung | Zugang |
|---|---|---|
| **JGit-Versionierung** | Jede DSL-Änderung wird als Git-Commit erfasst | Versions-Tab → History |
| **Branching & Merge** | Verschiedene Architekturvarianten parallel pflegen | Versions-Tab → Variants |
| **Explanation-Traces** | KI-Begründungen für Bewertungen | Analyse-Ergebnis → Justification |
| **Diff zwischen Versionen** | Änderungen zwischen Commits sichtbar | DSL-Diff-Ansicht |
| **Volltextsuche** | Suche in Taxonomie-Beschreibungen | Suchfeld |
| **Semantische Suche** | KI-gestützte Bedeutungssuche | Semantic Search |
| **Graph-Exploration** | Upstream/Downstream-Abhängigkeiten | Graph-Tab |
| **Multi-Format-Export** | ArchiMate XML, Visio, Mermaid, JSON, DOCX | Export-Buttons |
| **Audit-Logging** | Wer hat wann was getan | Application Logs |
| **Timeline-View** | Chronologische Ansicht aller Architekturentscheidungen | Versions-Tab → History |
| **Dokument-Import** | PDF/DOCX importieren und automatisch Anforderungen extrahieren | Dokumentimport-Panel; siehe [DOCUMENT_IMPORT.md](DOCUMENT_IMPORT.md) |

### Geplant (Roadmap)

| Funktion | Beschreibung | Phase |
|---|---|---|
| **ADR-Export** | Architecture Decision Records aus Commit-History generieren | Phase 3 |
| **Meeting-Transkription** | Audio-Aufnahmen von Architektur-Reviews transkribieren | Phase 6 |

---

## Architekturentscheidungen nachvollziehen

### Schritt-für-Schritt

1. **Anforderung eingeben:** Beschreiben Sie die fachliche Anforderung in natürlicher Sprache
2. **Analyse durchführen:** Die KI bewertet jeden Baustein der Taxonomie (0–100)
3. **Ergebnis prüfen:** Relevante Bausteine werden farbcodiert im Baum angezeigt
4. **Begründung lesen:** Für jeden Baustein gibt es eine KI-generierte Begründung
5. **Architektur speichern:** Commit im DSL-Repository mit aussagekräftiger Nachricht
6. **Wiederfinden:** Suche, Filter, History-Navigation, oder Graph-Exploration

### Bewertungsskala

| Score | Bedeutung | Farbe |
|---|---|---|
| 90–100 | Hohe Relevanz — Kernbaustein | 🟢 Dunkelgrün |
| 70–89 | Relevant — einbeziehen | 🟡 Grün/Gelb |
| 40–69 | Möglicherweise relevant | 🟠 Orange |
| 1–39 | Geringe Relevanz | 🔴 Rot |
| 0 | Nicht relevant | ⚪ Grau |

---

## Integration in behördliche Prozesse

### IT-Architekturmanagement (TOGAF / IT-Grundschutz)

| Prozessschritt | Taxonomy Analyzer Funktion |
|---|---|
| **Architecture Vision** | Anforderungsanalyse → KI-Bewertung |
| **Architecture Development** | DSL-Modellierung → Versionierung |
| **Architecture Review** | Vergleich gegen bestehende Architektur |
| **Change Management** | Diff-Ansicht → Impact-Analyse |
| **Compliance** | Audit-Logs → Export für Prüfakte |

### Beschaffungsprozesse

Der Taxonomy Analyzer kann Beschaffungsentscheidungen unterstützen:

1. Anforderungen aus der Leistungsbeschreibung eingeben
2. Relevante Architekturbausteine identifizieren
3. Gap-Analyse: welche Bausteine werden von der bestehenden IT nicht abgedeckt
4. Export als Bewertungsgrundlage für die Beschaffungskommission

### Dokumentationspflichten

| Pflicht | Umsetzung |
|---|---|
| **IT-Rahmenarchitektur** | Export als ArchiMate XML → Import in EA/Sparx |
| **Architekturübersicht** | Export als Mermaid → Einbettung in Confluence/Wiki |
| **Prüfbericht** | DOCX-Report mit Bewertungen und Begründungen |
| **Änderungshistorie** | Git-Log als nachvollziehbarer Audit-Trail |

---

## Technische Voraussetzungen

### Minimalkonfiguration für Behörden

```bash
# Luftdichter Betrieb (keine externen API-Aufrufe)
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_ENABLED=true
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5

# Produktionsprofil mit gehärteten Defaults
SPRING_PROFILES_ACTIVE=production,postgres

# Audit-Logging aktiv
TAXONOMY_AUDIT_LOGGING=true

# Passwortänderung erzwingen
TAXONOMY_REQUIRE_PASSWORD_CHANGE=true
```

### Datensouveränität

| Anforderung | Umsetzung |
|---|---|
| **Keine Daten verlassen das Netz** | `LLM_PROVIDER=LOCAL_ONNX` |
| **Vorabdownload aller Modelle** | `TAXONOMY_EMBEDDING_MODEL_DIR` |
| **Eigene Datenbank** | PostgreSQL on-premises |
| **Zentrale Authentifizierung** | Keycloak mit LDAP/SAML |
| **Vollständiges Audit** | `TAXONOMY_AUDIT_LOGGING=true` |

---

## Verwandte Dokumentation

- [AI Transparency / KI-Transparenz](AI_TRANSPARENCY.md) — Transparenz über KI-Einsatz
- [Data Protection / Datenschutz](DATA_PROTECTION.md) — DSGVO-Dokumentation
- [Security / Sicherheit](SECURITY.md) — Sicherheitsarchitektur
- [Operations Guide / Betriebshandbuch](OPERATIONS_GUIDE.md) — Betrieb und Wartung
- [Deployment Checklist / Einführungscheckliste](DEPLOYMENT_CHECKLIST.md) — Checkliste für die Einführung
