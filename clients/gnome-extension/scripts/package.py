#!/usr/bin/env python3
"""Build a deterministic installable archive using a strict runtime-file allowlist."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "org.gnome.shell.extensions.quotadeck.gschema.xml"


def build():
    metadata = json.loads((ROOT / "metadata.json").read_text())
    assert metadata["uuid"] == "quotadeck@juliendevelopment.github.io"
    assert metadata["shell-version"] == ["49", "50"]
    assert isinstance(metadata["version"], int) and metadata["version"] > 0
    assert re.fullmatch(r"\d+\.\d+\.\d+", metadata["version-name"])
    assert metadata["version-name"] == json.loads((ROOT / "package.json").read_text())["version"]
    files = {name: (ROOT / name).read_bytes() for name in
             ["metadata.json", "extension.js", "prefs.js", "stylesheet.css", f"schemas/{SCHEMA}"]}
    for directory in ["lib", "native", "ui"]:
        for path in sorted((ROOT / directory).glob("*.js")):
            assert path.is_file() and not path.is_symlink()
            files[path.relative_to(ROOT).as_posix()] = path.read_bytes()
    with tempfile.TemporaryDirectory(prefix="quotadeck-package-", dir=os.environ.get("TMPDIR")) as temp:
        subprocess.run(["glib-compile-schemas", "--strict", f"--targetdir={temp}", str(ROOT / "schemas")], check=True)
        files["schemas/gschemas.compiled"] = (Path(temp) / "gschemas.compiled").read_bytes()
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    archive = dist / f'{metadata["uuid"]}.shell-extension.zip'
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            out.writestr(info, data)
    with zipfile.ZipFile(archive) as packed:
        assert packed.testzip() is None
        assert set(packed.namelist()) == set(files)
        assert all(not name.startswith("/") and ".." not in Path(name).parts for name in packed.namelist())
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (dist / "SHA256SUMS.txt").write_text(f"{digest}  {archive.name}\n")
    print(f"Archive: {archive} ({archive.stat().st_size} bytes, {len(files)} files)")
    print(f"SHA-256: {digest}")
    return archive


if __name__ == "__main__":
    build()
