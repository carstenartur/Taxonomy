# Pluginfähige Modularchitektur – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended here) or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bestehende Funktionen über klare Verträge entkoppeln, externe Erweiterungen ohne Neubau des Hosts installieren und Export-/Renderer-Plugins kontrolliert im laufenden Einzelprozess nachladen können.

**Architecture:** Modularer Monolith mit einem ausführbaren Host, fachlich geschlossenen Modulen und frameworkfreien Verträgen. Bestehende Fachmodule werden explizit zusammengesetzte Startzeit-Funktionspakete. Nur zustandsarme Export-/Renderer-Erweiterungen erhalten dynamische Aktivierung; Datenautorität, Security, Migrationen und Auftragssteuerung bleiben beim Host beziehungsweise ihrem bisherigen Fachkern.

**Tech Stack:** Java 21, Maven Wrapper 3.9.16, vorhandener Spring-Boot-/JGit-/Artemis-Stack; PF4J 3.16.0 als gekapselter Loader für den begrenzten Laufzeitteil. Keine PF4J-Typen in den öffentlichen Taxonomy-SPIs.

**Spec:** [Revidierter Architekturentwurf](../specs/2026-10-08-template-api-boundary-design.md), entsprechend Version 3 der bestehenden Datei `Taxonomy/Taxonomy-Modularchitektur-2026-09-08.md`.

**Status:** Am 9. Oktober 2026 ausdrücklich freigegeben: „Plan freigegeben, direkt umsetzen.“ Direkte Umsetzung in der Sitzung mit unabhängigem Abschlussreview; keine erneuten Planfreigaben zwischen den Arbeitspaketen. Umsetzung und tatsächliche Prüfergebnisse werden gesondert protokolliert; diese Freigabe ist kein Nachweis erledigter Tasks.

## Ausgangspunkt und Umfang

- Frisch geprüfter `main`: `a8b78e2cad64576ebbb5d1353c86652f985bd06e`, 9. Oktober 2026.
- Gegenüber der geprüften Architektur-Basis `ed875906fb9bd542ad7a6e91e9b80f3ed4d75568` sind nur Release-Anforderung, Release-Orchestrierung, deren Tooling-Tests und Release-Notizen verändert; die betrachteten Produktionsschnittstellen sind unverändert.
- #628 ist seit 19. September abgeschlossen: Die sieben Fachmodule existieren bereits. Nicht erneut deren Extraktion behaupten, kein Duplikat-Issue anlegen. Die neue Arbeit ergänzt API-Entkopplung und Pluginfähigkeit.
- Offener PR #1188 verändert unter anderem Template-/Export-Controller und OpenAPI-Verträge. Seine Annotationen und neuen Vertragstests bei Integration erhalten. #1185 betrifft die Entwicklungsversion; keine eigene Versionsanhebung vornehmen.
- Vollständig bedeutet hier: alle zwölf unten stehenden Pakete einschließlich der negativen Betriebsfälle und der Gesamtverifikation. Nicht enthalten sind Microservices, OSGi-Umstellung, eine Sandbox für fremden Java-Code oder Hot-Unload von JPA-/Artemis-Fachmodulen.
- Die Vollausstattung bleibt Standard. Installierbare Startzeit-Pakete und dynamische Erweiterungen werden im Handbuch und in der Oberfläche unterschiedlich bezeichnet.

## Global Constraints

- ADR 0005: dauerhaft akzeptierte semantische Operationen mit Undo/Redo, separate wiederaufnehmbare Git-Checkpoints, keine gemeinsame ORM/JGit-Transaktion und kein Git-Commit pro Editoraktion.
- Snapshotgebundene Autorität, geprüfte Interoperabilität, Nutzerisolation, bestehende URLs und Datenformate bleiben erhalten. Kein Plugin lädt stillschweigend einen neueren oder fremden Workspace nach.
- `taxonomy-domain`, `taxonomy-dsl`, `taxonomy-export` und `taxonomy-extension-api` bleiben frameworkfrei; keine Abhängigkeit zurück auf `taxonomy-app`, keine Split Packages und kein allgemeines Adapter-Sammelmodul.
- Fachspezifische HTTP-/WebDAV-/Persistenz-/Health-Adapter behalten einen fachlichen Eigentümer. Nur echte anwendungsweite Zusammensetzung gehört nach `taxonomy-app`.
- Laufzeit-Plugins sind vertrauenswürdiger Anwendungscode. Keine Upload-/URL-Ausführungsfunktion; nur operatorseitig installierte Artefakte aus einem konfigurierten lokalen Verzeichnis. Dynamische Verwaltung standardmäßig ausgeschaltet.
- Der bestehende Preview-Port bleibt erhalten. Preview, ETags, WebDAV-Locks, Smart-HTTP, Fehlermeldungen, historische Revisionen und Restore dürfen durch Paketbewegungen nicht ihr Verhalten ändern.
- Ein Pluginwechsel darf weder Nutzerdaten löschen noch laufende Aufrufe auf eine andere Version umbiegen. Datenmigrationen sind nicht durch Code-Rollback rückgängig gemacht.
- Keine neuen unabhängigen GitHub-Testworkflows, keine abgeschwächten Gates, keine neuen Ausschluss-Tags und keine Rohlogs im Repository. Kein bezahlter oder echter LLM-Aufruf für die Entwicklung.
- Vollständiger Abschlusslauf: `./mvnw -B verify -Pci -DrunOnnxTests=true`. Dazu bleiben die tatsächlich erforderlichen Datenbank-, Browser-, Security-, Cluster- und Wiederherstellungsnachweise erforderlich; siehe [Maven-Verifikation](../../dev/MAVEN_VERIFICATION.md).

## Entscheidungen für diesen Plan

1. **Zwei Paketarten, keine zwei konkurrierenden Fach-SPIs:** Startzeit-Feature-JARs werden vor Spring-Initialisierung auf den Boot-Classpath aufgenommen; dynamische Erweiterungen liefern bestehende `TaxonomyExtension`-Verträge über einen gekapselten PF4J-Loader. Ein Artefakt darf nicht gleichzeitig über beide Wege registriert werden.
2. **Kleine öffentliche APIs:** `taxonomy-templates` erhält zunächst ein eigenes `api`-Paket. Für getrennte Feature-Installation wird dieses später in `taxonomy-templates-api` verschoben. Reportmodelle erhalten `taxonomy-reporting-api`, weil unabhängig gebaute Renderer sie benötigen. Kein Reportmodell wird bloß der Zentralisierung wegen in `taxonomy-extension-api` gesteckt.
3. **Fester Kern:** Domain-/DSL-/Export-/Erweiterungsverträge, Workspace/Versionierung/Editor, Knowledge/Katalog/Relationen, Security und Betriebskoordination bleiben fest installiert. Ihre Bereitstellung wird nicht zu einer neuen dynamischen Erweiterungsfläche.
4. **Dynamischer Umfang:** Export und Report-Rendering ohne neue Entities und ohne eigene Hintergrundjobs. LLM-Anbieter, Import-/Integrationskonnektoren, Templates und größere Fachpakete sind zunächst Startzeit-Erweiterungen. Ihre heutigen fachlichen Freigabeverträge bleiben unverändert.
5. **Cluster:** Startzeit-Plugins müssen auf beteiligten Instanzen in exakt kompatibler Version vorhanden sein. Dynamische Verwaltungsoperationen werden im Cluster zunächst ausdrücklich abgewiesen; keine nur lokal wirksame Änderung mit vorgetäuschter Cluster-Unterstützung.
6. **Standardkompatibilität:** Bisher eingebaute Erweiterungen werden nicht dupliziert. Der Mermaid-Pilot verschiebt den bestehenden Adapter; der vorhandene Exportalgorithmus bleibt die einzige Implementierung.

## Review Focus

- Gleichzeitiges Entladen und Rendern: ein bereits zugelassener Aufruf beendet sich mit seiner alten Version; neue Aufrufe erhalten einen definierten Nicht-verfügbar-Fehler. Tests in Task 10.
- Plugin mit eigener SDK-Kopie oder doppelter Beitrags-ID: vollständige Ablehnung vor Veröffentlichung, keine halbleere Registry. Tests in Tasks 6–7.
- Fehlendes Plugin nach Neustart/Restore: Daten und unbekannte IDs bleiben erhalten; kein Ersatz durch irgendeinen eingebauten Provider und kein falsches vollständiges Backup. Tests in Tasks 5, 9, 11.
- Teilanalyse und bewusst nicht bewertete Knoten: neue Reportmodelle bewahren die Unterscheidung zwischen fehlendem Score und expliziter Null. Tests in Task 3.
- Altes LLM-Jobformat, falscher Nutzerkontext und fehlende Provider-Version: kompatibles Lesen alter eingebauter Provider, ansonsten fail-closed ohne fremden Kontext oder Quota-Verbrauch. Tests in Tasks 5 und 11.

## Ausführung und Nachweisformat

Vor Task 1: frischen `main` erneut festhalten, vorhandenen Dokumentationszweig erhalten und einen isolierten Implementierungs-Worktree anlegen. Java 21 auswählen; der am 9. Oktober geprüfte Standard-PATH verwendet Java 17, während der Maven Wrapper korrekt 3.9.16 liefert. Das ist noch kein Testlauf und keine Aussage über andere mögliche JDK-Installationen.

Jede Task führt RED → GREEN → Commit aus. Neue Tests müssen zuerst den fehlenden Vertrag zeigen, nicht an einem Tippfehler scheitern. Bei einem neuen Modul werden zunächst nur POM, Reaktoreintrag und Testverzeichnis angelegt, damit der RED-Befehl tatsächlich JUnit erreicht; ein fehlendes Maven-Modul ist kein RED-Nachweis. Für neue API-Typen darf der erste Test den fehlenden Vertrag reflektiv prüfen; danach werden normale typisierte Tests verwendet. Vorherige erfolgreiche Teiltests ersetzen nie Task 12.

Die folgenden `Files` nennen Besitzer und exakte neue Dateien. Bei Umzügen werden alle durch `rg`/Compiler ermittelten Verbraucher im selben Commit aktualisiert; keine alten öffentlichen Repository-Typen als dauerhaftes zweites API-System stehen lassen. Testfixtures, Manifeste und vollständige Prozesslogs liegen unter den jeweils eigenen `src/test`- beziehungsweise `target`-Verzeichnissen.

## Task 1: Template-API von Git-Typen entkoppeln

**Files:** Neues Paket `taxonomy-templates/src/main/java/com/taxonomy/templates/api/` mit `DocumentTemplates.java`, `TemplateManifest.java`, `TemplateDescriptor.java`, `TemplateRevision.java`, `TemplateDiff.java`, `PartChange.java`, `TemplateFile.java`, `TemplatePartView.java`, `TemplatePartComparison.java`, `TemplateConflictException.java`, `TemplateNotFoundException.java`. Ändern: `taxonomy-templates/src/main/java/com/taxonomy/templates/DocumentTemplateService.java`, `DocumentTemplateGitRepository.java` und deren Verbraucher. Test: `taxonomy-templates/src/test/java/com/taxonomy/templates/TemplatePublicApiTest.java`.

**Interfaces:** `DocumentTemplates` übernimmt exakt die bestehenden öffentlichen Serviceoperationen `list`, `exists`, `upload`, `describeCurrent`, `describe`, `downloadCurrent`, `downloadCurrentValidated`, `download`, `history`, `diff`, `readPart`, `comparePart`, `restore` mit bestehenden Parametern/Exceptions und den neuen API-Rückgabetypen. Records behalten Namen und Komponenten ihrer heutigen JSON-Darstellung; `headCommit` bleibt die per-Template-Version. `TemplateSnapshot`, `CapturedTree` und JGit-Handles sind keine Fach-API.

- [x] RED: `TemplatePublicApiTest.apiDoesNotExposeRepositoryMembers` untersucht öffentliche Methodensignaturen rekursiv: `assertFalse(type.getName().contains("DocumentTemplateGitRepository$"))`; es prüft ausdrücklich Rückgabe-, Parameter-, generische und Exception-Typen. `downloadBytesAreDefensiveCopies` verändert das gelieferte Array und erwartet bei erneutem Download unveränderte Bytes.
- [x] Run: `./mvnw -B -pl taxonomy-templates -am test -Dtest=TemplatePublicApiTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: erster API-Grenztest scheitert am heutigen Repository-Typ.
- [x] Implementieren: Typen ohne Wire-Format-Änderung ausgliedern; Service implementiert `DocumentTemplates`; fachfremde Nutzer injizieren die Schnittstelle. Fachadapter bleiben im Template-Modul. Repository-interne Snapshots nicht mit-exportieren.
- [x] GREEN: derselbe Test sowie `./mvnw -B -pl taxonomy-templates -am test`. Expected: neue und bestehende Template-Tests grün, insbesondere Konkurrenz, Vorbedingungen, Sicherheitsvalidierung, Read-Cost und historische Revisionen.
- [x] Commit: `refactor(templates): expose repository-independent template API`.

## Task 2: Templatefamilien und Seed-Verantwortung öffnen

**Files:** Neu `taxonomy-templates/src/main/java/com/taxonomy/templates/api/TemplateContribution.java`, `TemplateContent.java`; ändern `DefaultDocumentTemplateBootstrap.java`, `DocumentTemplateContract.java`; Entscheidungsvertrag, Health-Indikator und `document-templates/decision-rationale-report.dotx` in den bestehenden Reporting-Bereich von `taxonomy-architecture` überführen. Tests: `taxonomy-templates/src/test/java/com/taxonomy/templates/TemplateContributionTest.java`, bestehende Bootstrap-/Preview-Tests.

**Interfaces:** `TemplateContent.open(): InputStream throws IOException`; `TemplateContribution(String templateId, String displayName, TemplateContent content, DocumentTemplateContract contract, boolean required)`. `DocumentTemplateContract` behält `templateId()` und `validate(Map<String,byte[]>)`. `DocumentTemplateReportPreview.renderPreview(): byte[]` bleibt unverändert. Beide vorhandenen Ports ziehen zusammen mit ihren Verbrauchern in `com.taxonomy.templates.api` um, damit Task 9 keine Rückabhängigkeit und kein Split Package erzeugt; es wird kein zweiter Preview-Port eingeführt. Ein Report besitzt seine Standardvorlage und seine fachlichen Regeln, generische Verwaltung nur OOXML-/Speicherregeln.

- [x] RED: zwei unabhängig definierte Beitrags-Fixtures seeden zwei IDs; nach manueller Änderung und erneutem Seeding gilt `assertEquals(editedRevision, templates.describeCurrent(id).headCommit())`. Doppelte IDs scheitern vor dem ersten Schreibzugriff; eine fehlende Pflichtvorlage verhindert Readiness, eine unbekannte optionale Familie bleibt gespeichert.
- [x] Run: `./mvnw -B -pl taxonomy-architecture -am test -Dtest=TemplateContributionTest,DefaultDocumentTemplateBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: Mehrfamilienvertrag fehlt.
- [x] Implementieren: Bootstrap iteriert validierte Beiträge, übernimmt bestehendes race-sicheres Create-only-Verhalten; Reportpaket liefert Entscheidungsvorlage. Health-Beanname und Preview-Endpunkt erhalten; keine umgekehrte Template-Abhängigkeit auf Reporting.
- [x] GREEN: gleicher Befehl plus `./mvnw -B -pl taxonomy-app -am test -Dtest=DefaultDocumentTemplateBootstrapIntegrationTest,DocumentTemplateDetailControllerLocalEditTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: lokale Editierbarkeit, Pflichtvorlage und Preview unverändert.
- [x] Commit: `refactor(templates): seed and validate contributed template families`.

## Task 3: Vollständige Reportmodelle als unterstützte Verträge

**Files:** Neu `taxonomy-reporting-api/pom.xml`, Pakete `src/main/java/com/taxonomy/reporting/api/decision/` und `.../document/`. Umziehen: `DecisionRationaleReport`, `DecisionReportScope`, `ArchitectureReportDocument` und deren rein datenbezogene Abhängigkeiten. Ändern: `taxonomy-architecture/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReportService.java`, `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/report/ReportRenderContext.java` nur soweit für die erhaltene API nötig. Test: `taxonomy-reporting-api/src/test/java/com/taxonomy/reporting/api/ReportModelContractTest.java`.

**Interfaces:** Die vollständigen bestehenden Record-Komponenten und JSON-Felder bleiben erhalten; keine Reduktion auf ein vereinfachtes Plugin-Demomodell. Berechnungen wie Figure-Planung/Übersichtserzeugung bleiben Implementierung, nicht Daten-API. `ReportRendererExtension.render(ReportRenderContext)` und `payloadAs(Class<T>)` bleiben nutzbar; konkrete Reportmodelle dürfen keine Implementationstypen referenzieren.

- [x] RED: vorhandenes Entscheidungsreport-Fixture außerhalb von `taxonomy-architecture` serialisieren und rendern; `assertNotEquals(missingScore, zeroScore)`, `assertEquals(capturedFingerprint, report.metadata().analysisSnapshotFingerprintSha256())`; transitive API-Grenzprüfung weist die heutigen Implementierungstypen nach.
- [x] Run: `./mvnw -B -pl taxonomy-app -am test -Dtest=ArchitectureDecisionReportBoundaryTest,DecisionRationaleReportTests -Dsurefire.failIfNoSpecifiedTests=false` mit ergänzter Grenzregression. Expected: neuer Test scheitert, vorhandene Semantik bleibt als Kontrollgruppe bestehen.
- [x] Implementieren: neues frameworkfreies API-Modul und vollständige Typumzüge; Legacy-Konstruktoren/Wire-Felder erhalten; DTO-Eingaben defensiv einfrieren. Architektur erzeugt das Modell; Renderer bekommen keine Repositories/Live-Services.
- [x] GREEN: `./mvnw -B -pl taxonomy-reporting-api,taxonomy-app -am test -Dtest=ReportModelContractTest,ArchitectureDecisionReportBoundaryTest,DecisionRationaleReportTests -Dsurefire.failIfNoSpecifiedTests=false`. Expected: dieselben Inhalte, keine Rückabhängigkeit auf Architekturimplementierung.
- [x] Commit: `refactor(reporting): publish snapshot-bound report model contracts`.

## Task 4: Reporting als eigener fachlicher Implementierungsbereich

**Files:** Neu `taxonomy-reporting/pom.xml`, `src/main/java/com/taxonomy/reporting/render/`, `.../templates/`, `.../config/ReportingAutoConfiguration.java`. Umziehen aus `taxonomy-architecture`: Renderer, Word-Layout/Figuren, Renderer-Registry/Decorator, Template-Preview und Task-2-Beiträge. Ableitung und `DecisionRationaleReportService` bleiben beim Architektur-Eigentümer. Ändern: `taxonomy-app/src/main/java/com/taxonomy/composition/report/` und alle Verbraucher der umgezogenen Renderertypen. Test: `taxonomy-reporting/src/test/java/com/taxonomy/reporting/ReportingIsolationTest.java`.

**Interfaces:** Reporting konsumiert Task-1-Template-API, Task-3-Modelle und bestehende Render-/Export-SPIs. Keine Abhängigkeit `taxonomy-architecture -> taxonomy-reporting` und keine `taxonomy-reporting -> taxonomy-architecture`. Fachliche Reporterstellung wird weiterhin vor Rendering durch die autorisierte Anwendungskomposition aufgerufen.

- [x] RED: Grenztest zeigt heutige Kopplung der reinen Renderer an das Architektur-Implementierungsartefakt. Extern konstruiertes Modell muss HTML/JSON/Markdown/DOCX rendern, ohne Spring-Anwendung oder Live-Taxonomiezugriff.
- [x] Run: `./mvnw -B test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false`. Expected: neue Grenzregel schlägt auf dem Vorher-Stand fehl.
- [x] Implementieren: zusammenhängende Klassen/Tests/Ressourcen umziehen; Paketnamen vollständig ändern, keine Split Packages. Root-/App-/Coverage-/Build-POMs, Docker-Eingaben und `.mvn/verification-suites.json` mitführen. Öffentliches Fehlermapping und die vorhandenen Report-URLs erhalten.
- [x] GREEN: `./mvnw -B -pl taxonomy-reporting -am test` und vollständiges Architekturprofil. Expected: Reporting ohne `taxonomy-app`/Architekturimplementierung testbar; alle Module im Graph-/Coverage-Inventar vorhanden.
- [x] Commit: `refactor(reporting): extract owned renderers and template adapters`.

## Task 5: Ausführbare LLM-Erweiterungen ohne geschlossene Provider-Enum

**Files:** Neu `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/llm/ProviderId.java`, `LlmTransport.java`, `LlmTransportExtension.java`. Ändern: `taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmGatewayRegistry.java`, `LlmProviderExtensionRegistry.java`, `LlmProviderConfig.java`, `LlmService.java`, `AnalysisRuntimeSettings.java`, eingebauten Gateway-Adaptern und `taxonomy-analysis/src/main/java/com/taxonomy/analysis/cluster/DurableClusterAnalysisExecution.java`. Test: `taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/ExternalProviderExecutionTest.java`.

**Interfaces:** `ProviderId(String value)` normalisiert trim/`Locale.ROOT`-Großschreibung; zulässig `[A-Z][A-Z0-9_.-]{0,127}`. `LlmTransport` bewahrt `sendHttpRequest(String prompt,String apiKey): String`, `extractResponseText(String rawResponseBody): String` und `providerName(): String` des heutigen Gateways. `LlmTransportExtension` ergänzt die vorhandene Metadaten-SPI um `transport(): LlmTransport`. Das neue frameworkfreie `LlmTransportException` im selben API-Paket trägt `FailureKind` (`RATE_LIMIT`, `TIMEOUT`, `UNAVAILABLE`, `INVALID_RESPONSE`), eine redigierbare Meldung und optional `Duration retryAfter`; Builtin-Adapter übersetzen vorhandene Providerfehler, der Host behält seine Wiederholungs-/Diagnosepolitik. Bestehende Enumwerte bleiben kompatible Builtin-Aliasse; offene IDs dürfen in keinem Ausführungspfad wieder per `LlmProvider.valueOf` abgewiesen werden.

- [x] RED: deterministischer Anbieter `EXAMPLE_CUSTOM` ohne Enumänderung wird ausgewählt und ausgeführt; alte Builtin-Konfiguration/Jobfixtures lesen identisch. Fehlender Anbieter erzeugt Fehler, keinen Provider-Fallback. Promptlimit/Abbruch/Permits werden genau einmal geprüft und nach Fehler freigegeben; kein echter Netzwerkaufruf.
- [x] Run: `./mvnw -B -pl taxonomy-analysis -am test -Dtest=ExternalProviderExecutionTest,LlmProviderExtensionRegistryTest,LlmGatewayRegistryPromptBudgetTest,LlmProviderFrozenScopeTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: offene Provider-ID scheitert am heutigen Enum-Gate.
- [x] Implementieren: ausführbare Builtin-Beiträge registrieren, String-ID durch Auswahl, Preferences, eingefrorene Aufträge und Cluster-Nachrichten führen; alte IDs/Wire-Felder erhalten. Bestehende Budget-, Permit-, Record/Replay- und Fehlerregeln im fachlichen Host behalten. LOCAL_ONNX nicht als generativen HTTP-Provider ausgeben. API-Key bleibt pro autorisiertem Aufruf, nie Bestandteil des Deskriptors oder Jobmanifests.
- [x] GREEN: gleicher Befehl und `./mvnw -B -pl taxonomy-analysis -am test`. Expected: Builtins und neuer deterministischer Anbieter nutzen dieselben Grenzen; alte Jobdaten bleiben lesbar.
- [x] Commit: `feat(analysis): support independently supplied provider transports`.

## Task 6: Pluginmanifest und atomarer Erweiterungskatalog

**Files:** Neu `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/plugin/PluginDescriptor.java`, `PluginRequirement.java`, `PluginIdentity.java`, `ExtensionKey.java`, `ExtensionCatalog.java`, `ExtensionLease.java`, `TaxonomyPlugin.java`; neues Modul `taxonomy-extension-runtime` mit `com.taxonomy.extension.runtime.PluginCatalog`. Tests: `taxonomy-extension-runtime/src/test/java/com/taxonomy/extension/runtime/PluginCatalogTest.java`.

**Interfaces:** `PluginIdentity(String id,String version,String artifactSha256)`. `PluginDescriptor(PluginIdentity identity,String hostApiRange,List<PluginRequirement> requires,Set<String> capabilities,PluginMode mode)`, `PluginMode` = `STARTUP` oder `DYNAMIC`. `PluginRequirement(String id,String versionRange)`. Plugin-IDs sind kleingeschriebene `[a-z][a-z0-9.-]{0,127}`; die Host-SPI beginnt bei `1.0.0`, getrennt von der Produktversion. Der Host errechnet `artifactSha256` aus den geladenen Bytes, nicht aus einer selbstbehaupteten Manifest-Prüfsumme. `TaxonomyPlugin.extensions(): List<TaxonomyExtension>`; `ExtensionKey(ExtensionKind kind,String id)`. `ExtensionCatalog.acquire(ExtensionKey key,Class<T> expectedType): ExtensionLease<T>`; Lease hat `extension()`, `plugin()` und `close()`.

- [x] RED: Publish zweier Beiträge mit einer kollidierenden ID lässt den Katalog vollständig unverändert (`assertEquals(before, after)`); gleiche Format-ID in verschiedenen Reportfamilien bleibt erlaubt; inkompatible Host-Range und fehlende Abhängigkeit werden vor Veröffentlichung abgewiesen.
- [x] Run: `./mvnw -B -pl taxonomy-extension-runtime -am test`. Expected: neue Vertragsregression zeigt fehlende Implementierung; keine Compile-Tippfehler als RED werten.
- [x] Implementieren: kompletter validierter Katalog-Snapshot pro Veröffentlichung, Referenzzählung pro Pluginversion, deterministische Deskriptorreihenfolge. Builtins und externe Beiträge benutzen denselben Katalog. Bestehende Registries werden Fassaden darüber; keine zweite unabhängig abweichende Liste in `/api/extensions`.
- [x] GREEN: gleicher Befehl; bestehende `ExtensionRegistryTest`, `ExportFormatExtensionRegistryTest` und `ReportRendererRegistryDecoratorTest` im jeweiligen Reaktor mitführen. Expected: Dublettenregeln erhalten, alle Leser sehen entweder alten oder neuen vollständigen Stand.
- [x] Commit: `feat(extensions): add versioned atomic plugin contribution catalog`.

## Task 7: Externe JARs sicher und reproduzierbar laden

**Files:** Neu `taxonomy-extension-runtime/src/main/java/com/taxonomy/extension/runtime/PluginArtifactValidator.java`, `Pf4jPluginRuntime.java`; `taxonomy-app/src/main/java/com/taxonomy/composition/plugins/PluginRuntimeConfiguration.java`. Tests: `taxonomy-extension-runtime/src/test/java/com/taxonomy/extension/runtime/PluginArtifactLoadingTest.java` mit echten Test-JARs.

**Interfaces:** `PluginArtifactValidator.validate(Path artifact): PluginDescriptor`; `Pf4jPluginRuntime.install(Path artifact): PluginIdentity`, `start(String pluginId)`, `stop(String pluginId,Duration deadline)`, `unload(String pluginId)`. Beiträge werden über `ServiceLoader<TaxonomyPlugin>` im Plugin-Classloader gefunden; PF4J ist Loader/Lifecycle-Implementierung, nicht zweite fachliche SPI.

- [x] RED: echte JAR-Fixtures für gültigen Beitrag, fehlende Abhängigkeit, falsche API-Range, doppelte ID, mitgelieferte Host-SDK-Klassen, defektes Service-Manifest und Pfad außerhalb des Pluginverzeichnisses. `assertEquals(previousCatalog, catalogAfterRejectedArtifact)`; Dateien mit Leerzeichen funktionieren.
- [x] Run: `./mvnw -B -pl taxonomy-extension-runtime -am test -Dtest=PluginArtifactLoadingTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: keine externe Lade-/Validierungsfunktion vorhanden.
- [x] Implementieren: PF4J `3.16.0` zentral pinnen, Parent-Identität für veröffentlichte Taxonomy-API-Klassen erzwingen, SDK-Kopien im Plugin ablehnen. Artefakte zuerst in einen privaten, unveränderlichen Digest-Pfad übernehmen; exakt diese Bytes validieren und laden, damit ein Dateiaustausch zwischen Prüfung und Classloading keine andere Version einschleust. Keine URL-Downloads, keine Pfadauflösung aus HTTP-Eingaben, keine halb gestarteten Beiträge. Plugin-Verzeichnis und ausführbare Standarddistribution dokumentieren.
- [x] GREEN: gleicher Befehl plus Modul-Suite. Expected: tatsächlich getrennte Plugin-Classloader, gemeinsame API-Klassenidentität, fehlgeschlagener Start hinterlässt keine Registrierung/offene Jar-Datei.
- [x] Commit: `feat(plugins): load validated external extension artifacts`.

## Task 8: Bestehenden Mermaid-Export als externes Paket liefern

**Files:** Neu `plugins/taxonomy-mermaid-plugin/pom.xml`, `src/main/java/com/taxonomy/plugins/mermaid/MermaidPlugin.java` und umgezogener `MermaidExportExtension.java`; Service-Datei `src/main/resources/META-INF/services/com.taxonomy.extension.api.plugin.TaxonomyPlugin`. Bisheriger Adapter: `taxonomy-app/src/main/java/com/taxonomy/export/service/MermaidExportExtension.java`. Integrationstest: `taxonomy-build/src/test/java/com/taxonomy/build/ExternalPluginPackagedIT.java`.

**Interfaces:** Vorhandenes `ExportFormatExtension.export(ExportContext): ExportResult`, Format-ID `mermaid`, Extension `mmd`, MIME `text/plain; charset=UTF-8`. Bestehender `MermaidExportService` bleibt einziger Algorithmus. Plugin-ID `taxonomy.mermaid`; keine hostinternen Serviceimports.

- [x] RED: verpackten Host starten, dessen unveränderter SHA-256 festgehalten ist; extern separat gebautes Plugin hinzufügen und über vorhandenen Exportweg ausführen. `assertArrayEquals(existingExpectedOutput, pluginOutput)` für DE/EN. Ohne Plugin ist nur das Format nicht verfügbar, nicht der ganze Server.
- [ ] Run: `./mvnw -B verify -Pplugin-packaging-tests` nach Aufnahme dieses Maven-eigenen Profils in den bestehenden Katalog. Expected: das bisherige Paket besteht den externen Installationsnachweis noch nicht.
- [x] Implementieren: vorhandenen Adapter verschieben, Tests mitnehmen, unabhängigen Plugin-POM gegen installierte exakte SDK-Artefakte bauen. Der Maven-Integrationstest installiert diese in ein leeres temporäres Repository, kopiert nur das Pluginprojekt dorthin und baut ohne Host-Quellbaum. Standarddistribution liefert das Plugin mit; keine zweite eingebaute Registrierung. Legacy- und generischen Exportweg auf dieselbe Registry führen.
- [x] GREEN: gleicher Profilbefehl; Host-Hash vor/nach Installation gleich, externe JAR nicht in `BOOT-INF/lib`, gültiger realer HTTP-Download und bestehende Mermaid-Regressionsfälle grün. Vollständiger CI-Nachweis auf `dd3448d2` am 10. Oktober, siehe Abschlussnachtrag unten.
- [x] Commit: `feat(export): package existing Mermaid extension as external plugin`.

**Zwischennachweis 9. Oktober 2026:** Der fokussierte Failsafe-Lauf `test-compile failsafe:integration-test failsafe:verify -Pplugin-packaging-tests -Dit.test=ExternalPluginPackagedIT` ist grün (1 realer Paket-/HTTP-Test und 5 separat ausgeführte Plugin-Tests). 46 Node-Vertragstests sowie der vollständige Architektur-Selektor einschließlich ergänzter Regressionen sind grün. Der vollständige Profil-Lauf bleibt bis Task 9/12 ausdrücklich offen.

## Task 9: Bestehende Fachpakete startzeitlich optional machen

**Files:** Neu `taxonomy-templates-api/pom.xml` mit dem aus Task 1 extrahierten API-Paket; `taxonomy-app/src/main/java/com/taxonomy/composition/plugins/FeatureAssembly.java`, `FeaturePluginPreflight.java`; explizite Auto-Konfiguration in Templates, Reporting, Architecture, Analysis, Portfolio und Interop. Ändern: `taxonomy-app/src/main/java/com/taxonomy/TaxonomyApplication.java`, betreffende Kompositionskonfigurationen, Root-/App-/Coverage-/Build-POMs, Dockerfile und Distributionsstartskripte. Test: `taxonomy-build/src/test/java/com/taxonomy/build/OptionalFeaturePackagedIT.java`.

**Interfaces:** `FeatureAssembly.validate(Set<String> installed): FeatureSet`; `FeatureSet.has(String id): boolean`. IDs: `templates`, `architecture`, `reporting`, `analysis`, `portfolio`, `interop`. Mindestabhängigkeiten: `reporting -> templates`; `analysis -> architecture`; `portfolio -> analysis,architecture`. Interop konsumiert weiterhin seine Workspace-Ports; konkrete appseitige Portfolio-Brücken sind nur bei Portfolio aktiv. Zusätzliche echte Abhängigkeiten werden durch den gemessenen Graphen aufgedeckt und vor Umsetzung ausdrücklich bereinigt oder deklariert, nicht versteckt.

- [x] RED: denselben Host starten mit Kern allein; Kern+Templates; Kern+Architecture; Kern+Templates+Architecture+Reporting; zusätzlich Analysis+Portfolio; Vollausstattung. Die abgewählten Implementierungs-JARs müssen physisch fehlen. Eine invalide Kombination scheitert vor Serving mit konkreter fehlender Fähigkeit, nicht mit `NoClassDefFoundError`.
- [ ] Run: `./mvnw -B verify -Pplugin-packaging-tests`. Expected: aktuelle unbedingte Verdrahtung verhindert mindestens die Kernkombination.
- [x] Implementieren: explizite, vor Bean-Erzeugung validierte Startzeit-Zusammensetzung; Boot `PropertiesLauncher`/dokumentierter externer Classpath für Feature-JARs. Kein globaler Scan, der optionale Klassen trotzdem erzwingt. Entities und Migrationen nur beim Start registrieren; keine schema-droppende Abwahl. HTTP-Adapter und Ressourcen gehen mit ihrem Feature. API-Pakete besitzen nach Extraktion genau einen Maven-Eigentümer.
- [x] GREEN: alle sechs Kombinationen gegen unveränderten Host; Readiness, ausgewählte echte HTTP-/WebDAV-/Reportaufrufe und fehlende Feature-Routen prüfen. Vollausstattung bleibt mit bisherigen URLs funktionsgleich.
- [x] Commit: `feat(modules): support dependency-checked startup feature packages`.

**Zwischennachweis 9. Oktober:** Acht physische Installationen, ein früher Abhängigkeitsfehler und zwei Archiv-/Ressourcentests bestehen (11 Failsafe-Tests, keine Auslassungen). Die kleineren Installationen benötigen einen expliziten Abwesenheitsadapter für den Portfolio-Git-Port; Schreibzugriffe werden vor Provisionierung und Git-Änderungen abgewiesen. Die vollständigen Profile folgen in Task 12.

## Task 10: Begrenztes dynamisches Laden, Drain und Wiederaufnahme

**Files:** Neu `taxonomy-extension-runtime/src/main/java/com/taxonomy/extension/runtime/PluginLifecycleCoordinator.java`; `taxonomy-app/src/main/java/com/taxonomy/composition/plugins/PluginAdministrationController.java`, `PluginOperationExceptionHandler.java`. Tests: `taxonomy-extension-runtime/src/test/java/com/taxonomy/extension/runtime/PluginLifecycleConcurrencyTest.java`, `taxonomy-app/src/test/java/com/taxonomy/composition/plugins/PluginAdministrationSecurityTest.java`.

**Interfaces:** `activate(String id): PluginIdentity`; `deactivate(String id,Duration timeout): PluginOperationResult`, Status `STOPPED`, `DRAINING` oder `REJECTED`. Neue Leases werden nach Deaktivierungsbeginn abgewiesen; bereits erworbene Leases behalten Version und Objekte. Bei Timeout bleibt das Plugin DRAINING und geladen; kein erzwungenes Schließen laufender Ressourcen. Update erfolgt erst nach erfolgreichem Drain.

- [x] RED: ein mit Latches blockierter echter Renderer hält seine Lease; Deaktivieren blockiert neue Aufrufe, bestehender Aufruf liefert alte Version; erst dessen Abschluss erlaubt Jar-Freigabe. Defekter Start veröffentlicht nichts; 50 Lade-/Stopzyklen lassen keine registrierten Listener/Threads/Dateihandles zurück. Kein blindes GC-Timing als einziger Leak-Nachweis.
- [x] Run: `./mvnw -B -pl taxonomy-app -am test -Dtest=PluginLifecycleConcurrencyTest,PluginAdministrationSecurityTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: dynamische Operationen fehlen.
- [x] Implementieren: Management nur bei `taxonomy.plugins.dynamic.enabled=true`, nur für ADMIN unter bestehenden Auth-/CSRF-Regeln und nur per installierter Plugin-ID. Keine beliebigen Klassen-/Dateipfade. Im Cluster und für STARTUP-Pakete: aussagekräftige Ablehnung. Aktive abhängige Plugins verhindern Entfernung. Endpunkte mit vollständigem OpenAPI-Vertrag und redigierten Fehlern.
- [x] GREEN: gleicher Befehl und verpackter Laufzeittest aus dem Packaging-Profil. Expected: Hot-Load/Export/Drain/Unload ohne Neustart, Security-Negativfälle und Fehler-Recovery belegt.
- [x] Commit: `feat(plugins): coordinate bounded runtime activation and draining`.

**Nachweis 9. Oktober:** 19 Runtime-Tests einschließlich 50 echter Ladezyklen, drei ADMIN/USER/CSRF-Sicherheitsfälle und 12 Pakettests sind grün. Der reale Host durchläuft drei HTTP-Deaktivierungs-/Aktivierungszyklen mit unverändertem Artefakt. Die vollständigen Profile und der Abschlussreview bleiben Task 12.

## Task 11: UI, Aufträge, Backup und Betrieb zusammenführen

**Files:** Ändern: `taxonomy-app/src/main/resources/static/js/shared/taxonomy-export.js`, `decision-export-dialog.js`, vorhandene Extension-/Capabilities-API, Navigation/i18n, `taxonomy-analysis/src/main/java/com/taxonomy/analysis/cluster/`, `taxonomy-app/src/main/java/com/taxonomy/backup/BackupCoverageInventory.java` und Backupmanifest-Verarbeitung. Neu: `taxonomy-app/src/test/java/com/taxonomy/composition/plugins/PluginRecoveryContractTest.java`, `.github/scripts/plugin-capabilities.test.mjs`. Maven-eigene Browserabnahmen in vorhandene UI-Suite integrieren.

**Interfaces:** Ein Aufruf/Job erhält `PluginIdentity` und eine nicht geheime Konfigurationsrevision, nicht serialisierte Implementierungen oder Classloader. Alte Builtin-Aufträge bleiben lesbar. UI liest verfügbare Deskriptoren, kein statischer Sonderfall pro externem Format; keine beliebigen Plugin-Skripte ausführen.

- [x] RED: fehlendes Format verschwindet aus Auswahl, laufender Export behält seine Identität; Neustart ohne benötigte Provider-Version hält Auftrag mit verständlichem Grund an, statt anderes Modell zu wählen. Backup mit fehlendem datenhaltenden Feature darf nicht als vollständig markiert werden; Restore verändert keine Daten, bevor Plugin-/Versionsvoraussetzungen erfüllt sind.
- [x] Run: `./mvnw -B -pl taxonomy-app -am test -Dtest=PluginRecoveryContractTest,BackupCoverageInventoryTest,LlmProviderFrozenScopeTest -Dsurefire.failIfNoSpecifiedTests=false`; Browserfälle über vorhandenen Maven-UI-Eigentümer. Expected: fehlende Versions-/Capabilities-Behandlung wird sichtbar.
- [x] Implementieren: zustandsgebundene Identität im bestehenden Auftragsvertrag; Worker prüft Verfügbarkeit vor Quota-Verbrauch. Bestehende JMS-Signale für Status nutzen, kein neues zentrales Polling. Fehlende Features/Versionen erhalten Nutzerdaten und werden im Betrieb klar angezeigt. UI-Zustände in DE/EN, Tastaturbedienung, Dark Mode und Rechteprüfung mitführen.
- [x] GREEN: gleiche Regressionen, reale HSQLDB-Neustart-/Restore-Abnahme und vorhandene Maven-Browser-/Artemis-Lanes. Expected: keine Kontextvermischung, keine vergessenen Daten und keine falsely-complete Backups. Plugin-Vollprofil, sechs Browser-Shards und Kubernetes-/Artemis-Abnahme auf `dd3448d2` erfolgreich; die verbleibende Gesamtprüfung ist in Task 12 ausgewiesen.
- [x] Commit: `feat(plugins): bind capabilities and recovery to plugin identity`.

**Stand 9. Oktober:** Implementierung vorhanden. 111 Backup-/Restore-/SQL-Vertragstests, 19 Provider-/Report-/Reformulierungsprüfungen (einschließlich separatem Checkpoint-JVM) und 1.011 JavaScript-Vertragstests bestehen ohne Fehler oder Skips. Der unabhängige Review ist durchgeführt; seine wichtigen Befunde sind korrigiert. Vollständige Paket-, Browser-, Prozessabbruch- und CI-Nachweise bleiben vor Abschluss erforderlich.

## Task 12: Architekturregeln, bestehende Gates und präziser PR-Abschluss

**Files:** `taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleExtractionTest.java`, bestehende Architektur-/Dependency-Ratchet-Tests, `.github/architecture-contexts.json`, `.github/architecture-exceptions.json`, Coverage-/Reaktor-POMs, Docker-/CI-Pfade; `README.md`, `docs/dev/07-extension-points.md`, `docs/dev/extension-module-boundaries.md`, deutsche/englische Installationsdokumentation. Bestehende Architekturdatei aktualisieren, keine Kopie anlegen.

**Interfaces:** Architekturtests verbieten neue Rückabhängigkeiten, Split Packages und fremde Repositoryzugriffe. Das vorhandene Backup-Split-Package wird aufgelöst, indem app-interne Klassen aus `com.taxonomy.backup` nach `com.taxonomy.backup.runtime` umziehen; frameworkfreie Domainverträge bleiben bei ihrem Besitzer. Keine Paketgrenzen bloß durch zusätzliche Ausnahmen grün machen.

- [x] RED: um eine gezielte negative Fixture ergänzte Modulprüfung erkennt ein Split Package und eine SDK->Host-Abhängigkeit. Expected: zwei bewusst schlechte Fixtures werden abgewiesen; der reale Vorher-Stand zeigt das vorhandene Backup-Split-Package.
- [x] Implementieren und GREEN: Paketumzug samt Verbrauchern, gemessene Modulgraph-/Ressourcenprüfungen und Dokumentation. `./mvnw -B test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false` muss ohne abgeschwächte Baselines bestehen.
- [ ] Run vollständig: `./mvnw -B verify -Pci -DrunOnnxTests=true`; zusätzlich `./mvnw -B verify -Pplugin-packaging-tests`, `./mvnw -B verify -Pdatabase-mssql`, `./mvnw -B verify -Pdatabase-oracle` und die laut aktuellem CI-Katalog separat erforderlichen Security-/Artemis-/Produkt-/Browser-/Recovery-Lanes. Expected: echte positive Testanzahlen, keine erforderliche Lane übersprungen; lokale Runtime-Lücken konkret ausweisen.
- [x] Ein unabhängiger Abschlussreview des gesamten Diffs; wichtige Befunde mit RED→GREEN beheben. Dokumentationslinks, tatsächliche Boot-JAR-/Plugin-Inhalte, vollständige Standarddistribution und externe SDK-Builds kontrollieren.
- [x] Fokussierte PR-Folge auf dem vorhandenen Architekturauftrag referenzieren: (1) API/Reporting, (2) ausführbare SPIs und Startup-Plugins, (3) Optionalität/Lifecycle/Betrieb. Keine Duplikat-Issues, kein ungeprüfter Merge. Ergebnisse in #628 als Folgearbeit mit konkreten SHAs/PRs/Nachweisen verdichten; die alte abgeschlossene Extraktion nicht rückwirkend als unerledigt bezeichnen.
- [x] Commit: `test(architecture): enforce plugin boundaries and distribution contracts`.

**Zusätzliche Verifikation 9. Oktober:** 49 gezielte OpenAPI-/Report-/Interop-/Publikations-/Recovery-Tests sind grün, darunter echte Anwendungsneustarts, erzwungener Prozessabbruch mit gespeicherter Providerbindung und Lease-Fencing. Feature-Scans übernehmen die Boot-Testfilter. V33 lief im PostgreSQL-Job erfolgreich; die Versionsliste und konkrete neuen Spaltenprüfungen sind für den nächsten CI-Lauf aktualisiert. Die vollständigen Profile bleiben offen. PR-Folge: #1189, #1190, #1191; alle Änderungen referenzieren die vorhandene Architekturarbeit #628.

**Paket-/Betriebsnachweise:** Der erweiterte fokussierte Paketlauf ist grün: 13 reale Paket-/HTTP-Tests und 5 Tests im unabhängig gebauten Mermaid-Projekt. Acht physische Installationen, ungültige Kombination und Voll→Kern→Voll mit unveränderten Vorlagen/Git-Historie sind belegt. Native Linux-App-Image gebaut, alle acht JAR-Hashes geprüft und installierter Launcher mit neuer persistenter HSQLDB gestartet (sechs Features, Mermaid, Readiness/Vorlagen/Taxonomie HTTP 200). Ein fehlender Pakettest führt im wirklichen Besitzer taxonomy-build weiterhin zu Build Failure (Negative-Control MissingPluginPackagingCanary); die Profilzuordnung toleriert ausschließlich Nichtbesitzer. Der Szenariofall besteht mit 1,4-GiB-Test-JVMs. Ungekürzte Profilwiederholung und abschließende aktuelle CI bleiben offen; die beiden vollständigen lokalen Versuche wurden zuvor durch das 8-GiB-Umgebungslimit beendet.

**Verifikation und Security-Nacharbeit:** Der komplette lokale Lauf auf `d22de558` hat 7.432 Tests ausgeführt: 7.427 erfolgreich, fünf Browserfälle konnten wegen `socket() failed: Operation not permitted` beim Chrome-Start nicht ausgeführt werden (Surefire: ein Fehlschlag, vier Fehler; keine Skips). Alle übrigen Fälle einschließlich Backup-Download und Wiederaufnahme sind erfolgreich; kein weiterer OOM-Abbruch. Coverage und die nachgelagerte Paketprüfung wurden wegen dieses Fehlers nicht erreicht. Die identischen fünf Browserfälle bestehen auf demselben Commit in der bestehenden CI-Szenario-Lane (26 Tests insgesamt); außerdem alle sechs CI-UI-Shards. Dies ist kein lokaler Gesamt-PASS.

Die separate CodeQL-Push-Lane meldete eine Regex-Laufzeitgefahr und drei nicht gemeinsam synchronisierte Plugin-Zustandszugriffe. Der gemeinsame Runtime-Monitor beseitigt die drei Race-Befunde; die nächste CodeQL-Auswertung bestätigte deren Wegfall. Die Regex-Warnung blieb zunächst bestehen. Ein neuer Regressionstest reproduzierte anschließend 50,7 Sekunden für die Modellidentität eines langen Proxy-Pfads ohne `/models/`; diese Suche verwendet jetzt lineare Stringoperationen unter Erhalt der bisherigen Ergebnisse. Vier Modelltests sind danach erfolgreich, zusammen mit Binding-/Setup-Prüfungen zehn Fälle ohne Fehler oder Skips.

Die Prüfung des vollständigen innersten JAR-Namens verhindert fremde Dateinamen, Suffixe und `!` innerhalb echter Dateinamen, auch für `jar:file:` und beide Boot-URL-Formen. Zwölf reale Artefaktidentitätsfälle, insgesamt 35 Runtime-Tests, sind grün; der entsprechende zweite PR-Schnitt besteht mit 28 Runtime- und vier Modelltests. Der unabhängige Nachreview bestätigt die geschlossene Namenslücke.

Die unveränderte vollständige PR-1-CI ist inzwischen erfolgreich: 8.388 ausgeführte Tests bestanden, 79 vorhandene bedingte Skips separat ausgewiesen; 18 UI-Szenarien genau einmal in sechs artefaktgebundenen Shards. Zeilencoverage 92,68 %, Branchcoverage 76,22 %. Der PR-Merge-Commit `060929e31c800881bbe23290f8bcb558531d0ff8` besitzt denselben Baum `3e07ee6ce162d2b28dfdbc359dfe84e2df2eef5c` wie Head `5ac87ffb`. Datenbankmatrix, ONNX, CodeQL-Push-Lanes und weitere vorhandene Gates sind grün.

Die erneute fokussierte Paketprüfung auf `9972e594` führte 14 ITs aus: zwölf reale Optionalitäts-/HTTP-/Reaktivierungsfälle bestanden; beide Archivprüfungen scheiterten an den neu mitgelieferten Prüfsummen-Sidecars. Die Inspektion und die native Paketierung prüfen diese Dateien nun gegen den regulären JAR-Eigentümer, SHA-256 und Dateinamen und erhalten ihre Bytes. 22 native Vertragsprüfungen einschließlich beschädigter, falsch benannter und verwaister Sidecars bestehen. Eine im Nachreview gefundene checked-Exception-Signatur wurde eingeengt. Aktuelle Paketprüfung, tatsächlicher nativer Start sowie vollständige CodeQL-/Gesamt-CI für die korrigierten Plugin-PRs bleiben offen. Keine Security-Baseline, Gate oder Testauswahl abgeschwächt.

**Aktueller Distributionsnachweis:** Der fokussierte Lauf auf `05c68aa6` besteht mit 283 eindeutigen JUnit-Fällen (davon 14 reale Paket-ITs) sowie fünf Tests im unabhängigen SDK-Build. Native Linux-Distribution dieses Heads gebaut und gegen neue persistente HSQLDB gestartet: sechs Features, Mermaid und HTTP 200 für Readiness/Vorlagen/Taxonomie; alle acht JARs und das SHA-256-Sidecar stimmen bytegenau mit den Installationseingaben überein.

CodeQL meldete danach weiterhin genau eine Regex-Laufzeitgefahr. Der Detailartefaktabruf liefert HTTP 403; die vorhandene Gate-Ausgabe nennt deshalb künftig zusätzlich die bereits geparste Quelldatei, ohne Schwelle, Baseline oder Entscheidung zu ändern. Im Provider-Katalog ist außerdem die zweite langsame Identitätsnormalisierung reproduziert (200.000 interne Bindestriche, Timeout nach zwei Sekunden) und linear korrigiert. Die fünf üblichen Target-Identitäten bleiben erhalten. Die aktuellen Gate-/Baseline-/Schwellen- und Target-Regressionen sind erfolgreich. Der jüngste Nachreview findet keine konkreten Fehler. Die abschließende CodeQL-/kanonische CI muss diesen letzten Nachtrag noch prüfen; aktueller verbindlicher Status und genaue Heads stehen in #628 und der bestehenden Architekturdatei.

## Fortsetzung am 10. Oktober 2026

#1189 und #1190 sind inzwischen gemergt; #1191 wurde ohne Änderung des
Implementierungsbaums mit main `56428217` abgeglichen. Der gezielte Task-11-Befehl
besteht auf `1c441a53` mit neun Tests (drei Provider-, zwei Backup- und vier
Recovery-Verträge), ohne Fehler oder Skips. JGit/PostgreSQL, OpenTelemetry,
Kubernetes mit Artemis-/Worker-Wiederherstellung, Dokument-Download, Datenbankmatrix
und die sechs vorhandenen Browser-Shards sind auf diesem Head erfolgreich.
Der vollständige kanonische Lauf und sein Aggregat waren beim folgenden Nachtrag
noch offen; der separate vollständige Plugin-Profil-PASS bleibt ausdrücklich offen.

Die Abschlussprüfung der CI-Eingaben fand eine konkrete Lücke: reine Änderungen
an `taxonomy-templates-api` aktivierten Kubernetes, Dokument-E2E und JGit nicht;
der Performance-Filter erfasste außerdem die neuen Plugin-APIs, den Loader und
externe Plugins nicht. Die bestehenden Filter enthalten diese Eingaben nun.
`PluginWorkflowTriggerTest` prüft alle betroffenen PR-/Push-Filter und führt den
wirklichen Performance-Scope-Befehl in isolierten Git-Repositories aus, einschließlich
unveränderter Negativkontrollen für Dokumentation und Frontend-Ressourcen.

RED: 28 Fälle, davon 19 erwartete Assertion-Fehlschläge, keine Ausführungsfehler
oder Skips. GREEN nach unabhängiger Reviewkorrektur der Git-/Shell-Umgebungsisolation:
259 Fälle ohne Fehler oder Skips (28 neue Verträge, sieben bestehende Workflow-
Authority-Tests und 224 verpflichtende Architekturguards). Tatsächlicher Maven-Aufruf
mit Java 21, explizitem Mockito-Startagenten und dem bereitgestellten Proxy/Zertifikatsspeicher:

```bash
./mvnw -s target/continuation-evidence/toolchain/maven-settings.xml -B \
  -Dmaven.build.cache.enabled=false -pl taxonomy-build -am test \
  -Dtest=PluginWorkflowTriggerTest,WorkflowTestAuthorityPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Kein neuer Workflow, keine zusätzliche Testauswahl in einem Workflow und keine
abgeschwächte Prüfung. Der unabhängige Nachreview findet keine weiteren Probleme.
Der neue Commit benötigt weiterhin die vollständige CI; frühere Ergebnisse werden
nicht als Ausführungen auf seinem Head bezeichnet. Die konkreten Heads, Läufe und
noch offenen Voraussetzungen stehen weiterhin in #628 und der bestehenden
Architekturdatei.

## Vollständiger Plugin-Profillauf in der vorhandenen CI

Die im Plan ausdrücklich verlangte vollständige Profilabnahme hat jetzt einen
Ausführungsort mit den nötigen Docker-/Helm-Voraussetzungen. Der zusätzliche Job
`plugin-profile` im vorhandenen `ci-cd.yml` führt unverändert
`./mvnw -B verify -Pplugin-packaging-tests` aus. Das bestehende Abschluss-Gate
verlangt seinen Erfolg; Fehler, Abbruch, Skip und fehlendes Ergebnis verhindern
den Gesamt-PASS. Keine neue Workflowdatei, kein zusätzlicher Testselektor und
keine geänderte Maven-Testauswahl. Das Profil bleibt zusätzlich im kanonischen
Build enthalten; überlappende Testanzahlen werden nicht addiert.

Der Maven-Vertragstest reproduziert zuvor fünf Assertion-Fehlschläge in 34 Fällen
(fehlender Job sowie vier unzulässig akzeptierte Ergebniszustände), ohne Fehler
oder Skips. Nach der Korrektur besteht derselbe oben dokumentierte Befehl mit
265 Fällen: 34 Workflowverträge, sieben Authority-Tests und 224 verpflichtende
Architekturguards; keine Fehler oder Skips, 21 Reaktoreinträge, 2:06 Minuten.
Der tatsächliche Shellbefehl des Abschluss-Gates wird für alle fünf Zustände
ausgeführt. Unabhängiger Review: keine Befunde. Der erste vollständige Lauf des
neuen Jobs und die endgültige CI-Abnahme sind weiterhin abzuwarten; die lokalen
Vertragstests sind kein vollständiger Plugin-Profil-PASS.

Die erste CI-Ausführung dieses Nachtrags fand einen zusätzlich zu pflegenden
bestehenden Vertrag: `ParallelCiEvidenceContractTest` erwartete wortwörtlich die
bisherigen sechs Abschluss-Jobs. Oracle und SQL Server scheiterten deshalb bereits
in `taxonomy-tooling` (200 Fälle, ein Fehlschlag), vor ihren Datenbanktests. Der
Fehler wurde lokal mit zwei Fällen und einem Fehlschlag reproduziert. Der Vertrag
verlangt jetzt alle bisherigen Voraussetzungen plus Plugin-Profil, dessen Jobnamen
und Ergebnisbindung. Alle **200 Tooling-Tests** bestehen danach ohne Fehler oder
Skips (`./mvnw -s target/continuation-evidence/toolchain/maven-settings.xml -B
-Dmaven.build.cache.enabled=false -pl taxonomy-tooling -am test`, 16,430 Sekunden).
Der unabhängige Nachreview bestätigt die unverändert strengen Voraussetzungen.
Der neue Commit muss weiterhin die vollständigen CI-Läufe bestehen.

## Vollprofilnachweis und asynchrone Exportbereitschaft

Der vollständige Befehl `./mvnw -B verify -Pplugin-packaging-tests` besteht auf
`dd3448d2a82a100418590a85baa780f9b1109375` in Job `114083112760` des Laufs
`38008568604`. Das per SHA-256 geprüfte CI-Artefakt enthält 1.089 JUnit-Berichte
mit 7.907 Tests, null Fehlern, Fehlschlägen oder Skips. Darin liegen exakt zwölf
Paketfälle (zehn `OptionalFeaturePackagedIT`, zwei `ExternalPluginPackagedIT`);
weitere fünf Tests bestehen im unabhängig gebauten Mermaid-Projekt. Diese
aktuelle Zählung ersetzt für die Abschlussabnahme die früheren Angaben von
14 Paket-ITs. Acht physische Installationen, HTTP/WebDAV, Voll→Kern→Voll mit
erhaltenen Daten und drei dynamische Aktivierungszyklen sind nachgewiesen.

Auch PostgreSQL, SQL Server, Oracle, ONNX, alle sechs UI-Shards, Szenario,
Dokument-E2E, Interoperabilität, OpenTelemetry, JGit, Kubernetes/Artemis,
Reformulierung, Security und der tatsächliche CodeQL-Push-Lauf sind erfolgreich.
Diese Ergebnisse werden nicht zu einer Summe überlappender Tests addiert.

Die kanonische Kernverifikation `114083112516` scheitert an genau einem
JUnit-Fehler: `CompleteCopilotSessionIT` wählt DOCX aus dem bereits sichtbaren
Dialog, bevor dessen asynchron geladene Rendereroptionen vorhanden sind
(`Cannot locate option with value: docx`). Die Ursache ist im tatsächlichen
JUnit-Bericht und Maven-Log des Artefakts `11654884856` zugänglich; der direkte
Joblogabruf scheitert weiter mit `Transport closed`.

Der Test wartet jetzt vor der Formatauswahl auf den vorhandenen freigegebenen
Download-Button. Dieser wird erst nach Capabilities und gespeichertem Scope
aktiviert. Alle drei Formate, Export-/Inhaltsassertions und das bestehende
Zeitlimit bleiben unverändert. Der unabhängige Review bestätigt den Fix ohne
weitere Befunde. Der korrigierte Selenium-Test kompiliert mit Java 21; alle
sieben Fälle von `CompleteCopilotResultUiContractTest` bestehen ohne Fehler oder
Skips (lokal, 1:02 Minuten, Maven Exit 0). Dieser fokussierte Testlauf ist kein
echter Selenium-Nachweis. Der erneute echte Browserlauf und die vollständige
kanonische Abnahme bleiben erforderlich; der erfolgreiche Plugin-Profillauf
ersetzt sie nicht.

## Abnahmekriterien des Gesamtauftrags

Erledigt erst, wenn alle zwölf Tasks samt Nachweisen abgeschlossen sind: bestehende Funktionalität der Vollausstattung; keine Repository-Typen in Template-API; vollständige Reportmodelle; neuer deterministischer Provider ohne Enumänderung; unabhängig gebautes Mermaid-JAR im unveränderten Host; echte Abwesenheit optionaler Fach-JARs; sicheres dynamisches Laden/Drain nur im zugesagten Umfang; UI-/Security-/Backup-/Restart-/Cluster-Negativfälle; aktueller vollständiger Build und präziser PR-Nachweis.

Ein grüner Unit-Test, ein vorhandener ServiceLoader oder ein erfolgreicher PF4J-Start allein erfüllt den Auftrag nicht. Bei einem echten Zugriffsgate keine Bestätigung im Namen des Nutzers erzeugen. Bis zur Planfreigabe bleibt dieser Text ein Plan, keine Umsetzungs- oder Prüferfolgsmeldung.

## Geprüfte technische Quellen

- [PF4J 3.16.0, veröffentlicht am 20. September 2026](https://github.com/pf4j/pf4j/releases/tag/release-3.16.0): gepinnter Kandidat; noch kein Taxonomy-Kompatibilitätsnachweis.
- [PF4J-Lebenszyklus](https://pf4j.org/doc/plugin-lifecycle.html) und [Classloading](https://pf4j.org/doc/class-loading.html): Loadermechanik; die fachliche Quieszenz und Registry-Atomizität bleiben Taxonomy-Aufgaben.
- [Repository-Grenzregeln](../../dev/extension-module-boundaries.md), [Maven-Verifikation](../../dev/MAVEN_VERIFICATION.md) und [ADR 0005](../../adr/0005-private-architecture-editor.md).
