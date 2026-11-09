# ADMBw-Architekturabgabe vorbereiten

Taxonomy prüft einen ausdrücklich ausgewählten Git-Checkpoint und erzeugt
Modellierungsdaten mit den zugehörigen Befunden. Das eingebaute Profil
`admbw-2026.05-fffmlv-2.0-c1-s1-r7` umfasst ausgewählte Strukturregeln für
ADMBw 2026.05 und den FFFmLV-Leitfaden 2.0 vom 17. April 2026. Für die Abgabe
sind der im Vorhaben verlangte Stand und der zugehörige Leitfaden maßgeblich.
Die Bundeswehr veröffentlicht für PBN-Service- und Systemarchitekturen einen
eigenen Leitfaden. Die verlinkten Anlagen der Leitfäden
dienen der internen Projektbearbeitung. [Aktuelle Bundeswehr-Downloads](https://www.bundeswehr.de/de/organisation/ausruestung-baainbw/vergabe/methode-architektur)

## Regeln und Liefernachweise

| Thema | In Taxonomy geprüft oder vorbereitet | Für die Abgabe erforderlich |
|---|---|---|
| Architekturauftrag und Modellbeschreibung | Checkpoint, Repository, Zweig, Workspace, Projektkürzel und Prüfprofil sind im Bericht festgehalten. | Vorhabenspezifische Leitfadenfassung, geforderte Sichten, A1-Architekturbeschreibung mit Modell- und Projektmetadaten, Startpaket und Lieferformat mit dem Auftraggeber festlegen. |
| C1–S1, FFFmLV § 3.2.6.1 | Ordnerpfad `Service Specifications/C1-S1/Einordnung Fähigkeitslage`, Diagrammname, `Capability`, `ServiceSpecification` und `Exhibits` vom Dienst zur Fähigkeit. Die gespeicherte Ausgangssicht liefert den Elementumfang und den Fingerabdruck des verwendeten C3-Katalogs. Einbezogen werden gültige, akzeptierte Verbindungen innerhalb dieses Umfangs, einschließlich früherer `REALIZES`-Zuordnungen. | GUID und Katalogfassung im tatsächlichen Standardelementekatalog bestätigen und das vorhandene Element wiederverwenden. Fehlende Fähigkeit als `Issue` erfassen und klären. Falls erforderlich, `Environment` und `EnvironmentalCondition` modellieren. |
| R7, FFFmLV § 3.2.8.3 | Ordnerpfad `Requirements/R7/Requirement Derivation`. Genau ein R7-Planungseintrag pro gespeicherter Anforderungsversion. `DerivedFrom` zeigt von der funktionalen oder nichtfunktionalen Anforderung zu genau einem `OperationalConstraint`. Ein optionales `ToBeRealizedBy` zeigt zu einer `CapabilityConfiguration` oder `ResourceRole`. Für R7-MK08 wird ein Katalogsubjekt als `OperationalArchitecture` oder `OperationalRole` über `Satisfy` mit der Vorgabe verbunden. | Das `Subjekt` der Anforderung muss mit dem vorgesehenen Systemelement übereinstimmen. Satzschablone und Tagged Values nach Require.7/SAMIT fachlich prüfen und im Zielmodell ergänzen. Das Katalogsubjekt und die Rollen des nativen `Satisfy`-Verbinders im Zielmodell prüfen. |
| Element- und Diagrammkonventionen | Name und Beschreibung für jedes in der Sicht ausgegebene Element. Die Beschreibung wird als Inhalt für EA-Notes angeboten. | Eindeutige Bezeichnungen und Glossar prüfen. Deutsch ist der Regelfall. Den tatsächlichen ADMBw-Diagrammtyp und Diagrammnotizen im Zielwerkzeug kontrollieren. |
| Weitere FFFmLV- und PBN-Sichten | Der Bericht enthält C1–S1 und R7. | Die im Vorhaben verlangten Sichten und Regeln separat ergänzen. Der ältere PBN-Service-/Systemleitfaden verwendet beispielsweise für R7 auch eine andere Ableitungsquelle. Die PFK-/Require.7-Übergabe hat einen eigenen Liefervertrag. |
| Austausch | Der JSON-Bericht enthält Typen, gerichtete Beziehungen und Befunde. Das optionale Sparx-Plugin schreibt beide Sichten als native ADMBw-Diagramme in eine QEA-Kopie. | Die Kopie im Zielwerkzeug mit der vereinbarten ADMBw-MDG öffnen. Diagrammtypen, Objekt- und Katalogidentitäten, Verbindungsrollen, Notes und Tags gegen die Vorgabe vergleichen. Das vereinbarte Lieferformat verwenden. |

Die Profilregeln stützen sich auf die Abschnitte 3.2.6.1 und 3.2.8.3 des
[FFFmLV-Leitfadens 2.0](https://www.bundeswehr.de/resource/blob/5932800/bc0578b2f8c9e9c132bb7dbf8dfc29ad/leitfaden-architekturen-fuer-die-fff-mit-loesungsvorschlag-pbn--data.pdf).
Die öffentlichen Metamodellbeschreibungen dokumentieren die Verbindungsrollen.
[ADMBw-Dokumentation 2024.06](https://www.bundeswehr.de/resource/blob/5572728/0a9c0aa01c45a261bd28079b75160bfc/dokumentation-admbw-version-2024-06-en-data.pdf)

Die Sparx-EA-Anleitung beschreibt ein natives QEAX-Projekt. Das konkrete Lieferformat
ergibt sich aus dem Vorhaben. [Sparx-EA-Anleitung der Bundeswehr](https://www.bundeswehr.de/resource/blob/5570650/237332d2cb398f280a68c45ebdeea384/dokumentation-verwendung-des-admbw-im-sparx-ea--data.pdf)

## Im Architektureditor

1. Bei einer Fähigkeit unter **ADMBw-Elementzuordnung** die GUID des vorhandenen
   Katalogelements einschließlich geschweifter Klammern und die Katalogfassung
   erfassen. Bei den Diensten den Stereotyp `ServiceSpecification` und bei den
   für R7 benötigten Elementen deren ADMBw-Stereotyp auswählen. Für das Subjekt
   der operationellen Vorgabe die GUID und Fassung des Modellelementekatalogs
   erfassen.
2. In der bearbeitbaren Architektur unter **ADMBw-Abgabe vorbereiten** das
   Projektkürzel und die übernommene NC3T-Analysesicht für C1–S1 wählen.
   **ADMBw-Abgabeprofil speichern** bindet Profilversion, Sicht und
   Katalogfingerabdruck an die Architektur. Eine Konfiguration ohne
   Katalogfingerabdruck ist als Entwurf möglich.
3. Im Architektur-Fokus eine Fähigkeit oder einen Dienst aus dieser Sicht
   wählen. Der Fokus bietet der KI ausschließlich ganze zulässige Verbindungen
   mit festem Dienst, Typ und katalogisierter Fähigkeit an. Die KI wählt eine
   Kandidaten-ID und begründet den Vorschlag mit Zitaten zu beiden Endpunkten.
   Zugeordnete Anforderungen können diese Belege ergänzen. Ohne passende
   Belegstellen bleibt die KI-Auswahl leer.
   Die Entscheidung zum Übernehmen ist fachlich. Auch manuelle Befehle wahren
   die modellierten Rollen.
4. Unter **Planungsinformationen zur Anforderung** das Profil **ADMBw R7
   Anforderungsherleitung** verwenden. Die operationelle Vorgabe, das
   Katalogsubjekt und gegebenenfalls das Systemelement stehen als typisierte
   Auswahlen zur Verfügung. Ein Systemelement vom Typ `ResourceRole` steht im
   gespeicherten Abgabeprofil zur Auswahl, sobald die GUID und Katalogfassung
   des vorhandenen EA-Parts eingetragen sind. Taxonomy hält die Textversion und ihren
   SHA-256 fest. Bei einer neuen Textversion den R7-Eintrag erneut prüfen und
   speichern. Importierte R7-Zuordnungen brauchen diese lokale Freigabe auch
   bei gleichem Text und gleicher Versionsnummer.
5. Einen Checkpoint erstellen und ihn in der Versionshistorie öffnen.
   **Checkpoint prüfen** zeigt die Befunde und offene Liefernachweise direkt an.
   Der JSON-Download enthält zusätzlich die Modellierungsdaten. Die Sichten
   zeigen getrennt `CHANGES_REQUIRED` mit Befunden oder `REVIEW_REQUIRED` mit
   den offenen fachlichen und technischen Prüfungen.
6. Wenn die Strukturprüfung für beide Sichten durchgelaufen ist, für
   **ADMBw-QEA-Kopie herunterladen** eine geschlossene `.qea` bis 32 MiB und die
   GUID des gewünschten Modellwurzelpakets angeben. Die Basis muss die
   vereinbarten Kataloge enthalten. Für eine `ResourceRole` als Systemelement
   in Taxonomy die GUID und Fassung des vorhandenen EA-Parts hinterlegen.
   Der Export benötigt das [Sparx-Plugin](sparx-integration-de.md) mit Host-SPI 1.5.
7. Die erzeugte Kopie in Enterprise Architect mit ADMBw 2026.05 öffnen,
   die im Prüfbericht genannten Kontrollen durchführen und die offenen
   vorhabenspezifischen Anforderungen ergänzen.

Der Export erstellt die Paketpfade unter der ausgewählten Modellwurzel.
Eindeutige vorhandene Pakete werden wiederverwendet. Ein bereits vorhandenes
Diagramm mit demselben Namen im Zielpaket führt zum Abbruch. Die QEA-Basis und
ihre Katalogelemente werden unverändert übernommen. Der Export prüft für jede
Katalogreferenz die GUID, den nativen Elementtyp und den qualifizierten
ADMBw-Stereotyp in der Basis. Die Prüfung der tatsächlichen Katalogfassung
gehört zur fachlichen Abnahme. Die Diagrammnotizen halten den Git-Commit fest.
Die Anforderungsobjekte enthalten die ursprüngliche Modellreferenz, Textversion
und SHA-256 als Taxonomy-Tags.

Die EAM-Toolfamilie liest die gerichteten Verbindungsregeln aus der MDG.
Ihr Extraktionsablauf lässt die KI Endpunkte und Typ angeben und prüft das
erzeugte Modell anschließend. Taxonomy verwendet die gerichtete Regel bereits
beim Erzeugen der Kandidaten. Dieselbe Regel bildet die gespeicherten
C1–S1-Verbindungen auf native `Exhibits`-Verbinder ab.
[EAM-Regelkatalog](https://github.com/Mitchel85/EAM-Toolfamilie/blob/6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93/packages/core/extract.py#L191-L231)
· [EAM-Modellprüfung](https://github.com/Mitchel85/EAM-Toolfamilie/blob/6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93/packages/core/eam_ai.py#L706-L791)

Die nativen Typen und Verbindungen wurden mit der öffentlichen
[MASC-Musterarchitektur](https://github.com/Mitchel85/EAM-Toolfamilie/blob/6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93/packages/core/fixtures/Musterarchitektur_MASC.qea)
und der [MDG 2026.05](https://github.com/Mitchel85/EAM-Toolfamilie/blob/6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93/packages/rules/NAFv4-ADMBw-MDG-2026.05.xml)
abgeglichen. Die SQLite-Prüfung deckt Modellstruktur und Kopiererhalt ab.
Das Öffnen und Prüfen in der vereinbarten EA-Version, die Require.7-Pflichtfelder
und die projektbezogene Abnahme stehen separat an.

## API

`GET /api/architecture/editor.admbw.json` erwartet `repositoryId`,
`workspaceScopeKey`, `branch`, den exakten `commit` und `projectCode`. Optional
beschränkt `viewId` C1–S1 auf die gespeicherte Sicht. R7 prüft die im
Checkpoint enthaltenen Anforderungen. Die Route setzt die Exportberechtigung und
Zugriff auf den ausgewählten Git-Kontext voraus. Bei gespeichertem Abgabeprofil
müssen `projectCode` und `viewId` mit der Konfiguration des Checkpoints
übereinstimmen.

`POST /api/architecture/editor.admbw.qea` verwendet dieselben Parameter und
zusätzlich `rootPackageGuid`. Der Request-Body enthält die geschlossene QEA-Datei
als `application/vnd.sparx.qea` oder `application/octet-stream`. Der Server
wiederholt die Strukturprüfung für den angegebenen Commit. Die Antwort enthält
die QEA-Kopie als Download und den Quellcommit im Header `X-Taxonomy-Commit`.
Die Berechtigung `integration:export` und der Zugriff auf den Git-Kontext
gelten auch für diesen Download.
