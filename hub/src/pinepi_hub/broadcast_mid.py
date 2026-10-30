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
    def __init__(self, config, deliver):
        self.config, self.deliver = config, deliver
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
        message, reason = decode_notification(packet)
        if message is None:
            self.ignored += 1
            self.last_rejection = reason
            LOG.warning('Broadcast Mid notification rejected; reason=%s', reason)
            return
        await self.deliver(message)
        self.received += 1
        self.state = 'connected'

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
                    async for raw in ws:
                        await self.handle(ws, raw)
                        if self.state == 'connected':
                            delay = 1
            except asyncio.CancelledError:
                raise
            except ConnectionClosed as error:
                code = error.rcvd.code if error.rcvd else None
                self.state = 'auth_rejected' if code in (4001, 4002) else 'reconnecting'
                LOG.warning('Broadcast Mid disconnected; state=%s', self.state)
            except Exception as error:
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
