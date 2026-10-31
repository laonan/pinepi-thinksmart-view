# Hub migration record — 2026-10-30

**English** | [中文版](HUB_MIGRATION.zh-CN.md)

Destination: `alan@192.168.31.36`, host `blue62a`, verified Raspberry Pi 4 Model B Rev 1.1, Debian 12 / aarch64. The old host at `192.168.1.50` was unreachable; its private configuration was not read and its service was not stopped. The Mac project's `hub/` directory supplied the source.

## Runtime and configuration

- System Python 3.11.2 was left unchanged. uv 0.13.6 in the user's home installed isolated CPython 3.13.18.
- uv: `/home/alan/.local/share/pinepi-bootstrap/bin/uv`; managed interpreter: `/home/alan/.local/share/uv/python/`. Keep these paths for runtime operation.
- Project: `/home/alan/pinepi-thinksmart-view/hub`, dedicated `.venv`, dependencies from `requirements.lock`; `pip check` passed.
- Service: `/etc/systemd/system/pinepi-hub.service`, User/Group `alan`, enabled and active, independent of SSH.
- Arguments: `/etc/pinepi-hub/runtime.env`; example: `hub/deploy/runtime.env.example`.
- Arguments used: `--host 0.0.0.0 --discover --discovery-interface eth0 --link-test-config /home/alan/.config/pinepi-hub/link-test.json`.
- Private JSON files use 0600 and their directory 0700. A new UUID was generated for this migration and explicitly confirmed on ThinkSmart. Control tokens were not put in source, migration archives, or reports.
- `eth0` held both `192.168.31.36` and `.37`. Independent mDNS browsing confirmed both advertisements used the same UUID and port 8766. Host interface configuration was not changed.
- ThinkSmart's Wi-Fi address in this round was `192.168.31.186`; no manual Hub address entry was needed.

Initial deployment sequence: copy source only → create isolated Python/venv → install locked dependencies → install project → test → generate private configuration → install systemd unit/arguments → `daemon-reload` → `enable --now` → recovery tests → terminal confirmation and fixed-message check.

Future migrations should carry the private JSON to preserve identity and adapt usernames, project paths, and interfaces. Recreate the venv on the destination instead of copying the old `.venv`. A new identity requires another terminal confirmation. Do not run the same identity on two Hubs for sustained operation.

## Verified results and limits

Health, enabled configuration, crash restart, persistent manual stop, and subsequent start recovery passed. The new Pi was not rebooted in this round; boot configuration does not prove an actual cold-start recovery.

Independent mDNS browsing passed. The control endpoint showed zero connections before confirmation and one afterward. Fixed message `f00e486614f34223bdd47d9998d878c6` was sent once and received a matching ACK with `wake_requested=false`. This establishes delivery/acknowledgement, not a screen-off wake test.

The final migration suite recorded **13 passed / 1 failed**. Synthetic HTTPS/WSS handshakes timed out, including an isolated rerun; enabling diagnostic logging once passed. The cause was unresolved. Timeouts were not increased and the failure was not skipped/suppressed. The agreed LAN HTTP/WS path passed; this round alone cannot accept public TLS/upstream integration. The existing Starlette/httpx deprecation warning was also retained.

USB ADB enumerated, but `input keyevent KEYCODE_WAKEUP` did not return promptly and the task's check process was stopped. The user performed terminal confirmation manually. Acceptance relies on the matching Hub ACK, not an unobserved UI or screen state.

Local evidence: `hub/reports/service-migration.json`, `pytest-migration.xml`, `migration-discovery.json`, `hub-migration-link.json`, and `migration-source-hashes.json`. Raw reports stay local and are not repository attachments.
