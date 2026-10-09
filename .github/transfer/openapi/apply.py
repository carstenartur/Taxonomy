import base64, gzip, hashlib, json, pathlib, subprocess
ROOT = pathlib.Path.cwd()
BASE = "a8b78e2cad64576ebbb5d1353c86652f985bd06e"
raw = gzip.decompress(base64.b64decode("".join(
    (pathlib.Path("/tmp/openapi-transfer") / ("part-" + str(i))).read_text()
    for i in range(11)).replace("\n", "").replace("\r", ""), validate=True))
if hashlib.sha256(raw).hexdigest() != "3258d587958270da8dfcc144908bb0bffa44fb777cc4a0099fb5d08ff3ab6758":
    raise SystemExit("Edit bundle digest mismatch")
bundle = json.loads(raw)
if bundle["base_commit"] != BASE or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() != BASE:
    raise SystemExit("Unexpected source revision")
def blob(value):
    return hashlib.sha1(("blob " + str(len(value)) + "\0").encode() + value).hexdigest()
prepared = {}
for name, change in bundle["files"].items():
    p = ROOT / name
    if not p.resolve().is_relative_to(ROOT) or p.is_symlink():
        raise SystemExit("Unsafe source path")
    if change["before"] is None:
        if p.exists(): raise SystemExit("New source already exists: " + name)
        source = b""
    else:
        source = p.read_bytes()
        if blob(source) != change["before"]:
            raise SystemExit("Source preimage changed: " + name)
    text = source.decode("utf-8")
    previous = len(text) + 1
    for start, end, replacement in reversed(change["edits"]):
        if not 0 <= start <= end <= len(text) or end > previous:
            raise SystemExit("Invalid edit ranges")
        text = text[:start] + replacement + text[end:]
        previous = start
    data = text.encode("utf-8")
    if blob(data) != change["after"]:
        raise SystemExit("Result digest mismatch: " + name)
    prepared[p] = data
for p, data in prepared.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
subprocess.run(["git", "add", "--", *bundle["files"]], check=True)
changed = set(subprocess.check_output(["git", "diff", "--cached", "--name-only"], text=True).splitlines())
if changed != set(bundle["files"]):
    raise SystemExit("Unexpected changed paths")
subprocess.run(["git", "diff", "--cached", "--check"], check=True)
print("Reproduced", len(prepared), "exact source files from pinned main")
