# Datenschutz

Dieses Dokument beschreibt die Verarbeitungstätigkeiten personenbezogener Daten des Taxonomy Architecture Analyzer in Übereinstimmung mit der Datenschutz-Grundverordnung (DSGVO / GDPR) und den anwendbaren deutschen Bundes- und Landesdatenschutzgesetzen.

---

## Inhaltsverzeichnis

1. [Zweck der Datenverarbeitung](#zweck-der-datenverarbeitung)
2. [Kategorien personenbezogener Daten](#kategorien-personenbezogener-daten)
3. [Datenspeicherorte](#datenspeicherorte)
4. [Rechtsgrundlage](#rechtsgrundlage)
5. [Datenaufbewahrung und Löschung](#datenaufbewahrung-und-löschung)
6. [Datenübermittlung an Dritte](#datenübermittlung-an-dritte)
7. [Technische und organisatorische Maßnahmen](#technische-und-organisatorische-maßnahmen)
8. [Betroffenenrechte](#betroffenenrechte)
9. [Datenschutz-Folgenabschätzung](#datenschutz-folgenabschätzung)
10. [BfDI-Leitlinien für KI in der Bundesverwaltung](#bfdi-leitlinien-für-ki-in-der-bundesverwaltung)

---

## Zweck der Datenverarbeitung

Der Taxonomy Architecture Analyzer verarbeitet Daten zum Zweck der **Architekturanalyse und des Wissensmanagements**. Die Anwendung:

1. Verwaltet Benutzerkonten zur Authentifizierung und Autorisierung
2. Protokolliert Audit-Ereignisse zur Einhaltung von Sicherheitsanforderungen
3. Speichert Architekturanalyseergebnisse und versionierte DSL-Dokumente
4. Sendet optional Geschäftsanforderungstexte an externe LLM-Anbieter zur KI-gestützten Analyse

Die Anwendung verarbeitet **keine** personenbezogenen Daten von Endkunden oder Bürgern. Sie ist ein internes Werkzeug für IT-Architekten und Analysten.

---

## Kategorien personenbezogener Daten

### Benutzerkontodaten

| Datenfeld | Zweck | Speicherort | Pflichtfeld |
|---|---|---|---|
| **Benutzername** | Authentifizierung, Audit-Zuordnung | Datenbank (JPA) | Ja |
| **Passwort-Hash** (BCrypt) | Authentifizierung | Datenbank (JPA) | Ja |
| **Anzeigename** | UI-Anzeige, Audit-Protokolle | Datenbank (JPA) | Optional |
| **E-Mail-Adresse** | Benutzeridentifikation | Datenbank (JPA) | Optional |
| **Rollen** | Autorisierung (USER, ARCHITECT, ADMIN) | Datenbank (JPA) | Ja |
| **Aktiviert-Flag** | Kontolebenszyklus (Soft Delete) | Datenbank (JPA) | Ja |
| **Erstellungs-/Aktualisierungszeitstempel** | Audit-Trail | Datenbank (JPA) | Automatisch |

### Audit-Protokolldaten

Wenn `TAXONOMY_AUDIT_LOGGING=true` (Standard im Produktionsprofil):

| Datenfeld | Zweck | Speicherort | Aufbewahrung |
|---|---|---|---|
| **Benutzername** | Zuordnung von Sicherheitsereignissen | Anwendungsprotokolle | Konfigurierbar |
| **IP-Adresse** | Sicherheitsforensik, Brute-Force-Erkennung | Anwendungsprotokolle, In-Memory (Rate Limiter) | Log-Rotationsrichtlinie |
| **Zeitstempel** | Ereignisreihenfolge | Anwendungsprotokolle | Log-Rotationsrichtlinie |
| **Ereignistyp** | Compliance-Berichterstattung | Anwendungsprotokolle | Log-Rotationsrichtlinie |

### Arbeitsbereichs- und Analysedaten

| Datenfeld | Personenbezogene Daten? | Zweck | Speicherort |
|---|---|---|---|
| **Geschäftsanforderungstext** | Möglicherweise (wenn Nutzer Namen/Referenzen einfügen) | KI-Analyse-Eingabe | In-Memory (transient) |
| **Analyseergebnisse** (Scores) | Nein | Architektur-Mapping | In-Memory / Exportdateien |
| **DSL-Dokumente** | Nein (Architekturbeschreibungen) | Versionierte Architekturmodelle | JGit-Repository |
| **Commit-Metadaten** | Ja (Autorname/-E-Mail) | Versionsverlaufszuordnung | JGit-Repository |

---

## Datenspeicherorte

| Komponente | Standardspeicherort | Enthält personenbezogene Daten | Verschlüsselung |
|---|---|---|---|
| **Datenbank** (HSQLDB/PostgreSQL/MSSQL/Oracle) | In-Memory oder konfigurierte URL | Benutzerkonten, Passwort-Hashes | Datenbankebene (TDE für Enterprise-DBs) |
| **Anwendungsprotokolle** | stdout / `/app/logs/` | Audit-Ereignisse (Benutzername, IP) | Dateisystemebene |
| **JGit-Repository** | `/app/data/git` | Commit-Autor-Metadaten | Dateisystemebene |
| **Lucene-Index** | `/app/data/lucene-index` | Nein (nur Taxonomiedaten) | Nicht erforderlich |
| **Docker-Volumes** | Hostkonfiguriert | Alle oben genannten | Verschlüsselung auf Hostebene |

---

## Rechtsgrundlage

Die Verarbeitung personenbezogener Daten basiert auf:

| Rechtsgrundlage (DSGVO) | Anwendungsbereich |
|---|---|
| **Art. 6 Abs. 1 lit. b — Vertragserfüllung** | Benutzerkontenverwaltung im Rahmen eines Beschäftigungs-/Dienstverhältnisses |
| **Art. 6 Abs. 1 lit. c — Rechtliche Verpflichtung** | Audit-Protokollierung zur IT-Sicherheits-Compliance (BSI IT-Grundschutz, ISO 27001) |
| **Art. 6 Abs. 1 lit. f — Berechtigtes Interesse** | Brute-Force-Schutz (IP-Tracking), Anwendungssicherheit |

Für Behörden kann die Verarbeitung zusätzlich auf anwendbaren Verwaltungsvorschriften basieren (z. B. BDSG §26 für die Verarbeitung von Beschäftigtendaten).

---

## Datenaufbewahrung und Löschung

### Aufbewahrungsfristen

| Datenkategorie | Aufbewahrungsfrist | Löschmethode |
|---|---|---|
| **Aktive Benutzerkonten** | Dauer des Beschäftigungs-/Einsatzverhältnisses | Administrator deaktiviert Konto über API |
| **Deaktivierte Benutzerkonten** | 90 Tage nach Deaktivierung (empfohlen) | Manuelle Löschung aus der Datenbank |
| **Audit-Protokolle** | 1 Jahr (empfohlen gemäß BSI) | Log-Rotation (siehe [Betriebshandbuch](OPERATIONS_GUIDE.md)) |
| **JGit-Commit-Verlauf** | Unbegrenzt (Architektur-Wissensbasis) | `git filter-branch` für bestimmte Commits |
| **Analyseergebnisse** | Sitzungsdauer (In-Memory) | Automatische Löschung bei Sitzungsende |
| **Rate-Limiter-Daten** (IP → Versuchszähler) | 5 Minuten (Sperrdauer) | Automatischer Ablauf |

### Löschverfahren

Um die personenbezogenen Daten eines Benutzers vollständig zu entfernen:

1. **Benutzerkonto deaktivieren**: `DELETE /api/admin/users/{id}`
2. **Datenbankdatensatz löschen**: Direktes Datenbank-DELETE (nach Ablauf der Aufbewahrungsfrist)
3. **Audit-Protokolle bereinigen**: Einträge mit dem Benutzernamen aus den Protokolldateien entfernen
4. **JGit-Verlauf umschreiben** (falls erforderlich): Verwenden Sie `git filter-branch`, um Autor-Metadaten zu entfernen

> **Hinweis:** JGit-Commits sind konstruktionsbedingt nur anhängend (append-only). Das Entfernen von Commit-Metadaten erfordert ein vollständiges Umschreiben des Repositorys, was die Datenintegrität für andere Benutzer beeinträchtigen kann.

---

## Datenübermittlung an Dritte

### Externe LLM-Anbieter

Bei Verwendung cloudbasierter LLM-Anbieter (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral) werden folgende Daten an externe Server gesendet:

| Gesendete Daten | Empfänger | Zweck | Standort |
|---|---|---|---|
| Geschäftsanforderungstext | LLM-Anbieter-API | KI-gestützte Analyse | Cloud-Infrastruktur des Anbieters |
| Taxonomie-Knotennamen/-beschreibungen | LLM-Anbieter-API | Scoring-Kontext | Cloud-Infrastruktur des Anbieters |

**Wichtige Hinweise:**

- **Keine personenbezogenen Daten sollten** in Geschäftsanforderungstexten enthalten sein, wenn externe LLM-Anbieter verwendet werden
- Es gelten die Datenverarbeitungsbedingungen der jeweiligen LLM-Anbieter (siehe die Datenschutzrichtlinie des jeweiligen Anbieters)
- Für den Einsatz in Behörden verwenden Sie `LLM_PROVIDER=LOCAL_ONNX`, um alle Daten vor Ort zu halten
- Ein **Auftragsverarbeitungsvertrag (AVV)** sollte mit dem LLM-Anbieter abgeschlossen werden, wenn personenbezogene Daten in Prompts enthalten sein könnten

### Betrieb ohne Internetverbindung (Air-Gapped)

Setzen Sie `LLM_PROVIDER=LOCAL_ONNX` und `TAXONOMY_EMBEDDING_ENABLED=true` mit einem vorab heruntergeladenen Modell, um ohne jegliche externe Datenübertragung zu arbeiten:

```bash
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5
```

Siehe [KI-Transparenz](AI_TRANSPARENCY.md) für Details darüber, welche Daten wohin fließen.

---

## Technische und organisatorische Maßnahmen (TOMs)

### Technische Maßnahmen

| Maßnahme | Umsetzung |
|---|---|
| **Passwort-Hashing** | BCrypt mit Standardstärke (10 Runden) |
| **Transportverschlüsselung** | HTTPS über Reverse Proxy; HSTS-Header erzwungen |
| **Zugriffskontrolle** | Rollenbasiert (USER, ARCHITECT, ADMIN) über Spring Security |
| **Brute-Force-Schutz** | IP-basierte Ratenbegrenzung an Login-Endpunkten |
| **CSRF-Schutz** | Aktiviert für Browsersitzungen |
| **Sicherheitsheader** | X-Content-Type-Options, X-Frame-Options, HSTS, Referrer-Policy |
| **Sitzungsverwaltung** | Serverseitige Sitzungen; zustandslose REST-API |
| **Audit-Protokollierung** | Authentifizierungsereignisse mit Benutzername und IP protokolliert |
| **Eingabevalidierung** | Größenbeschränkungen für Geschäftstexte, Architekturknoten, Exportknoten |

### Organisatorische Maßnahmen

| Maßnahme | Empfehlung |
|---|---|
| **Zugriffsverwaltung** | Minimal notwendige Rollen zuweisen; vierteljährlich überprüfen |
| **Admin-Trennung** | Separate `TAXONOMY_ADMIN_PASSWORD` und `ADMIN_PASSWORD` |
| **Passwortrichtlinie** | Passwortänderungen erzwingen über `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true` |
| **Sicherheitsschulung** | Sicherstellen, dass Administratoren in sicherer Konfiguration geschult sind |
| **Vorfallreaktion** | Audit-Protokolle überwachen; Eskalationsverfahren definieren |
| **Regelmäßige Updates** | Anwendung und Abhängigkeiten aktualisieren; SBOM auf Schwachstellen prüfen |

---

## Betroffenenrechte

Gemäß DSGVO haben betroffene Personen (Benutzer der Anwendung) folgende Rechte:

| Recht | Ausübung |
|---|---|
| **Auskunftsrecht** (Art. 15) | Administrator exportiert Benutzerdatensatz über `GET /api/admin/users/{id}` |
| **Recht auf Berichtigung** (Art. 16) | Administrator aktualisiert Benutzer über `PUT /api/admin/users/{id}` |
| **Recht auf Löschung** (Art. 17) | Administrator deaktiviert Benutzer → Datenbanklöschung nach Aufbewahrungsfrist |
| **Recht auf Einschränkung der Verarbeitung** (Art. 18) | Administrator deaktiviert Benutzerkonto (Soft Delete) |
| **Recht auf Datenübertragbarkeit** (Art. 20) | Benutzerdaten sind über REST-API im JSON-Format verfügbar |

---

## Datenschutz-Folgenabschätzung

Eine Datenschutz-Folgenabschätzung (DSFA) gemäß Art. 35 DSGVO kann erforderlich sein, wenn:

- Die Anwendung personenbezogene Daten in Geschäftsanforderungstexten verarbeitet
- Die Anwendung mit externen LLM-Anbietern integriert ist (Profiling-Risiko)
- Die Anwendung über mehrere Organisationseinheiten hinweg eingesetzt wird

**Empfehlung:** Führen Sie eine DSFA durch, bevor Sie die Anwendung in Umgebungen einsetzen, in denen personenbezogene Daten in Analyseeingaben enthalten sein könnten. Für reine Architektur-Anwendungsfälle ohne personenbezogene Daten in den Anforderungen ist eine DSFA in der Regel nicht erforderlich.

---

## BfDI-Leitlinien für KI in der Bundesverwaltung

Der Bundesbeauftragte für den Datenschutz und die Informationsfreiheit (BfDI) hat Leitlinien für den Einsatz von KI/LLM-Systemen in der Bundesverwaltung veröffentlicht. Die folgende Tabelle ordnet die BfDI-Anforderungen der Umsetzung im Taxonomy Architecture Analyzer zu:

| BfDI-Anforderung | Umsetzung im Taxonomy | Status |
|---|---|---|
| **Kein Training mit personenbezogenen Daten** | Kein eigenes Modelltraining; Prompts sollten keine personenbezogenen Daten (PII) enthalten | ✅ Erfüllt |
| **Protokollierung der KI-Nutzung** | LLM-Kommunikationsprotokoll im Admin-Panel (Prompts, Antworten, Zeitstempel, Token-Anzahl); Audit-Protokollierung für Sicherheitsereignisse | ✅ Erfüllt |
| **Datenschutzaufsichtsbehörde bleibt zuständig** | In der obigen DSFA-Empfehlung referenziert; Zuständigkeit der Aufsichtsbehörde wird durch KI-Nutzung nicht berührt | ✅ Erfüllt |
| **Transparenzpflicht gegenüber Betroffenen** | [KI-Transparenz](AI_TRANSPARENCY.md) dokumentiert alle KI-Komponenten, Datenflüsse und Einschränkungen | ✅ Erfüllt |
| **Daten dürfen Deutschland/EU nicht verlassen** | `LOCAL_ONNX` für vollständig lokale Verarbeitung; `MISTRAL` (Frankreich/EU) für cloudbasierte EU-Datenresidenz | ✅ Erfüllt |
| **Zweckbindung** | KI wird ausschließlich für Architekturanalyse verwendet; kein Profiling, keine Bewertung von Einzelpersonen oder Entscheidungsautomatisierung | ✅ Erfüllt |
| **Datenminimierung** | Nur Taxonomie-Knotennamen/-beschreibungen und Geschäftsanforderungstext werden an das LLM gesendet; keine Benutzerkontodaten oder IP-Adressen | ✅ Erfüllt |

### Empfehlungen für Behördenbetreiber

1. **Verwenden Sie `LLM_PROVIDER=LOCAL_ONNX`** für maximalen Datenschutz — keine Daten verlassen den Anwendungsserver
2. **Falls ein Cloud-LLM erforderlich ist**, bevorzugen Sie EU-basierte Anbieter (Mistral) und schließen Sie einen **Auftragsverarbeitungsvertrag (AVV)** mit dem Anbieter ab
3. **Weisen Sie die Benutzer an**, keine personenbezogenen Daten in Geschäftsanforderungstexte aufzunehmen (siehe [KI-Kompetenzkonzept](AI_LITERACY_CONCEPT.md))
4. **Aktivieren Sie die Audit-Protokollierung** (`TAXONOMY_AUDIT_LOGGING=true`) für die Compliance-Dokumentation
5. **Führen Sie eine DSFA durch**, wenn personenbezogene Daten in Analyseeingaben enthalten sein könnten

---

## Verwandte Dokumentation

- [Sicherheit](SECURITY.md) — Authentifizierung, Autorisierung und Sicherheitsarchitektur
- [KI-Transparenz](AI_TRANSPARENCY.md) — KI-Modelldetails und Datenflüsse
- [KI-Kompetenzkonzept](AI_LITERACY_CONCEPT.md) — KI-Kompetenzschulungskonzept gemäß EU AI Act Art. 4
- [BSI-KI-Checkliste](BSI_KI_CHECKLIST.md) — BSI-Kriterien-Checkliste für KI-Modelle
- [Betriebshandbuch](OPERATIONS_GUIDE.md) — Sicherung, Wiederherstellung und Protokollverwaltung
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — alle Umgebungsvariablen
- [Digitale Souveränität](DIGITAL_SOVEREIGNTY.md) — Digitale Souveränität und Datenresidenz
