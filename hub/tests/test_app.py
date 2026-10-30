import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from pinepi_hub.app import MAX_FRAME_BYTES, create_app


def test_default_service_exposes_only_minimal_health() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/healthz")
        assert response.status_code == 200
        assert response.json() == {"status": "ok", "service": "pinepi-hub", "stage": "foundation"}
        for path in ("/docs", "/redoc", "/openapi.json", "/devices", "/messages"):
            assert client.get(path).status_code == 404
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/_diagnostics/echo"):
                pytest.fail("diagnostics must be disabled by default")


def test_probe_retains_order_and_unicode() -> None:
    with TestClient(create_app(diagnostics=True)) as client:
        with client.websocket_connect("/_diagnostics/echo") as ws:
            for text in ("probe-1", "中文传输测试", "", "probe-4"):
                ws.send_text(text)
                assert ws.receive_text() == text


def test_probe_rejects_binary_then_accepts_a_fresh_connection() -> None:
    with TestClient(create_app(diagnostics=True)) as client:
        with client.websocket_connect("/_diagnostics/echo") as ws:
            ws.send_bytes(b"synthetic-test")
            with pytest.raises(WebSocketDisconnect) as error:
                ws.receive_text()
            assert error.value.code == 1003
        with client.websocket_connect("/_diagnostics/echo") as ws:
            ws.send_text("fresh")
            assert ws.receive_text() == "fresh"


def test_frame_limit_counts_utf8_bytes() -> None:
    with TestClient(create_app(diagnostics=True)) as client:
        with client.websocket_connect("/_diagnostics/echo") as ws:
            ws.send_text("a" * MAX_FRAME_BYTES)
            assert len(ws.receive_text()) == MAX_FRAME_BYTES
        with client.websocket_connect("/_diagnostics/echo") as ws:
            ws.send_text("中" * (MAX_FRAME_BYTES // 3 + 1))
            with pytest.raises(WebSocketDisconnect) as error:
                ws.receive_text()
            assert error.value.code == 1009

