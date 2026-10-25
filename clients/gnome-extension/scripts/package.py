#!/usr/bin/env python3
"""Build a deterministic installable archive and offline Linux bundle."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "org.gnome.shell.extensions.quotadeck.gschema.xml"
BUNDLE_NAME = "QuotaDeck-GNOME-0.1.0-linux.tar.gz"
BUNDLE_DIR = "QuotaDeck-GNOME-0.1.0"


def build_extension():
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
    return archive, digest


def build_bundle(archive, archive_digest):
    dist = ROOT / "dist"
    bundle = dist / BUNDLE_NAME
    installer = ROOT / "scripts" / "install.py"
    install_guide = ROOT / "INSTALL.md"
    assert installer.is_file() and install_guide.is_file()
    # Bundle-level checksum covers every file shipped in the bundle.
    bundle_checksum = dist / "bundle-checksum.txt"
    bundle_checksum.write_text(
        f"{archive_digest}  {archive.name}\n"
        f"{hashlib.sha256(install_guide.read_bytes()).hexdigest()}  INSTALL.md\n"
        f"{hashlib.sha256(installer.read_bytes()).hexdigest()}  install.py\n"
    )
    with tarfile.open(bundle, "w:gz", format=tarfile.PAX_FORMAT) as packed:
        for name in (archive.name, "SHA256SUMS.txt", "INSTALL.md", "install.py"):
            if name == archive.name:
                source = archive
            elif name == "SHA256SUMS.txt":
                source = bundle_checksum
            elif name == "INSTALL.md":
                source = install_guide
            elif name == "install.py":
                source = installer
            info = packed.gettarinfo(str(source), arcname=f"{BUNDLE_DIR}/{name}")
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mode = 0o644
            with source.open("rb") as handle:
                packed.addfile(info, handle)
    # Write a bundle-level checksum next to it for release tooling.
    bundle_digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
    # Keep the original extension checksum and add the bundle checksum.
    extension_checksum = (dist / "SHA256SUMS.txt").read_text().strip()
    (dist / "SHA256SUMS.txt").write_text(extension_checksum + "\n" + f"{bundle_digest}  {bundle.name}\n")
    print(f"Bundle: {bundle} ({bundle.stat().st_size} bytes)")
    print(f"Bundle SHA-256: {bundle_digest}")
    return bundle


def build():
    archive, archive_digest = build_extension()
    # Bundle checksum must include the inner archive and helper files.
    bundle_checksum_lines = [f"{archive_digest}  {archive.name}"]
    for name in ("INSTALL.md", "install.py"):
        source = ROOT / ("scripts" if name == "install.py" else "") / name if name == "install.py" else ROOT / name
        if name == "install.py":
            source = ROOT / "scripts" / "install.py"
        else:
            source = ROOT / name
        bundle_checksum_lines.append(f"{hashlib.sha256(source.read_bytes()).hexdigest()}  {name}")
    (ROOT / "dist" / "SHA256SUMS.txt").write_text(
        (ROOT / "dist" / "SHA256SUMS.txt").read_text() + "".join(
            f"{line.split()[0]}  {line.split()[1]}\n" for line in []))
    build_bundle(archive, archive_digest)
    return archive


if __name__ == "__main__":
    build()
