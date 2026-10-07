# CI-Nachtrag: mobiler Titelumbruch und finale Laufzeitprüfung

Auf dem neu eingefrorenen ausführbaren JAR `8e264dd0884bb2542bb48a5f337c25e26df173cc686ebab4ce224d4ddc29dd07` besteht die aktuelle ADMIN-Primary-Suite mit **64 Prüfungen**, **21 Axe-Scans ohne Befund** und **18 Screenshots**. Die unveränderten fokussierten Layoutprüfungen bestehen mit **18 Assertions** und ohne Browser-PageErrors. Der Server lief direkt aus dieser JAR, ohne Ressourcenoverride.

Die 18 Layout-Assertions wiederholen für die konkrete Geometrie- und Fontmessung denselben Ablauf, den die ADMIN-Suite verwendet. Sie werden nicht zu den 64 ADMIN-Prüfungen als zusätzliche eigenständige Abdeckung addiert.

Der vorausgehende normale Maven-Lauf `./mvnw -B -ntp -nsu install -DskipTests -Dmaven.build.cache.enabled=false` hat alle **16 Module** kompiliert, paketiert und ausschließlich im lokalen Maven-Repository installiert. Java-Tests wurden dabei übersprungen. Die gesonderten Portfolio-JUnit-Abnahmen sind ein eigener Nachweis; dieser Lauf ist weder ein vollständiger `test-local`- noch ein CI-Pass.

## Ursache und unveränderte Eingabefläche

Der zuvor fehlgeschlagene veröffentlichte CI-Lauf ist [37583519381, mobiler ADMIN-Shard](https://github.com/carstenartur/Taxonomy/actions/runs/37583519381/job/112669368043). Der originale mobile Versions-Trennstrich blieb sichtbar, obwohl die Versionsnummer ausgeblendet wurde. Mit der in CI vorhandenen Noto-Emoji-Schrift rutschte der verwaiste Trennstrich auf eine zusätzliche 20-Pixel-Zeile. Der Trennstrich wird nun zusammen mit der Version ausgeblendet.

| Messung bei 390 × 844 | Vor dem Fix mit CI-Emoji-Font | Finale JAR mit CI-Emoji-Font |
| --- | ---: | ---: |
| Navbar-Höhe | 144 px | 124 px |
| Höhe des fachlichen Texteingabefelds | 182 px | 182 px |
| Unterkante der primären Aktion | 857.09375 px | 837.09375 px |

Die bestehende Assertion `y + height <= viewport.height` ist unverändert. Das Eingabefeld bleibt 182 Pixel hoch und vertikal vergrößerbar. Die lokal reproduzierte rote Messung und die grüne Messung erklären den früheren Unterschied von exakt 20 Pixeln.

## Identität und Reproduzierbarkeit

Alle **225 Ressourcen** aus `taxonomy-app/src/main/resources`, einschließlich des aktualisierten Portfolio-README, stimmen mit der eingefrorenen JAR überein. Erlaubte Buildtransformationen sind einzeln dokumentiert: Maven-Versionsersetzung in `application.properties` und Spring Boots META-INF-Platzierung. Der Quellstand blieb während des Install-Laufs unverändert.

Der attestierte Ausgangs-Commit ist `26bb32f8ec9ca9e16d64d31212dcb8574267e09c` mit Baum `a980c2e4d7ae476a0864b87d3b274282f35730f6`. Das JSON ergänzt die Hashes aller noch geänderten Produkt- und Testdateien unter `.github` und `taxonomy-*`; Tooling, Caches, `node_modules`, Buildausgaben und QA-Dokumente sind ausgeschlossen. Die Git-Metadaten im JAR stammen möglicherweise aus dem ursprünglichen Checkout des verknüpften Worktrees und sind deshalb kein eigenständiger Quellenbeleg.

Verwendet wurden Temurin 21.0.12.1+1, Maven 3.9.16, Node v24.18.0, Chrome Headless Shell 149.0.7827.55, Playwright 1.61.1 und Axe 4.12.1. Der temporär eingebundene Emoji-Font entspricht dem CI-Paket `fonts-noto-color-emoji` 2.047-0ubuntu0.24.04.1; Font-SHA256: `93cdc4ee9aa40e2afceecc63da0ca05ec7aab4bec991ece51a6b52389f48a477`. Das Manifest enthält außerdem ausführbare Browser-, Driver-, JDK-, Node- und Fontconfig-Hashes; der Layoutbericht erfasst die tatsächlich verwendeten Navbar-Schriftarten.

Der Server verwendet eine reale Spring-Boot-Anwendung mit frischem HSQLDB-Zustand, deterministischem Mock-LLM und deaktivierten Embeddings. Die Browserfälle bedienen und messen die Anwendung; Portfolio-Migrationsfälle sind inzwischen dem separaten Java/Maven-Owner zugeordnet. Daher werden die aktuellen Zähler verwendet und die früheren 72/23 nicht fortgeschrieben.

Die historische JAR `5559bf41b57704449ede816f71d0e32e6c6cb5d70cf40ecc99cf06b18b4ba0d4` und ihre ursprünglichen Nachweise bleiben unverändert erhalten.

## Nachweise

- [Build-, Ressourcen- und Toolchainmanifest](ci-followup-application-build.json)
- [Laufzeitübersicht](ci-followup-runtime-summary.json)
- [Vollständiger ADMIN-Bericht](ci-followup-primary-admin.json)
- [Konkrete Layout- und Fontmessungen](ci-followup-context-layout.json)

Die JAR und Rohlogs liegen temporär außerhalb des Repositorys. Ihre Hashes sind im Manifest gespeichert; es wird keine ausführbare Binärdatei eingecheckt.
