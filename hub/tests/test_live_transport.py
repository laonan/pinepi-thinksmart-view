from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import time

import httpx
import pytest
from websockets.exceptions import ConnectionClosed
from websockets.sync.client import connect


@contextmanager
def live_server(*, tls: bool, diagnostics: bool = True):
    with tempfile.TemporaryDirectory(prefix="pinepi-transport-test-") as temp:
        context = None
        tls_args = []
        if tls:
            cert, key = Path(temp) / "cert.pem", Path(temp) / "key.pem"
            subprocess.run([
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
                "-keyout", str(key), "-out", str(cert),
            ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
            context = ssl.create_default_context(cafile=str(cert))
            tls_args = ["--tls-cert", str(cert), "--tls-key", str(key)]
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        command = [sys.executable, "-m", "pinepi_hub", "--port", str(port), *tls_args]
        if diagnostics:
            command.append("--diagnostics")
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen(command, stdout=log, stderr=log)
            try:
                with httpx.Client(verify=context or True, trust_env=False, timeout=1) as client:
                    url = f"{'https' if tls else 'http'}://127.0.0.1:{port}"
                    deadline = time.monotonic() + 15
                    while True:
                        if process.poll() is not None:
                            pytest.fail("test service exited before readiness")
                        try:
                            if client.get(url + "/healthz").status_code == 200:
                                break
                        except httpx.TransportError:
                            pass
                        if time.monotonic() >= deadline:
                            pytest.fail("test service readiness timed out")
                        time.sleep(0.05)
                    ws_url = f"{'wss' if tls else 'ws'}://127.0.0.1:{port}/_diagnostics/echo"
                    yield client, url, ws_url, context
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=7)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=3)


@pytest.mark.parametrize("tls", [False, True], ids=["http-ws", "https-wss"])
def test_real_transport_reconnect_and_limits(tls: bool) -> None:
    with live_server(tls=tls) as (client, url, ws_url, context):
        assert client.get(url + "/healthz").json()["status"] == "ok"
        options = {"ssl": context} if tls else {}
        for index in range(3):
            with connect(ws_url, proxy=None, open_timeout=3, **options) as ws:
                text = f"synthetic-{index}-中文"
                ws.send(text)
                assert ws.recv(timeout=3) == text
        def concurrent_probe(index: int) -> None:
            with connect(ws_url, proxy=None, open_timeout=3, **options) as ws:
                for sequence in range(8):
                    text = f"client-{index}-probe-{sequence}"
                    ws.send(text)
                    assert ws.recv(timeout=3) == text
        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(concurrent_probe, index) for index in range(4)]
            for future in futures:
                future.result(timeout=10)
        with connect(ws_url, proxy=None, open_timeout=3, **options) as ws:
            ws.send(b"binary-probe")
            with pytest.raises(ConnectionClosed) as error:
                ws.recv(timeout=3)
            assert error.value.rcvd.code == 1003
        with connect(ws_url, proxy=None, open_timeout=3, **options) as ws:
            ws.send("x" * 4097)
            with pytest.raises(ConnectionClosed) as error:
                ws.recv(timeout=3)
            assert error.value.rcvd.code == 1009
        with connect(ws_url, proxy=None, open_timeout=3, **options) as ws:
            ws.send("still-healthy")
            assert ws.recv(timeout=3) == "still-healthy"
        if tls:
            with httpx.Client(trust_env=False, timeout=2) as untrusted:
                with pytest.raises(httpx.ConnectError):
                    untrusted.get(url + "/healthz")


def test_service_default_does_not_expose_diagnostic_socket() -> None:
    with live_server(tls=False, diagnostics=False) as (client, url, ws_url, _):
        response = client.get(url + "/healthz")
        assert response.status_code == 200
        from websockets.exceptions import InvalidStatus
        with pytest.raises(InvalidStatus) as error:
            with connect(ws_url, proxy=None, open_timeout=3):
                pytest.fail("default service exposed diagnostic socket")
        assert error.value.response.status_code == 403

