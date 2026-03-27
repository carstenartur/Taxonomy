# cURL-Beispiele für Automatisierung & Integration

> ⚠️ **Hinweis:** Die folgenden Beispiele richten sich an **Entwickler und Automatisierung**
> (CI/CD-Pipelines, Batch-Verarbeitung, Systemintegration).
> Für die tägliche Arbeit verwenden Sie die grafische Oberfläche. Siehe:
> - [Benutzerhandbuch](USER_GUIDE.md) für die Web-UI
> - [Beispiele](EXAMPLES.md) für GUI-basierte Workflows

End-to-End-cURL-Workflows für häufige Aufgaben. Die vollständige Endpunkt-Referenz finden Sie in der [API-Referenz](API_REFERENCE.md)
oder der [Swagger UI](http://localhost:8080/swagger-ui.html).

**Authentifizierung:** Alle API-Endpunkte erfordern HTTP-Basic-Authentifizierung (`-u admin:admin`).

---

> 💡 **GUI-Äquivalent:** Reiter **Analysieren** → Text eingeben → **Analysieren** klicken → Export-Schaltflächen in der Architekturansicht. Siehe [Beispiel 1](EXAMPLES.md#1-anforderung--architektur).

## Workflow 1: Anforderung analysieren und ArchiMate exportieren

Analysieren Sie eine Geschäftsanforderung und exportieren Sie anschließend die resultierende Architektur als ArchiMate-XML.

```bash
# Schritt 1 — Anforderung analysieren
curl -u admin:admin -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff","includeArchitectureView":true}'

# Schritt 2 — Architektur als ArchiMate-XML exportieren
curl -u admin:admin -X POST http://localhost:8080/api/diagram/archimate \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff"}' \
  --output architecture.xml

# Alternative Exportformate:
# Visio
curl -u admin:admin -X POST http://localhost:8080/api/diagram/visio \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff"}' \
  --output architecture.vsdx

# Mermaid
curl -u admin:admin -X POST http://localhost:8080/api/diagram/mermaid \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff"}'
```

---

> 💡 **GUI-Äquivalent:** Reiter **Analysieren** → Analysieren → Reiter **Lücken** → **🔍 Lückenanalyse starten** → Reiter **Empfehlungen**. Siehe [Beispiel 3](EXAMPLES.md#3-architektur-lückenanalyse).

## Workflow 2: Analyse → Lückenanalyse → Empfehlung

Identifizieren Sie Architekturlücken und erhalten Sie KI-generierte Empfehlungen.

```bash
# Schritt 1 — Anforderung analysieren
curl -u admin:admin -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Integrated hospital communication services","includeArchitectureView":true}'

# Schritt 2 — Lückenanalyse auf den bewerteten Knoten ausführen
curl -u admin:admin -X POST http://localhost:8080/api/gap/analyze \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CP":85,"BP":72},"businessText":"Integrated hospital communication services","minScore":50}'

# Schritt 3 — Architekturempfehlungen erhalten
curl -u admin:admin -X POST http://localhost:8080/api/recommend \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CP":85,"BP":72},"businessText":"Integrated hospital communication services","minScore":50}'
```

---

> 💡 **GUI-Äquivalent:** Panel **Beziehungsvorschläge** → **Vorschläge generieren** → Akzeptieren/Ablehnen → Graph Explorer. Siehe [Beispiel 4](EXAMPLES.md#4-beziehungsvorschläge).

## Workflow 3: Beziehungen vorschlagen → Prüfen → Im Graphen verifizieren

Generieren Sie Beziehungsvorschläge, akzeptieren oder lehnen Sie diese ab und überprüfen Sie das Ergebnis im Graphen.

```bash
# Schritt 1 — Beziehungsvorschläge für einen Knoten auslösen
curl -u admin:admin -X POST http://localhost:8080/api/proposals/propose \
  -H "Content-Type: application/json" \
  -d '{"sourceCode":"CR-1047","relationType":"SUPPORTS","limit":"10"}'

# Schritt 2 — Ausstehende Vorschläge prüfen
curl -u admin:admin http://localhost:8080/api/proposals/pending

# Schritt 3 — Einen Vorschlag akzeptieren (ersetzen Sie 1 durch die tatsächliche Vorschlags-ID)
curl -u admin:admin -X POST http://localhost:8080/api/proposals/1/accept

# Schritt 4 — Die neue Beziehung im Graphen verifizieren
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/downstream?maxHops=2"

# Alternativ: Mehrere Vorschläge gleichzeitig akzeptieren/ablehnen
curl -u admin:admin -X POST http://localhost:8080/api/proposals/bulk \
  -H "Content-Type: application/json" \
  -d '{"ids":[1,2,3],"action":"ACCEPT"}'
```

---

> 💡 **GUI-Äquivalent:** **DSL-Editor** (Reiter DSL) → Bearbeiten → **💾 Speichern** → **Varianten-Panel** für Merge. Siehe [Beispiel 8](EXAMPLES.md#8-architektur-dsl-workflow).

## Workflow 4: DSL-Export → Bearbeiten → Commit → Diff → Merge

Exportieren Sie die Architektur als DSL-Text, bearbeiten Sie diesen, committen Sie die Änderungen und führen Sie Branches zusammen.

```bash
# Schritt 1 — Aktuelle Architektur als DSL-Text exportieren
curl -u admin:admin http://localhost:8080/api/dsl/export

# Schritt 2 — Bearbeiteten DSL-Text in einen Feature-Branch committen
curl -u admin:admin -X POST http://localhost:8080/api/dsl/commit \
  -H "Content-Type: application/json" \
  -d '{"dslText":"element CP-1023 type Capability {\n  title: \"Communication and Information System Capabilities\";\n}","branch":"feature/add-comms","message":"Add CP-1023 capability"}'

# Schritt 3 — Branches vergleichen
curl -u admin:admin "http://localhost:8080/api/dsl/diff?sourceBranch=feature/add-comms&targetBranch=main"

# Schritt 4 — Feature-Branch in main zusammenführen
curl -u admin:admin -X POST http://localhost:8080/api/dsl/merge \
  -H "Content-Type: application/json" \
  -d '{"sourceBranch":"feature/add-comms","targetBranch":"main"}'

# Schritt 5 — Commit-Verlauf anzeigen
curl -u admin:admin "http://localhost:8080/api/dsl/history?branch=main"
```

---

> 💡 **GUI-Äquivalent:** Alle hier aufgeführten Endpunkte sind auch über die grafische Oberfläche erreichbar. Siehe [Benutzerhandbuch](USER_GUIDE.md).

## Kurzreferenz: Einzelne Endpunkte

Für einzelne Befehle, die nicht Teil eines Workflows sind, siehe die [API-Referenz](API_REFERENCE.md). Häufige Beispiele:

```bash
# Vollständigen Taxonomiebaum abrufen
curl -u admin:admin http://localhost:8080/api/taxonomy

# Volltextsuche
curl -u admin:admin "http://localhost:8080/api/search?q=voice+communications&maxResults=20"

# Semantische Suche
curl -u admin:admin "http://localhost:8080/api/search/semantic?q=voice+communications&maxResults=20"

# KI-/LLM-Anbieterstatus prüfen
curl -u admin:admin http://localhost:8080/api/ai-status

# Upstream-Graph-Exploration
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/upstream?maxHops=2"

# Ausfallwirkungsanalyse
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/failure-impact?maxHops=3"

# Bericht als Markdown exportieren
curl -u admin:admin -X POST http://localhost:8080/api/report/markdown \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Hospital communications","scores":{"CP-1023":92}}' --output report.md
```
