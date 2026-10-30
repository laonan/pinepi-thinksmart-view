import asyncio
import json
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient
from pinepi_hub.app import create_app
from pinepi_hub.broadcast_mid import BroadcastMid, read_config
from pinepi_hub.link_test import LinkTest
from pinepi_hub.notifications import NotificationStore, text_notification


def configuration(path, **overrides):
    data = {'url': 'wss://example.com/ws/', 'device': 'pinepi-home-hub', 'token': 'secret-device-token'}
    data.update(overrides)
    path.write_text(json.dumps(data))
    return path


def test_config_requires_secure_url_and_separate_device_token(tmp_path):
    path = configuration(tmp_path / 'config.json')
    assert read_config(path)[1] == 'pinepi-home-hub'
    for values in ({'url':'ws://example.com/ws/'}, {'url':'wss://user:secret@example.com/ws/'},
                   {'url':'wss://example.com/ws/?token=secret'}, {'token':''}, {'device':'bad name'}):
        configuration(path, **values)
        with pytest.raises(ValueError):read_config(path)


def test_only_text_compatibility_and_bounded_frames():
    event = {'event': {'id':'upstream-123', 'title':'标题', 'content':'你好', 'play_now':1}}
    first = text_notification(event)
    assert first == text_notification(event)
    assert first['type'] == 'home.message' and first['text'] == '你好'
    assert first['title'] == '标题' and 'play_now' not in first
    for value in ({'type':'ping'}, {'event':{'content':' '}}, {'event':{'content':['bad']}},
                  {'event':{'content':'x'*8193}}, {'event':{'content':'x','title':'x'*201}}, []):
        assert text_notification(value) is None
    assert text_notification({'content':'same'})['id'] != text_notification({'content':'same'})['id']


def test_offline_pending_is_private_bounded_durable_and_acknowledged(tmp_path):
    path = tmp_path/'pending.json';store = NotificationStore(path)
    for index in range(12):
        store.add(text_notification({'id':str(index), 'content':str(index)}))
    assert len(store.messages) == 10 and store.messages[0]['text'] == '2'
    assert path.stat().st_mode & 0o777 == 0o600
    restored = NotificationStore(path)
    assert restored.messages == store.messages
    assert not restored.add(store.messages[-1])
    restored.acknowledge(store.messages[-1]['id'])
    assert len(NotificationStore(path).messages) == 9


def test_corrupt_pending_state_is_not_silently_discarded(tmp_path):
    path=tmp_path/'pending.json';path.write_text('not json')
    with pytest.raises(ValueError):NotificationStore(path)
    assert path.read_text() == 'not json'


def test_application_ping_and_binary_never_become_notifications():
    async def scenario():
        deliver=AsyncMock();ws=AsyncMock();mid=BroadcastMid('unused',deliver)
        await mid.handle(ws,json.dumps({'type':'ping'}))
        assert json.loads(ws.send.call_args.args[0]) == {'type':'pong'}
        await mid.handle(ws,b'binary-image')
        await mid.handle(ws,'invalid JSON')
        deliver.assert_not_awaited()
        await mid.handle(ws,json.dumps({'event':{'content':'hello'}}))
        deliver.assert_awaited_once()
        assert mid.status() == {'state':'connected','received':1,'ignored':2,'last_rejection':None}
    asyncio.run(scenario())


def test_auth_is_first_frame_and_reconnect_cancels_cleanly(tmp_path):
    path=configuration(tmp_path/'config.json');sent=[];attempts=[]
    class Session:
        async def __aenter__(self):return self
        async def __aexit__(self,*args):pass
        async def send(self,value):sent.append(json.loads(value))
        def __aiter__(self):return self
        async def __anext__(self):raise StopAsyncIteration
    def connect(url,**kwargs):attempts.append((url,kwargs));return Session()
    async def scenario():
        with patch('pinepi_hub.broadcast_mid.connect',connect):
            async with BroadcastMid(path,AsyncMock()):await asyncio.sleep(1.1)
        assert len(attempts) == 2
        assert all(frame == {'token':'secret-device-token','device':'pinepi-home-hub'} for frame in sent)
        assert attempts[0][1]['proxy'] is None
    asyncio.run(scenario())


def test_missing_credentials_keeps_local_service_available(tmp_path):
    mid=BroadcastMid(tmp_path/'not-created.json',AsyncMock())
    with TestClient(create_app(broadcast_mid=mid)) as client:
        assert client.get('/healthz').status_code == 200
        assert mid.state == 'waiting_configuration'


def test_notification_replay_ack_removes_pending_without_test_protocol_regression(tmp_path):
    link=LinkTest('d'*40,'c'*40,trusted_lan=True)
    path=tmp_path/'pending.json';link.notifications=NotificationStore(path)
    item=text_notification({'id':'notification','title':'通知','content':'真实文本兼容格式'})
    link.notifications.add(item)
    control={'Authorization':'Bearer '+'c'*40}
    with TestClient(create_app(link_test=link)) as client:
        with client.websocket_connect('/_test/device') as ws:
            assert ws.receive_json() == item
            ws.send_json({'type':'home.ack','id':item['id'],'wake_requested':False})
            import time
            deadline=time.monotonic()+2
            while client.get('/_test/status',headers=control).json()['last_ack'] is None:
                assert time.monotonic()<deadline;time.sleep(.01)
            assert NotificationStore(path).messages == []
            sent=client.post('/_test/send',headers=control).json()
            assert ws.receive_json()['id'] == sent['id']


def test_live_mid_callback_persists_then_forwards_to_connected_terminal(tmp_path):
    link=LinkTest('d'*40,'c'*40,trusted_lan=True)
    link.notifications=NotificationStore(tmp_path/'pending.json')
    item=text_notification({'id':'live-mid','source':'Hermes','content':'Live upstream forwarding'})
    with TestClient(create_app(link_test=link)) as client:
        with client.websocket_connect('/_test/device') as ws:
            client.portal.call(link.publish_notification,item)
            received=ws.receive_json()
            assert received == item and received['source'] == 'Hermes'
            assert NotificationStore(link.notifications.path).messages == [item]


def test_long_chinese_text_and_utf16_boundaries_are_lossless():
    from pinepi_hub.notifications import decode_notification
    text = '中文长文，保留段落和标点。\n' * 400
    assert len(text.encode('utf-8')) > 4096
    message, reason = decode_notification({'event': {'title':'长文测试','content':text}})
    assert reason is None and message['text'] == text
    assert text_notification({'content':'中'*8192})['text'] == '中'*8192
    assert text_notification({'content':'中'*8193}) is None
    assert text_notification({'content':'😀'*4096}) is not None
    assert text_notification({'content':'😀'*4097}) is None
    assert decode_notification({'content':'中'*8193})[1] == 'content_too_long'


def test_ten_long_notifications_survive_restart_above_old_64k_limit(tmp_path):
    path=tmp_path/'pending.json';store=NotificationStore(path)
    for index in range(10):store.add(text_notification({'id':str(index),'content':'中'*8192}))
    assert path.stat().st_size > 64*1024
    restored=NotificationStore(path)
    assert len(restored.messages)==10 and all(row['text']=='中'*8192 for row in restored.messages)


def test_rejected_text_exposes_reason_without_body():
    async def scenario():
        mid=BroadcastMid('unused',AsyncMock())
        await mid.handle(AsyncMock(),json.dumps({'content':'中'*8193}))
        assert mid.status()['last_rejection']=='content_too_long'
        assert mid.ignored==1 and mid.received==0
    asyncio.run(scenario())


def test_custom_source_and_legacy_fallback():
    assert text_notification({'event':{'source':' Hermes ','content':'hello'}})['source']=='Hermes'
    assert text_notification({'source':'家庭提醒','content':'hello'})['source']=='家庭提醒'
    for event in ({'content':'hello'},{'source':'','content':'hello'},{'source':'  ','content':'hello'}):
        assert text_notification(event)['source']=='Broadcast Mid'


def test_source_validation_matches_terminal_utf16_limit():
    from pinepi_hub.notifications import decode_notification
    assert text_notification({'source':'中'*100,'content':'hello'}) is not None
    assert text_notification({'source':'😀'*50,'content':'hello'}) is not None
    assert decode_notification({'source':'中'*101,'content':'hello'})[1]=='source_too_long'
    assert decode_notification({'source':'😀'*51,'content':'hello'})[1]=='source_too_long'
    assert decode_notification({'source':{'name':'Hermes'},'content':'hello'})[1]=='invalid_source'
