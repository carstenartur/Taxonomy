# Fachliche Prüfung des zivilen Testreferenzfalls

Prüfgegenstand ist `civilian-flood-information-v1`, kein betriebenes Warnsystem.
Die Anforderung wurde aus der [öffentlichen Dienstbeschreibung](https://www.gov.uk/get-flood-warnings)
und dem [API-Vertrag](https://environment.data.gov.uk/flood-monitoring/doc/reference)
abgeleitet. Der Katalog und die Antwortregeln bleiben unverändert. Diese Prüfung
bewertet die Zuordnungen und benennt die Grenzen, die ein Export mitführen muss.

| Anforderung | Begründete Zuordnung im erzeugten Modell | Grenze / Umsetzungspflicht |
|---|---|---|
| F1: veröffentlichte Warnungen, Gebiete, Beobachtungen mit Quelle und Zeit | BP-1017 erfasst Daten; CI-1052 verarbeitet hydrografische Informationen; IP-1116 beschreibt das Umweltlagebild; UA-1580 die fachliche Anwendung. | Datenverträge, Gebietsschlüssel, Quellenanzeige und Zeitstempel sind noch keine implementierten Schnittstellen. |
| F2: zivile Aufsicht | BR-1223 ist ein ausdrücklich ziviler Governance-Begriff. | Rollenmodell, Entscheidungsbefugnisse und Protokollierung müssen in einer konkreten Anwendung ausgestaltet werden. |
| F3: Gebietsabonnement; SMS, Telefon, E-Mail | CP-1041 beschreibt Informationsverbreitung, CO-1048 den SMS-Zugang. | Telefon, E-Mail und die Zuordnung von Abonnements zu Gebieten besitzen noch keine eigene Modellkomponente. SMS darf nicht als Nachweis aller drei Kanäle gezählt werden. |
| F4: Kontaktdaten ändern, Gebiete entfernen, Konto löschen; Oberflächenwasser ausgeschlossen | Die Anforderung bleibt vollständig im Snapshot und Bericht erhalten. | Kein ausgewählter Endpunkt belegt Kontoverwaltung oder Löschabläufe. Die fachliche Lücke ist ausdrücklich offen; Oberflächenwasser darf nicht als abgedeckt dargestellt werden. |
| F5: effizienter Abruf, Aktualität, Ausfälle, offizielle Kanäle | BP-1017, CI-1052 und UA-1580 passen funktional zu Abruf, Verarbeitung und Anzeige. | Weder eine Taxonomiebewertung noch eine Beziehung beweist Abrufintervalle, Ausfallerkennung oder eine Verfügbarkeitsgarantie. |
| A1: Warteschlange, idempotente Wiederholung, Schutz der Kontaktdaten, Audit | CR-1097 ist die passende technische Kategorie für eine Nachrichtenwarteschlange. | Dauerhaftigkeit, Idempotenz, Zugriffsregeln und Audit sind zusätzliche Testannahmen und benötigen eigene technische Verträge und Fehlerfalltests. |

## Entscheidung

Als **Referenz für Integrationstests angenommen**: Die acht Endpunkte sind fachlich
nachvollziehbar, identifizierbar und reichen aus, um verschiedene Schichten,
Beziehungsarten, Quellen, Scores und begründete Alternativen zu prüfen. Die 22
isolierten Kategorien sind Vorfahren der gewählten Endpunkte. Sie dienen der
Katalogeinordnung und dürfen nicht als 22 unverbundene Anwendungskomponenten
interpretiert werden. Die 44 Beziehungen bleiben generierte Kandidaten; ihre
Existenz ist kein Beleg für einen implementierten Datenaustausch. Der echte
ArchiMate-Import prüft die nativen Typregeln: 8 Kandidaten (je 4 `REALIZES` und
`SUPPORTS`) werden abgelehnt, 36 übernommen. Das unveränderte Sparx-Profil v1
meldet für weitere 28 Beziehungen fehlende Abbildungen. Nach ausdrücklicher
Ablehnung liefert XMI alle 38 Elemente und 8 unterstützte Beziehungen; Namen,
Richtung und kanonischer Beziehungstyp werden gegen die angenommene Quelle geprüft.
Die zwei maschinenlesbaren Reviews und `integration-quality.json` dokumentieren
diese Verluste. Dieser Export darf nicht als vollständige Übertragung aller 44
Kandidaten dargestellt werden.

Die Entscheidung schließt die Prüfung des **Testreferenzfalls** ab. Sie gibt keine
Produktionsarchitektur frei und bestätigt keine vollständige Anforderungsabdeckung.
Für eine solche Freigabe sind insbesondere F3/F4 sowie die zeitlichen und
betrieblichen Verträge von F5/A1 separat zu modellieren und zu prüfen. Die
maschinelle Abnahme kontrolliert diese Grenzen, anstatt ihnen einen pauschalen
Erfüllungswert von 100 Prozent zu geben.

Ein bewusst kleiner Referenzfall hält Regressionen verständlich. Zusätzliche
Szenarien können später mehrere Alternativen, Kontoverwaltung und Zustellfehler
ergänzen, ohne diesen reproduzierbaren Ausgangspunkt umzudeuten.
