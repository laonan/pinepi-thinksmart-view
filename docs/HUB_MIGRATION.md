# Hub 迁移记录 · 2026-10-30

迁移目标为 `alan@192.168.31.36`（主机 `blue62a`），实测型号 Raspberry Pi 4 Model B Rev 1.1，Debian 12 / aarch64。旧机 `192.168.1.50` 网络不可达，未从旧机读取私有配置或停用旧服务。Mac 项目 `hub/` 是本次代码来源。

## 运行环境与配置

- 系统 Python 3.11.2 保持不变；通过用户目录内 uv 0.13.6 安装独立 CPython 3.13.18。
- uv 路径 `/home/alan/.local/share/pinepi-bootstrap/bin/uv`；托管解释器位于 `/home/alan/.local/share/uv/python/`，运行时也必须保留。
- 项目 `/home/alan/pinepi-thinksmart-view/hub`，专用虚拟环境 `.venv`；按 `requirements.lock` 安装，`pip check` 通过。
- 服务 `/etc/systemd/system/pinepi-hub.service`，User/Group 为 `alan`，已 enabled / active，独立于 SSH 常驻。
- 运行参数文件 `/etc/pinepi-hub/runtime.env`，示例在 `hub/deploy/runtime.env.example`。
- 参数为 `--host 0.0.0.0 --discover --discovery-interface eth0 --link-test-config /home/alan/.config/pinepi-hub/link-test.json`。
- 私有 JSON 为 0600，目录 0700；本轮生成新 UUID，用户已手动在终端点“确认连接”。不将控制 token 写入源码、迁移包或报告。
- `eth0` 当前同时持有 `192.168.31.36` 和 `.37`，独立 mDNS 浏览确认两条广播具有同一 Hub UUID，均为 8766 端口；未擅自修改主机网卡配置。
- ThinkSmart 本轮 Wi-Fi 地址为 `192.168.31.186`，应用不需要手工写入 Hub 地址。

首次安装流程为：复制纯源码 → 创建独立 Python/venv → 安装锁定依赖 → 安装项目 → 测试 → 生成私有配置 → 安装 systemd 单元和运行参数 → `daemon-reload` → `enable --now` → 服务恢复测试 → 终端确认与固定消息验证。

后续迁移应携带私有 JSON 以保留身份，并按新机器的用户名、项目目录、网卡调整 systemd/运行参数；虚拟环境应在目标机器重建，不复制旧 `.venv`。若新建身份，终端需要再次确认。相同身份不可在两台 Hub 同时长期运行。

## 已验证与限制

服务健康、enabled 配置、异常退出自动重启、手动停止保持停止、再次启动恢复通过。未重启整台新树莓派，因此仅确认开机自启配置，不声称真实冷启动验收完成。

独立 mDNS 浏览通过；用户确认前控制接口显示 0 个连接，确认后 1 个。固定消息 `f00e486614f34223bdd47d9998d878c6` 发送 1 次，收到同 ID 回执，`wake_requested=false`；只据此确认消息到达与回执，不将本轮当作熄屏消息唤醒测试。

完整测试最终为 **13 passed / 1 failed**；失败为合成 HTTPS/WSS 握手超时，隔离复查仍失败，开启诊断日志时曾通过一次，未确定根因。没有增加超时、跳过或屏蔽此测试。当前用户确定的局域网 HTTP/WS 链路通过；公网 TLS 链路与上游对接仍不能据此验收。测试另有既有 Starlette/httpx 弃用提示。

USB ADB 枚举正常，但本轮 `input keyevent KEYCODE_WAKEUP` 命令长时间未返回，已终止自己启动的检查进程；终端确认由用户手动完成，验收依据为 Hub 同 ID 回执，没有伪称读到 UI 文字或测到屏幕状态。

证据：`hub/reports/service-migration.json`、`pytest-migration.xml`、`migration-discovery.json`、`hub-migration-link.json`、`migration-source-hashes.json`。
