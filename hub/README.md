# PinePi Hub 基础服务

当前已完成基础服务、局域网消息链路和用户批准的 Broadcast Mid 文本订阅/转发；终端主 UI 已实现。完整媒体协议、STT、Agent、TTS 尚未实施。业务逻辑和 UI 规划须经用户确认；基础测试与构建可自主进行。

当前部署（2026-10-30 迁移）：`alan@192.168.31.36`，主机 `blue62a`，Raspberry Pi 4B / Debian 12 / aarch64。使用隔离的 Python 3.13.18（uv 管理），系统 Python 3.11 不变；代码在 `/home/alan/pinepi-thinksmart-view/hub`，仍由 systemd 常驻，发现网卡为 `eth0`。该网口当前有 `.36` 和 `.37` 两个地址，广播同一新 Hub 身份；用户已在终端确认，固定消息及同 ID 回执已通过。详情见 [迁移记录](../docs/HUB_MIGRATION.md)。

迁移检查：依赖完整性通过；完整测试 13 passed / 1 failed（HTTPS/WSS 握手超时，诊断开启日志时曾通过一次，原因未定）。局域网 HTTP/WS、服务异常恢复、手动停止/启动及 mDNS 通过；不将 TLS 测试或真实整机重启验收标为通过。

Broadcast Mid 已接通：公网 `wss://hail.lonelyassistant.com/ws/`，设备名 `pinepi-home-hub`。独立设备 Token 在 `~/.config/pinepi-hub/broadcast-mid.json`（0600）；订阅集成于同一 Hub service。上游心跳和真实文本通知均已验证，终端 0.7.2 显示/落盘并返回同 ID ACK。配置、待送队列和验收范围见 [接入说明](../docs/BROADCAST_MID.md)。本轮相关与 LAN 回归 22 passed，原本地合成 HTTPS/WSS 1 项未参与；真实公网 WSS 已通过证书、心跳及通知收发验证。

基础阶段记录（2026-10-30）：RPi 5 / Debian 13 / Python 3.13.5 上 wheel 与源码包构建成功，依赖完整性检查通过，当时 7 项测试通过。systemd 服务已安装、enabled 且 active，异常自动重启、手动停止保持停止、再次启动恢复均通过。报告位于 `reports/pytest.xml` 与 `reports/service-validation.json`；后续消息测试已扩充到 10 项，见 `reports/pytest-link-test.xml`。测试客户端出现一条上游 Starlette 对 httpx 的弃用警告，当前测试通过，后续升级依赖时处理，不屏蔽该警告。

## Service 原则

Hub 必须作为 service 常驻运行。RPi 使用 systemd 系统服务 `pinepi-hub.service`，运行用户为 `alan`，开机自启、异常退出自动重启，独立于 Mac 和 SSH 会话。单元文件见 `deploy/pinepi-hub.service`。

无运行配置时，基础服务默认监听 `127.0.0.1:8766`，只提供 `GET /healthz`。首次在旧树莓派部署时通过 `/etc/pinepi-hub/runtime.env` 设置参数，监听 `0.0.0.0:8766` 并启用局域网消息测试及发现，wlan0 广播当前地址 `192.168.2.109`。用户确定局域网作为可信物联网网关网络，使用 WS/HTTP，不需要局域网证书；公网侧仍保持 WSS/HTTPS。健康检查只表示本进程可响应，不代表设备已连接或完整业务可用。

`--diagnostics` 显式启用临时 WebSocket 回显探针 `/_diagnostics/echo`，仅供合成数据测试；常驻 systemd 服务没有此参数。该探针不是未来设备消息协议，不能把回显能力当作主动推送或消息亮屏已实现。

## 构建与测试

目标为树莓派的 Python 3.13 / aarch64。若 Debian 的 venv 创建报告缺少 ensurepip，先核对 `python3.13-venv` 文件完整性；本机曾出现包已登记安装但模块文件缺失，重装该包后恢复。

首次解析开发依赖：

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
```

用已记录的依赖版本重建环境：

```sh
.venv/bin/python -m pip install -r requirements.lock
.venv/bin/python -m build --no-isolation
.venv/bin/python -m pip install --no-deps --force-reinstall dist/pinepi_hub-0.1.0.dev0-py3-none-any.whl
.venv/bin/python -m pip check
.venv/bin/python -m pytest -q --junitxml=reports/pytest.xml
```

真实传输测试临时生成测试证书，仅由本次客户端显式信任，验证 HTTP/WS 和 HTTPS/WSS、并发隔离、重连、二进制拒绝、大小限制和不受信证书拒绝。所有临时测试进程与证书均在测试结束时清理；没有向 Android 安装证书或开放正式网络入口。测试方法依据：[FastAPI WebSocket 测试](https://fastapi.tiangolo.com/advanced/testing-websockets/)。

## 树莓派 service 运维

实际路径为 `/home/alan/pinepi-thinksmart-view/hub`。安装完成后：

```sh
sudo systemctl status pinepi-hub.service
sudo systemctl start pinepi-hub.service
sudo systemctl stop pinepi-hub.service
sudo systemctl restart pinepi-hub.service
systemctl is-enabled pinepi-hub.service
journalctl -u pinepi-hub.service -n 30 --no-pager
curl --fail http://192.168.31.36:8766/healthz
```

普通健康与配置检查：

```sh
.venv/bin/python tools/verify_service.py --url http://192.168.31.36:8766/healthz
```

基础阶段维护测试（会杀死、停止并重新启动本服务，正式承载业务时需在维护窗口执行）：

```sh
.venv/bin/python tools/verify_service.py --url http://192.168.31.36:8766/healthz --exercise-lifecycle
```

该测试区分 systemd 的 enabled 配置、进程运行及健康响应；开机自启配置不等于已实测整机重启恢复。本阶段不为验证自启而重启树莓派，避免打断其他已有进程。

## 局域网消息与自动发现（Android 0.5.0）

这轮临时协议验证连接、接收、亮屏及重连，不作为正式业务协议冻结。0.4.0 当时 Hub 10 项测试、Android 18 项测试与 Lint 通过；自动实机记录及截图在 `reports/android-link-test.json`、`reports/android-link-test.png`。

接口均在显式 `--link-test-config` 模式下启用：

| 接口 | 用途 | 测试凭证 |
| --- | --- | --- |
| `WS /_test/device` | 终端接收测试消息并回送确认 | `--discover` 时无需认证；旧模式使用设备 token |
| `POST /_test/send` | 发送固定中文文本，不接受任意业务正文 | 控制 token |
| `GET /_test/status` | 查看连接数及最后一次接收确认 | 控制 token |
| `POST /_test/disconnect` | 仅供重连测试 | 控制 token |

当前 `--discover` 模式下，终端连接不使用认证，首次需在终端点击确认。开发控制接口仍使用 `Authorization: Bearer ...` 控制 token。旧的无发现测试模式保留独立设备 token，仅用于回归测试。配置位于树莓派 `/home/alan/.config/pinepi-hub/link-test.json`，权限 0600；不提交凭证。可直接在树莓派发送测试消息：

```sh
cd /home/alan/pinepi-thinksmart-view/hub
.venv/bin/python tools/link_test.py send --config /home/alan/.config/pinepi-hub/link-test.json
.venv/bin/python tools/link_test.py status --config /home/alan/.config/pinepi-hub/link-test.json
```

测试消息仅含 `type=test.message`、`version=1`、唯一 `id` 和固定 `text`；终端回送 `test.ack` 与 `wake_requested`。确认表示终端处理了测试消息并报告是否请求亮屏，不表示用户已阅读。没有持久离线队列或业务重放承诺。

Android 使用 OkHttp 4.12.0，前台服务持有连接，心跳 10 秒，重连退避上限 30 秒。NSD 使用 Android 8.1 可用的旧 resolve API（新 SDK 的弃用提示不表示 API 27 不可用），串行解析服务并每 30 秒重新扫描，网络恢复立即重新发现；停止服务会清除扫描、回调及连接。只接受 RFC1918 私有 IPv4、协议版本 1、固定路径及 `auth=none` 的广播，禁止重定向。旧的设备私有 `files/hub-test.json` 不再读取；无需 ADB 注入地址或凭证。

首次接入显示一个确认按钮，包含 Hub 名称和地址；确认前不建立 WebSocket，确认后保存 Hub UUID。已选 Hub 的 IP/端口变化不重复确认，其他 UUID 必须另点确认；已选 Hub 消失时不会自动转接其他 Hub。稳定 UUID 只是记忆用户选择，不是认证，符合可信局域网原则。

mDNS 类型 `_pinepi-hub._tcp.local.`，TXT 为 `id`（持久 UUID）、`v=1`、`path=/_test/device`、`auth=none`，不广播凭据。Hub 对每个选定网卡上的私有 IPv4 分别广播端点，每 15 秒检查地址变化并撤销旧地址；退出服务撤销广播。组播发现要求终端与 Hub 位于同一支持 mDNS 的局域网，当前选择 wlan0，跨 VLAN/隔离网络未验证。

现网启动参数：

```text
--host 0.0.0.0 --discover --discovery-interface wlan0 --link-test-config /home/alan/.config/pinepi-hub/link-test.json
```

首次升级既有配置可运行 `tools/enable_discovery.py --config ...`，原子新增 `hub_id`。迁移或更新必须保留该私有配置和 Hub 编号；全新生成编号将要求终端再次确认。新环境可用 `tools/link_test.py provision` 生成配置；它兼容生成的旧设备注入文件仅供历史模式，不供当前自动发现使用。`--host 0.0.0.0` 仅在显式 `--discover` 模式允许。systemd 允许 `AF_NETLINK` 以读取网卡；不提升运行用户权限。依赖新增并锁定 zeroconf/ifaddr。

实机通过的路径：亮屏时接收并显示；临时关闭接近服务、确认屏幕 OFF 后接收消息，回执请求亮屏且系统变为 ON；重启 Hub 后自动重连；关闭再恢复终端 Wi-Fi 后自动重连并接收新消息。测试后 Wi-Fi、接近唤醒均已恢复开启。

边界：长时间深度待机尚未验证；消息不自动启动录音或播放，也不主动把其他应用切回 PinePi。当前测试在 PinePi 主页前台/熄屏场景下验证，其他应用前台时如何呈现业务消息需另行确认。

0.5.0 检查：Hub 14 项、Android 21 项单元测试通过，Lint 无问题。用户已在终端点击首次确认；应用重新启动、Wi-Fi 恢复、Hub 服务重启以及临时更换端口后均已自动连接，新端口测试消息收到回执。更换 DHCP 地址的实机测试未执行，地址变化与确认编号稳定性由单元测试覆盖。测试后恢复 8766；此后树莓派整机重启、自启与终端恢复，以及超过 30 分钟普通插电熄屏后的消息亮屏均已实测通过；深度 Doze 仍未验证。记录见 `../docs/DEVICE_VALIDATION.md`。

参考：[Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd)、[python-zeroconf API](https://python-zeroconf.readthedocs.io/en/latest/api.html)。

## 已确认的远端 Agent 接入方向（尚未部署）

首版只接 Hermes，Hub 通过 Tailscale 私有网络调用其 HTTP API。终端无需安装 Tailscale，仍使用本地 WS/HTTP。API 地址、版本、密钥以及服务的私网可达性需要实施时检查；凭据仅保留服务端。

用户问答及正常回复走 `PinePi Home ↔ Hub ↔ Hermes API`；Hermes 主动通知走 `Hermes → Broadcast Mid → Hub → PinePi Home`，复用 Mid 现有发布接口及 Hub 独立订阅。主动通知不自动触发 Agent 推理或合并进当前聊天上下文；若以后需要关联，会话字段另行定义。文本 title/content 已接通，图片/视频负载另行适配。

本阶段不开发自定义 Hermes 渠道或 Gadget 适配，不引入多 Agent 通用插件框架。Hermes、Tailscale 尚未实施；Broadcast Mid 文本通知已独立接通。
