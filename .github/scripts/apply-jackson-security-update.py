#!/usr/bin/env python3
"""Pin the Spring Boot managed Jackson 2 BOM to the first patched release."""
from pathlib import Path

path = Path("pom.xml")
text = path.read_text(encoding="utf-8")
old = "        <java.version>21</java.version>\n        <djl.version>0.36.0</djl.version>"
new = (
    "        <java.version>21</java.version>\n"
    "        <!-- Security override: fixes CVE-2026-54515 in jackson-databind 2.21.4. -->\n"
    "        <jackson-bom.version>2.21.5</jackson-bom.version>\n"
    "        <djl.version>0.36.0</djl.version>"
)
if old not in text:
    if "<jackson-bom.version>2.21.5</jackson-bom.version>" in text:
        print("Jackson security override already applied")
        raise SystemExit(0)
    raise SystemExit("Expected root POM property block not found")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Pinned jackson-bom.version to 2.21.5")
