import asyncio
import json
import threading
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import parse_qs

import pytest
from fastapi.testclient import TestClient
from pinepi_hub.app import create_app
from pinepi_hub.baidu_tts import BaiduSpeech, SpeechError, validate_submission
from pinepi_hub.link_test import LinkTest
from pinepi_hub.message_protocol import ProtocolError, validate_message
from pinepi_hub.notifications import NotificationStore


def submission(mode='TTS', ident='baidu-test-001'):
    return {'type': 'home.message', 'version': 2, 'id': ident, 'source': 'TTS test',
            'created_at': '2026-10-31T10:00:00+08:00', 'kind': 'TEXT', 'title': 'Not sent to Baidu',
            'text': '你好，这是语音测试。', 'text_mode': mode}


@pytest.fixture
def speech(tmp_path):
    config = tmp_path/'baidu-tts.json'
    config.write_text('{"api_key":"sample-key","secret_key":"sample-secret"}')
    return BaiduSpeech(config, tmp_path/'media', 'http://192.168.31.36:8766/media/')


def mock_fetch(request, limit):
    if 'oauth' in request.full_url:
        return 'application/json', b'{"access_token":"sample-token","expires_in":3600}'
    return 'audio/mp3', b'ID3' + b'0'*256


def test_only_http_submission_can_omit_tts_speech():
    item = submission()
    assert validate_submission(item) == item
    with pytest.raises(ProtocolError): validate_message(item)
    with pytest.raises(ProtocolError): validate_submission(dict(item, text_mode='PRIVATE', speech={}))
    with pytest.raises(ProtocolError): validate_submission(dict(item, text_mode='UNKNOWN'))


def test_private_text_never_reads_config_or_calls_provider(speech):
    speech.config.unlink()
    with patch('pinepi_hub.baidu_tts.fetch') as fetch:
        item = submission('PRIVATE')
        assert speech.prepare(item) == item
        fetch.assert_not_called()
    assert not speech.directory.exists()


def test_synthesis_sends_body_only_and_caches_audio_without_credentials(speech):
    with patch('pinepi_hub.baidu_tts.fetch', side_effect=mock_fetch) as fetch:
        result = speech.prepare(submission())
        assert fetch.call_count == 2
        payload = parse_qs(fetch.call_args.args[0].data.decode())
        assert payload['tex'] == [submission()['text']]
        assert 'title' not in payload and submission()['title'] not in str(payload)
        assert validate_message(result) == result
        cached = list(speech.directory.glob('tts-*.mp3'))
        assert len(cached) == 1 and cached[0].read_bytes().startswith(b'ID3')
        assert cached[0].stat().st_mode & 0o777 == 0o600
        second = speech.prepare(submission(ident='another-id'))
        assert second['speech'] == result['speech'] and fetch.call_count == 2
        assert all(secret not in json.dumps(result) for secret in ('sample-key','sample-secret','sample-token'))


def test_token_is_reused_for_different_bodies(speech):
    with patch('pinepi_hub.baidu_tts.fetch', side_effect=mock_fetch) as fetch:
        speech.prepare(submission())
        speech.prepare(dict(submission(ident='another'), text='不同的正文。'))
        assert fetch.call_count == 3


@pytest.mark.parametrize('text', ['中'*512, 'emoji 😀'])
def test_unsupported_text_is_rejected_before_any_provider_call(speech, text):
    with patch('pinepi_hub.baidu_tts.fetch') as fetch:
        with pytest.raises(SpeechError, match='tts_text_'):
            speech.prepare(dict(submission(), text=text))
        fetch.assert_not_called()


def test_precomputed_speech_needs_no_provider(speech):
    speech.config.unlink()
    item = dict(submission(), speech={'url':'https://example.com/existing.mp3','mime_type':'audio/mpeg'})
    with patch('pinepi_hub.baidu_tts.fetch') as fetch:
        assert speech.prepare(item) == item
        fetch.assert_not_called()


def test_provider_json_failure_does_not_create_audio_file(speech):
    with patch('pinepi_hub.baidu_tts.fetch', side_effect=[
        ('application/json', b'{"access_token":"sample-token"}'),
        ('application/json', b'{"err_no":502,"err_msg":"sensitive-provider-error"}'),
    ]):
        with pytest.raises(SpeechError, match='^baidu_synthesis_failed$'):
            speech.prepare(submission())
    assert not list(speech.directory.iterdir())


def test_control_api_synthesizes_then_forwards_strict_wire_message(tmp_path, speech):
    link = LinkTest('d'*40, 'c'*40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path/'pending.json')
    link.speech = speech
    headers = {'Authorization':'Bearer '+'c'*40}
    with patch('pinepi_hub.baidu_tts.fetch', side_effect=mock_fetch) as fetch:
        with TestClient(create_app(link_test=link)) as client:
            with client.websocket_connect('/_test/device') as ws:
                assert client.post('/api/messages', json=submission(), headers=headers).status_code == 200
                result = ws.receive_json()
                validate_message(result)
                assert result['speech']['url'].endswith('.mp3')
                assert NotificationStore(link.notifications.path).messages == [result]
                assert client.post('/api/messages', json=submission(), headers=headers).json()['status'] == 'duplicate'
                assert fetch.call_count == 2


def test_control_api_preserves_private_and_reports_missing_configuration(tmp_path):
    link = LinkTest('d'*40, 'c'*40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path/'pending.json')
    headers = {'Authorization':'Bearer '+'c'*40}
    with TestClient(create_app(link_test=link)) as client:
        assert client.post('/api/messages', json=submission(), headers=headers).status_code == 503
        assert link.notifications.messages == []
        assert client.post('/api/messages', json=submission('PRIVATE'), headers=headers).status_code == 200
        assert 'speech' not in link.notifications.messages[0]


def test_failed_provider_request_never_publishes_or_returns_provider_body(tmp_path, speech):
    link = LinkTest('d'*40, 'c'*40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path/'pending.json'); link.speech = speech
    with patch('pinepi_hub.baidu_tts.fetch', side_effect=SpeechError('baidu_connection_failed')):
        with TestClient(create_app(link_test=link)) as client:
            response = client.post('/api/messages', json=submission(), headers={'Authorization':'Bearer '+'c'*40})
            assert response.status_code == 502 and response.json() == {'detail':'baidu_connection_failed'}
            assert link.notifications.messages == []


def test_private_notification_does_not_wait_for_slow_synthesis(tmp_path):
    link = LinkTest('d'*40, 'c'*40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path/'pending.json')
    started, release = threading.Event(), threading.Event()
    def prepare(item):
        started.set()
        assert release.wait(3)
        return dict(item, speech={'url':'https://example.com/read.mp3','mime_type':'audio/mpeg'})
    link.speech = SimpleNamespace(prepare=prepare)
    async def scenario():
        task = asyncio.create_task(link.publish_submission(submission()))
        try:
            assert await asyncio.to_thread(started.wait, 2)
            assert await asyncio.wait_for(link.publish_submission(submission('PRIVATE','private-fast')), timeout=.5)
            assert link.notifications.messages[0]['id'] == 'private-fast'
        finally:
            release.set()
            await task
    asyncio.run(scenario())


def test_critical_keeps_tts_body_only_and_private_never_calls_provider(speech):
    with patch('pinepi_hub.baidu_tts.fetch', side_effect=mock_fetch) as fetch:
        result = speech.prepare(dict(submission(), severity='CRITICAL'))
        assert result['severity'] == 'CRITICAL'
        assert parse_qs(fetch.call_args.args[0].data.decode())['tex'] == [submission()['text']]
        count = fetch.call_count
        private = dict(submission('PRIVATE', 'critical-private'), severity='CRITICAL')
        assert speech.prepare(private) == private
        assert fetch.call_count == count and 'speech' not in private
