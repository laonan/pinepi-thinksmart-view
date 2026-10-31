# PinePi Home message protocol and usage

**English** | [中文版](MESSAGE_PROTOCOL.zh-CN.md)

Protocol version: `2`. Applies to Android 0.8.0 and the matching Hub. Replace example IP addresses with the actual Hub address.

## Implementation status

Implemented: Hub v2 validation, durable pending messages and forwarding; four Android message types and two text modes; URL-based media loading; playback of supplied narration audio; manual audio/video playback; and v2 ACKs. Legacy `version: 1` text and fixed test messages remain compatible and do not automatically speak.

**Pending configuration: Baidu API credentials are unavailable, so no Baidu audio has been generated. The four fixed notification announcements are not bundled yet.** Corresponding messages display normally but their announcement remains silent; no substitute sound is used. Resource selection and playback are implemented and become usable after generation and an APK rebuild. Ordinary text currently requires a sender-generated `speech.url`. Automatic body-to-speech calls on the Hub, news collection, and scheduling are outside this implementation. Protocol support does not mean Baidu TTS is configured.

## Transport and publishing

```text
Sender → Hub (validate and persist) → WebSocket JSON → PinePi Home
                                                        └─ HTTP(S) media/narration download
```

The terminal retains the mDNS-discovered `WS /_test/device` endpoint; no manual address entry is needed. The historical endpoint name remains, but its messages support v2. Trusted-LAN discovery mode does not authenticate the terminal; first-use Hub confirmation is unchanged.

Use `POST /api/messages` with the existing independent control token to publish a complete message object. A `queued` response means it entered durable pending storage; `duplicate` means that ID is already pending. Neither means playback finished. Offline terminals do not prevent submission.

On the Hub, send any complete example below as `message.json`. Credentials are read from a private file, not placed in command arguments or the repository:

```sh
python3 - <<'PYTHON'
import json
from pathlib import Path
from urllib.request import Request, urlopen
config = json.loads(Path.home().joinpath('.config/pinepi-hub/link-test.json').read_text())
request = Request('http://127.0.0.1:8766/api/messages',
    data=Path('message.json').read_bytes(),
    headers={'Authorization': 'Bearer ' + config['control_token'],
             'Content-Type': 'application/json'})
with urlopen(request, timeout=15) as response:
    print(response.read().decode())
PYTHON
```

Errors: `401` unauthorized, `413` JSON frame over 64 KiB, `422` invalid JSON/fields, and `503` durable storage not configured. Validation errors contain a reason, not the message body. Do not include credentials, Base64, binary media, or provider-specific TTS parameters in the JSON.

Broadcast Mid accepts a complete v2 object inside `event`, or a direct complete object; v2 IDs are preserved. Legacy `event.title/content/source` still becomes silent v1 text. An invalid new-format object containing `version/kind/text_mode/speech/media` is rejected rather than reinterpreted as legacy text.

## Common fields

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `type` | string | Yes | Exactly `home.message` |
| `version` | integer | Yes | Exactly `2`; strings and decimal numbers are rejected |
| `id` | string | Yes | 1–80 ASCII letters, digits, underscores, or hyphens; reuse the ID on retries |
| `source` | string | Yes | Nonblank display source, up to 100 UTF-16 units |
| `created_at` | string | Yes | ISO 8601 with a timezone, year 1970 onward; e.g. `2026-10-31T07:30:00+08:00` or `2026-10-30T23:30:00Z`; optional 1–3 fractional-second digits |
| `kind` | string | Yes | Case-sensitive `TEXT`, `IMAGE`, `AUDIO`, or `VIDEO` |
| `title` | string | No | Up to 200 UTF-16 units; omitted means empty |

Omit absent optional fields instead of sending `null`. Version 2 rejects unknown fields and fields belonging to another variant. Examples: `PRIVATE` with `speech`, an image with `text`, or any message with `autoplay` is invalid. Type and text mode determine playback policy; extra fields cannot override it.

Each message has one primary content type. Titles and bodies are plain text, not HTML/Markdown. Display time comes from `created_at`; ordering remains based on terminal arrival, not an upstream clock that may move backward.

## Ordinary text: display and narrate

```json
{
  "type": "home.message",
  "version": 2,
  "id": "news-20261031-001",
  "source": "Morning News",
  "created_at": "2026-10-31T07:30:00+08:00",
  "kind": "TEXT",
  "title": "Today's news",
  "text": "Good morning. Here is the first of today's five news summaries.",
  "text_mode": "TTS",
  "speech": {
    "url": "http://192.168.31.36:8766/media/news-20261031-001.mp3",
    "mime_type": "audio/mpeg"
  }
}
```

`text`, `text_mode: "TTS"`, and `speech` are required. The body must be nonblank and at most 8192 UTF-16 units and 32 KiB UTF-8. Narration corresponds to `text` only; do not implicitly prepend the title or source. The sender must generate a downloadable narration file before publishing.

After a new message is saved, the terminal downloads and plays narration when audio protection allows. It does not also announce a new text message. The card provides read/stop controls. Download or playback failure preserves the text and may fall back to the fixed local text announcement. The terminal neither submits text to Baidu nor stores Baidu credentials.

## Private text: fixed local announcement only

```json
{
  "type": "home.message",
  "version": 2,
  "id": "private-20261031-001",
  "source": "Personal Assistant",
  "created_at": "2026-10-31T08:00:00+08:00",
  "kind": "TEXT",
  "title": "Private reminder",
  "text": "Sensitive or private content for on-screen display only.",
  "text_mode": "PRIVATE"
}
```

`text_mode: "PRIVATE"` is mandatory and `speech` is forbidden. Display the body and play the local recording “有新的文字消息” (there is a new text message). Do not show a body-narration button. Any future sender/Hub TTS implementation must check the mode **before calling a third party** and must not submit the private title or body.

This mode prohibits third-party narration; it does not change existing Hub processing, trusted-LAN transport, or local terminal history storage.

## Image: display and announce

```json
{
  "type": "home.message",
  "version": 2,
  "id": "image-20261031-001",
  "source": "Family Album",
  "created_at": "2026-10-31T08:10:00+08:00",
  "kind": "IMAGE",
  "title": "Today's photo",
  "media": {
    "url": "http://192.168.31.36:8766/media/image-20261031-001.jpg",
    "mime_type": "image/jpeg"
  }
}
```

Play the local recording “有新的图片消息” (there is a new image message), and download and display the image with its aspect ratio preserved. Do not analyze or narrate image contents.

## Audio: announce, then play on request

```json
{
  "type": "home.message",
  "version": 2,
  "id": "audio-20261031-001",
  "source": "Family Messages",
  "created_at": "2026-10-31T08:20:00+08:00",
  "kind": "AUDIO",
  "title": "Voice message",
  "media": {
    "url": "http://192.168.31.36:8766/media/audio-20261031-001.mp3",
    "mime_type": "audio/mpeg"
  }
}
```

Play the local recording “有新的音频消息” (there is a new audio message). The card has play/stop controls. Download and play the primary audio only after the user taps; it does not overlap the arrival announcement.

## Video: announce, then play on request

```json
{
  "type": "home.message",
  "version": 2,
  "id": "video-20261031-001",
  "source": "Family Messages",
  "created_at": "2026-10-31T08:30:00+08:00",
  "kind": "VIDEO",
  "title": "Video message",
  "media": {
    "url": "http://192.168.31.36:8766/media/video-20261031-001.mp4",
    "mime_type": "video/mp4"
  }
}
```

Play the local recording “有新的视频消息” (there is a new video message). Video starts on a user tap and stops when leaving the page, backgrounding the Activity, or entering a disallowed Bluetooth state.

## URLs, formats, and download limits

`media` and `speech` contain exactly two string fields: `url` and `mime_type`. URLs are limited to 4096 UTF-16 units. HTTPS is supported; HTTP requires an RFC1918 private IPv4 address. User information, fragments, local file/content schemes, and invalid ports are rejected. Image/audio clients do not follow redirects. Video uses Android `VideoView`, whose redirect handling belongs to the system player; publishers should supply direct, non-redirecting URLs.

| Use | MIME | Initial scope |
| --- | --- | --- |
| Image | `image/jpeg`, `image/png` | 8 MiB download limit; at most 40 million pixels before decoding; downsampled display decode with maximum side 1600 |
| Audio and narration | `audio/mpeg` | MP3; 16 MiB limit; complete download before playback; 45-second total request timeout |
| Video | `video/mp4` | H.264 + AAC recommended; system network playback; other codecs are not guaranteed |

Image/audio HTTP response Content-Type must also match the media category; file extensions alone are insufficient. Video compatibility depends on the device decoder.

Keep content immutable at a given URL. Publishers must keep media available while messages remain in history; short-lived signed links may prevent replay. Images and audio currently have no persistent media cache. Temporary audio files are deleted on stop/completion; replay after restart fetches the original URL again.

To serve files from the Hub, create a dedicated directory and explicitly add this service argument:

```text
--media-directory /var/lib/pinepi-hub/media
```

It maps to unauthenticated `GET /media/<filename>` for trusted-LAN terminals. Store only display media there; do not point it at configuration, home, or pending-state directories. The Hub is not a download proxy and does not read arbitrary local files named by message JSON.

## Sound, deduplication, and acknowledgement

- Arrival sound is triggered only after a new v2 message is saved. Legacy v1 text remains silent.
- The newest arrival replaces current message audio; there is no delayed narration queue. Late callbacks from canceled downloads cannot restart sound. An upstream scheduler must coordinate sequential news items; automatic queuing of an entire morning briefing is not implemented.
- Local prompts, text narration, audio, and video sound are mutually exclusive. They use media volume without changing system volume.
- Playback is allowed only when Bluetooth is off or all connections are confirmed disconnected. Unknown/connecting/connected states block automatic sound. Reconnection cancels message audio immediately. Message audio is suspended on the diagnostics recording page. Previously blocked sounds are not replayed when the block clears.
- Leaving the Home Activity, switching Home pages or the selected message, or clearing messages stops current message audio. History restoration and paging do not automatically replay sounds.
- The terminal retains ten messages and persists the latest 256 accepted IDs. Within this window, retries do not save, wake, announce, or narrate again. Clearing display history preserves deduplication IDs. Permanent deduplication beyond this bound is not promised; publishers must use a new ID for changed content.
- The Hub retains the latest ten unacknowledged messages, dropping the oldest on overflow, and replays oldest-first on reconnect. It is not unlimited offline storage.

After successfully saving a message, the terminal sends:

```json
{
  "type": "home.ack",
  "version": 2,
  "id": "news-20261031-001"
}
```

An implementation may add boolean `wake_requested` for diagnostics; receivers must accept a v2 ACK without it. An ACK means saved, not read, downloaded, or played. Already-saved retries also receive an ACK. There is no playback-completion event in this protocol.

## Generate and bundle the fixed local announcements

| Resource | Exact spoken text |
| --- | --- |
| `notice_text.mp3` | 有新的文字消息 |
| `notice_image.mp3` | 有新的图片消息 |
| `notice_audio.mp3` | 有新的音频消息 |
| `notice_video.mp3` | 有新的视频消息 |

These Chinese phrases are product audio content and remain Chinese in both documentation editions.

Create a private configuration outside the repository, such as `~/.config/pinepi-hub/baidu-tts.json`, with mode `0600`:

```json
{
  "api_key": "YOUR_BAIDU_SPEECH_API_KEY",
  "secret_key": "YOUR_BAIDU_SPEECH_SECRET_KEY"
}
```

From the repository root, run:

```sh
python3 hub/tools/generate_notice_audio.py --config ~/.config/pinepi-hub/baidu-tts.json --voice 0
```

The default voice is Du Xiaomei (`per=0`); select another enabled Baidu voice with `--voice`. The tool only submits the four fixed public phrases and has no arbitrary-message-body input. It writes to `android/app/src/main/res/raw/` only after all four generations succeed. Output contains resource checksums, never keys or tokens.

Commit the four generated MP3 product assets, then rebuild/install the APK. They remain available on the terminal without runtime Baidu calls. The private configuration is excluded by `.gitignore` and must never be committed. Missing assets remain silent and must not be counted as successful announcement validation.

Reference: [Baidu short-text synthesis API](https://ai.baidu.com/ai-doc/SPEECH/mlbxh7xie).
