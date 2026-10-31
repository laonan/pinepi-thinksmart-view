import argparse
import ipaddress
import json
from pathlib import Path

import uvicorn

from .app import MAX_FRAME_BYTES, create_app
from .link_test import LinkTest
from .discovery import HubDiscovery
from .broadcast_mid import BroadcastMid
from .notifications import NotificationStore
from .baidu_tts import BaiduSpeech


def main() -> None:
    parser = argparse.ArgumentParser(description="PinePi foundation / local-gateway test service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--diagnostics", action="store_true", help="enable synthetic transport probe")
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--link-test-config", type=Path)
    parser.add_argument("--discover", action="store_true", help="advertise trusted LAN device service via mDNS")
    parser.add_argument("--discovery-interface", help="optional interface name, e.g. wlan0")
    parser.add_argument("--broadcast-mid-config", type=Path)
    parser.add_argument("--notification-state", type=Path)
    parser.add_argument('--media-directory', type=Path, help='optional dedicated directory served at /media')
    parser.add_argument('--baidu-tts-config', type=Path, help='private Baidu api_key/secret_key JSON')
    parser.add_argument('--media-base-url', help='terminal-accessible /media/ URL for generated narration')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    if args.media_directory is not None and not args.media_directory.is_dir():
        parser.error('media directory does not exist')
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("TLS certificate and key must be supplied together")
    if args.tls_cert and (not args.tls_cert.is_file() or not args.tls_key.is_file()):
        parser.error("TLS certificate or key file does not exist")
    try:
        address = ipaddress.ip_address(args.host)
        networks = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
        if not address.is_loopback and not (args.discover and args.host == "0.0.0.0") and not any(address in n for n in networks):
            parser.error("bind address must be loopback or an explicit private LAN address")
        link = None
        if args.link_test_config:
            data = json.loads(args.link_test_config.read_text())
            link = LinkTest(data["device_token"], data["control_token"], trusted_lan=args.discover)
        if not address.is_loopback and link is None:
            parser.error("LAN binding requires the explicit authenticated link-test mode")
    except (ValueError, KeyError, OSError):
        parser.error("invalid bind address or link-test configuration")
    discovery = None
    if args.discover:
        if link is None or args.tls_cert:
            parser.error("discovery requires trusted LAN link-test mode without TLS")
        try:
            discovery = HubDiscovery(data["hub_id"], args.port, args.discovery_interface, args.host)
        except (ValueError, KeyError):
            parser.error("discovery requires a persistent UUID hub_id in private configuration")
    upstream = None
    if link is not None:
        try:
            state_path = args.notification_state or args.link_test_config.with_name('notifications.json')
            link.notifications = NotificationStore(state_path)
        except (OSError, ValueError, TypeError):
            parser.error('invalid notification state; refusing to discard it')
    if args.broadcast_mid_config:
        if link is None or args.notification_state is None:
            parser.error("Broadcast Mid requires a terminal link and notification state path")
        upstream = BroadcastMid(args.broadcast_mid_config, link.publish_notification)
        link.upstream_status = upstream.status
    if args.baidu_tts_config is not None:
        if link is None or args.media_directory is None or not args.media_base_url:
            parser.error('Baidu TTS requires a message link, media directory and media base URL')
        try:
            link.speech = BaiduSpeech(args.baidu_tts_config, args.media_directory, args.media_base_url)
        except ValueError:
            parser.error('invalid Baidu media base URL')
    uvicorn.run(
        create_app(diagnostics=args.diagnostics, link_test=link, discovery=discovery, broadcast_mid=upstream,
                   media_directory=args.media_directory),
        host=args.host,
        port=args.port,
        ws="websockets",
        ws_max_size=MAX_FRAME_BYTES,
        ws_ping_interval=20,
        ws_ping_timeout=20,
        timeout_graceful_shutdown=5,
        access_log=False,
        proxy_headers=False,
        ssl_certfile=str(args.tls_cert) if args.tls_cert else None,
        ssl_keyfile=str(args.tls_key) if args.tls_key else None,
    )


if __name__ == "__main__":
    main()

