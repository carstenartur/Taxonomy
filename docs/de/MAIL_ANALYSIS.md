# Taxonomy-Aufträge per E-Mail

Senden Sie die Anforderung als Nachrichtentext, als Anhang oder in beiden Formen an das eingerichtete Taxonomy-Postfach. Der Betreff steuert den Auftrag. Formulierungen auf Deutsch und Englisch werden ausgewertet.

| Beispielbetreff | Ergebnis |
|---|---|
| `Vollständige Architektur als PDF und ArchiMate` | Bewertung und Relationssuche über alle verfügbaren Teiltaxonomien. Die Mail enthält beide Architekturdateien. |
| `Entscheidungsbaum für Business Processes und Core Services als Word` | Entscheidungsauswertung für die Teiltaxonomien BP und CI. Die Mail enthält das Word-Dokument. |
| `Entscheidungsbaum für alle Teiltaxonomien als HTML und Visio` | Entscheidungsauswertung für alle geladenen Teiltaxonomien. |

Für Architekturen stehen PDF, SVG, Word, Visio, ArchiMate und Structurizr zur Verfügung. Ohne Formatangabe ist PDF vorgesehen. Für Entscheidungsbäume stehen HTML, Word und Visio zur Verfügung. Ohne Formatangabe ist HTML vorgesehen. PDF für Entscheidungsbäume setzt `taxonomy.reporting.visio-pdf.enabled=true` und LibreOffice voraus. Taxonomy prüft das aus dem Betreff erzeugte strukturierte Kommando anhand des geladenen Katalogs und der für den Auftrag verfügbaren Formate.

Unterstützte Anhänge sind PDF, DOCX, HTML, Markdown und Text. Texthaltige PDFs können direkt gelesen werden. Gescannte PDFs brauchen eine durchsuchbare Textschicht. Die Grenzen gelten für Text und Anhänge zusammen. Standardmäßig gelten 10 MiB Nachrichtengröße, acht Anhänge und 5.000 Zeichen extrahierter Anforderungstext. Die Laufzeitgrenze der normalen Taxonomy-Analyse gilt auch für E-Mail-Aufträge.

Sobald Taxonomy eine Nachricht eingelesen und einen Platz reserviert hat, bestätigt es den Eingang per E-Mail mit Warteschlangenposition und Live-Link. Eine volle Warteschlange führt zur sofortigen Ablehnung beim Einlesen. Der Live-Link zeigt den Bearbeitungsstand, die Position und die Gesamtzahl wartender Aufträge. Wer den Link hat, kann diese Angaben sehen. Nachricht, Anforderung und Exporte erscheinen dort nicht. Die Ergebnisse kommen als Anhänge an die zugelassene Absenderadresse. Teilstände sind in der Ergebnisnachricht gekennzeichnet.

## Einrichtung

Die Analyse-, Architektur- und Berichtsfunktionen sowie ein KI-Anbieter für strukturierte Textantworten müssen aktiviert sein. Richten Sie ein eigenes Postfach ein, dessen Mailserver die angegebene Absenderidentität prüft. Taxonomy nimmt Aufträge nur von den ausdrücklich zugelassenen Adressen entgegen. Verwenden Sie die TLS-Ports Ihres Mailservers.

Beispiel für eine Spring-YAML-Konfiguration. Die Platzhalter für die Kennwörter werden aus der Laufzeitumgebung geladen.

```yaml
taxonomy:
  mail:
    enabled: true
    public-base-url: https://architektur.example.org/taxonomy
    allowed-senders:
      - analyseauftrag@example.org
    imap:
      host: imap.example.org
      port: 993
      username: taxonomy@example.org
      password: ${TAXONOMY_MAIL_IMAP_PASSWORD}
      folder: INBOX
      poll-interval: PT30S
    smtp:
      host: smtp.example.org
      port: 587
      username: taxonomy@example.org
      password: ${TAXONOMY_MAIL_SMTP_PASSWORD}
      from: taxonomy@example.org
    queue:
      directory: /var/lib/taxonomy/mail-analysis
      capacity: 16
      retention: PT48H
      language: de
      max-result-bytes: 15000000
```

Betreiben Sie für das Postfach einen E-Mail-Koordinator mit einem persistenten, für diese Instanz zugänglichen Verzeichnis. Das Verzeichnis hält wartende Aufträge und ausstehende Ergebnisnachrichten über Neustarts hinweg. In einer Installation mit mehreren Instanzen muss der öffentliche Statuspfad den Koordinator erreichen. Der Koordinator verwendet den vorhandenen Analysepfad der Anwendung. `smtp.implicit-tls: true` aktiviert implizites TLS, typischerweise mit Port 465. Für die übrigen Eingabegrenzen gibt es `max-message-bytes`, `max-attachments` und `max-requirement-characters` unter `taxonomy.mail`.
