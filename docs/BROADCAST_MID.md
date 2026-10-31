# Broadcast Mid client

**English** | [中文版](BROADCAST_MID.zh-CN.md)

Target Hub: `alan@192.168.31.36`. Public subscription: `wss://hail.lonelyassistant.com/ws/`; device name: **`pinepi-home-hub`**, not `pine-pi-home-hub`. Public TLS uses the system CA store. Hub-to-ThinkSmart traffic remains trusted-LAN WS without local certificates.

Version 0.8.0 additionally accepts complete v2 objects as described in [Message protocol and usage](MESSAGE_PROTOCOL.md). The compatibility behavior and historical results below describe the original text integration.

## Configuration

Add `pinepi-home-hub` and a dedicated random token to the existing `devices` in cloud `/etc/pinepi-broadcast-mid/config.json`. Add it to the relevant Producer's `targets`, unless that Producer already has `*` permission. Do not reuse another terminal's device name: Mid disconnects the old connection with the same name. Restart `pinepi-broadcast-mid` after cloud changes.

From the Mac, run the interactive configuration tool. Enter the token into its hidden terminal prompt, not into chat:

```sh
ssh -t alan@192.168.31.36 'cd /home/alan/pinepi-thinksmart-view/hub && .venv/bin/python tools/configure_broadcast_mid.py'
```

It creates `/home/alan/.config/pinepi-hub/broadcast-mid.json` with mode 0600 and refuses to overwrite an existing file automatically. Edit an existing file locally on the Pi while retaining permissions. Example structure, without a real token:

```json
{
  "url": "wss://hail.lonelyassistant.com/ws/",
  "device": "pinepi-home-hub",
  "token": "<device token>"
}
```

Keep the same systemd `pinepi-hub.service` and append:

```text
--broadcast-mid-config /home/alan/.config/pinepi-hub/broadcast-mid.json --notification-state /var/lib/pinepi-hub/notifications.json
```

The unit uses `StateDirectory=pinepi-hub`, mode 0700; pending storage is 0600. Home remains read-only to the service, so it cannot write configuration. Missing/invalid configuration makes the subscriber wait and reread every five seconds without stopping local service. Valid changes are read on the next reconnect; restart the service to reconnect immediately.

## Compatibility protocol and initial scope

Immediately after WSS connects, the Hub sends `{token, device}`. Application JSON `ping` receives `pong`; WebSocket control-frame heartbeat is also enabled. Reconnect backoff is 1–60 seconds. Logs exclude failure details, tokens, and message bodies.

The agreed legacy input is optional `event.title`, required nonblank string `event.content`, and optional `event.source` (up to 100 UTF-16 units). Source is trimmed and falls back to Broadcast Mid when absent/blank; wrong types or excessive length are rejected with a reason. Text without an `event` wrapper is also accepted.

Since 0.7.3: title ≤200 UTF-16 units; body ≤8192 UTF-16 units and 32 KiB UTF-8; forwarded frame ≤64 KiB; pending file ≤512 KiB, matching the terminal history reader. This is about 8000 ordinary Chinese characters; emoji commonly use two UTF-16 units. The old 0.7.2 limit of 2048 body bytes was fixed. Oversize input records a body-free reason and exposes `broadcast_mid.last_rejection=content_too_long`; it is not silently truncated.

Legacy conversion emits `type=home.message, version=1, kind=TEXT, id, source, title, text`. Terminal 0.7.2 / code 14 onward applies local history, newest selection, and screen-off arrival wake. `play_now` is not executed; legacy text does not speak or record. Binary e-ink frames, malformed input, and unsupported legacy non-text payloads are not forwarded. Complete v2 media objects use the separately documented v2 path rather than this compatibility adapter. The fixed `test.message` probe remains available; Agent session events are not defined by this legacy format.

The Hub persists up to ten unacknowledged notifications, retaining the latest ten on overflow. Offline/restarted delivery replays oldest-first. A terminal `home.ack` after successful save removes the pending record; a known duplicate ID is acknowledged without another wake. An upstream `event.id` produces a stable hashed Hub ID; absent IDs generate a new ID on each arrival. Equal bodies are not used for deduplication. This is a single-terminal bounded queue, not unlimited storage or an exactly-once guarantee across all failures.

## Operations and verification

```sh
cd /home/alan/pinepi-thinksmart-view/hub
.venv/bin/python tools/link_test.py status --config /home/alan/.config/pinepi-hub/link-test.json
sudo systemctl status pinepi-hub.service
```

`auth_sent` only means the authentication frame was sent. Mid has no authentication-success frame; an application heartbeat or valid notification establishes `connected`. For `auth_rejected`, check the device name/token and whether the cloud service was restarted. The Producer token is used only for publishing, not for this subscription.

Publish through an existing Producer's HTTPS `/api/send`:

```json
{
  "targets": ["pinepi-home-hub"],
  "event": {
    "id": "a-unique-message-id",
    "source": "Hermes",
    "title": "Connection test",
    "content": "A text notification from Broadcast Mid."
  }
}
```

Mid's `delivered` means delivery to the Hub; use the terminal's matching `home.ack` to establish terminal persistence. Record actual end-to-end, display, and upgrade results rather than assuming them before a Producer is connected.

Protocol references: [Mid README](https://github.com/laonan/pinepi-broadcast-mid/blob/main/README.md), [server implementation](https://github.com/laonan/pinepi-broadcast-mid/blob/main/python/broadcast_server_mid.py).

## Device results (2026-10-30)

Real public TLS, cloud application heartbeat, a user-published text notification, terminal UI/private history, and matching ACK all passed on 0.7.2. ADB recovered through the documented power-on sequence without a new RSA prompt. Related/LAN tests: 22 passed. The previously failing local synthetic WSS case was excluded from this round and its failure retained in the historical record. The screen was already on at arrival; no new standby wake test was performed.

## 0.7.3 long-text fix (2026-10-30)

A Chinese notification exceeded the old 2048-byte UTF-8 limit and incremented ignored. Body/frame/pending limits were aligned, with Chinese/emoji boundaries and ten long-message reloads tested. Hub/LAN: 25 passed / 1 deselected; Android: 29 tests, Lint 0 errors / six existing warnings. Hub and terminal were updated. Resending the original 2002-character / 5878-byte body preserved both ends, storage, and matching ACK. Local evidence: `hub/reports/long-text-validation.json`.

## Custom display sources (2026-10-30)

New legacy messages may include `event.source`. The Hub trims and forwards it to existing 0.7.3 terminals without an APK or Mid server change. Missing/blank source stays Broadcast Mid; historical records are not retroactively relabeled. This is the sender's claimed display name, not an identity authenticated from a Producer token.

```sh
curl -sS 'https://hail.lonelyassistant.com/api/send' \
  -H 'Authorization: Bearer <PRODUCER_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{"targets":["pinepi-home-hub"],"event":{"source":"Hermes","title":"Source test","content":"This notification comes from Hermes."}}'
```

Use the Producer token here, not the Hub's subscription token. Older clients limited to title/content remain compatible; curl can send additional supported fields.
