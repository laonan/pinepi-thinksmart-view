# PinePi Home — project context

**English** | [中文版](PROJECT_CONTEXT.zh-CN.md)

Base record updated 2026-10-30, with subsequent decisions dated below. Sources: development discussions, device tests, and confirmed decisions. Later decisions supersede earlier exploration. **Verified facts** describe observed/user-confirmed device behavior; **agreed** means a chosen direction; **draft/proposal** is not a frozen interface or accepted result; **pending** must not be filled in by assumption. This is a decision record, not a substitute for implementation reports.

Documentation policy, confirmed 2026-10-31: English is the primary GitHub documentation language. Preserve Chinese editions as `.zh-CN.md`, link them using “中文版” from the English originals, and provide reciprocal English links. Update both editions together. Documentation language does not change the device UI or spoken Chinese prompts.

## 1. Hardware and verified facts

**2026-10-31 protocol decision:** four message types, TEXT/IMAGE/AUDIO/VIDEO. TEXT explicitly chooses TTS (body narration supplied by audio URL) or PRIVATE (do not send body to third-party TTS; play the fixed local text announcement). Images/audio/video have their respective fixed arrival announcements; primary audio/video playback is manual. Generate prompts through Baidu during development and persist them in the APK. Baidu is the initial TTS provider, but the user has no credentials configured and the four assets have not been generated. See [Message protocol and usage](docs/MESSAGE_PROTOCOL.md). This supersedes earlier three-type and unfrozen notification-protocol notes below; it does not freeze conversational session/upload events.

| Item | Status and limits |
| --- | --- |
| Device | Lenovo ThinkSmart View; ADB verified Android 8.1.0 / API 27, build `Lenovo_Kingston_OS_PV_220620_V0.0.40`. ROM was preinstalled; its provenance and modifications are unconfirmed. |
| Bluetooth speaker | User verified phone-to-device Bluetooth music reception on this ROM. Preserve it. |
| Orientation | User observed portrait operation; launcher rotation/state restoration is not accepted yet. |
| Reference specifications | CD-18781Y, APQ8053, 2 GB RAM, 8 GB eMMC, 8-inch 1280×800 touch display, dual microphones, 10 W speaker were cited in discussion, not individually verified on this unit. |
| Launcher | Home page, system settings, original launcher entry, default Home, repeated Home return, and post-reboot entry were user-verified. Original launcher briefly flashes first. Initially only volume buttons woke the screen; 0.3.0 added an accepted proximity-wake test. |
| Local audio | 0.2.4 capture/replay and independent test tones verified; startup pop removed. Enhanced playback volume was acceptable but muddy. Enhancement does not change the original WAV; STT quality is untested. |
| Stop/cancel | 0.2.4 cancellation, opening Settings/reconnecting Bluetooth during capture, and canceling a disconnect wait stopped/cleared capture. Canceling enhanced replay stopped sound and released focus/effects. See validation evidence. |
| Proximity | Foreground `VCNL4200 PRX` repeatedly near=0.0, far=5.0. In 0.3.0, the optional foreground service passed screen-off wake: user feedback matched ADB OFF→ON and wake logs. Disabling removed service/listener. |
| Proximity after reboot | On October 30 the user confirmed wake after restoring power. That report did not independently check stored switch/service state through ADB in the same round. |
| Navigation flash | 0.3.1 earlier immersive flags and a package-specific rule both failed to eliminate the wake flash. The system rule was restored. Settings/Launcher 3 navigation and Home return passed. User accepts this low-priority issue; it does not block Hub work. |
| USB development | Use the verified A-to-C path. On cold boot, connect power only, wait for Home, then connect USB. This recovered enumeration; the low-level cause remains unknown. See README development notes and network ADB recovery. |
| Still unverified | Some Bluetooth/lifecycle combinations, long/deep standby proximity wake, extended operation, and the complete AI pipeline. |

**2026-10-29 baseline decision:** Android 8.1.0 / API 27 and a custom Home APK. Version/build were verified; discussing LineageOS 15.1 did not establish that it is installed. Version 0.2.0 added local audio, public Bluetooth broadcasts, and foreground sensor diagnostics. README and [Device validation](docs/DEVICE_VALIDATION.md) govern implementation/acceptance details.

## 2. Agreed technical direction

- **Android 8.1 / API 27 + custom Home launcher + native settings + remote Home AI Hub.** Preserve ROM/drivers and work at application level.
- Terminal owns touch, recording, display, playback, and local connection state. Hub coordinates STT, Agent access, TTS, and content policy. The terminal runs no large model and is not tied to an Agent or speech vendor.
- Reuse [pinepi-broadcast-mid](https://github.com/laonan/pinepi-broadcast-mid) for upstream notifications. Existing terminals keep their subscriptions; the local Hub subscribes independently and forwards to ThinkSmart. Bidirectional Agents connect through Hub adapters. Real Mid WSS text forwarding works since 0.7.2; bidirectional Agent access remains pending.
- Preserve the original launcher for maintenance. Use public Wi-Fi/Bluetooth/system Settings intents and user-selected default Home, not privileged silent switching.
- **Tap to record; no continuous listening.** First close the short-recording → STT → Agent → text/permitted TTS loop.
- **Use Bluetooth music and AI voice separately.** If phone audio is still connected, ask the user to disconnect it; observed disconnection permits AI recording. Cancel does not record. Do not disable all Bluetooth or force a hidden-profile disconnect.
- Use mature components for a small personal prototype. Do not maintain kernels, drivers, a dedicated OS, or unnecessary intermediary services.
- **Hub must be a system service** (October 30): systemd on Pi, boot startup, background operation, crash restart, explicit start/stop/status, independent of Mac/terminal/SSH lifetime.
- **Development authorization** (October 30): foundation tests/build/infrastructure may proceed autonomously. New business logic, protocol, and UI plans require user approval; subsequent explicit approvals authorize their respective work.
- **Trusted-LAN boundary** (October 30): WS/HTTP between Hub and terminal without local certificates; WSS/HTTPS publicly. No terminal authentication for now; discovered WS endpoints require explicit private IPv4. First use shows a simple confirmation before connection. Persist selection by Hub identity: same ID with a changed IP does not prompt again; another identity does. Control/test endpoints retain an independent token.
- The 0.4.0/0.5.0 approved loop reused the existing Hub area for connection and the latest fixed test text, validating reception, independent screen wake, reconnect, and restart without designing a formal UI. 0.5.0 added discovery and personally verified consent. Those temporary probe messages are not the later notification protocol.

### Initial Agent and cross-network access (confirmed 2026-10-30)

Use one **Hermes** instance through its existing HTTP API, reached by the local Hub over **Tailscale**. The Android terminal only connects to its local Hub and does not need Tailscale. Actual endpoint, Hermes version/capabilities, keys, and deployment remain unchecked/unconfigured.

- Interactive conversation: `PinePi Home ↔ local Hub ↔ Tailscale private network ↔ Hermes API`. Return replies through that API call/event stream to Chat; do not rebroadcast them.
- Proactive notification: `Hermes → HTTPS publish → Broadcast Mid → WSS subscription → local Hub → LAN WS → Messages`. Reuse Mid publishing and separate Hub subscriber identity without disturbing other terminals. Legacy title/content already works; Hermes Producer configuration and its actual media publishing remain pending.
- Notifications do not automatically invoke Agent reasoning or enter current chat context. Define correlation fields later if needed.
- Do not build a Hermes channel plugin, Gadget integration, general plugin framework, or several Agent adapters now. Keep a small replaceable Hub Hermes adapter boundary. Reusing Mid supersedes the earlier proposal for a later custom proactive channel.
- This is an agreed direction, not deployed capability. Tailscale alone does not make an API reachable: configure listening or Serve forwarding, retain Hermes authentication, and store keys server-side.

### Main UI constraints and implementation

The user required the existing diagnostic View to remain available as a hidden secondary page. After wireframe discussion, the three-page main UI was approved. Version 0.6.0 moved all original testing to non-exported `DiagnosticsActivity`, reached by seven version taps within three-second intervals; Back returns to Settings. Wireframe clock placement, message duration/dismissal, and voice entry were initially proposals; Agent selection was removed by decision and routing belongs to the Hub. Other wireframe suggestions did not automatically become requirements.

**0.7.1 layout:** logo/brand upper left, full date/time beside it, count/page indicators lower left, Settings/Chat on the right. Reuse `HorizontalPager` for drag-following cards, snapping, short-drag return, older-left/newer-right navigation, no looping, and newest selection on arrival. Only the settled card loads media or manually plays video; dragging stops video but preserves decoded images.

**0.7.0 appearance:** Compose + Material 3 for the three main pages; native diagnostics unchanged. Fixed pine-green, warm light/subdued dark themes, no wallpaper/dynamic color, normal-weight equal-size date/time. Auto/Light/Dark selection persists; Auto is light 07:00 inclusive–19:00 exclusive. Foreground time/timezone changes and resume refresh it; no screen wake for theme changes. Stack: Kotlin 2.1.20 / SDK 35, BOM 2025.04.01, Activity Compose 1.10.1, runtime API 27. Device date was observed corrected to October 30, `auto_time=1` restored, timezone Asia/Shanghai retained; long-term RTC/NTP after power loss remains unverified.

**Final UI direction recorded October 30:**

- Product name: **PinePi Home**, using the PinePi prefix.
- Message-first, not a clock/message split. Header shows full date including year/weekday and 24-hour time with seconds, equal normal typography.
- Keep only the newest ten local messages; remove the oldest on overflow. No per-item close/delete. A same-width “clear all local messages” action lives in Settings and does not clear Hub history.
- One full-width message, horizontal paging, current position/count at bottom; latest is item 1. New arrivals select latest. History persists through restart using private `AtomicFile`. Do not forcibly leave Chat/Settings during interaction; screen-off arrival restores Messages. Recording/video interruption policy must be defined with actual business integration.
- No wallpaper; reserve room for content. No Agent selector.
- The original three separate text/image/video types and pending wire format were superseded by the October 31 v2 decision. Earlier 0.6.0 images/failures were synthetic-record tests; a video play entry and Bluetooth guard did not prove actual video/audio playback.

## 3. V1 scope and interaction

1. Full-screen local Home with AI Chat, Bluetooth music state, Hub state, native settings, and default-Home support.
2. Bluetooth check before AI recording; explicit capture state with start/finish/cancel.
3. Native recording upload to Hub, transcription and Agent reply display, permitted TTS; private output remains on-screen with fixed local notification audio as later agreed.
4. **Hub chooses routing** (October 30). No terminal Agent list/current/default selector or requirement to submit `agent_id`. Terminal submits input and renders the result; detailed Hub routing is not frozen.
5. Hub notifications show source/content and permitted audio/local prompts. **All user-facing arrivals wake an already-off display**, including normal/important notifications and conversation replies, irrespective of priority or optional Hub wake flags. Screen-on arrivals display normally. Wake does not grant recording/speech permission. Fixed-message wake with proximity disabled passed in 0.4.0; formal variants need their own acceptance.
6. Handle authorization failures, disconnects, timeouts, and cancellation. Local Home/settings/Bluetooth music must work when the Hub is offline.

Draft flow: `idle → Bluetooth check → record → upload/process → display/optional playback → idle`. Cancel stops this recording/playback and notifies Hub; old replies must not leak into a newer request.

Unfrozen suggestions: tap start/finish initially, VAD later. The discussed ~1.5-second silence and 30-second cap are experimental starting values, not final session requirements. Follow-up listening is undecided. Landscape is a recommendation; portrait, rotation, and orientation locking still require a decision.

## 4. Minimal architecture and ownership

```text
Phone ──Bluetooth music──> ThinkSmart View / Android 8
                            └─ Home launcher
                                 │ LAN WS: control, text, notifications
                                 │ LAN HTTP: short recording upload (pending), media downloads
                                 ▼
                            Home AI Hub
                            ├─ Device/session and Agent routing
                            ├─ Privacy and output policy
                            └─ STT / Agent / TTS adapters
                                 └─ Agents and owned business systems
```

| Component | Direction |
| --- | --- |
| Android | Kotlin + Material 3 Compose main UI, native hidden diagnostics, one app module; min/target 27 and compile 35 for building. AudioRecord/MediaPlayer local tests exist. No required WebView. See README/toolchain and device evidence. |
| Settings | Public intents, exception handling/fallback when ROM pages are absent, original launcher retained. |
| Hub | One FastAPI systemd service on Pi 4B `192.168.31.36`, user alan, isolated Python 3.13.18. LAN WS/HTTP, eth0 discovery, first consent, Mid WSS heartbeat/reconnect, ten durable pending messages, terminal save-before-ACK. No separate admin site, MQTT broker, or mandatory media server. |
| Agent | One initial Hermes instance through Tailscale HTTP. Server-side endpoints/credentials/output permissions; no general registry or multi-Agent orchestration. Proactive Mid, interactive API responses. Agent owns its task; Hub owns access/distribution. |
| Local configuration | Identity, Hub selection, UI choices, cache; no Agent selection. Terminal authentication storage remains undecided. Vendor/Agent keys stay server-side. |

## 5. Historical session-protocol drafts

The accepted notification protocol is now [v2](docs/MESSAGE_PROTOCOL.md). The drafts below are retained as context and must not be implemented as an additional required notification protocol.

Agreed transport ownership: terminal initiates Hub connection; WebSocket JSON carries connection/session control, text, and notifications. HTTP POST uploads short recordings; HTTP GET retrieves TTS/image/media files. Trusted LAN uses WS/HTTP; public connections WSS/HTTPS. Public identity/authorization/certificate deployment and upload details remain undecided. Discovery initially used no terminal authentication plus first consent; control probes use a token. No Base64 audio in control JSON or required real-time binary audio in V1.

The original six candidate events were:

| Event | Direction | Draft meaning |
| --- | --- | --- |
| `device.hello` | Terminal → Hub | Online |
| `session.start` | Terminal → Hub | Start a session; Hub routes without terminal Agent selection |
| `session.cancel` | Terminal → Hub | Cancel current request |
| `assistant.text` | Hub → Terminal | Text reply |
| `assistant.audio` | Hub → Terminal | Permitted audio URL/MIME |
| `notification` | Hub → Terminal | Proactive notification |

```json
{
  "type": "assistant.text",
  "request_id": "<current-request-id>",
  "payload": { "text": "<reply-body>" }
}
```

The same `request_id` would correlate upload and asynchronous replies. Reconnect, timeouts, cancellation, late results, heartbeat/backoff, end/error events, delta/full-text semantics, and deduplication still need a final session design.

A subsequent multi-Agent discussion proposed a common presentation envelope:

```json
{
  "type": "message",
  "id": "<message-id>",
  "source": "<Hub-authenticated-Agent-identity>",
  "title": "<title>",
  "text": "<body>",
  "presentation": {
    "mode": "display",
    "sound": "local_chime",
    "priority": "high"
  }
}
```

`presentation` was to be generated by the Hub. Its relationship to `assistant.*`/`notification` was not frozen; these were alternatives, not two mandatory implementations. Permitted audio references and session correlation also needed definition.

Media suggestions: test 16 kHz mono PCM/WAV capture first and HTTP MP3 with native playback for TTS. Discussion endpoints, host addresses, device IDs, and token names were examples, not deployable credentials. Actual upload paths/limits, negotiation, audio authorization, and expiry remain to be configured.

## 6. Privacy and Agent routing policy

**Policy lives at the Hub.** Agents supply content and suggested labels; Hub makes the final decision using authenticated identity, trusted configuration, sensitivity, and user settings. The terminal executes presentation, does not classify political sensitivity, and does not select TTS vendors.

Original proposed defaults, with actual identities/permissions pending:

| Agent role | Default output | External TTS |
| --- | --- | --- |
| Risk monitoring | Display, optional fixed local prompt | Forbidden |
| Daily tasks | Display and voice | Ordinary content allowed |
| Owned business | Display | Forbidden by default |
| Family assistant | Display and voice | Approved content only; separate restrictions for private data |

- Bound trust by tasks, data permissions, and provider configuration, not geography. Self-claimed `public`, `trusted`, or `source` fields cannot elevate Agent permissions.
- Draft classifications: `public`, `personal`, `sensitive`, `local_only`. Sensitive output stays on-screen or uses fixed local prompts. Block `local_only` before any external STT, LLM, tool, or TTS call. Priority cannot bypass privacy.
- Display and TTS are independent: failed/rejected/disabled synthesis must not discard the reminder. Risk notices retain source, event time, and uncertainty; distinguish facts from inference.
- Hub owns Agent selection/routing and server-side registration/address/auth/permission configuration. No terminal selector. Detailed routing and cross-Agent orchestration are not implicitly authorized.
- Validate/route proactive notifications by sending Agent identity. Agents receive only task-required data; do not share business/family/other-Agent context by default.
- Record only after a user trigger. Cleanup after processing and user-controlled conversation history were proposed; retention, log redaction, notification deletion, and exact history policy require explicit design. Do not log raw audio, sensitive bodies, or keys.
- Public TLS protects transit, not data from the Hub/provider processing it. Trusted LAN uses the agreed WS/HTTP model. Disabling third-party TTS reduces disclosure at that stage; it does not make cloud Agent/STT processing entirely local/private.

## 7. Deferred or excluded from V1

- Linux port, ROM replacement, custom kernel/drivers, system stripping, kiosk boot artwork: outside the current route.
- Always-on microphone, wake word, full duplex, speech interruption, AEC, Bluetooth/AI mixing or automatic ducking: not now.
- NAS music was canceled as a current requirement. FM/video streams, room synchronization, and a general remote-terminal platform are not prerequisites.
- Fully streaming STT/TTS, sentence queues, local speech models, complex multi-Agent routing, and a full Hub admin UI require later experience/validation.
- A private-mode UI toggle, automatic follow-up, and complete dual-orientation layouts were only proposals. Proximity wake was explicitly approved and tested in 0.3.0; later records qualify long-term/reboot behavior.

## 8. Validation order and progress

The October 29 order changed: foreground sensor tests were completed, then screen-off proximity wake was requested immediately and accepted on 0.3.0. Hub deployment/protocol work followed later; historical statements about an undecided host refer to that time.

1. Record model, Android point release, and ROM build. Preserve verified Bluetooth music instead of redeveloping it.
2. Validate local APK/default Home/boot/Home return/settings/original launcher, capture/replay, Bluetooth checks and cancellation. Confirm no unsolicited capture and prompt microphone release.
3. Foreground near/far, screen-off wake, switch-off cleanup; 0.3.0 passed. User confirmed wake after power restore October 30. Long/deep standby pending. Volume buttons remain a verified backup; touch wake is not implemented.
4. Deploy/discover/confirm Hub and validate the network loop, then short HTTP upload → STT → Hub-routed Agent → text → permitted TTS, request correlation, and stop. Verify all message arrivals wake an off display and test long screen-off reliability separately.
5. Policy boundaries: risk/business content cannot trigger external TTS, priority cannot lower sensitivity, claimed identities cannot elevate access, local-only blocks before external calls, failed TTS preserves display/source.
6. Routing/failures: no terminal Agent choice; disconnect/reconnect/offline/timeouts/canceled late replies must not cross sessions or repeat audio. Preserve Bluetooth/settings usability.
7. Real experience: compare STT on the same adult/child Chinese, English, and mixed recordings; audition short answers, bilingual text, and stories. Measure actual latency, extended operation, and sleep recovery. Thresholds/durations remain undecided; early estimates are not achieved metrics.

## 9. Remaining decisions

The original checklist predated later decisions: the initial Pi 5 deployment was replaced by Pi 4B, Hermes was selected as the first Agent, and Baidu was selected for initial TTS on October 31. Earlier unselected-provider notes are historical, not permission to hard-code another vendor.

Remaining items include formal conversational/upload/error/completion/cancel semantics; actual Agent endpoint/auth/history isolation and unavailable handling; STT vendor/model/region; Baidu credentials/voice/data policy; VAD location/parameters; orientation scope; recording/history/media retention and acceptance thresholds. Android toolchain is pinned in README, but added dependencies still need compatibility checks. Device authentication and public certificate/key provisioning need explicit design within the approved network boundary.

Complete the hardware baseline and minimal protocol before real service integration. Record unresolved items or expose configuration instead of inventing addresses, keys, or unverified capabilities.

## Subsequent implementation records

### Hub moved to another Raspberry Pi (2026-10-30)

Current host: `alan@192.168.31.36`, Pi 4B / Debian 12, isolated Python 3.13.18, unchanged system Python, project `/home/alan/pinepi-thinksmart-view/hub`, enabled/active systemd, eth0 with `.36`/`.37`. Old host was unreachable; a new Hub identity was generated and confirmed on ThinkSmart. Fixed message and matching ACK passed. Migration suite: 13 passed / 1 failed (unresolved synthetic HTTPS/WSS timeout); LAN and recovery passed. See [Migration](docs/HUB_MIGRATION.md). Configuration alone did not validate a new-Pi reboot.

### Broadcast Mid text (2026-10-30)

Approved initial `event.title` + `event.content`. Public endpoint `wss://hail.lonelyassistant.com/ws/`, correct device **`pinepi-home-hub`**; an extra hyphen (`pine-pi-home-hub`) caused authentication rejection and was corrected in private configuration/tool defaults without changing the token. Real WSS certificate validation, heartbeat, and one cloud notification passed. Terminal 0.7.2 / code 14 showed source/body, saved privately, and ACKed. Hub/LAN 22 passed, one existing synthetic WSS test excluded; Android 29 tests, Lint 0 errors / six warnings. See [Mid setup](docs/BROADCAST_MID.md).

USB shell timed out and reconnect lost enumeration. The user recovered with USB unplug → power off/wait → power-only boot to Home → A-to-C data. `HUA0BQMQ device` returned without another RSA prompt. ADB installed directly; no browser-install fallback was used.

### 0.7.3 long-text boundaries (2026-10-30)

The old 2048-byte adapter rejected a 2002-character / 5878-byte Chinese article (`ignored=1`). Limits became 8192 UTF-16 units / 32 KiB UTF-8, 64 KiB frame, 512 KiB pending storage. Oversize produces `content_too_long`, not truncation; legacy non-text remains unsupported. Version 0.7.3 / code 15 installed; the resent article preserved both ends, storage, and matching ACK. Hub/LAN 25 passed / one deselected; Android 29 tests and Lint passed with six existing warnings.

### Custom notification source (2026-10-30)

Approved `event.source` as a display name, absent/blank fallback Broadcast Mid, not inferred from Producer token. Hub validates type and 100 UTF-16 units. Existing terminal source support needs no APK change; title/content-only senders remain compatible. Historical source names stay as originally received. Examples: [Mid setup](docs/BROADCAST_MID.md).

### 0.7.4 visual details (2026-10-30)

Approved logo 26→30dp, brand 15→17sp, title/body gap 14→36dp. Arrival used a 320ms alpha 0.55→1 fade and 12dp rise with no looping. MainActivity emits only on newest-ID change; Messages consumes the event once. Startup/history/paging/settings return do not retrigger. Chat/Settings stays open on arrival; later Messages return consumes the latest event. Draw-layer animation avoids rebuilding media. Code 16 installed; 54px title gap, full date, newest selection, paging, and recorded alpha/position change passed.

### 0.7.5 pure fade (supersedes the rise)

Old content fades for 160ms, changes while transparent, then new content fades for 200ms without movement. New arrivals only; initial content only fades in. Rapid arrivals cancel an older transition. New paging starts at item 1 during transition to avoid stale callbacks overwriting newest selection. Media/gestures pause during transition. Code 17 installed; silent recordings confirmed fade without movement, newest selection, and paging.

### 0.7.6 cinematic fade (supersedes the faster fade)

The user found 0.7.5 too flash-like. Use 700ms fade-out + 150ms blank + 1200ms fade-in, 2050ms total, smooth easing, alpha only on internal content, fixed card background/position/size. Arrival consumption, latest priority, and paging remain. Code 18 installed.

### 0.7.7 scroll hint

A centered single downward chevron appears only when `ScrollState.canScrollForward`, hides at the end, and returns when scrolling back. Original area 28dp, icon 24dp, no text overlay, click, or looping. Short text/images/videos have no hint. Accessibility text: “下方还有内容” (more content below). The area shares the text ScrollState through native `scrollable`, supporting drag/inertia from near the arrow. Arrival fading remains. Reference: [NN/g](https://www.nngroup.com/articles/scrolling-and-attention/).

### 0.7.8 double-chevron and tap hint

Supersedes the non-click design: remove 28dp outer bottom padding, use a 32dp area (total 56→32dp), centered 24dp double down-chevron, 48dp-wide tap target. Show only the default Chinese Toast “请向上滑显示全部内容”; do not auto-scroll. Repeated taps replace the Toast; leaving cancels it. Dragging/inertia still works and cancels clicks. End/short-message visibility rules and non-text padding remain.


### 0.8.1 Baidu short-text TTS (2026-10-31)

Baidu credentials are configured privately on the Hub. The four fixed Chinese announcements were generated with voice 0 and bundled into Android 0.8.1 / code 23. HTTP TEXT/TTS publishing may omit speech; Hub checks the explicit mode before any provider call, synthesizes the body, caches MP3, then sends a strict v2 message with speech URL. PRIVATE never calls the provider. The user confirmed all four local announcements and a complete Hub-generated narration. This first adapter rejects bodies that cannot be GBK-encoded or reach 1024 GBK bytes; no truncation, long-news segmentation, STT, Agent, or scheduling is implied. The earlier credential-pending statements describe 0.8.0, not the current state.


### Message playback volume (0.8.2)

Settings → **语音播放音量** (voice playback volume) saves a minimum media-volume step, displayed as **1/15 … 15/15** on ThinkSmart, default **14/15**. Just before a local arrival announcement, text narration, or manually selected audio message starts, the terminal raises media volume only if it is below this minimum. It does not lower a louder setting. Downloads, blocked Bluetooth playback, and diagnostics do not raise volume; the video player itself retains normal media-volume behavior.

On completion, cancellation, error, interruption, or leaving Home, the previous volume is restored before audio focus is released. A volume-key press or observed external volume change during playback keeps the user's adjustment instead. This restoration covers normal playback/lifecycle cleanup, not abrupt process termination or power loss. The selector uses the device's actual media-volume steps directly, with no percentage conversion. Bluetooth music remains protected by the existing disconnect requirement; there is no automatic disconnection or queued replay.


### Message header icons (0.8.3)

Private text shows a small lock immediately after the timestamp. Text narration uses a compact play icon in the same header, replacing the text button above the body. It changes to stop during playback and to a cancelable progress indicator during loading. Accessibility labels retain the action and Bluetooth-blocked reason; playback errors appear as a brief toast. Privacy and playback policies are unchanged.


### Severity (0.8.4)

Messages may carry `severity: "NORMAL"` or `"CRITICAL"`; omitted defaults to NORMAL. Reserve CRITICAL for credible war, major disaster or potential serious family crises, rather than everyday news and reminders. The publisher assigns it; there is no automatic classifier. Severe cards use subdued amber/warm-brown surfaces for light/dark themes, a fine border and a warning icon plus label. Privacy and playback policies remain unchanged. Upgrade Hub and terminal before publishing the field; older strict v2 readers reject it. See [Message protocol](docs/MESSAGE_PROTOCOL.md) for JSON and publishing rules.


### Full-screen images and video (0.8.5)

Tap an image or its expand icon to view the already-decoded bitmap full screen, preserving aspect ratio without another download. Tap the video area/play button to open a full-screen player and start only if Bluetooth playback is allowed. While blocked, the viewer opens with an explanation and disabled play button; disconnecting Bluetooth does not start it automatically.

The viewer uses a black background, hides Home chrome and system bars, and provides a top-right close button and Android Back dismissal. Returning preserves the selected message. Video completion leaves a replay action in the viewer. Closing, leaving Home, changing the selected message or Bluetooth becoming unsafe releases/stops the video; a new message sound also closes a playing video viewer before using audio. No automatic media playback is added on message arrival. Existing download bounds, URL validation and message audio-volume rules remain unchanged; video uses ordinary media volume.
