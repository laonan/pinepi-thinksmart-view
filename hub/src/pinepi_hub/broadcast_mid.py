"""Outbound WSS subscriber. Never log credentials, URLs with secrets or payloads."""
import asyncio
from contextlib import suppress
import json
import logging
from pathlib import Path
import re
from urllib.parse import urlsplit

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed
from .notifications import decode_notification, MAX_FRAME_BYTES
from .baidu_tts import SpeechError
from .message_protocol import ProtocolError

LOG = logging.getLogger(__name__)


def read_config(path):
    data = json.loads(Path(path).read_text())
    url, device, token = data['url'], data['device'], data['token']
    if not all(isinstance(value, str) for value in (url, device, token)):
        raise ValueError('invalid configuration')
    parsed = urlsplit(url)
    if (parsed.scheme != 'wss' or not parsed.hostname or parsed.username is not None
            or parsed.query or parsed.fragment or not parsed.path
            or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', device)
            or not token.strip() or len(token) > 4096 or '\n' in token or '\r' in token):
        raise ValueError('invalid configuration')
    return url, device, token


class BroadcastMid:
    def __init__(self, config, deliver, media_cache=None):
        self.config, self.deliver = config, deliver
        self.media_cache = media_cache
        self.task = None
        self.state = 'starting'
        self.received = self.ignored = 0
        self.last_rejection = None

    def status(self):
        return {'state': self.state, 'received': self.received, 'ignored': self.ignored,
                'last_rejection': self.last_rejection}

    async def handle(self, ws, raw):
        if not isinstance(raw, str):
            self.ignored += 1
            return
        try:
            packet = json.loads(raw)
        except ValueError:
            self.ignored += 1
            return
        if isinstance(packet, dict) and packet.get('type') == 'ping':
            await ws.send(json.dumps({'type': 'pong'}))
            self.state = 'connected'
            return
        if isinstance(packet, dict) and packet.get('type') == 'media.cached.result':
            if packet.get('status') != 'cached':
                self.last_rejection = 'media_confirmation_rejected'
                self.ignored += 1
            self.state = 'connected'
            return
        if isinstance(packet, dict) and packet.get('type') == 'media.delivery':
            from .mid_media import MediaError
            try:
                if self.media_cache is None:
                    raise MediaError('media_cache_not_configured')
                confirmation = await self.media_cache.accept(packet)
                await ws.send(json.dumps(confirmation))
            except (MediaError, ProtocolError) as error:
                self.ignored += 1
                self.last_rejection = str(error)
                LOG.warning('Broadcast Mid media rejected; reason=%s', self.last_rejection)
                return
            self.received += 1
            self.state = 'connected'
            return
        message, reason = decode_notification(packet, allow_hub_tts=True)
        if message is None:
            self.ignored += 1
            self.last_rejection = reason
            LOG.warning('Broadcast Mid notification rejected; reason=%s', reason)
            return
        try:
            await self.deliver(message)
        except (SpeechError, ProtocolError) as error:
            # Both errors expose bounded reason codes, never provider responses,
            # credentials or message text. Failed synthesis is not enqueued.
            self.ignored += 1
            self.last_rejection = str(error)
            LOG.warning('Broadcast Mid submission rejected; reason=%s', self.last_rejection)
            return
        self.received += 1
        self.state = 'connected'

    async def receive(self, ws):
        # Synthesis can take longer than Mid's application heartbeat timeout.
        # Bound background submissions while handling pings/private events promptly.
        pending = set()
        def completed(task):
            pending.discard(task)
            if not task.cancelled() and task.exception() is not None:
                self.ignored += 1
                self.last_rejection = 'submission_failed'
                LOG.warning('Broadcast Mid submission failed; error_type=%s', type(task.exception()).__name__)
        try:
            async for raw in ws:
                try:
                    packet = json.loads(raw) if isinstance(raw, str) else None
                    event = packet.get('event', packet) if isinstance(packet, dict) else None
                    is_media = isinstance(packet, dict) and packet.get('type') == 'media.delivery'
                    needs_speech = (isinstance(event, dict) and event.get('kind') == 'TEXT'
                                    and event.get('text_mode') == 'TTS' and 'speech' not in event)
                except ValueError:
                    needs_speech = False
                    is_media = False
                if needs_speech or is_media:
                    if len(pending) >= 10:
                        self.ignored += 1
                        self.last_rejection = 'media_submission_queue_full' if is_media else 'tts_submission_queue_full'
                        LOG.warning('Broadcast Mid submission rejected; reason=%s', self.last_rejection)
                        continue
                    task = asyncio.create_task(self.handle(ws, raw))
                    pending.add(task)
                    task.add_done_callback(completed)
                else:
                    await self.handle(ws, raw)
        finally:
            tasks = tuple(pending)
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    async def run(self):
        delay = 1
        while True:
            try:
                url, device, token = read_config(self.config)
            except (OSError, ValueError, KeyError, TypeError):
                self.state = 'waiting_configuration'
                await asyncio.sleep(5)
                continue
            try:
                self.state = 'connecting'
                # WSS uses the platform CA trust store; no insecure TLS override.
                async with connect(url, proxy=None, open_timeout=15, max_size=MAX_FRAME_BYTES,
                                   ping_interval=20, ping_timeout=20, close_timeout=5) as ws:
                    await ws.send(json.dumps({'token': token, 'device': device}))
                    # Mid has no auth-success frame. A heartbeat/event proves progress.
                    self.state = 'auth_sent'
                    await self.receive(ws)
                    if self.state == 'connected':
                        delay = 1
            except asyncio.CancelledError:
                raise
            except ConnectionClosed as error:
                if self.state == 'connected':
                    delay = 1
                code = error.rcvd.code if error.rcvd else None
                self.state = 'auth_rejected' if code in (4001, 4002) else 'reconnecting'
                LOG.warning('Broadcast Mid disconnected; state=%s', self.state)
            except Exception as error:
                if self.state == 'connected':
                    delay = 1
                self.state = 'reconnecting'
                LOG.warning('Broadcast Mid retry; error_type=%s', type(error).__name__)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 60)

    async def __aenter__(self):
        self.task = asyncio.create_task(self.run())
        return self

    async def __aexit__(self, *args):
        self.task.cancel()
        with suppress(asyncio.CancelledError):
            await self.task
