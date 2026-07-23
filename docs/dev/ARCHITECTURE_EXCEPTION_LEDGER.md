# Architecture exception ledger

`.github/architecture-exceptions.json` is the machine-readable inventory of temporary dependency edges excluded from the strict ArchUnit cycle rule.

Every entry requires:

- a stable identifier;
- exception kind;
- exact origin and target package patterns;
- accountable owner;
- concrete rationale;
- objectively testable removal condition;
- expiry date.

The test suite fails when:

- the JSON is malformed or a required field is blank;
- identifiers are duplicated;
- an entry has expired;
- the ledger and the exceptions compiled into `ArchitectureCycleBoundaryTest` differ;
- a new cycle is introduced outside the documented package edges.

A synthetic alpha/beta cycle is included in the test sources and must be rejected by ArchUnit. This protects the test itself against accidental weakening.

## Exception review

Before extending an exception:

1. demonstrate the actual dependency path;
2. explain why a port or shared contract cannot be extracted in the same change;
3. assign an owner and a removal condition;
4. use the shortest practical expiry;
5. update both the ledger and the ArchUnit rule in one reviewed pull request.

The shared DTO, model, and shared-infrastructure packages are structural contracts rather than bounded contexts. They are excluded separately and must not be used as a route for domain-service dependencies.

The strict-rule adoption exposed seven cycles hidden by the former broad exclusions. Five additional directed edges are sufficient to explain them: one analysis use-case dependency, two Hibernate Search binder directions, and two HTTP-controller orchestration dependencies. Reverse service dependencies remain governed by the cycle rule, and all five adoption entries expire on 30 June 2027.
