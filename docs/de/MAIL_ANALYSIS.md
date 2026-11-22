# Taxonomy-Aufträge per E-Mail

Senden Sie die Anforderung als Nachrichtentext, als Anhang oder in beiden Formen an das eingerichtete Taxonomy-Postfach. Der Betreff steuert den Auftrag. Formulierungen auf Deutsch und Englisch werden ausgewertet.

| Beispielbetreff | Ergebnis |
|---|---|
| `Vollständige Architektur als PDF und ArchiMate` | Bewertung und Relationssuche über alle verfügbaren Teiltaxonomien. Die Mail enthält beide Architekturdateien. |
| `Entscheidungsbaum für Business Processes und Core Services als Word` | Entscheidungsauswertung für die Teiltaxonomien BP und CR. Die Mail enthält das Word-Dokument. |
| `Entscheidungsbaum für alle Teiltaxonomien als HTML und Visio` | Entscheidungsauswertung für alle geladenen Teiltaxonomien. |
| `Build the complete architecture and return HTML and Markdown reports` | Architekturbericht in den beiden angeforderten Formaten. |

Taxonomy wertet den Betreff mit den deutschen und englischen Namen des geladenen Katalogs aus. Der Auftrag kann mehrere Formate kombinieren. Welche davon zur Verfügung stehen, hängt von den installierten Exportern ab.

| Auftrag | Anforderbare Formate | Standard |
|---|---|---|
| Vollständige Architektur | Diagramm als PDF, SVG, Visio, ArchiMate, Structurizr, Mermaid oder Sparx-EA-Austauschpaket. Architekturbericht als Word, HTML, Markdown oder JSON. | PDF |
| Entscheidungsbaum | Entscheidungsbericht als HTML, Word, Visio, JSON oder PDF. | HTML mit der Standardinstallation |

Mermaid benötigt das Mermaid-Plugin. PDF für Entscheidungsbäume setzt `taxonomy.reporting.visio-pdf.enabled=true` und den eingerichteten Visio-PDF-Konverter voraus. Taxonomy weist ein aus dem Betreff erzeugtes Kommando zurück, wenn es eine unbekannte Teiltaxonomie oder ein für diesen Auftrag nicht verfügbares Format enthält.

Unterstützte Anhänge sind PDF, DOCX, HTML, Markdown und Text. Texthaltige PDFs können direkt gelesen werden. Gescannte PDFs brauchen eine durchsuchbare Textschicht. Die Grenzen gelten für Text und Anhänge zusammen. Standardmäßig gelten 10 MiB Nachrichtengröße, acht Anhänge und 5.000 Zeichen extrahierter Anforderungstext. Die Laufzeitgrenze der normalen Taxonomy-Analyse gilt auch für E-Mail-Aufträge.

Unterstützt der Posteingangsserver IMAP IDLE, meldet er neue Nachrichten über eine offene TLS-Verbindung. Nach einem Verbindungsabbruch liest Taxonomy auch zwischenzeitlich eingetroffene Nachrichten. Ohne IDLE prüft Taxonomy das Postfach im konfigurierten Intervall (`imap.poll-interval`, standardmäßig 30 Sekunden).

Sobald Taxonomy eine Nachricht eingelesen und einen Platz reserviert hat, bestätigt es den Eingang per E-Mail mit Warteschlangenposition und Live-Link. Eine volle Warteschlange führt zur sofortigen Ablehnung beim Einlesen. Der Live-Link zeigt den Bearbeitungsstand und die aktuelle Position des eigenen Auftrags. Für das gesamte Postfach zeigt er die belegten und konfigurierten Warteplätze, die Zahl der laufenden Analysen und die Ergebnisse im Versand. Ein Auftrag gibt seinen Warteplatz bei Beginn der Analyse frei. Ist der eigene Auftrag abgeschlossen, bleiben die Zahlen der Warteschlange bis zum Ablauf des Links sichtbar und werden weiter aktualisiert. Wer den Link hat, kann diese Angaben sehen. Nachricht, Anforderung und Exporte erscheinen dort nicht. Die Ergebnisse kommen als Anhänge an die zugelassene Absenderadresse. Teilstände sind in der Ergebnisnachricht gekennzeichnet.

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
