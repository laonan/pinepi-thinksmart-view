from types import SimpleNamespace
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from pinepi_hub.app import create_app
from pinepi_hub.discovery import HubDiscovery, private_addresses, SERVICE_TYPE
from pinepi_hub.link_test import LinkTest

ID = "22532e9a-a842-4f44-b4c2-60a271b71c84"


def test_advertisement_has_stable_identity_and_no_credentials():
    hub = HubDiscovery(ID, 8766)
    first, second = hub.info("192.168.2.109"), hub.info("192.168.2.110")
    assert first.type == SERVICE_TYPE
    assert first.name != second.name
    assert first.properties[b"id"] == second.properties[b"id"] == ID.encode()
    assert first.properties == {b"id": ID.encode(), b"v": b"1", b"path": b"/_test/device", b"auth": b"none"}
    with pytest.raises(ValueError):
        HubDiscovery("unstable", 8766)


def test_addresses_follow_interface_and_exclude_public_loopback():
    adapters = [SimpleNamespace(name="wlan0", ips=[SimpleNamespace(ip=x) for x in ["192.168.2.109", "8.8.8.8", ("::1", 0, 0)]]),
                SimpleNamespace(name="eth0", ips=[SimpleNamespace(ip="192.168.1.50")])]
    with patch("pinepi_hub.discovery.ifaddr.get_adapters", return_value=adapters):
        assert private_addresses("wlan0") == {"192.168.2.109"}
        assert private_addresses(bind_host="192.168.1.50") == {"192.168.1.50"}
        assert private_addresses() == {"192.168.1.50", "192.168.2.109"}


def test_trusted_lan_device_needs_no_token_but_control_remains_separate():
    with TestClient(create_app(link_test=LinkTest("d" * 40, "c" * 40, trusted_lan=True))) as client:
        assert client.post("/_test/send").status_code == 401
        with client.websocket_connect("/_test/device") as ws:
            sent = client.post("/_test/send", headers={"Authorization": "Bearer " + "c" * 40}).json()
            assert sent["sent"] == 1
            assert ws.receive_json()["id"] == sent["id"]


def test_address_change_withdraws_stale_endpoint_and_advertises_new_one():
    import asyncio
    from unittest.mock import AsyncMock

    async def scenario():
        hub = HubDiscovery(ID, 8766)
        hub.zc = SimpleNamespace(async_register_service=AsyncMock(), async_unregister_service=AsyncMock())
        with patch("pinepi_hub.discovery.private_addresses", return_value={"192.168.2.109"}):
            await hub.refresh()
        old = hub.services["192.168.2.109"]
        with patch("pinepi_hub.discovery.private_addresses", return_value={"192.168.2.110"}):
            await hub.refresh()
        hub.zc.async_unregister_service.assert_awaited_once_with(old)
        assert set(hub.services) == {"192.168.2.110"}
        assert hub.zc.async_register_service.await_count == 2
        assert hub.services["192.168.2.110"].properties[b"id"] == ID.encode()
    asyncio.run(scenario())
