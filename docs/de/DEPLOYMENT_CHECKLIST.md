# Deployment-Checkliste

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
