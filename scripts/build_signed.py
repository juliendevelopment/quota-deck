#!/usr/bin/env python3
"""Build using a private JSON signing config, never copying secrets into the checkout."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--config", type=Path, required=True)
args = parser.parse_args()
config = json.loads(args.config.read_text())
required = ["SIGNING_STORE_FILE", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD"]
if any(not config.get(key) for key in required):
    raise SystemExit("Incomplete signing config")
if not Path(config["SIGNING_STORE_FILE"]).is_file():
    raise SystemExit("Signing keystore not found")
root = Path(__file__).resolve().parents[1]
subprocess.run([str(root / "gradlew"), "testDebugUnitTest", "lintRelease", "assembleRelease", "--no-daemon", "--console=plain"], cwd=root, env=dict(os.environ, **{key: config[key] for key in required}), check=True)
