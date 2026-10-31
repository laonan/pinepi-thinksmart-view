# Broadcast Mid 文本客户端

[English](BROADCAST_MID.md) | **中文版**

目标 Hub：`alan@192.168.31.36`。公网订阅地址 `wss://hail.lonelyassistant.com/ws/`，设备名 **`pinepi-home-hub`**（不是 `pine-pi-home-hub`）。公网使用系统 CA 校验 TLS；Hub 到 ThinkSmart 继续使用可信局域网 WS，无证书。

## 配置

在云端 `/etc/pinepi-broadcast-mid/config.json` 的现有 `devices` 中新增 `pinepi-home-hub` 及专用随机 Token，并为相关 Producer 的 `targets` 追加此设备（已有 `*` 权限者无需修改）。不要复用其他终端的名称：Mid 会断开同名旧连接。云端修改后重启 `pinepi-broadcast-mid`。

在 Mac 上运行以下交互命令，Token 直接在终端隐藏输入，不发到聊天里：

```sh
ssh -t alan@192.168.31.36 'cd /home/alan/pinepi-thinksmart-view/hub && .venv/bin/python tools/configure_broadcast_mid.py'
```

配置保存为 `/home/alan/.config/pinepi-hub/broadcast-mid.json`，0600。配置工具拒绝自动覆盖已有文件；需要修改时在树莓派本地编辑该文件，保持权限。结构如下（实际 Token 不进入源码）：

```json
{
  "url": "wss://hail.lonelyassistant.com/ws/",
  "device": "pinepi-home-hub",
  "token": "<device token>"
}
```

systemd 仍为同一 `pinepi-hub.service`，追加运行参数：

```text
--broadcast-mid-config /home/alan/.config/pinepi-hub/broadcast-mid.json --notification-state /var/lib/pinepi-hub/notifications.json
```

单元使用 `StateDirectory=pinepi-hub`，目录 0700；待送队列文件 0600。原 Home 只读约束保留，配置文件不可由服务写入。无配置/配置无效时上游等待并每 5 秒重读，本地服务保持运行；有效配置修改在下一次上游重连读取，需要立即重连可重启 Hub service。

## 协议与本轮范围

Hub 在 WSS 建立后立即发送 `{token, device}`，响应应用 JSON `ping` 为 `pong`；另保留 WebSocket 控制帧心跳。自动退避重连 1–60 秒，不将失败详情、Token、消息正文写入日志。

用户已确认首轮文本兼容 `event.title`（可选）、`event.content`（必填、非空字符串），并新增 `event.source`（可选、最多 100 UTF-16 单位）。来源去除首尾空白，未提供或空白时回退为 Broadcast Mid；非字符串或超长来源拒收并记录原因。也接受 Mid 兼容的无 event 包装文本。0.7.3 起标题最多 200 个 UTF-16 单位、正文最多 8192 个 UTF-16 单位且不超过 32 KiB UTF-8、转发帧不超过 64 KiB；普通中文约 8000 字，emoji 通常占两个单位。Hub 待送文件上限 512 KiB，与终端既有的 512 KiB 历史读取上限匹配。旧版 0.7.2 正文 2048 字节的限制已修正。不执行 `play_now`，不发声或启动录音；二进制墨水屏帧、非文本负载及非法消息不转发。文本超限会记录不含正文的原因，并在控制状态 `broadcast_mid.last_rejection` 中返回 `content_too_long`；不会截断后冒充完整送达。图片、视频另行接入。

转换为局域网兼容消息：`type=home.message, version=1, kind=TEXT, id, source=<发送方填写或默认 Broadcast Mid>, title, text`。终端 0.7.2 / code 14 起接收并沿用既有本地历史、最新消息优先及所有通知熄屏亮屏规则。保留现有固定 `test.message` 测试入口，未冻结未来完整媒体/Agent 协议。

Hub 保存最多 10 条尚未确认的文本通知，超限保留最新 10 条；终端离线或 Hub 重启后按旧到新顺序补送。终端保存成功后返回 `home.ack`，Hub 从待送队列删除；重复本地已有 ID 返回回执而不再次亮屏。提供上游 `event.id` 时生成稳定的 Hub ID；无 ID 的每次接收生成独立 ID，不按相同正文误去重。本轮队列按单终端使用，不承诺无限离线消息或跨故障全链路恰好一次投递。

## 运维与验证

```sh
cd /home/alan/pinepi-thinksmart-view/hub
.venv/bin/python tools/link_test.py status --config /home/alan/.config/pinepi-hub/link-test.json
sudo systemctl status pinepi-hub.service
```

状态 `auth_sent` 仅说明已发送鉴权；Mid 不发送鉴权成功帧，收到应用心跳或有效文本后才标为 `connected`。`auth_rejected` 请核对设备名、设备 Token 和云端是否已重启。Producer Token 仅在发送端使用，Hub 订阅不需要它。

测试消息通过现有 Producer 的 HTTPS `/api/send` 发布，格式示例：

```json
{
  "targets": ["pinepi-home-hub"],
  "event": {
    "id": "a-unique-message-id",
    "source": "Hermes",
    "title": "连接测试",
    "content": "来自 Broadcast Mid 的文本通知。"
  }
}
```

Mid 返回 delivered 仅代表送到 Hub，最终以终端同 ID 的 `home.ack` 为依据。线上端到端、屏幕状态及终端升级完成情况按本轮报告实际填写；未连接 Producer 前不冒充已做线上全链路测试。

协议来源：[Mid README](https://github.com/laonan/pinepi-broadcast-mid/blob/main/README.md)、[服务端实现](https://github.com/laonan/pinepi-broadcast-mid/blob/main/python/broadcast_server_mid.py)。


## 本轮实机结果（2026-10-30）

已通过实际公网 TLS、云端应用心跳、用户发送的文本通知及终端 UI/私有存储/同 ID ACK 验证。终端已安装 0.7.2，本轮 ADB 通过规定断电顺序恢复，无需新的 RSA 弹窗。相关与 LAN 测试 22 passed；先前本地合成 WSS 用例未参与此轮，保留既有失败记录。用户消息到达时终端已亮屏，未在本轮另做熄屏待机测试。


## 0.7.3 长文修复（2026-10-30）

用户一篇中文通知在旧 2048 UTF-8 字节限制下被过滤，Hub ignored 增加。本轮统一修正正文、通知帧与待送文件的限制，并测试中文/emoji 边界和十条长文重启恢复。Hub/LAN 回归 25 passed / 1 deselected，Android 29 项通过，Lint 0 errors / 6 个既有提示。终端与 Hub 已更新。用户重发后实测正文 2002 字 / 5878 UTF-8 字节，首尾吻合，本地记录和同 ID ACK 通过。证据见 `hub/reports/long-text-validation.json`。


## 自定义来源（2026-10-30）

新消息可发送 `event.source`，Hub 读取后原样作为终端来源展示（去除首尾空格），不需要更新已有 0.7.3 终端或修改 Mid 服务端。缺少/空白来源仍显示 Broadcast Mid；历史消息不会反推真实来源。此值为发送方声明的展示名，不代表 Mid 根据 Producer Token 认证出的身份。

```sh
curl -sS 'https://hail.lonelyassistant.com/api/send' \
  -H 'Authorization: Bearer <PRODUCER_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{"targets":["pinepi-home-hub"],"event":{"source":"Hermes","title":"来源测试","content":"这条通知来自 Hermes。"}}'
```

这里使用 Producer Token，不是 Hub 的设备订阅 Token。原客户端固定 title/content 不影响使用 curl 发送额外字段。
