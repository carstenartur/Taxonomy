# Git access to versioned document templates / Git-Zugriff auf Dokumentvorlagen

## Deutsch

### Ein Speicher, vorhandenes Protokoll

Die Git-Adresse des Vorlagenrepositorys ist relativ zur Taxonomy-Anwendungsadresse:

```text
<Anwendungsadresse>/api/admin/git/taxonomy-document-templates.git
```

Der Endpunkt verwendet `jgit-storage-hibernate-smart-http` in derselben Version wie
Core. Dessen `SecuredSmartHttp` stellt das normale JGit-Smart-HTTP-Protokoll bereit.
Er öffnet das bereits vorhandene logische Repository `taxonomy-document-templates`
über dieselbe Hibernate-SessionFactory. Es gibt keinen zweiten Server, Spiegel,
Dateisystemexport, eigenen Git-Protokollhandler oder zusätzliche Benutzerdatenbank.

Die bestehende `/api/admin/**`-Sicherheitsregel bleibt maßgeblich. Nur angemeldete
Administratoren dürfen die gesamte Vorlagenhistorie klonen. Die zusätzliche
Repository-Regel erlaubt ausschließlich DISCOVER/READ für genau dieses Repository;
andere Repositorys bleiben verborgen. Ein normaler Benutzer verwendet weiterhin die
für ihn vorgesehenen Dokumentdownloads. Das Git-Repository ist kein Dateidownload.

### Lesen mit normalen Git-Werkzeugen

Beispiel für den lokalen Entwicklungsstart ohne Kontextpräfix:

```bash
git ls-remote http://localhost:8080/api/admin/git/taxonomy-document-templates.git
git clone --branch main http://localhost:8080/api/admin/git/taxonomy-document-templates.git
cd taxonomy-document-templates
git log -- templates/
git fetch origin
git diff HEAD..origin/main -- templates/
```

Bei einer Installation unter `/taxonomy` lautet der Pfad entsprechend
`/taxonomy/api/admin/git/taxonomy-document-templates.git`. Die Beispiele sind keine
Bestätigung, dass eine bestimmte entfernte Instanz bereits aktualisiert wurde.
Außerhalb der lokalen Entwicklung ist die öffentlich konfigurierte HTTPS-Adresse
hinter dem vertrauenswürdigen Proxy zu verwenden.

Im lokalen Anmeldemodus fragt Git nach dem bestehenden Administrator-Benutzernamen
und Passwort. Keine Geheimnisse in URL, Shell-History oder Repository-Konfiguration
schreiben; zur dauerhaften Verwendung den vorgesehenen Git-Credential-Helper nutzen.
Im Keycloak-Profil ist ein gültiger Bearer-Token über eine geeignete sichere
Clientkonfiguration erforderlich; Browser-SSO ist kein Git-Passwort. WebDAV-spezifische
App-Passwörter werden für diesen neuen Pfad nicht freigeschaltet. Die Integration
führt keine weitere Authentifizierungsmethode ein.

Git liefert den tatsächlich versionierten OOXML-Baum:

```text
templates/<vorlagen-id>/template.json
templates/<vorlagen-id>/package/[Content_Types].xml
templates/<vorlagen-id>/package/word/document.xml
...
```

Word benötigt dagegen eine zusammengesetzte DOTX-Datei. Diese wird weiterhin über
die vorhandene Vorlagenverwaltung heruntergeladen, in Word bearbeitet und dort
wieder hochgeladen. Ein anschließendes `git fetch` zeigt die vom selben
Vorlagenservice gespeicherte Änderung und deren Historie.

### Bewusste Schreibgrenze

Dieser Endpunkt ist **nur lesend**: `ls-remote`, Clone und Fetch. Der wiederverwendete
Adapter deaktiviert Receive-Pack und den direkten Dumb-HTTP-Dateizugriff; die
Repository-Regel verweigert zusätzlich jede Schreiboperation. Auch ein Administrator
kann darüber nicht pushen. Weder neue Branches noch Force-Push sind freigegeben.

Ein späterer Git-Schreibweg muss vor der Veröffentlichung eines akzeptierten Commits
die bestehenden OOXML-, Manifest- und Platzhalterprüfungen wiederverwenden. Eine
nachträgliche Prüfung erst nach einem erfolgreichen Push wäre nicht ausreichend.
Diese begrenzte Leseintegration umgeht daher bewusst keinen bestehenden Uploadvertrag.

## English

The template remote is `<application-base>/api/admin/git/taxonomy-document-templates.git`.
Include the application's context path, when configured. It embeds the existing
`jgit-storage-hibernate-smart-http` adapter over the same logical repository and
Hibernate SessionFactory used by template uploads. No mirror, extra server,
credential database, custom Git protocol or workflow is added.

Only administrators may read the complete history through standard Git clients.
The existing Spring authentication, role and CSRF configuration remains unchanged.
Local authentication uses the existing administrator account; Keycloak deployments
need valid Bearer credentials provided through secure client configuration. A browser
session and a WebDAV application password are not automatically Git credentials.
Use HTTPS outside loopback development and never embed credentials in the remote URL.

Use `git clone --branch main`, `git fetch`, `git log` and `git diff` as above.
The checkout contains unpacked OOXML and the template manifest, not an immediately
editable Word file. Continue using the existing validated DOTX upload for changes;
a fetch observes those same accepted commits. Git push is deliberately disabled,
including new refs and force updates. Other internal repositories are not exposed.

## Verification boundary / Prüfumfang

`DocumentTemplateGitHttpIT` starts the real Spring application below `/taxonomy`
with an isolated HSQLDB database. It invokes the installed **Git CLI** for remote
listing, clone, object integrity, fetch after an actual template HTTP upload,
content/history comparison and rejected push. HTTP checks cover anonymous/bad
credentials, a real non-admin account, hidden sibling repositories, disabled
receive-pack and disabled dumb HTTP. `DocumentTemplateGitHttpConfigTest` checks
every Core permission and the request-bound administrator requirement.

These tests use the existing Maven/Failsafe discovery; no additional workflow is
introduced. Run the complete repository verification command with Git, Java 21 and
Docker available:

```bash
./mvnw verify -DexcludedGroups="real-llm"
```

A passing local or CI test does not certify an independently deployed URL, a proxy,
Keycloak configuration, Microsoft Word client, SSH, LFS or Git-based template writes.
