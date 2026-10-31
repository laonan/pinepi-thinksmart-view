# PinePi Home

**English** | [中文版](README.zh-CN.md)

A custom Android Home launcher for the Lenovo ThinkSmart View, built and validated against **Android 8.1.0 / API 27**. It preserves the existing ROM, Bluetooth speaker functionality, and original launcher. See [Project context](PROJECT_CONTEXT.md) for decisions and scope.

## Current status: Material 3 UI, message protocol v2, and Baidu TTS (0.8.1)

See **[Message protocol and usage](docs/MESSAGE_PROTOCOL.md)** for publishing, complete JSON fields, all five examples, acknowledgements, media limits, and notification audio generation. Version 2 supports spoken text, private text with a local announcement, and image/audio/video URLs. Legacy v1 text remains silent.

Baidu TTS is connected in 0.8.1: four fixed announcements are bundled, and the Hub can synthesize permitted short text when the HTTP publisher omits `speech`. The user confirmed all four local announcements and one complete narration. Private text never enters this provider path. News collection, scheduled briefings, and long-text segmentation remain pending. Primary audio/video playback is distinct from its local arrival announcement; Bluetooth protection remains active.

- Kotlin and Material 3 Compose, in one `app` module. The original native View diagnostics remain accessible through a hidden entry. No WebView is required.
- Declares `MAIN + HOME + DEFAULT` and an app-list launch entry. Users choose it as the default Home through Android settings.
- Messages, Chat, and Settings pages. One full-width message at a time, horizontal paging, position/count at the bottom, and the latest ten records in private storage.
- Public Settings intents open Wi-Fi, Bluetooth, system, and default Home settings, with a system-settings fallback where a ROM-specific page is unavailable.
- The original launcher remains available through the maintenance entry. Opening it temporarily does not change the default Home.
- Tap the version seven times in Settings, with no more than three seconds between taps, to open hardware/recording/Bluetooth/proximity/Hub diagnostics. Back returns to Settings.

## Documentation

| Document | Purpose |
| --- | --- |
| [Message protocol and usage](docs/MESSAGE_PROTOCOL.md) | v2 JSON examples, publishing, media, privacy, ACKs, and fixed prompts |
| [Hub service](hub/README.md) | Setup, systemd operations, discovery, and tests |
| [Broadcast Mid](docs/BROADCAST_MID.md) | Cloud subscription, legacy text, source names, and validation |
| [Device validation](docs/DEVICE_VALIDATION.md) | Device baseline, results, historical evidence, and outstanding checks |
| [Hub migration](docs/HUB_MIGRATION.md) | Migration to the current Raspberry Pi 4B |
| [Project context](PROJECT_CONTEXT.md) | Agreed architecture, scope, and development decisions |

English is the primary language for repository documentation. Keep the corresponding `.zh-CN.md` editions and bidirectional language links when updating documentation. The on-device UI and fixed spoken Chinese announcements are independent of the documentation language.

## Toolchain and build

| Component | Pinned value |
| --- | --- |
| Runtime and validation OS | Android 8.1.0 / API 27 |
| `minSdk` / `targetSdk` | 27 / 27 |
| `compileSdk` / Build Tools | 35 / 35.0.0 (AGP default) |
| Android Gradle Plugin | 8.9.2 |
| Gradle Wrapper | 8.11.1 |
| Kotlin | 2.1.20 |
| Build JDK | 17 |
| Java / Kotlin bytecode target | 1.8 |

`compileSdk` does not require Android 15 on the device. This is a locally installed appliance app with API 27 behavior, not an app-store release configuration. There is no `maxSdkVersion`; newer devices may install it but are outside the validated scope. Check API 27 compatibility before adding dependencies or APIs.

Open `android/` in Android Studio, select JDK 17 for Gradle, and install SDK Platform 35 and Build Tools 35.0.0. For command-line builds, set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK, or put `sdk.dir` in the untracked `android/local.properties`.

Android Studio creates the `app` run configuration after synchronization. The device appears as `QUALCOMM msm8953_32`; Run has been verified to install and launch the app. If the IDE's bundled JDK 25 is incompatible with Gradle 8.11.1, select the installed JDK 17.

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. The first build downloads Gradle, plugins, and Kotlin dependencies.

## Installation and default Home

After authorizing ADB, confirm the target with `adb devices`. Add `-s <serial>` when more than one device is connected.

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pinepi.thinksmart.launcher/.MainActivity
```

Use the app's default Home settings entry and select PinePi Home in Android. If the dedicated page is unavailable, find the default Home selection in system app settings. Keep the original launcher installed and enabled; use the other-launcher entry for maintenance or system settings to switch back.

This is a development build. Release signing and key management remain separate; never commit private keys.

## Development notes

### USB cable and power-on order

The verified connection uses an **A-to-C cable path**: USB-C at ThinkSmart, with the tested USB-A/adapter path at the Mac. The previously tested C-to-C path did not enumerate ThinkSmart even though it worked with a Samsung phone. The underlying compatibility cause is not established.

After a cold boot or power loss, use the sequence verified on 2026-10-30:

1. Leave ThinkSmart's USB data cable disconnected.
2. Connect only power and wait for the PinePi desktop to finish loading.
3. Connect the A-to-C data cable with USB debugging enabled. Allow the accessory on the Mac if prompted.
4. Run `adb devices -l` and confirm `HUA0BQMQ` is listed as `device` before installing or debugging.

If the device is not detected, disconnect USB and power, wait about ten seconds, then repeat the sequence above. Do not cut power during installation, updates, or important writes. This is an observed recovery procedure, not a confirmed diagnosis of a controller or USB role-negotiation issue.

A previously authorized Mac may not display another RSA prompt. Absence of a prompt alone does not prove failed authorization. If ADB lists no device, first check USB enumeration; investigate authorization when the state is `unauthorized`. Repeatedly revoking authorization or changing developer settings is usually unnecessary.

If needed, read `getprop sys.usb.state` and `getprop init.svc.adbd` in Termux on ThinkSmart. The successful values were `diag,adb` and `running`, but these do not prove host-side enumeration. See [Cold-boot USB recovery](docs/DEVICE_VALIDATION.md#cold-boot-usb-recovery-2026-10-30).

### Network ADB and device selection

The USB serial is `HUA0BQMQ`. Network ADB must first be enabled through authorized USB and may need enabling again after power loss or reboot. Device addresses change: `192.168.2.101` was used on October 29 and `192.168.2.108` on October 30. Check the current Wi-Fi address before using this historical example:

```sh
adb -s HUA0BQMQ tcpip 5555
adb connect 192.168.2.108:5555
adb -s 192.168.2.108:5555 shell getprop ro.build.display.id
```

This procedure was previously verified. A refused connection on port 5555 means USB must be restored before enabling `tcpip 5555`. Android Studio can then target `<current-IP>:5555`. Specify `-s` for every command if USB, network ADB, or another phone is also connected. USB can remain attached. Use this only on a trusted development LAN; `adb -s HUA0BQMQ usb` returns the device to USB mode.

### Known issues and maintenance limits

- Navigation buttons briefly flash during wake. The user accepts this as a low-priority issue; preserve system settings, other-launcher navigation, and the edge gesture instead of disabling SystemUI.
- Reliable ADB shutdown has not been verified. `reboot -p` actually rebooted this device, so an ADB disconnect must not be treated as successful shutdown. Holding both volume keys can trigger factory reset; do not use that combination to try to power off.

## Implementation history

The following records describe their respective versions. Later protocol and deployment decisions take precedence.

### Messages and visual design

- **0.7.9:** Replaced the header's Bluetooth status text with state icons and accessibility descriptions; the icon was subsequently reduced from 24dp to 18dp.
- **0.7.8:** Removed the text card's extra 28dp bottom padding, reducing total bottom space from 56dp to 32dp. A 24dp double down-chevron is vertically centered in the hint area. Tapping shows the Chinese Toast “请向上滑显示全部内容” (swipe up to see all content). Dragging and inertia still work; short messages and the end of a message hide the hint.
- **0.7.7:** Added a centered downward hint only when more text remains below. Its original fixed area was 28dp, with no overlap or layout jump. This version's non-clickable single-chevron design was superseded by 0.7.8.
- **0.7.6:** Changed arrival animation to a 700ms content fade-out, 150ms blank interval, and 1200ms fade-in. Only content fades; card background, position, and dimensions stay fixed.
- **0.7.5:** Removed upward movement. The old content faded out for 160ms, changed while transparent, and faded in for 200ms. Paging and media were paused during transitions. On-device recording and paging checks passed; local evidence: `hub/reports/fade-validation.json`.
- **0.7.4:** Increased the logo to 30dp and brand text to 17sp, with 36dp between title and body. The initial animation was a 320ms fade-in and 12dp rise, consumed once per new arrival, not on startup/history/settings return. Layout and animation were verified in `hub/reports/polish-validation.json`.
- **0.7.3:** Replaced the old 2048-byte body limit with 8192 UTF-16 units / 32 KiB UTF-8. Oversized messages are rejected with a reason, never silently truncated. A resent 2002-character / 5878-byte Chinese article was saved intact and acknowledged.
- **0.7.2:** Connected `Broadcast Mid → WSS → Hub → LAN WS → PinePi Home`. The subscription is `wss://hail.lonelyassistant.com/ws/`, device `pinepi-home-hub`, with its token stored privately on the Pi. Legacy `event.title/content` becomes silent text; heartbeat, backoff, ten durable pending notifications, and save-before-ACK were implemented. A real cloud message was displayed, saved, and acknowledged. The screen was already on; this did not repeat a standby wake test. See [Broadcast Mid](docs/BROADCAST_MID.md).
- **0.7.1:** Moved logo/brand to the upper left and position/page indicators to the lower left. Existing Compose `HorizontalPager` provides drag-following cards, snapping, short-drag return, no wraparound, and newest selection on arrival. Dragging stops video, retains decoded images, and does not preload adjacent media.
- **0.7.0:** Migrated Messages, Chat, and Settings to Compose + Material 3 with a fixed pine-green palette, warm light and subdued dark themes, consistent spacing and typography. Auto/Light/Dark selection persists; Auto is light from 07:00 inclusive to 19:00 exclusive, using device local time. Time/timezone changes and resume recalculate the theme; the app does not wake the screen to change it. A separate clock composable isolates second-by-second updates from media.

The Compose stack remains pinned to Kotlin 2.1.20 / SDK 35, Compose BOM 2025.04.01 (UI 1.8.0 / Material 3 1.3.2), and Activity Compose 1.10.1, with the matching Kotlin Compose compiler plugin and AndroidX. There are no dynamic wallpaper colors, network fonts, blurred backgrounds, or looping animations. Existing dependency/KTX/deprecated platform notices are non-blocking.

Images use bounded native decoding and a private HTTP client. Videos use system `VideoView` through `AndroidView`, require a user action, and stop when leaving the page or backgrounding the Activity. Diagnostics remains native Views. Hermes and the full conversational speech pipeline are still pending.

**0.6.0** introduced the three-page UI, equal-size normal-weight date/time, no wallpaper or Agent selector, ten arrival-ordered records in private `AtomicFile` storage, and equal-width clear/settings controls. Chat/Settings interactions were not forcibly interrupted by arrivals; screen-off arrival returned to Messages. The Chat page had a real scroll container but no fabricated replies or automatic recording; its recording action was disabled until the remote service exists. Image tests used synthetic private records and a temporary LAN server, with an 8 MiB bound and pre-decode checks/downsampling. Video's actual audio/video playback was not accepted in that round. The device clock was still wrong (2025); it was not changed by that release. The 26 unit tests and Lint passed; device checks covered history limits, paging, upgrade recovery, clearing, hidden diagnostics, image display, and failure states. Local report: `hub/reports/main-ui-validation.json`.

### Local audio, Bluetooth, and proximity

- **0.2.0:** Foreground `AudioRecord` at 16 kHz mono PCM16, WAV output, and `MediaPlayer` replay. Recording requires a user action and permission; granting permission requires another tap. Stop releases the microphone; cancel, backgrounding, and Activity recreation clean up the recording. The 30-second limit is a diagnostic parameter, not a frozen conversational rule. Files stay in private cache and leftovers are cleared on process startup; no upload or audio-body logging.
- **0.2.1:** Added `VOICE_RECOGNITION` versus `MIC`, precise peak percentage, average dBFS excluding the first 0.2 seconds, media volume, and system microphone-mute status. Below −45 dBFS is only a weak-input indication, not voice detection. A separate three-beep 660 Hz test uses the same player path without microphone access or overwriting recordings. Volume keys control media; the app does not raise system volume.
- **0.2.2:** Replaced misleading Bluetooth-blocked feedback with “playback has not started.” After cold start, keep the page foreground, connect and disconnect a phone to establish observed state, then tap playback again. Playback is not queued; only the diagnostic recording wait can continue after a detected disconnect.
- **0.2.3:** Ordinary MIC measured −48.6 dBFS versus −53.9 dBFS for voice recognition and became the provisional default. Optional, initially off `LoudnessEnhancer` provides up to +24 dB for one positive app playback session ID. It does not bind to the global session, affect Bluetooth/test tones, modify WAV data, change volume or microphone settings, or improve the original signal-to-noise ratio. Stop/cancel/leaving releases it; failure falls back to original playback. [Android reference](https://developer.android.com/reference/android/media/audiofx/LoudnessEnhancer).
- **0.2.4:** A near-full-scale startup pulse around 35ms led to 200ms discarded warm-up and 20ms fades at both effective PCM boundaries. Recording begins after the preparing state; average levels exclude warm-up, and the time cap includes it. The user confirmed the pop disappeared and enhanced volume was acceptable, though muddy. Clarity, raw capture strength, and STT quality remain unaccepted.

Bluetooth uses public API 27 aggregate adapter connection broadcasts, not a precise A2DP-sink playback query. A fresh process with Bluetooth on reports unknown until a phone connect/disconnect is observed. Unknown/connecting/connected states block local audio; missing broadcasts do not mean disconnected. The app does not disable Bluetooth, scan, force disconnections, or use hidden profile APIs.

In 0.2.4, manual cancellation, opening Settings during capture, phone Bluetooth reconnection, and cancellation of a disconnect wait all stopped capture and cleared files without unintended later recording. Canceling enhanced replay stopped sound and released audio focus, effects, and files. Preparation-time races, replay interrupted by Settings/Bluetooth, rotation, screen-off, the cap, and stress combinations were not all validated.

The `VCNL4200 PRX` sensor repeatedly reported near=0.0 and far=5.0 in foreground diagnostics. **0.3.0** added an initially off, persistent proximity-wake switch and a separate silent-notification foreground service. It triggers on a far-to-near edge while non-interactive, with 1.5-second cooldown; continuous obstruction does not retrigger. API 27 screen wake locks are released immediately, with a one-second timeout fallback. Disabling removes the listener and notification. It does not start recording/playback or force an Activity, and it restores the enabled service on Home resume without an added boot receiver. [Background execution](https://developer.android.com/about/versions/oreo/background), [PowerManager](https://developer.android.com/reference/android/os/PowerManager).

On-device OFF→ON, the wake log, user confirmation, switch-off cleanup, and no residual lock were verified; 15 unit tests and Lint passed. The user confirmed proximity wake after power cycling on October 30. Deep standby remains unverified. Permissions evolved from Bluetooth/recording to normal `WAKE_LOCK`, then `INTERNET` and `ACCESS_NETWORK_STATE`; no external-storage permission was added.

**0.3.1** applied window immersive flags earlier in creation/resume while retaining `IMMERSIVE_STICKY` and focus restoration. Neither that nor a package-specific `policy_control` trial eliminated the navigation flash; the system rule was removed and the previous state restored. System settings/Launcher 3 navigation and Home return were verified. Fifteen tests and Lint passed. The precise trigger remains unresolved. [Navigation reference](https://developer.android.com/training/system-ui/navigation).

### Hub foundation and discovery history

The initial Hub ran on Pi 5B / 8 GB / Debian 13 / aarch64 / Python 3.13.5, SSH `alan@192.168.1.50`, advertising `192.168.2.109` on wlan0 and listening on `0.0.0.0:8766`. systemd startup/recovery/manual operations were validated; real whole-Pi reboot and terminal recovery passed on October 30. The current deployment moved to Pi 4B at `192.168.31.36`; see [Hub service](hub/README.md) and [migration](docs/HUB_MIGRATION.md).

**0.4.0** implemented the approved LAN fixed-text test loop with OkHttp 4.12.0, heartbeat, bounded reconnect backoff, and a foreground connection service. The trusted LAN uses WS/HTTP without certificates. Initial separate device/control tokens were stored through ADB stdin in private debug-app configuration (0600), never embedded in APK/logs. Android 18 tests/Lint and Hub 10 tests passed. Device checks covered text display, message-driven wake with proximity disabled and no microphone activity, Hub restart, and Wi-Fi loss/recovery. Local evidence: `hub/reports/android-link-test.json` and `android-link-test.png`.

**0.5.0** added Android NSD and Hub systemd-scoped `_pinepi-hub._tcp` advertising. First use displays the Hub name/address and a confirmation button; no socket connects before consent. Selection persists by `hub_id`, so endpoint changes do not require another confirmation, while a different identity does. The app does not silently choose another Hub if the selected one disappears, read the old `hub-test.json`, or send a device token in trusted-LAN discovery mode. Keep the private Hub identity during migration; clearing app data removes consent. The identity remembers a choice and is not authentication. Hub 14 / Android 21 tests passed.

All user-facing arrivals wake an already-off display, regardless of priority or the optional Hub wake instruction. This does not override speech/notification/recording rules. Fixed-message wake was verified in 0.4.0; formal message and long/deep standby acceptance must be tracked separately.

## Repository layout

```text
android/
  app/src/main/
    AndroidManifest.xml
    java/com/pinepi/thinksmart/launcher/
      MainActivity.kt          # Compose state, services, system entry points
      HomeScreen.kt            # Three Material 3 pages and media presentation
      HomeTheme.kt             # Pine-green light/dark colors and typography
      DiagnosticsActivity.kt   # Original hardware diagnostics
      SystemNavigation.kt      # Settings, other launchers, ROM fallbacks
      LauncherApplication.kt   # Process-level Bluetooth and cleanup
      BluetoothMonitor.kt      # Public broadcasts and conservative state
      LocalAudioSession.kt     # Foreground diagnostic capture/replay/cleanup
      MessageAudio.kt          # Cancelable message audio and local prompts
      HomeMessageProtocol.kt   # Strict v2 decoder
      ProximityWakeService.kt  # Optional sensor service and short wake request
      ProximityWakeGate.kt     # Edges, obstruction, and cooldown
      WavFile.kt               # PCM16 WAV output
    res/                       # XML layouts, themes, icons, and strings
  gradle/wrapper/               # Wrapper with distribution SHA-256 verification
docs/                          # English primary and Chinese documentation
hub/                           # Service, transport tests, locked dependencies, systemd
```

## Next steps

Preserve the accepted audio cancellation/release, proximity wake, and recovery behavior; track weak/muddy capture and long standby in [Device validation](docs/DEVICE_VALIDATION.md). The Baidu short-text loop and fixed prompts are connected; next implement the approved speech pipeline with short-recording upload, STT, Hermes, permitted TTS, cancellation, and output-policy checks. Evaluate recognition using real recordings. Keep navigation flash at low priority. A successful build alone does not validate device capabilities.

## Repository and local-only files

The repository contains Android/Hub source, tests, dependency definitions, and documentation. Raw device reports, screenshots, and recordings under `hub/reports/` can contain real notifications and remain local and untracked. References to those paths identify local evidence, not attachments distributed with the repository.

SDK paths, IDE state, build products, virtual environments, signing files, and runtime credentials are not committed. Actual `link-test.json`, `broadcast-mid.json`, `baidu-tts.json`, and `runtime.env` stay private on deployment machines. Credential-free `.example` files may be committed.

Toolchain reference: [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes). Home declaration: [Android Intent](https://developer.android.com/reference/android/content/Intent#CATEGORY_HOME).
