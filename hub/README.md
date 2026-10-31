# PinePi Hub service

**English** | [中文版](README.zh-CN.md)

The Hub provides the foundation service, LAN message transport, Broadcast Mid subscription/forwarding, and v2 validation with durable forwarding. See **[Message protocol and usage](../docs/MESSAGE_PROTOCOL.md)** for complete JSON and all five variants. `POST /api/messages` uses the control token; the terminal supports text, images, audio, and video. Baidu short-text synthesis and bundled fixed announcements are connected in 0.8.1; the user confirmed all four prompts and one narration. Configure `--baidu-tts-config`, `--media-directory`, and `--media-base-url` as described in the protocol. STT, Agent integration, long-text segmentation, and scheduled news remain pending.

`--link-test-config` enables durable pending storage. Set its path with `--notification-state`; otherwise it defaults to `notifications.json` beside the link configuration. Optional `--media-directory` explicitly serves a dedicated media directory at `/media`. The older validation records below retain their original scope.

## Current deployment

Since the migration on 2026-10-30: `alan@192.168.31.36`, host `blue62a`, Raspberry Pi 4B / Debian 12 / aarch64. The isolated, uv-managed Python 3.13.18 leaves system Python 3.11 unchanged. Code resides at `/home/alan/pinepi-thinksmart-view/hub`; systemd runs it independently of SSH. Discovery uses `eth0`, which held `.36` and `.37` addresses advertising the same Hub identity. The user confirmed the new Hub, and a fixed message received its matching ACK. See [Migration record](../docs/HUB_MIGRATION.md).

Migration checks passed dependency integrity, LAN HTTP/WS, crash recovery, manual stop/start, and mDNS. The full migration suite recorded **13 passed / 1 failed**: synthetic HTTPS/WSS handshake timeout, once passing with diagnostic logging, cause unresolved. Do not reinterpret that historical round as successful TLS or whole-machine reboot acceptance.

Broadcast Mid uses public `wss://hail.lonelyassistant.com/ws/`, device `pinepi-home-hub`. Its separate token stays in private `~/.config/pinepi-hub/broadcast-mid.json` (0600). The subscriber runs inside the same service; real heartbeat, text notification, terminal display/storage, and matching ACK were verified in 0.7.2. That round passed 22 related/LAN tests; one previously failing synthetic HTTPS/WSS case was not included. Real public WSS certificate validation and notifications were checked independently. See [Broadcast Mid setup](../docs/BROADCAST_MID.md).

The initial Pi 5 / Debian 13 / Python 3.13.5 foundation built wheel/source distributions, passed dependency integrity and seven tests, and validated systemd enablement, crash restart, persistent manual stop, and manual start recovery. Local evidence: `reports/pytest.xml`, `reports/service-validation.json`; later message tests expanded to ten (`reports/pytest-link-test.xml`). A Starlette/httpx deprecation warning remains visible rather than suppressed.

## Service principles

The Hub must run as system `pinepi-hub.service`, user `alan`, with boot startup and crash recovery, independent of a Mac terminal or SSH session. Unit: `deploy/pinepi-hub.service`.

Without runtime configuration it binds `127.0.0.1:8766` and exposes only `GET /healthz`. Initial deployment on the old Pi used `/etc/pinepi-hub/runtime.env` to bind `0.0.0.0:8766`, enable LAN messaging/discovery, and advertise `192.168.2.109` on wlan0. Current deployment uses eth0. The agreed trusted LAN uses WS/HTTP without local certificates; public connections use WSS/HTTPS. Health only proves this process responds, not device connectivity or complete business readiness.

`--diagnostics` explicitly enables the temporary synthetic WebSocket echo endpoint `/_diagnostics/echo`. It is disabled in the persistent service. Echo capability does not prove notifications or display wake.

## Build and test

Target: Python 3.13 / aarch64 on Raspberry Pi. If Debian venv creation reports missing ensurepip, verify `python3.13-venv` package integrity; reinstalling that package fixed a recorded installed-but-missing-files case.

Initial development dependency resolution:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
```

Rebuild with the recorded versions:

```sh
.venv/bin/python -m pip install -r requirements.lock
.venv/bin/python -m build --no-isolation
.venv/bin/python -m pip install --no-deps --force-reinstall dist/pinepi_hub-0.1.0.dev0-py3-none-any.whl
.venv/bin/python -m pip check
.venv/bin/python -m pytest -q --junitxml=reports/pytest.xml
```

Real transport tests generate temporary certificates trusted only by their test clients. They cover HTTP/WS and HTTPS/WSS, concurrency isolation, reconnect, binary rejection, size limits, and rejection of untrusted certificates. Test processes/certificates are removed afterward; no certificate is installed on Android or production endpoint opened by these tests. Reference: [FastAPI WebSocket testing](https://fastapi.tiangolo.com/advanced/testing-websockets/).

## Raspberry Pi operations

From `/home/alan/pinepi-thinksmart-view/hub`:

```sh
sudo systemctl status pinepi-hub.service
sudo systemctl start pinepi-hub.service
sudo systemctl stop pinepi-hub.service
sudo systemctl restart pinepi-hub.service
systemctl is-enabled pinepi-hub.service
journalctl -u pinepi-hub.service -n 30 --no-pager
curl --fail http://192.168.31.36:8766/healthz
```

Health/configuration verification:

```sh
.venv/bin/python tools/verify_service.py --url http://192.168.31.36:8766/healthz
```

Foundation lifecycle exercise, which kills, stops, and starts the service; use a maintenance window when it carries real traffic:

```sh
.venv/bin/python tools/verify_service.py --url http://192.168.31.36:8766/healthz --exercise-lifecycle
```

The tool distinguishes enabled configuration, a running process, and HTTP readiness. Enablement alone is not a verified post-reboot recovery. Do not reboot other workloads merely to check that configuration.

## LAN transport and discovery (introduced in Android 0.5.0)

The initial fixed-message protocol validated connectivity, reception, wake, and reconnect; v2 is now documented separately. At 0.4.0, Hub ten tests and Android 18 tests/Lint passed. Local device evidence: `reports/android-link-test.json`, `reports/android-link-test.png`.

Endpoints require explicit `--link-test-config` mode:

| Endpoint | Purpose | Credentials |
| --- | --- | --- |
| `WS /_test/device` | Receive messages and return ACKs | None with `--discover`; device token in legacy mode |
| `POST /api/messages` | Publish validated v2 JSON to durable pending storage | Control token |
| `POST /_test/send` | Send fixed Chinese test text; no arbitrary body | Control token |
| `GET /_test/status` | Connection count and latest ACK | Control token |
| `POST /_test/disconnect` | Reconnect testing only | Control token |

Trusted-LAN discovery mode requires the user's first-use confirmation, not a terminal token. Control endpoints retain `Authorization: Bearer ...`. The separate device-token mode remains for regression tests. Private configuration: `/home/alan/.config/pinepi-hub/link-test.json`, mode 0600; do not commit credentials.

```sh
cd /home/alan/pinepi-thinksmart-view/hub
.venv/bin/python tools/link_test.py send --config /home/alan/.config/pinepi-hub/link-test.json
.venv/bin/python tools/link_test.py status --config /home/alan/.config/pinepi-hub/link-test.json
```

Fixed test messages contain `type=test.message`, `version=1`, a unique `id`, and fixed `text`. The terminal returns `test.ack` and `wake_requested`. This means processed and optionally wake-requested, not read by a person. Fixed test messages have no durable offline/replay promise; v2 notification storage is separate.

Android uses OkHttp 4.12.0, a foreground connection service, ten-second heartbeat, and reconnect backoff capped at 30 seconds. Android 8.1 NSD uses the compatible older resolve API, serial resolution, a 30-second rescan, and immediate rediscovery after network recovery. Service shutdown clears scanning/callbacks/connections. Discovery accepts only RFC1918 IPv4, discovery version 1, the fixed path, and `auth=none`; it rejects redirects. Discovery version 1 is independent of message protocol version 2. The old private `files/hub-test.json` is not read; no address/token injection is needed.

First use shows the Hub name/address and a confirmation button. No WebSocket opens before consent. The selected UUID persists; IP/port changes for that identity do not require consent again. Other identities need explicit confirmation, and the app does not silently switch when the selected Hub vanishes. UUID selection is not authentication.

mDNS type: `_pinepi-hub._tcp.local.`; TXT: persistent `id`, `v=1`, `path=/_test/device`, `auth=none`, with no credentials. The Hub advertises each private IPv4 on the selected interface, checks changes every 15 seconds, removes stale addresses, and withdraws on exit. Terminal and Hub must share an mDNS-capable LAN. Cross-VLAN and isolated networks remain unverified.

Historical wlan0 launch arguments (the current migrated deployment uses `eth0`):

```text
--host 0.0.0.0 --discover --discovery-interface wlan0 --link-test-config /home/alan/.config/pinepi-hub/link-test.json
```

`tools/enable_discovery.py --config ...` atomically adds `hub_id` to an existing configuration. Preserve it during migration/update; a newly generated identity requires terminal confirmation. `tools/link_test.py provision` can create new configuration; its legacy Android injection file is for the historical mode only. Binding `0.0.0.0` requires explicit `--discover`. systemd permits `AF_NETLINK` for interface discovery without elevating the process user. zeroconf/ifaddr are locked dependencies.

Verified paths include foreground text display, message-driven wake with proximity disabled and no microphone, Hub restart reconnect, and Wi-Fi loss/recovery. Wi-Fi and proximity wake were restored afterward. At 0.5.0, Hub 14 / Android 21 tests and Lint passed; the user personally confirmed the Hub. A temporary port change reconnected without another confirmation; actual DHCP change was not simulated, but identity/address migration has unit coverage. Port 8766 was restored.

Later rounds verified whole-Pi reboot recovery and message wake after more than 30 minutes of ordinary powered standby. Deep Doze remains unverified. Fixed-message tests did not automatically record/play or force PinePi over another foreground app; behavior in that situation requires separate acceptance. See [Device validation](../docs/DEVICE_VALIDATION.md).

References: [Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd), [python-zeroconf API](https://python-zeroconf.readthedocs.io/en/latest/api.html).

## Agreed remote Agent integration (not deployed)

The first Agent is Hermes, called by the Hub over its HTTP API through a private Tailscale network. The Android 8.1 terminal stays on LAN WS/HTTP and does not need Tailscale. Actual API address/version/keys and network reachability must be checked during implementation; credentials stay server-side.

Conversation replies use `PinePi Home ↔ Hub ↔ Hermes API`. Proactive Hermes notifications reuse `Hermes → Broadcast Mid → Hub → PinePi Home`, with the existing publish endpoint and independent Hub subscription. Notifications do not automatically invoke Agent reasoning or enter active chat context; correlation fields can be defined later if needed. Legacy text is connected; v2 transport support is described in the message protocol.

Do not introduce a custom Hermes channel, Gadget integration, or a general multi-Agent plugin framework in this stage. Hermes/Tailscale deployment remains pending, independently of the working Mid text subscription.


### Severity (0.8.4)

Messages may carry `severity: "NORMAL"` or `"CRITICAL"`; omitted defaults to NORMAL. Reserve CRITICAL for credible war, major disaster or potential serious family crises, rather than everyday news and reminders. The publisher assigns it; there is no automatic classifier. Severe cards use subdued amber/warm-brown surfaces for light/dark themes, a fine border and a warning icon plus label. Privacy and playback policies remain unchanged. Upgrade Hub and terminal before publishing the field; older strict v2 readers reject it. See [Message protocol](../docs/MESSAGE_PROTOCOL.md) for JSON and publishing rules.
