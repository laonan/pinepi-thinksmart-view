import importlib.util
import json
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from pinepi_hub.app import create_app
from pinepi_hub.link_test import LinkTest
from pinepi_hub.message_protocol import ProtocolError, validate_message
from pinepi_hub.notifications import NotificationStore, decode_notification


def message(kind='TEXT', mode='PRIVATE', ident='message-001'):
    item = {'type': 'home.message', 'version': 2, 'id': ident, 'source': '家庭消息',
            'created_at': '2026-10-31T07:30:00+08:00', 'kind': kind, 'title': '标题'}
    if kind == 'TEXT':
        item.update(text='正文', text_mode=mode)
        if mode == 'TTS':
            item['speech'] = {'url': 'http://192.168.31.36:8766/media/voice.mp3', 'mime_type': 'audio/mpeg'}
    else:
        mime, ext = {'IMAGE': ('image/jpeg', 'jpg'), 'AUDIO': ('audio/mpeg', 'mp3'), 'VIDEO': ('video/mp4', 'mp4')}[kind]
        item['media'] = {'url': f'https://example.com/media/file.{ext}', 'mime_type': mime}
    return item


@pytest.mark.parametrize('kind,mode', [('TEXT', 'PRIVATE'), ('TEXT', 'TTS'), ('IMAGE', ''), ('AUDIO', ''), ('VIDEO', '')])
def test_all_five_variants_are_preserved_by_mid_and_disk(tmp_path, kind, mode):
    item = message(kind, mode)
    assert validate_message(item) == item
    for envelope in (item, {'event': item}):
        assert decode_notification(envelope) == (item, None)
    store = NotificationStore(tmp_path / 'pending.json')
    assert store.add(item)
    assert not store.add(item)
    assert NotificationStore(store.path).messages == [item]


@pytest.mark.parametrize('changes', [
    {'text_mode': None}, {'text_mode': 'LEGACY'}, {'speech': None},
    {'speech': {'url': 'https://example.com/private.mp3', 'mime_type': 'audio/mpeg'}},
    {'media': {'url': 'https://example.com/x.jpg', 'mime_type': 'image/jpeg'}},
    {'source': ''}, {'id': 'invalid id'}, {'created_at': '2026-10-31T07:30:00'},
    {'created_at': '2026-02-30T07:30:00Z'}, {'created_at': '1969-12-31T23:59:59Z'},
    {'version': True}, {'version': 2.0}, {'kind': []}, {'text': '\ud800'},
    {'text': '😀' * 4097}, {'title': None}, {'autoplay': True},
])
def test_invalid_or_privacy_conflicting_frames_cannot_fall_back_to_legacy(changes):
    item = message()
    item.update(changes)
    item['content'] = 'legacy fallback must not happen'
    result, reason = decode_notification({'event': item})
    assert result is None and reason


@pytest.mark.parametrize('url', [
    'http://example.com/a', 'http://127.0.0.1/a', 'http://169.254.169.254/a',
    'https://u:p@example.com/a', 'file:///etc/passwd', 'https://example.com/a#fragment',
    'https://example.com:0/a', 'https://example.com:99999/a', 'https://example.com/a\n',
])
def test_media_urls_reject_non_lan_cleartext_credentials_and_local_schemes(url):
    item = message('AUDIO')
    item['media']['url'] = url
    with pytest.raises(ProtocolError): validate_message(item)


def test_tts_requires_audio_and_does_not_accept_wrong_mime_or_extra_asset_fields():
    for change in ({}, {'url': 'https://example.com/a', 'mime_type': 'video/mp4'},
                   {'url': 'https://example.com/a', 'mime_type': 'audio/mpeg', 'token': 'secret'}):
        item = message(mode='TTS')
        item['speech'] = change
        with pytest.raises(ProtocolError): validate_message(item)


def test_publish_auth_validation_offline_replay_and_minimal_v2_ack(tmp_path):
    link = LinkTest('d'*40, 'c'*40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path / 'pending.json')
    headers = {'Authorization': 'Bearer ' + 'c'*40}
    item = message('AUDIO')
    with TestClient(create_app(link_test=link)) as client:
        assert client.post('/api/messages', json=item).status_code == 401
        assert client.post('/api/messages', json={'type': 'home.message'}, headers=headers).status_code == 422
        assert client.post('/api/messages', content=b'x' * (64*1024+1), headers=headers).status_code == 413
        assert client.post('/api/messages', json=item, headers=headers).json()['status'] == 'queued'
        assert client.post('/api/messages', json=item, headers=headers).json()['status'] == 'duplicate'
        with client.websocket_connect('/_test/device') as ws:
            assert ws.receive_json() == item
            ws.send_json({'type': 'home.ack', 'version': 2, 'id': item['id']})
            # Serial portal work runs after the socket handler has processed the acknowledgement.
            import time
            deadline = time.monotonic() + 2
            while NotificationStore(link.notifications.path).messages:
                assert time.monotonic() < deadline
                time.sleep(.01)


def test_prompt_generator_sends_only_four_fixed_phrases_and_leaves_no_credentials(tmp_path):
    spec = importlib.util.spec_from_file_location('generate_notice_audio', Path(__file__).parents[1] / 'tools/generate_notice_audio.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    config = tmp_path/'baidu-tts.json'
    config.write_text(json.dumps({'api_key': 'key', 'secret_key': 'secret'}))
    requests = []
    def fetch(request):
        from urllib.parse import parse_qs
        data = parse_qs(request.data.decode())
        requests.append(data)
        if 'oauth' in request.full_url:
            return 'application/json', b'{"access_token":"token"}'
        return 'audio/mp3', b'ID3' + b'0'*256
    with patch.object(module, 'fetch', fetch):
        manifest = module.generate(config, tmp_path/'raw', 0)
    assert {r['tex'][0] for r in requests[1:]} == set(module.PROMPTS.values())
    assert len(list((tmp_path/'raw').glob('*.mp3'))) == 4
    assert 'token' not in json.dumps(manifest) and 'secret' not in json.dumps(manifest)


def test_failed_prompt_generation_does_not_replace_existing_files(tmp_path):
    spec = importlib.util.spec_from_file_location('generate_notice_audio', Path(__file__).parents[1] / 'tools/generate_notice_audio.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    config = tmp_path/'baidu-tts.json'; config.write_text('{"api_key":"key","secret_key":"secret"}')
    output = tmp_path/'raw'; output.mkdir(); (output/'notice_text.mp3').write_bytes(b'original')
    with patch.object(module, 'fetch', side_effect=[('application/json', b'{"access_token":"token"}'), ('application/json', b'{"error":1}')]):
        with pytest.raises(ValueError): module.generate(config, output, 0)
    assert (output/'notice_text.mp3').read_bytes() == b'original'


def test_documented_message_examples_are_all_valid():
    import re
    document = Path(__file__).parents[2] / 'docs/MESSAGE_PROTOCOL.md'
    examples = [json.loads(raw) for raw in re.findall(r'```json\n(.*?)\n```', document.read_text(), re.S)]
    messages = [example for example in examples if example.get('type') == 'home.message']
    assert {(item['kind'], item.get('text_mode')) for item in messages} == {
        ('TEXT', 'PRIVATE'), ('TEXT', 'TTS'), ('IMAGE', None), ('AUDIO', None), ('VIDEO', None)
    }
    for example in messages:
        validate_message(example)


def test_explicit_media_directory_serves_files_but_not_parent_or_symlink(tmp_path):
    media = tmp_path/'media'; media.mkdir()
    (media/'sample.mp3').write_bytes(b'sample audio')
    private = tmp_path/'secret.json'; private.write_text('private')
    (media/'escape.json').symlink_to(private)
    with TestClient(create_app(media_directory=media)) as client:
        response = client.get('/media/sample.mp3')
        assert response.status_code == 200 and response.headers['content-type'].startswith('audio/mpeg')
        assert client.get('/media/escape.json').status_code == 404
        assert client.get('/media/%2e%2e/secret.json').status_code == 404


@pytest.mark.parametrize('kind,mode', [('TEXT', 'PRIVATE'), ('TEXT', 'TTS'), ('IMAGE', ''), ('AUDIO', ''), ('VIDEO', '')])
@pytest.mark.parametrize('severity', ['NORMAL', 'CRITICAL'])
def test_explicit_severity_survives_mid_and_pending_restart(tmp_path, kind, mode, severity):
    item = dict(message(kind, mode), severity=severity)
    assert validate_message(item) == item
    assert decode_notification({'event': item}) == (item, None)
    store = NotificationStore(tmp_path / 'pending.json')
    assert store.add(item)
    assert NotificationStore(store.path).messages == [item]


@pytest.mark.parametrize('severity', [None, '', 'critical', 'SEVERE', 1, True, [], {}])
def test_invalid_severity_is_rejected_without_legacy_downgrade(severity):
    item = dict(message(), severity=severity)
    with pytest.raises(ProtocolError, match='invalid_severity'):
        validate_message(item)
    item['content'] = 'must not become legacy text'
    assert decode_notification(item)[0] is None
    assert decode_notification({'content': 'legacy', 'severity': severity})[0] is None


def test_missing_severity_remains_ordinary_without_rewriting_frame():
    item = message()
    assert validate_message(item) is item
    assert 'severity' not in item


def test_critical_private_http_and_websocket_preserve_privacy(tmp_path):
    link = LinkTest('d'*40, 'c'*40, trusted_lan=True)
    link.notifications = NotificationStore(tmp_path / 'pending.json')
    item = dict(message(), severity='CRITICAL')
    headers = {'Authorization': 'Bearer ' + 'c'*40}
    with TestClient(create_app(link_test=link)) as client:
        assert client.post('/api/messages', json=item, headers=headers).json()['status'] == 'queued'
        with client.websocket_connect('/_test/device') as ws:
            received = ws.receive_json()
            assert received == item
            assert received['text_mode'] == 'PRIVATE' and 'speech' not in received
