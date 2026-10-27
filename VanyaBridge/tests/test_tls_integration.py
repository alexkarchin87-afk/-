"""Real loopback TLS + REST + WebSocket; not an Android/Windows device test."""
import asyncio
import queue
import ssl
import time
import uuid

import httpx
import pytest
from websockets.asyncio.client import connect

from backend.runtime import Runtime


def reply(runtime, action):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        tag, value = runtime.messages.get(timeout=5)
        if tag == 'error': raise AssertionError(value)
        if tag == action: return value
    raise AssertionError('No runtime reply')


def test_real_tls_pair_task_reconnect_revoke(tmp_path):
    runtime = Runtime(port=0, data=tmp_path / 'state')
    runtime.start()
    port = runtime.server.servers[0].sockets[0].getsockname()[1]
    base = f'https://127.0.0.1:{port}'
    context = ssl.create_default_context(cafile=str(runtime.cert))
    context.check_hostname = False  # This test trusts this exact local certificate only.
    (runtime.root / 'test.txt').write_text('real file')
    try:
        with pytest.raises(httpx.ConnectError):
            httpx.get(base+'/snapshot', trust_env=False, timeout=3)  # System CA must reject self-signed server.
        runtime.call('pair_code'); code = reply(runtime, 'pair_code')
        with httpx.Client(base_url=base, verify=context, trust_env=False, timeout=5) as client:
            assert client.get('/snapshot').status_code == 401
            request = client.post('/pair/request', json={'code': code, 'name': 'Integration phone'}).json()
            assert client.post('/pair/claim', json=request).json()['status'] == 'pending'
            runtime.call('pair_decide', request['id'], True); reply(runtime, 'pair_decide')
            identity = client.post('/pair/claim', json=request).json()
            headers = {'Authorization': 'Bearer '+identity['token']}
            command = dict(text='Покажи файлы в моей тестовой папке', mode='Ultra Free', request_id=str(uuid.uuid4()))
            result = client.post('/tasks', json=command, headers=headers)
            assert result.status_code == 200
            deadline = time.monotonic()+5
            while time.monotonic() < deadline:
                snapshot = client.get('/snapshot', headers=headers).json()
                if snapshot['tasks'][0]['state'] == 'COMPLETED': break
                time.sleep(.05)
            assert snapshot['tasks'][0]['result']['files'][0]['name'] == 'test.txt'
            assert client.post('/tasks', json=command, headers=headers).json()['id'] == command['request_id']

            async def websocket_roundtrip():
                # Connect, close and reconnect with the same saved identity.
                for _ in range(2):
                    async with connect(f'wss://127.0.0.1:{port}/events', ssl=context, additional_headers=headers,
                                       proxy=None, open_timeout=5, close_timeout=2) as ws:
                        import json
                        data = json.loads(await asyncio.wait_for(ws.recv(), 3))
                        assert data['tasks'][0]['state'] == 'COMPLETED'
            asyncio.run(websocket_roundtrip())
            runtime.call('revoke', identity['device_id']); reply(runtime, 'revoke')
            assert client.get('/snapshot', headers=headers).status_code == 401
    finally:
        runtime.stop()
    assert not runtime.thread.is_alive()
