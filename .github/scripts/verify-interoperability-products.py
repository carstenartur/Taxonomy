#!/usr/bin/env python3
"""Pinned real-product smoke round trips, with commit-bound evidence; no proprietary service credentials."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "target/interoperability-products"
OUT.mkdir(parents=True, exist_ok=True)
ARCHI_SHA = "f9422455a00a22f5340dc28692ceafe0ad720c8cde839eaafb0fab1cea57287f"
report = {"schemaVersion": 1, "sourceCommit": os.environ.get("GITHUB_SHA"), "executedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "results": [], "status": "running"}

def run(*command):
    completed = subprocess.run([str(c) for c in command], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=300)
    with (OUT / "execution.log").open("a") as log:
        log.write("$ " + " ".join(str(c) for c in command) + "\n" + completed.stdout + "\n")
    if completed.returncode:
        raise RuntimeError(f"Product verification command failed ({completed.returncode}): {command[0]}")
    return completed.stdout

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

try:
    manifest_path = ROOT / "target/ui-application/manifest.json"
    manifest = json.loads(manifest_path.read_text())
    if manifest["sourceCommit"] != report["sourceCommit"] or manifest["sourceTree"] != run("git", "rev-parse", f"{report['sourceCommit']}^{{tree}}").strip():
        raise RuntimeError("Application artifact belongs to another source revision")
    name = manifest["jarName"]
    if Path(name).name != name or not name.endswith(".jar"):
        raise RuntimeError("Invalid application artifact name")
    jar = manifest_path.parent / name
    if digest(jar) != manifest["sha256"]:
        raise RuntimeError("Application artifact digest differs")
    report["applicationSha256"] = manifest["sha256"]
    runtime = OUT / "application"
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")):
                target = runtime / entry.filename
                if not target.resolve().is_relative_to(runtime.resolve()):
                    raise RuntimeError("Unsafe application artifact entry")
                archive.extract(entry, runtime)
    classes = OUT / "probe-classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join([str(classes), str(runtime / "BOOT-INF/classes"), str(runtime / "BOOT-INF/lib/*")])
    run("javac", "--release", "21", "-cp", classpath, "-d", classes, ROOT / ".github/scripts/InteroperabilityProductProbe.java")

    venv = OUT / "strictdoc-venv"
    run(sys.executable, "-m", "venv", venv)
    run(venv / "bin/python", "-m", "pip", "install", "--disable-pip-version-check", "strictdoc==0.29.0")
    (OUT / "strictdoc-dependencies.txt").write_text(run(venv / "bin/python", "-m", "pip", "freeze"))
    strictdoc = venv / "bin/strictdoc"
    fixture = ROOT / "taxonomy-export/src/test/resources/interoperability/strictdoc-product.sdoc"
    run(strictdoc, "export", "--formats=reqif-sdoc", "--reqif-enable-mid", "--reqif-multiline-is-xhtml", fixture, "--output-dir", OUT / "strictdoc-original", "--no-parallelization")
    original = OUT / "strictdoc-original/reqif/output.reqif"
    exported = OUT / "taxonomy.reqif"
    run("java", "-cp", classpath, "InteroperabilityProductProbe", "reqif", "export", original, exported)
    run(strictdoc, "convert", "--reqif-enable-mid", exported, OUT / "strictdoc-returned")
    run(strictdoc, "export", "--formats=reqif-sdoc", "--reqif-enable-mid", "--reqif-multiline-is-xhtml", OUT / "strictdoc-returned", "--output-dir", OUT / "strictdoc-final", "--no-parallelization")
    returned = OUT / "strictdoc-final/reqif/output.reqif"
    run("java", "-cp", classpath, "InteroperabilityProductProbe", "reqif", "compare", original, returned)
    report["results"].append({"product": "StrictDoc", "version": "0.29.0", "profile": "reqif-1.2/1", "direction": "product -> Taxonomy -> product -> Taxonomy", "fixture": fixture.name,
        "inputSha256": digest(original), "outputSha256": digest(returned), "result": "passed", "knownLosses": ["StrictDoc regenerates type, hierarchy and relation identifiers; stable requirement/specification MIDs, mapped fields and topology are compared."]})

    archive_path = OUT / "archi.tgz"
    url = "https://github.com/archimatetool/archi.io/releases/download/5_10_0/Archi-Linux64-5.10.0.tgz"
    with urllib.request.urlopen(url, timeout=60) as response, archive_path.open("wb") as output:
        shutil.copyfileobj(response, output)
    if digest(archive_path) != ARCHI_SHA:
        raise RuntimeError("Archi release digest differs from pinned upstream asset")
    archi_dir = OUT / "archi"
    with tarfile.open(archive_path) as archive:
        archive.extractall(archi_dir, filter="data")
    executables = [p for p in archi_dir.rglob("Archi") if p.is_file() and os.access(p, os.X_OK)]
    if len(executables) != 1:
        raise RuntimeError("Expected one Archi launcher")
    archi_fixture = ROOT / "taxonomy-export/src/test/resources/interoperability/archi-bendpoints.xml"
    archi_export = OUT / "taxonomy-archimate.xml"
    archi_returned = OUT / "archi-returned.xml"
    run("java", "-cp", classpath, "InteroperabilityProductProbe", "archimate", "export", archi_fixture, archi_export)
    run("xvfb-run", "-a", executables[0], "-nosplash", "-consoleLog", "-application", "com.archimatetool.commandline.app", "-data", OUT / "archi-workspace", "--abortOnException",
        "--xmlexchange.import", archi_export, "--xmlexchange.export", archi_returned)
    run("java", "-cp", classpath, "InteroperabilityProductProbe", "archimate", "compare", archi_fixture, archi_returned)
    report["results"].append({"product": "Archi", "version": "5.10.0", "releaseSha256": ARCHI_SHA, "profile": "archimate-3.1/1", "direction": "Archi fixture -> Taxonomy -> Archi -> Taxonomy", "fixture": archi_fixture.name,
        "inputSha256": digest(archi_fixture), "outputSha256": digest(archi_returned), "result": "passed", "knownLosses": ["This product fixture verifies identities, types, relation endpoints, view membership and geometry; it does not certify every Archi feature."]})
    report["status"] = "passed"
except Exception as error:
    report["status"] = "failed"
    report["failure"] = str(error)
    raise
finally:
    (OUT / "evidence.json").write_text(json.dumps(report, indent=2) + "\n")
