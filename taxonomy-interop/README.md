# Taxonomy Interoperability

Ordinary library for reviewed external-tool exchanges, connector orchestration,
durable identity mappings/checkpoints/events and OSLC application integration.

The production packages retain their names and file histories. The application
still has one Spring Boot deployment. Configuration and SQL migrations stay in
`taxonomy-app`; this library never depends back on the application.

`IntegrationPortfolioPort` owns the small set of required scoped portfolio
operations. Application composition binds it to the existing portfolio services;
authorization, project locks, requirement versions and projection logic retain
their existing authority. Neutral port values contain no portfolio implementation
types. Interoperability consumes workspace ports, not editor persistence.

Eight existing unit-test classes move here. Cross-context flow, journal, restart
and architecture-projection tests remain in the application and exercise this
library. The module participates in the normal reactor and aggregate coverage.

From the repository root:

```sh
./mvnw -B -pl taxonomy-interop -am test

# Repository-authoritative PR core verification; see .github/workflows/ci-cd.yml
./mvnw -B verify -Pci -DrunOnnxTests=true -Dtaxonomy.ui.skip=true
```
