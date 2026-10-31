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

### 通过 Broadcast Mid 提交 TTS

发送网页默认选择 Hub 合成，在 Mid 的 `event` 中发送 `TEXT`、`text_mode: "TTS"`，省略 `speech`。Mid 原样转发，更新后的 Hub 订阅器按发送请求校验，复用 `POST /api/messages` 的合成路径。Hub 合成并保存音频后，再校验、持久化和转发带真实 `speech.url` 的完整终端消息。主动提供真实 MP3 URL 时跳过合成；PRIVATE 和旧版 v1 文本不会调用供应商。

需要同时更新 Hub 订阅器和 Mid 网页，并配置 `--baidu-tts-config`、`--media-directory`、`--media-base-url`。配置的局域网地址必须能让终端下载生成的 MP3。供应商或配置错误不会入队，在受控制凭证保护的 `/_test/status` 中通过 `broadcast_mid.last_rejection` 提供不含正文的原因；Mid HTTP 接收成功不代表合成或播放成功。未成功的请求使用原 ID 重试；已接受的相同 ID 会去重。想重复发送同一正文时应使用新的 ID。

订阅器最多保留十个待合成任务，慢合成期间继续处理应用心跳和 PRIVATE 消息。超限记录 `tts_submission_queue_full`，不会向终端转发不完整的 TTS 消息。

### 上传的图片、MP3 音频和 MP4 视频

Mid 只负责消息中转和媒体上传暂存，不做 TTS 合成。网页将四个消息类型与严重程度分开；IMAGE/AUDIO/VIDEO 先上传本地文件，再通过 `media_id` 发布，Hub 接收外层已鉴权的 `media.delivery`。终端不会收到 `mid://` 引用或 Mid 凭证。

配置 `--media-directory` 及终端可访问的 `--media-base-url` 后，Hub 仅从已配置的 Mid HTTPS 源下载，使用自己的 Device Token 和投递票据；拒绝重定向及 NONE 绕过，校验完整大小/SHA-256，原子保存文件，改写为局域网 `/media/` URL 并持久保存消息后，才发送 `media.cached`。临时网络失败最多重试三次并刷新票据，重启/重连会复用已校验文件。Mid 清理不删除 Hub 历史媒体，确认响应也不会作为终端消息转发。

`--media-cache-bytes` 默认 536870912（512 MiB），另限制最多 10000 个托管文件；只统计 `mid-*` 上传缓存，TTS 使用自己的独立缓存。不自动删除终端历史可能仍引用的文件，容量不足拒绝新下载并报告 `media_cache_full`；按历史引用安全回收仍为独立工作。缓存启动时清理超过十分钟的未完成临时文件。终端文件限制为图片 8 MiB、MP3 16 MiB、MP4 100 MiB。错误以不含正文/URL 的原因出现在受控制凭证保护的 `/_test/status` 中的 `broadcast_mid.last_rejection`。
