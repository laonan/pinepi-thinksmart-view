# PinePi Home 消息协议与使用说明

协议版本：`2`。适用：Android 0.8.0 及配套 Hub。本文中的 IP 为示例，请替换为实际 Hub 地址。

## 实现状态

已实现：Hub v2 校验、持久待送队列及转发，Android 四类消息、两种文本模式、URL 媒体加载、文本朗读音频播放、手动音频/视频播放和 v2 ACK。旧 `version: 1` 文本及固定测试消息继续兼容，不自动朗读。

**待配置：没有百度 API 凭证，尚未调用百度生成音频。四段固定提示音暂未打包，收到对应消息时正常显示但提示音保持静默；不会用其他声音冒充。** 终端已实现资源选择与播放逻辑，生成资源并重新构建后启用。普通文本 TTS 目前接收发送方已生成的 `speech.url`；本轮未实现 Hub 按正文自动调用百度、新闻采集或定时任务。不要把协议接通当作百度 TTS 已配置完成。

## 链路和发送入口

```text
发送方 → Hub（校验、持久化）→ WebSocket JSON → PinePi Home
                                             └─ HTTP(S) 下载媒体或朗读音频
```

终端沿用 mDNS 自动发现的 `WS /_test/device`，无需手填地址。路径名称沿用旧版本，消息体已支持正式 v2。可信局域网发现模式不对终端认证，首次确认 Hub 的规则保持。

Hub 提供 `POST /api/messages`，使用现有独立控制 token 鉴权，接收本文完整消息对象。响应 `queued` 表示已写入待送队列，`duplicate` 表示该 ID 已在待送队列；都不代表播放完成。没有设备在线时仍可提交。

在 Hub 上发送示例（`message.json` 为下文任一完整 JSON；凭证不出现在命令参数或仓库）：

```sh
python3 - <<'PY'
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
PY
```

错误：未授权 `401`、JSON 帧超过 64 KiB `413`、格式或字段错误 `422`、未配置持久队列 `503`。校验错误仅返回原因，不回显消息正文。JSON 不携带密钥、Base64、二进制内容或第三方 TTS 参数。

Broadcast Mid 支持完整 v2 消息对象作为 `event`，也支持直接完整对象；v2 ID 保持不变。旧 `event.title/content/source` 仍转换成静默 v1 文本；不得指望它自动朗读。带 `version/kind/text_mode/speech/media` 的无效新协议对象会被拒绝，不回退为旧文本。

## 通用字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 是 | 固定 `home.message` |
| `version` | integer | 是 | 固定 `2`，不接受字符串或小数 |
| `id` | string | 是 | 1–80 个 ASCII 字母、数字、下划线或连字符；重发保持 ID |
| `source` | string | 是 | 非空来源，最多 100 个 UTF-16 单位 |
| `created_at` | string | 是 | 带时区 ISO 8601，1970 年起，例如 `2026-10-31T07:30:00+08:00` 或 `2026-10-30T23:30:00Z`；可带 1–3 位小数秒 |
| `kind` | string | 是 | `TEXT`、`IMAGE`、`AUDIO`、`VIDEO`，区分大小写 |
| `title` | string | 否 | 标题，最多 200 个 UTF-16 单位；缺省为空 |

可选字段应省略，不能填 `null`。v2 拒绝未知字段及不属于对应类型的字段，例如 `PRIVATE` 带 `speech`、图片带 `text`、任何消息带 `autoplay` 均无效。播放策略由类型和文本模式确定，不能用额外字段覆盖。

每条消息只有一个主体。标题、正文按纯文本显示，不解析 HTML/Markdown。显示时间来自 `created_at`，消息排列仍按终端到达顺序，不因上游时钟倒退而重排。

## 普通文本：显示并朗读

```json
{
  "type": "home.message",
  "version": 2,
  "id": "news-20261031-001",
  "source": "早间新闻",
  "created_at": "2026-10-31T07:30:00+08:00",
  "kind": "TEXT",
  "title": "今日新闻",
  "text": "早上好。今天为你整理了五条新闻，下面是第一条。",
  "text_mode": "TTS",
  "speech": {
    "url": "http://192.168.31.36:8766/media/news-20261031-001.mp3",
    "mime_type": "audio/mpeg"
  }
}
```

`text`、`text_mode: "TTS"` 和 `speech` 必填。正文非空，最多 8192 个 UTF-16 单位且 UTF-8 不超过 32 KiB。朗读只对应 `text`，不自动拼接标题/来源。发送方应先生成可下载的正文音频，再发送消息。

新消息到达后，在声音保护允许时自动下载播放正文；不另播“有新的文字消息”。卡片提供“朗读正文/停止播放”入口，下载/播放失败时保留文字，可降级到本地文字提示音。终端不向百度发送正文，不持有百度凭证。

## 隐私文本：只播固定提示

```json
{
  "type": "home.message",
  "version": 2,
  "id": "private-20261031-001",
  "source": "私人助理",
  "created_at": "2026-10-31T08:00:00+08:00",
  "kind": "TEXT",
  "title": "私人提醒",
  "text": "这里是仅供屏幕显示的敏感或隐私内容。",
  "text_mode": "PRIVATE"
}
```

`text_mode: "PRIVATE"` 必填，禁止 `speech`。收到后显示正文，本地播放“有新的文字消息”；无正文朗读按钮。未来任何发送方/Hub TTS 实现必须在第三方调用之前检查模式，不得把隐私标题或正文送第三方 TTS。

隐私模式禁止第三方朗读，不改变经过 Hub、可信局域网传输及终端本地保存的现有行为。

## 图片：显示并播图片提示

```json
{
  "type": "home.message",
  "version": 2,
  "id": "image-20261031-001",
  "source": "家庭相册",
  "created_at": "2026-10-31T08:10:00+08:00",
  "kind": "IMAGE",
  "title": "今日照片",
  "media": {
    "url": "http://192.168.31.36:8766/media/image-20261031-001.jpg",
    "mime_type": "image/jpeg"
  }
}
```

本地播放“有新的图片消息”，下载并等比显示图片，不分析或朗读图片内容。

## 音频：提示后手动播放

```json
{
  "type": "home.message",
  "version": 2,
  "id": "audio-20261031-001",
  "source": "家庭消息",
  "created_at": "2026-10-31T08:20:00+08:00",
  "kind": "AUDIO",
  "title": "一段语音留言",
  "media": {
    "url": "http://192.168.31.36:8766/media/audio-20261031-001.mp3",
    "mime_type": "audio/mpeg"
  }
}
```

本地播放“有新的音频消息”，卡片提供播放/停止按钮。主体音频仅点击后下载播放，和到达提示音不会叠播。

## 视频：提示后手动播放

```json
{
  "type": "home.message",
  "version": 2,
  "id": "video-20261031-001",
  "source": "家庭消息",
  "created_at": "2026-10-31T08:30:00+08:00",
  "kind": "VIDEO",
  "title": "一段视频",
  "media": {
    "url": "http://192.168.31.36:8766/media/video-20261031-001.mp4",
    "mime_type": "video/mp4"
  }
}
```

本地播放“有新的视频消息”，用户点击后播放视频；切页、进入后台或蓝牙状态不允许时停止。

## URL、格式和下载限制

`media` 与 `speech` 均严格包含 `url`、`mime_type` 两个字符串字段。URL 最长 4096 个 UTF-16 单位；支持 HTTPS，HTTP 仅允许 RFC1918 私有 IPv4。禁止用户信息、fragment、file/content 等本地协议及无效端口。图片/音频下载不跟随重定向。视频沿用 Android 系统 VideoView，URL 后续重定向由系统播放器处理，发布方应提供无重定向直链。

| 用途 | MIME | 首版范围 |
| --- | --- | --- |
| 图片 | `image/jpeg`、`image/png` | 下载上限 8 MiB，解码前最多 4000 万像素，显示解码边长缩至不超过 1600 |
| 音频及朗读 | `audio/mpeg` | MP3，下载上限 16 MiB，整段下载后播放，单次请求总超时 45 秒 |
| 视频 | `video/mp4` | 建议 H.264 + AAC，系统播放器网络播放，不承诺其他编码可播 |

图片/音频 HTTP 响应 Content-Type 也必须匹配对应媒体类别，不能依赖文件后缀。视频播放兼容性取决于终端系统解码器。

同一个 URL 的内容应保持不变。发送方负责让媒体在消息仍被保留期间可用，避免短期签名地址过期导致历史无法重播。图片和音频当前没有持久媒体缓存；音频临时文件在停止/结束后删除，重启后从原 URL 重新获取。

如需由 Hub 提供文件，创建专用目录并在 service 启动参数中显式增加：

```text
--media-directory /var/lib/pinepi-hub/media
```

该目录映射为 `GET /media/<文件名>`，无需认证，供可信局域网终端访问。只放待展示媒体，不能指向配置目录、用户主目录或待送消息状态目录。Hub 不做代理下载，也不会根据 JSON 任意读取本机文件。

## 声音、去重和接收确认

- 新 v2 消息保存后才触发到达声音；旧 v1 文本保持静默。
- 新到达消息替换当前消息音频，不设延迟播报队列；晚到的旧下载回调不能重新开声。新闻如果需要按条排队，应由上游按播放策略调度，目前没有“全部早间新闻自动排队”承诺。
- 提示音、正文朗读、音频与视频声音互斥，使用媒体音量，不修改系统音量。
- 蓝牙关闭、或已确认全部断开时才允许播放；蓝牙未知/连接中/已连接时不自动发声。蓝牙重新连接立即取消消息音频。诊断录音页期间暂停消息声音；退出页面不补播此前被拦截的声音。
- 离开主页 Activity、切换主页页面、切换所选消息或清空消息时停止当前消息音频。历史恢复、翻页不会自动重播。
- 终端保留最新 10 条消息，并持久保存最近 256 个已接受 ID；在此去重窗口内，重发不再次保存、亮屏、提示或朗读。清空展示历史不清空去重记录。超出窗口不能承诺永久去重，发送方不能复用 ID 更新内容。
- Hub 保存最新 10 条未确认消息，超限移除最旧待送记录，重连按旧到新补送；不是无限离线存储。

终端消息保存成功后回送：

```json
{
  "type": "home.ack",
  "version": 2,
  "id": "news-20261031-001"
}
```

终端实现还可附加布尔字段 `wake_requested` 用于诊断，接收方不能依赖它才接受 v2 ACK。ACK 只表示消息已保存，不表示用户已读、媒体已下载或声音已播放。重发的已保存消息也返回 ACK。协议不包含播放完成回执。

## 开发时生成并打包本地提示音

| 资源文件 | 固定内容 |
| --- | --- |
| `notice_text.mp3` | 有新的文字消息 |
| `notice_image.mp3` | 有新的图片消息 |
| `notice_audio.mp3` | 有新的音频消息 |
| `notice_video.mp3` | 有新的视频消息 |

在仓库外创建私有配置，例如 `~/.config/pinepi-hub/baidu-tts.json`，权限设为 `0600`：

```json
{
  "api_key": "填写百度语音应用的API Key",
  "secret_key": "填写对应Secret Key"
}
```

在仓库根目录执行：

```sh
python3 hub/tools/generate_notice_audio.py --config ~/.config/pinepi-hub/baidu-tts.json --voice 0
```

默认使用度小美（`per=0`）；可用 `--voice` 选择已开通的百度音色。工具仅发送上述四句固定公开提示，无自定义消息正文入口。四段全部生成成功后才写入 `android/app/src/main/res/raw/`；输出仅含资源校验摘要，不打印密钥或 token。

生成的四个 MP3 是产品资源，应纳入 Git 提交，重新构建并安装 APK 后在终端持久可用，运行时不再调用百度。私有配置已经列入 `.gitignore`，不得提交。缺少资源时保持静默，不能据此声称提示音播报验收通过。

参考：[百度短文本在线合成 API](https://ai.baidu.com/ai-doc/SPEECH/mlbxh7xie)。
