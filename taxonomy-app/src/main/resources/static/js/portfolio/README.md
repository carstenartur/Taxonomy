# Portfolio browser modules

The files in this directory are production browser modules for the graphical project portfolio. They are not a test runner and must not contain a parallel acceptance framework.

Portfolio domain, persistence, REST, Git and report contracts are verified by JUnit 5. Real-browser portfolio acceptance is implemented in `PortfolioUiAcceptanceIT` and executed by Maven Failsafe with the repository's Selenium/Testcontainers infrastructure.

Portfolio-specific Node/Playwright workflow scripts are intentionally not permitted. `PortfolioTestArchitectureContractTest` enforces that boundary during the Maven build.
