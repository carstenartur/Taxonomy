# Versionshinweise 1.4.0 — unveröffentlicht

Version 1.4.0 wurde nicht veröffentlicht. Diese Hinweise beschreiben den damaligen Kandidaten; die [aktuellen Versionshinweise](https://github.com/carstenartur/Taxonomy/blob/v1.4.1/docs/de/RELEASE_NOTES_1_4_1.md) gelten für 1.4.1.

Die [vollständige Release-Beschreibung](https://github.com/carstenartur/Taxonomy/blob/v1.4.0/release_notes.md) ist die verbindliche
Umfangsbeschreibung. Diese Hinweise fassen die sichtbaren Änderungen zusammen;
die Veröffentlichung setzt die Freigabeprüfungen des endgültigen Kandidaten voraus.

## Anforderungen und Architektur

- Projekt- und Anforderungsportfolio mit unveränderlichen Versionen, wiederaufnehmbaren
  Analysen, Evidenzprüfung, Vergleich, Historie und Berichten.
- Neuformulierungsangebote mit Antworten und Vertagungen, geschützten manuellen
  Entwürfen, historischen Exporten und ausdrücklicher Übernahme als neue Version.
- Versionierte Planungsprofile für ein geplantes Inbetriebnahmejahr/-datum und
  Vorgabenbezüge, integriert in Editor, Historie und begrenzten ReqIF-Austausch.
- Versionierte Word-Vorlagen, Architekturansichten aus Snapshots und deterministische
  Exporte. Klassifizierungen im Information-Product-Overlay bleiben vorläufig.
- Getrennte Workspace-Repositories und tabbezogene Auswahl; die zentrale gemeinsame
  Ansicht bleibt bis zur Auswahl eines bearbeitbaren Workspace schreibgeschützt.

## Analyse, lokale Suche und Betrieb

Root-Relevanz behält jetzt ihren unabhängigen Wert von 0 bis 100. Unterkategorien
verteilen weiterhin das Elternbudget; konkrete Produkte behalten ihre unabhängige
Eignungsbewertung. Pausierte Analysen unterscheiden ungeprüfte Knoten von bestätigter
Relevanz null.

Die lokale ONNX-Suche verwendet normalisierte CLS-Vektoren und ein getrenntes
ANN-Suchbudget. Ältere Vektoren müssen über die kontrollierte Initialisierung neu
aufgebaut werden. ONNX kann keine Beziehungsbewertungen generieren: Die Analyse
weist diesen Teil ausdrücklich als ungeprüft/teilweise aus und vermeidet nicht
unterstützte Generierungsaufrufe. Deutsche Referenztreffer, die das englische
Embedding-Modell verfehlt, bleiben sichtbar; ein bestandener englischer Test ist
kein Nachweis gleichwertiger deutscher Suchqualität.

Optionale lokale Benutzerverwaltung, geführte Rancher-/Helm-Konfiguration und die
native Setup-Grundlage verwenden die vorhandene Authentifizierung und Konfiguration.
Die Backup-Anleitung ermittelt jetzt das tatsächliche Compose-Volume. PostgreSQL
ist der externe Produktionspfad mit verwalteten Migrationen; MSSQL/Oracle bleiben
Kompatibilitätsprofile mit offener vollständiger Qualifizierung. Vorhandene
dateibasierte HSQLDB-Installationen bereiten Repository- und Portfolio-Zuordnungen
jetzt vor dem Hibernate-Start vor. Das Upgrade erhält gespeicherte Zuordnungen und
bricht bei uneindeutiger Herkunft ab, statt befüllte Tabellen unmigriert zu lassen.
Vor Produktivbetrieb
die [Upgrade-Hinweise](https://github.com/carstenartur/Taxonomy/blob/v1.4.0/release_notes.md#upgrade-notes) beachten und Wiederherstellung,
Suche und Git-Historie prüfen.

Die GUI-Sprache garantiert derzeit nicht die Sprache generierter KI-Begründungen.
Lokalisierte Berichtstitel übersetzen gespeicherte Gründe nicht. Die vorgeschlagenen
getrennten Sprachvorgaben sind [separat beschrieben](../dev/ANALYSIS_LANGUAGE_POLICY.md)
und in dieser Release-Korrektur noch nicht implementiert.

## Experimentelle visuelle Visio-Übergabe (#965)

Die Architecture Workbench lädt den ausgewählten unveränderlichen Snapshot als Paket mit `diagram.vsdx`, versioniertem Mapping-Profil und einem durch Prüfsummen gebundenen Verlustmanifest herunter. Der Export führt keine neue Analyse aus. Einzelne VSDX-Downloads enthalten dieselben Profil- und Übergabedaten zusätzlich eingebettet.

Stabile Taxonomy-Element-/Beziehungsidentitäten, Originaltypen, normalisierte Bewertungen, Auswahlmerkmale und freigegebene gespeicherte Reviews bleiben als typisierte Shape-Daten erhalten. Dokumenteigenschaften und Manifest tragen Snapshot-, Anforderungs-/Versions- sowie Repository-/Workspace-/Branch-/Commit-Zuordnung, soweit der Quell-Snapshot diese enthält. Fehlende Metadaten und bewusste Layout-/Semantikverluste werden ausdrücklich ausgewiesen.

Jedes erzeugte Paket durchläuft OPC-/Referenzprüfungen und eine Validierung mit fest versionierten Visio-Schemabindungen. Das Profil verwendet masterlose Konnektoren, explizite Stile und eine oder zwei Snapshot-Seiten. Wiederholte Exporte sind deterministisch. Automatisierte Tests umfassen unabhängiges Laden/Rendern mit Apache POI und den Vergleich des kanonischen Graphen.

**Die Microsoft-Visio-Desktop-Abnahme für Öffnen, Bearbeiten, Speichern und erneutes Öffnen steht aus.** Das Format bleibt eine experimentelle begrenzte visuelle Übergabe; produktionsreife Bearbeitung oder allgemeine Visio-Kompatibilität werden nicht zugesichert. Externe Änderungen aktualisieren Taxonomy nicht. Für semantischen Architekturaustausch dienen ArchiMate Exchange und freigegebene JSON-Nachweise vorbehaltlich der gesonderten Abnahme in #967.

Das [Benutzerhandbuch](USER_GUIDE.md) beschreibt den Download; [Profil, Grenzen und Desktop-Abnahmeverfahren](../dev/VISIO_HANDOFF_PROFILE.md) dokumentieren Umfang und offene Nachweise. Die ergänzende POI-Vorschau zeigt Einschränkungen bei Schriftzeichen und Pfeilspitzen.
