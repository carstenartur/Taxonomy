# Portfolio browser modules

The files in this directory are production browser modules for the graphical project portfolio. They are not a test runner and must not contain a parallel acceptance framework.

Portfolio domain, persistence, REST, Git and report contracts are verified by JUnit 5. Real-browser portfolio acceptance is implemented in `PortfolioUiAcceptanceIT` and executed by Maven Failsafe with the repository's Selenium/Testcontainers infrastructure.

Focused client regressions also belong to JUnit/Failsafe: `PortfolioClientRoutingIT`, `PortfolioVersioningRecoveryIT`, `RequirementVersionReviewIT`, `PortfolioRequirementReviewIT` and `PortfolioMatrixReviewIT`. They render the actual Thymeleaf templates, load the production scripts, use the existing `BrowserSession`, and supply scoped HTTP responses for errors and delayed reads. They do not replace persistence, authorization or the full application workflow in `PortfolioUiAcceptanceIT`.

The full workflow keeps the packaged application and Selenium containers as its default. The existing `-Ptest-local` profile runs the same test methods against a real, isolated Spring application and the local browser adapter. It also verifies the root and an actual `/taxonomy` servlet context sequentially. No separate portfolio runner or CI selection is introduced.

Portfolio-specific Node/Playwright workflow scripts are intentionally not permitted. `PortfolioTestArchitectureContractTest` enforces that boundary during the Maven build.
