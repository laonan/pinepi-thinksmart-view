"""Producer -> Mid envelope -> Hub synthesis/cache -> strict terminal wire."""
import asyncio
import json
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from pinepi_hub.app import create_app
from pinepi_hub.baidu_tts import BaiduSpeech, SpeechError
from pinepi_hub.broadcast_mid import BroadcastMid
from pinepi_hub.link_test import LinkTest
from pinepi_hub.message_protocol import ProtocolError, validate_message
from pinepi_hub.notifications import NotificationStore, decode_notification


def submission(mode='TTS', ident='mid-tts-001'):
    return {'type': 'home.message', 'version': 2, 'id': ident, 'source': '早间新闻',
            'created_at': '2026-10-31T14:02:44.412Z', 'kind': 'TEXT', 'severity': 'NORMAL',
            'title': '今日新闻', 'text': '早上好。今天的新闻已为你整理好。', 'text_mode': mode}


def link_and_mid(tmp_path):
    link = LinkTest('d' * 40, 'c' * 40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path / 'pending.json')
    return link, BroadcastMid('unused', link.publish_mid_notification)


def fetch(request, limit):
    if 'oauth' in request.full_url:
        return 'application/json', b'{"access_token":"sample-token","expires_in":3600}'
    return 'audio/mp3', b'ID3' + b'0' * 256


def test_mid_submission_is_incomplete_until_hub_adds_speech():
    item = submission()
    assert decode_notification({'event': item})[0] is None
    assert decode_notification({'event': item}, allow_hub_tts=True) == (item, None)
    with pytest.raises(ProtocolError): validate_message(item)
    private = dict(submission('PRIVATE'), speech={'url':'https://example.com/read.mp3','mime_type':'audio/mpeg'})
    assert decode_notification({'event': private}, allow_hub_tts=True)[0] is None


def test_mid_default_tts_generates_a_real_cached_url_before_terminal_delivery(tmp_path):
    link, mid = link_and_mid(tmp_path)
    config = tmp_path / 'tts.json'
    config.write_text('{"api_key":"sample-key","secret_key":"sample-secret"}')
    media = tmp_path / 'media'
    media.mkdir()
    link.speech = BaiduSpeech(config, media, 'http://192.168.31.36:8766/media/')
    item = submission()
    with patch('pinepi_hub.baidu_tts.fetch', side_effect=fetch) as provider:
        with TestClient(create_app(link_test=link, media_directory=media)) as client:
            with client.websocket_connect('/_test/device') as terminal:
                client.portal.call(mid.handle, AsyncMock(), json.dumps({'event': item}))
                message = terminal.receive_json()
                assert validate_message(message) == message
                assert message['id'] == item['id'] and message['text'] == item['text']
                assert message['severity'] == 'NORMAL' and message['speech']['mime_type'] == 'audio/mpeg'
                response = client.get('/media/' + message['speech']['url'].rsplit('/', 1)[1])
                assert response.status_code == 200 and response.content.startswith(b'ID3')
                assert NotificationStore(link.notifications.path).messages == [message]
                # Replayed pending IDs are deduplicated before provider synthesis.
                client.portal.call(mid.handle, AsyncMock(), json.dumps({'event': item}))
                assert provider.call_count == 2
                assert len(link.notifications.messages) == 1
                assert mid.status()['received'] == 2


def test_mid_private_and_legacy_never_call_synthesis(tmp_path):
    link, mid = link_and_mid(tmp_path)
    link.speech = AsyncMock()
    async def scenario():
        await mid.handle(AsyncMock(), json.dumps({'event': submission('PRIVATE')}))
        await mid.handle(AsyncMock(), json.dumps({'event': {'content': 'legacy text'}}))
    asyncio.run(scenario())
    assert [m['version'] for m in link.notifications.messages] == [2, 1]
    assert all('speech' not in m for m in link.notifications.messages)
    link.speech.prepare.assert_not_called()


def test_mid_existing_audio_uses_url_without_synthesis(tmp_path):
    link, mid = link_and_mid(tmp_path)
    link.speech = AsyncMock()
    item = dict(submission(), speech={'url': 'https://example.com/real.mp3', 'mime_type': 'audio/mpeg'})
    asyncio.run(mid.handle(AsyncMock(), json.dumps({'event': item})))
    assert link.notifications.messages == [item]
    link.speech.prepare.assert_not_called()


@pytest.mark.parametrize('reason', ['baidu_tts_not_configured', 'baidu_connection_failed'])
def test_mid_failed_synthesis_is_not_forwarded_and_keeps_a_body_free_reason(tmp_path, reason, caplog):
    link, mid = link_and_mid(tmp_path)
    if reason != 'baidu_tts_not_configured':
        from types import SimpleNamespace
        def fail(item): raise SpeechError(reason)
        link.speech = SimpleNamespace(prepare=fail)
    async def scenario():
        await mid.handle(AsyncMock(), json.dumps({'event': submission()}))
        assert link.notifications.messages == []
        assert mid.status()['last_rejection'] == reason
        assert mid.status()['ignored'] == 1 and mid.status()['received'] == 0
        # A later private message and heartbeat remain usable after the rejection.
        ws = AsyncMock()
        await mid.handle(ws, json.dumps({'type': 'ping'}))
        ws.send.assert_awaited_once()
        await mid.handle(ws, json.dumps({'event': submission('PRIVATE', 'private-after-failure')}))
    asyncio.run(scenario())
    assert [m['id'] for m in link.notifications.messages] == ['private-after-failure']
    assert submission()['text'] not in caplog.text


def test_slow_mid_synthesis_does_not_block_application_pongs_or_private_messages():
    async def scenario():
        started, release, finished = asyncio.Event(), asyncio.Event(), asyncio.Event()
        delivered = []
        async def deliver(item):
            if item.get('text_mode') == 'TTS':
                started.set()
                await release.wait()
            delivered.append(item['id'])
            if item.get('text_mode') == 'TTS': finished.set()
        ws = AsyncMock()
        async def frames():
            yield json.dumps({'event': submission()})
            await asyncio.wait_for(started.wait(), 1)
            yield json.dumps({'type':'ping'})
            yield json.dumps({'event': submission('PRIVATE', 'private-while-slow')})
            assert delivered == ['private-while-slow']
            assert json.loads(ws.send.call_args.args[0]) == {'type':'pong'}
            release.set()
            await asyncio.wait_for(finished.wait(), 1)
        ws.__aiter__.side_effect = frames
        mid = BroadcastMid('unused', deliver)
        await mid.receive(ws)
        assert delivered == ['private-while-slow', 'mid-tts-001']
    asyncio.run(scenario())


def test_mid_synthesis_queue_is_bounded_and_cancels_pending_on_disconnect():
    async def scenario():
        started = 0
        cancelled = 0
        async def deliver(item):
            nonlocal started, cancelled
            started += 1
            try: await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled += 1
                raise
        ws = AsyncMock()
        async def frames():
            for index in range(11):
                yield json.dumps({'event': submission(ident=f'queued-{index}')})
                await asyncio.sleep(0)
        ws.__aiter__.side_effect = frames
        mid = BroadcastMid('unused', deliver)
        await mid.receive(ws)
        assert started == 10 and cancelled == 10
        assert mid.last_rejection == 'tts_submission_queue_full' and mid.ignored == 1
    asyncio.run(scenario())
