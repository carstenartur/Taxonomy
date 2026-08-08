#!/usr/bin/env python3
"""Ensure PortfolioUiAcceptanceIT enters the desktop contract before login."""

from pathlib import Path

path = Path("taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java")
text = path.read_text(encoding="utf-8")
old = """        driver = browserSession.driver();
        driver.setFileDetector(new LocalFileDetector());
"""
new = """        driver = browserSession.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        driver.setFileDetector(new LocalFileDetector());
"""
if new in text:
    print("Portfolio login viewport already configured.")
    raise SystemExit(0)
if text.count(old) != 1:
    raise SystemExit(f"Expected one portfolio driver setup block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Configured the portfolio Selenium viewport before login.")
