# Taxonomy Templates

The document-template library owns versioned template Git storage, OOXML safety
validation and package codecs, default-template bootstrap, materialization/cache,
WebDAV locks and projection, and template administration/health adapters.

This is an ordinary JAR, not another deployable application. It has no dependency
on `taxonomy-app` or another Taxonomy feature module. Maven Enforcer prohibits
an application back-dependency. Global security, configuration, migration scripts,
and presentation resources remain in the single Boot application.

The bundled default Word template is packaged once in this library under the
unchanged `document-templates/decision-rationale-report.dotx` classpath name.
Production and migrated test files retain their original packages and contents.
Application/security/HTTP tests remain in `taxonomy-app` and exercise this JAR.

Focused module validation from the repository root:

```sh
./mvnw -B -pl taxonomy-templates -am verify
```

Canonical application verification remains `./mvnw -B verify -Pci`; the focused
module build does not replace product, browser or database integration checks.
