"""Provision an authorized debug APK over ADB stdin; do not expose tokens in argv/logs."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

PACKAGE = "com.pinepi.thinksmart.launcher"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    content = args.config.read_bytes()
    parsed = json.loads(content)
    if set(parsed) != {"url", "token"} or not 32 <= len(parsed["token"]) <= 128:
        raise SystemExit("invalid device configuration")
    command = "run-as " + PACKAGE + " sh -c " + shlex.quote(
        "umask 077; mkdir -p files; cat > files/hub-test.json"
    )
    result = subprocess.run([args.adb, "-s", args.serial, "shell", command],
                            input=content, capture_output=True, timeout=15)
    if result.returncode:
        raise SystemExit("ADB private configuration write failed")
    print("Provisioned debug connection in application-private files; credentials not printed")


if __name__ == "__main__":
    main()
