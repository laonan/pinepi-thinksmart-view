"""Terminal message transport, durable v2 publishing and retained legacy probes."""
import asyncio
import json
from collections import deque
from contextlib import suppress
import secrets
from uuid import uuid4

from fastapi import FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from .message_protocol import MAX_FRAME_BYTES, ProtocolError, validate_message


class LinkTest:
    def __init__(self, device_token: str, control_token: str, *, trusted_lan: bool = False):
        if min(len(device_token), len(control_token)) < 32 or device_token == control_token:
            raise ValueError("separate random device and control tokens are required")
        self.trusted_lan = trusted_lan
        self.device_token = device_token
        self.control_token = control_token
        self.clients: set[WebSocket] = set()
        self.sent_ids: deque[str] = deque(maxlen=128)
        self.last_ack: dict | None = None
        self.notification_lock = asyncio.Lock()
        self.notifications = None
        self.upstream_status = None

    async def publish_notification(self, message):
        if message.get('version') == 2:
            validate_message(message)
        async with self.notification_lock:
            if self.notifications is None:
                raise RuntimeError('notification store is not configured')
            if not self.notifications.add(message):
                return False
            self.sent_ids.append(message['id'])
            for ws in tuple(self.clients):
                try:
                    await asyncio.wait_for(ws.send_json(message), timeout=3)
                except (TimeoutError, RuntimeError, WebSocketDisconnect):
                    self.clients.discard(ws)
                    with suppress(Exception):
                        await ws.close()
            return True

    def authorize_control(self, authorization: str | None) -> None:
        if not secrets.compare_digest((authorization or "").encode(), ("Bearer " + self.control_token).encode()):
            raise HTTPException(status_code=401, detail="Unauthorized")

    def attach(self, app: FastAPI) -> None:
        @app.websocket("/_test/device")
        async def device(ws: WebSocket):
            if not self.trusted_lan and not secrets.compare_digest(ws.headers.get("authorization", "").encode(), ("Bearer " + self.device_token).encode()):
                await ws.close(code=1008)
                return
            await ws.accept()
            try:
                async with self.notification_lock:
                    self.clients.add(ws)
                    if self.notifications is not None:
                        for message in tuple(self.notifications.messages):
                            self.sent_ids.append(message['id'])
                            await asyncio.wait_for(ws.send_json(message), timeout=3)
                while True:
                    event = await ws.receive()
                    if event["type"] == "websocket.disconnect":
                        return
                    text = event.get("text")
                    if text is None or len(text.encode("utf-8")) > 4096:
                        await ws.close(code=1008)
                        return
                    try:
                        ack = json.loads(text)
                    except (ValueError, TypeError):
                        await ws.close(code=1008)
                        return
                    if (isinstance(ack, dict) and ack.get("type") in ("test.ack", "home.ack")
                            and isinstance(ack.get("id"), str) and ack["id"] in self.sent_ids
                            and ((ack.get('type') == 'home.ack' and type(ack.get('version')) is int and ack['version'] == 2)
                                 or isinstance(ack.get("wake_requested"), bool))):
                        self.last_ack = {"id": ack["id"], "wake_requested": ack.get("wake_requested", False)}
                        if ack['type'] == 'home.ack' and self.notifications is not None:
                            self.notifications.acknowledge(ack['id'])
            except (WebSocketDisconnect, RuntimeError, TimeoutError):
                pass
            finally:
                self.clients.discard(ws)

        @app.post("/_test/send")
        async def send(authorization: str | None = Header(default=None)):
            self.authorize_control(authorization)
            message = {"type": "test.message", "version": 1, "id": uuid4().hex,
                       "text": "PinePi 测试消息：局域网连接正常。"}
            self.sent_ids.append(message["id"])
            delivered = 0
            for ws in tuple(self.clients):
                try:
                    await asyncio.wait_for(ws.send_json(message), timeout=3)
                    delivered += 1
                except (TimeoutError, RuntimeError, WebSocketDisconnect):
                    self.clients.discard(ws)
            return {"id": message["id"], "sent": delivered}

        @app.post('/api/messages')
        async def publish(request: Request, authorization: str | None = Header(default=None)):
            self.authorize_control(authorization)
            if self.notifications is None:
                raise HTTPException(503, detail='notification_store_not_configured')
            body = bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body) > MAX_FRAME_BYTES:
                    raise HTTPException(413, detail='frame_too_large')
            try:
                message = validate_message(json.loads(body))
            except ProtocolError as error:
                raise HTTPException(422, detail=str(error)) from None
            except (ValueError, UnicodeError):
                raise HTTPException(422, detail='invalid_json') from None
            added = await self.publish_notification(message)
            return {'id': message['id'], 'status': 'queued' if added else 'duplicate'}

        @app.get("/_test/status")
        async def status(authorization: str | None = Header(default=None)):
            self.authorize_control(authorization)
            result = {"connections": len(self.clients), "last_ack": self.last_ack}
            if self.upstream_status is not None:
                result['broadcast_mid'] = self.upstream_status()
            return result

        @app.post("/_test/disconnect")
        async def disconnect(authorization: str | None = Header(default=None)):
            self.authorize_control(authorization)
            clients = tuple(self.clients)
            for ws in clients:
                try:
                    await ws.close(code=1012)
                except (RuntimeError, WebSocketDisconnect):
                    pass
                self.clients.discard(ws)
            return {"disconnected": len(clients)}
