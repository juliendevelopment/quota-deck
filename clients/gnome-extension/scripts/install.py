#!/usr/bin/env python3
"""Install a local, checksummed GNOME package. No network, sudo or account access."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import zipfile

UUID = "quotadeck@juliendevelopment.github.io"
ARCHIVE_NAME = f"{UUID}.shell-extension.zip"
FEDORA_PACKAGES = "sudo dnf install gjs libsoup3 libsecret gtk4 libadwaita gnome-keyring python3"
RUNTIME_PROBE = """
imports.gi.versions.Soup = '3.0';
imports.gi.versions.Secret = '1';
imports.gi.versions.Gtk = '4.0';
imports.gi.versions.Adw = '1';
const {Soup, Secret, Gtk, Adw} = imports.gi;
if (!Soup.Session || !Secret.Service || !Gtk.Window || !Adw.PreferencesWindow)
    throw new Error('Required runtime library unavailable');
if (Adw.get_major_version() === 1 && Adw.get_minor_version() < 8)
    throw new Error('libadwaita 1.8 or newer is required');
"""


class InstallError(Exception):
    """An actionable failure; never install after a failed preflight."""


def run(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=60,
                            env={**os.environ, "LC_ALL": "C"})
    if result.returncode:
        raise InstallError(f"{command[0]} failed: {result.stderr.strip()[-1500:]}")
    return result.stdout.strip()


def checked_package(archive):
    """Validate a local checksum and archive before invoking the system installer."""
    manifest = archive.parent / "SHA256SUMS.txt"
    entries = []
    for line in manifest.read_text().splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *](.+)", line)
        if match and match[2] == archive.name:
            entries.append(match[1].lower())
    if len(entries) != 1:
        raise InstallError(f"Expected one checksum for {archive.name} in {manifest.name}.")
    if hashlib.sha256(archive.read_bytes()).hexdigest() != entries[0]:
        raise InstallError("ZIP checksum mismatch. Download/extract the complete bundle again.")
    with zipfile.ZipFile(archive) as packed:
        infos = packed.infolist()
        names = [entry.filename for entry in infos]
        if len(names) != len(set(names)) or sum(entry.file_size for entry in infos) > 8 * 1024 * 1024:
            raise InstallError("Invalid or unexpectedly large extension archive.")
        for entry in infos:
            path = PurePosixPath(entry.filename)
            if (path.is_absolute() or ".." in path.parts or "\\" in entry.filename
                    or stat.S_ISLNK(entry.external_attr >> 16)):
                raise InstallError("Unsafe extension archive entry.")
        metadata = json.loads(packed.read("metadata.json"))
        if (metadata.get("uuid") != UUID or metadata.get("shell-version") != ["49", "50"]
                or type(metadata.get("version")) is not int or metadata["version"] < 1
                or not re.fullmatch(r"\d+\.\d+\.\d+", metadata.get("version-name", ""))):
            raise InstallError("This is not a supported QuotaDeck GNOME package.")
        required = {"extension.js", "prefs.js", "schemas/gschemas.compiled",
                    "schemas/org.gnome.shell.extensions.quotadeck.gschema.xml"}
        if not required.issubset(names) or packed.testzip() is not None:
            raise InstallError("The extension archive is incomplete or corrupt.")
        files = {entry.filename: packed.read(entry.filename) for entry in infos if not entry.is_dir()}
    return metadata, files


def install(archive):
    if os.geteuid() == 0 or os.environ.get("SUDO_USER"):
        raise InstallError("Run this in your GNOME desktop terminal as yourself, without sudo.")
    metadata, files = checked_package(archive)
    for command in ("gnome-shell", "gnome-extensions", "gjs"):
        if shutil.which(command) is None:
            raise InstallError(f"Missing {command}. Run on a GNOME desktop. On Fedora: {FEDORA_PACKAGES}")
    version = run(["gnome-shell", "--version"])
    match = re.search(r"\bGNOME Shell (\d+)\b", version)
    if not match or match[1] not in metadata["shell-version"]:
        raise InstallError(f"Supported GNOME Shell versions: 49 and 50. Detected: {version}")
    try:
        run(["gjs", "-c", RUNTIME_PROBE])
    except InstallError as error:
        raise InstallError(f"Runtime dependencies unavailable (libadwaita >= 1.8 required). "
                           f"On Fedora: {FEDORA_PACKAGES}\n{error}") from error
    data = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    if not data.is_absolute():
        raise InstallError("XDG_DATA_HOME must be an absolute path.")
    target = data / "gnome-shell/extensions" / UUID
    print(f"Verified QuotaDeck GNOME {metadata['version-name']} for {version}.")
    run(["gnome-extensions", "install", "--force", str(archive)])
    # Confirm the exact installed source, not merely the CLI's exit code.
    # GLib may regenerate the compiled schema for this desktop's GLib version.
    for name, contents in files.items():
        if name != "schemas/gschemas.compiled" and (target / name).read_bytes() != contents:
            raise InstallError(f"Installed-file verification failed: {name}")
    print(f"Installed and read back: {target}")
    print("Accounts/keyring records were not read, removed or changed.")
    print("Log OUT of GNOME and log back IN (also after an update). Then run:")
    print(f"  gnome-extensions enable {UUID}")
    print(f"  gnome-extensions prefs {UUID}")
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", nargs="?", type=Path,
                        default=Path(__file__).resolve().parent / ARCHIVE_NAME,
                        help="local ZIP; SHA256SUMS.txt must be next to it (default: next to this installer)")
    args = parser.parse_args()
    try:
        install(args.archive.resolve())
    except (InstallError, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(f"Installation stopped: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
