"""Local-only provisioning/control helper; never print credentials."""
import argparse
import json
import os
from pathlib import Path
import secrets
from uuid import uuid4
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["provision", "send", "status", "disconnect"])
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--host", help="override configured Hub address")
    parser.add_argument("--port", type=int, help="override configured Hub port")
    args = parser.parse_args()
    if args.action == "provision":
        if args.config.exists():
            raise SystemExit("configuration exists; refusing to overwrite credentials")
        device_file = args.config.parent / "android-hub-test.json"
        if device_file.exists():
            raise SystemExit("device provisioning file exists; refusing to overwrite")
        args.config.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        data = {"host": args.host or "192.168.2.109", "port": args.port or 8766, "hub_id": str(uuid4()), "device_token": secrets.token_urlsafe(32),
                "control_token": secrets.token_urlsafe(32)}
        with open(args.config, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as f:
            json.dump(data, f)
        device = {"url": f"ws://{data['host']}:{data['port']}/_test/device", "token": data["device_token"]}
        with open(device_file, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as f:
            json.dump(device, f)
        print("Created private link-test configuration")
        return
    data = json.loads(args.config.read_text())
    method = "GET" if args.action == "status" else "POST"
    host, port = args.host or data["host"], args.port or data["port"]
    request = Request(f"http://{host}:{port}/_test/{args.action}", method=method,
                      headers={"Authorization": "Bearer " + data["control_token"]})
    with urlopen(request, timeout=5) as response:
        print(response.read().decode())


if __name__ == "__main__":
    main()
