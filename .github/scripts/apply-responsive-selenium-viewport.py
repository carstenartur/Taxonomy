#!/usr/bin/env python3
"""Keep desktop Selenium acceptance tests on the desktop navigation contract."""

from pathlib import Path

FILES = [
    Path("taxonomy-app/src/test/java/com/taxonomy/CoreUiAcceptanceIT.java"),
    Path("taxonomy-app/src/test/java/com/taxonomy/ArchitectureWorkbenchUiIT.java"),
    Path("taxonomy-app/src/test/java/com/taxonomy/PortfolioAnalysisUiDiagnosticIT.java"),
    Path("taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java"),
]

IMPORT_MARKER = "import org.openqa.selenium.By;\n"
IMPORT = "import org.openqa.selenium.Dimension;\n"
DRIVER_MARKER = "        driver = browserSession.driver();\n"
VIEWPORT = "        driver.manage().window().setSize(new Dimension(1440, 1000));\n"

for path in FILES:
    text = path.read_text(encoding="utf-8")
    if IMPORT not in text:
        if IMPORT_MARKER not in text:
            raise SystemExit(f"Missing Selenium import marker in {path}")
        text = text.replace(IMPORT_MARKER, IMPORT_MARKER + IMPORT, 1)
    if VIEWPORT not in text:
        if text.count(DRIVER_MARKER) != 1:
            raise SystemExit(f"Expected one driver assignment in {path}")
        text = text.replace(DRIVER_MARKER, DRIVER_MARKER + VIEWPORT, 1)
    path.write_text(text, encoding="utf-8")

print("Configured four desktop Selenium acceptance tests with an explicit 1440x1000 viewport.")
