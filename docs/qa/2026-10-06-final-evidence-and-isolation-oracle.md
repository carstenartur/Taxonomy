# QA-Fortsetzung: Browsernachweise, Login-Lücke und deterministischer Isolationstest

## Geprüfter Ausgangspunkt

Diese Prüfung setzt PR #1177 auf Commit
`20d39bff79db26f17cdc401cb897981e63fb6921` fort. Die Anwendung stammt aus
CI-Lauf **37527848671**, Artefakt **11443441966**. Das Manifest nennt den
Test-Merge `e298d49f4c887c96b7542e6f5c6efc85bb8ac299` und den Quellbaum
`5da19f82cb002b02987ea730441d0267fb822767`.

ZIP SHA-256: `7693cf604b5511292e2da9ba1cac26a4672a29a87125f71ba9944c76bf01d926`.
JAR SHA-256: `820a55cf4166a64c2727594950b55497725a9b55ad5ed5dc26754f61833de174`.
Beide wurden lokal bestätigt. Die nachfolgenden Anwendungsmessungen und
Browsernachweise gehören zu diesem Ausgangspunkt, nicht zu einem späteren Commit.

## 1. PostgreSQL-Prüfjob: Zufallstreffer im Test statt Datenleck

[Job 112489724120](https://github.com/carstenartur/Taxonomy/actions/runs/37527848863/job/112489724120)
scheiterte im Anwendungstest `ReformulationBasePathContractTest`, der
`ReformulationIsolationTest.everyPersistedBaselineIdentityCoordinateMustMatchPhysicalProposal`
erbt. Der betreffende Testabschnitt meldet **2.574 Tests, einen Fehler,
keine Ausführungsfehler und keine Skips**. Das ist keine Gesamtzahl aller
Module und kein Beleg für einen PostgreSQL-SQL-Fehler.

Der Test prüfte, dass die verfälschte numerische Identität `1014` nirgendwo
im serialisierten Fehlertext vorkommt. Die tatsächliche HTTP-409-Antwort war:

```json
{"detail":"Stored reformulation baseline does not match proposal identity","instance":"/api/projects/9/requirements/12/reformulations/fb6f1014-b4bb-4229-8524-d816163c2392","status":409,"title":"Portfolio state conflict","type":"urn:taxonomy:portfolio:conflict"}
```

`1014` steht hier nur zufällig in der UUID der tatsächlich angefragten
Ressource. Die Antwort enthält keinen fremden Baseline-Datensatz.

### Korrektur ausschließlich im Testcode

Der Test vergleicht nun die **gesamte strukturierte Fehlerantwort** mit den
fünf erwarteten öffentlichen Feldern. `instance` muss exakt der URI des
kontrollierten Requests entsprechen. Zusätzliche Felder oder veränderte
Werte werden abgelehnt; die JSON-Feldreihenfolge ist unerheblich. Nachfolgende
JSON-Dokumente und doppelte Objektschlüssel werden ausdrücklich zurückgewiesen.

Die sieben Manipulationen gespeicherter Identitätskoordinaten, die echten
HTTP-409-Prüfungen, die Datenbankupdates und das Wiederherstellen im `finally`
bleiben unverändert. Es gibt keine Änderung an Produktivcode, Berechtigungen,
Sicherheitsschwellen, Datenbankschemata oder Workflow-Konfiguration.

Ein kleiner testinterner Helfer wird zusätzlich durch eine eigene
JUnit-Testklasse ohne Spring-Kontext geprüft. Die Regression enthält die
exakte UUID des fehlgeschlagenen CI-Laufs sowie Gegenproben mit tatsächlichen
zusätzlichen Daten, veränderten Fehlerfeldern und falschem Kontextpfad.

### Tatsächlich ausgeführte lokale Prüfung

Die ursprüngliche Testdatei wurde vor Änderung über ihren Git-Blob-Hash
`c43c36801f19ce2a6919edba5857aecc3736b3c5` abgeglichen. Der alte
Substring-Prüfausdruck scheitert mit der oben dokumentierten Antwort auch
in einer isolierten lokalen Reproduktion.

Der neue, unveränderte Prüfhelfer wurde mit **Java 21.0.11** gegen die
Jackson-Bibliotheken des verifizierten Anwendungspakets kompiliert und
über eine separate Java-Prüfroutine ausgeführt: **18 Prüfungen bestanden**.
Drei wirksame Negativmutationen (Gleichheitsprüfung entfernen, nachfolgendes
JSON erlauben, doppelte Schlüssel erlauben) erzeugten jeweils den erwarteten
Fehler. Danach bestanden die 18 Prüfungen am unveränderten Helfer erneut.
Die Syntax aller drei Java-Testdateien wurde mit dem Java-21-Compiler geparst.

Dies ist **kein ausgeführter JUnit-, Spring- oder Datenbanklauf**. Die neue
JUnit-Klasse und der geänderte vollständige Isolationstest müssen im regulären
Maven-Reaktor laufen. Ein vollständiger lokaler Checkout war durch die
fehlende GitHub-Namensauflösung blockiert; die vollständigen Testabhängigkeiten
standen lokal nicht bereit. Die abschließende Helferkompilierung verwendet
nur die benötigten Jackson-JARs und meldet keine Compilerwarnungen.

Reproduktion im vollständigen Repository:

```sh
./mvnw -B -pl taxonomy-app -am test \
  -Dtest=ReformulationProblemAssertionsTest,ReformulationIsolationTest,ReformulationBasePathContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 2. Browserabnahme des Ausgangscommits unabhängig nachgeprüft

Alle sechs UI-Shards und der UI-Vertragsjob sind auf `20d39bf` erfolgreich.
Die sechs heruntergeladenen Shard-Archive wurden gegen ihre GitHub-SHA-256-Werte
geprüft und unabhängig ausgewertet. Sie enthalten **18 verschiedene Szenarien
exakt einmal**, gebunden an denselben Test-Merge und dieselbe Anwendungssumme.

Die 18 Berichte enthalten **376 benannte Prüfungen** sowie **121 Axe-Zustände**,
ohne gemeldete Verstöße, blockierende Befunde oder Auditfehler. Die Axe-Prüfungen
sind teilweise bereits in den benannten Prüfungen enthalten; die Zahlen dürfen
nicht addiert werden. Absichtlich erzeugte HTTP-Fehler der Negativtests werden
nicht nachträglich als unerwartete Anwendungsfehler klassifiziert.

Diese Prüfung validiert gespeicherte CI-Nachweise. Sie ist weder ein neuer
lokaler Browserlauf noch eine vollständige manuelle Screenreader-Abnahme.
Sicherheits-, Szenario-, Dokumentvorlagen-, Kubernetes- und Reformulierungsworkflow
waren auf diesem Commit ebenfalls erfolgreich. Oracle und SQL Server bestanden;
der PostgreSQL-Lauf scheiterte am oben beschriebenen zufälligen Testtreffer.
Zum Stand dieser Dokumentation war der übergreifende Java-CI-Lauf noch nicht
abschließend ausgewertet. Ein neuer Commit benötigt eigene CI-Abnahme.

## 3. Neue echte Sprachlücke: lokale Anmeldeseite — noch offen

Das unveränderte Anwendungspaket wurde mit frischer In-Memory-HSQLDB, einem
lokalen Testadministrator, Mock-KI und deaktivierten Embedding-Downloads
nur auf Loopback gestartet. Der Readiness-Endpunkt meldete HTTP 200 und `UP`.
Direkte HTTP-Aufrufe ergaben reproduzierbar:

| Aufruf | Ergebnis |
| --- | --- |
| `/login` | HTTP 200, `lang=en`, Titel `Please sign in`, englische Feldbeschriftungen |
| `/login?lang=de` | HTTP 404, JSON-Fehler |
| `/login?lang=en` | HTTP 404, JSON-Fehler |
| `/login?error` und `/login?logout` | HTTP 200, englische generierte Anmeldeseite |

`SecurityConfig` verwendet die generierte Spring-Security-Anmeldeseite mit
`formLogin(Customizer.withDefaults())`, nicht eine lokalisierte MVC-Ansicht.
Der Befund ist mit Reproduktion und Abnahmekriterien in **[Issue #1179](https://github.com/carstenartur/Taxonomy/issues/1179)**
gespeichert und **in diesem Nachtrag nicht behoben**.

Der abgegrenzte Folgefix ist eine serverseitig lokalisierte Ansicht am bestehenden
`/login`-Pfad nur für `!keycloak`. Spring Security soll weiterhin die Anmeldung
verarbeiten. CSRF, Anmeldebegrenzung, Passwortwechselpflicht, gespeicherte
Zielanfragen, Sitzungsverwaltung, HTTP Basic und Keycloak dürfen nicht verändert
werden. Fehlanmeldung, Abmeldung, Kontextpfade und Bedienung ohne JavaScript
müssen mitgeprüft werden. Zusätzliche Kontoregistrierung oder Passwortreset werden
nicht erfunden.

Ein lokaler Chromium-Aufruf wurde vor dem ersten Seitenaufruf von der verwalteten
Umgebung mit `ERR_BLOCKED_BY_ADMINISTRATOR` blockiert. Die Richtlinie wurde nicht
geändert. Daher liegen für diesen neuen Login-Befund HTTP-, aber keine lokalen
Browser-/Axe-Nachweise vor. Die lokale Testanwendung wurde anschließend beendet.

## 4. Speicher-/Zeitmessung der Analysevorschau: Teilnachweis, kein Gesamtbenchmark

Gemessen wurde die tatsächliche private Methode `ClusterAnalysisStore.scoreEvidence`
des unveränderten Pakets, nicht eine nachprogrammierte Parserformel. Die nicht
benutzten Datenbank-/Transaktionsgrenzen waren durch sofort fehlschlagende Proxys
abgesichert; es wurden keine Datenbankabfragen oder Provideraufrufe ausgeführt.

Je Fall wurden drei JVM-Prozesse mit je 20 Aufwärm- und 30 Messdurchläufen verwendet:
Java 21.0.11, `-Xms64m -Xmx512m -XX:+UseSerialGC`. Die synthetischen Daten enthalten
Bewertungen und Originalbewertungen sowie optional einen langen Erklärungstext.
Die Tabelle zeigt den Median der drei Prozessmediane und die mittlere
**pro Aufruf allokierte** Bytezahl des ausführenden Threads. Die Erzeugung der
Eingabe ist nicht darin enthalten.

| Bewertungen | Zusätzliche Erklärung | JSON-Zeichen | Laufzeit (ms) | Allokierte Bytes | Zurückgegebene Bewertungen |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 512 | 0 | 12.007 | 1,254 | 243.264 | 512 |
| 8.192 | 0 | 209.337 | 6,421 | 3.837.530 | 8.192 |
| 8.192 | 8 Mio. Zeichen | 8.209.337 | 11,168 | 3.837.528 | 8.192 |
| 50.000 | 0 | 1.368.903 | 71,169 | 27.290.352 | 8.192 |
| 50.000 | 24 Mio. Zeichen | 25.368.903 | 72,844 | 27.290.352 | 8.192 |

Der lange nicht benötigte Erklärungstext verursacht hier keine proportional
zusätzlichen Objektallokationen. Die Bewertungsmenge tut dies dagegen: Trotz
begrenztem Rückgabewert werden weiterhin vollständige Bewertungsstrukturen
vorübergehend materialisiert. Das bestätigt den verbleibenden Optimierungspunkt.

**Nicht gemessen:** maximal gleichzeitig belegter Heap, Datenbank-/LOB-Übertragung,
gesamte HTTP-Latenz, Parallelbetrieb oder reale Nutzerlast. Allokierte Bytes sind
nicht gleich Spitzen- oder dauerhaft belegter Speicher. Die neue Messung ersetzt
deshalb keinen vollständigen Lasttest. Der Parser wurde nicht verändert; auch die
früheren Grenzen zur manuellen Barrierefreiheitsprüfung und zum 5.000-Aufträge-Limit
bleiben bestehen.
