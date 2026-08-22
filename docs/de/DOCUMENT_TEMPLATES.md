# Versionierte Word-Vorlagen

Taxonomy verwaltet Word-Vorlagen als entpackte OOXML-Paketbäume im logischen
JGit-/Hibernate-Repository `taxonomy-document-templates`. WebDAV und Downloads
stellen daraus vollständige `.dotx`-Dateien zusammen.

## Standardvorlage für den Bewertungsbericht

Die Anwendung liefert die erforderliche Vorlage

```text
decision-rationale-report
```

im Container beziehungsweise JAR mit. Beim ersten erfolgreichen Start wird sie
idempotent als erster Git-Commit importiert. Existiert die Vorlage bereits, wird sie
weder beim Neustart noch bei einem Softwareupdate überschrieben. Eigene Logos,
Organisationsangaben und Layoutänderungen bleiben daher erhalten.

Der DOCX-Bewertungsbericht wird tatsächlich aus der jeweils aktuellen Git-Version
dieser Vorlage erzeugt. Taxonomy verwendet insbesondere:

```text
{{taxonomy.report.title}}
{{taxonomy.report.subtitle}}
{{taxonomy.report.status}}
{{taxonomy.report.requirement}}
{{taxonomy.report.generatedAt}}
{{taxonomy.report.generatedBy}}
{{taxonomy.report.taxonomyVersion}}
{{taxonomy.report.applicationVersion}}
{{taxonomy.report.workspace}}
{{taxonomy.report.branch}}
{{taxonomy.template.id}}
{{taxonomy.template.commit}}
{{taxonomy.report.body}}
```

`{{taxonomy.report.body}}` muss genau einmal als eigener Absatz vorkommen und der
letzte nicht leere Inhaltsblock des Dokuments sein. Taxonomy entfernt ihn beim Export
und fügt dort Kurzfassung, Entscheidungskapitel, Diagramme und Anhang ein. Titelseite,
Bilder, Kopf- und Fußzeilen, Seitenformat und Word-Formatvorlagen bleiben Bestandteil
der bearbeitbaren DOTX-Vorlage.

Uploads und Wiederherstellungen der Standardvorlage werden zusätzlich zum
allgemeinen OOXML-Sicherheitscheck gegen diesen Vertrag validiert. Der
Actuator-Health-Check `decisionRationaleTemplate` meldet `DOWN`, wenn die Pflichtvorlage
fehlt oder strukturell ungültig ist.

## Versions- und Konkurrenzmodell

Jede Vorlage verwendet den letzten Git-Commit, der ihren eigenen OOXML-Unterbaum
geändert hat, als Version und HTTP-ETag. Eine Änderung an einer anderen Vorlage macht
deshalb keinen geöffneten Editor ungültig und erzeugt keinen falschen Konflikt. Die
Erstanlage ist ausschließlich create-only; Ersetzen und Wiederherstellen benötigen das
aktuelle Vorlagen-ETag. `If-Match` verwendet starke HTTP-Entity-Tags, akzeptiert mehrere
Alternativen in einer kommaseparierten Liste und legt niemals eine fehlende Ressource
an.

WebDAV-Sperren verbessern die Bearbeitung in Word. Sie werden bewusst pro Prozess
gehalten; das unterstützte Helm-Profil bleibt deshalb bei genau einer Replik. Die
atomare Git-Prüfung der erwarteten Vorlagenversion bleibt auch nach einem
Prozessneustart oder dem Ablauf einer Sperre der dauerhafte Schutz vor verlorenen
Änderungen. Für mehrere Repliken wäre zunächst ein gemeinsamer Lock-Speicher nötig,
damit Word-Sperren ohne Unterbrechung zwischen Instanzen funktionieren.

## Zugriff und Transport

Angemeldete Benutzer dürfen vollständige Vorlagen lesen und daraus ein neues Dokument
erzeugen. Nur Administratoren dürfen Vorlagen hochladen, wiederherstellen, sperren oder
speichern. Direkte Desktop-Word-Links benötigen HTTPS; ausgenommen sind lokale
Loopback-Adressen für die Entwicklung. Der normale HTTPS-Download und eine kopierbare
WebDAV-Adresse bleiben als Rückfallwege verfügbar.

Im Keycloak-Profil sind direkte `ms-word:`-Links standardmäßig deaktiviert. Desktop-
Word kann die OIDC-Sitzung des Browsers nicht übernehmen und erhält den erforderlichen
Bearer-Token nicht. Die Links dürfen erst wieder aktiviert werden, wenn der Betrieb
einen WebDAV-kompatiblen, begrenzten Zugang wie ein App-Passwort implementiert und
getestet hat. WebDAV-Clients mit Bearer-Token-Unterstützung können den Endpunkt weiter
verwenden.

Taxonomy weist Makros, ActiveX, eingebettete OLE-Objekte, Signaturen, unsichere
ZIP-Pfade, nur in Groß-/Kleinschreibung kollidierende Paketbestandteile, fehlerhaftes
XML, externe Beziehungen außer Hyperlinks, fehlende interne Beziehungsziele, Pakete
ohne genau eine Root-`officeDocument`-Beziehung auf `word/document.xml` sowie
OOXML-Bestandteile namens `template.json` zurück. Dieser Name ist dem internen
Taxonomy-Manifest vorbehalten.
