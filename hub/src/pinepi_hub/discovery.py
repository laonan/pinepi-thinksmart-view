"""DNS-SD announcements live only while the Hub process is running.

Stable UUID is a remembered identity for user confirmation, not authentication.
"""
import asyncio
from contextlib import suppress
import ipaddress
import logging
from uuid import UUID

import ifaddr
from zeroconf import IPVersion
from zeroconf.asyncio import AsyncServiceInfo, AsyncZeroconf

SERVICE_TYPE = "_pinepi-hub._tcp.local."


def private_addresses(interface=None, bind_host="0.0.0.0"):
    result = set()
    for adapter in ifaddr.get_adapters():
        if interface and adapter.name != interface:
            continue
        for item in adapter.ips:
            if not isinstance(item.ip, str):
                continue
            address = ipaddress.ip_address(item.ip)
            if any(address in ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
                if bind_host == "0.0.0.0" or bind_host == item.ip:
                    result.add(item.ip)
    return result


class HubDiscovery:
    def __init__(self, hub_id, port, interface=None, bind_host="0.0.0.0"):
        self.hub_id = str(UUID(hub_id))
        self.port, self.interface, self.bind_host = port, interface, bind_host
        self.services = {}
        self.zc = None
        self.task = None

    def info(self, address):
        suffix = address.replace(".", "-")
        return AsyncServiceInfo(
            SERVICE_TYPE, f"PinePi-{self.hub_id[:8]}-{suffix}.{SERVICE_TYPE}",
            parsed_addresses=[address], port=self.port,
            server=f"pinepi-{self.hub_id}-{suffix}.local.",
            properties={"id": self.hub_id, "v": "1", "path": "/_test/device", "auth": "none"},
        )

    async def refresh(self):
        addresses = private_addresses(self.interface, self.bind_host)
        for address in self.services.keys() - addresses:
            await self.zc.async_unregister_service(self.services.pop(address))
        for address in addresses - self.services.keys():
            info = self.info(address)
            await self.zc.async_register_service(info, allow_name_change=True)
            self.services[address] = info

    async def watch(self):
        while True:
            await asyncio.sleep(15)
            try:
                await self.refresh()
            except Exception:
                logging.getLogger(__name__).exception("Hub discovery refresh failed")

    async def __aenter__(self):
        self.zc = AsyncZeroconf(ip_version=IPVersion.V4Only)
        try:
            await self.refresh()
            self.task = asyncio.create_task(self.watch())
        except BaseException:
            await self.zc.async_close()
            raise
        return self

    async def __aexit__(self, *args):
        self.task.cancel()
        with suppress(asyncio.CancelledError):
            await self.task
        try:
            await self.zc.async_unregister_all_services()
        finally:
            await self.zc.async_close()
