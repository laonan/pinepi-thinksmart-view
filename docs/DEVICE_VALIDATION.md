# Android 8.1.0 launcher — device validation

**English** | [中文版](DEVICE_VALIDATION.zh-CN.md)

**0.8.1 update (2026-10-31):** Baidu authentication and real synthesis passed. Four fixed Chinese announcements are bundled; the user confirmed all four local prompts and a full Hub-generated text narration. Original ten messages were restored, test records removed, and Bluetooth readiness re-established by the user. See the 0.8.1 entry below; the 0.8.0 limitations remain historical evidence.

**2026-10-31 update:** Android 0.8.0 / code 22 is installed and the Pi Hub supports message protocol v2. All five variants passed reception, persistence, ACK, duplicate-ID, and upgrade-restoration checks; image URL download/display passed. Baidu credentials are absent and four fixed prompts are not generated. Unknown Bluetooth state blocked audio/TTS/video sound, so actual playback was not accepted in this round. See [Message protocol and usage](MESSAGE_PROTOCOL.md).

The dated records below retain their historical scope. By October 30, 0.7.8 included the tighter bottom area, double-chevron, and Toast; earlier releases introduced scroll hints, content fading, typography, long text, real Mid notifications, paging, Compose themes, three-page UI, discovery/consent, and the fixed-message LAN loop. Default Home, settings/original launcher access, basic capture/replay/cancel cleanup, and proximity wake have evidence. Weak/muddy capture, navigation flash, deep standby, extended operation, and the full AI voice pipeline remain qualified or pending.

## Local build checks (2026-10-29)

- `:app:assembleDebug` produced `android/app/build/outputs/apk/debug/app-debug.apk`.
- `:app:lintDebug` passed with no issues. The fixed old-target publishing check is explicitly disabled for the appliance baseline in `app/build.gradle.kts`.
- `aapt` checked initial package `com.pinepi.thinksmart.launcher`, version 0.1.0, min/target 27, `HOME + DEFAULT`, and `LAUNCHER`. Initial 0.1.0 had no permissions; 0.2.0 added `BLUETOOTH` and `RECORD_AUDIO`.
- `apksigner verify --verbose` passed with debug signing and valid APK v2 signature.
- Android Studio imported `android/` using JDK 17, synchronized Gradle, and created `app`. Run on `QUALCOMM msm8953_32` built in about 12 seconds and installed in about three. ADB confirmed `.MainActivity` in foreground.

These checks alone do not validate the actual display, ROM behavior, default Home selection, or device audio.

## Device record

USB and network ADB were established on October 29. Do not infer ROM identity from the product name.

```sh
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell getprop ro.build.fingerprint
```

| Field | Recorded value |
| --- | --- |
| Device | ThinkSmart View; ROM `ro.product.model=msm8953_32`; USB product/device `msm8x53_som` |
| Android / API | ADB-verified 8.1.0 / 27 |
| ROM build | `Lenovo_Kingston_OS_PV_220620_V0.0.40`; preinstalled, source/modifications unknown |
| Fingerprint | `qcom/msm8953_32/msm8953_32:8.1.0/OPM1.171019.026/penghu06200907:user/test-keys` |
| Original Home | UI name Launcher 3; package/version still to record |
| Historical APK baseline | 0.4.0 / code 9 at this record; initial 0.1.0 Home acceptance completed; later versions below |
| Initial validation | 2026-10-29, partial |

## Initial acceptance procedure

### ADB investigation (2026-10-29)

The same Mac port/C-to-C cable enumerated a Samsung phone through USB/ADB with working MTP. This did not prove a bad cable or ThinkSmart compatibility. ThinkSmart `192.168.2.101` and Mac `192.168.2.111` exchanged all three pings; port 5555 refused connection. Termux `su -c id` found no usable `su`.

User-provided terminal photos showed `sys.usb.config=diag,adb`, `persist.sys.usb.config=diag,adb`, `init.svc.adbd=running`, empty `service.adb.tcp.port`, `ro.debuggable=0`, and `ro.build.type=user`. ADB configuration/service state did not prove host enumeration or require root; actual USB state/gadget/path still needed checking.

The user subsequently confirmed `sys.usb.state=diag,adb`. Switching to the A-to-C path enumerated Android as `HUA0BQMQ device`. This implicated path compatibility without establishing exact Type-C negotiation/electrical causes. `adb -s HUA0BQMQ tcpip 5555` set the property and opened the port. After restarting Mac ADB, `adb connect 192.168.2.101:5555` worked and read the build. No root or ROM flashing occurred. Network ADB was validated only for that boot; persistence across reboot was not established. Select a target with `-s` when both transports exist.

### Launcher acceptance

- [x] Install and launch from the original Home without crashing.
- [x] Display Home and scroll to settings/maintenance.
- [ ] Verify device baseline text and unsupported-system warning outside 8.1.0.
- [x] Select PinePi Home under default apps/Home app.
- [ ] Verify default-Home status text refresh after returning.
- [x] Home returns to PinePi after selection.
- [x] Repeated Home presses do not create duplicate pages (user confirmed).
- [ ] Back stays on Home and maintenance entries remain reachable.
- [ ] All Wi-Fi/Bluetooth/system/default-Home entries and missing-page fallbacks work.
- [x] Open system settings and default-app pages.
- [x] List/open original Launcher through the other-launcher entry.
- [ ] Canceling launcher selection preserves Home.
- [ ] Switch default back to the original Launcher and then PinePi.
- [x] Boot enters PinePi (user confirmed), preceded by a brief original-launcher flash. Default-Home behavior passed; startup transition remains improvable.
- [ ] Restore full-screen after sleep/settings return, retaining temporary edge-revealed system bars.

The original-launcher flash is not proof of failed default Home. Its trigger needs boot/configuration evidence; do not disable the original launcher to conceal it.

### Initial sleep/wake observations (2026-10-29)

Initially the user could wake only with volume buttons; touch/proximity wake was not accepted then. The requested proximity feature required sensor tests followed by screen-off listening/wake, not merely foreground events. Power disconnect/reconnect verified boot entry. Lenovo documents holding both volume buttons as factory reset; do not use it as a reboot test.

- [ ] Verify system-following rotation and access to all settings; record actual ROM orientation behavior.
- [ ] Preserve Bluetooth music and accurate 0.2.0 connection-state feedback, without treating unknown as disconnected.
- [x] No microphone use at the 0.2.0 post-install check: `appops ... RECORD_AUDIO` returned `No operations`.

## 0.2.0 local audio and sensor validation

Implementation: foreground AudioRecord 16 kHz mono PCM16 WAV and MediaPlayer replay; stop releases the microphone, cancel/leaving removes the current recording, retained recordings can be replaced, diagnostic cap 30 seconds. Public aggregate Bluetooth connection broadcasts conservatively gate capture, without hidden APIs or claiming precise initial A2DP-sink state.

Build, unit tests, and Lint passed. WAV tests use an independent JDK Java Sound parser to verify format and positive/negative PCM samples.

Read-only device checks found `A2dpSinkService` disconnected, without making its private state available to an ordinary APK. `VCNL4200 PRX` advertised wake-up support and registered a foreground listener; this did not prove screen-off wake. Installation/startup and recording/wait/cancel controls were visible in MainActivity.

- [x] Phone connect/disconnect changes state (user confirmed Bluetooth works).
- [ ] Cold-start unknown, connecting, and connected all block capture.
- [x] Canceling a disconnect wait, then disconnecting, does not record; 0.2.4 retest stayed cleared with disabled finish/replay and no new microphone activity/cache.
- [ ] First permission grant still requires a new start tap; denial does not crash/capture.
- [ ] Speak for 3–5 seconds, finish, check time/levels, and clearly hear replay.
- [x] Cancel capture: cleared/disabled replay, no input descriptor, empty monitor/cache (0.2.4).
- [x] Open Settings during capture: no active input/cache there; Home return stays cleared (0.2.4).
- [x] Cancel enhanced replay: immediate stop, released focus/effects, empty cache (0.2.4).
- [ ] Replay interrupted by Settings releases playback/focus/enhancement; rotation/lock boundaries separately pending.
- [x] Reconnect phone Bluetooth while recording: stops/clears; input appears then disappears, monitor/cache empty (0.2.4).
- [ ] Reconnect Bluetooth during replay: stop/cleanup immediately.
- [ ] Reach 30-second cap, release microphone, replay, and repeat/cancel without stale results.
- [x] Repeated foreground near=0.0 / far=5.0 with fresh ADB events (0.2.4); screen-off results below.

At 0.2.0, recording quality, actual speaker playback, all ROM broadcasts, and resource release after real recording were still pending. No Hub/audio upload existed at that time.

### Weak capture and 0.2.1 comparison (2026-10-29)

The user heard only a quiet beep, with microphone switch unmuted and input 0%–2%. System records showed complete replay to the built-in speaker, volume 5/15; this did not isolate volume as the cause.

In-memory-only WAV statistics: 16 kHz, mono, 16-bit, 5.56 seconds; first-second peak 29683, later peaks about 727/755/291/426/6. The container parsed, with a startup transient and weak subsequent signal, but no proof that later content was useful voice. No raw file was saved, auditioned, transcribed, or sent externally. MIC routed to `handset-mic`; vendor calibration warnings were not established as causal and ROM/drivers/calibration were unchanged.

Version 0.2.1 built/installed/launched; four tests covered WAV, mean level, transient exclusion, and three-tone spacing; Lint passed. It added MIC/VOICE_RECOGNITION, exact peak/dBFS, volume/mute readings, and three beeps, without automatic gain/volume/recording. Next manual check: hear the beeps, record the same sentence on both inputs, compare mean level/clarity and finish replay before switching. Installation did not prove a fix.

### Misleading “silent” test feedback (0.2.2)

The user heard no 0.2.1 speaker test at volume 11/15 with system mute false. Current PID was 5980, but focus/playback records belonged to old PID 5583. The UI showed idle microphone and Bluetooth wait: the new tone had not started. This was not evidence of speaker failure.

The reused recording-wait text falsely implied queued playback and pointed to a disabled cancel button. Version 0.2.2 introduced explicit “not started” feedback, disconnect-then-tap-again instructions, no automatic playback, and visible Bluetooth status. Build/four tests/Lint passed; capture quality and listening remained pending.

Later, after confirming Bluetooth disconnected, the user heard all three tones: independent speaker output passed. VOICE_RECOGNITION recorded 5.2 seconds at −53.9 dBFS excluding the first 0.2 seconds. Zero current input after finish was expected. Logs confirmed source 6 / `voice-rec-mic`. Clarity and same-condition MIC comparison remained pending.

### Both inputs weak; enhanced replay (0.2.3)

Voice-recognition voice was audible but very quiet. MIC averaged −48.6 dBFS, about 5.3 dB higher, but neither passed quality acceptance. Bluetooth music was briefly reported quiet, then acceptable with another song; it held focus at volume 6/15. No global output fault or volume/routing/vendor change was inferred.

`/vendor/lib/soundfx/libldnhncr.so` was loaded. Version 0.2.3 selected MIC provisionally and added initially off, per-session enhancement up to +24 dB. Original WAV/levels remain unchanged, permitting original/enhanced comparison. No global effect, tone/Bluetooth processing, or global audio-control permission. Build/four tests/Lint/install/start passed; actual gain/noise acceptance remained manual and raw weakness unresolved.

### Startup pop (0.2.4)

Enhanced output was a little louder but the opening pop was severe. In-memory analysis of a 4.52-second test found peak 29196 at ~35.1ms: zero first 20ms, strong 20–60ms transient, decay by ~120ms. Excluding 200ms left peak 1041, about 29 dB lower. No audition/transcription/Mac audio copy occurred.

A `STARTING` state discards 200ms warm-up before the recording UI state. Effective PCM receives 20ms fades at both ends; WAV duration/header reflect effective frames and the cap includes warm-up. Optional gain remains +24 dB/off by default. Eight tests covered startup-pulse removal, read-chunk invariance, no effective warm-up frames, tail fade, and duration. Build/Lint/install/start passed. Preparation-time cancel/Bluetooth cleanup still needed separate tests.

The user then confirmed the pop was gone: that issue passed. Evidence localizes it to a PCM capture-start transient, not specifically ADC/DSP/HAL/ROM initialization or speaker damage. Weak capture, ideal enhanced volume/clarity, and preparation races were not all resolved.

### Enhancement feedback and baseline (0.2.4)

Before comparison: ~4.4 effective seconds, −42.9 dBFS excluding warm-up, volume 7/15, enhancement unchecked and original replay used. Comparing the same segment with enhancement, the user reported acceptable volume but muddiness. No later effect/volume reread was claimed.

Keep MIC, 200ms discarded warm-up, 20ms boundaries, optional +24 dB. Do not increase gain or claim a known source of muddiness among capture, room, effect, and speaker. Enhancement does not change the WAV or prove STT quality. Preparation cancellation, Bluetooth interruptions, leaving, and rapid repetition need independent validation.

### Stop and cancellation (0.2.4)

Manual cancel cleared capture/disabled replay; `media.audio_policy` Inputs, RecordActivityMonitor, and cache were empty. Opening Settings during capture also left no input/cache; Home did not restore the clip. Phone Bluetooth reconnection made input appear then disappear and cleared state.

An initially confusing multi-step instruction was not treated as a failed cancel. The retest separately entered the connected-phone wait, canceled and confirmed cleared, then had the user disconnect only. UI remained idle with finish/replay disabled, microphone usage aged without a new entry, and monitor/cache stayed empty.

The user started enhanced replay and canceled about three seconds in; sound stopped immediately. Focus appeared then disappeared, effect chains went 0→1→0, and cache cleared. These manual paths do not cover very short preparation races, rotation/lock, cap, or stress. No code/build/install changed in this round, and recording/playback were user-triggered, not automated; audio contents were not read.

### Foreground proximity (0.2.4, 2026-10-29)

The user requested sensor testing before further audio/Hub work. Device was Awake/ON with PinePi foreground. `VCNL4200 PRX` had wake-up=true, listener registration, reading/range 5.0. Repeated hand approach/withdrawal produced 0.0/5.0; ADB captured 14 fresh events (seven 0→5 pairs).

This did not measure distance or prove screen-off delivery/wake. Activity foreground registration/onPause removal remained unchanged. APK stayed 0.2.4; no automatic continuation was scheduled at round end.

### Screen-off proximity wake (0.3.0, 2026-10-29)

The user then requested same-day implementation. An initially off persisted switch controls a separate silent-notification foreground service and wake-up sensor listener. Disable removes both; Home resume restores an enabled service. Far→near only, not initially covered/continuously near/already interactive; withdraw to reset, 1.5-second cooldown.

Seven new tests made 15 total; build/Lint passed. A localized `ImplicitSamInstance` note explains that Android matches `stopService(Intent)` by component, not Intent instance identity. API 27 uses `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, `acquire(1000)`, then immediate finally release. No continuous CPU/screen lock, hidden API, timeout change, recording/playback, or forced Activity; only normal `WAKE_LOCK` was added.

Install/start passed. Enabled service was foreground with its own listener. After ADB `input keyevent 223`, Dozing/OFF and active listener were confirmed. User withdrew/approached and confirmed wake; ADB observed Awake/ON plus `Proximity requested display wake`. This passed ordinary screen-off wake, not deep standby.

Disabling removed service/sensor connection, leaving only foreground diagnostics; then re-enabled with no residual `PinePi:ProximityWake` lock or new microphone use. `mStayOnWhilePluggedInSetting=7` was only a configuration value; the user observed actual screen timeout, and it was not changed. Long standby, natural-timeout repetitions, and reboot were still pending at that point.

### Message wake policy (2026-10-30)

The user confirmed proximity wake after restoring power. This is user evidence, not an independent ADB check of switch, restoration timing, or sleep depth. Every user-facing message should wake an off display irrespective of importance. Wake does not grant sound/recording permission. Fixed-message results cannot substitute for all business variants:

- [ ] Ordinary screen-off message wakes and displays.
- [ ] Important screen-off message wakes and displays.
- [ ] Notifications and conversation replies follow the same rule; screen-on display remains normal.
- [ ] Reliable reception/wake after extended screen-off time.
- [ ] Display-only messages respect audio restrictions and never start the microphone.

These historical checklist items remain tied to that round; later specific standby/protocol evidence is recorded separately.

### Navigation flash (0.3.1, 2026-10-30)

User accepts the flash as low priority, without blocking Hub work. Preserve Home/Back in system settings/other launchers and edge-revealed navigation in PinePi.

0.3.0 restored immersive state only on focus. 0.3.1 applied the same window flags after layout creation and at resume, retaining focus restoration, without global SystemUI policy changes or repeated hide timers. Code 8 built; existing 15 tests/Lint passed; install and proximity service checks passed after USB recovery. User still observed the flash.

Package-specific `immersive.navigation=com.pinepi.thinksmart.launcher` also failed. `settings delete global policy_control` restored null/empty filters. A 20-second silent recording showed three left-side buttons during transition, then hiding; trigger remains unknown, and two failed attempts do not prove an APK-layer fix impossible.

- [x] Repeated wake comparison: neither approach eliminated the flash; unresolved.
- [x] Settings and Launcher 3 show navigation; Home returns to PinePi and stable state hides it.
- [ ] Manual PinePi edge-reveal acceptance. One synthetic swipe showed no transient bar, which neither proves success nor real-gesture failure.

### Shutdown attempts at day end (2026-10-29)

Historical record; development resumed October 30. `svc power shutdown` did not power off; the internal REQUEST_SHUTDOWN entry denied shell `SHUTDOWN`. Simulated power long-press opened the camera, immediately exited through Home without a photo.

`adb -s HUA0BQMQ shell reboot -p` disconnected USB/network ADB and was initially mistaken for shutdown. The user observed an actual reboot. **ADB shutdown did not succeed; this command is not a validated shutdown method.** The user chose direct power disconnection and work stopped. ADB disconnect alone cannot distinguish off from reboot.

### Initial Hub read-only inspection (2026-10-30)

After the user's `ssh-copy-id`, BatchMode login to `alan@192.168.1.50` worked. Model: Pi 5 Model B Rev 1.0, aarch64/64-bit, Debian 13 trixie, ~8 GB RAM, ~23 GB free root storage. Python 3.13.5 had venv/pip but no FastAPI/Uvicorn and no Docker on PATH. No installation/deployment occurred in that read-only round. Initial Mac ADB enumeration was absent.

### Cold-boot USB recovery (2026-10-30)

Cable/adapter/Mac port were unchanged after ThinkSmart power loss. Termux still showed `diag,adb`/running, while Mac USB/ADB saw nothing; restarting Mac ADB did not help. A Samsung phone worked on the same path with RSA prompt, focusing investigation on ThinkSmart.

The user disconnected ThinkSmart USB and power, waited, powered it alone to Home, then connected USB. Android enumerated as `HUA0BQMQ device`. The sequence worked without proving a controller/role-negotiation/ROM cause. Version 0.3.1 installed and foreground/proximity services worked. The newly supplied IP `192.168.2.108` refused 5555; network ADB had not been re-enabled.

### Hub foundation and systemd (2026-10-30)

Approved foundation tests/build and a system service only, not yet business routing/push/upload/STT/Agent/TTS/UI. Installed `python3.13-venv` lacked ensurepip; `dpkg -V` confirmed it and only that package was reinstalled. Dependencies were installed in `.venv` and locked in `hub/requirements.lock` without unrelated system upgrades.

Wheel/source builds, installed-wheel `pip check`, and seven tests passed: default-disabled diagnostics, Chinese/order, binary rejection, UTF-8 bounds, real HTTP/WS and HTTPS/WSS with four clients, reconnect, service after an oversize connection, and untrusted-certificate rejection. Local `hub/reports/pytest.xml`; existing Starlette/httpx warning was not suppressed. Temporary servers/certificates were removed. Persistent service had no echo diagnostics and bound only localhost at that stage.

Systemd unit at `/etc/systemd/system/pinepi-hub.service`: user alan, Restart=on-failure, enabled/active, multi-user.target, no persistent terminal required. An early health-query race in the verification tool was fixed with bounded readiness polling. SIGKILL recovery, manual stop staying inactive/unhealthy, and start recovery passed (`hub/reports/service-validation.json`). Whole-Pi reboot was not performed in that round; healthy foundation did not prove Android delivery/wake.

### LAN fixed-message loop (0.4.0, 2026-10-30)

Approved existing Hub area for state/latest fixed text and trusted WS/HTTP without local certificates, public WSS/HTTPS unchanged. Pi bound `192.168.2.109:8766`. Separate random device/control tokens stayed in private Pi files; Android configuration was injected via stdin into debug private storage (0600), not source/APK/logs.

Android 18 tests/Lint and Hub ten tests passed; version 0.4.0 installed with foreground HubTestService heartbeat/reconnect. First text sent=1, displayed correctly, matching ACK, `wake_requested=false` while already on.

With proximity disabled and only Hub service, ADB confirmed Dozing/OFF. Message `0106bfc22e6d49c3ad4c020dd5e7ec41` ACKed wake=true, Awake/ON, empty RecordActivityMonitor: message wake independent of proximity and microphone. Hub restart went 0→1 clients; `dc37141a0dab482a83466bcd2342197f` ACKed. Wi-Fi off/~three seconds/on reconnected; `21babaa9b1034623a1e6c2bd51dc3041` ACKed/displayed.

Wi-Fi/proximity were restored; both foreground services ran without a residual HubMessage lock. Local `hub/reports/android-link-test.json`/`.png`. This was automated ADB/UI/ACK evidence, not user acceptance of that test screen, deep standby, formal business, uploads, or cloud Agents.

## 0.5.0 discovery and first consent (2026-10-30)

Hub mDNS `_pinepi-hub._tcp` follows systemd lifetime, advertises persistent UUID/version/port/fixed path, and checks private addresses every 15 seconds. API 27 NSD resolves serially, rescans every 30 seconds/on network recovery, and opens no socket before consent. Selection persists by UUID; no silent failover to another identity or old injected address/token. Control token remains separate.

Version code 10 installed; the user personally saw/tapped confirmation, then one connection appeared. Zero connections before the first tap was not independently captured; code/unit gates provide that evidence instead. Upgrade/reopen, Wi-Fi restore, and Hub restart reconnected without reconfirming. Same UUID temporarily moved 8766→8767; terminal followed without configuration change and ACKed `fe4ca848bb41461fb1e8ad87d164078f`, wake=false. Physical DHCP change was not simulated; unit tests cover migration.

Port 8766/Wi-Fi/proximity were restored. `AF_NETLINK` was added after the initial systemd address-family restriction blocked interface discovery. Android 21 tests, Hub 14 tests, and Lint passed; necessary older NSD API deprecations and existing Starlette warning remain qualified.

Preserve `hub_id` in `/home/alan/.config/pinepi-hub/link-test.json`; clearing app data or generating an ID requires confirmation again. Deep standby, cross-VLAN discovery, actual reboot, formal business/cloud were not all tested at that point.

## 0.5.0 message wake after 30 minutes (2026-10-30)

Proximity temporarily off; Hub remained foreground, no touch or wake key. Before arrival: OFF/Dozing, unchanged `lastSleep=7873333`, `lastWake=5232554`, screen-off elapsed 2016805ms (33m36.8s), one connection, no proximity service.

Message `9ab004790589454abbeb3df7e9e8acbf` ACKed wake=true; afterward ON/Awake, `lastWake=9901471`, total 2028138ms (33m48.1s), visible fixed text. Proximity restored true with both services running. Evidence: `hub/reports/standby-30min.json`, `standby-30min-result.png`.

Accepted only ordinary powered screen-off standby exceeding 30 minutes. ROM `mLightEnabled=false`, `mDeepEnabled=false`; settings unchanged. This did not validate deep Doze. The one-off task was removed after completion.

## 0.5.0 whole-Pi reboot recovery (2026-10-30)

Authorized `sudo systemctl reboot` changed boot ID `30081c41-3ec7-4b0a-88bb-aca6a45160c4`→`7a3166a1-9f73-4fa5-a750-8752688503c0` and reset uptime. Boot logs: service start 15:58:55, ready/terminal connected 15:59:02. Enabled/active without manual service start, terminal restart, or renewed consent.

One connection; message `37716eef2fa64dfc93ad32bc5d1d7402` matching ACK, wake=false while already on. UI showed connected/fixed text; proximity remained on. Reboot/startup/reconnect passed (`hub/reports/rpi-reboot.json`). This was the original Pi, not the later migrated host.

## 0.6.0 main UI (2026-10-30)

Code 11 installed: Messages/Chat/Settings; all original diagnostics moved to non-exported native DiagnosticsActivity, seven version taps, Back→Settings. API 27 native Views/window immersive flags and settings/original-launcher maintenance remained.

Full date/time, equal normal typography; no wallpaper/Agent selector/item close. Separate full-width text/image/video components; older-left/newer-right, no looping, count at bottom. Private AtomicFile keeps ten arrival-ordered records, drops oldest, restores restart. Clear action aligns with Wi-Fi/Bluetooth/system controls. Normal navigation does not delete messages; Chat/Settings is not forcibly interrupted, latest is selected on return; screen-off arrival returns to Messages. Formal recording interruptions were still undecided.

Fixed `test.message` supplied real input; no media wire protocol was invented. Synthetic images used a temporary LAN server, 8 MiB bound, pre-decode pixel checking, ≤1600-side downsampling and aspect fit. Leaving/background cancels downloads; late results cannot overwrite newer content. Video is tap-only, Bluetooth-gated, stops on exit, and never changes Bluetooth. A discovered Bluetooth-change-clears-image bug was fixed by resetting only affected video; prepared-video callbacks also gained generation checks.

Verified: 12 arrivals retain latest ten; upgrade restores ten; page counts, Chat return, same-width clear, empty on-disk history after clear, hidden seven-tap entry/return, image display, video entry/guard, video→text, image 404, synthetic-record cleanup. Android 26 tests/Lint passed. Local `hub/reports/main-ui-validation.json`, `main-ui-image.png`.

Chat had a real scrollable container/state but no Hermes/STT/TTS, disabled record, no fake replies, and no session restoration. Actual video/audio playback was not accepted. Device clock still read 2025; app did not change date/timezone/auto-sync. Earlier 30-minute evidence did not become a new UI-version standby result.

Short screen-off regression: Chat page, proximity off, ADB OFF, message `d449baafd55e467480dd933d68197612`, matching wake=true ACK, ON, latest Messages (1/1). Test server/records removed, original proximity enabled, both services verified. Screens: `main-ui-home.png`, `main-ui-chat.png`, `main-ui-settings.png` under `hub/reports/`.

Test isolation: force-stopping default Home can immediately relaunch it, racing private-file injection. Open system settings first, then stop/write/start. The revised procedure passed and is not evidence of ordinary message loss. The independent Bluetooth/image defect was fixed separately.

## 0.7.0 Compose/Material 3 and themes (2026-10-30)

Approved migration of three pages; native diagnostics unchanged. Fixed pine-green, warm light/subdued dark, consistent corners/spacing/type/icons, normal clock, no close buttons/wallpaper/network fonts/Android 12 dynamic colors. Clear aligns with settings.

Min/target 27, compile 35, AGP 8.9.2, Kotlin 2.1.20 unchanged. AndroidX and matching Compose compiler, BOM 2025.04.01 (UI 1.8.0 / M3 1.3.2), Activity Compose 1.10.1. Code 12 installed on 8.1. Lint: 0 errors, six unsuppressed notices (two dependency updates, four KTX suggestions).

Theme selection in private preferences. Auto light 07:00≤hour<19:00; boundary/manual-override tests passed. Foreground date/time/timezone broadcasts/resume refresh, with no alarm/theme wake lock. Separate clock composable avoids per-second media rebuild. Date was observed corrected to October 30; auto_time restored to 1 and Asia/Shanghai retained, without claiming power-loss clock repair.

Android 29 tests passed. Device checks: auto/day/manual light/dark screenshots, preferences, dark retained through upgrade/restart, Auto restore, equal clear widths, disconnected Chat record disabled, native hidden diagnostics/return, existing history, paging, short screen-off wake/latest return. Debug launch ThisTime 2052ms, later 1876ms, not Release performance/power benchmarks.

Compose images/system VideoView through AndroidView retain download/release/guard behavior. Background immediately cancels media/stops video/hides controllers; old callbacks cannot replace newer content. Synthetic media records were isolated and original history restored using Settings-before-stop. Hermes/STT/TTS and live media wire format were still pending then. Portrait, Release performance, long RTC/NTP and long standby were not covered. Local `hub/reports/compose-ui-validation.json`, `compose-*.png`.

References: [Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3), [Compose compiler](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping).

## 0.7.1 layout and card dragging (2026-10-30)

Logo/brand upper left; full normal-weight date beside it; position/count/dots lower left; Settings/Chat right. Existing Foundation 1.8.0 HorizontalPager provides finger-following cards, snap/short-drag return, no looping. Arrivals select item 1; Settings return preserves position.

Code 13 installed. Coordinates, full date, short drag, older/newer swipes, endpoints, Settings return, newest arrival passed. Silent recording showed moving current and adjacent cards. Local `hub/reports/pager-home.png`, `pager-during-drag.png`, `pager-drag.mp4`, `pager-ui-validation.json`. Android 29 tests, Lint 0 errors/six existing warnings. No Release frame benchmark.

Only settled card requests media. Dragging stops video, retains decoded image, and does not load/play neighbors. Synthetic records restored afterward; no formal media protocol or business-audio policy change. [Pager reference](https://developer.android.com/develop/ui/compose/layouts/pager).

## Power-operation review (read-only, 2026-10-30)

The user wanted one power icon opening shutdown/restart only if reliable. No button or new power command was implemented. Shell uid=2000, no `su`; REBOOT/SHUTDOWN are signature|privileged and not granted to shell. PinePi is an ordinary `/data/app` app, not device owner; default Home does not grant power privileges. This matches [Android 8.1 AOSP permissions](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/core/res/AndroidManifest.xml).

Together with the previous failed shutdown/permission denial/actual `reboot -p` restart, no reliable software shutdown exists in the verified privilege setup. Do not present a nonworking menu, claim hardware can never power off, or retry the misleading command. A future privileged/vendor interface needs actual physical-off validation. After stopping capture/playback/install/update/writes and waiting, unplugging power is direct power loss, not orderly shutdown.

## Migration to the new Pi (2026-10-30)

Moved to Pi 4B / Debian 12 / isolated Python 3.13.18 at `192.168.31.36`, eth0; enabled/active, crash/manual recovery passed. User confirmed new identity; terminal `.31.186` ACKed fixed message. Not a screen-off/cold-boot test. Synthetic HTTPS/WSS timeout remained (13 passed / 1 failed), cause unknown. Deployed/source SHA256 matched. See [Migration](HUB_MIGRATION.md); local `hub/reports/hub-migration.json`.

## 0.7.2 real Broadcast Mid and ADB recovery (2026-10-30)

WSS `wss://hail.lonelyassistant.com/ws/`, correct device `pinepi-home-hub`. Hidden prompt saved token with 0600; extra-hyphen typo rejected auth, corrected name received heartbeat/connected. Public certificate validation passed, LAN still certificate-free.

User-published connection-test text displayed Broadcast Mid source and actual body, persisted, Hub received=1, ID `mid_5b4f5347cdbc49f7a220f4eb0afd499c` ACKed wake=false. Already-on delivery is not standby wake acceptance. Subscriber lifetime, ping/pong, reconnect, configuration absence, bounds, ten pending records, ACK deletion, live forwarding have coverage: 22 passed / 1 deselected (existing local synthetic TLS failure); real public WSS independently verified. Android 29 tests, Lint 0 errors/six existing warnings.

USB shell timed out; reconnect lost enumeration; network 5555 refused. The documented power sequence restored `HUA0BQMQ device`, shell, install/start (ThisTime 2004ms). Original RSA authorization remained; no revoke/flash/root/browser APK. See [Mid setup](BROADCAST_MID.md). Local evidence: `broadcast-mid-status.json`, `broadcast-mid-terminal.json`, `pytest-broadcast-mid.xml`, `broadcast-mid-validation.json` under `hub/reports/`.

## 0.7.3 intact Chinese long text (2026-10-30)

Old 2048-byte limit rejected a real article (ignored=1). New limits: 8192 UTF-16 units, 32 KiB UTF-8 body, 64 KiB frame, 512 KiB pending/history; fixed test text still 2048 bytes. Oversize exposes last_rejection without body/token logs.

Code 15 and new Hub installed, cloud credentials unchanged. Resent article: 2002 characters, 5878 UTF-8 bytes, 54 newlines; both ends matched. ID `mid_1c611aca03a0401b97038215ef5ffb5e` ACKed wake=false; Hub received=1, ignored=0, last_rejection=null. Tests cover Chinese >old limit, 8192/8193 units, emoji pairs, ten long persisted/reloaded records >old 64 KiB, and rejection reason. Hub/LAN 25 passed / one deselected; Android 29 tests, Lint 0 errors/six existing warnings. Local `pytest-long-text.xml`, `long-text-terminal.json`, `long-text-hub-status.json`, `long-text-validation.json` in `hub/reports/`.

## Custom notification sources (2026-10-30)

Optional event.source is trimmed/forwarded; absent/blank falls back to Broadcast Mid, non-string/>100 UTF-16 rejected with a reason. Forwarding/legacy/emoji tests: Hub/LAN 27 passed / one deselected; local `hub/reports/pytest-message-source.xml`. APK unchanged. Real cloud publishing of that new field was left for the user's curl check, not falsely claimed as accepted.

## 0.7.4 brand, spacing, and arrival animation (2026-10-30)

Code 16 installed/launched (ThisTime 2272ms). Logo 30dp, brand 17sp, title/body 36dp measured 54px; full date visible. Untitled messages add no title gap. New arrivals consumed a 320ms fade/12dp rise using graphicsLayer to avoid media recomposition; manual paging remained.

Fixed arrival selected item 1; left swipe reached item 2. Silent frame samples showed internal green steady 38→entry 33→restored, plus top-boundary movement. No new arrival ID on ordinary startup/return. Android 29 tests, Lint 0 errors/six existing warnings. Local `polish-home.png`, `polish-arrival.mp4`, `polish-validation.json`. No recording, automatic sound, or Hub protocol change.

## 0.7.5 pure fade (2026-10-30)

Code 17 removed rise: keep old pager until 160ms fade-out, switch while transparent, 200ms fade-in. Startup/manual paging does not use arrival animation; gestures/media pause during transition. Recorded fixed arrival: green 37→27→restored, top/internal same-frame difference ≤2, supporting alpha rather than movement. Latest item 1/manual item 2 passed. Android 29 tests, Lint 0 errors/six existing warnings. Local `hub/reports/fade-arrival.mp4`, `fade-validation.json`.

## 0.7.6 slower content fade (2026-10-30)

User found the prior fade flash-like. Use 700ms out, 150ms blank, 1200ms in; alpha on internal Column only, fixed Surface/no position motion, arrivals consumed once, then gestures/media resume. Code 18 built; `hub/reports/cinema-arrival.mp4`, `cinema-validation.json`.

Recorded content green peak 245→38→207 (different text can have different peaks), 45 distinctly dim frames at 30fps; background green varied only 4 from compression. Latest item 1/paging passed. Android 29 tests, Lint 0 errors/six existing warnings.

## 0.7.7 scroll hint (2026-10-30)

A centered 24dp single down-chevron in a fixed 28dp area avoids covering text/layout jumps. Appears only after measurement when canScrollForward; short text hides, bottom hides, reverse scroll restores. Originally not clickable, accessibility “下方还有内容”. Hint area shares ScrollState through scrollable for bottom-origin drag/inertia. Initial non-scrolling hint area was fixed.

The old article was no longer in latest ten, so private temporary long/short records were used with backup/restore and merge of real arrivals, not new business notifications. Final checks passed short/long visibility, area-origin drag, end-hide/return, original ten restored, temporary records removed. Android 29 tests/Lint 0 errors/six existing warnings; 0.7.7 installed. Local `scroll-hint-validation.json`, `scroll-hint-long.png`, `scroll-hint-end.png` in `hub/reports/`. [NN/g reference](https://www.nngroup.com/articles/scrolling-and-attention/); this arrow is a product choice, not a mandatory Material rule.

## 0.7.8 bottom area, double-chevron, and Toast

Remove extra bottom 28dp padding, set area 32dp (total 56→32dp), center 24dp double-chevron, tap width 48dp. Toast: “请向上滑显示全部内容”; repeated taps replace and leaving cancels. Shared native scroll/click coordination retains dragging/inertia and cancels taps. Non-text padding unchanged.

Temporary long/short records used the same preservation strategy. Code 20 installed. Area measured 48px=32dp, icon center offset 0px, exact Toast seen. Short/long/end/reverse-scroll/area-drag checks passed; original ten restored. Android 29 tests/Lint 0 errors/six existing warnings. Local `hub/reports/double-hint-validation.json`, `double-hint-long.png`, `double-hint-end.png`, `double-hint-toast.png`.

### 0.7.9 Bluetooth status icon (2026-10-31)

Replaced text with a 24dp icon: primary-color connected with connection marks, ordinary disconnected, searching for changing state, slashed for off/unavailable. Unknown retains distinct accessibility text and is not called disconnected. contentDescription, monitoring, and audio protection remain. The icon was subsequently reduced to 18dp at the user's request.

Debug/Lint/diff checks passed; code 21 installed/launched. Screenshot verified the icon, date/Hub/settings layout. Every Bluetooth-state icon was not toggled through in that round.

### 0.8.0 message protocol v2 (2026-10-31)

Implemented authenticated Hub `/api/messages`, strict v2 validation/durable forwarding, complete Mid v2 event compatibility, optional dedicated `/media`; terminal TEXT/TTS, TEXT/PRIVATE, IMAGE/AUDIO/VIDEO, timestamps/MIME/URL checks, 256 persisted IDs, v2 ACK, cancelable audio download/playback, Bluetooth/diagnostics protection. Legacy v1 stays silent.

Local full Hub suite 63 passed, then two added documentation/static-directory tests passed (65 distinct tests). Android 31 tests, Debug build, Lint 0 errors/seven warnings (one optional prompt-resource lookup warning, others existing). Initial Pi deployment protocol/Mid regression 49 passed and service became active/reconnected; final synchronized regression later passed 51.

After the user's USB replug, non-streaming installation succeeded, 0.8.0/code 22. All five synthetic real Hub→terminal variants ACKed matching IDs and stored kind/mode/URLs. PRIVATE had no speech URL or read-body button. Temporary LAN image GET returned 200 and displayed with preserved aspect ratio; audio/video/narration controls appeared. Duplicate ID only ACKed, without adding/reordering history. Final APK update/restart restored all five variants.

Unknown Bluetooth blocked playback; Bluetooth was not disabled to bypass it. Synthetic audio-URL reception does not prove Baidu synthesis or actual audio/video playback. User has no Baidu credentials; the fixed-four-public-phrase generator exists, but real MP3 resources are absent, so local announcements remain silent.

Temporary media service stopped; all five synthetic records removed; original ten IDs verified restored, with deduplication retained. No real message deletion or Bluetooth-switch change. Complete protocol/examples are in [Message protocol and usage](MESSAGE_PROTOCOL.md), linked from both READMEs.

Raw paths in this record refer to local evidence in ignored `hub/reports/`, not downloadable repository attachments.


## 0.8.1 Baidu short-text round trip (2026-10-31)

User-created credentials were verified from a mode-0600 private file, then copied privately to the Pi. No credential value was printed or committed. Four Baidu voice-0 MP3 announcements were generated (text 2.016s, image/audio 2.052s, video 2.088s), bundled, and referenced by compile-time resource IDs. Android 0.8.1 / code 23 installed via non-streaming ADB; build, 31 unit tests, and Lint passed (0 errors / six existing warnings).

The Hub now accepts HTTP TEXT/TTS submissions without speech, validates before synthesis, sends only body text, caches MP3, and forwards a complete strict v2 frame. PRIVATE and non-text variants never call the provider; supplied audio URLs bypass synthesis. A slow provider does not hold private notifications behind its synthesis lock. Short text must be GBK-encodable and <1024 GBK bytes; oversize is explicitly rejected, not truncated. Long-text segmentation remains pending.

Pi configuration gained --baidu-tts-config, --media-directory and --media-base-url, preserving the existing arguments and a pre-change runtime backup. The service recovered healthy and the terminal reconnected. One generated narration cache file was 67968 bytes; the credential file remained 0600.

Five synthetic real Hub→terminal messages saved and ACKed: PRIVATE, IMAGE, AUDIO, VIDEO, then a TTS body generated by the Hub. The user established a phone Bluetooth connect/disconnect observation before testing and confirmed all five complete sounds. This accepts the four arrival announcements and ordinary-text narration, not primary audio/video playback. PRIVATE had no speech URL; TTS contained the Hub-generated URL.

Initial full Hub regression: 75 passed / one known synthetic HTTPS/WSS handshake timeout. That case passed an isolated rerun. After the final concurrency regression was added, the complete final suite passed 77 tests, with the existing Starlette/httpx deprecation warning retained; no timeout or test was disabled.

Test records were removed with Settings foreground and application stopped; original ten IDs were verified restored, merging any real arrivals and retaining deduplication. The user re-established Bluetooth readiness after restoration/restart. No extra test messages were sent afterward. Temporary image/video/audio fixtures were removed from the Hub; the generated public narration cache may be retained for reuse. News collection, scheduling, STT, Agent integration, and long-text synthesis were not claimed complete.
