import asyncio
import hashlib
import io
import json
from email.message import Message
from pathlib import Path
import time
from unittest.mock import AsyncMock, Mock
from urllib.error import HTTPError, URLError

import pytest
from fastapi.testclient import TestClient
from pinepi_hub.app import create_app
from pinepi_hub.broadcast_mid import BroadcastMid
from pinepi_hub.link_test import LinkTest
from pinepi_hub.mid_media import MidMediaCache, MediaError
from pinepi_hub.notifications import NotificationStore


class Response(io.BytesIO):
    status = 200
    def __init__(self, body, mime='image/png'):
        super().__init__(body)
        self.headers = Message()
        self.headers['Content-Type'] = mime


def setup(tmp_path, kind='IMAGE'):
    config = tmp_path/'mid.json'
    config.write_text(json.dumps({'url':'wss://mid.example.org/ws/','device':'hub','token':'device-secret'}))
    link = LinkTest('d'*40, 'c'*40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path/'pending.json')
    directory = tmp_path/'media'; directory.mkdir()
    cache = MidMediaCache(config, directory, 'http://192.168.1.2:8766/media/', link)
    body = b'example media bytes' * 20
    mime = {'IMAGE':'image/png','AUDIO':'audio/mpeg','VIDEO':'video/mp4'}[kind]
    description = {'media_id':'a'*32,'mime_type':mime,'size_bytes':len(body),'sha256':hashlib.sha256(body).hexdigest(),
                   'expires_at':time.time()+3600,'ticket_expires_at':time.time()+300,'auth_mode':'DEVICE',
                   'url':'https://mid.example.org/api/media/'+'a'*32+'?ticket=opaque'}
    event = {'type':'home.message','version':2,'id':'uploaded-media','source':'Upload test',
             'created_at':'2026-10-31T14:00:00Z','kind':kind,'severity':'CRITICAL',
             'media':{'url':'mid://'+'a'*32,'mime_type':mime}}
    envelope = {'type':'media.delivery','delivery_id':'b'*32,'publication_id':'c'*32,'event':event,'downloads':[description]}
    return cache, link, envelope, body


@pytest.mark.parametrize('kind', ['IMAGE','AUDIO','VIDEO'])
def test_download_cache_rewrite_persistence_then_confirmation(tmp_path, kind):
    cache, link, envelope, body = setup(tmp_path, kind)
    requests = []
    def open_(request, timeout):
        requests.append(request)
        assert request.get_header('Authorization') == 'Bearer device-secret'
        return Response(body, envelope['downloads'][0]['mime_type'])
    cache.opener.open = open_
    ws = AsyncMock()
    async def confirm(raw):
        confirmation = json.loads(raw)
        assert confirmation['type'] == 'media.cached'
        saved = NotificationStore(link.notifications.path).messages[0]
        assert saved['media']['url'].startswith('http://192.168.1.2:8766/media/mid-')
        assert 'mid://' not in saved['media']['url'] and saved['severity'] == 'CRITICAL'
        assert next(cache.directory.glob('mid-*')).read_bytes() == body
    ws.send.side_effect = confirm
    mid = BroadcastMid('unused', link.publish_mid_notification, media_cache=cache)
    asyncio.run(mid.handle(ws, json.dumps(envelope)))
    ws.send.assert_awaited_once()
    assert mid.received == 1
    with TestClient(create_app(link_test=link, media_directory=cache.directory)) as client:
        message = link.notifications.messages[0]
        path = '/media/' + message['media']['url'].rsplit('/',1)[1]
        assert client.get(path).content == body
        assert client.get(path, headers={'Range':'bytes=0-9'}).status_code == 206
    # Restart keeps content and message refs; duplicate delivery reuses verified bytes.
    restored_link = LinkTest('d'*40,'c'*40,trusted_lan=True)
    restored_link.notifications = NotificationStore(link.notifications.path)
    restored = MidMediaCache(cache.config, cache.directory, cache.base_url, restored_link)
    restored.opener.open = Mock(side_effect=AssertionError('cached bytes must not be downloaded again'))
    confirmation = asyncio.run(restored.accept(envelope))
    assert confirmation['sha256'] == envelope['downloads'][0]['sha256']
    assert len(restored_link.notifications.messages) == 1
    restored.opener.open.assert_not_called()


@pytest.mark.parametrize('mutation', ['third_party','redirect','none','hash','size','mime','private_url'])
def test_invalid_or_unsafe_download_never_publishes_or_confirms(tmp_path, mutation):
    cache, link, envelope, body = setup(tmp_path)
    descriptor = envelope['downloads'][0]
    if mutation == 'third_party': descriptor['url'] = descriptor['url'].replace('mid.example.org','third-party.example')
    elif mutation == 'none': descriptor['auth_mode'] = 'NONE'
    elif mutation == 'hash': descriptor['sha256'] = 'f'*64
    elif mutation == 'size': descriptor['size_bytes'] = len(body)-1
    elif mutation == 'mime': descriptor['mime_type'] = 'audio/mpeg'
    elif mutation == 'private_url': descriptor['url'] = descriptor['url'].replace('mid.example.org','127.0.0.1')
    def open_(request, timeout):
        if mutation == 'redirect': raise HTTPError(request.full_url,302,'redirect',{},None)
        return Response(body)
    cache.opener.open = Mock(side_effect=open_)
    with pytest.raises(MediaError): asyncio.run(cache.accept(envelope))
    assert link.notifications.messages == []
    assert not list(cache.directory.glob('mid-*'))
    assert not list(cache.directory.glob('.mid-incoming-*'))
    if mutation in ('third_party','none','mime','private_url'): cache.opener.open.assert_not_called()


def test_interrupt_refreshes_ticket_and_retries_without_extending_asset_lifetime(tmp_path):
    cache, link, envelope, body = setup(tmp_path)
    descriptor = envelope['downloads'][0]
    renewed = {**descriptor, 'url':descriptor['url'].replace('opaque','renewed')}
    calls=[]
    def open_(request, timeout):
        calls.append(request)
        if len(calls)==1: raise URLError('network interrupted')
        if request.get_method()=='POST': return Response(json.dumps(renewed).encode(),'application/json')
        assert 'ticket=renewed' in request.full_url
        return Response(body)
    cache.opener.open=open_
    confirmation=asyncio.run(cache.accept(envelope))
    assert len(calls)==3 and calls[1].get_method()=='POST'
    assert all(r.get_header('Authorization')=='Bearer device-secret' for r in calls)
    assert confirmation['media_id']==descriptor['media_id']


def test_cache_capacity_refuses_new_download_without_evicting_history(tmp_path):
    cache, link, envelope, body=setup(tmp_path)
    retained=cache.directory/'mid-history.mp3';retained.write_bytes(b'history')
    cache.capacity_bytes=len(body)
    cache.opener.open=Mock()
    with pytest.raises(MediaError,match='media_cache_full'):asyncio.run(cache.accept(envelope))
    assert retained.read_bytes()==b'history'
    cache.opener.open.assert_not_called()


def test_cached_confirmation_result_is_not_a_terminal_message(tmp_path):
    cache, link, envelope, body=setup(tmp_path)
    mid=BroadcastMid('unused',link.publish_mid_notification,media_cache=cache)
    asyncio.run(mid.handle(AsyncMock(),json.dumps({'type':'media.cached.result','status':'cached'})))
    assert mid.ignored==0 and link.notifications.messages==[]


def test_startup_cleans_old_incomplete_downloads_but_keeps_history(tmp_path):
    import os
    cache, link, envelope, body = setup(tmp_path)
    orphan = cache.directory / '.mid-incoming-orphan'
    orphan.write_bytes(b'partial')
    os.utime(orphan, (0, 0))
    history = cache.directory / 'mid-history.mp3'
    history.write_bytes(b'keep')
    MidMediaCache(cache.config, cache.directory, cache.base_url, link)
    assert not orphan.exists() and history.read_bytes() == b'keep'


def test_malformed_media_kind_is_rejected_before_network(tmp_path):
    cache, link, envelope, body = setup(tmp_path)
    envelope['event']['kind'] = []
    cache.opener.open = Mock()
    with pytest.raises(MediaError, match='media_kind_mismatch'):
        asyncio.run(cache.accept(envelope))
    cache.opener.open.assert_not_called()
