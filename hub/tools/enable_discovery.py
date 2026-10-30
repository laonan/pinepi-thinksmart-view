"""Add a persistent Hub identity without exposing or replacing existing credentials."""
import argparse
import json
import os
from pathlib import Path
from uuid import UUID, uuid4


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.config.read_text())
    if "hub_id" in data:
        UUID(data["hub_id"])
        print("Persistent Hub identity already configured")
        return
    data["hub_id"] = str(uuid4())
    temporary = args.config.with_suffix(".discovery.tmp")
    with open(temporary, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as f:
        json.dump(data, f)
    os.replace(temporary, args.config)
    print("Persistent Hub identity configured")


if __name__ == "__main__":
    main()
