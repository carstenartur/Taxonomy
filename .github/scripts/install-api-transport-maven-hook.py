#!/usr/bin/env python3
"""Run the canonical API transport contract from the Maven-owned frontend lifecycle."""

from __future__ import annotations

import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
package_path = root / ".github/package.json"
pom_path = root / "taxonomy-build/pom.xml"

package = json.loads(package_path.read_text(encoding="utf-8"))
scripts = package.setdefault("scripts", {})
scripts["test:api-transport"] = "node scripts/test-taxonomy-api-client.mjs"
package_path.write_text(
    json.dumps(package, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)

pom = pom_path.read_text(encoding="utf-8")
execution = """                    <execution>
                        <id>run-canonical-api-transport-contract</id>
                        <phase>integration-test</phase>
                        <goals><goal>npm</goal></goals>
                        <configuration>
                            <skip>${taxonomy.ui.skip}</skip>
                            <arguments>run test:api-transport</arguments>
                        </configuration>
                    </execution>
"""
marker = """                    <execution>
                        <id>run-maven-owned-browser-verification</id>
"""
if execution not in pom:
    if pom.count(marker) != 1:
        raise SystemExit(
            f"Expected one frontend verification insertion marker, found {pom.count(marker)}"
        )
    pom = pom.replace(marker, execution + marker, 1)
pom_path.write_text(pom, encoding="utf-8")

print("Registered the API transport contract in the Maven-owned frontend lifecycle.")
