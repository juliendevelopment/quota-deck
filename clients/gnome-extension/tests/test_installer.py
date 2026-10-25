"""Offline installer and distributable-bundle regressions. No desktop/account changes."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("installer", ROOT / "scripts/install.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.archive = self.base / installer.ARCHIVE_NAME
        self.metadata = {"uuid": installer.UUID, "shell-version": ["49", "50"],
                         "version": 1, "version-name": "0.1.0"}
        self.files = {"extension.js": b"// synthetic source", "prefs.js": b"// synthetic prefs",
                      "schemas/gschemas.compiled": b"synthetic compiled schema",
                      "schemas/org.gnome.shell.extensions.quotadeck.gschema.xml": b"<schemalist/>"}
        self.pack()

    def pack(self, extra=None):
        files = {**self.files, "metadata.json": json.dumps(self.metadata).encode(), **(extra or {})}
        with zipfile.ZipFile(self.archive, "w") as packed:
            for name, data in files.items():
                packed.writestr(name, data)
        digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        (self.base / "SHA256SUMS.txt").write_text(f"{digest}  {self.archive.name}\n")

    def test_valid_package_checks_identity_and_source(self):
        metadata, files = installer.checked_package(self.archive)
        self.assertEqual(metadata, self.metadata)
        self.assertEqual(files["extension.js"], self.files["extension.js"])

    def test_corrupt_zip_is_rejected_by_checksum_before_install(self):
        with self.archive.open("ab") as out:
            out.write(b"tampered")
        with self.assertRaisesRegex(installer.InstallError, "checksum mismatch"):
            installer.checked_package(self.archive)

    def test_wrong_checksum_filename_is_not_accepted(self):
        (self.base / "SHA256SUMS.txt").write_text("a" * 64 + "  unrelated.zip\n")
        with self.assertRaisesRegex(installer.InstallError, "Expected one checksum"):
            installer.checked_package(self.archive)

    def test_duplicate_checksum_is_rejected(self):
        manifest = self.base / "SHA256SUMS.txt"
        manifest.write_text(manifest.read_text() * 2)
        with self.assertRaises(installer.InstallError):
            installer.checked_package(self.archive)

    def test_missing_checksum_has_no_fallback(self):
        (self.base / "SHA256SUMS.txt").unlink()
        with self.assertRaises(FileNotFoundError):
            installer.checked_package(self.archive)

    def test_wrong_extension_identity_is_rejected(self):
        self.metadata["uuid"] = "unrelated@example.com"
        self.pack()
        with self.assertRaisesRegex(installer.InstallError, "not a supported"):
            installer.checked_package(self.archive)

    def test_unknown_shell_support_is_not_silently_accepted(self):
        self.metadata["shell-version"].append("48")
        self.pack()
        with self.assertRaises(installer.InstallError):
            installer.checked_package(self.archive)

    def test_missing_runtime_file_is_rejected(self):
        del self.files["prefs.js"]
        self.pack()
        with self.assertRaisesRegex(installer.InstallError, "incomplete"):
            installer.checked_package(self.archive)

    def test_traversal_absolute_and_windows_paths_are_rejected(self):
        for name in ("../escape", "/absolute", "schemas/../../escape", "..\\escape"):
            with self.subTest(name=name):
                self.pack({name: b"bad"})
                with self.assertRaisesRegex(installer.InstallError, "Unsafe"):
                    installer.checked_package(self.archive)

    def test_symlink_archive_is_rejected(self):
        entry = zipfile.ZipInfo("unsafe-link")
        entry.create_system = 3
        entry.external_attr = 0o120777 << 16
        self.pack({entry: b"/unrelated"})
        with self.assertRaisesRegex(installer.InstallError, "Unsafe"):
            installer.checked_package(self.archive)

    def test_root_is_refused_before_any_system_command(self):
        with patch.object(installer.os, "geteuid", return_value=0), patch.object(installer, "run") as run:
            with self.assertRaisesRegex(installer.InstallError, "without sudo"):
                installer.install(self.archive)
            run.assert_not_called()

    def test_sudo_is_refused_even_with_nonroot_identity(self):
        with patch.object(installer.os, "geteuid", return_value=1000), patch.dict(os.environ, {"SUDO_USER": "tester"}):
            with self.assertRaisesRegex(installer.InstallError, "without sudo"):
                installer.install(self.archive)

    def fake_command(self, command):
        if command[:2] == ["gnome-shell", "--version"]:
            return "GNOME Shell 50.6"
        if command[:2] == ["gnome-extensions", "install"]:
            with zipfile.ZipFile(command[-1]) as archive:
                archive.extractall(self.target)
            # A runtime compiler may rewrite this one generated file.
            (self.target / "schemas/gschemas.compiled").write_bytes(b"recompiled")
        return ""

    def perform(self, command=None):
        self.target = self.base / "data/gnome-shell/extensions" / installer.UUID
        env = {"XDG_DATA_HOME": str(self.base / "data"), "SUDO_USER": ""}
        with patch.object(installer.os, "geteuid", return_value=1000), patch.dict(os.environ, env), \
                patch.object(installer.shutil, "which", return_value="/synthetic/bin"), \
                patch.object(installer, "run", side_effect=command or self.fake_command) as run:
            target = installer.install(self.archive)
            return target, [call.args[0] for call in run.call_args_list]

    def test_install_reads_back_exact_source_and_preserves_other_data(self):
        sentinel = self.base / "data/keyrings/unrelated"
        sentinel.parent.mkdir(parents=True)
        sentinel.write_text("synthetic sentinel, not a real secret")
        target, commands = self.perform()
        self.assertEqual(target, self.target)
        self.assertEqual((target / "extension.js").read_bytes(), self.files["extension.js"])
        self.assertTrue(sentinel.is_file())
        self.assertEqual([command[0] for command in commands], ["gnome-shell", "gjs", "gnome-extensions"])
        self.assertEqual(commands[-1][1:3], ["install", "--force"])

    def test_update_is_in_place_without_uninstall(self):
        self.perform()
        self.files["extension.js"] = b"// synthetic update"
        self.metadata["version"] = 2
        self.metadata["version-name"] = "0.1.1"
        self.pack()
        target, commands = self.perform()
        self.assertEqual((target / "extension.js").read_bytes(), b"// synthetic update")
        self.assertFalse(any("uninstall" in command for command in commands))

    def test_unsupported_desktop_is_refused_before_install(self):
        with self.assertRaisesRegex(installer.InstallError, "Detected: GNOME Shell 48"):
            self.perform(lambda command: "GNOME Shell 48" if command[0] == "gnome-shell" else "")

    def test_success_exit_without_actual_install_is_not_success(self):
        with self.assertRaises(FileNotFoundError):
            self.perform(lambda command: "GNOME Shell 50.6" if command[0] == "gnome-shell" else "")

    def test_readback_mismatch_is_reported(self):
        def altered(command):
            result = self.fake_command(command)
            if command[0] == "gnome-extensions":
                (self.target / "extension.js").write_text("not the expected source")
            return result
        with self.assertRaisesRegex(installer.InstallError, "Installed-file verification failed"):
            self.perform(altered)


class BundleTest(unittest.TestCase):
    def test_deterministic_bundle_contains_only_install_files(self):
        package_spec = importlib.util.spec_from_file_location("package", ROOT / "scripts/package.py")
        package = importlib.util.module_from_spec(package_spec)
        package_spec.loader.exec_module(package)
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            root = Path(directory)
            # Copy only the source files the package builder needs, not accounts/build outputs.
            for name in ("metadata.json", "package.json", "extension.js", "prefs.js", "stylesheet.css", "INSTALL.md"):
                shutil.copy(ROOT / name, root / name)
            for name in ("lib", "native", "ui", "schemas", "scripts"):
                shutil.copytree(ROOT / name, root / name, ignore=shutil.ignore_patterns("__pycache__"))
            with patch.object(package, "ROOT", root):
                package.build()
                bundle = root / "dist/QuotaDeck-GNOME-0.1.0-linux.tar.gz"
                self.assertTrue(bundle.is_file(), "A build must ship a ready-made, offline installation bundle")
                first = bundle.read_bytes()
                package.build()
                self.assertEqual(first, bundle.read_bytes())
            with tarfile.open(bundle) as packed:
                members = packed.getmembers()
                self.assertEqual({Path(item.name).name for item in members},
                                 {installer.ARCHIVE_NAME, "SHA256SUMS.txt", "INSTALL.md", "install.py"})
                self.assertTrue(all(item.isfile() and not item.issym() for item in members))
                self.assertTrue(all(item.name.startswith("QuotaDeck-GNOME-0.1.0/") for item in members))
                checksums = packed.extractfile("QuotaDeck-GNOME-0.1.0/SHA256SUMS.txt").read().decode()
                for name in (installer.ARCHIVE_NAME, "INSTALL.md", "install.py"):
                    data = packed.extractfile("QuotaDeck-GNOME-0.1.0/" + name).read()
                    self.assertIn(hashlib.sha256(data).hexdigest() + "  " + name, checksums)
            outer = (root / "dist/SHA256SUMS.txt").read_text()
            self.assertIn(hashlib.sha256(first).hexdigest() + "  " + bundle.name, outer)


if __name__ == "__main__":
    unittest.main()
