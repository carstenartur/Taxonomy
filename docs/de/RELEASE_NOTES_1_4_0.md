# Versionshinweise 1.4.0 — unveröffentlicht

## Experimentelle visuelle Visio-Übergabe (#965)

Die Architecture Workbench lädt den ausgewählten unveränderlichen Snapshot als Paket mit `diagram.vsdx`, versioniertem Mapping-Profil und einem durch Prüfsummen gebundenen Verlustmanifest herunter. Der Export führt keine neue Analyse aus. Einzelne VSDX-Downloads enthalten dieselben Profil- und Übergabedaten zusätzlich eingebettet.

Stabile Taxonomy-Element-/Beziehungsidentitäten, Originaltypen, normalisierte Bewertungen, Auswahlmerkmale und freigegebene gespeicherte Reviews bleiben als typisierte Shape-Daten erhalten. Dokumenteigenschaften und Manifest tragen Snapshot-, Anforderungs-/Versions- sowie Repository-/Workspace-/Branch-/Commit-Zuordnung, soweit der Quell-Snapshot diese enthält. Fehlende Metadaten und bewusste Layout-/Semantikverluste werden ausdrücklich ausgewiesen.

Jedes erzeugte Paket durchläuft OPC-/Referenzprüfungen und eine Validierung mit fest versionierten Visio-Schemabindungen. Das Profil verwendet masterlose Konnektoren, explizite Stile und eine oder zwei Snapshot-Seiten. Wiederholte Exporte sind deterministisch. Automatisierte Tests umfassen unabhängiges Laden/Rendern mit Apache POI und den Vergleich des kanonischen Graphen.

**Die Microsoft-Visio-Desktop-Abnahme für Öffnen, Bearbeiten, Speichern und erneutes Öffnen steht aus.** Das Format bleibt eine experimentelle begrenzte visuelle Übergabe; produktionsreife Bearbeitung oder allgemeine Visio-Kompatibilität werden nicht zugesichert. Externe Änderungen aktualisieren Taxonomy nicht. Für semantischen Architekturaustausch dienen ArchiMate Exchange und freigegebene JSON-Nachweise vorbehaltlich der gesonderten Abnahme in #967.

Das [Benutzerhandbuch](USER_GUIDE.md) beschreibt den Download; [Profil, Grenzen und Desktop-Abnahmeverfahren](../dev/VISIO_HANDOFF_PROFILE.md) dokumentieren Umfang und offene Nachweise. Die ergänzende POI-Vorschau zeigt Einschränkungen bei Schriftzeichen und Pfeilspitzen.
