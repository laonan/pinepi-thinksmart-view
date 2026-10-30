import pytest
import time
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from pinepi_hub.app import create_app
from pinepi_hub.link_test import LinkTest

D = "d" * 40
C = "c" * 40
CONTROL = {"Authorization": "Bearer " + C}


def test_auth_roles_and_message_ack():
    with TestClient(create_app(link_test=LinkTest(D, C))) as client:
        assert client.post("/_test/send").status_code == 401
        assert client.post("/_test/send", headers={"Authorization": "Bearer " + D}).status_code == 401
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/_test/device", headers=CONTROL):
                pytest.fail("control token must not authenticate as device")
        with client.websocket_connect("/_test/device", headers={"Authorization": "Bearer " + D}) as ws:
            sent = client.post("/_test/send", headers=CONTROL).json()
            message = ws.receive_json()
            assert sent["sent"] == 1 and message["id"] == sent["id"]
            assert message["version"] == 1 and message["text"] == "PinePi 测试消息：局域网连接正常。"
            ws.send_json({"type": "test.ack", "id": message["id"], "wake_requested": True})
            deadline = time.monotonic() + 2
            while True:
                status = client.get("/_test/status", headers=CONTROL).json()
                if status["last_ack"] is not None or time.monotonic() >= deadline:
                    break
                time.sleep(0.01)
            assert status["connections"] == 1
            assert status["last_ack"]["id"] == message["id"]


def test_disconnected_device_can_reconnect_without_replaying_messages():
    with TestClient(create_app(link_test=LinkTest(D, C))) as client:
        assert client.post("/_test/send", headers=CONTROL).json()["sent"] == 0
        for _ in range(2):
            with client.websocket_connect("/_test/device", headers={"Authorization": "Bearer " + D}) as ws:
                sent = client.post("/_test/send", headers=CONTROL).json()
                assert ws.receive_json()["id"] == sent["id"]
                client.post("/_test/disconnect", headers=CONTROL)
                with pytest.raises(WebSocketDisconnect):
                    ws.receive_text()


def test_distinct_strong_tokens_required():
    with pytest.raises(ValueError):
        LinkTest("short", C)
    with pytest.raises(ValueError):
        LinkTest(D, D)
