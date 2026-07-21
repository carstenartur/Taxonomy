#!/usr/bin/env python3
"""Apply the one-time Air-Gap browser asset migration on the QA hardening branch.

The script is deliberately idempotent so reruns cannot duplicate dependencies or tags.
"""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
POM = ROOT / "taxonomy-app" / "pom.xml"
INDEX = ROOT / "taxonomy-app" / "src" / "main" / "resources" / "templates" / "index.html"

DEPENDENCIES = """
        <!-- Version-pinned local browser assets for offline / air-gapped operation. -->
        <dependency>
            <groupId>org.webjars.npm</groupId>
            <artifactId>bootstrap</artifactId>
            <version>5.3.2</version>
        </dependency>
        <dependency>
            <groupId>org.webjars.npm</groupId>
            <artifactId>d3</artifactId>
            <version>7.9.0</version>
        </dependency>
        <dependency>
            <groupId>org.webjars.npm</groupId>
            <artifactId>jspdf</artifactId>
            <version>4.2.0</version>
        </dependency>
        <dependency>
            <groupId>org.webjars.npm</groupId>
            <artifactId>svg2pdf.js</artifactId>
            <version>2.2.0</version>
        </dependency>

"""


def patch_pom() -> None:
    text = POM.read_text(encoding="utf-8")
    if "Version-pinned local browser assets" in text:
        return
    anchor = "        <!-- Spring Security: form login, session, role-based access -->\n"
    if anchor not in text:
        raise SystemExit("Cannot locate dependency insertion anchor in taxonomy-app/pom.xml")
    POM.write_text(text.replace(anchor, DEPENDENCIES + anchor, 1), encoding="utf-8")


def replace_exact(text: str, old: str, new: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"Cannot locate expected template reference: {old}")
    return text.replace(old, new)


def patch_index() -> None:
    text = INDEX.read_text(encoding="utf-8")
    text = replace_exact(
        text,
        '<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css"/>',
        '<link rel="stylesheet" th:href="@{/webjars/bootstrap/5.3.2/dist/css/bootstrap.min.css}" href="/webjars/bootstrap/5.3.2/dist/css/bootstrap.min.css"/>',
    )
    text = replace_exact(
        text,
        '<link rel="stylesheet" th:href="@{/css/taxonomy.css}"/>',
        '<link rel="stylesheet" th:href="@{/css/taxonomy.css}"/>\n    <link rel="stylesheet" th:href="@{/css/taxonomy-ergonomics.css}"/>',
    )
    text = replace_exact(
        text,
        '<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>',
        '<script th:src="@{/webjars/bootstrap/5.3.2/dist/js/bootstrap.bundle.min.js}" src="/webjars/bootstrap/5.3.2/dist/js/bootstrap.bundle.min.js"></script>',
    )
    text = replace_exact(
        text,
        '<script src="https://cdn.jsdelivr.net/npm/d3@7"></script>',
        '<script th:src="@{/webjars/d3/7.9.0/dist/d3.min.js}" src="/webjars/d3/7.9.0/dist/d3.min.js"></script>',
    )
    text = replace_exact(
        text,
        '<script src="https://cdn.jsdelivr.net/npm/jspdf@4.2.0/dist/jspdf.umd.min.js"></script>',
        '<script th:src="@{/webjars/jspdf/4.2.0/dist/jspdf.umd.min.js}" src="/webjars/jspdf/4.2.0/dist/jspdf.umd.min.js"></script>',
    )
    text = replace_exact(
        text,
        '<script src="https://cdn.jsdelivr.net/npm/svg2pdf.js@2.2.4/dist/svg2pdf.umd.min.js"></script>',
        '<script th:src="@{/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js}" src="/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js"></script>',
    )
    INDEX.write_text(text, encoding="utf-8")


def main() -> None:
    patch_pom()
    patch_index()


if __name__ == "__main__":
    main()
