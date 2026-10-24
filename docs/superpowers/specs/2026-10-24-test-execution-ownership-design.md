# Test execution ownership

## Intent

Reduce the verified duplicate executions identified in the 24 October audit while retaining the complete supported verification scope. The user approved implementation and requested the JUnit discussion #4570 approach for inherited tests.

## Decisions

- Keep the existing fast UI-contract lane as the single CI owner of JavaScript contracts. The unsliced local Maven verification continues to execute them.
- Give the existing plugin lane sole ownership of its packaged integration tests in split CI; consume its original reports in the final evidence.
- Stop repeating upstream unit tests in the plugin and database companion profiles. Keep the lane-specific integration tests and build guards. Standalone profiles retain ordinary verification.
- Prepare the document browser runtime once without an extra unit-test pass, then execute the existing complete selected verification.
- Add an opt-in, test-scoped JUnit Platform PostDiscoveryFilter, registered through ServiceLoader, to the two concrete reformulation subclasses. Inherited methods are absent from discovery, not disabled. The original base tests and subclass-specific tests continue to execute.

The source for the last decision is https://github.com/junit-team/junit-framework/discussions/4570. The supported launcher API is preferable to runner internals or overriding every inherited method. Extracting a test-free superclass was considered; the requested opt-in filter keeps the existing fixture structure and makes suppression explicit.

## Constraints

No new workflows or test framework, no global inherited-test suppression, no weakened assertions, coverage floors or required statuses. Original reports and source identities remain verifiable. Changes affect test selection and ownership, not product behavior. Reuse existing regression owners, adding only a bounded real-launcher regression for discovery behavior.

## Acceptance

The two annotated subclasses contribute only their own two cases each, the base retains nine cases, and no inherited skip placeholders appear. JavaScript contracts appear once in final CI evidence. Packaged plugin cases run once in split CI and remain represented in the final report. Database and plugin integration selections and build guards still execute. Document runtime preparation does not run the unit suite again. All authoritative remote gates pass at the published PR head.
