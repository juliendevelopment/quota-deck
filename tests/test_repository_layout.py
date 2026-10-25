"""Portable monorepo/build-path checks. No Android SDK or signing secrets required."""
import os
from pathlib import Path
import re
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "clients" / "android"


class RepositoryLayoutTest(unittest.TestCase):
    def test_both_clients_have_their_own_documentation(self):
        for name in ("android", "gnome-extension"):
            self.assertTrue((ROOT / "clients" / name / "README.md").is_file(), name)
        self.assertTrue((ROOT / "shared" / "README.md").is_file())
        self.assertTrue((ROOT / "docs" / "architecture.md").is_file())

    def test_android_build_is_self_contained(self):
        paths = ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                 "gradle/wrapper/gradle-wrapper.properties", "settings.gradle.kts",
                 "build.gradle.kts", "gradle.properties", ".tool-versions",
                 "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
                 "scripts/build_signed.py", "RELEASE_NOTES.md"]
        for name in paths:
            self.assertTrue((ANDROID / name).is_file(), name)
        self.assertTrue(os.access(ANDROID / "gradlew", os.X_OK))
        for name in ("app", "gradle", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
            self.assertFalse((ROOT / name).exists(), name)

    def test_android_identity_and_upgrade_boundary_are_preserved(self):
        path = ANDROID / "app" / "build.gradle.kts"
        self.assertTrue(path.is_file())
        build = path.read_text()
        self.assertIn('applicationId = "be.julien.quotadeck"', build)
        self.assertIn('namespace = "be.julien.quotadeck"', build)
        self.assertIn("minSdk = 26", build)
        self.assertNotIn("clients.gnome", build)

    def test_ci_runs_android_in_the_client_directory(self):
        workflow = (ROOT / ".github" / "workflows" / "build.yml").read_text()
        self.assertRegex(workflow, r"defaults:\s+run:\s+working-directory: clients/android")
        self.assertIn("python3 -m unittest discover -s tests -v", workflow)
        for name in ("outputs/apk/debug/app-debug.apk", "reports/", "test-results/", "qa/"):
            self.assertIn("clients/android/app/build/" + name, workflow)
        self.assertNotRegex(workflow, r"(?m)^\s+path: app/")

    def test_release_paths_and_android_tag_namespace(self):
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text()
        self.assertRegex(workflow, r"defaults:\s+run:\s+working-directory: clients/android")
        self.assertIn("tags: ['v*']", workflow)
        self.assertIn("--notes-file RELEASE_NOTES.md", workflow)
        self.assertTrue((ANDROID / "RELEASE_NOTES.md").is_file())
        self.assertIn("apksigner", workflow)
        self.assertIn("SIGNING_STORE_PASSWORD", workflow)

    def test_signing_script_resolves_its_own_client_root(self):
        script = ANDROID / "scripts" / "build_signed.py"
        self.assertTrue(script.is_file())
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            temp = Path(directory)
            key = temp / "fixture.jks"
            key.write_text("not a keystore; subprocess is mocked")
            config = temp / "fixture.json"
            import json
            config.write_text(json.dumps({"SIGNING_STORE_FILE": str(key),
                "SIGNING_STORE_PASSWORD": "fixture", "SIGNING_KEY_ALIAS": "fixture",
                "SIGNING_KEY_PASSWORD": "fixture"}))
            with patch.object(sys, "argv", [str(script), "--config", str(config)]), patch("subprocess.run") as run:
                runpy.run_path(str(script), run_name="__main__")
            args, kwargs = run.call_args
            self.assertEqual(str(ANDROID / "gradlew"), args[0][0])
            self.assertEqual(ANDROID, kwargs["cwd"])
            self.assertTrue(kwargs["check"])
            self.assertEqual(str(key), kwargs["env"]["SIGNING_STORE_FILE"])
            self.assertIn("testDebugUnitTest", args[0])
            self.assertIn("lintRelease", args[0])
            self.assertIn("assembleRelease", args[0])

    def test_documentation_relative_links_resolve(self):
        docs = [ROOT / "README.md", ROOT / "docs" / "architecture.md", ROOT / "shared" / "README.md",
                ANDROID / "README.md", ROOT / "clients" / "gnome-extension" / "README.md"]
        for path in docs:
            self.assertTrue(path.is_file(), str(path))
            text = path.read_text()
            targets = re.findall(r"\]\(([^)\s]+)\)|(?:src|href)=\"([^\"]+)\"", text)
            for markdown, html in targets:
                link = urlsplit(markdown or html)
                if link.scheme or link.netloc or not link.path:
                    continue
                resolved = (path.parent / unquote(link.path)).resolve()
                self.assertTrue(resolved.is_relative_to(ROOT), str(resolved))
                self.assertTrue(resolved.exists(), f"{path.relative_to(ROOT)} -> {link.path}")


if __name__ == "__main__":
    unittest.main()
