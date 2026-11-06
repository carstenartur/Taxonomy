# Database Setup Guide

This guide explains how to run the Taxonomy Architecture Analyzer with different database backends. By default, the application uses an embedded **HSQLDB** database that requires no setup. For persistent external-database production, use PostgreSQL with the released JGit Core and Taxonomy Flyway migrations. SQL Server and Oracle profiles are compatibility/evaluation paths; their complete migration, upgrade and restore contracts have not been qualified for production. A JDBC driver and Hibernate dialect alone do not establish support.

---

## Table of Contents

- [Overview](#overview)
- [Optional database setup assistant](#optional-database-setup-assistant)
- [Keep data and settings across restarts](#persistent-installation)
- [Initialize an empty SQL Server or Oracle schema](#schema-initialization)
- [PostgreSQL Pod in Rancher or Kubernetes](#postgresql-pod-in-rancher-or-kubernetes)
- [HSQLDB (Default)](#hsqldb-default)
- [PostgreSQL](#postgresql)
- [Microsoft SQL Server (MSSQL)](#microsoft-sql-server-mssql)
- [Oracle Database](#oracle-database)
- [Adding a New Database](#adding-a-new-database)
- [Common Configuration](#common-configuration)
- [Moving from HSQLDB to PostgreSQL](#moving-from-hsqldb-to-postgresql)
- [Related Documentation](#related-documentation)

---

## Overview

The application provides database backends through **Spring Profiles**. Each database profile configures the JDBC driver, dialect, connection pool, and any database-specific column type mappings. Their deployment and migration support differs:

| Profile | Database | Activation | Docker Compose File | Support boundary |
|---|---|---|---|---|
| `hsqldb` *(default)* | HSQLDB (in-process) | No configuration needed | — | In-memory development/tests; file-backed controlled baseline |
| `postgres` | PostgreSQL 14+ | `SPRING_PROFILES_ACTIVE=postgres` | `docker-compose-postgres.yml` | Persistent external database with released migrations |
| `mssql` | SQL Server 2019+ | `SPRING_PROFILES_ACTIVE=mssql` | `docker-compose-mssql.yml` | Compatibility/evaluation; no qualified complete migration path |
| `oracle` | Oracle 19c+ / 23c Free | `SPRING_PROFILES_ACTIVE=oracle` | `docker-compose-oracle.yml` | Compatibility/evaluation; no qualified complete migration path |

All database profiles share the same entity model. Hibernate's `@Nationalized` and `@Lob` annotations ensure correct Unicode and large-text handling across all databases.

---

## Optional database setup assistant

On the first eligible administrator visit while Taxonomy uses embedded HSQLDB,
the welcome dialog offers database setup. You can close it, press Escape, or
continue with the current database. Dismissal keeps the active configuration and
application data intact and is stored on the server through `PreferencesService`
as `setup.database.dismissed`. It applies to the installation and survives a new
browser session. With in-memory HSQLDB it lasts only as long as that database;
resetting or recreating the data also removes the saved choice. Administrators
can reopen the assistant under **Tools > Database setup**.

**Host/Service prerequisite:** The selected endpoint must route to one logical,
writable database target. Do not load-balance independently initialized databases
behind the same Service. For high availability, use the database operator's
supported writable-primary endpoint or writable Oracle PDB service. The wizard
checks logical names, credentials and permissions; it does not validate replication
or the database topology.

1. Select **PostgreSQL**, **SQL Server** or **Oracle** and choose an existing connection or the optional creation path. The server and a reachable Service must already exist. PostgreSQL creates a database and restricted account, SQL Server creates a database with a login and mapped user, and Oracle creates a local application user/schema in an **existing PDB**. Oracle server/PDB creation is outside this wizard. PostgreSQL creation requires version 15 or later.
2. Enter the host reachable **from the Taxonomy Pod**, port, application database and application user. Configure TLS to match the server. Verified TLS requires server support, a matching certificate hostname and a trusted CA in the Taxonomy Pod. An optional CA path refers to a mounted server file, such as `/etc/taxonomy-db-ca/ca.crt`. Selecting TLS does not enable it on PostgreSQL. If you explicitly select an unencrypted connection, the wizard displays a transport warning and never silently falls back from TLS.
3. For an **existing database**, select **Test connection**. The main result reports success, a diagnosed failure or an unknown failure. Success means that a separate connection logged in with the submitted settings; schema migration and deployment readiness remain separate. You can edit the inputs and test again. Scope details are expandable.
4. For **creation**, enter a separate administrator login and an application password. Select **Check server and proposed setup**, review the names and create/reuse actions, then confirm creation explicitly. Oracle also requires an existing application tablespace and a finite quota. The inspection step creates nothing. If resources change between inspection and confirmation, the wizard requires a fresh inspection. Completion verifies the application login and the vendor-specific ownership/permissions; it does not prove that all Taxonomy tables exist.
5. Select **Review deployment configuration**. First follow [data and settings retention](#persistent-installation); a new target does not contain your current installation. Put the application password in your Rancher Secret and review [schema initialization](#schema-initialization) for SQL Server/Oracle before applying configuration and restarting. Downloads omit both passwords. The deployment activates the new connection; PostgreSQL's released startup migrations initialize its fresh schema.

### Starting with the official PostgreSQL image

On a fresh empty data directory, the official `postgres:18` image initialized only with **`POSTGRES_PASSWORD`** creates the administrative user **`postgres`** and database **`postgres`**. Use those in the administrator section and choose a separate account such as **`taxonomy_app`** with database **`taxonomy`** in the application section. Those application names are suggestions until the explicit creation step succeeds. Setting `POSTGRES_USER=taxonomy_app` creates a superuser and is not the same as creating a restricted application account. Image initialization variables do not recreate accounts on an existing volume. See the [official image documentation](https://hub.docker.com/_/postgres).

The optional creation path creates a restricted LOGIN role and a dedicated database owned by it. A new account requires a PostgreSQL server administrator; a non-superuser may reuse a verified existing restricted account when it has the required database-creation and owner-role capability. Administrator credentials never become the application credentials. The application password is converted to a SCRAM verifier before it is included in creation SQL, using [pgJDBC PasswordUtil](https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/util/PasswordUtil.html).

Existing resources are reused only after checking account privileges/memberships, supplied application access and database ownership. The wizard does not change an existing password or owner, or drop resources. A successful login reflects the server's authentication rules; if an administrator configured `trust` authentication, it does not establish that the supplied password is correct.

### SQL Server and Oracle creation prerequisites

For **SQL Server 2019 or later**, use SQL authentication and a temporary setup
administrator with `sysadmin`, connected through administrative database `master`.
This path targets SQL Server instances, not Azure SQL provisioning. It creates a
SQL login and its database; the new database is owned by that login, which connects
as database user `dbo`. It does not add another `CREATE USER` account or grant the
application server-wide administrator rights. Reuse requires a compatible active
SQL login without extra server roles/grants and its existing online, writable
database with `TRUSTWORTHY` and `DB_CHAINING` disabled. Existing ownership and
passwords are not changed.

For **Oracle 19 or later**, connect to an existing writable **PDB service** such as
`FREEPDB1`. Use `SYSTEM` or a setup administrator with the active `DBA` role and
access to `SYS.DBMS_LOCK`; a missing privilege is reported, not automatically granted.
Select an existing online permanent application tablespace, normally `USERS`, and
a finite quota in MiB (default **100 MiB**, range **1–1048576 MiB**). The wizard creates
a local application user/schema with `CREATE SESSION`, `CREATE TABLE` and
`CREATE SEQUENCE`. It excludes root/seed containers and system accounts/storage.
Reusing a user requires a compatible open local password account, no extra
roles/system privileges, the same tablespace and exactly the selected finite quota;
it does not repair the account or change its permissions/quota. Image-created
`APP_USER` accounts can have different grants and are not automatically compatible
with these reuse checks.

Both paths preserve the distinction between [resource preparation and application
schema initialization](#schema-initialization). Administrative passwords stay out
of the application deployment. A partial or uncertain result requires inspection
before another explicit creation attempt; no automatic cleanup or write retry occurs.

| Main result | Meaning and next step |
|---|---|
| Connection successful | Taxonomy could log in with the submitted connection settings. Review and apply the deployment configuration. |
| Connection failed | Follow the diagnosis for DNS, connection refusal, TLS, login, missing database, permissions or timeout, then test again. |
| Setup checked — review the plan | No creation was requested. Review each resource marked create/reuse and confirm explicitly. |
| Database access prepared | The application connection and vendor-specific account/database permissions were verified. Review schema initialization and retained data before applying configuration. |
| Setup partly completed | The result identifies which resources were created. Inspect the current state before continuing; there is no automatic cleanup. |
| The setup outcome is unknown | The response or database operation was interrupted. Resources may already exist. Inspect before attempting another creation. |

For PostgreSQL, `CREATE DATABASE` cannot share a transaction with role creation, so partial or uncertain outcomes are meaningful states rather than an all-or-nothing rollback. The wizard disables editing and navigation during creation and never automatically retries writes. A later inspection rereads server state and can safely reuse compatible resources. See [CREATE DATABASE](https://www.postgresql.org/docs/18/sql-createdatabase.html).

Closing and reopening the dialog in the same page session retains non-secret inputs and TLS choices. Both passwords are cleared; the administrator password is also cleared after successful preparation. Reloading the page starts a fresh form. Configuration export preserves other active/default profiles, replaces the old database profile and adds `kubernetes` in Helm output. It preserves `production` when already active and does not add it automatically.

If configuration export reports a **schema-setting conflict**, review existing
`spring.jpa.properties.hibernate.hbm2ddl.auto`,
`spring.jpa.properties.jakarta.persistence.schema-generation.database.action`,
and the legacy `spring.jpa.properties.javax.persistence.schema-generation.database.action`
setting. Also check `spring.jpa.hibernate.ddl-auto` in `SPRING_APPLICATION_JSON`,
JVM system properties, or command-line arguments: those sources can outrank the
generated environment settings. Remove the conflicting overrides or set them to
`validate` in the deployment, then retry once the corrected configuration is
effective. The ordinary embedded-HSQLDB default in an application-properties file
does not prevent export. Connection testing remains available during a conflict.
This check examines the current server configuration; still review settings added
later to the target deployment before restarting it.

The operator applies the configuration and deliberately redeploys/restarts
Taxonomy. The assistant does not switch the running datasource, provision Pods,
initialize Taxonomy's tables, or restart the application. It does **not transfer existing
HSQLDB data**. Keep the source database and a verified backup if those records
must be retained; see [Moving from HSQLDB to PostgreSQL](#moving-from-hsqldb-to-postgresql).

TLS reference: [pgJDBC](https://jdbc.postgresql.org/documentation/ssl/),
[Microsoft JDBC](https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-with-ssl-encryption),
and [Oracle JDBC](https://docs.oracle.com/en/database/oracle/oracle-database/23/jjdbc/client-side-security.html).

<a id="persistent-installation"></a>
## Keep data and settings across restarts

**Check the current storage before the first restart or mount change.** Application
settings are saved in the active database. A mounted volume, a successful connection
test or a newly created empty database does not transfer an existing installation.
In particular, `jdbc:hsqldb:mem:...` loses its contents when its application process
ends, even if `/app/data` has a PVC.

### Where state lives

| State | Storage to retain |
|---|---|
| Saved work, users/roles, server-side settings and their history | The active database. Settings use the `taxonomy-preferences` Git repository stored in that database; Git objects, refs and reflogs are also database-backed. There is no separate `/app/data/git` store. |
| File-backed HSQLDB | All `/app/data/taxonomydb*` files, when the JDBC URL selects that path, on the Taxonomy PVC. |
| External PostgreSQL, SQL Server or Oracle | The database server's own persistent storage and database-native backups. The Taxonomy PVC does not contain it. |
| Retained source files and filesystem search indexes | The configured paths, normally `/app/data/source-content` and `/app/data/lucene-index`. Back up separately mounted paths as well. Do not assume every missing index rebuilds completely on startup. |
| Deployment configuration, connection passwords and trust material | Retained Helm values/manifests, Secrets and ConfigMaps. Keep credentials in Secrets; public CA certificates may be in ConfigMaps. Preserve external identity-provider configuration separately. |

Bundled ONNX models are immutable image content under `/opt/taxonomy/models`.
Custom model mounts, an external Artemis broker and optional backup-job directories
have their own storage requirements. Browser preferences, unsaved browser edits,
sessions and process caches are not made durable by attaching a database volume.
See [backup scope](OPERATIONS_GUIDE.md#backup-and-recovery).

### Choose the safe starting case

| Current installation | Before changing it |
|---|---|
| Disposable in-memory trial | Deliberately decide that current work can be discarded. Prepare a new persistent target, then configure and start it. |
| In-memory installation containing valuable data or settings | Keep the current process running. Record settings and deployment configuration and establish a verified transfer/recovery procedure before restarting. The project ships no automatic live-memory export or complete cross-database transfer. Saving settings manually is not a complete backup. |
| Existing file-backed or external database | Verify a consistent backup and an isolated restore. Reuse the same retained storage or restore into a separate persistent target before switching connections. |

A blank PVC mounted over a populated container directory does not copy the old
files. For an external database currently using ephemeral storage, capture a
database-native consistent backup while the source is still available, restore to
the persistent target, and verify it before cutover. For file-backed HSQLDB, stop
**all writers** before a filesystem backup; use the tested
[same-version cold recovery procedure](CONTAINER_IMAGE.md#5-persistence-and-backup).
That procedure does not recover a stopped in-memory database. Portable `.taxbackup`
import and automatic HSQLDB-to-other-vendor migration are not released recovery paths.

### Rancher checks and existing chart values

In the selected Rancher cluster and namespace, inspect the workload's **Volumes**
and environment/Secret references, then the storage view's **PersistentVolumeClaims**.
Menu wording varies by Rancher version. Verify the actual mounted path and claim;
the mere presence of a PVC in the namespace proves neither that the Pod uses it nor
that the database writes there. Inspect the workload YAML when necessary.

1. Confirm the effective JDBC mode and database, chosen image/version, actual mount
   path and bound claim. Check its StorageClass, capacity, write permissions and
   PV reclaim policy. A file path alone does not prove durable storage.
2. Save the Helm values/manifests, Secret references and protected credential backup
   outside the Pod. Record a recovery point **before** changing mounts or restarting
   a temporary installation. A Kubernetes Secret preserves deployment credentials;
   it is not an application-data backup.
3. For embedded file HSQLDB, use one application instance, `Recreate`, a file URL
   below `/app/data` and a PVC there. For an external DB, configure its own data PVC
   or managed storage; also retain Taxonomy's local source/index files when used.
4. After a verified cutover, test login, a previously saved setting, workspaces,
   history and representative search. Then perform a controlled application restart
   and verify them again. Test database recovery separately; do not delete a PVC as
   a persistence test.

The existing chart can retain application files with this values fragment:

```yaml
replicaCount: 1
upgrade:
  strategy: Recreate
persistence:
  enabled: true
  accessModes: [ReadWriteOnce]
  size: 10Gi
  storageClass: "" # Choose an existing class, or deliberately use the cluster default.
```

This fragment creates the chart's `/app/data` PVC. It does not change the datasource
or preserve an external database. For a **fresh file-HSQLDB baseline**, use
`database.type=existing`, set `config.SPRING_PROFILES_ACTIVE=production,hsqldb,kubernetes`,
`config.TAXONOMY_DDL_AUTO=update`, and keep `TAXONOMY_INIT_RELOAD_EXISTING=false`.
In the referenced Secret, set `SPRING_DATASOURCE_URL` to
`jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;hsqldb.write_delay_millis=0;shutdown=true`
and supply the intended HSQLDB username/password plus the required application-login
Secret keys. If selecting `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem`, keep its
root under the same protected directory. These settings prepare a new file database;
they do not move the current in-memory contents.

The chart creates its own application PVC; it does not provide an `existingClaim`
setting or promise retention after Helm uninstall, namespace deletion or PVC
deletion. A `Delete` reclaim policy can delete the backing storage. Review lifecycle
and restore arrangements before deleting resources; a PVC is not a backup. See
[Kubernetes persistent volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)
and the [existing Rancher deployment guide](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/RANCHER.md).

### Database Pod storage by image

| Database image | Persistent directory | Initial setup |
|---|---|---|
| Official `postgres:18` | Mount `/var/lib/postgresql`; default `PGDATA=/var/lib/postgresql/18/docker` | Bootstrap credentials from a Secret; then create a separate application account/database. Versions through 17 use `/var/lib/postgresql/data` by default. |
| Microsoft SQL Server Linux | `/var/opt/mssql`, including system DBs and protected secrets, or the explicitly configured data/log/secrets paths | Configure `ACCEPT_EULA` and the appropriately licensed `MSSQL_PID`; supply the administrator password through a Secret and create a separate application login/database. |
| Repository example `gvenzl/oracle-free:23-slim-faststart` | `/opt/oracle/oradata` | `ORACLE_PASSWORD` sets SYS/SYSTEM passwords; `ORACLE_DATABASE` creates a PDB. `APP_USER`/`APP_USER_PASSWORD` create a separate application schema user, or use the wizard for that user in an existing PDB. |

Match ownership and write permissions to the **database image**, not Taxonomy's
container identity. Oracle-owned images use different variables such as `ORACLE_PWD`
and `ORACLE_PDB`; consult the exact image instructions. SQL Server Developer edition
is for development/testing, and Oracle Free has edition-specific limits and terms.
Selecting a larger PVC does not change licensing or Taxonomy's compatibility tier.
References: [PostgreSQL image](https://hub.docker.com/_/postgres),
[SQL Server storage](https://learn.microsoft.com/en-us/sql/linux/containers/configure),
[SQL Server editions](https://www.microsoft.com/licensing/guidance/SQL),
[gvenzl Oracle image](https://github.com/gvenzl/oci-oracle-free),
[Oracle-owned images](https://github.com/oracle/docker-images/blob/main/OracleDatabase/SingleInstance/README.md).

The [database Pod guide](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/DATABASE_PODS.md)
and the [PostgreSQL](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/values-postgres-service.yaml),
[SQL Server](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/values-mssql-service.yaml)
and [Oracle](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/values-oracle-service.yaml)
Service values reuse the existing chart.
They keep database storage independent and describe DNS, TLS and NetworkPolicy rules.
A ClusterIP Service provides internal addressing; limiting access to Taxonomy also
requires suitable, enforced NetworkPolicies. TLS and network isolation protect
different parts of the connection and must match the deployment's chosen policy.

<a id="schema-initialization"></a>
## Initialize an empty SQL Server or Oracle schema

**An account and a successful login do not initialize Taxonomy's tables.** The
optional SQL Server/Oracle creation paths prepare database accounts and permissions;
Oracle works inside an existing PDB and creates the application schema user with a
finite tablespace quota. The normal exported deployment remains
`TAXONOMY_DDL_AUTO=validate`, and the profiles keep `spring.flyway.enabled=false`.
Do not enable PostgreSQL's Flyway migrations for these engines. Portable contract
migrations alone are not a complete empty-schema initialization.

For retained installations, qualify the database-specific schema, upgrade and
restore procedure for the intended release before deployment. A validation failure
must be investigated against that procedure. Never use `create` or `create-drop`
to repair a database containing retained data.

Only a **fresh, isolated, disposable evaluation target** may follow the repository's
evaluation fixture: start one Taxonomy instance with `TAXONOMY_DDL_AUTO=create` to
create Hibernate-managed tables, check that startup succeeds, then change the
deployment to `TAXONOMY_DDL_AUTO=validate` **before any subsequent application restart**.
The supplied SQL Server and Oracle Compose files deliberately retain `create` and
are therefore disposable examples, even though their DB files have a volume.
Do not repeatedly run those files against data you wish to keep. Startup overrides
must agree with the selected mode. In particular, the wizard's Helm export also
sets `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` and
`SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO=validate`; changing only
`TAXONOMY_DDL_AUTO` will not override these. Use a separate disposable initialization
configuration with no conflicting native Hibernate/JPA schema actions, then restore
the complete reviewed validation configuration for retained operation. Never remove
these protections from an existing retained database. The normal wizard export does
not enable destructive initialization. This evaluation path does not establish production
migration or restore support.

## PostgreSQL Pod in Rancher or Kubernetes

A straightforward starting arrangement is a separate PostgreSQL **StatefulSet**
with its own **PersistentVolumeClaim (PVC)** and a **ClusterIP Service** in the same
namespace as Taxonomy. The Taxonomy chart and browser assistant do not create the
PostgreSQL workload; the optional assistant can create a database and account on an existing server. The existing [database Pod guide](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/DATABASE_PODS.md)
and [PostgreSQL values example](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/values-postgres-service.yaml)
show how Taxonomy connects to a separately managed database.

1. **Create the workload and storage.** In Rancher, open the cluster's workload
   view, create a StatefulSet in the intended namespace, and attach a volume claim
   using a suitable StorageClass. Pin a PostgreSQL image version approved for your
   release and environment. For the official `postgres` image, version 17 uses
   `/var/lib/postgresql/data`; version 18 mounts the volume at `/var/lib/postgresql`
   and defaults to `PGDATA=/var/lib/postgresql/18/docker`. Match the PVC mount to the
   chosen image and configuration. See [Rancher workloads](https://ranchermanager.docs.rancher.com/how-to-guides/new-user-guides/kubernetes-resources-setup/workloads-and-pods/deploy-workloads),
   [Kubernetes StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/),
   and the [official image instructions](https://hub.docker.com/_/postgres).
2. **Create the database and application account**, either with the optional assistant above or through your database administration process. Use a database named
   `taxonomy` and a dedicated restricted account such as `taxonomy_app`. Keep
   administrator/bootstrap credentials separate. The official image's
   `POSTGRES_USER` creates a **superuser**; setting it to the application username
   does not create a restricted application account. Provision the application
   role separately, with ownership/DDL rights for the Taxonomy database and schema
   needed by startup migrations, without cluster-wide superuser privileges.
   `POSTGRES_DB=taxonomy` and initialization scripts apply to an empty data
   directory; changing them does not reconfigure an existing PVC. See the
   [image's initialization contract](https://hub.docker.com/_/postgres).
3. **Expose an internal Service and configure TLS.** A Service called `postgres`
   on TCP 5432 can be addressed as `postgres` from the same namespace. For another
   namespace, use a name such as `postgres.database.svc.cluster.local`, adjusted
   to the cluster's DNS domain. Use the writable primary endpoint. `localhost`
   would point at the Taxonomy Pod; no NodePort, public LoadBalancer, or HTTP Ingress
   is needed. Enable TLS on PostgreSQL, provide a server certificate matching the
   chosen Service DNS name, and mount the public CA certificate into Taxonomy.
   The wizard's TLS option does not configure the database server. See
   [Service DNS](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/)
   and [pgJDBC certificate verification](https://jdbc.postgresql.org/documentation/ssl/).
4. **Supply Taxonomy's configuration.** In the Taxonomy namespace, populate the
   Secret referenced by `existingSecret` with `SPRING_DATASOURCE_URL`,
   `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. For example, the
   URL can be `jdbc:postgresql://postgres:5432/taxonomy?sslmode=verify-full&sslrootcert=/etc/taxonomy-db-ca/ca.crt`.
   Select `database.type=postgres`; leave `database.url` and `database.username`
   blank to use those Secret keys, or deliberately override them in values.
   Retain the configured login mode and its required Secret keys, such as
   `ADMIN_PASSWORD` for local login or `KEYCLOAK_CLIENT_SECRET` for Keycloak.
   Selecting the database must not remove authentication or deployment profiles.
5. **Allow the connection in both directions.** Any applicable NetworkPolicies
   must allow Taxonomy egress to the database and database ingress from Taxonomy
   on TCP 5432. Allow Taxonomy to reach the cluster DNS service as well. Match the
   actual namespace and Pod labels, and retain other required rules and mounts
   when combining values files. See [Kubernetes NetworkPolicies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
   and the [chart examples](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/DATABASE_PODS.md#netzwerk-und-dauerhafte-daten).
6. **Test, deploy, and verify persistence.** Run the assistant's connection test
   from the running Taxonomy server, then explicitly apply the reviewed deployment
   configuration with `TAXONOMY_DDL_AUTO=validate`. On the next application start,
   the released JGit Core and Taxonomy Flyway streams initialize a fresh PostgreSQL
   schema; the connection test does not do this. Verify actual startup, login, and
   application behavior. Preserve the database PVC independently of Taxonomy's
   `/app/data` PVC and establish tested backups and restore. One database Pod alone
   does not provide high availability.

---

## HSQLDB (Default)

The application ships with an embedded HSQLDB database. No installation or external database server is required.

**Key characteristics:**
- Runs **in-process** (same JVM, no network hop)
- Uses a bounded HikariCP pool (`minimum-idle=1`, `maximum-pool-size=8`)
- Defaults to an in-memory URL for zero-configuration development and tests
- Supports a file URL for persistent deployments; the pool keeps a connection open so `shutdown=true` cannot close HSQLDB during Spring startup
- Reuses an existing persisted catalogue on restart; `TAXONOMY_INIT_RELOAD_EXISTING=true` performs an intentional destructive reload

The in-memory default is for development and tests. The small controlled production Compose baseline uses file-backed HSQLDB and filesystem Lucene; multi-user external production uses PostgreSQL.

For an upgrade from 1.3, stop all application writers and verify a complete
[backup and restore](CONTAINER_IMAGE.md#5-persistence-and-backup) before starting the
new version. With the HSQLDB profile and schema migration enabled, startup prepares
the existing repository and portfolio rows before Hibernate adds the remaining
schema. Repository/workspace/branch identities are derived from recorded
provenance; existing completed identities are preserved. Ambiguous repositories,
colliding portfolio keys within the same tenant or inconsistent parent references stop the
upgrade. Restore the stopped-writer backup before resolving the reported data
conflict and retrying. Do not use `create` or disable schema migration to get past
an upgrade failure.

Historical central portfolios remain available by selecting the central view
(API clients send an explicitly empty `X-Taxonomy-Workspace-Id`). Their GET/HEAD
read routes keep the exact repository and branch identity. New analyses and
changes still require an isolated workspace; selecting a workspace does not move
the old central records into it.

---

## PostgreSQL

### Prerequisites

- PostgreSQL 14 or later (including Amazon RDS, Azure Database for PostgreSQL, Google Cloud SQL)
- Docker (for Quick Start) **or** an existing PostgreSQL instance

### Quick Start with Docker Compose

```bash
docker compose -f docker-compose-postgres.yml up
# Open http://localhost:8080
```

This starts a PostgreSQL 16 Alpine container alongside the Taxonomy application. Data is persisted in a Docker volume (`postgres-data`).
This is a **disposable evaluation example**: its Compose file sets
`TAXONOMY_DDL_AUTO=create`, so restarting the application recreates
Hibernate-managed tables and can discard data despite the persistent volume.
Use `production,postgres` with `TAXONOMY_DDL_AUTO=validate`, backed-up
PostgreSQL and the released migrations for retained production data.

```bash
# Tear down and remove data
docker compose -f docker-compose-postgres.yml down -v
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `postgres` |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:postgresql://localhost:5432/taxonomy` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | No | `taxonomy` | PostgreSQL login |
| `SPRING_DATASOURCE_PASSWORD` | No | `taxonomy` | PostgreSQL password |
| `TAXONOMY_DDL_AUTO` | No | base `create`; production `update`; Kubernetes `validate` | Use `validate` with released PostgreSQL migrations; never use `create` on persistent data. |

### Example: Connecting to an Existing Server

```bash
export SPRING_PROFILES_ACTIVE=production,postgres
export TAXONOMY_DATASOURCE_URL="jdbc:postgresql://myserver.example.com:5432/taxonomy"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD="$DATABASE_PASSWORD"
export TAXONOMY_ADMIN_PASSWORD="$TAXONOMY_ADMIN_PASSWORD"
export TAXONOMY_DDL_AUTO=validate

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Character Encoding

PostgreSQL uses UTF-8 by default, so all string fields are stored correctly without special annotations. Hibernate's `@Nationalized` annotation has no effect on PostgreSQL.

### Troubleshooting

| Problem | Symptom | Fix |
|---|---|---|
| **Connection refused** | `Connection to localhost:5432 refused` | Verify PostgreSQL is running: `pg_isready -h localhost -p 5432` |
| **Auth failed** | `FATAL: password authentication failed` | Check `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` |
| **DB missing** | `FATAL: database "taxonomy" does not exist` | Create it: `CREATE DATABASE taxonomy OWNER taxonomy;` |

### Running Integration Tests

```bash
./mvnw -B verify -Pdatabase-postgres
```

---

## Microsoft SQL Server (MSSQL)

### Prerequisites

- SQL Server 2019 or later (including Azure SQL Database)
- Docker (for Quick Start) **or** an existing SQL Server instance

### Quick Start with Docker Compose (compatibility test only)

```bash
docker compose -f docker-compose-mssql.yml up
# Open http://localhost:8080
```

This starts a SQL Server 2022 Developer Edition container alongside the Taxonomy application. Data is persisted in a Docker volume (`mssql-data`).
The application fixture uses `TAXONOMY_DDL_AUTO=create`: the volume alone does not
protect Hibernate-managed tables across application restarts. Use only a disposable
target; read [schema initialization](#schema-initialization) before retaining data.
Developer edition does not provide production use rights.

```bash
# Tear down and remove data
docker compose -f docker-compose-mssql.yml down -v
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `mssql` |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:sqlserver://localhost:1433;databaseName=taxonomy;encrypt=false;trustServerCertificate=true` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | No | `sa` | SQL Server login |
| `SPRING_DATASOURCE_PASSWORD` | **Yes** | *(empty)* | SQL Server password (must meet complexity requirements) |
| `TAXONOMY_DDL_AUTO` | No | base `create`; production `update`; Kubernetes `validate` | Compatibility testing only; this profile does not have the released PostgreSQL Flyway migration path. Never use `create` on retained data. |

### Example: Connecting to an Existing Server

```bash
export SPRING_PROFILES_ACTIVE=mssql
export TAXONOMY_DATASOURCE_URL="jdbc:sqlserver://myserver.example.com:1433;databaseName=taxonomy;encrypt=true;trustServerCertificate=false"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=validate

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Character Encoding — Why `nvarchar`?

All string fields use Hibernate's `@Nationalized` annotation, which maps to `nvarchar` (Unicode) columns on SQL Server. Large text fields use `@Lob` + `@Nationalized`, producing `nvarchar(max)`.

### SQL Server Password Requirements

The password must be at least 8 characters and contain characters from at least three of: uppercase, lowercase, digits, symbols. The Docker Compose example uses `A_Str0ng_Required_Password`.

### Troubleshooting

| Problem | Symptom | Fix |
|---|---|---|
| **TLS error** | `Could not establish secure connection` | Configure a trusted CA/server certificate and matching hostname; do not disable certificate validation for production. |
| **Login timeout** | `Login failed` or timeout | The profile sets 60s retry; increase if needed |
| **Deprecated ntext** | `ntext` columns in schema | Verify Hibernate 7.x + `SQLServerDialect` are in use |

### Running Integration Tests

```bash
./mvnw -B verify -Pdatabase-mssql
```

---

## Oracle Database

### Prerequisites

- Oracle Database 23c Free, or Oracle Database 19c+ (including Oracle Cloud Autonomous Database)
- Docker (for Quick Start) **or** an existing Oracle instance

### Quick Start with Docker Compose (compatibility test only)

```bash
docker compose -f docker-compose-oracle.yml up
# Open http://localhost:8080
```

This starts an Oracle Database 23c Free container alongside the Taxonomy application. Data is persisted in a Docker volume (`oracle-data`).
The image creates the `taxonomy` PDB with `ORACLE_DATABASE` and the application user
with `APP_USER`/`APP_USER_PASSWORD`; `ORACLE_PASSWORD` alone only sets the administrative
passwords. These initialization variables do not modify an existing data volume.
The application fixture still uses `TAXONOMY_DDL_AUTO=create`, so it is disposable
despite that volume. See [schema initialization](#schema-initialization) before
retaining data and [image-specific setup](https://github.com/gvenzl/oci-oracle-free).

> **Note:** The Oracle container may take 1–2 minutes to start on the first run while it initialises the database. The `gvenzl/oracle-free:23-slim-faststart` image is optimised for fast startup.

```bash
# Tear down and remove data
docker compose -f docker-compose-oracle.yml down -v
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `oracle` |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:oracle:thin:@localhost:1521/taxonomy` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | No | `taxonomy` | Oracle login |
| `SPRING_DATASOURCE_PASSWORD` | No | `taxonomy` | Oracle password |
| `TAXONOMY_DDL_AUTO` | No | base `create`; production `update`; Kubernetes `validate` | Compatibility testing only; this profile does not have the released PostgreSQL Flyway migration path. Never use `create` on retained data. |

### Example: Connecting to an Existing Server

```bash
export SPRING_PROFILES_ACTIVE=oracle
export TAXONOMY_DATASOURCE_URL="jdbc:oracle:thin:@myserver.example.com:1521/ORCLPDB1"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=validate

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Character Encoding

Oracle 23c uses **AL32UTF8** by default. Hibernate's `@Nationalized` maps to `NVARCHAR2` / `NCLOB`, ensuring correct Unicode storage regardless of the database character set.

### Oracle Reserved Words

Oracle reserves certain SQL keywords (e.g. `LEVEL`, `COMMENT`, `USER`). The application maps conflicting field names to safe column names via `@Column(name = "...")` — for example, `TaxonomyNode.level` → column `node_level`.

If you add new entity fields, check [Oracle Reserved Words](https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Oracle-SQL-Reserved-Words.html).

### Troubleshooting

| Problem | Symptom | Fix |
|---|---|---|
| **Connection refused** | `IO Error: The Network Adapter could not establish the connection` | Verify Oracle is running and listener is active |
| **Auth failed** | `ORA-01017: invalid username/password` | Check credentials match Oracle configuration |
| **Service not found** | `ORA-12514: listener does not currently know of service` | Ensure JDBC URL uses correct service name (not SID) |
| **Slow startup** | Container takes >2 minutes | The `faststart` image is used; increase healthcheck retries if needed |

### Service Name vs SID

The default URL uses a **service name**: `jdbc:oracle:thin:@localhost:1521/taxonomy`

For Oracle instances using a **SID**, use: `jdbc:oracle:thin:@localhost:1521:ORCL`

### Running Integration Tests

```bash
./mvnw -B verify -Pdatabase-oracle
```

---

## Adding a New Database

To add support for a new database backend:

1. **Create a Spring Profile** — Add an `application-{dbname}.properties` file under `src/main/resources/` with the JDBC driver, dialect, and connection pool settings.
2. **Add a Docker Compose file** — Create `docker-compose-{dbname}.yml` with the database container and application service.
3. **Test entity mappings** — Run the full test suite against the new database using Testcontainers to verify that `@Nationalized`, `@Lob`, and custom column mappings work correctly.
4. **Check reserved words** — Verify that all `@Column(name = "...")` mappings avoid reserved words in the target database.
5. **Add an integration test** — Create a `*{Dbname}*IT.java` test class with the appropriate Testcontainers setup.
6. **Update this documentation** — Add a new section to this file with Quick Start, environment variables, troubleshooting, and test instructions.

The entity model alone does not qualify a new backend: storage-library schema migrations, application migrations, persistence, upgrade and restore must also pass on that database.

---

## Common Configuration

### Schema Strategy (`TAXONOMY_DDL_AUTO`)

| Value | Behavior | Recommended For |
|---|---|---|
| `create` | Drops and recreates Hibernate-managed tables on every start | Disposable development/tests only; never retained data |
| `update` | Hibernate attempts additive changes; it is not a versioned migration | File-backed HSQLDB Compose baseline; not the PostgreSQL managed-migration contract |
| `validate` | Verifies schema matches entity model; fails on mismatch | Production with managed migrations |

> **PostgreSQL:** JGit Core and Taxonomy application Flyway migrations are already released and run at startup. Use `TAXONOMY_DDL_AUTO=validate` after a verified backup. The generic `production` profile still defaults to `update` for the file-backed HSQLDB baseline; override it explicitly for PostgreSQL. SQL Server and Oracle disable this Flyway path pending qualification.

### HikariCP Connection Pool

All external database profiles (PostgreSQL, MSSQL, Oracle) configure HikariCP with the same defaults:

| Property | Value | Description |
|---|---|---|
| `maximum-pool-size` | 10 | Max simultaneous connections |
| `minimum-idle` | 2 | Minimum idle connections |
| `connection-timeout` | 30000 ms | Max wait for a connection |
| `idle-timeout` | 600000 ms | Max idle time before retirement |
| `initialization-fail-timeout` | 60000 ms | Retry window for initial connection |

Override via environment variables:
```bash
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
```

> **Note:** The HSQLDB profile uses a small bounded HikariCP pool (`minimum-idle=1`, `maximum-pool-size=8`). The idle connection is required for a stable file-database lifecycle when `shutdown=true` is used.

---

## Moving from HSQLDB to PostgreSQL

Selecting `postgres` points the application at a **different database**; it
never copies users, workspaces, relations, hypotheses, Git packs/refs or other
records from HSQLDB. The project does not ship an automatic cross-database
transfer procedure. Keep a tested backup of the complete source database and
its associated index/configuration before considering a transfer. Qualify any
conversion on an isolated copy, verify row and Git-history integrity, login,
workspaces and representative search, and plan a controlled cutover. Do not
set `TAXONOMY_DDL_AUTO=create` on either retained database.

For a **fresh empty** PostgreSQL installation, provide database and local-login
credentials and `SPRING_PROFILES_ACTIVE=production,postgres`, set
`TAXONOMY_DDL_AUTO=validate`, and allow the released JGit Core and Taxonomy
application Flyway streams to create the schema. Back up before subsequent
upgrades; the safe Helm strategy is `Recreate`, and rolling compatibility is
release-specific. See [JGit storage](JGIT_STORAGE_HIBERNATE.md) and the
[Helm upgrade contract](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/README.md#upgrade-safety-and-database-migrations).

SQL Server and Oracle Compose files remain evaluation fixtures, not a
production migration destination or a substitute for a verified data transfer.

---

## Related Documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md) — All environment variables
- [Deployment Guide](DEPLOYMENT_GUIDE.md) — Docker and cloud deployment
- [Architecture](ARCHITECTURE.md) — Database architecture and entity model

## Test architecture and compatibility evidence

`./mvnw verify` is the bounded deterministic lifecycle and does not start external
databases. Core HSQLDB container tests and the PostgreSQL/MSSQL/Oracle matrix are
ordinary Failsafe/Testcontainers tests enabled with `-DskipITs=false`.

The `Database Compatibility` workflow runs PostgreSQL diagnostics and Selenium
on relevant pull requests, all six database scenarios weekly, and a selected
family on manual dispatch. Every command is documented in
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).

### Hypothesis branch authority when upgrading to 1.5.1

V43 (PostgreSQL) and the portable schema contract require an explicit branch for
all relation hypotheses. Existing nonblank branches remain authoritative on
restart. A legacy row without a branch is backfilled only through its recorded
analysis snapshot or project, with matching repository, workspace and canonical
portfolio scope. An overlapping analysis-session string, current workspace
branch, repository default, or review status is never sufficient evidence.
Evidence rows inherit the validated parent's exact repository/workspace/branch
and retain a composite foreign key to that parent. A workspace can read the
central baseline only on the selected branch; mutations remain workspace-exact.

If provenance is absent, dangling or contradictory, startup stops without
removing hypotheses or evidence. The error contains bounded reason counts and at
most 20 numeric row IDs; those IDs refer to hypotheses unless the reason names
evidence. To remediate:

1. Keep all application writers stopped and preserve a verified full backup.
2. Inspect the reported IDs offline. Recover the recorded snapshot/project
   links and canonical repository/workspace/branch identity from trustworthy
   historical records or a backup. Repair only links and identities that can be
   proven; never assign all rows to `draft` or the current workspace branch.
3. Correct conflicting populated evidence authority to its proven parent only
   after verifying the historical link. Rerun startup; the migration reports the
   next bounded set if further conflicts remain.
4. If the original authority cannot be recovered, export/archive/removal of the
   affected hypotheses **and their dependent evidence** requires an explicit
   operator decision and a retained backup. The migration never performs that
   deletion automatically. Do not bypass the migration or edit published V7.

The retained HSQLDB upgrade prepares this contract before Hibernate. The shipped
Oracle and SQL Server compatibility profiles disable Flyway, so their portable
initializer also runs before Hibernate; the normal runner repeats the idempotent
checks. Interrupted DDL can be resumed, including Oracle's independently committed
DDL phases. These profiles still require their existing native compatibility
lanes; local HSQLDB results do not qualify Oracle or SQL Server.

SQL Server stores the new branch authority as Unicode with binary `BIN2`
comparison so `Main`, `main` and non-ASCII Git refs remain distinct. Its exact
business key was already wider in declared capacity than the nonclustered index
limit before this change. SQL Server allows variable-width declarations above
that limit, but an actual combined key above **1,700 bytes** cannot be indexed.
The migration retains exact keys and fails rather than replacing authority with
a lossy or hashed identity. Verify the native lane and real key lengths before
using that compatibility profile. See Microsoft's [maximum capacity limits](https://learn.microsoft.com/en-us/sql/sql-server/maximum-capacity-specifications-for-sql-server?view=sql-server-ver17)
and [collation and Unicode support](https://learn.microsoft.com/en-us/sql/relational-databases/collations/collation-and-unicode-support/).
