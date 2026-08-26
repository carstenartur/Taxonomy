# Schutz vor Brute-Force-Anmeldungen

Taxonomy verwendet eine begrenzte Peer-Sperre nur im lokalen Benutzermodus. Keycloak-Bereitstellungen überlassen Zugangsdatenprüfung und Brute-Force-Schutz dem Identitätsanbieter.

## Authentifizierungsgrenze

Der Login-Begrenzer wird genau einmal innerhalb der Spring-Security-Filterkette registriert. Er läuft, nachdem `SecurityContextHolderFilter` eine vertrauenswürdige Browser-Sitzung wiederhergestellt hat, und bevor `UsernamePasswordAuthenticationFilter` sowie `BasicAuthenticationFilter` neue Zugangsdaten prüfen.

Daraus folgt:

- eine bereits authentifizierte, nicht anonyme Sitzung umgeht die Peer-Sperre;
- ein neuer Form-Login- oder HTTP-Basic-Versuch eines gesperrten Peers wird vor der Zugangsdatenprüfung abgewiesen;
- der Begrenzer dupliziert nicht die Passwortprüfung von Spring Security;
- nur das maßgebliche nachgelagerte Authentifizierungsergebnis verändert den Fehlversuchszustand.

Ein fehlgeschlagenes `POST /login` zählt, wenn Spring Security die Login-Fehlerantwort liefert. Eine Antwort unter `/api/**` zählt nur, wenn die Anfrage einen ausdrücklichen Header `Authorization: Basic ...` enthielt und Spring Security HTTP `401` zurückgab. Fehlende Zugangsdaten, Bearer-Zugangsdaten und andere Autorisierungsfehler erzeugen oder erhöhen keinen Sperrzustand.

## Peer-Identität und vertrauenswürdiger Ingress

Der Begrenzer verwendet ausschließlich den vom Framework aufgelösten Wert `HttpServletRequest.getRemoteAddr()`. Er wertet `Forwarded`, `X-Forwarded-For` oder ähnliche Header niemals selbst aus. In Kubernetes darf die Framework-Verarbeitung von Weiterleitungs-Headern nur hinter einem vertrauenswürdigen Ingress aktiviert sein; direkter Clientzugriff auf den Anwendungsport muss verhindert werden.

Die In-Memory-Sperrtabelle speichert einen SHA-256-Digest fester Länge des aufgelösten Peers, nicht die rohe Adresse. HTTP-Antworten und Begrenzerprotokolle geben weder die rohe Adresse noch übermittelte Zugangsdaten aus.

## Kapazität und Ablauf

Fehlversuchsfenster verwenden monotone Zeit, damit Änderungen der Systemuhr eine Sperre weder verlängern noch verkürzen. Inaktive Einträge laufen global ab. Die normale Peer-Tabelle ist pro laufender Anwendungsinstanz hart auf 10.000 Einträge begrenzt. Weitere Peers teilen ein fehlgeschlossenes Überlaufkontingent; sie können bestehende Sperren weder löschen noch verdrängen.

Der Zustand gilt jeweils nur für eine Anwendungsinstanz. Mehrere Replikate führen daher getrennte Peer-Sperrtabellen. Benötigt eine Bereitstellung ein clusterweites Authentifizierungskontingent, muss davor eine geeignete vertrauenswürdige Schutzschicht eingerichtet werden.

## Konfiguration

| Umgebungsvariable | Standard | Vertrag |
|---|---:|---|
| `TAXONOMY_LOGIN_RATE_LIMIT` | `true` | Aktiviert den Login-Begrenzer des lokalen Benutzermodus. |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `5` | Positive Zahl maßgeblicher Fehlversuche vor der Sperre. Null und negative Werte verhindern den Start. |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `300` | Positives Fehlversuchsfenster und Sperrdauer in Sekunden. Null und negative Werte verhindern den Start. |

Dieselbe Pfaderkennung gilt am Root-Kontext und unter einem Servlet-Kontextpfad wie `/taxonomy`.

## Antwort bei Sperre

Ein blockierter neuer Authentifizierungsversuch erhält UTF-8-JSON mit HTTP `423 Locked`. Die Antwort enthält:

- `Retry-After` mit den verbleibenden ganzen Sekunden;
- `Cache-Control: no-store`;
- `status: 423` und `retryAfterSeconds` im JSON-Inhalt;
- keine Peer-Adresse, keinen Benutzernamen und kein Zugangsdatenmaterial.
