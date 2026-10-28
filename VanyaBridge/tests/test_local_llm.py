import asyncio
import json
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from backend.agents.local_llm import LocalLLM
from backend.engine import Engine
from backend.store import Store

class Tool:
    def __init__(self): self.calls=[]
    async def execute(self, action, argument):
        self.calls.append((action,argument)); return {'text':'OK'}


def test_local_text_provider_real_loopback_and_isolated_context(tmp_path, monkeypatch):
    import backend.agents.local_llm as module
    received=[]
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            received.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            payload=json.dumps({'done':True,'message':{'role':'assistant','content':'Ответ локальной модели'}}).encode()
            self.send_response(200); self.send_header('Content-Length',str(len(payload))); self.end_headers(); self.wfile.write(payload)
        def log_message(self, *args): pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
    monkeypatch.setattr(module, 'OLLAMA_URL', 'http://127.0.0.1:%d/api/chat' % server.server_port)
    store=Store(tmp_path/'history.db')
    try:
        chat=store.new_chat(); other=store.new_chat(); system=Tool(); browser=Tool()
        async def run():
            engine=Engine(store,browser,system); engine.local_llm=LocalLLM('example:latest')
            async def send(text, cid):
                t=engine.submit('desktop',text,'Ultra Free',str(uuid.uuid4()),cid)
                await asyncio.gather(*list(engine.workers.values()))
                return t
            first=await send('Привет',chat['id']); assert first['result']['text']=='Ответ локальной модели'
            await send('другая беседа',other['id'])
            await send('статус',chat['id']); assert len(system.calls)==1 and len(received)==2
            second=await send('продолжим',chat['id']); assert second['state']=='COMPLETED'
            assert received[-1]['messages'][0]['role']=='system'
            assert [m['content'] for m in received[-1]['messages'][1:]]==['Привет','Ответ локальной модели','продолжим']
            assert received[-1]['stream'] is False and 'tools' not in received[-1]
            assert browser.calls==[]
        asyncio.run(run())
    finally:
        store.close(); server.shutdown(); server.server_close(); thread.join()


def test_bad_model_names_rejected():
    for name in ('', '../escape', 'model\nnew', 'https://example.com?q=x', 'model:latest-cloud'):
        with pytest.raises(ValueError): LocalLLM(name)


def test_unavailable_local_service_and_invalid_tool_result(tmp_path, monkeypatch):
    import backend.agents.local_llm as module
    store=Store(tmp_path/'history.db')
    class Broken:
        async def execute(self,*args): return {}
    try:
        async def run():
            engine=Engine(store,Tool(),Broken()); engine.local_llm=LocalLLM('small')
            monkeypatch.setattr(module,'OLLAMA_URL','http://127.0.0.1:1/api/chat')
            a=engine.submit('desktop','привет','Ultra Free',str(uuid.uuid4()))
            b=engine.submit('desktop','статус','Ultra Free',str(uuid.uuid4()))
            await asyncio.gather(*list(engine.workers.values()))
            assert a['state']=='FAILED' and 'Ollama' in a['messages'][-1]['text']
            assert b['state']=='FAILED' and 'корректный результат' in b['messages'][-1]['text']
        asyncio.run(run())
    finally: store.close()


def test_cancelled_llm_does_not_deliver_late_text(tmp_path):
    store=Store(tmp_path/'history.db')
    class LateProvider:
        def __init__(self): self.started=asyncio.Event()
        async def respond(self, messages):
            self.started.set()
            try: await asyncio.sleep(30)
            except asyncio.CancelledError: return 'late response'
    try:
        async def run():
            engine=Engine(store,Tool(),Tool()); provider=LateProvider(); engine.local_llm=provider
            task=engine.submit('desktop','привет','Ultra Free',str(uuid.uuid4()))
            await provider.started.wait()
            engine.cancel(task['id'],'desktop')
            await asyncio.gather(*list(engine.workers.values()),return_exceptions=True)
            assert task['state']=='CANCELLED' and task['result'] is None
            assert 'late response' not in str(task['messages'])
        asyncio.run(run())
    finally: store.close()
