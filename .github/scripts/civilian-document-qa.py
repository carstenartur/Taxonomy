#!/usr/bin/env python3
"""Independent LibreOffice/Poppler QA of real acceptance exports (no source edits)."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stderr} {result.stdout}")
    return result.stdout


def normalized(text):
    return re.sub(r"\W+", "", text).lower()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts", type=pathlib.Path)
    parser.add_argument("--soffice", default="soffice")
    args = parser.parse_args()
    root = args.artifacts.resolve()
    output = root / "document-qa"
    output.mkdir(exist_ok=True)
    (output / "quality.json").unlink(missing_ok=True)
    report = {"renderer": run(args.soffice, "--version").strip(), "documents": {}}
    decision = json.loads((root / "decision.json").read_text())
    architecture = json.loads((root / "architecture.json").read_text())
    for name in ("decision.docx", "architecture.vsdx"):
        pdf = output / (pathlib.Path(name).stem + ".pdf")
        with tempfile.TemporaryDirectory(prefix="civilian-lo-") as temporary:
            scratch = pathlib.Path(temporary)
            rendered = scratch / "output"
            rendered.mkdir()
            run(args.soffice, "-env:UserInstallation=" + (scratch / "profile").as_uri(),
                "--headless", "--convert-to", "pdf", "--outdir", str(rendered), str(root / name))
            converted = rendered / pdf.name
            assert converted.is_file(), f"LibreOffice did not produce {pdf.name}"
            shutil.copyfile(converted, pdf)
        text = run("pdftotext", "-raw", str(pdf), "-")
        xml = ET.fromstring(run("pdftotext", "-bbox", str(pdf), "-"))
        pages = xml.findall(".//{http://www.w3.org/1999/xhtml}page")
        assert pages, "No rendered pages"
        empty = []
        body_text = []
        for index, page in enumerate(pages, 1):
            # The report's running header is at 24pt and footer at 796pt; body
            # continuation may start at 51pt. Draw has no running page furniture.
            top, bottom = (45, 55) if name.endswith("docx") else (0, 0)
            body = [w.text for w in page if float(w.get("yMin")) >= top
                    and float(w.get("yMax")) < float(page.get("height")) - bottom]
            body_text.extend(t for t in body if t)
            if not any(t and t.strip() for t in body):
                empty.append(index)
        assert not empty, f"{name}: empty page bodies {empty}"
        if name.endswith("docx"):
            # Thirty complete decision chapters need space; detect a return to the
            # observed 87-page narrow-column report without shrinking its contents.
            assert len(pages) <= 65, f"Report grew to {len(pages)} pages"
            expected = [decision["requirement"], decision["metadata"]["analysisSnapshotId"]]
            for chapter in decision["chapters"]:
                expected.extend([chapter["parentCode"], chapter["decisionSummary"], chapter["comparativeRationale"]])
                expected.extend(child["reason"] for child in chapter["children"] if child.get("reason"))
        else:
            expected = [node["label"] for node in architecture["diagram"]["nodes"] if not node["container"]]
        # Remove repeated running headers/footers before matching paragraphs that
        # legitimately continue on the next page.
        if name.endswith("docx"):
            text = " ".join(body_text)
        for phrase in expected:
            assert normalized(phrase) in normalized(text), f"{name}: missing text {phrase[:120]}"
        for old in output.glob(pathlib.Path(name).stem + "-*.png"):
            old.unlink()
        run("pdftoppm", "-scale-to", "1400", "-png", str(pdf), str(output / pathlib.Path(name).stem))
        report["documents"][name] = {"pages": len(pages), "emptyBodyPages": empty,
                "contentAssertions": len(expected), "sourceSha256": hashlib.sha256((root / name).read_bytes()).hexdigest(),
                "renderedPdfSha256": hashlib.sha256(pdf.read_bytes()).hexdigest()}
    (output / "quality.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
