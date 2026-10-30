"""Prompt on the Pi, never expose the device token in argv or stdout."""
import getpass
import json
import os
from pathlib import Path
from pinepi_hub.broadcast_mid import read_config


def main():
    path = Path.home() / '.config/pinepi-hub/broadcast-mid.json'
    if path.exists():
        raise SystemExit('Configuration exists; refusing to replace it automatically.')
    data = {'url': 'wss://hail.lonelyassistant.com/ws/', 'device': 'pinepi-home-hub',
            'token': getpass.getpass('Broadcast Mid device token (hidden): ').strip()}
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix('.tmp')
    try:
        with open(temporary, 'x', opener=lambda p, flags: os.open(p, flags, 0o600)) as stream:
            json.dump(data, stream)
        read_config(temporary)
        # Hard link refuses replacement even if another process creates the destination.
        os.link(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    print('Private Broadcast Mid configuration saved. Hub will reload it within 5 seconds.')


if __name__ == '__main__':
    main()
