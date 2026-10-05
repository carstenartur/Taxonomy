# Tests und GUI ohne Docker

`test-local` führt die vorhandenen Java-Tests mit Spring Boot, HSQLDB und lokalem
Chrome aus. Es gibt keine zweite Anwendung und keine Kopie der fachlichen Tests.
Die Szenarien benutzen dieselben HTTP-Aufrufe, DOM-Interaktionen, Assertions und
Exportprüfungen wie mit dem Selenium-Container.

## Voraussetzungen und Start

Java 21, der eingecheckte Maven Wrapper, Node für die vorhandenen JavaScript-
Vertragstests und ein zusammenpassendes Chrome/ChromeDriver-Paar werden benötigt.
Zum Beispiel liefert [Chrome for Testing](https://googlechromelabs.github.io/chrome-for-testing/)
beide Programme in derselben Version. Unter Linux müssen deren Systembibliotheken
installiert und die entpackten Programme ausführbar sein. Es wird kein Browser
automatisch heruntergeladen und bei fehlenden Voraussetzungen kein Test still
übersprungen.

`CHROME_BIN` kann auch auf `chrome-headless-shell` zeigen, wenn es zur Version von
ChromeDriver passt. Der lokale Adapter setzt das echte Downloadverzeichnis auch
über das Browserprotokoll, damit Downloads unabhängig von Chrome-Profilpräferenzen
funktionieren. Der unten gezeigte `BrowserSessionIT` prüft den Dateidownload und
seinen Inhalt mit dem tatsächlich ausgewählten Browser.

```bash
export CHROME_BIN=/absolute/path/chrome
export CHROMEDRIVER=/absolute/path/chromedriver
./mvnw -B verify -Ptest-local
```

Alternativ können die Pfade mit `-Dscenario.chrome.binary=...` und
`-Dwebdriver.chrome.driver=...` übergeben werden. Maven prüft die Dateien in
`validate`; der Browserstart prüft absolute, ausführbare Pfade. Ein inkompatibles
Paar führt zu einem normalen Testfehler mit der ChromeDriver-Diagnose.

Das Profil aktiviert auch Failsafe (`*IT`) und die Browseransicht des
Architekturszenarios. Eine gezielte Abnahme mit den sechs bereits vorhandenen
Szenariotestklassen ist schneller als der vollständige Lauf:

```bash
./mvnw -B -pl taxonomy-app -am test -Ptest-local,scenario-acceptance
```

Für die Browser-Infrastruktur und die fünf Live-Fortschrittsprüfungen:

```bash
./mvnw -B -pl taxonomy-app -am verify -Ptest-local \
  -Dtest=BrowserSessionTest \
  -Dit.test=BrowserSessionIT,AnalysisLiveProgressUiIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Die beiden `failIfNoSpecifiedTests`-Optionen erlauben hier nur vorgeschaltete
Reaktormodule ohne ausgewählte Klasse. Die benannten Tests müssen im
Surefire-/Failsafe-Bericht mit positiven Ausführungszahlen erscheinen.

## Abdeckung und CI

| Bereich | `test-local` | Bestehende CI/Profile |
|---|---|---|
| Java-, Service-, HTTP- und HSQLDB-Tests | dieselben Tests | unverändert |
| Architektur- und Reformulierungsszenarien, Desktop/390px, Downloads | lokales Chrome | Selenium-Container |
| Live-Fortschritt, Abbruch und sichere DOM-Darstellung | lokales Chrome | Selenium-Container |
| JVM-Neustart, Recovery und persistierte Historie | echte getrennte JVMs und dateibasierte HSQLDB | unverändert |
| Gepackte App im Container, Keycloak, PostgreSQL, MSSQL, Oracle | ausdrücklich ausgeschlossen | bestehende Container-/DB-Profile bleiben zuständig |
| ONNX und echte externe LLMs | ausdrücklich ausgeschlossen | bestehende Opt-in-Profile bleiben zuständig |
| Vollständige Playwright-/Accessibility-Matrix | separat über das vorhandene `ui-tests`-Profil | bestehender Runner und Matrix bleiben zuständig |

`docker-required` kennzeichnet Tests, die wirklich Container für die Anwendung,
Identitätsverwaltung oder Datenbank benötigen; Basisklassen vererben das Tag.
`browser` kennzeichnet die unabhängig vom Browser-Standort ausführbaren
Browserverträge. Die vorhandenen DB-/ONNX-/LLM-Tags bleiben erhalten.

`test-local` ersetzt keinen CI-Abnahmebericht. Es wird nicht zusätzlich als zweite
vollständige CI-Lane gestartet; Workflow-Auswahl und bisherige Gates bleiben
unverändert. `ci` und `test-local` sind alternative Umgebungsprofile und werden
nicht kombiniert. Ohne explizites `taxonomy.test.browser` bleibt das bisherige
Verhalten erhalten: ein gesetzter `webdriver.chrome.driver` wählt lokal,
ansonsten wird der Selenium-Container verwendet. Explizites `local` fällt bei
Fehlern niemals auf Docker zurück.

## Die echte Oberfläche ansehen

Die Szenarien erzeugen Screenshots und Downloads unter
`taxonomy-app/target/scenario-acceptance/` sowie
`taxonomy-app/target/reformulation-scenario-acceptance/`. Das lokale Profil legt
Architekturbilder unter `scenario-acceptance/screenshots/` ab und überschreibt
keine eingecheckten Dokumentationsbilder. Fehler liefern zusätzlich HTML und
Geometrieinformationen. Der Live-Fortschritt schreibt
`taxonomy-app/target/analysis-ui-evidence/live-progress.png`.

Für eine interaktive Vorschau kann dieselbe gepackte Spring-Boot-Anwendung nach
`verify` weiterlaufen. Der folgende Aufruf verwendet eine eigene dateibasierte
HSQLDB unter `target/local-preview`, keine externe LLM und keine Modell-Downloads:

```bash
java -jar taxonomy-app/target/taxonomy-app-*.jar \
  --server.address=127.0.0.1 --server.port=8080 \
  --spring.profiles.active=hsqldb \
  '--spring.datasource.url=jdbc:hsqldb:file:./target/local-preview/db;shutdown=true' \
  --spring.jpa.hibernate.ddl-auto=update \
  --taxonomy.admin-password=Local-Preview-Only-2026! \
  --taxonomy.security.require-password-change=false \
  --embedding.enabled=false --embedding.allow-download=false --llm.mock=true
```

Dann `http://127.0.0.1:8080` öffnen und mit `admin` / `Local-Preview-Only-2026!`
anmelden. Dieser Start bleibt bis zum Beenden des Java-Prozesses verfügbar.
Die deterministischen Test-LLM-Antworten ersetzen weiterhin nur den Provider;
GUI, Controller, Services, Persistenz und Exporte laufen als echte Komponenten.
