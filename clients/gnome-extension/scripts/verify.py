#!/usr/bin/env python3
"""Headless verification with fresh XDG data and session buses. No personal accounts."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "build" / "reports"
REPORTS.mkdir(parents=True, exist_ok=True)
RESULTS = []


def run(label, command, env=None):
    print(f"Running {label}", flush=True)
    result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, timeout=180)
    log = REPORTS / f"{label}.log"
    log.write_text(result.stdout + result.stderr)
    RESULTS.append({"name": label, "command": command, "exitCode": result.returncode, "log": str(log.relative_to(ROOT))})
    (REPORTS / "verification.json").write_text(json.dumps(RESULTS, indent=2) + "\n")
    if result.returncode:
        print(result.stdout + result.stderr)
        raise SystemExit(result.returncode)
    summary = [line for line in result.stdout.splitlines() if line.startswith(("# tests", "# pass", "# fail", "Native ", "Real isolated", "Secret Service", "Archive:", "SHA-256:", "Shell smoke:"))]
    print("\n".join(summary) or f"PASS · {log}", flush=True)


def isolated(temp):
    env = dict(os.environ)
    base = Path(temp)
    for key, suffix in [("HOME", "home"), ("XDG_DATA_HOME", "data"), ("XDG_CONFIG_HOME", "config"),
                        ("XDG_CACHE_HOME", "cache"), ("XDG_STATE_HOME", "state"), ("XDG_RUNTIME_DIR", "runtime")]:
        path = base / suffix
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        env[key] = str(path)
    env.update(GIO_USE_VFS="local", GSETTINGS_BACKEND="memory", QUOTADECK_ISOLATED_TEST="1", GTK_A11Y="none", LIBGL_ALWAYS_SOFTWARE="1")
    env.pop("DBUS_SESSION_BUS_ADDRESS", None)
    return env


def keyring_worker():
    # Only a synthetic test password reaches an isolated ephemeral keyring.
    daemon = subprocess.run(["gnome-keyring-daemon", "--unlock", "--components=secrets"],
                            input="synthetic-test-keyring-password", text=True, capture_output=True, check=True)
    for line in daemon.stdout.splitlines():
        if line.startswith("GNOME_KEYRING_CONTROL="):
            os.environ["GNOME_KEYRING_CONTROL"] = line.split("=", 1)[1]
    return subprocess.run(["gjs", "-m", "tests/keyring.js"], cwd=ROOT).returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shell", action="store_true", help="also run GNOME 50 headless compositor smoke test")
    parser.add_argument("--keyring-worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.keyring_worker:
        if os.environ.get("QUOTADECK_ISOLATED_TEST") != "1":
            raise SystemExit("Refusing non-isolated test keyring")
        raise SystemExit(keyring_worker())
    run("node", ["npm", "test"])
    run("lint", ["npm", "run", "lint"])
    with tempfile.TemporaryDirectory(prefix="quotadeck-native-", dir=os.environ.get("TMPDIR")) as temp:
        env = isolated(temp)
        schema_dir = Path(temp) / "schemas"
        schema_dir.mkdir()
        run("schemas", ["glib-compile-schemas", "--strict", f"--targetdir={schema_dir}", "schemas"], env)
        env["GSETTINGS_SCHEMA_DIR"] = str(schema_dir)
        run("gjs", ["dbus-run-session", "--", "gjs", "-m", "tests/native.js"], env)
        run("preferences", ["xvfb-run", "-a", "dbus-run-session", "--", "gjs", "-m", "tests/preferences.js"], env)
        run("keyring", ["dbus-run-session", "--", sys.executable, str(Path(__file__).resolve()), "--keyring-worker"], env)
        # Existing isolated default collection is now locked on a new bus, with no login/unlock.
        run("keyring-unavailable", ["dbus-run-session", "--", "gjs", "-m", "tests/keyring.js", "--unavailable"], env)
        run("package", [sys.executable, "scripts/package.py"], env)
        def shell_command(archive):
            version = subprocess.check_output(["gnome-shell", "--version"], text=True).strip()
            (REPORTS / "shell-version.txt").write_text(version + "\n")
            if version.split()[-1].split(".")[0] == "49":
                # GNOME 49's test tool predates --extension and XDG isolation.
                # Install into OUR temporary XDG home and select it via keyfile settings.
                run("shell-install-49", ["gnome-extensions", "install", "--force", str(archive)], env)
                keyfile = Path(env["XDG_CONFIG_HOME"]) / "glib-2.0/settings/keyfile"
                keyfile.parent.mkdir(parents=True, exist_ok=True)
                keyfile.write_text("[org/gnome/shell]\nenabled-extensions=['quotadeck@juliendevelopment.github.io']\n"
                                   "disable-user-extensions=false\n[org/gnome/desktop/interface]\nenable-animations=false\n")
                env["GSETTINGS_BACKEND"] = "keyfile"
                return ["dbus-run-session", "--", "gnome-shell-test-tool", "--headless", str(ROOT / "tests/shell.js")]
            if version.split()[-1].split(".")[0] != "50":
                raise SystemExit("Shell smoke test supports GNOME 49 and 50 only")
            return ["dbus-run-session", "--", "gnome-shell-test-tool", "--headless", "--disable-animations",
                    "--extension", str(archive), str(ROOT / "tests/shell.js")]
        if args.shell:
            archive = ROOT / "dist/quotadeck@juliendevelopment.github.io.shell-extension.zip"
            run("shell", shell_command(archive), env)
    print(f"Verification complete: {REPORTS / 'verification.json'}")


if __name__ == "__main__":
    main()
